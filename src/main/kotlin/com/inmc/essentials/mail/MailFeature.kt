package com.inmc.essentials.mail

import com.inmc.essentials.Ess
import com.inmc.essentials.feature.Feature
import com.inmc.essentials.feature.Perm
import com.inmc.essentials.service.CmiData
import com.inmc.essentials.service.CmiImportable
import com.inmc.essentials.service.ImportReport
import com.inmc.essentials.verify.Check
import kr.inmc.core.integration.Mail
import kr.inmc.core.store.SqlSource
import com.inmc.essentials.service.ModerationState
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.permissions.PermissionDefault
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 우편(2026-10-10) — 카탈로그 cmd4:sys-mail. **core `Mail` 의 공급처**: 업적·낚시·숫자게임의 core 우편함, 보상 서비스의 "가방이 찼다", 커스텀아이템·
 * urb·던전의 지급이 전부 여기 한 우편함으로 온다. 사람끼리의 편지(CMI /mail)도 같은 우편함에. 운영 문서 `docs/mail.md`.
 *
 * 꽂기([Mail.register])는 [enable] 에서 저장소를 다 읽은 **뒤** — 꽂히는 순간 core 우편함들이 남은 것을 넘긴다. 떼기는 [disable].
 */
class MailFeature(ess: Ess) : Feature(ess), CmiImportable {

    override val id = "mail"

    override val hasSettings = true

    override val permissions: List<Perm> = listOf(
        Perm(USE, PermissionDefault.TRUE, "/우편 — 내 우편함(보상·편지 받기, 읽기, 지우기)"),
        Perm(SEND, PermissionDefault.TRUE, "/우편 보내기 · 임시 — 편지(글만)"),
        Perm(ATTACH, PermissionDefault.OP, "/우편 첨부 — 손에 든 아이템을 붙여 보내기"),
        Perm(COLOR, PermissionDefault.OP, "편지 글에 MiniMessage·색"),
        Perm(OTHERS, PermissionDefault.OP, "/우편 보기 · 비우기 <플레이어> — 남의 우편함"),
        Perm(ALL, PermissionDefault.OP, "/우편전체 — 최근 접속한 모두에게 편지 · 회수"),
    )

    override val messageDefaults: Map<String, String> = MailMessages.ALL

    @Volatile
    var settings: MailSettings = MailSettings()
        private set

    internal lateinit var store: MailStore
    internal lateinit var service: MailService
    private var listener: MailListener? = null

    /** 플레이어 → 마지막으로 편지를 보낸 때(쿨타임 — 메모리만, 나가면 지운다). */
    internal val lastSent = ConcurrentHashMap<UUID, Long>()

    /** 이번 실행에 마지막으로 보낸 /우편전체 묶음 열쇠(회수가 "마지막 것" 을 고를 때 — 재시작 뒤에는 남은 편지·묘비로). */
    @Volatile
    internal var lastBatch: String? = null

    /** 우리가 꽂은 공급처 — 뗄 때 우리 것만(core `Mail.unregister` 도 같은 것만 뗀다). */
    private var registered: Mail.Provider? = null

    override fun schema(sql: SqlSource) {
        MailStore(ess).schema(sql)
    }

    override fun load(settings: YamlConfiguration) {
        this.settings = MailSettings.from(settings)
    }

    override fun enable() {
        store = MailStore(ess)
        store.loadAll()
        val purged = store.purgeExpired(System.currentTimeMillis())
        if (purged > 0) ess.logger.info("만료된 편지 ${purged}통을 지웠습니다")
        service = MailService(ess, this)
        val mailListener = MailListener(ess, this)
        listener = mailListener
        ess.listen(mailListener)
        MailCommands(ess, this).register()
        ess.ticks.add("mail-만료", 60) { now ->
            val n = store.purgeExpired(now)
            if (n > 0) ess.logger.info("만료된 편지 ${n}통을 지웠습니다")
        }
        store.pruneSpent(System.currentTimeMillis())
        ess.ticks.add("mail-묘비", 3600) { now -> store.pruneSpent(now) }
        Mail.register(service)
        registered = service
    }

