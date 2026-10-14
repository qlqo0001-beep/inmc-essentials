package com.inmc.essentials.moderation

import com.inmc.essentials.Ess
import com.inmc.essentials.feature.Feature
import com.inmc.essentials.feature.Perm
import com.inmc.essentials.service.CmiData
import com.inmc.essentials.service.CmiImportable
import com.inmc.essentials.service.ImportReport
import com.inmc.essentials.service.ModerationState
import com.inmc.essentials.store.LogoutSpot
import com.inmc.essentials.verify.Check
import io.papermc.paper.event.player.AsyncChatEvent
import kr.inmc.core.integration.ChatVisibility
import kr.inmc.core.store.SqlDialect
import kr.inmc.core.store.SqlSource
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.BanEntry
import org.bukkit.Bukkit
import org.bukkit.block.BlockFace
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityMountEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.permissions.PermissionDefault
import java.net.InetAddress
import java.util.Date
import java.util.UUID

/**
 * 제재(2026-10-10) — 카탈로그 cmd1:ban-system · cmd4:cmd-kick · cmd5:cmd-mute · cmd5:cmd-mutechat · cmd4:sys-jail · cmd8:cmd-warn · cmd2:cmd-cuff ·
 * cmd1:admin-login-alert · cmd5:cmd-note · cmd1:admin-checkaccount · cmd4:cmd-lockip · cmd5:cmd-patrol · cmd7:cmd-sudo · cmd8:cmd-usermeta ·
 * cmd7:cmd-switchplayerdata · cmd5:cmd-removeuser · cmd6:cmd-resetdbfields · cmd5:cfg-purge · cmd3:cmd-importoldusers. 운영 문서 `docs/moderation.md`.
 *
 * - 밴은 Bukkit BanList(바닐라 `/ban`·`/pardon` 과 같은 목록) · 뮤트·감옥·경고·메모·경보·메타·IP 는 DB(`ess_moderation_*`) + 메모리([ModState]).
 * - `ess.moderation` 접점을 [ModState] 로 바꿔 끼운다 — 귓속말·우편·도움요청이 본다. 공개 채팅·명령·이동의 막기는 [ModListener] 가 직접.
 * - 데이터 도구(교환·삭제·열 초기화·정리)는 확인하면 예약 → **다음 시작 때** [schema] 단계에서 적용([DataTools.applyPending]).
 */
class ModerationFeature(ess: Ess) : Feature(ess), CmiImportable {

    override val id = "moderation"

    override val hasSettings = true

