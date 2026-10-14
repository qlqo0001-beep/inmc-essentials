package com.inmc.essentials.world

import com.inmc.essentials.Ess
import com.inmc.essentials.service.Abilities
import com.inmc.essentials.util.Ph
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityToggleGlideEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerToggleFlightEvent
import java.util.UUID

/**
 * 월드별 제한(CMI WorldLimits · worldLimitsElytra) — 게임모드 · 비행 · 무적 · 지붕 위 비행 · 겉날개 · 몹 스폰 사유.
 * 들어올 때(접속·월드 이동·부활)와 [WorldSettings.Limits.checkEverySeconds] 마다 맞춘다. 우회 권한은 제한마다 따로(`inmcessentials.world.limit.*`).
 *
 * 무적은 player 기능의 상태라 직접 끌 수 없다(기능끼리 import 금지 — CLAUDE.md 1). 접점 `ess.abilities`(2026-10-14 — 전에는 그 기능의 메서드를
 * 이름으로 불렀다)로 `isGod`(무적 또는 시간제 무적) · `setGod(false)`(둘 다 끈다 — 저장도 그 기능이) · `wantsFlight`(`/비행` 또는 시간제 비행)을 본다.
 * 끄고도 무적이 남으면(다른 플러그인의 무적) 그 사람·그 월드에서는 다시 시도하지 않고 한 번만 로그를 남긴다(5초마다 알림이 쌓이지 않게 —
 * 월드를 옮기거나 다시 접속하면 다시 본다).
 *
 * 비행은 우리가 끈 사람을 기억했다가 제한 없는 월드로 나가면 돌려준다(CMI 는 돌려주지 않았다 — 나은 점). 단 그때도 player 기능이 아직 날게 해
 * 주는 사람(`wantsFlight` — 시간제 비행이 그 사이 끝났거나 `/비행 끄기` 를 받았으면 아니다)에게만 — player 기능이 없으면 예전처럼 돌려준다.
 */
class WorldLimits(private val ess: Ess, private val settings: () -> WorldSettings) : Listener {

    /** 우리가 비행을 끈 사람. */
    private val flightRemoved = HashSet<UUID>()

    /** 무적을 끄려다 못 끈 사람 — 이 월드에 있는 동안 다시 시도하지 않는다(나가기·월드 이동에서 지운다). */
    private val godGaveUp = HashSet<UUID>()

    /** 검증기 — 우회 권한을 무시한다(검증하는 사람은 관리자라 늘 우회한다). */
    @Volatile
    internal var ignoreBypass = false

    private var counter = 0

    private fun limits() = settings().limits

    private fun bypass(player: Player, node: String): Boolean = !ignoreBypass && player.hasPermission(node)

    // --- 들어올 때 -----------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        // player 기능이 접속 때 저장된 비행을 다시 켠다 — 그 뒤에 본다.
        later(event.player, 3L)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorld(event: PlayerChangedWorldEvent) {
        godGaveUp -= event.player.uniqueId
        later(event.player, 1L)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) {
        // player 기능이 부활 2틱 뒤 비행을 돌려줄 수 있다 — 그 뒤에.
        later(event.player, 4L)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        forget(event.player.uniqueId)
    }

    /** 이 사람에 대한 기억을 지운다(나감 · 검증기). */
    fun forget(id: UUID) {
        flightRemoved -= id
        godGaveUp -= id
    }

    private fun later(player: Player, ticks: Long) {
        if (!limits().enabled) return
        player.scheduler.runDelayed(ess.plugin, { _ -> if (player.isOnline) enforce(player, entering = true) }, null, ticks)
    }

    /** 주기 검사(1초 틱에서 부른다). */
    fun tick(now: Long) {
        val l = limits()
        if (!l.enabled) return
        if (++counter < l.checkEverySeconds) return
        counter = 0
        for (player in Bukkit.getOnlinePlayers()) enforce(player, entering = false)
    }