    override fun disable() {
        registered?.let { Mail.unregister(it) }
        registered = null
    }

    // --- CMI 가져오기 ------------------------------------------------------------------------------

    /**
     * CMI 우편(사람마다 보낸 사람·시각·글). 같은 편지를 두 번 넣지 않게 열쇠([MailRules.cmiKey])로 넣는다(다시 가져와도 한 통 — 지운 뒤에도
     * 묘비가 있는 동안은). 이미 만료(설정의 일수)된 편지는 건너뛰고 까닭을 남긴다. CMI 글은 `&` 색이 섞여 있어 색이 있는 편지(rich)로 넣는다 —
     * 읽을 때 색·꾸밈만 살린다(클릭·호버는 글자 그대로 — [MailText]). 편지함 한도는 넘어서도 넣는다(옮기는 것이지 새로 보내는 것이 아니다).
     *
     * CMI 우편의 보관 기간(`keepFor` — sendtemp·mailall 의 4번째 칸, 초)은 [CmiMail.keepForSeconds] 로 온다 — 있으면 `보낸 시각 + keepFor` 가 만료(CMI 와 같다),
     * 없으면 설정 일수. 이미 만료된 편지는 건너뛰고 까닭을 남긴다.
     */
    override fun importCmi(data: CmiData, report: ImportReport) {
        if (!::store.isInitialized) return report.skipped(KIND, "mail 기능이 켜지지 않았습니다")
        val now = System.currentTimeMillis()
        for (user in data.users) {
            for ((index, mail) in user.mail.withIndex()) {
                report.read(KIND)
                val text = mail.message.trim()
                if (text.isEmpty()) {
                    report.unreadable(KIND, "${user.name} 의 우편 — 글이 비었습니다(보낸 사람 ${mail.sender ?: "?"}, 시각 ${mail.time ?: "?"})")
                    continue
                }
                if (mail.time == null) report.note("${user.name} 의 우편(보낸 사람 ${mail.sender ?: "?"}) — 보낸 시각이 없어 가져온 때를 시각으로 씁니다")
                val at = mail.time ?: now
                val sender = mail.sender?.trim()?.takeIf { it.isNotEmpty() }
                val senderId = sender?.let { name -> ess.users.byName(name)?.id ?: runCatching { UUID.fromString(name) }.getOrNull() }
                val senderName = senderId?.let { ess.users.get(it)?.name } ?: sender ?: settings.consoleSender
                val parcel = Mail.Parcel(
                    kind = Mail.Kind.LETTER, origin = LETTER_ORIGIN,
                    source = ess.messages.raw("mail-source-letter").replace("{플레이어}", senderName).replace("{player}", senderName),
                    senderId = senderId, senderName = senderName, text = text, at = at,
                    expiresAt = mail.keepForSeconds?.let { at + it * 1000L },
                    key = MailRules.cmiKey(sender, mail.time, text, index),
                )
                val expires = MailRules.expiryOf(parcel, settings)
                if (expires != null && expires <= now) {
                    val why = if (mail.keepForSeconds != null) "CMI 보관 기간(${mail.keepForSeconds}초)이 지났습니다" else "보관 ${settings.letterDays}일이 지났습니다"
                    report.skipped(KIND, "${user.name} 의 우편(${service.date(at)}, ${senderName}) — $why")
                    continue
                }
                when (val admit = service.depositEntry(user.id, parcel, rich = true, force = true).first) {
                    is MailRules.Admit.Accept -> report.imported(KIND)
                    MailRules.Admit.Duplicate -> report.skipped(KIND, "${user.name} 의 우편(${service.date(at)}) 은(는) 이미 가져왔습니다")
                    else -> report.skipped(KIND, "${user.name} 의 편지함이 가득 차 넣지 못했습니다($admit) — 한도 settings/mail.yml letters.max-per-player")
                }
            }
        }
    }

    // --- 검증기 ------------------------------------------------------------------------------------

