package com.inmc.essentials.teleport

import com.inmc.essentials.Ess
import com.inmc.essentials.feature.Feature
import com.inmc.essentials.feature.Perm
import com.inmc.essentials.service.CmiData
import com.inmc.essentials.service.CmiImportable
import com.inmc.essentials.service.CmiLocation
import com.inmc.essentials.service.ImportReport
import com.inmc.essentials.service.TeleportKind
import com.inmc.essentials.util.Ph
import com.inmc.essentials.verify.Check
import kr.inmc.core.store.SqlSource
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.permissions.PermissionDefault

/**
 * 이동 — 플레이어 이동·소환·좌표 이동(강제 포함) · 이동 요청(tpa) · 뒤로 · 사망 지점, 그리고 **모든 기능의 이동이 지나는 [EssTeleporter]**
 * (안전한 자리 · 금지 아이템 · 종류별 효과 · 이동 뒤 무적 · 탄 것·태운 것·끈에 묶인 것 같이 옮기기).
 *
 * CMI 항목: cmd-tp · cmd-tphere-tpo · cmd-tpall · cmd-tppos · mod-teleportwith · cfg-teleport-effects · tp-blacklisted-items ·
 * cfg-teleport-invulnerability · cmd-tpa · tp-back · cmd-dback. 운영 문서 `docs/teleport.md`.
 */
class TeleportFeature(ess: Ess) : Feature(ess), CmiImportable {

    override val id = "teleport"

    override val hasSettings = true

    override val permissions: List<Perm> = listOf(
        Perm(TP, PermissionDefault.OP, "/이동 — 플레이어에게 이동"),
        Perm(TP_OTHERS, PermissionDefault.OP, "/이동·/소환 <누구> <어디로> — 남을 남에게 보내기 · /좌표이동 -p"),
        Perm(OFFLINE, PermissionDefault.OP, "/이동 <나간 사람> — 그 사람이 마지막으로 나간 자리로"),
        Perm(HERE, PermissionDefault.OP, "/소환 — 플레이어를 내 자리로"),
        Perm(HERE_OFFLINE, PermissionDefault.OP, "/소환 <나간 사람> — 다음 접속 때 내 자리로 옮겨지게"),
        Perm(TPO, PermissionDefault.OP, "/강제이동 — 요청 차단을 무시하고 이동"),
        Perm(TPOHERE, PermissionDefault.OP, "/강제소환 — 요청 차단을 무시하고 소환"),
        Perm(TPALL, PermissionDefault.OP, "/전원소환"),
        Perm(TPALLWORLD, PermissionDefault.OP, "/월드전원소환"),
        Perm(TPPOS, PermissionDefault.OP, "/좌표이동"),
        Perm(TPPOS_OTHERS, PermissionDefault.OP, "/좌표이동 -p:<이름> — 남을 좌표로"),
        Perm(TPOPOS, PermissionDefault.OP, "/강제좌표이동(안전 확인 없음, -p 포함)"),
        Perm(TPA, PermissionDefault.TRUE, "/이동요청"),
        Perm(TPAHERE, PermissionDefault.TRUE, "/소환요청"),
        Perm(TPAALL, PermissionDefault.OP, "/전원소환요청"),
        Perm(TPACCEPT, PermissionDefault.TRUE, "/수락"),
        Perm(TPDENY, PermissionDefault.TRUE, "/거절"),
        Perm(TPTOGGLE, PermissionDefault.TRUE, "/요청차단 — 요청 받기 켜고 끄기 · 한 사람 막기"),
        Perm(NO_DISTANCE, PermissionDefault.OP, "이동 요청 거리 제한 무시"),
        Perm(TOGGLE_BYPASS, PermissionDefault.OP, "요청 받기를 끈 사람에게도 이동·소환·요청"),
        Perm(BACK, PermissionDefault.TRUE, "/뒤로"),
        Perm(BACK_OTHERS, PermissionDefault.OP, "/뒤로 <플레이어>"),
        Perm(DBACK, PermissionDefault.TRUE, "/사망지점"),
        Perm(DBACK_OTHERS, PermissionDefault.OP, "/사망지점 <플레이어>"),
        Perm(RESETBACK, PermissionDefault.OP, "/뒤로초기화 — 내 뒤로 자리 지우기"),
        Perm(RESETBACK_OTHERS, PermissionDefault.OP, "/뒤로초기화 <플레이어>"),
        Perm(EssTeleporter.WITH, PermissionDefault.OP, "이동할 때 탄 것·태운 것·끈에 묶인 동물이 같이 온다"),
        Perm(EssTeleporter.BYPASS_BLACKLIST, PermissionDefault.OP, "이동 금지 아이템을 들고도 이동"),
    )

