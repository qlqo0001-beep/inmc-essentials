package com.inmc.essentials.afk

import com.inmc.essentials.Ess
import com.inmc.essentials.feature.Feature
import com.inmc.essentials.feature.Perm
import com.inmc.essentials.service.AfkState
import com.inmc.essentials.verify.Check
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.permissions.PermissionDefault

/**
 * 잠수 — 손으로(`/잠수 [사유]`) · 자동(쉰 시간) · 제목·무작위 부제 · 들어가기/나오기 방송과 명령 · 잠수 중 보호(피해·몹 때리기·낚싯대·밀림·줍기·
 * 셜커·근처 몹 생성) · 우회 막기(제자리 뛰기·꾹 누르기·물길 기계) · 활동 종류마다 켜고 끄기 · 인원별 자동 추방(면제·사람별 시간) · `/잠수확인`.
 * 상태는 `ess.afk` 로 다른 기능에 내놓는다(플레이 시간·감옥 시간 멈추기는 그 기능이 `ess.afk.isAfk` 를 본다). 탭·이름표 표시는 타이틀포지의 일.
 *
 * CMI 항목: afk-system · cfg-afk-rules · cfg-afk-autokick · cfg-afk-mobspawn. 운영 문서 `docs/afk.md`.
 */
class AfkFeature(ess: Ess) : Feature(ess) {

    override val id = "afk"

    override val hasSettings = true

    override val permissions: List<Perm> = listOf(
        Perm(AFK, PermissionDefault.TRUE, "/잠수 — 손으로 잠수 켜고 끄기"),
        Perm(OTHERS, PermissionDefault.OP, "/잠수 -p:<플레이어> — 남을 잠수로"),
        Perm(AUTO, PermissionDefault.TRUE, "쉰 시간이 지나면 자동으로 잠수(CMI cmi.command.afk.auto)"),
        Perm(CHECK, PermissionDefault.TRUE, "/잠수확인"),
        Perm(KICK_BYPASS, PermissionDefault.OP, "잠수 자동 추방 면제"),
        Perm(STAFF, PermissionDefault.OP, "잠수 중 귓속말을 받으면 \"스태프는 디스코드로\" 안내(afk-may-not-respond-staff)"),
    )

    override val messageDefaults: Map<String, String> = linkedMapOf(
        "afk-enter-self" to "<gray>잠수 상태가 되었습니다.</gray>",
        "afk-enter-self-reason" to "<gray>잠수 상태가 되었습니다:</gray> <white>{사유}</white>",
        "afk-leave-self" to "<gray>잠수에서 돌아왔습니다.</gray> <dark_gray>({시간} 동안)</dark_gray>",
        "afk-broadcast-enter" to "<gray>{플레이어}님이 잠수 중입니다.</gray>",
        "afk-broadcast-enter-reason" to "<gray>{플레이어}님이 잠수 중입니다:</gray> <white>{사유}</white>",
        "afk-broadcast-leave" to "<gray>{플레이어}님이 돌아왔습니다.</gray>",
        "afk-title" to "<gold>잠수 중</gold>",
        "afk-subtitle-1" to "<gray>쿠키를 너무 많이 먹지 마세요!</gray>",
        "afk-subtitle-2" to "<gray>낚시하기 좋은 날이네요</gray>",
        "afk-subtitle-3" to "<gray>또 잠수예요?</gray>",
        "afk-subtitle-4" to "<gray>어디 가셨어요?</gray>",
        "afk-subtitle-5" to "",
        "afk-set-other" to "<green>{대상}님을 잠수 상태로 만들었습니다.</green>",
        "afk-unset-other" to "<green>{대상}님의 잠수를 풀었습니다.</green>",
        "afk-no-reason" to "사유 없음",
        "afk-check-afk" to "<gold>{대상}</gold><gray>: 잠수 중</gray> <white>{값}</white><gray>째 ·</gray> <white>{사유}</white>",
        "afk-check-active" to "<gold>{대상}</gold><gray>: 활동 중 · 마지막 활동</gray> <white>{시간}</white> <gray>전</gray>",
        "afk-check-header" to "<gold>접속자 {개수}명 · 잠수 {금액}명</gold>",
        "afk-check-line-afk" to "<gray>{순번}.</gray> <white>{대상}</white><gray>: 잠수 {값}째 · {사유}</gray>",
        "afk-check-line-active" to "<gray>{순번}.</gray> <white>{대상}</white><gray>: 활동 중 · {시간} 전</gray>",
        "afk-kick-reason" to "<yellow>{시간} 넘게 자리를 비워 연결을 끊었습니다.</yellow>",
        "afk-may-not-respond" to "<gray>{대상}님은 잠수 중이라 답하지 못할 수 있습니다.</gray> <dark_gray>({사유})</dark_gray>",
        "afk-may-not-respond-staff" to "<gray>{대상}님(스태프)은 잠수 중이라 답하지 못할 수 있습니다. 급하면 디스코드로 알려 주세요.</gray>",
        "afk-in-combat" to "<red>싸우는 중에는 잠수할 수 없습니다.</red> <gray>({시간} 뒤에 다시)</gray>",
    )

