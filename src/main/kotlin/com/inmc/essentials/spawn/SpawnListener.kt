package com.inmc.essentials.spawn

import com.inmc.essentials.Ess
import com.inmc.essentials.service.TeleportKind
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent

/**
 * 접속 자리 · 첫 접속(킷) · 부활 순서 · 접속·부활 무적. inmc-menu 의 `SpawnListener` 를 옮겨 왔다(2026-10-10).
 *
 * - 접속 자리: `AsyncPlayerSpawnLocationEvent` **HIGH** — 들어오기 전에 정한다(들어온 뒤 순간이동이 보이지 않게). 비동기라 설정을 읽기만 한다.
 *   던전 `GuardListener`(HIGHEST)가 뒤에서 이겨야 크러시 뒤 복귀가 맞다 — HIGHEST 로 올리지 않는다.
 * - 부활: **HIGH** 에서 순서대로 처음 되는 곳. 침대·앵커는 그 부활이 침대·앵커일 때 그대로. **엔드 귀환(END_PORTAL)은 건드리지 않는다.**
 *   던전 `RunListener`(HIGHEST)가 판 안 부활을 이긴다. CMI(HIGH)가 켜져 있으면 켜진 순서로 갈린다 — CMI `ReSpawn.Enabled: false` 로.
 * - 첫 접속 판정: 접속 자리 사건의 `isNewPlayer`(바닐라 — 이 서버에서 논 적 없음)와 `ess.users.isFirstJoin`(이 플러그인의 기록) 이
 *   **둘 다** 참일 때만 첫 접속 킷을 준다 — 이 플러그인을 새로 깐 날 모든 사람이 "처음" 으로 보여 킷을 받는 일이 없게.
 */
class SpawnListener(private val ess: Ess, private val feature: SpawnFeature, private val service: SpawnService) : Listener {

    @EventHandler(priority = EventPriority.HIGH)
    fun onSpawnLocation(event: AsyncPlayerSpawnLocationEvent) {
        val id = event.connection.profile.id
        if (id != null) {
            // 지난번 접속이 접속 사건 전에 끊겼으면 남은 표시가 있다 — 이번 판단만 쓴다.
            service.newPlayers.remove(id)
            service.movedOnJoin.remove(id)
            if (event.isNewPlayer) service.newPlayers += id
        }
        val c = service.config
        when (c.joinTarget(event.isNewPlayer, event.spawnLocation.world?.name, feature.settings.ignoredWorlds)) {
            JoinTarget.FIRST -> service.firstTarget()?.let { event.spawnLocation = it }
            JoinTarget.SERVER -> c.point?.location()?.let { target ->
                val original = event.spawnLocation.clone()
                event.spawnLocation = target
                if (id != null) service.movedOnJoin[id] = SpawnService.JoinMove(original, target)
            }
            JoinTarget.NONE -> Unit
        }
    }

    /** 뒤의 플러그인(던전 `GuardListener` HIGHEST 의 복귀 자리 등)이 자리를 다시 정했으면 우회 되돌리기를 하지 않는다. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onSpawnLocationDecided(event: AsyncPlayerSpawnLocationEvent) {
        val id = event.connection.profile.id ?: return
        val move = service.movedOnJoin[id] ?: return
        if (event.spawnLocation != move.ours) service.movedOnJoin.remove(id, move)
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        val id = player.uniqueId
        val vanillaNew = service.newPlayers.remove(id)
        val recordNew = ess.users.isFirstJoin(player)
        if (vanillaNew != recordNew) {
            ess.logger.info("첫 접속 판정이 다릅니다 — ${player.name}: 바닐라 ${if (vanillaNew) "처음" else "논 적 있음"} · 에센셜 기록 ${if (recordNew) "처음" else "있음"}" +
                " (둘 다 처음일 때만 첫 접속 킷을 줍니다)")
        }
        val settings = feature.settings
        service.protect(id, settings.joinInvulnerable)

        // 매 접속 스폰 우회 — 비동기 사건에는 권한이 없어 일단 옮기고 여기서 원래 접속 자리(서버가 저장해 둔 나간 자리)로 돌려보낸다.
        val move = service.movedOnJoin.remove(id)
        if (move != null && player.hasPermission(SpawnFeature.JOIN_BYPASS) && move.original.isWorldLoaded) {
            ess.teleporter.teleport(player, move.original, TeleportKind.OTHER)
        }

        if (vanillaNew && recordNew && settings.firstJoinKit) {
            // 한 틱 뒤 — 접속 처리(가방 읽기·다른 플러그인의 접속 아이템)가 끝난 뒤에 준다. 한 번만 주기는 kits 의 몫.
            player.scheduler.runDelayed(ess.plugin, { ess.kits?.giveFirstJoin(player) }, null, 2L)
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onRespawn(event: PlayerRespawnEvent) {
        if (!feature.settings.handleRespawn) return
        if (event.respawnReason == PlayerRespawnEvent.RespawnReason.END_PORTAL) return
        // 감옥이면 moderation(같은 HIGH, 뒤에 등록)이 감방으로 — 등록 순서에 기대지 않게 여기서 비킨다.
        if (ess.moderation.isJailed(event.player.uniqueId)) return
        val target = service.respawnLocation(event.player, event.isBedSpawn, event.isAnchorSpawn) ?: return
        event.respawnLocation = target
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawnDone(event: PlayerRespawnEvent) {
        if (event.respawnReason == PlayerRespawnEvent.RespawnReason.END_PORTAL) return
        service.protect(event.player.uniqueId, feature.settings.respawnInvulnerable)
    }

    /** 접속·부활 무적 — 공허·/kill·자살·월드 경계는 막지 않는다(갇히지 않게). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (event.cause.name in UNBLOCKABLE) return
        if (service.isProtected(player.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        service.forget(event.player.uniqueId)
    }

    private companion object {
        val UNBLOCKABLE = setOf("VOID", "KILL", "SUICIDE", "WORLD_BORDER")
    }
}