    override val messageDefaults: Map<String, String> = linkedMapOf(
        // --- 공통 ---
        "teleport-someone" to "관리자",
        "teleport-self" to "<red>자기 자신에게는 할 수 없습니다.</red>",
        "teleport-same-player" to "<red>같은 플레이어를 두 번 적었습니다.</red>",
        "teleport-failed" to "<red>{플레이어}님을 옮기지 못했습니다.</red>",
        "teleport-blacklisted" to "<red>이동할 수 없는 아이템을 들고 있습니다.</red> <hover:show_text:'{목록}'><gray><u>[목록 {개수}개]</u></gray></hover>",
        "teleport-blacklisted-line" to "<white>{아이템}</white> <gray>{개수}개 (최대 {최대}개)</gray>",
        "teleport-unsafe" to "<red>도착할 자리 근처에 안전한 곳이 없어 이동하지 않았습니다.</red>",
        "teleport-target-not-accepting" to "<red>{대상}님은 이동을 받지 않습니다.</red>",
        "teleport-world-not-found" to "<red>'{월드}' 월드를 찾을 수 없습니다.</red>",
        "teleport-world-gone" to "<red>'{월드}' 월드가 지금 없어 갈 수 없습니다.</red>",
        "teleport-need-location" to "<red>위치를 적어 주세요.</red> <gray>(x y z 월드 · 또는 월드;x;y;z)</gray>",
        "teleport-bad-coords" to "<red>좌표를 읽지 못했습니다: {값}</red> <gray>(x y z [월드] [pitch yaw] [-p:이름] [-rng:범위] [-s] · ~ 는 지금 자리)</gray>",
        // --- 이동 · 소환 ---
        "teleport-tp-done" to "<green>{대상}님에게 이동했습니다.</green>",
        "teleport-tp-offline-done" to "<green>{대상}님이 마지막으로 나간 자리로 이동했습니다.</green> <gray>({값})</gray>",
        "teleport-offline-no-spot" to "<red>{대상}님이 나간 자리 기록이 없습니다.</red>",
        "teleport-tp-other-done" to "<green>{플레이어}님을 {대상}님에게 보냈습니다.</green>",
        "teleport-moved-by" to "<yellow>{플레이어}님이 당신을 {대상}님에게 보냈습니다.</yellow>",
        "teleport-here-done" to "<green>{대상}님을 불러왔습니다.</green>",
        "teleport-here-moved" to "<yellow>{플레이어}님이 당신을 불렀습니다.</yellow>",
        "teleport-here-offline" to "<green>{대상}님은 접속해 있지 않습니다 — 다음 접속 때 지금 이 자리로 옮겨집니다.</green>",
        "teleport-pending-applied" to "<yellow>나가 있는 동안 {플레이어}님이 당신을 불러, 그 자리로 옮겼습니다.</yellow>",
        "teleport-pending-dropped" to "<gray>나가 있는 동안 불린 자리로 옮기지 못해 그 이동은 취소되었습니다.</gray>",
        "teleport-pending-cancelled" to "<green>{대상}님의 다음 접속 이동을 취소했습니다.</green>",
        "teleport-pending-all-cancelled" to "<green>다음 접속 이동 {개수}개를 모두 취소했습니다.</green>",
        "teleport-tpall-done" to "<green>{개수}명을 {대상}님에게 불렀습니다.</green>",
        "teleport-tpallworld-done" to "<green>'{월드}' 의 접속자 {개수}명을 옮겼고, 나가 있는 {나간사람}명은 다음 접속 때 옮겨집니다.</green>",
        "teleport-tppos-done" to "<green>{값} 으로 이동했습니다.</green>",
        "teleport-tppos-other-done" to "<green>{플레이어}님을 {값} 으로 보냈습니다.</green>",
        "teleport-tppos-moved" to "<yellow>{플레이어}님이 당신을 {값} 으로 보냈습니다.</yellow>",
        // --- 이동 요청 ---
        "teleport-tpa-restricted-self" to "<red>지금은 이동 요청을 쓸 수 없습니다.</red>",
        "teleport-tpa-restricted-target" to "<red>{대상}님은 지금 이동 요청을 받을 수 없습니다.</red>",
        "teleport-tpa-excluded-world" to "<red>'{월드}' 에서는 이동 요청을 쓸 수 없습니다.</red>",
        "teleport-tpa-other-mode" to "<red>{대상}님은 다른 모드에 있습니다 — 모드가 다르면 이동 요청을 쓸 수 없습니다.</red>",
        "teleport-tpa-failed" to "<red>{플레이어}님의 이동이 이루어지지 않았습니다.</red>",
        "teleport-tpa-not-accepting" to "<red>{대상}님은 이동 요청을 받지 않습니다.</red>",
        "teleport-tpa-deny-blocked" to "<red>{대상}님이 요청을 거절해 {시간}초 동안 다시 요청할 수 없습니다.</red>",
        "teleport-tpa-too-far" to "<red>{대상}님이 너무 멀리 있습니다.</red> <gray>(최대 {값}블록 · 같은 월드)</gray>",
        "teleport-tpa-sent" to "<green>{대상}님에게 이동 요청을 보냈습니다.</green> <gray>({시간}초 안에 수락해야 합니다)</gray>",
        "teleport-tpahere-sent" to "<green>{대상}님에게 소환 요청을 보냈습니다.</green> <gray>({시간}초 안에 수락해야 합니다)</gray>",
        "teleport-tpa-cancel-button" to "<click:run_command:'/이동요청 취소 {대상}'><hover:show_text:'<gray>요청 취소'><red>[취소]</red></hover></click>",
        "teleport-tpa-received" to "<yellow>{플레이어}</yellow><white>님이 당신에게 가도 되는지 묻습니다.</white> <gray>({시간}초)</gray>",
        "teleport-tpahere-received" to "<yellow>{플레이어}</yellow><white>님이 당신을 부릅니다.</white> <gray>({시간}초)</gray>",
        "teleport-tpa-buttons" to "<click:run_command:'/수락 {플레이어}'><hover:show_text:'<green>수락'><green>[수락]</green></hover></click> " +
            "<click:run_command:'/거절 {플레이어}'><hover:show_text:'<red>거절'><red>[거절]</red></hover></click>",
        "teleport-tpa-hint" to "<gray>/수락 {플레이어} 또는 /거절 {플레이어}</gray>",
        "teleport-tpaall-sent" to "<green>{개수}명에게 소환 요청을 보냈습니다.</green> <gray>({시간}초)</gray>",
        "teleport-tpa-none" to "<red>받은 이동 요청이 없습니다.</red>",
        "teleport-tpa-none-sent" to "<red>보낸 이동 요청이 없습니다.</red>",
        "teleport-tpa-gone" to "<red>{플레이어}님이 접속해 있지 않습니다.</red>",
        "teleport-tpa-accepted" to "<green>{플레이어}님의 요청을 수락했습니다.</green>",
        "teleport-tpa-accepted-by" to "<green>{대상}님이 요청을 수락했습니다.</green>",
        "teleport-tpa-warmup" to "<yellow>{시간}초 뒤에 이동합니다. 움직이지 마세요.</yellow>",
        "teleport-tpa-warmup-bar" to "<yellow>이동까지 {시간}초</yellow>",
        "teleport-tpa-warmup-moved" to "<red>움직여서 이동을 취소했습니다.</red>",
        "teleport-tpa-warmup-cancelled" to "<red>{플레이어}님이 움직여 이동이 취소되었습니다.</red>",
        "teleport-tpa-done" to "<green>{대상}님에게 이동했습니다.</green>",
        "teleport-tpa-denied" to "<gray>{플레이어}님의 요청을 거절했습니다.</gray>",
        "teleport-tpa-denied-by" to "<red>{대상}님이 요청을 거절했습니다.</red>",
        "teleport-tpa-cancelled" to "<gray>보낸 요청 {개수}개를 취소했습니다.</gray>",
        "teleport-tpa-cancelled-by" to "<gray>{플레이어}님이 요청을 취소했습니다.</gray>",
        "teleport-tpa-expired" to "<gray>{대상}님에게 보낸 요청이 시간이 지나 사라졌습니다.</gray>",
        "teleport-tpa-left" to "<gray>{플레이어}님이 나가서 이동 요청이 사라졌습니다.</gray>",
        "teleport-tptoggle-on" to "<green>이제 이동 요청을 받습니다.</green>",
        "teleport-tptoggle-off" to "<yellow>이제 이동 요청을 받지 않습니다.</yellow> <gray>(관리자 이동은 막지 못합니다)</gray>",
        "teleport-tpblock-on" to "<yellow>{대상}님의 이동 요청을 막았습니다.</yellow>",
        "teleport-tpblock-off" to "<green>{대상}님의 이동 요청을 다시 받습니다.</green>",
        // --- 뒤로 · 사망 지점 ---
        "teleport-back-none" to "<red>돌아갈 자리가 없습니다.</red>",
        "teleport-dback-none" to "<red>돌아갈 사망 지점이 없습니다.</red>",
        "teleport-back-excluded" to "<red>'{월드}' 로는 돌아갈 수 없습니다.</red>",
        "teleport-back-done" to "<green>이전 자리로 돌아왔습니다.</green> <gray>({값})</gray>",
        "teleport-back-other-done" to "<green>{플레이어}님을 이전 자리로 보냈습니다.</green> <gray>({값})</gray>",
        "teleport-dback-done" to "<green>사망 지점으로 돌아왔습니다.</green> <gray>({값})</gray>",
        "teleport-dback-other-done" to "<green>{플레이어}님을 사망 지점으로 보냈습니다.</green> <gray>({값})</gray>",
        "teleport-resetback-done" to "<green>{플레이어}님의 뒤로 자리를 지웠습니다.</green>",
        "teleport-resetback-all-done" to "<green>{플레이어}님의 뒤로·사망 자리를 지웠습니다.</green>",
        "teleport-resetback-notice" to "<yellow>{플레이어}님이 당신의 뒤로 자리를 지웠습니다.</yellow> <gray>사유: {사유}</gray>",
        "teleport-no-reason" to "없음",
    )

