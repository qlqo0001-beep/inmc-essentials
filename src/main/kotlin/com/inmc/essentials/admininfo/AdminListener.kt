package com.inmc.essentials.admininfo

import com.destroystokyo.paper.event.entity.PlayerNaturallySpawnCreaturesEvent
import com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent
import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import io.papermc.paper.event.player.AsyncChatEvent
import kr.inmc.core.integration.Vanish
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityTargetEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerPickupArrowEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.GenericGameEvent

/**
 * admininfo 의 사건 전부. 투명 옵션의 막기는 HIGH(다른 기능이 먼저 정한 취소를 존중 — `ignoreCancelled`), 접속 표식은 LOWEST,
 * 접속·퇴장 메시지 손질은 HIGHEST(타이틀포지가 HIGH 에서 정한 뒤 — 디스코드의 MONITOR 보다 먼저).
 */
internal class AdminListener(private val ess: Ess, private val feature: AdminInfoFeature) : Listener {

    private val vanish get() = feature.vanish

    private fun hidden(player: Player) = vanish.isActive(player)

    // --- 접속 ---------------------------------------------------------------------------------------

    /** 공용 DB — 다른 서버에서 바뀐 그 사람의 줄을 다시 읽는다(비동기 사건이라 기다려도 된다). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        if (event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED) return
        val id = event.uniqueId
        // 투명으로 들어오면 못 보는 사람에게는 지난 접속을 보인다 — 사용자 기록이 이번 접속으로 바뀌기 전에 잡아 둔다.
        vanish.rememberJoin(id)
        if (ess.db.network == null) return
        runCatching {
            vanish.preload(id)
            feature.playtime.preload(id)
            feature.prewards.preload(id)
            feature.votes.preload(id)
        }.onFailure { ess.logger.warning("공용 DB 에서 ${event.name} 의 admininfo 자료를 다시 읽지 못했습니다: $it") }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoinEarly(event: PlayerJoinEvent) {
        vanish.joinEarly(event.player)
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onJoin(event: PlayerJoinEvent) {
        feature.playtime.join(event.player)
    }

    /** 타이틀포지(HIGH)가 투명인 사람의 메시지를 뺀 뒤 — "접속 메시지 보이기" 면 우리 모양으로 다시 넣고, 아니면 확실히 뺀다. */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onJoinMessage(event: PlayerJoinEvent) {
        val player = event.player
        if (!hidden(player)) return
        if (vanish.option(player, VanishOption.INFORM_ON_JOIN)) {
            if (event.joinMessage() == null) event.joinMessage(ess.messages.component("admininfo-vanish-fake-join", Ph.of().player(VanishService.displayName(player))))
        } else {
            event.joinMessage(null)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoined(event: PlayerJoinEvent) {
        vanish.joined(event.player)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onQuitMessage(event: PlayerQuitEvent) {
        val player = event.player
        if (!hidden(player)) return
        if (vanish.option(player, VanishOption.INFORM_ON_LEAVE)) {
            if (event.quitMessage() == null) event.quitMessage(ess.messages.component("admininfo-vanish-fake-quit", Ph.of().player(VanishService.displayName(player))))
        } else {
            event.quitMessage(null)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        feature.playtime.quit(player)
        feature.prewards.flush(player.uniqueId)
        feature.prewards.forget(player.uniqueId)
        vanish.quit(player)
    }

    // --- 투명 옵션 -------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDamaged(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (!hidden(player)) return
        if (event.cause == EntityDamageEvent.DamageCause.VOID || event.cause == EntityDamageEvent.DamageCause.KILL) return
        if (!vanish.option(player, VanishOption.PLAYER_DAMAGE)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onAttack(event: EntityDamageByEntityEvent) {
        val attacker = when (val d = event.damager) {
            is Player -> d
            is Projectile -> d.shooter as? Player
            else -> null
        } ?: return
        if (hidden(attacker) && !vanish.option(attacker, VanishOption.DAMAGE_TO_ENTITY)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        if (hidden(player) && !vanish.option(player, VanishOption.ITEM_PICKUP)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onArrow(event: PlayerPickupArrowEvent) {
        if (hidden(event.player) && !vanish.option(event.player, VanishOption.ITEM_PICKUP)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onExp(event: PlayerPickupExperienceEvent) {
        if (hidden(event.player) && !vanish.option(event.player, VanishOption.ITEM_PICKUP)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onTarget(event: EntityTargetEvent) {
        val player = event.target as? Player ?: return
        if (hidden(player) && !vanish.option(player, VanishOption.MOB_AGGRO)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onPhysical(event: PlayerInteractEvent) {
        if (event.action != Action.PHYSICAL || !hidden(event.player)) return
        if (!vanish.option(event.player, VanishOption.INTERACTION)) event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY)
    }

    /** 발걸음·스컬크 진동 — 투명인 사람이 낸 게임 사건은 늘 막는다(소리는 hidePlayer 가 가리지만 진동 감지기는 서버 쪽이다). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onGameEvent(event: GenericGameEvent) {
        val player = event.entity as? Player ?: return
        if (hidden(player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSpawnNear(event: PlayerNaturallySpawnCreaturesEvent) {
        if (hidden(event.player) && !vanish.option(event.player, VanishOption.MOB_SPAWNING)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onFish(event: PlayerFishEvent) {
        if (event.state != PlayerFishEvent.State.CAUGHT_ENTITY || !hidden(event.player)) return
        if (!vanish.option(event.player, VanishOption.HOOK_PLAYERS)) event.isCancelled = true
    }

    /** 투명인 동안 공개 채팅 막기(옵션). 비동기 사건 — 메모리만 본다. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        if (!hidden(player) || !vanish.option(player, VanishOption.NO_MESSAGES)) return
        event.isCancelled = true
        ess.messages.send(player, "admininfo-vanish-no-chat")
    }

    /**
     * 죽음 메시지 — 옵션이 꺼져 있으면 볼 수 있는 사람(과 본인·콘솔)에게만. **MONITOR** 에서: 다른 플러그인(던전 HIGHEST 등)이 메시지를 지웠거나
     * 사건을 취소했으면 아무것도 하지 않는다(HIGHEST 끼리는 순서가 등록 순이라 거기서는 지울지 알 수 없다). 메시지는 사건이 끝난 뒤 읽히므로
     * MONITOR 에서 비워도 방송되지 않는다. 디스코드(MONITOR)는 투명인 사람의 죽음을 어차피 보내지 않는다.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDeath(event: PlayerDeathEvent) {
        val player = event.player
        if (!hidden(player) || vanish.option(player, VanishOption.DEATH_MESSAGES)) return
        val message = event.deathMessage() ?: return
        event.deathMessage(null)
        Bukkit.getConsoleSender().sendMessage(message)
        for (other in Bukkit.getOnlinePlayers()) if (Vanish.canSee(other, player)) other.sendMessage(message)
    }

    /** 발전과제 알림 — 투명인 사람의 것은 볼 수 있는 사람에게만(투명이 새는 곳). */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onAdvancement(event: PlayerAdvancementDoneEvent) {
        val player = event.player
        if (!hidden(player)) return
        val message = event.message() ?: return
        event.message(null)
        for (other in Bukkit.getOnlinePlayers()) if (Vanish.canSee(other, player)) other.sendMessage(message)
    }
}
