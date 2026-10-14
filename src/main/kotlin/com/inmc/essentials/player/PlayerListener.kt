package com.inmc.essentials.player

import com.inmc.essentials.Ess
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.potion.PotionEffect
import java.util.UUID

/**
 * player 기능의 사건 — 무적 · 비행 유지 · 시간제 비행 끝난 뒤 착지 보호 · 탄 채 질식 · 모자 칸 · 의자 · 접속/나감.
 */
class PlayerListener(private val ess: Ess, private val feature: PlayerFeature) : Listener {

    private val config get() = feature.config
    private val states get() = feature.states
    private val seats get() = feature.seats

    /** 접속 직전에 읽은 지난 접속 시각 · 나간 시각(비동기 사건이 넣고 접속 때 뺀다). */
    private val previousVisit = java.util.concurrent.ConcurrentHashMap<UUID, Pair<Long, Long?>>()

    /** 죽기 전에 날 수 있던 사람(다시 살아나면 비행을 돌려준다 — 설정). */
    private val flewBeforeDeath = HashSet<UUID>()

    // --- 무적 ----------------------------------------------------------------------------------

    /**
     * LOW — 다른 플러그인이 피해를 키우기 전에. 허공·바닐라 `/kill`(KILL)은 막지 않는다(끝없이 떨어지거나 관리자 처치가 안 먹는 일이 없게).
     * `/처치` 는 무적이면 명령에서 먼저 막는다(`-forced` 로 넘는다).
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (feature.isForcedKill(player.uniqueId)) return // `/처치 -forced` — 무적도 무시
        when (event.cause) {
            EntityDamageEvent.DamageCause.VOID, EntityDamageEvent.DamageCause.KILL -> return
            EntityDamageEvent.DamageCause.SUFFOCATION -> if (config.rideEjectOnSuffocation && player.vehicle is Player) {
                // CMI rideAnimation — 사람 위에 탄 채 블록에 끼면 내려 준다.
                event.isCancelled = true
                player.leaveVehicle()
                return
            }
            EntityDamageEvent.DamageCause.FALL -> if (feature.consumeLanding(player.uniqueId)) {
                event.isCancelled = true
                return
            }
            else -> Unit
        }
        if (feature.isGod(player)) {
            event.isCancelled = true
            player.fireTicks = 0
        }
    }

    /**
     * 맞으면 일어나기(`sit.stand-on-damage`) — **MONITOR**: 뒤에서 취소될 피해(잠수 보호·보호구역 PvP 금지·던전 유예·이동 뒤 무적)로는 일으키지 않는다
     * (2026-10-10 리뷰 — LOW 에서 일으키면 남의 의자에 앉은 사람을 아무나 주먹으로 끌어내렸다).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamaged(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (config.sitStandOnDamage && seats.isSeated(player)) seats.stand(player)
    }

    /** `/처치 -forced` — 다른 플러그인(잠수 보호·보호구역·던전)이 막은 피해도 들어가게 한다. 고른 피해 원인이 사망 메시지에 남는다. */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onForcedKill(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (event.isCancelled && feature.isForcedKill(player.uniqueId)) event.isCancelled = false
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onHunger(event: FoodLevelChangeEvent) {
        val player = event.entity as? Player ?: return
        if (config.godKeepsHunger && event.foodLevel < player.foodLevel && feature.isGod(player)) event.isCancelled = true
    }

    // --- 비행 유지 -------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) {
        val player = event.player
        if (config.flyRestoreAfterDeath && player.allowFlight && player.gameMode in SURVIVAL_LIKE) flewBeforeDeath += player.uniqueId
        if (seats.isSeated(player)) seats.stand(player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        val flew = flewBeforeDeath.remove(player.uniqueId)
        if (!FlightRule.restoreOnRespawn(flew, feature.wantsFlight(player.uniqueId))) return
        player.scheduler.runDelayed(ess.plugin, { _ -> if (player.isOnline && player.gameMode in SURVIVAL_LIKE) player.allowFlight = true }, null, 2L)
    }

    /** 서바이벌로 바꾸면 바닐라가 비행을 끈다 — `/비행`·`/시간제비행` 으로 켠 사람은 다시 켠다(설정). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onGameMode(event: PlayerGameModeChangeEvent) {
        val player = event.player
        if (!FlightRule.restoreOnGameMode(config.flyKeepOnGameModeChange, event.newGameMode in SURVIVAL_LIKE, feature.wantsFlight(player.uniqueId))) return
        player.scheduler.run(ess.plugin, { _ -> if (player.isOnline && player.gameMode in SURVIVAL_LIKE) player.allowFlight = true }, null)
    }

    // --- 모자 칸 -------------------------------------------------------------------------------

    /**
     * 자기 가방 화면의 머리 칸에 손에 든(커서) 아이템을 놓으면 모자로 쓴다(CMI cmi.inventoryhat). 바닐라가 받는 것(투구·머리·호박)은 그대로 둔다.
     * 창작 모드 화면(InventoryCreativeEvent)은 손대지 않는다. 다른 플러그인이 이미 막은 클릭(가방 잠금·감옥·메뉴)도 손대지 않는다.
     *
     * **바꾸기는 이 사건 안에서 바로 한다**(2026-10-10 리뷰): 취소된 클릭은 서버가 적용하지 않고 칸·커서를 **지금 서버 상태**로 다시 보낸다.
     * 한 틱 뒤로 미루면 그 사이(같은 틱) 다른 클릭이 커서·머리 칸을 바꾸고, 클릭 때 찍어 둔 사본을 덮어써 아이템이 복사·소실된다.
     * 다음 틱에는 화면 다시 그리기(`updateInventory`)만.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onHeadSlot(event: InventoryClickEvent) {
        if (event is InventoryCreativeEvent || !config.hatInventory) return
        val player = event.whoClicked as? Player ?: return
        if (event.view.topInventory.type != InventoryType.CRAFTING || event.rawSlot != HEAD_RAW_SLOT || event.slotType != InventoryType.SlotType.ARMOR) return
        if (event.click != ClickType.LEFT && event.click != ClickType.RIGHT) return // 숫자 키·쉬프트·버리기는 커서를 놓는 클릭이 아니다
        val cursor = event.cursor
        if (cursor.type.isAir || FunCommands.isHeadwear(cursor)) return
        if (!player.hasPermission(PlayerFeature.HAT_INVENTORY)) return
        if (feature.hatVerdict(cursor) != HatRule.Verdict.OK) return
        val old = player.inventory.helmet?.takeUnless { it.type.isAir }
        if (cursor.amount > 1 && old != null) return // 커서에 둘을 들 수 없다
        event.isCancelled = true
        if (old != null && !HatRule.canReplace(FunCommands.isBound(old), player.gameMode == GameMode.CREATIVE)) {
            ess.messages.send(player, "player-hat-incorrect")
            return
        }
        val single = cursor.clone().also { it.amount = 1 }
        val rest = if (cursor.amount > 1) cursor.clone().also { it.amount = cursor.amount - 1 } else null
        player.inventory.setHelmet(single)
        player.setItemOnCursor(rest ?: old)
        player.scheduler.run(ess.plugin, { _ -> if (player.isOnline) player.updateInventory() }, null)
    }

    // --- 의자 · 좌석 ----------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    fun onChair(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND) return
        val player = event.player
        val block = event.clickedBlock ?: return
        // 보호 구역 플러그인이 블록 사용을 막았으면 앉지도 않는다.
        if (event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) return
        if (player.isSneaking || !player.inventory.itemInMainHand.type.isAir || player.isInsideVehicle) return
        if (!player.hasPermission(PlayerFeature.SIT_CHAIR)) return
        val spot = seats.chairSpot(block) ?: return
        if (spot.distance(player.location) > config.sitChairRange) return
        if (!block.getRelative(org.bukkit.block.BlockFace.UP).isPassable) return
        if (!seats.doubleClicked(player, block)) return
        if (seats.occupant(block) != null) {
            ess.messages.send(player, "player-sit-occupied")
            return
        }
        event.isCancelled = true
        // 일어날 때 이 자리로 돌려보낸다 — 창살 틈·구멍 너머의 의자에 앉았다 일어나 건너편으로 빠져나가지 못하게.
        if (seats.sit(player, spot, block, origin = player.location)) ess.messages.send(player, "player-sit-on")
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDismount(event: EntityDismountEvent) {
        val player = event.entity as? Player ?: return
        if (!seats.isSeat(event.dismounted) || seats.isStanding(player)) return
        player.scheduler.run(ess.plugin, { _ -> if (seats.stand(player)) ess.messages.send(player, "player-sit-off") }, null)
    }

    /**
     * 의자에서 내리면(바닐라 내리기 = 좌석 자리에 세우는 DISMOUNT 이동) 앉기 전 자리로 바꾼다. 있던 이동 사건의 도착지만 고치므로
     * 새 이동이 아니다(`ess.teleporter` 를 거치지 않는다 — 거치면 이동 기능의 "이동 뒤 무적" 이 앉았다 일어나기로 걸린다).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDismountMove(event: PlayerTeleportEvent) {
        if (event.cause != PlayerTeleportEvent.TeleportCause.DISMOUNT && event.cause != PlayerTeleportEvent.TeleportCause.UNKNOWN) return
        val origin = seats.dismountOrigin(event.player) ?: return
        val to = event.to
        if (origin.world != to.world) return
        event.setTo(origin.clone().also { it.yaw = to.yaw; it.pitch = to.pitch })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        val player = event.player
        if (!seats.isSeated(player) || seats.isStanding(player)) return
        if (event.cause == PlayerTeleportEvent.TeleportCause.DISMOUNT || event.cause == PlayerTeleportEvent.TeleportCause.UNKNOWN) return
        seats.stand(player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        seats.freeChair(event.block)
    }

    // --- 접속 · 나감 ----------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        if (event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED) return
        states.preload(event.uniqueId)
        // 접속 처리(UserService)가 마지막 접속 시각을 덮어쓰기 전에 — 효과 시간 줄이기가 크래시를 알아보게.
        ess.users.get(event.uniqueId)?.let { previousVisit[event.uniqueId] = it.lastJoin to it.lastQuit }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        val record = states.joined(player.uniqueId) { id, loaded -> org.bukkit.Bukkit.getPlayer(id)?.let { feature.applyRecord(it, loaded) } }
        // 한 틱 뒤 — 게임모드·월드 기능의 접속 처리가 끝난 뒤 비행을 다시 켠다.
        player.scheduler.runDelayed(ess.plugin, { _ -> if (player.isOnline) feature.applyRecord(player, record) }, null, 2L)
        // 공용 DB — 서버를 옮긴 직후 옛 서버의 늦은 나감 쓰기를 3초 뒤 다시 읽어 시간제 남은 초를 작은 쪽으로(2026-10-10 리뷰).
        if (states.isAwaitingRecheck(player.uniqueId)) player.scheduler.runDelayed(ess.plugin, { _ -> states.recheckTimed(player.uniqueId) }, null, 60L)
        if (config.effectDeductWhileOffline) deductOffline(player) else previousVisit.remove(player.uniqueId)
        seats.joined(player)
    }

    /**
     * CMI PotionEffects.DeductWhileOffline — 나가 있던 시간만큼 효과를 줄인다(무한은 그대로).
     * 지난 접속이 크래시로 끝났으면(나간 시각이 지난 접속 시각보다 앞) 나간 시각을 모르므로 줄이지 않는다.
     */
    private fun deductOffline(player: Player) {
        val (lastJoin, quit) = previousVisit.remove(player.uniqueId) ?: return
        if (!OfflineMath.knowsQuit(lastJoin, quit)) return
        quit ?: return
        val away = ((System.currentTimeMillis() - quit) / 50L).coerceAtLeast(0)
        if (away <= 0) return
        for (effect in player.activePotionEffects) {
            if (effect.isInfinite) continue
            val left = effect.duration - away
            player.removePotionEffect(effect.type)
            if (left > 0) player.addPotionEffect(PotionEffect(effect.type, left.toInt(), effect.amplifier, effect.isAmbient, effect.hasParticles(), effect.hasIcon()))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        seats.quit(player)
        flewBeforeDeath.remove(player.uniqueId)
        feature.forgetLanding(player.uniqueId)
        seats.forgetClicks(player)
        previousVisit.remove(player.uniqueId)
        states.quit(player.uniqueId)
    }

    companion object {
        /** 자기 가방 화면(제작 2×2)의 머리 칸 원시 번호. */
        const val HEAD_RAW_SLOT = 5

        val SURVIVAL_LIKE = setOf(GameMode.SURVIVAL, GameMode.ADVENTURE)
    }
}