    /** 설정 스냅샷(리로드 때 통째로 바뀐다). */
    @Volatile
    var settings: TeleportSettings = TeleportSettings()
        private set

    internal lateinit var service: TeleportService
    internal lateinit var teleporter: EssTeleporter
    internal lateinit var tpa: TpaFlow

    override fun schema(sql: SqlSource) {
        TeleportData.schema(sql)
    }

    override fun load(settings: YamlConfiguration) {
        val loaded = TeleportSettings.from(settings)
        for (problem in TeleportSettings.problems(settings)) ess.logger.warning("settings/teleport.yml $problem")
        this.settings = loaded
    }

    override fun enable() {
        service = TeleportService(ess) { settings }
        teleporter = EssTeleporter(ess, service) { settings }
        tpa = TpaFlow(ess, this, service)
        ess.listen(TeleportListener(this, service))
        TeleportCommands(ess, this, service, tpa).register()
        ess.ticks.add("teleport-요청", 1) { now -> tpa.tick(now) }
        ess.teleporter = teleporter
    }

    override fun ready() {
        service.loadOnline { player, found -> applyPending(player, found) }
    }

    override fun disable() {
        if (::teleporter.isInitialized && ess.teleporter === teleporter) ess.teleporter = com.inmc.essentials.service.Teleporter.PLAIN
        if (::tpa.isInitialized) tpa.shutdown()
        if (::service.isInitialized) service.shutdown()
    }