    override val permissions: List<Perm> = listOf(
        Perm(NOTIFY, PermissionDefault.OP, "조용히(-s) 한 제재·그림자 뮤트·경고 누적 알림을 받는다"),
        Perm(BAN, PermissionDefault.OP, "/밴"),
        Perm(BAN_BYPASS, PermissionDefault.OP, "밴·기간밴 당하지 않는다"),
        Perm(TEMPBAN, PermissionDefault.OP, "/기간밴"),
        Perm(TEMPBAN + ".max.unlimited", PermissionDefault.OP, "기간밴·IP 기간밴 길이 제한 없음(없으면 …tempban.max.<초> 중 가장 큰 것, 그것도 없으면 설정값)"),
        Perm(IPBAN, PermissionDefault.OP, "/아이피밴 · /기간아이피밴"),
        Perm(IPBAN_BYPASS, PermissionDefault.OP, "IP 밴 당하지 않는다"),
        Perm(UNBAN, PermissionDefault.OP, "/밴해제"),
        Perm(BANLIST, PermissionDefault.OP, "/밴목록"),
        Perm(CHECKBAN, PermissionDefault.OP, "/밴확인"),
        Perm(KICK, PermissionDefault.OP, "/추방"),
        Perm(KICK_ALL, PermissionDefault.OP, "/추방 all — 전원 추방"),
        Perm(KICK_BYPASS, PermissionDefault.OP, "추방 당하지 않는다"),
        Perm(MUTE, PermissionDefault.OP, "/뮤트"),
        Perm(MUTE_BYPASS, PermissionDefault.OP, "뮤트 당하지 않는다"),
        Perm(MUTE + ".max.unlimited", PermissionDefault.OP, "뮤트 길이 제한 없음(없으면 …mute.max.<초>)"),
        Perm(SHADOWMUTE, PermissionDefault.OP, "/그림자뮤트"),
        Perm(UNMUTE, PermissionDefault.OP, "/뮤트해제"),
        Perm(MUTECHAT, PermissionDefault.OP, "/채팅잠금 · /채팅잠금해제"),
        Perm(MUTECHAT_BYPASS, PermissionDefault.OP, "채팅이 잠겨도 말할 수 있다"),
        Perm(JAIL, PermissionDefault.OP, "/감옥"),
        Perm(JAIL_BYPASS, PermissionDefault.OP, "감옥에 갇히지 않는다"),
        Perm(JAIL + ".max.unlimited", PermissionDefault.OP, "감옥 시간 제한 없음(없으면 …jail.max.<초>)"),
        Perm(JAILEDIT, PermissionDefault.OP, "/감옥편집"),
        Perm(JAILLIST, PermissionDefault.OP, "/감옥목록"),
        Perm(UNJAIL, PermissionDefault.OP, "/석방"),
        Perm(WARN, PermissionDefault.OP, "/경고"),
        Perm(WARN_BYPASS, PermissionDefault.OP, "경고 받지 않는다"),
        Perm(WARNINGS, PermissionDefault.TRUE, "/경고목록 — 내 경고"),
        Perm(WARNINGS_OTHERS, PermissionDefault.OP, "/경고목록 [플레이어]"),
        Perm(EDITWARNINGS, PermissionDefault.OP, "/경고편집"),
        Perm(CUFF, PermissionDefault.OP, "/수갑"),
        Perm(CUFF_BYPASS, PermissionDefault.OP, "수갑을 차지 않는다"),
        Perm(ALERT, PermissionDefault.OP, "/경보"),
        Perm(ALERT_INFORM, PermissionDefault.OP, "경보가 걸린 사람이 들어오면 알림을 받는다"),
        Perm(NOTE, PermissionDefault.OP, "/메모"),
        Perm(CHECKACCOUNT, PermissionDefault.OP, "/부계정"),
        Perm(CHECKACCOUNT_SHOWIP, PermissionDefault.OP, "/부계정 에서 IP 를 보고 IP 로 찾는다"),
        Perm(SAMEIP, PermissionDefault.OP, "/같은아이피"),
        Perm(LOCKIP, PermissionDefault.OP, "/아이피잠금"),
        Perm(LOCKIP_BYPASS, PermissionDefault.OP, "남이 내 계정의 허용 IP 를 고치지 못한다(오프라인이면 op)"),
        Perm(PATROL, PermissionDefault.OP, "/순찰"),
        Perm(PATROL_EXEMPT, PermissionDefault.FALSE, "순찰 대상에서 빠진다"),
        Perm(SUDO, PermissionDefault.OP, "/대신실행"),
        Perm(SUDO_BYPASS, PermissionDefault.OP, "대신실행 당하지 않는다"),
        Perm(USERMETA, PermissionDefault.OP, "/유저메타"),
        Perm(SWITCHPLAYERDATA, PermissionDefault.OP, "/데이터교환"),
        Perm(REMOVEUSER, PermissionDefault.OP, "/유저삭제"),
        Perm(RESETDBFIELDS, PermissionDefault.OP, "/열초기화"),
        Perm(PURGE, PermissionDefault.OP, "/미접속정리"),
        Perm(IMPORTOLDUSERS, PermissionDefault.OP, "/유저가져오기"),
    )

    override val messageDefaults: Map<String, String> = ModMessages.ALL