    @Volatile
    var config: AfkConfig = AfkConfig()
        private set

    internal lateinit var service: AfkService
    private lateinit var listener: AfkListener

    override fun load(settings: YamlConfiguration) {
        config = AfkConfig.from(settings)
    }

    override fun enable() {
        service = AfkService(ess) { config }
        listener = AfkListener(ess, this)
        ess.listen(listener)
        AfkCommands(ess, this).register()
        ess.ticks.add("afk", 1) { now -> service.tick(now) }
        ess.afk = service
    }

    override fun ready() {
        // 켤 때(또는 /reload 뒤) 이미 접속한 사람은 지금부터 잰다.
        for (player in Bukkit.getOnlinePlayers()) service.joined(player)
    }

    override fun disable() {
        if (!::service.isInitialized) return
        if (ess.afk === service) ess.afk = AfkState.NEVER
        service.clear()
    }

    // --- 검증기 --------------------------------------------------------------------------------

    @Suppress("DEPRECATION") // 사건 생성자는 26.2 에서 내부용 표시 — 검증기에서 쏘기만 한다
    override fun checks(): List<Check> = listOf(
        Check("ess.afk 가 이 기능의 것이다") { _ -> if (ess.afk === service) null else "ess.afk 가 ${ess.afk.javaClass.name}" },
        Check("잠수에 들어가면 isAfk 가 참") { p -> dry(p) { service.enter(p, auto = false, reason = "검증"); if (ess.afk.isAfk(p)) null else "들어갔는데 잠수가 아닙니다" } },
        Check("잠수 중 피해 사건이 취소된다") { p ->
            if (!config.protectsFromDamage(true, 0, p.world.name)) return@Check null
            dry(p) {
                service.enter(p, auto = true, reason = null)
                val event = EntityDamageEvent(p, EntityDamageEvent.DamageCause.CUSTOM, DamageSource.builder(DamageType.GENERIC).build(), 0.0)
                Bukkit.getPluginManager().callEvent(event)
                if (event.isCancelled) null else "잠수인데 피해 사건이 취소되지 않았습니다"
            }
        },
        Check("갓 손으로 잠수한 사람은 아직 피해 보호가 없다(싸우다 /잠수 막기)") { p ->
            if (!config.preventDamage || config.manualDamageDelay <= 0) return@Check null
            dry(p) {
                service.enter(p, auto = false, reason = null)
                val auto = AfkConfig(manualDamageDelay = 30, damageDisabledWorlds = setOf("dungeon"))
                when {
                    service.protectsFromDamage(p) -> "손 잠수 직후인데 피해를 막습니다"
                    !auto.protectsFromDamage(false, 30_000, "world") -> "정한 시간이 지났는데 막지 않습니다"
                    auto.protectsFromDamage(true, 0, "dungeon") -> "보호를 뺀 월드에서 막습니다"
                    else -> null
                }
            }
        },
        Check("싸운 뒤에는 스스로 잠수할 수 없다") { p ->
            if (config.combatBlockSeconds <= 0) return@Check null
            val before = service.rawCombat(p.uniqueId)
            try {
                // 진짜 때리기 사건은 쏘지 않는다 — 인첸트 등의 "때렸을 때" 효과가 검증하는 사람에게 걸린다. 기록 → 판단만 본다.
                service.restoreCombat(p.uniqueId, null)
                val fresh = service.combatLeft(p.uniqueId) == 0L
                service.combat(p.uniqueId)
                if (fresh && service.combatLeft(p.uniqueId) > 0) null else "싸움 기록이 잠수를 막지 않습니다"
            } finally {
                service.restoreCombat(p.uniqueId, before)
            }
        },
        Check("명령 속 사유는 명령을 조작하지 못한다") { _ ->
            val safe = AfkService.commandSafe("<click:run_command:'/op me'>눌러 -s §c빨강 a;b")
            if ('<' !in safe && '§' !in safe && ';' !in safe && " -s" !in safe && safe.contains("눌러")) null else "걸러지지 않았습니다: $safe"
        },
        // 명령 사건은 **방송하지 않는다** — 방송하면 명령 쿨타임·비용·도배 세기(commands 기능)·별칭·인첸트 COMMAND 트리거가 관리자에게 걸린다
        // (2026-10-10 리뷰). 리스너가 사건에서 부르는 판단을 그대로 부른다.
        Check("명령을 치면 잠수가 풀린다") { p ->
            if (!config.counts(AfkConfig.Activity.COMMAND)) return@Check null
            dry(p) {
                service.enter(p, auto = false, reason = null)
                listener.commandActivity(p, "/inmcess-afk-probe", cancelled = false)
                if (!service.isAfk(p)) null else "명령 뒤에도 잠수입니다"
            }
        },
        Check("/잠수 자신의 명령은 잠수를 풀지 않는다") { p ->
            dry(p) {
                service.enter(p, auto = false, reason = null)
                listener.commandActivity(p, "/잠수", cancelled = false)
                if (service.isAfk(p)) null else "/잠수 가 잠수를 풀었습니다(곧바로 다시 들어가게 됩니다)"
            }
        },
        Check("쉰 시간이 지나면 주기 검사에서 자동 잠수가 된다") { p ->
            val c = config
            if (c.autoAfkSeconds <= 0 || c.autoDisabledIn(p.world.name) || !p.hasPermission(AUTO)) return@Check null
            dry(p) {
                val now = System.currentTimeMillis()
                service.restore(p.uniqueId, null, now - c.autoAfkSeconds * 1000 - 1000)
                service.check(p, now, Bukkit.getOnlinePlayers().size, c)
                if (service.isAfk(p)) null else "auto-afk-seconds 가 지났는데 잠수가 되지 않았습니다"
            }
        },
        Check("추방 시각이 지나면 주기 검사가 추방을 고른다(실제 추방 없이)") { p ->
            dry(p) {
                val now = System.currentTimeMillis()
                val c = config.copy(kickFromPlayerCount = 0, kickRepeat = false)
                service.enter(p, auto = true, reason = null, now = now - 1000)
                service.entry(p.uniqueId)?.kickAt = now - 1
                when {
                    !service.check(p, now, 1, c) -> "추방 시각이 지났는데 고르지 않았습니다"
                    service.entry(p.uniqueId)?.kickAt != null -> "되풀이가 꺼져 있는데 다음 추방 시각이 남았습니다"
                    else -> null
                }
            }
        },
        Check("낚싯대 우클릭은 낚시 활동이다(기본 꺼짐 — 잠수를 풀지 않는다)") { _ ->
            when {
                AfkListener.interactActivity(fishingRod = true) != AfkConfig.Activity.FISHING -> "낚싯대 우클릭을 상호작용으로 셉니다"
                AfkListener.interactActivity(fishingRod = false) != AfkConfig.Activity.INTERACT -> "보통 우클릭이 상호작용이 아닙니다"
                AfkConfig().counts(AfkConfig.Activity.FISHING) -> "기본 설정에서 낚시가 활동입니다"
                else -> null
            }
        },
        Check("높이만 바뀐 움직임(제자리 뛰기)은 활동이 아니다") { p ->
            if (!config.preventJumping) return@Check null
            dry(p) {
                service.enter(p, auto = true, reason = null)
                val from = p.location.clone()
                val to = from.clone().add(0.0, 0.4, 0.0)
                Bukkit.getPluginManager().callEvent(PlayerMoveEvent(p, from, to))
                if (service.isAfk(p)) null else "제자리 뛰기로 잠수가 풀렸습니다"
            }
        },
        Check("키를 안 누른 채 옮겨지면(물길) 활동이 아니다") { p ->
            if (!config.antiAfkMachines) return@Check null
            val input = p.currentInput
            if (input.isForward || input.isBackward || input.isLeft || input.isRight) return@Check null // 검증하는 사람이 키를 누르고 있다
            dry(p) {
                service.enter(p, auto = true, reason = null)
                val from = p.location.clone()
                val to = from.clone().add(0.3, 0.0, 0.0)
                Bukkit.getPluginManager().callEvent(PlayerMoveEvent(p, from, to))
                if (service.isAfk(p)) null else "키 입력 없는 이동으로 잠수가 풀렸습니다"
            }
        },
        Check("자동 추방 시간: 인원 단계·권한·면제") { _ ->
            val c = AfkConfig(kickSeconds = 900, kickByPlayerCount = listOf(10 to 600L, 20 to 500L))
            when {
                c.kickAfter(5, null, false) != 900L -> "9명 이하인데 기본값이 아닙니다"
                c.kickAfter(25, null, false) != 500L -> "25명인데 20명 단계가 아닙니다"
                c.kickAfter(25, 1200, false) != 1200L -> "권한 시간이 이기지 않습니다"
                c.kickAfter(25, null, true) != null -> "면제인데 추방 시간이 있습니다"
                else -> null
            }
        },
        Check("몹 생성 막기: 잠수만 있을 때만") { _ ->
            if (AfkListener.shouldBlockSpawn(listOf(true, true)) && !AfkListener.shouldBlockSpawn(listOf(true, false)) && !AfkListener.shouldBlockSpawn(emptyList())) null
            else "판정이 틀렸습니다"
        },
    )