    /**
     * 이 기능의 명령이 옮길 때 — 접점(`ess.teleporter`)이 이 기능의 구현이면 명령 열쇠([command] — 효과·`/이동` 금지 아이템)까지 넘기고,
     * 누가 바꿔 끼웠으면 그 구현의 보통 이동으로(모든 이동은 접점을 지난다).
     */
    internal fun move(player: Player, to: org.bukkit.Location, kind: TeleportKind, safe: Boolean = true, command: String? = null): java.util.concurrent.CompletableFuture<Boolean> {
        val current = ess.teleporter
        return if (current === teleporter) teleporter.go(player, to, kind, safe, command) else current.teleport(player, to, kind, safe)
    }

    /**
     * 나가 있는 동안 불린 자리로 — 한 틱 뒤(스폰 기능의 접속 이동보다 뒤에). **한 번만 시도한다**: 옮기기 전에 기록을 지운다 — 늘 막히는 이동
     * (감옥 등)이 접속마다 되풀이되거나 몇 주 뒤 옛 자리로 옮기지 않게. `tp.pending-expire-seconds` 가 지난 것은 조용히 버린다.
     */
    internal fun applyPending(player: Player, found: Pair<Spot, String?>) {
        val (spot, by) = found
        player.scheduler.run(ess.plugin, { _ ->
            if (!player.isOnline) return@run
            service.takePending(player.uniqueId)
            if (settings.pendingExpired(spot, System.currentTimeMillis())) return@run
            val location = spot.toLocation()
            if (location == null) {
                ess.messages.send(player, "teleport-pending-dropped")
                return@run
            }
            ess.teleporter.teleport(player, location, TeleportKind.ADMIN).thenAccept { ok ->
                if (ok != true) {
                    if (player.isOnline) ess.messages.send(player, "teleport-pending-dropped")
                    return@thenAccept
                }
                ess.messages.send(player, "teleport-pending-applied", Ph.of().player(summonerName(player, by)))
            }
        }, null)
    }

    /** 부른 사람의 이름 — 적지 않았거나(투명이었음) 지금 접속해 투명이라 [viewer] 가 못 보면 "관리자"(CLAUDE.md 8). */
    private fun summonerName(viewer: Player, by: String?): String {
        val someone = ess.messages.raw("teleport-someone")
        by ?: return someone
        val online = Bukkit.getPlayerExact(by)
        return if (online != null && !kr.inmc.core.integration.Vanish.canSee(viewer, online)) someone else by
    }

