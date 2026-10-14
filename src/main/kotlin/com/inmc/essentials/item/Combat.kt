package com.inmc.essentials.item

import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 마지막으로 맞은 때·싸운 때(메모리). 셜커 배낭의 "전투 중 금지"와 염색의 hit·combat 모드가 본다.
 * 다른 기능의 전투 상태(전투 기록 플러그인)는 없어서 여기서 직접 센다 — 사람·몹 어느 쪽이든 때리거나 맞으면 싸움.
 */
class Combat : Listener {

    private val hurt = ConcurrentHashMap<UUID, Long>()
    private val fought = ConcurrentHashMap<UUID, Long>()

    /** 맞은 순간 알림(염색의 즉시 다시 칠하기). 메인 스레드. */
    var onHurt: ((Player) -> Unit)? = null

    fun lastHurt(id: UUID): Long = hurt[id] ?: 0L

    fun inCombat(id: UUID, seconds: Int, now: Long = System.currentTimeMillis()): Boolean =
        now - (fought[id] ?: 0L) < seconds * 1000L

    /** 검증기용 — 메모리만. */
    internal fun mark(id: UUID, at: Long?) {
        if (at == null) { fought.remove(id); hurt.remove(id) } else { fought[id] = at; hurt[id] = at }
    }

    internal fun snapshot(id: UUID): Pair<Long?, Long?> = fought[id] to hurt[id]

    internal fun restore(id: UUID, saved: Pair<Long?, Long?>) {
        saved.first?.let { fought[id] = it } ?: fought.remove(id)
        saved.second?.let { hurt[id] = it } ?: hurt.remove(id)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val victim = event.entity as? Player ?: return
        hurt[victim.uniqueId] = System.currentTimeMillis()
        onHurt?.invoke(victim)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFight(event: EntityDamageByEntityEvent) {
        val now = System.currentTimeMillis()
        (event.entity as? Player)?.let { fought[it.uniqueId] = now }
        val damager = event.damager
        val attacker = damager as? Player ?: (damager as? Projectile)?.shooter as? Player
        attacker?.let { fought[it.uniqueId] = now }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        hurt.remove(event.player.uniqueId)
        fought.remove(event.player.uniqueId)
    }
}
