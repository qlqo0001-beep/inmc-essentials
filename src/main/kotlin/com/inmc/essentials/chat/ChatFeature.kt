package com.inmc.essentials.chat

import com.inmc.essentials.Ess
import com.inmc.essentials.feature.Feature
import com.inmc.essentials.feature.Perm
import com.inmc.essentials.service.CmiData
import com.inmc.essentials.service.CmiImportable
import com.inmc.essentials.service.ImportReport
import com.inmc.essentials.service.ModerationState
import com.inmc.essentials.verify.Check
import kr.inmc.core.integration.PlayerSettings
import kr.inmc.core.store.SqlSource
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.permissions.PermissionDefault
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiPredicate

/**
 * 채팅(2026-10-10) — 카탈로그 set1:chat-private-message · cmd3:cmd-ignore · cmd3:cmd-helpop · cmd7:cmd-staffmsg · cmd5:cfg-spy · post:sign-spy ·
 * post:chat-spy · set1:filter-duplicate-spam · set1:chat-ranged-shout · cmd1:chat-broadcast · cmd7:cmd-silence · set1:chat-link-shorten · cmd7:cmd-titlemsg.
 * 운영 문서 `docs/chat.md`.
 *
 * 채팅 순서 계약(계획 6절 · CLAUDE.md 10)을 지킨다 — 렌더러는 부르지 않는다. 귓속말 받기·소리·전체 채팅 끄기는 core 개인 설정
 * (`PlayerSettings` — 플레이어 메뉴 `/설정` 에 저절로 뜬다), 무시 목록·스파이는 DB.
 *
 * [BiPredicate] 는 mail 기능이 "받는 사람이 보낸 사람을 무시하나" 를 묻는 자리다(기능끼리 import 하지 않는 규칙 — `test(받는 사람, 보낸 사람)`).
 * 공용 접점(`ess.ignores`)이 생기면 그리로 옮긴다.
 */
class ChatFeature(ess: Ess) : Feature(ess), CmiImportable, BiPredicate<UUID, UUID> {

    override val id = "chat"

    override val hasSettings = true

    override val permissions: List<Perm> = listOf(
        Perm(MSG, PermissionDefault.TRUE, "/귓 — 귓속말"),
        Perm(REPLY, PermissionDefault.TRUE, "/답장"),
        Perm(MSG_CLEAN, PermissionDefault.OP, "귓속말 앞 ! — 보낸 이름 없는 깨끗한 글"),
        Perm(MSG_NOREPLY, PermissionDefault.OP, "귓속말 앞 !- — 깨끗하고 답장 못 하는 글"),
        Perm(MSG_BYPASS, PermissionDefault.OP, "귓속말을 끈 사람·그룹 제한에도 보내기"),
        Perm(COLOR, PermissionDefault.OP, "귓속말·도움요청·스태프 글에 색(&a · <red>)"),
        Perm(IGNORE, PermissionDefault.TRUE, "/무시 — 귓속말·공개 채팅·편지 막기"),
        Perm(IGNORE_OTHERS, PermissionDefault.OP, "/무시 -p:<이름> — 남의 무시 목록"),
        Perm(IGNORE_BYPASS, PermissionDefault.OP, "무시를 뚫는다(운영진)"),
        Perm(HELPOP, PermissionDefault.TRUE, "/도움요청"),
        Perm(HELPOP_RECEIVE, PermissionDefault.OP, "도움요청을 받는다"),
        Perm(STAFF, PermissionDefault.OP, "/스태프 — 스태프 채널 보내기·받기"),
        Perm(SPY, PermissionDefault.OP, "/스파이 — 명령·표지판·귓속말 스파이"),
        Perm(SPY_OTHERS, PermissionDefault.OP, "/스파이 <종류> <켜기|끄기> <플레이어>"),
        Perm(SPY_ALL, PermissionDefault.OP, "명령 스파이의 보기 목록(only-commands)을 무시하고 전부"),
        Perm(SPY_BYPASS, PermissionDefault.OP, "명령 스파이의 숨김 목록(hidden-commands — 로그인 등)까지 본다"),
        Perm(SPY_EXEMPT, PermissionDefault.FALSE, "이 사람의 명령·표지판은 스파이에게 안 보인다"),
        Perm(SPAM_BYPASS, PermissionDefault.OP, "도배 막기 면제"),
        Perm(SHOUT, PermissionDefault.TRUE, "! 로 외치기(거리 채팅이 켜져 있을 때)"),
        Perm(SHOUT_FREE, PermissionDefault.OP, "외침 비용 면제"),
        Perm(RANGE_BYPASS, PermissionDefault.OP, "내 채팅은 거리 제한 없이 모두에게"),
        Perm(RANGE_SPY, PermissionDefault.OP, "채팅 스파이를 켜면 거리 밖 채팅도 [범위 밖] 으로 본다"),
        Perm(BROADCAST, PermissionDefault.OP, "/공지"),
        Perm(SILENCE_USE, PermissionDefault.TRUE, "/채팅끄기 — 내 화면에서 공개 채팅 끄기"),
        Perm(SILENCE_BYPASS, PermissionDefault.OP, "채팅을 끈 사람에게도 내 채팅이 보인다"),
        Perm(TITLE, PermissionDefault.OP, "/타이틀"),
    )

