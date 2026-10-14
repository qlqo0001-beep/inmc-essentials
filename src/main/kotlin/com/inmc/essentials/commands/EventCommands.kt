package com.inmc.essentials.commands

import com.inmc.essentials.Ess
import kr.inmc.core.util.Text
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.Cancellable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityToggleGlideEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.event.player.PlayerBedEnterEvent
import org.bukkit.event.player.PlayerBedLeaveEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerKickEvent
import org.bukkit.event.player.PlayerLevelChangeEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.player.PlayerTeleportEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 이벤트 명령(`commands/events.yml` — CMI `EventCommands.yml` 과 같은 이름·모양). 사건이 일어나면 그 사건의 `Commands` 를 콘솔로(기본) 돌린다.
 * `playerKillPlayer`·`playerDeath` 는 죽인 사람이 있으면 `SourceCommands` 를 죽인 사람을 주인공으로 돌린다. `CancelEvent` 는 취소할 수 있는
 * 사건에서만 뜻이 있다(아래 [CANCELLABLE]).
 *
 * 리스너는 MONITOR(결과만 본다) — 취소를 거는 사건만 HIGH 에서 보고 그 자리에서 취소한다.
 */
class EventCommands(private val ess: Ess, private val feature: CommandsFeature) : Listener {

    @Volatile
    var rules: Map<String, EventRule> = emptyMap()

    /** 마지막으로 돈 사건(검증기가 본다). */
    @Volatile
    var lastFired: String? = null

    private fun fire(id: String, player: Player?, source: Player? = null, values: Map<String, String> = emptyMap()): EventRule? {
        val rule = rules[id]?.takeIf { it.enabled } ?: return null
        lastFired = id
        if (rule.commands.isNotEmpty() && player != null) {
            feature.runner.run(rule.commands, CommandRunner.Context(player, source, Bukkit.getConsoleSender(), values, origin = "이벤트 $id"))
        }
        if (rule.sourceCommands.isNotEmpty() && source != null) {
            feature.runner.run(rule.sourceCommands, CommandRunner.Context(source, player, Bukkit.getConsoleSender(), values, origin = "이벤트 $id(죽인 쪽)"))
        }
        return rule
    }

    /** 취소할 수 있는 사건 — 규칙이 `CancelEvent` 면 취소. */
    private fun fireCancellable(id: String, player: Player, event: Cancellable, values: Map<String, String> = emptyMap()) {
        val rule = fire(id, player, null, values) ?: return
        if (rule.cancel) event.isCancelled = true
    }

