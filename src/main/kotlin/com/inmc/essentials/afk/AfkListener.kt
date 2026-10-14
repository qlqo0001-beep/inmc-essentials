package com.inmc.essentials.afk

import com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent
import com.inmc.essentials.Ess
import com.inmc.essentials.afk.AfkConfig.Activity
import com.inmc.essentials.util.Ph
import io.papermc.paper.event.entity.EntityKnockbackEvent
import io.papermc.paper.event.player.AsyncChatEvent
import org.bukkit.Input
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.Shulker
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerInputEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import kotlin.math.abs

/**
 * 잠수 — 활동 감지 · 잠수 중 보호.
 *
 * 우회 막기(CMI AntiAfkMachines · PreventJumping · SmartInteractCheck):
 * - **물길·피스톤 기계**: 켜 두면 몸이 옮겨진 것만으로는 활동이 아니다 — 그 순간 손님이 이동 키(앞·뒤·옆)를 누르고 있어야 한다
 *   (Paper `Player.currentInput`/`PlayerInputEvent` — 서버가 손님의 키 입력을 받는다).
 * - **제자리 뛰기**: 켜 두면 점프 키만 바뀌거나 높이만 바뀐 움직임은 활동이 아니다.
 * - **버튼 꾹 누르기**: 켜 두면 같은 블록·같은 손 아이템에 같은 동작이 1초 안에 되풀이되면 처음 한 번만 활동이다.
 */
class AfkListener(private val ess: Ess, private val feature: AfkFeature) : Listener {

    private val config get() = feature.config
    private val service get() = feature.service

    /** 마지막 상호작용(블록·동작·아이템) — 꾹 누르기 판정. 메인. */
    private val lastInteract = HashMap<UUID, Pair<String, Long>>()

    private fun active(player: Player, activity: Activity) {
        if (config.counts(activity)) service.activity(player)
    }

    // --- 움직임 --------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val from = event.from
        val to = event.to
        val player = event.player
        val c = config
        val moved = from.x != to.x || from.y != to.y || from.z != to.z
        val looked = abs(from.yaw - to.yaw) > 0.5f || abs(from.pitch - to.pitch) > 0.5f
        if (looked) active(player, Activity.LOOK_AROUND)
        if (!moved) return
        val horizontal = from.x != to.x || from.z != to.z
        if (c.preventJumping && !horizontal) return // 높이만 — 제자리 뛰기·물에 뜨기
        if (c.antiAfkMachines && !steering(player.currentInput)) return // 키를 안 누르는데 옮겨졌다 — 물길·기계
        active(player, Activity.MOVE)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onInput(event: PlayerInputEvent) {
        val input = event.input
        if (steering(input) || input.isSneak || input.isSprint || (!config.preventJumping && input.isJump)) active(event.player, Activity.MOVE)
    }

    private fun steering(input: Input): Boolean = input.isForward || input.isBackward || input.isLeft || input.isRight

