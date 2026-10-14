package com.inmc.essentials.moderation

import com.inmc.essentials.util.Ph
import io.papermc.paper.event.player.AsyncChatEvent
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityMountEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.vehicle.VehicleMoveEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 제재의 막기 — 채팅(LOWEST 취소 · 채팅 순서 계약 6절) · 명령 · 이동 · 상호작용 · 접속 전(밴 화면·IP 잠금·IP 기록) · 접속(감옥·경보).
 * 상태는 [ModState](동시성 안전) — 비동기 채팅·접속 전 사건에서도 읽는다.
 */
class ModListener(private val mod: Moderation, private val jails: JailService, private val bans: Bans, private val records: Records) : Listener {

    private val ess get() = mod.ess
    private val state get() = mod.state
    private val settings get() = mod.settings

    /** 같은 안내를 너무 자주 보내지 않게(사람 → 마지막으로 보낸 시각). */
    private val noticed = ConcurrentHashMap<UUID, Long>()

    private fun throttled(id: UUID, everyMs: Long = 2_000): Boolean {
        val now = System.currentTimeMillis()
        val last = noticed[id]
        if (last != null && now - last < everyMs) return true
        noticed[id] = now
        return false
    }

    // --- 접속 전 (비동기) ----------------------------------------------------------------------------