    override val messageDefaults: Map<String, String> = ChatMessages.ALL

    @Volatile
    var settings: ChatSettings = ChatSettings()
        private set

    internal lateinit var store: ChatStore
    internal lateinit var service: ChatService
    internal lateinit var listener: ChatListener

    internal val spam = SpamGuard()
    internal val book = ReplyBook()

    /** 스태프 모드(공개 채팅이 스태프 채널로) — 메모리만, 재시작하면 꺼진다. */
    internal val staffMode: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    private var settingsRegistered = false

    private var papi: ChatPapi? = null

    override fun schema(sql: SqlSource) {
        ChatStore(ess).schema(sql)
    }

    override fun load(settings: YamlConfiguration) {
        this.settings = ChatSettings.from(settings)
    }

    override fun enable() {
        store = ChatStore(ess)
        store.loadAll()
        service = ChatService(ess, this)
        listener = ChatListener(ess, this, service)
        ess.listen(listener)
        ChatCommands(ess, this, service).register()
        registerSettings()
    }

    /** PlaceholderAPI(`%inmcchat_range%`) — 다른 플러그인이 다 켜진 뒤에. */
    override fun ready() {
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) papi = ChatPapi(ess, this).also { it.setup() }
    }

    override fun disable() {
        papi?.teardown()
        papi = null
        if (settingsRegistered) PlayerSettings.unregisterAll(SETTINGS_OWNER)
        settingsRegistered = false
    }

    /** "받는 사람 [receiver] 가 보낸 사람 [sender] 를 무시하나"(mail 기능이 편지를 막을 때 — 클래스 KDoc). */
    override fun test(receiver: UUID, sender: UUID): Boolean =
        ::store.isInitialized && store.ignores(receiver, sender) && Bukkit.getPlayer(sender)?.hasPermission(IGNORE_BYPASS) != true

    private fun registerSettings() {
        PlayerSettings.register(
            PlayerSettings.Setting(ACCEPT_PM, SETTINGS_OWNER, "귓속말 받기", Material.MAP, listOf("끄면 귓속말이 오지 않습니다(운영진은 예외)."), PlayerSettings.Toggle(true)),
        )
        PlayerSettings.register(
            PlayerSettings.Setting(PM_SOUND, SETTINGS_OWNER, "귓속말 소리", Material.STONE_BUTTON, listOf("귓속말을 받을 때 소리를 냅니다."), PlayerSettings.Toggle(true)),
        )
        PlayerSettings.register(
            PlayerSettings.Setting(
                SILENCE, SETTINGS_OWNER, "공개 채팅 끄기", Material.BARRIER,
                listOf("켜면 내 화면에 공개 채팅이 안 보입니다.", "귓속말·공지·운영진 채팅은 그대로 옵니다.", "/채팅끄기 와 같습니다."), PlayerSettings.Toggle(false),
            ),
        )
        settingsRegistered = true
    }

    // --- CMI 가져오기 -----------------------------------------------------------------------------

    /**
     * CMI 무시 목록(사람마다 uuid 목록)과 개인 설정(`users.Options` — 서버 기능이 모르는 열이라 `CmiUser.extra["Options"]` 로 온다).
     * 무시: 이미 있으면 건너뛴다. 모르는 uuid 도 그대로 넣는다(나중에 온 사람도 막혀야 한다). CMI `/ignore all` 은 목록 속의 특별한 uuid
     * ([ChatRules.CMI_IGNORE_ALL])라 우리 "모두 무시"([ChatStore.ALL])로 바꿔 넣는다.
     * 설정: 스파이(chatSpy·cmdSpy·signSpy)는 켜진 것만 켜고, 귓속말 받기(acceptingPM)·소리(pmSound)는 꺼진 것만 core 개인 설정에 끈다(기본값과 같은 것은
     * 쓰지 않는다). 다른 칸(acceptingTPA 등)은 이 기능의 것이 아니다.
     */
    override fun importCmi(data: CmiData, report: ImportReport) {
        if (!::store.isInitialized) return report.skipped(KIND, "chat 기능이 켜지지 않았습니다")
        for (user in data.users) {
            for (target in user.ignores) {
                report.read(KIND)
                if (target == user.id) {
                    report.skipped(KIND, "${user.name} 이(가) 자기 자신을 무시 — 뺍니다")
                    continue
                }
                val ours = ChatRules.cmiIgnoreTarget(target)
                val label = if (ours == ChatStore.ALL) "모두 무시(ignore all)" else target.toString()
                if (store.addIgnore(user.id, ours)) report.imported(KIND)
                else report.skipped(KIND, "${user.name} → $label 은(는) 이미 무시 중")
                if (ours != ChatStore.ALL && ess.users.get(target) == null) {
                    report.note("${user.name} 이(가) 무시하던 $target 은(는) 이 서버에 온 기록이 없습니다 — 그대로 넣습니다")
                }
            }
            importOptions(user.id, user.name, user.extra["Options"], report)
        }
    }

    private fun importOptions(id: UUID, name: String, raw: String?, report: ImportReport) {
        val options = ChatRules.cmiOptions(raw) ?: return
        report.read(KIND_OPTIONS)
        for (bad in options.bad) report.unreadable(KIND_OPTIONS, "$name — $bad (원문 ${raw?.take(120)})")
        val done = ArrayList<String>()
        for ((option, kind) in listOf("chatSpy" to ChatStore.Spy.CHAT, "cmdSpy" to ChatStore.Spy.COMMAND, "signSpy" to ChatStore.Spy.SIGN)) {
            if (options.values[option] == true && !store.spying(id, kind)) {
                store.setSpy(id, kind, true)
                done += option
            }
        }
        for ((option, key) in listOf("acceptingPM" to ACCEPT_PM, "pmSound" to PM_SOUND)) {
            if (options.values[option] != false) continue
            if (PlayerSettings.set(id, key, false)) done += "$option=끔"
            else report.unreadable(KIND_OPTIONS, "$name — $option=끔 을 개인 설정 $key 에 넣지 못했습니다")
        }
        if (done.isEmpty()) report.skipped(KIND_OPTIONS, "$name — 기본값과 다른 채팅 설정이 없습니다")
        else {
            report.imported(KIND_OPTIONS)
            report.note("$name 의 CMI 설정: ${done.joinToString(", ")}")
        }
    }

    // --- 검증기 -----------------------------------------------------------------------------------

    override fun checks(): List<Check> = listOf(
        Check("개인 설정(귓속말 받기·소리·채팅 끄기)이 올라가 있다") { _ ->
            val missing = listOf(ACCEPT_PM, PM_SOUND, SILENCE).filter { PlayerSettings.get(it) == null }
            if (missing.isEmpty()) null else "올라가지 않음: $missing"
        },
        Check("/minecraft:msg 는 우리 귓속말로 바뀐다(처리기 직접 — 명령 스파이·기록에 남지 않게)") { p ->
            if (PlayerCommandPreprocessEvent.getHandlerList().registeredListeners.none { it.listener === listener }) {
                return@Check "명령 사건에 리스너가 걸려 있지 않습니다"
            }
            val event = PlayerCommandPreprocessEvent(p, "/minecraft:msg ${p.name} ess-verify")
            listener.onVanillaWhisper(event)
            if (event.message.startsWith("/$WHISPER ")) null else "바뀌지 않았습니다: ${event.message}"
        },
        Check("표지판 스파이 리스너가 걸려 있다") { _ ->
            if (SignChangeEvent.getHandlerList().registeredListeners.any { it.listener === listener }) null else "표지판 사건에 리스너가 걸려 있지 않습니다"
        },
        Check("답장 — 시간 안의 대화 상대가 새로 보낸 사람보다 먼저") { _ ->
            val me = UUID.randomUUID()
            val a = UUID.randomUUID()
            val b = UUID.randomUUID()
            val now = System.currentTimeMillis()
            val book = ReplyBook()
            book.sent(me, a, now)
            book.received(me, b, now + 1000)
            val inTime = book.target(me, now + 2000, settings)
            val late = book.target(me, now + settings.replyTimeoutSeconds * 1000L + 5000, settings)
            when {
                settings.replyToLastMessenger -> if (inTime == b) null else "ReplyToLastMessenger 인데 마지막 보낸 사람이 아닙니다"
                inTime != a -> "시간 안인데 대화 상대(a)가 아니라 $inTime"
                late != b -> "시간이 지났는데 마지막 보낸 사람(b)이 아니라 $late"
                else -> null
            }
        },
        Check("무시하면 공개 채팅·편지에서 빠진다(메모리만)") { p ->
            val stranger = UUID.randomUUID()
            val added = store.addIgnoreMemory(p.uniqueId, stranger.toString())
            try {
                val drop = listener.dropOf(p, stranger, ignoreBypass = false, silenceBypass = true, origin = p.location, range = null)
                val bypass = listener.dropOf(p, stranger, ignoreBypass = true, silenceBypass = true, origin = p.location, range = null)
                when {
                    !store.ignores(p.uniqueId, stranger) -> "무시 목록에 넣었는데 무시로 안 봅니다"
                    !test(p.uniqueId, stranger) -> "편지 막기(test)가 무시를 못 봅니다"
                    settings.ignorePublic && drop != ChatListener.Drop.IGNORE -> "무시한 사람의 채팅이 빠지지 않습니다: $drop"
                    !settings.ignorePublic && drop == ChatListener.Drop.IGNORE -> "ignore.public-chat 이 꺼졌는데 빠집니다"
                    bypass == ChatListener.Drop.IGNORE -> "무시 뚫기 권한이 있어도 빠집니다"
                    else -> null
                }
            } finally {
                if (added) store.removeIgnoreMemory(p.uniqueId, stranger.toString())
            }
        },
        Check("거리 채팅 — 거리 밖은 빠지고 안은 남는다") { p ->
            val stranger = UUID.randomUUID()
            val far = listener.dropOf(p, stranger, ignoreBypass = true, silenceBypass = true, origin = p.location.clone().add(10_000.0, 0.0, 0.0), range = 100.0)
            val near = listener.dropOf(p, stranger, ignoreBypass = true, silenceBypass = true, origin = p.location, range = 100.0)
            val unlimited = listener.dropOf(p, stranger, ignoreBypass = true, silenceBypass = true, origin = p.location.clone().add(10_000.0, 0.0, 0.0), range = null)
            when {
                far != ChatListener.Drop.RANGE -> "1만 블록 밖인데 빠지지 않습니다: $far"
                near != null -> "제자리인데 빠집니다: $near"
                unlimited != null -> "거리 제한이 없는데 빠집니다: $unlimited"
                else -> null
            }
        },
        Check("받는 사람 거르기 — 보낸 사람 자신과 콘솔은 늘 남는다") { p ->
            val viewers = mutableSetOf<net.kyori.adventure.audience.Audience>(p, Bukkit.getConsoleSender())
            listener.filterViewers(p, viewers, 1.0, ArrayList())
            if (p in viewers && Bukkit.getConsoleSender() in viewers) null else "보낸 사람 자신이나 콘솔이 빠졌습니다"
        },
        Check("도배 — 같은 글이 정해진 횟수를 넘으면 막힌다(알림 없이)") { p ->
            if (!settings.spamEnabled || p.hasPermission(SPAM_BYPASS)) {
                // 꺼져 있으면 규칙만 본다(켜졌다고 치고).
                val on = settings.copy(spamEnabled = true)
                val guard = SpamGuard()
                val id = UUID.randomUUID()
                val now = System.currentTimeMillis()
                val blocked = (0..on.spamMinAmount).map { guard.check(id, "public", "ess-verify-spam", now + it, on) }
                return@Check if (blocked.dropLast(1).none { it } && blocked.last()) null else "도배 규칙이 틀렸습니다: $blocked"
            }
            spam.forget(p.uniqueId)
            try {
                val blocked = (0..settings.spamMinAmount).map {
                    val event = ChatListener.probe(p, "ess-verify-spam", setOf(p))
                    listener.spamCheck(event)
                    event.isCancelled
                }
                if (blocked.dropLast(1).any { it }) "너무 일찍 막혔습니다: $blocked"
                else if (!blocked.last()) "${settings.spamMinAmount + 1}번째도 막히지 않았습니다"
                else null
            } finally {
                spam.forget(p.uniqueId)
            }
        },
        Check("외침 ! 를 떼고, 나간 외침에서 표식을 거둔다(거리 채팅이 켜져 있을 때)") { p ->
            if (ChatRules.shoutRange(settings) == ChatRules.NO_SHOUT || !p.hasPermission(SHOUT)) return@Check null
            if (settings.shoutCost > 0.0 && ess.economy.isEnabled && !p.hasPermission(SHOUT_FREE)) return@Check null
            if (p.uniqueId in staffMode) return@Check null
            val event = ChatListener.probe(p, "!ess-verify", setOf(p))
            listener.shout(event, notify = false)
            try {
                val plain = ChatText.plain(event.message())
                when {
                    plain != "ess-verify" -> "글이 '$plain' 입니다"
                    !listener.isShout(event) -> "외침 표식이 없습니다"
                    else -> {
                        listener.onSent(event)
                        if (listener.isShout(event)) "나간 뒤에도 외침 표식이 남았습니다" else null
                    }
                }
            } finally {
                listener.forgetShout(event)
            }
        },
        Check("스태프 모드면 공개 채팅이 취소된다(메모리만, 보내지 않음)") { p ->
            if (!p.hasPermission(STAFF)) return@Check null
            val had = p.uniqueId in staffMode
            staffMode += p.uniqueId
            try {
                val event = ChatListener.probe(p, "ess-verify-staff", setOf(p))
                listener.staffCapture(event, deliver = false)
                if (event.isCancelled) null else "취소되지 않았습니다"
            } finally {
                if (!had) staffMode -= p.uniqueId
            }
        },
        Check("링크 축약") { _ ->
            if (!settings.linkEnabled) return@Check null
            val plain = ChatText.plain(ChatText.shortenLinks(net.kyori.adventure.text.Component.text("보세요 https://example.com/a"), settings.linkRegex, ess.messages.raw("chat-link-label")))
            if (plain.contains("example.com")) "주소가 그대로입니다: $plain" else null
        },
        Check("뮤트·그림자 뮤트를 본다(접점을 메모리에서만 바꿈)") { p ->
            val before = ess.moderation
            try {
                ess.moderation = object : ModerationState by ModerationState.NONE {
                    override fun isMuted(id: UUID): Boolean = id == p.uniqueId
                }
                val muted = service.muted(p)
                ess.moderation = object : ModerationState by ModerationState.NONE {
                    override fun isMuted(id: UUID): Boolean = id == p.uniqueId
                    override fun isShadowMuted(id: UUID): Boolean = id == p.uniqueId
                }
                val shadow = service.muted(p)
                when {
                    muted != ChatService.Muted.YES -> "뮤트로 보지 않습니다: $muted"
                    shadow != ChatService.Muted.SHADOW -> "그림자 뮤트로 보지 않습니다: $shadow"
                    else -> null
                }
            } finally {
                ess.moderation = before
            }
        },
        Check("명령 스파이 — 숨김 목록(로그인 등)은 안 보인다") { _ ->
            val hidden = settings.spyHidden.firstOrNull() ?: return@Check null
            if (ChatRules.spyShows("/$hidden 비밀번호", settings, all = true, bypass = false)) "/$hidden 이(가) 스파이에게 보입니다" else null
        },
        Check("외침 비용은 잡아 두고, 취소된 외침은 놓는다(메모리만 · 돈은 빼지 않음)") { p ->
            if (ChatRules.shoutRange(settings) == ChatRules.NO_SHOUT || !p.hasPermission(SHOUT)) return@Check null
            if (settings.shoutCost <= 0.0 || !ess.economy.isEnabled || p.hasPermission(SHOUT_FREE) || p.uniqueId in staffMode) return@Check null
            if (!ess.economy.has(p, settings.shoutCost)) return@Check null
            val before = listener.reservedOf(p.uniqueId)
            val event = ChatListener.probe(p, "!ess-verify", setOf(p))
            try {
                if (!listener.shout(event, notify = false)) return@Check "잔액이 있는데 외침으로 치지 않았습니다"
                val held = listener.reservedOf(p.uniqueId) - before
                event.isCancelled = true
                listener.onSent(event)
                val after = listener.reservedOf(p.uniqueId)
                when {
                    held < settings.shoutCost - 1e-9 -> "비용을 잡아 두지 않았습니다: $held"
                    after > before + 1e-9 -> "취소된 외침의 몫을 놓지 않았습니다: $after"
                    else -> null
                }
            } finally {
                listener.forgetShout(event)
            }
        },
        Check("채팅 끄기 — 끈 사람은 빠지고, 뚫기 권한이면 남는다") { p ->
            val stranger = UUID.randomUUID()
            val off = listener.dropOf(p, stranger, ignoreBypass = true, silenceBypass = false, origin = p.location, range = null, silenced = true)
            val bypass = listener.dropOf(p, stranger, ignoreBypass = true, silenceBypass = true, origin = p.location, range = null, silenced = true)
            val on = listener.dropOf(p, stranger, ignoreBypass = true, silenceBypass = false, origin = p.location, range = null, silenced = false)
            val live = listener.isSilenced(p) == PlayerSettings.enabled(p.uniqueId, SILENCE, false)
            when {
                off != ChatListener.Drop.SILENCE -> "채팅을 끈 사람이 빠지지 않습니다: $off"
                bypass != null -> "뚫기 권한인데 빠집니다: $bypass"
                on != null -> "채팅을 켠 사람이 빠집니다: $on"
                !live -> "채팅 끄기를 개인 설정 $SILENCE 에서 읽지 않습니다"
                else -> null
            }
        },
        Check("귓속말 막기 — 무시·받기 끔(그림자 뮤트와 상관없이 같은 판단 · 메모리만)") { p ->
            val stranger = UUID.randomUUID()
            val none: (String) -> Boolean = { false }
            val open = service.whisperBlock(stranger, none, p, accepting = true)
            val closed = service.whisperBlock(stranger, none, p, accepting = false)
            val closedBypass = service.whisperBlock(stranger, { it == MSG_BYPASS }, p, accepting = false)
            val added = store.addIgnoreMemory(p.uniqueId, stranger.toString())
            try {
                val ignored = service.whisperBlock(stranger, none, p, accepting = true)
                val ignoredBypass = service.whisperBlock(stranger, { it == IGNORE_BYPASS }, p, accepting = true)
                val console = service.whisperBlock(null, none, p, accepting = true)
                when {
                    open != null && open.first != "chat-msg-group-denied" -> "막을 까닭이 없는데 막힙니다: $open"
                    closed?.first != "chat-msg-not-accepting" && closed?.first != "chat-msg-group-denied" -> "귓속말을 끈 사람에게 갑니다: $closed"
                    closedBypass?.first == "chat-msg-not-accepting" -> "뚫기 권한인데 '받지 않습니다' 로 막힙니다"
                    ignored?.first != "chat-msg-ignored" && ignored?.first != "chat-msg-group-denied" -> "무시한 사람의 귓속말이 갑니다: $ignored"
                    ignoredBypass?.first == "chat-msg-ignored" -> "무시 뚫기 권한인데 막힙니다"
                    console?.first == "chat-msg-ignored" -> "콘솔이 무시에 막힙니다"
                    else -> null
                }
            } finally {
                if (added) store.removeIgnoreMemory(p.uniqueId, stranger.toString())
            }
        },
        Check("귓속말 그룹 — 실제로 준 group.<이름> 만 본다(OP 라고 첫 그룹이 되지 않음)") { p ->
            val order = listOf("ess-verify-a", "ess-verify-b")
            val explicit = ChatRules.groupOf(order) { node -> p.isPermissionSet(node) && p.hasPermission(node) }
            if (explicit != null) "주지 않은 그룹으로 봅니다: $explicit" else null
        },
        Check("도움요청 받는 사람 — 받기 권한자만, 보낸 사람 자신은 빼고") { p ->
            val fromConsole = service.helpopStaff(Bukkit.getConsoleSender(), listOf(p))
            val fromSelf = service.helpopStaff(p, listOf(p))
            val expected = p.hasPermission(HELPOP_RECEIVE)
            when {
                (p in fromConsole) != expected -> "받기 권한($expected)과 받는 사람이 맞지 않습니다"
                p in fromSelf -> "보낸 사람 자신에게 갑니다"
                else -> null
            }
        },
        Check("표지판 스파이 좌표는 표지판 자리") { p ->
            val sign = p.location.clone().add(5.0, 2.0, -3.0)
            val shown = service.coords(sign)
            val expected = "${sign.blockX} ${sign.blockY} ${sign.blockZ}"
            if (shown == expected) null else "좌표가 '$shown' 입니다(표지판 $expected)"
        },
        Check("CMI 모두 무시 표시는 우리 모두 무시로") { _ ->
            if (ChatRules.cmiIgnoreTarget(ChatRules.CMI_IGNORE_ALL) == ChatStore.ALL) null else "ffffffff-… 을 사람으로 넣습니다"
        },
        Check("공지 — 월드·거리 거르기") { p ->
            val world = p.world.name.lowercase()
            val here = service.broadcastReceivers(listOf(p), setOf(world), null, null)
            val elsewhere = service.broadcastReceivers(listOf(p), setOf("ess-verify-없는-월드"), null, null)
            val far = service.broadcastReceivers(listOf(p), emptySet(), p.location.clone().add(10_000.0, 0.0, 0.0), 50.0)
            val near = service.broadcastReceivers(listOf(p), emptySet(), p.location, 50.0)
            when {
                here != listOf(p) -> "-w:${p.world.name} 인데 빠졌습니다"
                elsewhere.isNotEmpty() -> "다른 월드 공지를 받습니다"
                far.isNotEmpty() -> "-r:50 밖인데 받습니다"
                near != listOf(p) -> "-r:50 안인데 빠졌습니다"
                else -> null
            }
        },
    )

    companion object {
        /** 귓속말 메인 이름(바닐라 `/minecraft:msg` 를 여기로 돌린다). */
        const val WHISPER = "귓"

        const val MSG = "inmcessentials.chat.msg"
        const val REPLY = "inmcessentials.chat.reply"
        const val MSG_CLEAN = "inmcessentials.chat.msg.clean"
        const val MSG_NOREPLY = "inmcessentials.chat.msg.noreply"
        const val MSG_BYPASS = "inmcessentials.chat.msg.bypass"

        /** + 그룹 이름(`private.groups`) — 설정에서 짓는 노드라 선언하지 않는다(op 기본, LuckPerms 로 준다). */
        const val MSG_GROUP_PREFIX = MSG + ".group."
        const val COLOR = "inmcessentials.chat.color"
        const val IGNORE = "inmcessentials.chat.ignore"
        const val IGNORE_OTHERS = "inmcessentials.chat.ignore.others"
        const val IGNORE_BYPASS = "inmcessentials.chat.ignore.bypass"
        const val HELPOP = "inmcessentials.chat.helpop"
        const val HELPOP_RECEIVE = "inmcessentials.chat.helpop.receive"
        const val STAFF = "inmcessentials.chat.staff"
        const val SPY = "inmcessentials.chat.spy"
        const val SPY_OTHERS = "inmcessentials.chat.spy.others"
        const val SPY_ALL = "inmcessentials.chat.spy.all"
        const val SPY_BYPASS = "inmcessentials.chat.spy.bypass"
        const val SPY_EXEMPT = "inmcessentials.chat.spy.exempt"
        const val SPAM_BYPASS = "inmcessentials.chat.spam.bypass"
        const val SHOUT = "inmcessentials.chat.shout"
        const val SHOUT_FREE = "inmcessentials.chat.shout.free"
        const val RANGE_BYPASS = "inmcessentials.chat.range.bypass"
        const val RANGE_SPY = "inmcessentials.chat.range.spy"
        const val BROADCAST = "inmcessentials.chat.broadcast"
        const val SILENCE_USE = "inmcessentials.chat.silence"
        const val SILENCE_BYPASS = "inmcessentials.chat.silence.bypass"
        const val TITLE = "inmcessentials.chat.title"

        /** core 개인 설정 열쇠(ARCHITECTURE "개인 설정 열쇠" 표). */
        const val ACCEPT_PM = "essentials.accept-pm"
        const val PM_SOUND = "essentials.pm-sound"
        const val SILENCE = "essentials.silence"
        private const val SETTINGS_OWNER = "채팅·귓속말"

        private const val KIND = "무시 목록"
        private const val KIND_OPTIONS = "채팅 개인 설정"
    }
}