    /** 검증기 — 방송·명령·제목 없이 메모리에서만 바꿨다 되돌린다. */
    private fun dry(player: Player, block: () -> String?): String? {
        val entry = service.entry(player.uniqueId)
        val last = service.rawLast(player.uniqueId)
        service.dryRun = true
        try {
            service.restore(player.uniqueId, null, last)
            return block()
        } finally {
            service.restore(player.uniqueId, entry, last)
            service.dryRun = false
        }
    }

    companion object {
        const val AFK = "inmcessentials.afk.afk"
        const val OTHERS = "inmcessentials.afk.afk.others"
        const val AUTO = "inmcessentials.afk.auto"
        const val CHECK = "inmcessentials.afk.check"
        const val KICK_BYPASS = "inmcessentials.afk.kickbypass"
        const val STAFF = "inmcessentials.afk.staff"

        /** 사람별 추방 시간 `inmcessentials.afk.kickoutin.<초>`(CMI kickOutIn) — 직접 준 노드만 본다. */
        val KICK_OUT_IN_PREFIX = AUTO.substringBeforeLast('.') + ".kickoutin."

        /** 이 기능의 명령 이름 — 이 명령을 치는 것은 "명령 활동" 으로 세지 않는다(치자마자 풀렸다 다시 들어가지 않게). */
        val OWN_LABELS = setOf("잠수", "afk")
    }
}