    // --- CMI 가져오기 --------------------------------------------------------------------------

    /**
     * CMI 의 `/back` 자리(TeleportLocation)·사망 자리(DeathLocation)·요청 받기(acceptingTPA)를 넣는다. **이미 있는 기록은 두고**
     * (`ON CONFLICT DO NOTHING`) 없는 것만 넣는다 — 두 번 돌려도 겹치지 않는다. 제외 월드의 자리는 건너뛴다.
     */
    override fun importCmi(data: CmiData, report: ImportReport) {
        val now = System.currentTimeMillis()
        for (user in data.users) {
            importSpot(user.id, user.name, TeleportData.BACK, user.back, user.lastLogoff ?: now, report)
            importSpot(user.id, user.name, TeleportData.DEATH, user.death, user.lastLogoff ?: now, report)
            importAccepting(user.id, user.name, user.extra, report)
        }
    }

    private fun importSpot(id: java.util.UUID, name: String, kind: String, location: CmiLocation?, at: Long, report: ImportReport) {
        location ?: return
        val label = if (kind == TeleportData.BACK) "뒤로 자리" else "사망 자리"
        report.read(label)
        if (!location.x.isFinite() || !location.y.isFinite() || !location.z.isFinite() || location.world.isBlank()) {
            report.unreadable(label, "$name: $location")
            return
        }
        val excluded = if (kind == TeleportData.BACK) settings.backExcluded(location.world) else settings.deathExcluded(location.world)
        if (excluded) {
            report.skipped(label, "$name: 제외 월드 ${location.world}")
            return
        }
        val spot = Spot(location.world, location.x, location.y, location.z, location.yaw, location.pitch, at)
        // DB(비어 있을 때만) + 접속 중이면 메모리에도 — 가져온 뒤 다시 접속하지 않아도 바로 /뒤로 가 된다.
        service.importSpot(id, kind, spot)
        report.imported(label)
    }

    /** CMI 는 개인 옵션을 users 표의 한 열에 묶어 둔다(형식이 버전마다 다르다) — `acceptingTPA` 와 참/거짓만 알아본다([AcceptingTpa]). */
    private fun importAccepting(id: java.util.UUID, name: String, extra: Map<String, String>, report: ImportReport) {
        for ((column, value) in extra) {
            val parsed = AcceptingTpa.parse(value)
            if (parsed == AcceptingTpa.Absent) continue
            report.read("요청 받기")
            if (parsed !is AcceptingTpa.Value) {
                report.unreadable("요청 받기", "$name: $column=${value.take(120)}")
                return
            }
            if (parsed.accepting == settings.tpaDefaultAccepting) {
                report.skipped("요청 받기", "$name: 기본값과 같음")
                return
            }
            service.importAccepting(id, parsed.accepting)
            report.imported("요청 받기")
            return
        }
    }

    // --- 검증기 --------------------------------------------------------------------------------
    //
    // 진짜 PlayerTeleportEvent 는 **한 번만** 쏜다(리스너가 걸렸나만 본다) — 그 사건은 다른 리스너에도 간다(이벤트 명령 playerTeleport,
    // 명령 대기 취소, 상자 열기 세션 등). 원인·거리·제외 월드·뒤로 자신 규칙은 사건 없이 service.onTeleport 를 바로 부른다.

