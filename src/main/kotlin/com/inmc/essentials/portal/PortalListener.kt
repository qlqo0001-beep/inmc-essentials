package com.inmc.essentials.portal

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.vehicle.VehicleMoveEvent

/**
 * 들어서기 판정. **블록이 바뀐 걷기만** 본다(고개 돌리기·같은 블록 안 움직임은 바로 돌아간다) — 그다음은 청크 색인으로 그 청크의 포탈만.
 *
 * - HIGHEST: 쓸 수 없는 포탈로 들어서는 걷기를 취소하고 밀어낸다(보호 플러그인이 HIGH 까지 먼저 정한다 — 이미 취소된 걷기는 보지 않는다).
 * - MONITOR(취소되지 않은 것만): 이동·명령·효과. 끝까지 살아남은 걷기에서만 쏜다.
 * - 탈것(`VehicleMoveEvent` — 취소할 수 없는 사건): 탄 사람마다 같은 판정(광산 수레 선로·보트·말 포탈). 블록이 바뀐 움직임만.
 *
 * 도착은 판정하지 않고 "이미 안에 있음" 으로만 적는다 — 순간이동(`PlayerTeleportEvent`) · 네더/엔드 포탈(`PlayerPortalEvent` — 제 HandlerList 라
 * 따로 받는다) · 부활 · 월드 바꿈 · 접속. 도착한 자리가 포탈이어도 연쇄로 쏘지 않게(나갔다 들어오면 쏜다).
 */
class PortalListener(private val service: PortalService) : Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onMoveGuard(event: PlayerMoveEvent) {
        val from = event.from
        val to = event.to
        if (sameBlock(event)) return
        service.guard(event.player, from, to, event)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (sameBlock(event)) return
        service.moved(event.player, event.from, event.to)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onVehicleMove(event: VehicleMoveEvent) {
        val vehicle = event.vehicle
        if (vehicle.passengers.isEmpty() || sameBlock(event.from, event.to)) return
        service.ridden(vehicle, event.from, event.to)
    }

    private fun sameBlock(event: PlayerMoveEvent): Boolean = sameBlock(event.from, event.to)

    private fun sameBlock(from: org.bukkit.Location, to: org.bukkit.Location): Boolean =
        from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ && from.world == to.world

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        service.mark(event.player, event.to)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPortal(event: PlayerPortalEvent) {
        service.mark(event.player, event.to)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) {
        service.mark(event.player, event.respawnLocation)
    }

    /** 다른 플러그인이 사건 없이 옮긴 경우까지 — 월드가 바뀐 뒤 선 자리. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        service.mark(event.player, event.player.location)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        service.mark(event.player, event.player.location)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        service.forget(event.player)
    }
}