    @Volatile
    var settings: ModSettings = ModSettings()
        private set

    internal lateinit var state: ModState
    internal lateinit var store: ModStore
    internal lateinit var mod: Moderation
    internal lateinit var jails: JailService
    internal lateinit var bans: Bans
    internal lateinit var records: Records
    internal lateinit var listener: ModListener

    private var papi: ModPapi? = null

    override fun schema(sql: SqlSource) {
        ModStore(ess).schema(sql)
        // 확인해 둔 데이터 도구를 다른 기능이 표를 읽기 전에(이 단계는 켜기 전, 모든 기능의 표 만들기 사이) 적용한다 —
        // **켤 때 한 번, 기능이 쓰는 DB 에서만.** 돌고 있는 서버가 부르는 schema(DB 옮기기 `DbMigration` 의 받는 쪽)와, 공용 DB 를 쓸 때 같이
        // 표만 만드는 로컬 DB(또는 그 반대)에는 적용하지 않는다 — 다른 DB 에 남은 옛 예약이 엉뚱한 때 돌지 않게.
        if (ess.ready) return
        if ((sql.dialect == SqlDialect.MYSQL) != ess.config.storage.mysql) return
        // 켜기의 DB 대기 한도(core SqlWorker.call 30초) 안에서 — APPLY_BUDGET_MS 가 지나면 남은 것(정리는 남은 사람)은 다음 시작에.
        val applied = DataTools.applyPending(sql, ess.logger, { op, purged ->
            ess.io.asyncRun {
                val n = DataTools.purgeFiles(op.folders, purged, op.deleteFiles, ess.logger)
                ess.logger.warning("미접속 정리 — 바닐라 파일 ${n}개를 " + if (op.deleteFiles) "지웠습니다" else "<폴더>_backup 으로 옮겼습니다")
            }
        }, deadline = System.currentTimeMillis() + APPLY_BUDGET_MS)
        if (applied > 0 && sql.dialect == SqlDialect.MYSQL) {
            ess.logger.warning("공용 DB 에 데이터 도구 예약 ${applied}건을 적용했습니다 — 같은 DB 를 쓰는 다른 서버가 켜져 있었다면 그 서버의 메모리 값이 다음 저장에 되써질 수 있습니다. 예약 적용은 모든 서버를 끈 뒤 하나만 켜서 하세요.")
        }
    }

    override fun load(settings: YamlConfiguration) {
        this.settings = ModSettings.from(settings)
        // 리로드(/에센셜 리로드) — 손으로 고친 data/jails.yml 도 다시 읽는다(파일 머리의 안내). 켤 때는 enable() 이 처음 읽는다.
        if (::jails.isInitialized) jails.load()
    }

    override fun enable() {
        state = ModState()
        store = ModStore(ess)
        store.loadAll(state)
        mod = Moderation(ess, this)
        jails = JailService(mod).also { it.load() }
        bans = Bans(mod)
        records = Records(mod)
        ess.moderation = state
        // 채팅 숨김 창구(core) — 디스코드 중계가 그림자 뮤트·감옥 거리 채팅을 서버 밖으로 보내지 않게.
        ChatVisibility.provide(CHAT_OWNER) { id -> state.isShadowMuted(id) || (state.isJailed(id) && settings.jailChatRange > 0) }

        listener = ModListener(mod, jails, bans, records)
        ess.listen(listener)

        BanCommands(mod, bans, records).register()
        MuteCommands(mod, records).register()
        JailCommands(mod, jails).register()
        WarnCommands(mod, records).register()
        RecordCommands(mod, records).register()
        DataCommands(mod).register()

        ess.ticks.add("moderation-jail", 1) { now -> jails.tick(now) }
        ess.ticks.add("moderation-expire", 10) { now -> records.expire(now) }
        ess.ticks.add("moderation-jail-save", 30) { _ -> jails.flush() }
        ess.logger.info("제재: 뮤트 ${state.mutes.size} · 감옥 ${jails.jails.size}곳/${state.jailed.size}명 · 경고 ${state.warnings.values.sumOf { it.size }} · 수갑 ${state.cuffs.size}")
    }