    /** LOW — 우리 문구의 밴 화면(바닐라 검사보다 먼저 온다)·IP 잠금. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        if (event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED) return
        val ip = event.address.hostAddress
        if (settings.banOverrideLogin) {
            val entry = runCatching { bans.entry(event.uniqueId, event.name) }.getOrNull()
            if (entry != null && (entry.expiration?.time ?: Long.MAX_VALUE) > System.currentTimeMillis()) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, bans.screen(entry, ip = false))
                return
            }
            val ipEntry = ip?.let { runCatching { bans.ipEntry(it) }.getOrNull() }
            if (ipEntry != null && (ipEntry.expiration?.time ?: Long.MAX_VALUE) > System.currentTimeMillis()) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, bans.screen(ipEntry, ip = true))
                return
            }
        }
        val locked = state.lockIps[event.uniqueId]
        if (!locked.isNullOrEmpty() && ip != null && ip !in locked) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, ess.messages.component("moderation-lockip-denied"))
        }
    }

    /** MONITOR — 정말 들어오는 사람의 IP 를 적는다(기다림 없는 기록). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPreLoginRecord(event: AsyncPlayerPreLoginEvent) {
        if (event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED) return
        if (!settings.ipRecord || settings.ipRecordDelaySeconds > 0) return
        event.address.hostAddress?.let { records.recordIp(event.uniqueId, it) }
    }

    // --- 접속·나감 ----------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        jails.joined(player)
        if (state.isCuffed(player.uniqueId)) ess.messages.send(player, "moderation-cuff-target-on")
        records.informAlert(player)
        if (settings.ipRecord && settings.ipRecordDelaySeconds > 0) {
            player.scheduler.runDelayed(ess.plugin, { _ ->
                if (player.isOnline) player.address?.address?.hostAddress?.let { records.recordIp(player.uniqueId, it) }
            }, null, settings.ipRecordDelaySeconds * 20L)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        jails.quit(event.player)
        noticed.remove(event.player.uniqueId)
    }

    // --- 채팅 (LOWEST 취소 — 계획 6절) ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        val id = player.uniqueId
        val now = System.currentTimeMillis()

        state.lockChat(now)?.let { lock ->
            if (!player.hasPermission(ModerationFeature.MUTECHAT_BYPASS)) {
                event.isCancelled = true
                val left = lock.until?.let { (it - now) / 1000 }
                player.sendActionBar(ess.messages.component("moderation-mutechat-blocked",
                    Ph.of().time(mod.timeText(left)).reason(lock.reason ?: "-")))
                return
            }
        }
        if (state.isCuffed(id) && settings.cuffMute) {
            event.isCancelled = true
            ess.messages.send(player, "moderation-cuff-no-chat")
            return
        }
        state.activeMute(id, now)?.let { mute ->
            if (mute.shadow) {
                // 본인(그리고 기록용 콘솔)에게만 보인다 — 본인은 보낸 줄 안다.
                event.viewers().removeIf { it is Player && it.uniqueId != id }
            } else {
                event.isCancelled = true
                ess.messages.send(player, "moderation-muted-chat", Ph.of().time(mod.timeText(mute.remainingSeconds(now))).reason(mute.reason))
            }
            return
        }
        if (state.isJailed(id)) {
            val range = settings.jailChatRange
            when {
                range < 0 -> {
                    event.isCancelled = true
                    ess.messages.send(player, "moderation-jail-cant-talk")
                }
                range > 0 -> {
                    val origin = player.location
                    val max = range.toDouble() * range
                    event.viewers().removeIf { v ->
                        v is Player && v.uniqueId != id && (v.world != origin.world || v.location.distanceSquared(origin) > max)
                    }
                    if (!throttled(id, 10_000)) player.sendActionBar(ess.messages.component("moderation-jail-chat-range", Ph.of().value(range.toString())))
                }
            }
        }
    }

    // --- 명령 -------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val player = event.player
        val id = player.uniqueId
        val line = event.message.removePrefix("/")
        if (state.isCuffed(id) && !ModRules.commandAllowed(line, settings.cuffAllowed)) {
            event.isCancelled = true
            ess.messages.send(player, "moderation-cuff-no-command", Ph.of().raw("{목록}", settings.cuffAllowed.joinToString(", ") { "/$it" }))
            return
        }
        if (state.isJailed(id) && !ModRules.commandAllowed(line, settings.jailWhitelist)) {
            event.isCancelled = true
            ess.messages.send(player, "moderation-jail-command-blocked")
            return
        }
        val mute = state.activeMute(id, System.currentTimeMillis())
        if (mute != null && settings.muteBlockedCommands.isNotEmpty() && ModRules.commandAllowed(line, settings.muteBlockedCommands)) {
            event.isCancelled = true
            if (!mute.shadow) ess.messages.send(player, "moderation-muted-command")
        }
    }

    // --- 이동 -------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (!event.hasChangedPosition()) return
        val player = event.player
        val id = player.uniqueId
        if (state.isCuffed(id)) {
            val from = event.from
            val to = event.to
            // 탈것 위에서는 자리를 되돌려도 탈것이 간다 — 내리게 한다.
            if (player.isInsideVehicle) player.leaveVehicle()
            // 떨어지는 것만 둔다(공중에 묶으면 서버가 날기로 보고 내보낸다).
            if (from.x != to.x || from.z != to.z || to.y > from.y) {
                event.setTo(from.clone().apply { yaw = to.yaw; pitch = to.pitch })
                if (!throttled(id, 3_000)) player.sendActionBar(ess.messages.component("moderation-cuff-actionbar"))
            }
            return
        }
        if (state.isJailed(id) && event.hasChangedBlock()) {
            if (jails.insideOwnJail(id, event.from) && !jails.insideOwnJail(id, event.to)) {
                event.setTo(event.from.clone().apply { yaw = event.to.yaw; pitch = event.to.pitch })
                if (!throttled(id)) ess.messages.send(player, "moderation-jail-escape")
            }
        }
    }

    /** 감옥 밖으로 가는 순간이동은 막는다 — 단 이 기능이 감방으로 보내는 이동([JailService.isMoving])은 둔다(감방이 영역 밖이어도 끼지 않게). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        val player = event.player
        val id = player.uniqueId
        if (state.isJailed(id) && !jails.isMoving(id) && !jails.insideOwnJail(id, event.to)) {
            event.isCancelled = true
            if (!throttled(id)) ess.messages.send(player, "moderation-jail-escape")
            return
        }
        if (state.isCuffed(id) && event.cause.name in CUFF_BLOCKED_CAUSES) event.isCancelled = true
    }

    /** 포탈(네더·엔드·게이트웨이)은 HandlerList 가 따로라 [onTeleport] 가 받지 못한다 — 같은 규칙을 포탈에도. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPortal(event: PlayerPortalEvent) = onTeleport(event)

    /** 탈것·말에 타기(수갑) — 타고 있으면 어디든 간다. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onMount(event: EntityMountEvent) {
        val player = event.entity as? Player ?: return
        if (state.isCuffed(player.uniqueId)) event.isCancelled = true
    }

    /** 이미 타고 있던 사람(수갑을 채우기 전에 탔거나, 남이 태운) — 탈것이 움직이면 내린다. */
    @EventHandler(priority = EventPriority.LOW)
    fun onVehicleMove(event: VehicleMoveEvent) {
        if (state.cuffs.isEmpty()) return
        for (passenger in event.vehicle.passengers) {
            if (passenger is Player && state.isCuffed(passenger.uniqueId)) passenger.leaveVehicle()
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onRespawn(event: PlayerRespawnEvent) {
        val jailed = state.jailed[event.player.uniqueId] ?: return
        jails.cellLocation(jailed)?.let { event.setRespawnLocation(it) }
    }

    // --- 수갑: 상호작용 -------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW)
    fun onInteract(event: PlayerInteractEvent) {
        if (state.isCuffed(event.player.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        if (state.isCuffed(event.player.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (state.isCuffed(event.player.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (state.isCuffed(event.player.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        if (state.isCuffed(event.player.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onAttack(event: EntityDamageByEntityEvent) {
        val damager = event.damager
        val attacker = damager as? Player ?: (damager as? Projectile)?.shooter as? Player ?: return
        if (state.isCuffed(attacker.uniqueId)) event.isCancelled = true
    }

    // --- 감옥: 피해·배고픔 ------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (!settings.jailPreventDamage || !state.isJailed(player.uniqueId)) return
        if (event.cause.name in DAMAGE_ALWAYS) return
        event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onHunger(event: FoodLevelChangeEvent) {
        val player = event.entity as? Player ?: return
        if (settings.jailPreventHunger && state.isJailed(player.uniqueId) && event.foodLevel < player.foodLevel) event.isCancelled = true
    }

    companion object {
        /** 수갑 찬 사람이 스스로 하는 순간이동(진주·후렴과·포탈(→ [onPortal])·관전). 관리자가 옮기는 것(PLUGIN·COMMAND)은 둔다. */
        val CUFF_BLOCKED_CAUSES = setOf("ENDER_PEARL", "CHORUS_FRUIT", "CONSUMABLE_EFFECT", "NETHER_PORTAL", "END_PORTAL", "END_GATEWAY", "SPECTATE")

        /** 감옥에서도 막지 않는 피해(관리자 /kill · 공허). */
        val DAMAGE_ALWAYS = setOf("KILL", "VOID", "WORLD_BORDER")
    }
}