    @Suppress("DEPRECATION") // EntityDamageEvent(…, DamageSource, …) 는 26.2 에서 사건 생성자가 내부용으로 표시됐다 — 검증기에서 쏘기만 한다
    override fun checks(): List<Check> = listOf(
        Check("이동 구현이 바꿔 끼워져 있다") { _ ->
            if (ess.teleporter === teleporter) null else "ess.teleporter 가 이 기능의 것이 아닙니다: ${ess.teleporter.javaClass.name}"
        },
        Check("이동 사건(COMMAND)의 출발 자리가 뒤로에 적힌다 — 진짜 사건 한 번") { player ->
            // 감옥에 있으면 moderation 이 사건을 취소한다 · 원인/월드가 설정에서 빠졌으면 적히지 않는 것이 맞다 — 그때는 건너뛴다.
            if (ess.moderation.isJailed(player.uniqueId) || PlayerTeleportEvent.TeleportCause.COMMAND !in settings.backCauses ||
                settings.backExcluded(player.world.name)
            ) {
                return@Check null
            }
            withDryRun(player) {
                val from = player.location.clone()
                val event = PlayerTeleportEvent(player, from, from.clone().add(20.0, 0.0, 0.0), PlayerTeleportEvent.TeleportCause.COMMAND)
                Bukkit.getPluginManager().callEvent(event)
                if (event.isCancelled) return@withDryRun null // 다른 플러그인(보호 구역 등)이 취소 — 리스너는 ignoreCancelled 라 적지 않는 것이 맞다
                recordedFrom(player)
            }
        },
        Check("가까운 이동은 뒤로에 안 적힌다") { player ->
            if (settings.backMinDistance <= 0.5) return@Check null
            withDryRun(player) {
                record(player, PlayerTeleportEvent.TeleportCause.PLUGIN, (settings.backMinDistance - 1.0).coerceAtLeast(0.5))
                if (service.back(player.uniqueId)?.at == MARK) null else "최소 거리(${settings.backMinDistance}) 안의 이동이 기록됐습니다"
            }
        },
        Check("엔더 진주 이동은 뒤로에 안 적힌다") { player ->
            if (PlayerTeleportEvent.TeleportCause.ENDER_PEARL in settings.backCauses) return@Check null
            withDryRun(player) {
                record(player, PlayerTeleportEvent.TeleportCause.ENDER_PEARL, 20.0)
                if (service.back(player.uniqueId)?.at == MARK) null else "ENDER_PEARL 이동이 기록됐습니다"
            }
        },
        Check("/뒤로 자신의 이동은 뒤로에 안 적힌다") { player ->
            withDryRun(player) {
                service.suppress(player.uniqueId)
                try {
                    record(player, PlayerTeleportEvent.TeleportCause.PLUGIN, 20.0)
                } finally {
                    service.unsuppress(player.uniqueId)
                }
                if (service.back(player.uniqueId)?.at == MARK) null else "/뒤로 의 이동이 기록됐습니다"
            }
        },
        Check("감옥 이동(가두기·풀기)은 뒤로에서 뺀다") { _ ->
            if (EssTeleporter.suppressesBack(TeleportKind.JAIL, settings) && !EssTeleporter.suppressesBack(TeleportKind.HOME, settings)) null
            else "감옥 이동을 뒤로에 적거나 홈 이동을 뺍니다"
        },
        Check("제외 월드에서 떠나면 뒤로에 안 적힌다") { player ->
            val before = settings
            withDryRun(player) {
                settings = before.copy(backExcludedWorlds = before.backExcludedWorlds + player.world.name.lowercase())
                try {
                    record(player, PlayerTeleportEvent.TeleportCause.COMMAND, 20.0)
                } finally {
                    settings = before
                }
                if (service.back(player.uniqueId)?.at == MARK) null else "제외 월드(${player.world.name})에서의 이동이 기록됐습니다"
            }
        },
        Check("사망 자리가 적힌다") { player ->
            withDryRun(player) {
                val excluded = settings.deathExcluded(player.world.name)
                service.onDeath(player, player.location)
                val death = service.death(player.uniqueId)
                when {
                    excluded -> if (death?.at == MARK) null else "제외 월드의 사망 자리가 적혔습니다"
                    death == null || death.at == MARK -> "사망 자리가 적히지 않았습니다"
                    else -> null
                }
            }
        },
        Check("이동 뒤 무적이 주어진다") { player ->
            val seconds = settings.invulnerabilityFor(player.world.name)
            if (seconds <= 0) return@Check null
            val before = service.invulnerableUntil(player.uniqueId)
            try {
                service.setInvulnerableUntil(player.uniqueId, null)
                service.grantInvulnerability(player.uniqueId, seconds)
                if (service.isInvulnerable(player.uniqueId)) null else "무적 ${seconds}초를 줬는데 무적이 아닙니다"
            } finally {
                service.setInvulnerableUntil(player.uniqueId, before)
            }
        },
        Check("이동 뒤 무적이면 피해 사건이 취소된다") { player ->
            val before = service.invulnerableUntil(player.uniqueId)
            try {
                service.setInvulnerableUntil(player.uniqueId, System.currentTimeMillis() + 10_000L)
                val event = EntityDamageEvent(player, EntityDamageEvent.DamageCause.CUSTOM, DamageSource.builder(DamageType.GENERIC).build(), 0.0)
                Bukkit.getPluginManager().callEvent(event)
                if (event.isCancelled) null else "무적인데 피해 사건이 취소되지 않았습니다"
            } finally {
                service.setInvulnerableUntil(player.uniqueId, before)
            }
        },
        Check("무적이 아니면 피해 사건을 건드리지 않는다") { player ->
            val before = service.invulnerableUntil(player.uniqueId)
            try {
                service.setInvulnerableUntil(player.uniqueId, null)
                val event = EntityDamageEvent(player, EntityDamageEvent.DamageCause.CUSTOM, DamageSource.builder(DamageType.GENERIC).build(), 0.0)
                Bukkit.getPluginManager().callEvent(event)
                // 다른 플러그인(보호 구역 등)이 취소했을 수 있다 — 우리 판단만 본다.
                if (!service.isInvulnerable(player.uniqueId)) null else "무적이 아닌데 무적으로 봅니다"
            } finally {
                service.setInvulnerableUntil(player.uniqueId, before)
            }
        },
        Check("금지 아이템: 가방에 든 것을 세어 최대를 넘으면 잡는다") { player ->
            val inventory = player.inventory
            val have = inventory.contains(org.bukkit.Material.BEDROCK)
            val slot = if (have) -1 else inventory.firstEmpty()
            if (!have && slot < 0) return@Check null // 가방이 가득 — 넣어 볼 칸이 없다
            val test = settings.copy(blacklistEnabled = true, blacklist = listOf(BlacklistRule("bedrock", 0)))
            try {
                if (slot >= 0) inventory.setItem(slot, ItemStack(org.bukkit.Material.BEDROCK, 1))
                val over = teleporter.violations(player, test)
                when {
                    over.isEmpty() -> "가방의 bedrock 을 금지 아이템으로 세지 못했습니다(ItemRef·아이템 판별 확인)"
                    !test.blacklistApplies(TeleportKind.TPA) || !test.blacklistAppliesToTp() -> "켠 금지 아이템이 이동 요청·/이동 에 걸리지 않습니다"
                    else -> null
                }
            } finally {
                if (slot >= 0) inventory.setItem(slot, null)
            }
        },
        Check("다른 기능의 시스템 이동(OTHER)은 금지 아이템·안전한 자리로 막지 않는다") { _ ->
            val all = settings.copy(blacklistEnabled = true, blacklistKinds = TeleportKind.entries.toSet())
            when {
                all.blacklistApplies(TeleportKind.OTHER) -> "OTHER 이동에 금지 아이템을 봅니다(토템 구조·앉기가 막힙니다)"
                EssTeleporter.isChosen(TeleportKind.OTHER, null) -> "다른 기능의 OTHER 를 사람이 고른 이동으로 봅니다"
                !EssTeleporter.exactFallback(TeleportKind.OTHER, chosen = false) -> "OTHER 이동이 안전한 자리가 없으면 막힙니다"
                EssTeleporter.exactFallback(TeleportKind.OTHER, chosen = true) -> "/이동 이 위험한 자리로 그대로 갑니다"
                else -> null
            }
        },
        Check("요청 받기를 끄면 이동·요청이 막히고 우회 권한은 통과한다") { player ->
            val before = service.acceptingRaw(player.uniqueId)
            service.dryRun = true
            try {
                service.setAccepting(player.uniqueId, false)
                when {
                    !service.refuses(player.uniqueId, null, bypass = false) -> "받기를 껐는데 막지 않습니다"
                    service.refuses(player.uniqueId, null, bypass = true) -> "우회 권한인데 막습니다"
                    else -> null
                }
            } finally {
                service.restoreAccepting(player.uniqueId, before)
                service.dryRun = false
            }
        },
        Check("막은 사람의 요청은 막힌다") { player ->
            val before = service.blocksRaw(player.uniqueId)
            val acceptingBefore = service.acceptingRaw(player.uniqueId)
            val other = java.util.UUID.randomUUID()
            service.dryRun = true
            try {
                service.restoreAccepting(player.uniqueId, true)
                service.toggleBlock(player.uniqueId, other)
                when {
                    !service.refuses(player.uniqueId, other, bypass = false) -> "막은 사람의 요청을 받습니다"
                    service.refuses(player.uniqueId, java.util.UUID.randomUUID(), bypass = false) -> "막지 않은 사람의 요청도 막습니다"
                    else -> null
                }
            } finally {
                service.restoreBlocks(player.uniqueId, before)
                service.restoreAccepting(player.uniqueId, acceptingBefore)
                service.dryRun = false
            }
        },
        Check("이동 요청 제외 월드(던전)에서는 요청을 막는다") { player ->
            val before = settings
            try {
                settings = before.copy(tpaExcludedWorlds = before.tpaExcludedWorlds + player.world.name.lowercase())
                if (tpa.excludedWorld(player.world) == player.world.name) null else "제외 월드(${player.world.name})를 알아보지 못했습니다"
            } finally {
                settings = before
            }
        },
        Check("동반 이동 권한이 등록돼 있다(사용자 결정: 권한이 있어야 따라온다)") { _ ->
            if (Bukkit.getPluginManager().getPermission(EssTeleporter.WITH) != null) null else "${EssTeleporter.WITH} 가 등록되지 않았습니다"
        },
        Check("안전한 자리: 지금 선 자리 근처에서 찾는다") { player ->
            if (player.gameMode != org.bukkit.GameMode.SURVIVAL && player.gameMode != org.bukkit.GameMode.ADVENTURE && player.isFlying) return@Check null
            val found = SafeSpot.find(player.location, settings, needGround = !player.allowFlight)
            if (found != null) null else "서 있는 자리(${player.location.blockX} ${player.location.blockY} ${player.location.blockZ}) 근처에서 안전한 자리를 못 찾았습니다"
        },
        Check("이동 요청 장부: 요청 → 수락 → 비움") { player ->
            val book = TpaBook()
            val other = java.util.UUID.randomUUID()
            val now = System.currentTimeMillis()
            book.add(TpaBook.Request(other, "test", player.uniqueId, player.name, false, "tpa", now, now + 1000))
            val taken = book.take(player.uniqueId)
            if (taken?.from == other && book.size() == 0) null else "장부가 요청을 돌려주지 않았습니다"
        },
    )