    // --- 상호작용 · 가방 · 버리기 · 낚시 ----------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action == Action.PHYSICAL) return
        val player = event.player
        if (config.smartInteractCheck) {
            val block = event.clickedBlock?.let { "${it.x},${it.y},${it.z}" } ?: "-"
            val key = "${event.action}|$block|${event.item?.type}"
            val now = System.currentTimeMillis()
            val last = lastInteract.put(player.uniqueId, key to now)
            if (last != null && last.first == key && now - last.second < REPEAT_WINDOW_MS) return
        }
        active(player, interactActivity(event.item?.type == Material.FISHING_ROD))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        val player = event.player
        if (config.smartInteractCheck) {
            val key = "entity|${event.rightClicked.uniqueId}|${player.inventory.itemInMainHand.type}"
            val now = System.currentTimeMillis()
            val last = lastInteract.put(player.uniqueId, key to now)
            if (last != null && last.first == key && now - last.second < REPEAT_WINDOW_MS) return
        }
        active(player, Activity.INTERACT)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onInventory(event: InventoryClickEvent) {
        (event.whoClicked as? Player)?.let { active(it, Activity.INVENTORY_CLICK) }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDrop(event: PlayerDropItemEvent) {
        active(event.player, Activity.ITEM_DROP)
    }

    /** 낚기·던지기·거두기 — 전부 낚시 활동(우클릭은 [onInteract] 가 이미 낚시로 센다). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFish(event: PlayerFishEvent) {
        if (event.state == PlayerFishEvent.State.CAUGHT_FISH) active(event.player, Activity.FISHING)
    }

    // --- 명령 · 채팅 --------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        commandActivity(event.player, event.message, event.isCancelled)
    }

    /** 명령 한 줄의 활동 판단(검증기는 사건을 방송하지 않고 이것을 부른다 — 방송하면 명령 쿨타임·별칭·인첸트 COMMAND 가 관리자에게 걸린다). */
    internal fun commandActivity(player: Player, message: String, cancelled: Boolean) {
        val parts = message.removePrefix("/").trim().split(Regex("\\s+"))
        val label = parts.firstOrNull()?.substringAfterLast(':')?.lowercase() ?: return
        if (label in AfkFeature.OWN_LABELS) return // /잠수 는 그 명령이 직접 들어가고 나온다
        val c = config
        val whisper = label in c.whisperCommands
        if (whisper || label in c.replyCommands) active(player, Activity.PRIVATE_CHAT) else active(player, Activity.COMMAND)
        // chat 기능이 켜져 있으면 그쪽이 **실제로 전한 뒤에만** "자리를 비웠습니다" 를 보낸다 — 여기서도 보내면 두 번이고, 받기를 끈·무시한 사람의
        // 잠수 사유까지 새어 나간다(2026-10-10 리뷰). 이 알림은 다른 귓속말 플러그인을 쓸 때만.
        if (whisper && c.mayNotRespond && !cancelled && !ess.enabled(CHAT_FEATURE)) parts.getOrNull(1)?.let { mayNotRespond(player, it) }
    }

    /** CMI afk.MayNotRespond — 잠수인 사람에게 귓속말하면 보낸 사람에게 알린다(투명이라 안 보이는 사람은 못 찾으므로 새지 않는다). */
    private fun mayNotRespond(sender: Player, name: String) {
        val target = ess.args.online(sender, name) ?: return
        if (target == sender) return
        val entry = service.entry(target.uniqueId) ?: return
        val key = if (target.hasPermission(AfkFeature.STAFF)) "afk-may-not-respond-staff" else "afk-may-not-respond"
        val reason = entry.reason?.let { AfkService.escape(it) } ?: ess.messages.raw("afk-no-reason")
        // 한 틱 뒤 — 귓속말이 먼저 보이게.
        sender.scheduler.run(ess.plugin, { _ -> ess.messages.send(sender, key, Ph.of().target(target.name).reason(reason)) }, null)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChat(event: AsyncChatEvent) {
        active(event.player, Activity.PUBLIC_CHAT)
    }

    // --- 잠수 중 보호 ----------------------------------------------------------------------------

    /** 자동 잠수는 바로, 손 잠수(`/잠수`)는 `protect.damage-manual-delay` 초 뒤부터 · `protect.damage-disabled-worlds` 는 빼고. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (!service.protectsFromDamage(player)) return
        if (event.cause == EntityDamageEvent.DamageCause.VOID || event.cause == EntityDamageEvent.DamageCause.KILL) return
        event.isCancelled = true
    }

    /** 싸움 기록 — 때리거나 맞은 사람은 잠깐 스스로 `/잠수` 를 못 한다(`manual.combat-block-seconds`). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCombat(event: EntityDamageByEntityEvent) {
        val now = System.currentTimeMillis()
        (event.entity as? Player)?.let { service.combat(it.uniqueId, now) }
        val attacker = when (val d = event.damager) {
            is Player -> d
            is Projectile -> d.shooter as? Player
            else -> null
        }
        if (attacker != null && attacker != event.entity) service.combat(attacker.uniqueId, now)
    }

    /** 잠수인 사람이 때리는 것(몹 농장 막기 — CMI PreventMobDamage). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onAttack(event: EntityDamageByEntityEvent) {
        if (!config.preventMobDamage) return
        val attacker = when (val d = event.damager) {
            is Player -> d
            is Projectile -> d.shooter as? Player
            else -> null
        } ?: return
        if (attacker != event.entity && service.isAfk(attacker)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onHook(event: PlayerFishEvent) {
        if (event.state != PlayerFishEvent.State.CAUGHT_ENTITY || !config.preventHook) return
        val caught = event.caught as? Player ?: return
        if (service.isAfk(caught)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onKnockback(event: EntityKnockbackEvent) {
        val player = event.entity as? Player ?: return
        if (config.preventPushing && service.isAfk(player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        if (config.disableItemPickup && service.isAfk(player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onExp(event: PlayerPickupExperienceEvent) {
        if (config.disableExpPickup && service.isAfk(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onTarget(event: EntityTargetLivingEntityEvent) {
        if (event.entity !is Shulker || !config.disableShulkerTargeting) return
        val target = event.target as? Player ?: return
        if (service.isAfk(target)) event.isCancelled = true
    }

    /**
     * 잠수인 사람 근처의 몹 생성 막기(AFK 농장) — 반경 안에 잠수인 사람이 있고 **깨어 있는 사람은 없을 때만**(같이 있는 사람의 사냥은 그대로).
     * 잠수인 사람이 없으면 바로 돌아간다(자연 생성은 자주 온다).
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onSpawn(event: CreatureSpawnEvent) {
        val c = config
        if (!c.mobSpawningEnabled || service.afkCount == 0) return
        val watched = when (event.spawnReason) {
            CreatureSpawnEvent.SpawnReason.NATURAL -> c.mobSpawningNatural
            CreatureSpawnEvent.SpawnReason.SPAWNER, CreatureSpawnEvent.SpawnReason.TRIAL_SPAWNER -> c.mobSpawningSpawners
            CreatureSpawnEvent.SpawnReason.VILLAGE_DEFENSE -> c.mobSpawningVillageDefence
            else -> false
        }
        if (!watched) return
        if (shouldBlockSpawn(event.location.world?.getNearbyPlayers(event.location, c.mobSpawningRadius).orEmpty().map { service.isAfk(it) })) event.isCancelled = true
    }

    // --- 접속 · 나감 ----------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        service.joined(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        service.quit(event.player)
        lastInteract.remove(event.player.uniqueId)
    }

    companion object {
        /** 같은 상호작용이 이보다 빨리 되풀이되면 꾹 누르기로 본다. */
        const val REPEAT_WINDOW_MS = 1000L

        /** 귓속말을 직접 전하는 기능의 id(그 기능이 잠수 알림을 맡는다 — 패키지를 import 하지 않고 이름으로만 본다). */
        const val CHAT_FEATURE = "chat"

        /**
         * 우클릭 하나의 활동 종류 — 낚싯대를 던지고 거두는 것은 **낚시**(`leave-on.fishing`, 기본 끔)다. 상호작용으로 세면 낚싯대 자동 클릭기가
         * 3초마다 던져 잠수에 들지 않았다(2026-10-10 리뷰).
         */
        fun interactActivity(fishingRod: Boolean): Activity = if (fishingRod) Activity.FISHING else Activity.INTERACT

        /** 근처 사람들의 잠수 여부 → 생성을 막나(순수): 잠수인 사람이 있고 모두 잠수일 때. */
        fun shouldBlockSpawn(nearbyAfk: Collection<Boolean>): Boolean = nearbyAfk.isNotEmpty() && nearbyAfk.all { it }
    }
}