    override fun ready() {
        // /reload — 이미 접속한 사람의 감옥(보스바·감방).
        for (player in Bukkit.getOnlinePlayers()) jails.joined(player)
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) papi = ModPapi(mod).also { it.setup() }
    }

    override fun disable() {
        papi?.teardown()
        papi = null
        ChatVisibility.withdraw(CHAT_OWNER)
        if (::jails.isInitialized) {
            jails.flush()
            jails.hideAll()
        }
        if (::state.isInitialized && ess.moderation === state) ess.moderation = ModerationState.NONE
    }

    // --- CMI 가져오기 ----------------------------------------------------------------------------

    override fun importCmi(data: CmiData, report: ImportReport) {
        if (!::mod.isInitialized) return report.skipped("제재", "moderation 기능이 켜지지 않았습니다")
        CmiModImport(mod, jails, bans).run(data, report)
    }

    // --- 검증기 --------------------------------------------------------------------------------

    override fun checks(): List<Check> = listOf(
        Check("ess.moderation 접점이 이 기능의 상태다") { _ -> if (ess.moderation === state) null else "다른 구현이 끼워져 있습니다: ${ess.moderation.javaClass.name}" },
        Check("뮤트 — 공개 채팅이 LOWEST 에서 취소된다(메모리만)") { p ->
            withMute(p, shadow = false) {
                val event = probe(p, setOf(p))
                listener.onChat(event)
                if (event.isCancelled) null else "취소되지 않았습니다"
            }
        },
        Check("그림자 뮤트 — 본인에게만 보인다(메모리만)") { p ->
            withMute(p, shadow = true) {
                val others = Bukkit.getOnlinePlayers().filter { it != p }
                val event = probe(p, (Bukkit.getOnlinePlayers() + Bukkit.getConsoleSender()).toSet())
                listener.onChat(event)
                when {
                    event.isCancelled -> "취소됐습니다 — 본인에게도 안 보입니다"
                    p !in event.viewers() -> "본인이 받는 사람에서 빠졌습니다"
                    others.any { it in event.viewers() } -> "다른 사람이 받는 사람에 남았습니다"
                    !ess.moderation.isShadowMuted(p.uniqueId) -> "접점이 그림자 뮤트로 보지 않습니다"
                    else -> null
                }
            }
        },
        Check("채팅 잠금 — 우회 권한이 없을 때만 막힌다(메모리만)") { p ->
            val before = state.chatLock
            state.chatLock = ChatLock(System.currentTimeMillis() + 60_000, "ess-verify", "verify")
            try {
                val event = probe(p, setOf(p))
                listener.onChat(event)
                val bypass = p.hasPermission(MUTECHAT_BYPASS)
                if (event.isCancelled == !bypass) null else "우회 권한 $bypass 인데 취소 ${event.isCancelled}"
            } finally {
                state.chatLock = before
            }
        },
        Check("감옥 — 허용 목록 밖의 명령이 막힌다(사건 발사 · 메모리만)") { p ->
            withJail(p, null) {
                val event = PlayerCommandPreprocessEvent(p, "/ess-verify-blocked-command")
                Bukkit.getPluginManager().callEvent(event)
                when {
                    !event.isCancelled -> "취소되지 않았습니다"
                    settings.jailWhitelist.isNotEmpty() && !ModRules.commandAllowed(settings.jailWhitelist.first() + " a", settings.jailWhitelist) -> "허용 목록의 명령이 막힙니다"
                    else -> null
                }
            }
        },
        Check("감옥 — 감옥 밖으로 순간이동이 막힌다(사건 발사 · 메모리만)") { p ->
            val here = p.location
            val probeJail = Jail(PROBE_JAIL, JailArea.around(here.world.name, here.blockX, here.blockY, here.blockZ, 2), null, emptyList())
            jails.putMemory(probeJail)
            try {
                withJail(p, PROBE_JAIL) {
                    val far = here.clone().add(100.0, 0.0, 0.0)
                    val event = PlayerTeleportEvent(p, here, far, PlayerTeleportEvent.TeleportCause.PLUGIN)
                    Bukkit.getPluginManager().callEvent(event)
                    when {
                        !event.isCancelled -> "감옥 밖 이동이 취소되지 않았습니다"
                        !jails.insideOwnJail(p.uniqueId, here) -> "감옥 안을 밖으로 봅니다"
                        else -> null
                    }
                }
            } finally {
                jails.removeMemory(PROBE_JAIL)
            }
        },
        Check("수갑 — 걸음이 제자리로 돌아간다(사건 발사 · 메모리만)") { p ->
            val had = state.cuffs[p.uniqueId]
            state.cuffs[p.uniqueId] = Cuff(p.uniqueId, "verify", System.currentTimeMillis())
            try {
                val from = p.location
                val event = PlayerMoveEvent(p, from, from.clone().add(1.0, 0.0, 0.0))
                Bukkit.getPluginManager().callEvent(event)
                if (event.isCancelled || event.to.x == from.x) null else "걸음이 그대로 갑니다"
            } finally {
                if (had != null) state.cuffs[p.uniqueId] = had else state.cuffs.remove(p.uniqueId)
            }
        },
        Check("IP 잠금 — 허용 목록 밖의 IP 는 접속 거부(가짜 사람 · 메모리만)") @Suppress("DEPRECATION") { _ ->
            val id = UUID.randomUUID()
            state.lockIps[id] = setOf("198.51.100.1")
            try {
                val event = AsyncPlayerPreLoginEvent("ess_verify", InetAddress.getByName("203.0.113.9"), id)
                listener.onPreLogin(event)
                if (event.loginResult == AsyncPlayerPreLoginEvent.Result.KICK_OTHER) null else "거부되지 않았습니다: ${event.loginResult}"
            } finally {
                state.lockIps.remove(id)
            }
        },
        Check("감옥 — 포탈 이동이 막힌다(사건 발사 · 메모리만)") { p ->
            val here = p.location
            val probeJail = Jail(PROBE_JAIL, JailArea.around(here.world.name, here.blockX, here.blockY, here.blockZ, 2), null, emptyList())
            jails.putMemory(probeJail)
            try {
                withJail(p, PROBE_JAIL) {
                    val far = here.clone().add(100.0, 0.0, 0.0)
                    val event = PlayerPortalEvent(p, here, far, PlayerTeleportEvent.TeleportCause.NETHER_PORTAL)
                    Bukkit.getPluginManager().callEvent(event)
                    if (event.isCancelled) null else "포탈로 감옥 밖에 나가는 것이 취소되지 않았습니다"
                }
            } finally {
                jails.removeMemory(PROBE_JAIL)
            }
        },
        Check("감옥 — 피해·배고픔이 막힌다(사건 발사 · 메모리만)") @Suppress("DEPRECATION") { p ->
            if (!settings.jailPreventDamage && !settings.jailPreventHunger) return@Check null
            withJail(p, null) {
                val damage = EntityDamageEvent(p, EntityDamageEvent.DamageCause.FALL, 1.0)
                Bukkit.getPluginManager().callEvent(damage)
                // 배고픔은 **줄어드는** 변화만 막는다 — 허기가 0 이면 줄어드는 사건을 만들 수 없어 그 검사는 건너뛴다.
                val hunger = if (p.foodLevel > 0) FoodLevelChangeEvent(p, p.foodLevel - 1).also { Bukkit.getPluginManager().callEvent(it) } else null
                when {
                    settings.jailPreventDamage && !damage.isCancelled -> "떨어지는 피해가 취소되지 않았습니다"
                    settings.jailPreventHunger && hunger != null && !hunger.isCancelled -> "배고픔이 취소되지 않았습니다"
                    else -> null
                }
            }
        },
        Check("감옥 — 거리 채팅: 먼 사람은 받는 사람에서 빠진다(메모리만)") { p ->
            val range = settings.jailChatRange
            if (range <= 0) return@Check null
            withJail(p, null) {
                val far = Bukkit.getOnlinePlayers().filter { it != p && (it.world != p.world || it.location.distanceSquared(p.location) > range.toDouble() * range) }
                val event = probe(p, (Bukkit.getOnlinePlayers() + Bukkit.getConsoleSender()).toSet())
                listener.onChat(event)
                when {
                    event.isCancelled -> "취소됐습니다"
                    p !in event.viewers() -> "본인이 받는 사람에서 빠졌습니다"
                    far.any { it in event.viewers() } -> "범위 밖 사람이 받는 사람에 남았습니다"
                    else -> null
                }
            }
        },
        Check("수갑 — 허용 목록 밖의 명령이 막힌다(사건 발사 · 메모리만)") { p ->
            withCuff(p) {
                val event = PlayerCommandPreprocessEvent(p, "/ess-verify-blocked-command")
                Bukkit.getPluginManager().callEvent(event)
                if (event.isCancelled) null else "취소되지 않았습니다"
            }
        },
        Check("수갑 — 상호작용·탈것 타기가 막힌다(사건 발사 · 메모리만)") { p ->
            withCuff(p) {
                val interact = PlayerInteractEvent(p, Action.RIGHT_CLICK_AIR, null, null, BlockFace.SELF)
                Bukkit.getPluginManager().callEvent(interact)
                val mount = EntityMountEvent(p, p)
                Bukkit.getPluginManager().callEvent(mount)
                when {
                    interact.useItemInHand() != Event.Result.DENY -> "상호작용이 막히지 않았습니다"
                    !mount.isCancelled -> "탈것 타기가 막히지 않았습니다"
                    else -> null
                }
            }
        },
        Check("뮤트 — 채팅 대신 쓰는 명령(/me 등)이 막힌다(사건 발사 · 메모리만)") { p ->
            val blocked = settings.muteBlockedCommands.firstOrNull() ?: return@Check null
            withMute(p, shadow = false) {
                val event = PlayerCommandPreprocessEvent(p, "/$blocked ess-verify")
                Bukkit.getPluginManager().callEvent(event)
                if (event.isCancelled) null else "/$blocked 이(가) 취소되지 않았습니다"
            }
        },
        Check("그림자 뮤트 — core 채팅 숨김 창구가 숨김으로 답한다(디스코드 중계가 보는 것)") { p ->
            withMute(p, shadow = true) {
                if (ChatVisibility.isHidden(p.uniqueId)) null else "ChatVisibility.isHidden 이 false 입니다(끼워지지 않았거나 다른 구현)"
            }
        },
        Check("밴 — 접속 거부 화면에 사유·차단한 사람·남은 기간이 들어간다(가짜 항목)") { _ ->
            val fake = object : BanEntry<String> {
                override fun getTarget(): String = "ess-verify"
                override fun getBanTarget(): String = "ess-verify"
                override fun getCreated(): Date = Date()
                override fun setCreated(created: Date) {}
                override fun getSource(): String = "ess-verify-source"
                override fun setSource(source: String) {}
                override fun getExpiration(): Date = Date(System.currentTimeMillis() + 100L * 86_400_000L)
                override fun setExpiration(expiration: Date?) {}
                override fun getReason(): String = "ess-verify-reason"
                override fun setReason(reason: String?) {}
                override fun save() {}
                override fun remove() {}
            }
            val text = PlainTextComponentSerializer.plainText().serialize(bans.screen(fake, ip = false))
            when {
                "ess-verify-reason" !in text -> "사유가 없습니다: $text"
                "ess-verify-source" !in text -> "차단한 사람이 없습니다: $text"
                ess.messages.raw("moderation-permanent") in text -> "기간밴인데 영구로 보입니다: $text"
                else -> null
            }
        },
        Check("경고 — 점수는 살아 있는 경고만 더하고 문턱은 새로 넘을 때만(메모리만)") { p ->
            val id = p.uniqueId
            val had = state.warnings[id]
            val now = System.currentTimeMillis()
            state.warnings[id] = listOf(
                Warning(-1, id, "ess-verify", 2, "ess-verify", "verify", now, now + 60_000),
                Warning(-2, id, "ess-verify", 100, "ess-verify", "verify", now - 2_000, now - 1_000),
            )
            try {
                val points = state.points(id, now)
                val keys = settings.warnPerform.keys
                val t = keys.minOrNull()
                when {
                    points != 2 -> "만료된 경고가 합산됩니다: $points"
                    t != null && ModRules.crossed(keys, t - 1, t) != listOf(t) -> "문턱 $t 를 넘었는데 고르지 못했습니다"
                    t != null && ModRules.crossed(keys, t, t).isNotEmpty() -> "이미 넘은 문턱이 다시 돕니다"
                    else -> null
                }
            } finally {
                if (had != null) state.warnings[id] = had else state.warnings.remove(id)
            }
        },
    )

    private fun probe(p: Player, viewers: Set<net.kyori.adventure.audience.Audience>): AsyncChatEvent {
        val message = Component.text("ess-verify")
        return AsyncChatEvent(false, p, viewers.toMutableSet(), io.papermc.paper.chat.ChatRenderer.defaultRenderer(), message, message,
            net.kyori.adventure.chat.SignedMessage.system("ess-verify", message))
    }

    private fun <T> withMute(p: Player, shadow: Boolean, block: () -> T): T {
        val had = state.mutes[p.uniqueId]
        state.mutes[p.uniqueId] = Mute(p.uniqueId, System.currentTimeMillis() + 60_000, "ess-verify", "verify", shadow, System.currentTimeMillis())
        try {
            return block()
        } finally {
            if (had != null) state.mutes[p.uniqueId] = had else state.mutes.remove(p.uniqueId)
        }
    }

    private fun <T> withCuff(p: Player, block: () -> T): T {
        val had = state.cuffs[p.uniqueId]
        state.cuffs[p.uniqueId] = Cuff(p.uniqueId, "verify", System.currentTimeMillis())
        try {
            return block()
        } finally {
            if (had != null) state.cuffs[p.uniqueId] = had else state.cuffs.remove(p.uniqueId)
        }
    }

    private fun <T> withJail(p: Player, jail: String?, block: () -> T): T {
        val had = state.jailed[p.uniqueId]
        state.jailed[p.uniqueId] = Jailed(p.uniqueId, jail ?: PROBE_JAIL_MISSING, null, 60_000, System.currentTimeMillis(), "ess-verify", "verify",
            System.currentTimeMillis(), LogoutSpot.of(p.location))
        try {
            return block()
        } finally {
            if (had != null) state.jailed[p.uniqueId] = had else state.jailed.remove(p.uniqueId)
        }
    }

    companion object {
        private const val PROBE_JAIL = "ess-verify-jail"
        private const val PROBE_JAIL_MISSING = "ess-verify-none"

        /** 켤 때 데이터 도구 예약을 적용하는 시간(밀리초) — core `SqlWorker.call` 의 30초 안에 다른 기능의 표 만들기와 공용 DB 쪽도 들어가야 한다. */
        const val APPLY_BUDGET_MS = 15_000L

        /** core `ChatVisibility` 공급자 이름. */
        const val CHAT_OWNER = "inmc-essentials:moderation"

        const val NOTIFY = "inmcessentials.moderation.notify"
        const val BAN = "inmcessentials.moderation.ban"
        const val BAN_BYPASS = "inmcessentials.moderation.ban.bypass"
        const val TEMPBAN = "inmcessentials.moderation.tempban"
        const val IPBAN = "inmcessentials.moderation.ipban"
        const val IPBAN_BYPASS = "inmcessentials.moderation.ipban.bypass"
        const val UNBAN = "inmcessentials.moderation.unban"
        const val BANLIST = "inmcessentials.moderation.banlist"
        const val CHECKBAN = "inmcessentials.moderation.checkban"
        const val KICK = "inmcessentials.moderation.kick"
        const val KICK_ALL = "inmcessentials.moderation.kick.all"
        const val KICK_BYPASS = "inmcessentials.moderation.kick.bypass"
        const val MUTE = "inmcessentials.moderation.mute"
        const val MUTE_BYPASS = "inmcessentials.moderation.mute.bypass"
        const val SHADOWMUTE = "inmcessentials.moderation.shadowmute"
        const val UNMUTE = "inmcessentials.moderation.unmute"
        const val MUTECHAT = "inmcessentials.moderation.mutechat"
        const val MUTECHAT_BYPASS = "inmcessentials.moderation.mutechat.bypass"
        const val JAIL = "inmcessentials.moderation.jail"
        const val JAIL_BYPASS = "inmcessentials.moderation.jail.bypass"
        const val JAILEDIT = "inmcessentials.moderation.jailedit"
        const val JAILLIST = "inmcessentials.moderation.jaillist"
        const val UNJAIL = "inmcessentials.moderation.unjail"
        const val WARN = "inmcessentials.moderation.warn"
        const val WARN_BYPASS = "inmcessentials.moderation.warn.bypass"
        const val WARNINGS = "inmcessentials.moderation.warnings"
        const val WARNINGS_OTHERS = "inmcessentials.moderation.warnings.others"
        const val EDITWARNINGS = "inmcessentials.moderation.editwarnings"
        const val CUFF = "inmcessentials.moderation.cuff"
        const val CUFF_BYPASS = "inmcessentials.moderation.cuff.bypass"
        const val ALERT = "inmcessentials.moderation.alert"
        const val ALERT_INFORM = "inmcessentials.moderation.alert.inform"
        const val NOTE = "inmcessentials.moderation.note"
        const val CHECKACCOUNT = "inmcessentials.moderation.checkaccount"
        const val CHECKACCOUNT_SHOWIP = "inmcessentials.moderation.checkaccount.showip"
        const val SAMEIP = "inmcessentials.moderation.sameip"
        const val LOCKIP = "inmcessentials.moderation.lockip"
        const val LOCKIP_BYPASS = "inmcessentials.moderation.lockip.bypass"
        const val PATROL = "inmcessentials.moderation.patrol"
        const val PATROL_EXEMPT = "inmcessentials.moderation.patrol.exempt"
        const val SUDO = "inmcessentials.moderation.sudo"
        const val SUDO_BYPASS = "inmcessentials.moderation.sudo.bypass"
        const val USERMETA = "inmcessentials.moderation.usermeta"
        const val SWITCHPLAYERDATA = "inmcessentials.moderation.switchplayerdata"
        const val REMOVEUSER = "inmcessentials.moderation.removeuser"
        const val RESETDBFIELDS = "inmcessentials.moderation.resetdbfields"
        const val PURGE = "inmcessentials.moderation.purge"
        const val IMPORTOLDUSERS = "inmcessentials.moderation.importoldusers"

        /** 권한으로 정하는 최대 시간의 접두(`…max.<초>` · `…max.unlimited`) — 문자열로 짓는 노드라 ResourceTest 가 못 본다. */
        const val TEMPBAN_MAX = "$TEMPBAN.max."
        const val MUTE_MAX = "$MUTE.max."
        const val JAIL_MAX = "$JAIL.max."
    }
}