    /** 한 사람을 이 월드의 제한에 맞춘다. 바꾼 것이 있으면 true. */
    fun enforce(player: Player, entering: Boolean): Boolean {
        val l = limits()
        if (!l.enabled) return false
        val world = player.world.name
        var changed = false

        l.gamemodeOf(world)?.let { name ->
            val mode = GameMode.valueOf(name.uppercase())
            if (player.gameMode != mode && !bypass(player, WorldFeature.LIMIT_GAMEMODE_BYPASS)) {
                player.gameMode = mode
                ess.messages.send(player, "world-limit-gamemode", Ph.of().world(world).value(ess.messages.raw("world-gamemode-$name")))
                changed = true
            }
        }

        val survival = player.gameMode == GameMode.SURVIVAL || player.gameMode == GameMode.ADVENTURE
        if (l.flyBlocked(world)) {
            if (survival && player.allowFlight && !bypass(player, WorldFeature.LIMIT_FLY_BYPASS)) {
                player.isFlying = false
                player.allowFlight = false
                flightRemoved += player.uniqueId
                ess.messages.send(player, "world-limit-fly", Ph.of().world(world))
                changed = true
            }
        } else if (entering && flightRemoved.remove(player.uniqueId) && survival && !player.allowFlight && stillMayFly(player)) {
            player.allowFlight = true
            ess.messages.send(player, "world-limit-fly-restored")
        }

        l.roofOf(world)?.let { roof ->
            if (player.isFlying && player.location.y > roof && !bypass(player, WorldFeature.LIMIT_ABOVE_ROOF) && player.gameMode != GameMode.SPECTATOR) {
                player.isFlying = false
                ess.messages.send(player, "world-limit-roof", Ph.of().value(roof.toString()))
                changed = true
            }
        }

        if (l.godBlocked(world) && !bypass(player, WorldFeature.LIMIT_GOD_BYPASS) && player.uniqueId !in godGaveUp && isGod(player)) {
            // player 기능으로 끈다(위 KDoc) — setGod 은 시간제 무적까지 끈다.
            ess.abilities.setGod(player, false)
            if (isGod(player)) {
                godGaveUp += player.uniqueId
                ess.logger.warning("월드 제한: ${player.name} 의 무적을 끄지 못했습니다($world) — 이 월드에 있는 동안 다시 시도하지 않습니다")
            } else {
                ess.messages.send(player, "world-limit-god", Ph.of().world(world))
                changed = true
            }
        }

        if (l.elytraBlocked(world) && !bypass(player, WorldFeature.LIMIT_ELYTRA_BYPASS)) {
            if (player.isGliding) {
                player.isGliding = false
                changed = true
            }
            val chest = player.inventory.chestplate
            if (entering && l.elytraUnequip && chest != null && chest.type == Material.ELYTRA) {
                val left = player.inventory.addItem(chest.clone())
                if (left.isEmpty()) {
                    player.inventory.setItem(org.bukkit.inventory.EquipmentSlot.CHEST, org.bukkit.inventory.ItemStack(Material.AIR))
                    ess.messages.send(player, "world-limit-elytra-unequipped", Ph.of().world(world))
                } else {
                    ess.messages.send(player, "world-limit-elytra", Ph.of().world(world))
                }
                changed = true
            }
        }
        return changed
    }

    // --- 막기 ----------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onGlide(event: EntityToggleGlideEvent) {
        val player = event.entity as? Player ?: return
        val l = limits()
        if (!event.isGliding || !l.enabled || !l.elytraBlocked(player.world.name) || bypass(player, WorldFeature.LIMIT_ELYTRA_BYPASS)) return
        event.isCancelled = true
        player.sendActionBar(ess.messages.component("world-limit-elytra", Ph.of().world(player.world.name), player))
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onFlight(event: PlayerToggleFlightEvent) {
        if (!event.isFlying) return
        val player = event.player
        val l = limits()
        if (!l.enabled) return
        val world = player.world.name
        val survival = player.gameMode == GameMode.SURVIVAL || player.gameMode == GameMode.ADVENTURE
        if (survival && l.flyBlocked(world) && !bypass(player, WorldFeature.LIMIT_FLY_BYPASS)) {
            event.isCancelled = true
            player.allowFlight = false
            flightRemoved += player.uniqueId
            return
        }
        val roof = l.roofOf(world) ?: return
        if (player.location.y > roof && !bypass(player, WorldFeature.LIMIT_ABOVE_ROOF)) {
            event.isCancelled = true
            player.sendActionBar(ess.messages.component("world-limit-roof", Ph.of().value(roof.toString()), player))
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSpawn(event: CreatureSpawnEvent) {
        val l = limits()
        if (!l.enabled || l.spawnReasons.isEmpty()) return
        if (l.spawnBlocked(event.location.world.name, event.spawnReason.name)) event.isCancelled = true
    }

    // --- player 기능의 무적·비행(접점 ess.abilities — 위 KDoc) ------------------------------------------

    private fun isGod(player: Player): Boolean = ess.abilities.isGod(player)

    /**
     * 검증기 — player 기능이 켜져 있으면 접점 `ess.abilities` 가 끼워져 있어야 한다(player 기능이 없으면 null — 볼 것이 없다). 안 끼워져 있으면
     * 무적 금지 월드가 소리 없이 꺼진다(isGod 이 늘 false — 2026-10-10 리뷰).
     */
    internal fun abilitiesMissing(): Boolean? {
        ess.feature("player") ?: return null
        return ess.abilities === Abilities.NONE
    }

    /** 돌려줘도 되나 — player 기능이 아직 날게 해 주는 사람(그 기능이 없으면 예전처럼 true). */
    private fun stillMayFly(player: Player): Boolean = ess.abilities === Abilities.NONE || ess.abilities.wantsFlight(player.uniqueId)

    fun clear() {
        flightRemoved.clear()
        godGaveUp.clear()
    }
}