    override fun checks(): List<Check> = listOf(
        Check("우편 공급처가 이 기능이다") { _ ->
            if (!Mail.available) "core Mail 에 공급처가 없습니다" else if (registered == null) "이 기능이 꽂지 않았습니다" else null
        },
        Check("같은 origin·key 의 우편은 한 통(메모리만)") { p ->
            val parcel = Mail.Parcel(kind = Mail.Kind.REWARD, origin = PROBE_ORIGIN, source = "검증", commands = listOf("say ess-verify"), key = "verify-" + UUID.randomUUID())
            val first = store.deposit(p.uniqueId, parcel, settings, rich = false, persist = false)
            try {
                val second = store.deposit(p.uniqueId, parcel, settings, rich = false, persist = false)
                when {
                    first.first !is MailRules.Admit.Accept -> "첫 넣기가 받아지지 않았습니다: ${first.first}"
                    second.first != MailRules.Admit.Duplicate -> "두 번째 넣기가 겹침으로 걸러지지 않았습니다: ${second.first}"
                    service.count(p.uniqueId, PROBE_ORIGIN) != 1 -> "세기가 1이 아닙니다: ${service.count(p.uniqueId, PROBE_ORIGIN)}"
                    else -> null
                }
            } finally {
                store.remove(p.uniqueId, store.of(p.uniqueId).filter { it.parcel.origin == PROBE_ORIGIN }.map { it.id }, persist = false)
            }
        },
        Check("만료 지우기 — 편지는 지우고 보상·첨부가 남은 편지는 남긴다(메모리만)") { p ->
            val old = System.currentTimeMillis() - 400L * MailRules.DAY
            val letter = Mail.Parcel(kind = Mail.Kind.LETTER, origin = PROBE_ORIGIN, source = "검증", text = "ess-verify", at = old, expiresAt = old + 1)
            val attached = letter.copy(text = "ess-verify-attached", commands = listOf("say ess-verify"))
            val reward = Mail.Parcel(kind = Mail.Kind.REWARD, origin = PROBE_ORIGIN, source = "검증", commands = listOf("say ess-verify"), at = old, expiresAt = old + 1)
            for (parcel in listOf(letter, attached, reward)) store.deposit(p.uniqueId, parcel, settings, rich = false, persist = false, force = true)
            try {
                val now = System.currentTimeMillis()
                val expired = store.of(p.uniqueId).filter { it.parcel.origin == PROBE_ORIGIN && MailRules.expired(it, now) }
                when {
                    expired.none { it.isLetter && !it.hasContent } -> "오래된 편지가 만료로 보이지 않습니다"
                    expired.any { !it.isLetter } -> "보상이 만료로 보입니다(보상은 만료 없음)"
                    expired.any { it.hasContent } -> "받을 것이 남은 편지가 만료로 보입니다(지우면 첨부가 사라진다)"
                    else -> null
                }
            } finally {
                store.remove(p.uniqueId, store.of(p.uniqueId).filter { it.parcel.origin == PROBE_ORIGIN }.map { it.id }, persist = false)
            }
        },
        Check("다 받아 지운 열쇠 우편은 다시 넘겨도 한 통(묘비 — 메모리만)") { p ->
            val parcel = Mail.Parcel(kind = Mail.Kind.REWARD, origin = PROBE_ORIGIN, source = "검증", commands = listOf("say ess-verify"), key = "verify-" + UUID.randomUUID())
            try {
                val first = store.deposit(p.uniqueId, parcel, settings, rich = false, persist = false).second
                    ?: return@Check "첫 넣기가 받아지지 않았습니다"
                store.remove(p.uniqueId, listOf(first.id), persist = false, tombstone = true)
                val again = store.deposit(p.uniqueId, parcel, settings, rich = false, persist = false).first
                if (again == MailRules.Admit.Duplicate) null else "지운 뒤 다시 넣은 것이 겹침으로 걸러지지 않았습니다: $again"
            } finally {
                store.remove(p.uniqueId, store.of(p.uniqueId).filter { it.parcel.origin == PROBE_ORIGIN }.map { it.id }, persist = false)
                store.forgetSpent(p.uniqueId, PROBE_ORIGIN)
            }
        },
        Check("그림자 뮤트된 사람의 편지는 보낸 척만 하고 넣지 않는다(접점을 메모리에서만 바꿈)") { p ->
            val before = ess.moderation
            val target = UUID.randomUUID()
            ess.moderation = object : ModerationState by ModerationState.NONE {
                override fun isShadowMuted(id: UUID): Boolean = id == p.uniqueId
                override fun isMuted(id: UUID): Boolean = id == p.uniqueId
            }
            try {
                val sent = service.sendLetter(p, target, "ess-verify", persist = false)
                when {
                    sent != MailService.Sent.SHADOW -> "그림자 뮤트인데 결과가 $sent 입니다"
                    store.of(target).isNotEmpty() -> "그림자 뮤트인데 편지가 들어갔습니다"
                    else -> null
                }
            } finally {
                ess.moderation = before
                store.remove(target, store.of(target).map { it.id }, persist = false)
            }
        },
        Check("편지 보내기 — 들어가고, 받는 사람이 무시하면 넣지 않는다(메모리만)") { p ->
            val before = ess.moderation
            val target = UUID.randomUUID()
            ess.moderation = ModerationState.NONE
            try {
                val ok = service.sendLetter(p, target, "ess-verify", persist = false)
                val entry = store.of(target).singleOrNull()
                service.ignoreOverride = { receiver, sender -> receiver == target && sender == p.uniqueId }
                val ignored = service.sendLetter(p, target, "ess-verify-ignored", persist = false)
                when {
                    ok != MailService.Sent.OK -> "보통 편지가 $ok 입니다"
                    entry == null || !entry.isLetter || entry.parcel.origin != LETTER_ORIGIN -> "편지가 편지 출처($LETTER_ORIGIN)로 들어가지 않았습니다: $entry"
                    ignored != MailService.Sent.IGNORED -> "무시하는 사람에게 보낸 편지가 $ignored 입니다"
                    store.of(target).size != 1 -> "무시하는 사람에게 편지가 들어갔습니다"
                    else -> null
                }
            } finally {
                service.ignoreOverride = null
                ess.moderation = before
                store.remove(target, store.of(target).map { it.id }, persist = false)
            }
        },
        Check("공용 DB 와 맞추지 못한 사람은 받기를 거절(메모리만)") { p ->
            if (!store.isSynced(p.uniqueId)) return@Check null
            val parcel = Mail.Parcel(kind = Mail.Kind.REWARD, origin = PROBE_ORIGIN, source = "검증", commands = listOf("say ess-verify"))
            store.deposit(p.uniqueId, parcel, settings, rich = false, persist = false)
            store.markUnsynced(p.uniqueId)
            try {
                val result = service.claimAll(p, PROBE_ORIGIN, tell = false)
                when {
                    result.claimed != 0 -> "맞추지 못했는데 ${result.claimed}통을 줬습니다"
                    store.of(p.uniqueId).none { it.parcel.origin == PROBE_ORIGIN } -> "맞추지 못했는데 우편이 사라졌습니다"
                    else -> null
                }
            } finally {
                store.clearUnsynced(p.uniqueId)
                store.remove(p.uniqueId, store.of(p.uniqueId).filter { it.parcel.origin == PROBE_ORIGIN }.map { it.id }, persist = false)
            }
        },
        Check("접속 알림 — 안 읽은 편지·받을 것을 센다") { p ->
            val letter = MailEntry("a", p.uniqueId, Mail.Parcel(kind = Mail.Kind.LETTER, origin = LETTER_ORIGIN, source = "검증", text = "x"))
            val read = letter.copy(id = "b", readAt = 1L)
            val reward = MailEntry("c", p.uniqueId, Mail.Parcel(kind = Mail.Kind.REWARD, origin = PROBE_ORIGIN, source = "검증", commands = listOf("x")))
            val counts = MailRules.joinCounts(listOf(letter, read, reward))
            when {
                counts != (1 to 1) -> "(안 읽은 편지, 받을 것)이 (1, 1) 이 아니라 $counts"
                ess.messages.raw("mail-join").isBlank() -> "접속 알림 메시지(mail-join)가 비었습니다"
                else -> null
            }
        },
        Check("편지 고르기 — 숫자뿐인 id 도 #id 로 고른다") { p ->
            val numeric = MailEntry("40213378-0000-0000-0000-000000000000", p.uniqueId, Mail.Parcel(kind = Mail.Kind.LETTER, origin = LETTER_ORIGIN, source = "검증"))
            val picked = MailRules.pick(listOf(numeric), listOf(numeric), "#${numeric.handle}")
            if (picked == numeric) null else "#${numeric.handle} 로 고르지 못했습니다"
        },
        Check("편지 글은 색만 살리고 클릭 태그는 글자 그대로") { _ ->
            val body = MailText.body("<click:run_command:'/op me'><red>ess-verify", rich = true)
            val clicks = generateSequence(listOf(body)) { level -> level.flatMap { it.children() }.takeIf { it.isNotEmpty() } }.flatten().mapNotNull { it.clickEvent() }.toList()
            if (clicks.isEmpty()) null else "편지 글에 클릭이 살아 있습니다: $clicks"
        },
        Check("접속 알림 리스너가 걸려 있다") { _ ->
            val mine = listener ?: return@Check "리스너가 없습니다"
            if (PlayerJoinEvent.getHandlerList().registeredListeners.any { it.listener === mine }) null else "PlayerJoinEvent 에 걸려 있지 않습니다"
        },
        Check("받기 규칙 — 가방이 다 받으면 명령까지, 아니면 아이템만 남긴다") { _ ->
            val parcel = Mail.Parcel(kind = Mail.Kind.REWARD, origin = PROBE_ORIGIN, source = "검증", items = listOf(byteArrayOf(1), byteArrayOf(2)), money = 5.0, commands = listOf("x"))
            val full = MailRules.claim(parcel, give = { emptyList() }, pay = { _, _ -> true })
            val tight = MailRules.claim(parcel, give = { if (it[0].toInt() == 2) listOf(it) else emptyList() }, pay = { _, _ -> true })
            when {
                !full.done || full.commands != listOf("x") -> "다 들어갔는데 끝나지 않았거나 명령이 없습니다"
                tight.done || !tight.partial || tight.rest.items.size != 1 || tight.commands.isNotEmpty() -> "가방이 모자랄 때 남기기가 틀렸습니다"
                else -> null
            }
        },
        Check("우편함 화면이 열린다") { p ->
            MailMenu(ess, p, this, p.uniqueId, p.name).show()
            val ok = p.openInventory.topInventory.holder is MailMenu
            p.closeInventory()
            if (ok) null else "화면이 열리지 않았습니다"
        },
        Check("공급처가 우편 수를 메모리에서 센다(core Mail.count)") { p ->
            val n = Mail.count(p.uniqueId)
            val own = store.of(p.uniqueId).size
            if (Bukkit.isPrimaryThread() && n != own) "core Mail.count($n) 와 우편함($own)이 다릅니다" else null
        },
    )

    companion object {
        const val USE = "inmcessentials.mail.use"
        const val SEND = "inmcessentials.mail.send"
        const val ATTACH = "inmcessentials.mail.attach"
        const val COLOR = "inmcessentials.mail.color"
        const val OTHERS = "inmcessentials.mail.others"
        const val ALL = "inmcessentials.mail.all"

        /**
         * 사람끼리의 편지의 origin — essentials 자신의 보상(core 우편함 · CLAUDE.md 9 의 `inmc-essentials`)과 갈라 둔다. 같으면 core `Mailbox` 의
         * origin 걸러내기(세기·받기·관리자 초기화의 discard)에 편지가 섞인다.
         */
        const val LETTER_ORIGIN = "inmc-essentials:letter"

        private const val KIND = "우편"
        private const val PROBE_ORIGIN = "inmc-essentials-verify"
    }
}
