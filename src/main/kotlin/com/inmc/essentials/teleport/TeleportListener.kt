package com.inmc.essentials.teleport

import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent

/**
 * 이동 기능의 사건.
 *
 * - 뒤로: **모든** `PlayerTeleportEvent`(MONITOR, 취소된 것 빼고) — 바닐라 `/tp`·다른 플러그인의 이동도 기록된다(원인은 설정).
 * - 사망 지점: `PlayerDeathEvent`(MONITOR, 취소된 것 빼고).
 * - 이동 뒤 무적: 피해 취소(LOW — 다른 플러그인이 피해를 키우기 전에) · 무적인 사람이 때리는 것도 취소(설정) · 블록/상호작용이면 풀기.
 * - 접속 직전(비동기)에 DB 읽기 · 접속 때 넣기 · 나갈 때 빼기(요청은 상대에게 알림).
 */
class TeleportListener(private val feature: TeleportFeature, private val service: TeleportService) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        service.onTeleport(event.player, event.from, event.to, event.cause)
    }

    /** 다른 플러그인(부활·대결)이 취소한 죽음은 적지 않는다. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDeath(event: PlayerDeathEvent) {
        service.onDeath(event.player, event.player.location)
    }

    // --- 이동 뒤 무적 -----------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (event.cause in UNBLOCKABLE) return
        if (service.isInvulnerable(player.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onAttack(event: EntityDamageByEntityEvent) {
        if (!feature.settings.invulnerableNoAttack) return
        val attacker = when (val damager = event.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        } ?: return
        if (attacker != event.entity && service.isInvulnerable(attacker.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (feature.settings.invulnerableEndOnBlock) service.endInvulnerability(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (feature.settings.invulnerableEndOnBlock) service.endInvulnerability(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onInteract(event: PlayerInteractEvent) {
        if (!feature.settings.invulnerableEndOnInteract) return
        if (event.action == Action.RIGHT_CLICK_BLOCK || event.action == Action.LEFT_CLICK_BLOCK) service.endInvulnerability(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        if (feature.settings.invulnerableEndOnInteract) service.endInvulnerability(event.player.uniqueId)
    }

    // --- 수락 뒤 대기 중 움직임 --------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val to = event.to
        if (event.from.blockX == to.blockX && event.from.blockY == to.blockY && event.from.blockZ == to.blockZ) return
        if (!feature.settings.tpaCancelOnMove || !service.hasWarmup(event.player.uniqueId)) return
        feature.tpa.movedDuringWarmup(event.player)
    }

    // --- 접속 · 나감 ------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        if (event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED) return
        service.preload(event.uniqueId)
    }

    /** HIGH — 스폰 기능의 첫 접속 이동(NORMAL)보다 뒤에, 관리자가 부른 자리가 이기게(한 틱 뒤 옮긴다). */
    @EventHandler(priority = EventPriority.HIGH)
    fun onJoin(event: PlayerJoinEvent) {
        val found = service.joined(event.player) ?: return
        feature.applyPending(event.player, found)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val gone = service.quit(event.player.uniqueId)
        feature.tpa.leftWith(event.player, gone)
    }

    private companion object {
        /** 무적이어도 막지 않는 피해 — 허공에 떨어져 끝없이 떨어지거나 /kill 이 안 먹는 일이 없게. */
        val UNBLOCKABLE = setOf(EntityDamageEvent.DamageCause.VOID, EntityDamageEvent.DamageCause.KILL, EntityDamageEvent.DamageCause.SUICIDE)
    }
}