    /** 검증기 — 기록을 메모리에서만 바꾸고 끝나면 되돌린다. 비교를 위해 미리 [MARK] 시각의 자리를 넣어 둔다. */
    private fun withDryRun(player: Player, block: () -> String?): String? {
        val backBefore = service.back(player.uniqueId)
        val deathBefore = service.death(player.uniqueId)
        service.dryRun = true
        try {
            val mark = Spot.of(player.location, MARK)!!
            service.restore(player.uniqueId, mark, mark)
            return block()
        } finally {
            service.restore(player.uniqueId, backBefore, deathBefore)
            service.dryRun = false
        }
    }

    /** 뒤로 기록 규칙만 — 사건 없이 [TeleportService.onTeleport] 를 바로 부른다. [distance] 블록 옆으로. */
    private fun record(player: Player, cause: PlayerTeleportEvent.TeleportCause, distance: Double) {
        val from = player.location.clone()
        service.onTeleport(player, from, from.clone().add(distance, 0.0, 0.0), cause)
    }

    private fun recordedFrom(player: Player): String? {
        val back = service.back(player.uniqueId) ?: return "기록이 없습니다"
        return if (back.at != MARK && back.world == player.world.name && kotlin.math.abs(back.x - player.location.x) < 0.01) null
        else "출발 자리가 기록되지 않았습니다(리스너가 걸리지 않았거나 다른 플러그인이 MONITOR 전에 취소)"
    }