    // 첫 접속은 접속 기록이 만들어진 뒤(LOWEST 뒤)에 안다. CMI 처럼 첫 접속에는 joinServer 를 돌리지 않는다.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (ess.users.isFirstJoin(player)) fire(FIRST_JOIN, player) else fire(JOIN, player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        fire(QUIT, event.player)
        combat.remove(event.player.uniqueId)
        voidFalls.remove(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDeath(event: PlayerDeathEvent) {
        val player = event.entity
        val killer = player.killer?.takeIf { it != player }
        fire(DEATH, player, killer)
        if (killer != null) fire(KILL_PLAYER, player, killer)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) {
        // 부활 자리로 옮겨진 뒤에 돌리려고 한 틱 뒤.
        val player = event.player
        player.scheduler.run(ess.plugin, { _ -> fire(RESPAWN, player) }, null)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        fire(TELEPORT, event.player, null, mapOf("cause" to event.cause.name.lowercase()))
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPreWorldChange(event: PlayerTeleportEvent) {
        if (event.from.world == event.to.world) return
        fireCancellable(PRE_WORLD_CHANGE, event.player, event, mapOf("value" to (event.to.world?.name ?: ""), "value2" to (event.from.world?.name ?: "")))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        fire(WORLD_CHANGE, event.player, null, mapOf("value" to event.player.world.name, "value2" to event.from.name))
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBedEnter(event: PlayerBedEnterEvent) {
        if (event.bedEnterResult != PlayerBedEnterEvent.BedEnterResult.OK) return
        fireCancellable(BED_ENTER, event.player, event)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onBedLeave(event: PlayerBedLeaveEvent) {
        fire(BED_LEAVE, event.player)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onGameMode(event: PlayerGameModeChangeEvent) {
        fireCancellable(GAMEMODE_CHANGE, event.player, event, mapOf("value" to event.newGameMode.name.lowercase(), "value2" to event.player.gameMode.name.lowercase()))
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onKick(event: PlayerKickEvent) {
        val reason = Text.plain(event.reason())
        if (event.cause == PlayerKickEvent.Cause.BANNED || event.cause == PlayerKickEvent.Cause.IP_BANNED) {
            fireCancellable(BAN, event.player, event, mapOf("value" to reason))
        } else {
            fireCancellable(KICK, event.player, event, mapOf("value" to reason))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onLevel(event: PlayerLevelChangeEvent) {
        fire(LEVEL_CHANGE, event.player, null, mapOf("value" to event.newLevel.toString(), "value2" to event.oldLevel.toString()))
    }

    private val voidFalls = ConcurrentHashMap<UUID, Long>()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onVoid(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (event.cause != EntityDamageEvent.DamageCause.VOID) return
        val now = System.currentTimeMillis()
        val last = voidFalls[player.uniqueId]
        if (last != null && now - last < 5000) return
        voidFalls[player.uniqueId] = now
        fire(VOID_FALL, player)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onGlide(event: EntityToggleGlideEvent) {
        val player = event.entity as? Player ?: return
        if (event.isGliding) fireCancellable(ELYTRA_START, player, event) else fire(ELYTRA_END, player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onAdvancement(event: PlayerAdvancementDoneEvent) {
        val display = event.advancement.display ?: return // 레시피 해금 같은 숨은 발전 과제는 빼고
        fire(ADVANCEMENT, event.player, null, mapOf("value" to event.advancement.key.key, "value2" to Text.plain(display.title())))
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSwap(event: PlayerSwapHandItemsEvent) {
        if (event.player.isSneaking) fireCancellable(SNEAK_SWAP, event.player, event) else fireCancellable(SWAP, event.player, event)
    }

    // --- 싸움 시작·끝(CMI pvpstart/pvpend) — 플레이어끼리 맞으면 시작, settings combat-seconds 동안 없으면 끝 ---

    private val combat = ConcurrentHashMap<UUID, Long>()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPvp(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val attacker = (event.damager as? Player) ?: ((event.damager as? Projectile)?.shooter as? Player) ?: return
        if (attacker == victim) return
        if (rules[PVP_START]?.enabled != true && rules[PVP_END]?.enabled != true) return
        val now = System.currentTimeMillis()
        for (p in listOf(attacker, victim)) {
            if (combat.put(p.uniqueId, now) == null) fire(PVP_START, p, if (p == attacker) victim else attacker)
        }
    }

    /** 1초마다(CommandsFeature 의 틱) — 싸움이 끝난 사람. */
    fun tick(now: Long) {
        if (combat.isEmpty()) return
        val limit = feature.settings.combatSeconds * 1000
        for ((id, last) in combat.entries.toList()) {
            if (now - last < limit) continue
            combat.remove(id)
            Bukkit.getPlayer(id)?.let { fire(PVP_END, it) }
        }
    }

    companion object {
        const val FIRST_JOIN = "firstJoinServer"
        const val JOIN = "joinServer"
        const val QUIT = "quitServer"
        const val DEATH = "playerDeath"
        const val KILL_PLAYER = "playerKillPlayer"
        const val RESPAWN = "playerRespawn"
        const val TELEPORT = "playerTeleport"
        const val BED_ENTER = "bedEnter"
        const val BED_LEAVE = "bedLeave"
        const val WORLD_CHANGE = "playerWorldChange"
        const val PRE_WORLD_CHANGE = "playerPreWorldChange"
        const val GAMEMODE_CHANGE = "playerGameModeChange"
        const val KICK = "playerKick"
        const val BAN = "playerBan"
        const val LEVEL_CHANGE = "playerLevelChange"
        const val VOID_FALL = "voidFall"
        const val ELYTRA_START = "elytraStartGlide"
        const val ELYTRA_END = "elytraEndGlide"
        const val ADVANCEMENT = "advancementDone"
        const val SNEAK_SWAP = "sneakingSwapHandItems"
        const val SWAP = "swapHandItems"
        const val PVP_START = "pvpstart"
        const val PVP_END = "pvpend"

        val ALL: List<String> = listOf(
            FIRST_JOIN, JOIN, QUIT, DEATH, KILL_PLAYER, RESPAWN, TELEPORT, BED_LEAVE, BED_ENTER, WORLD_CHANGE, PRE_WORLD_CHANGE,
            GAMEMODE_CHANGE, KICK, BAN, LEVEL_CHANGE, VOID_FALL, ELYTRA_START, ADVANCEMENT, ELYTRA_END, SNEAK_SWAP, SWAP, PVP_START, PVP_END,
        )

        /** `CancelEvent` 가 뜻이 있는 사건. */
        val CANCELLABLE: Set<String> = setOf(PRE_WORLD_CHANGE, BED_ENTER, GAMEMODE_CHANGE, KICK, BAN, ELYTRA_START, SNEAK_SWAP, SWAP)
    }
}