    companion object {
        /** 검증기가 미리 넣는 표식 시각 — 이것이 그대로면 "안 적혔다". */
        private const val MARK = 1L

        const val TP = "inmcessentials.teleport.tp"
        const val TP_OTHERS = "inmcessentials.teleport.tp.others"
        const val OFFLINE = "inmcessentials.teleport.offline"
        const val HERE = "inmcessentials.teleport.here"
        const val HERE_OFFLINE = "inmcessentials.teleport.here.offline"
        const val TPO = "inmcessentials.teleport.tpo"
        const val TPOHERE = "inmcessentials.teleport.tpohere"
        const val TPALL = "inmcessentials.teleport.tpall"
        const val TPALLWORLD = "inmcessentials.teleport.tpallworld"
        const val TPPOS = "inmcessentials.teleport.tppos"
        const val TPPOS_OTHERS = "inmcessentials.teleport.tppos.others"
        const val TPOPOS = "inmcessentials.teleport.tpopos"
        const val TPA = "inmcessentials.teleport.tpa"
        const val TPAHERE = "inmcessentials.teleport.tpahere"
        const val TPAALL = "inmcessentials.teleport.tpaall"
        const val TPACCEPT = "inmcessentials.teleport.tpaccept"
        const val TPDENY = "inmcessentials.teleport.tpdeny"
        const val TPTOGGLE = "inmcessentials.teleport.tptoggle"
        const val NO_DISTANCE = "inmcessentials.teleport.tpa.nodistance"
        const val TOGGLE_BYPASS = "inmcessentials.teleport.toggle.bypass"
        const val BACK = "inmcessentials.teleport.back"
        const val BACK_OTHERS = "inmcessentials.teleport.back.others"
        const val DBACK = "inmcessentials.teleport.dback"
        const val DBACK_OTHERS = "inmcessentials.teleport.dback.others"
        const val RESETBACK = "inmcessentials.teleport.resetback"
        const val RESETBACK_OTHERS = "inmcessentials.teleport.resetback.others"
    }
}
