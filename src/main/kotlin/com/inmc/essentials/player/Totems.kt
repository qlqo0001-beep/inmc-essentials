package com.inmc.essentials.player

import com.inmc.essentials.Ess
import com.inmc.essentials.service.TeleportKind
import com.inmc.essentials.util.Ph
import kr.inmc.core.integration.PlayerSettings
import kr.inmc.core.util.Durations
import net.kyori.adventure.bossbar.BossBar
import org.bukkit.EntityEffect
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityResurrectEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.UUID

/**
 * 불사의 토템 확장(CMI totemBehavior · config Totem).
 *
 * - 가방 속 토템: 손에 없을 때 부활 사건이 "취소됨" 으로 온다 — 가방(주 칸)에서 하나 빼고 취소를 푼다(설정 · 개인 설정 `essentials.totem-inventory`
 *   · 설정에 따라 권한). Paper 는 취소가 풀리면 손에 토템이 없어도 바닐라 부활(체력 1 · 효과)을 그대로 한다.
 * - 쿨타임: 그 사이엔 손의 토템도 막는다(취소 — 토템은 남고 죽는다). 웜업: 한 번 터진 뒤 N초 동안은 토템 없이도 부활(웜업을 쿨타임보다 먼저 본다).
 * - 효과 시간: 바닐라 효과를 한 틱 뒤 설정 값으로 바꿔 건다(0 = 그 효과 없음).
 * - 허공: 토템을 가진 채 허공에 떨어지면 리스폰 자리로 옮기고, **옮기기에 성공하면** 토템 하나를 쓴다(설정). 실패하면 5초 동안 다시 하지 않는다.
 * - 월드: `vanilla-worlds` 는 손대지 않고, `disabled-worlds` 에서는 토템이 아예 안 된다. 허공 구하기는 `protect-from-void-skip-worlds` 에서도 안 한다
 *   (기본 `inmc_dungeon` — 던전은 허공 추락을 "쓰러짐" 으로 다루고, 다른 월드로 옮기면 판에서 빠진다).
 * - 보스바: 웜업·쿨타임이 남은 동안(개인 설정 `essentials.totem-bossbar`).
 *
 * **부활 사건 계약**(2026-10-10 — inmc-enchants `CombatListener.onResurrect` REVIVE 도 HIGH 에서 취소를 푼다): 우리는 **HIGHEST** 에서 본다.
 * 손(또는 보조 손)에 죽음 보호 아이템이 있으면 `event.hand` 가 그 손, 없으면 null 이다.
 * - 손 토템 없음 + 취소 안 됨 = 다른 플러그인이 살렸다 → 손대지 않는다(쿨타임·웜업도 안 건다 · 금지 월드에서도 막지 않는다).
 * - 손 토템 없음 + 취소됨 = 아무도 안 살렸다 → 웜업 또는 가방 속 토템으로 살린다(효과는 우리가 건다 — 바닐라는 아이템이 있어야 건다).
 * - 손 토템 있음 + 취소됨 = 다른 플러그인이 막았다 → 손대지 않는다.
 * - 손 토템 있음 → 금지 월드·쿨타임이면 취소(웜업 중이면 쿨타임이어도 산다), 아니면 쿨타임·웜업을 건다.
 * - `/처치 -forced` 중이면 무엇이든 막는다.
 */
class Totems(private val ess: Ess, private val feature: PlayerFeature) : Listener {

    private val config get() = feature.config

    private val cooldownUntil = HashMap<UUID, Long>()
    private val warmupUntil = HashMap<UUID, Long>()
    private val bars = HashMap<UUID, BossBar>()

    /** 허공에서 구해 옮기는 중 → 이 시각까지(이동은 비동기라 그 사이 허공 피해가 또 온다). */
    private val rescuing = java.util.concurrent.ConcurrentHashMap<UUID, Long>()

    /** 허공 구하기의 이동이 실패한 사람 → 이 시각까지 다시 하지 않는다. 메인. */
    private val voidFailedUntil = HashMap<UUID, Long>()

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onResurrect(event: EntityResurrectEvent) {
        val player = event.entity as? Player ?: return
        val handTotem = event.hand != null
        val id = player.uniqueId
        // `/처치 -forced` — 토템도 무시한다(손 토템이 터져 살아났다가 체력 0 으로 또 죽으면 토템만 잃는다).
        if (feature.isForcedKill(id)) {
            event.isCancelled = true
            return
        }
        val c = config
        val world = player.world.name
        val now = System.currentTimeMillis()
        val decision = decide(handTotem, event.isCancelled, c.totemDisabled(world), c.totemVanilla(world), now, cooldownUntil[id], warmupUntil[id])
        when (decision) {
            Decision.IGNORE -> return
            Decision.CANCEL -> event.isCancelled = true
            Decision.CANCEL_COOLDOWN -> {
                event.isCancelled = true
                val cool = cooldownUntil[id] ?: now
                // 손에도 가방에도 토템이 없으면 알릴 것이 없다(어차피 죽는다).
                if (handTotem || hasTotem(player)) ess.messages.send(player, "player-totem-cooldown-denied", Ph.of().time(Durations.formatShort((cool - now + 999) / 1000)))
            }
            Decision.ALLOW_WARMUP -> {
                event.isCancelled = false
                popped(player, now, vanillaApplied = handTotem)
            }
            Decision.TRY_INVENTORY -> if (canUseInventory(player) && takeFromInventory(player)) {
                event.isCancelled = false
                popped(player, now, vanillaApplied = false)
            }
            Decision.HAND -> popped(player, now, vanillaApplied = true)
        }
    }

    /** 부활이 일어났다 — 쿨타임·웜업을 걸고 효과 시간을 바꾼다. */
    private fun popped(player: Player, now: Long, vanillaApplied: Boolean) {
        val c = config
        if (c.totemCooldown > 0) cooldownUntil[player.uniqueId] = now + c.totemCooldown * 1000
        if (c.totemWarmup > 0 && (warmupUntil[player.uniqueId] ?: 0) <= now) warmupUntil[player.uniqueId] = now + c.totemWarmup * 1000
        player.scheduler.run(ess.plugin, { _ -> applyEffects(player, vanillaApplied) }, null)
    }

    /**
     * [vanillaApplied] = 바닐라 부활이 이미 효과를 걸었다(손의 죽음 보호 아이템이 건다 — 값이 바닐라와 같으면 둔다). 손 토템 없이 살린
     * 부활(가방 속 토템 · 웜업 · 허공)은 바닐라가 효과를 걸지 않으니 늘 건다.
     */
    private fun applyEffects(player: Player, vanillaApplied: Boolean) {
        val c = config
        if (!effectsNeeded(vanillaApplied, c)) return
        fun set(type: PotionEffectType, seconds: Int, amplifier: Int) {
            player.removePotionEffect(type)
            if (seconds > 0) player.addPotionEffect(PotionEffect(type, seconds * 20, amplifier))
        }
        set(PotionEffectType.REGENERATION, c.totemRegeneration, 1)
        set(PotionEffectType.FIRE_RESISTANCE, c.totemFireResistance, 0)
        set(PotionEffectType.ABSORPTION, c.totemAbsorption, 1)
    }

    private fun canUseInventory(player: Player): Boolean {
        val c = config
        if (!c.totemFromInventory) return false
        if (c.totemRequirePermission && !player.hasPermission(PlayerFeature.TOTEM_INVENTORY)) return false
        return PlayerSettings.enabled(player, PlayerFeature.SETTING_TOTEM_INVENTORY, true)
    }

    private fun hasTotem(player: Player): Boolean {
        return holding(player) || (canUseInventory(player) && player.inventory.storageContents.any { it?.type == Material.TOTEM_OF_UNDYING })
    }

    private fun holding(player: Player): Boolean =
        player.inventory.itemInMainHand.type == Material.TOTEM_OF_UNDYING || player.inventory.itemInOffHand.type == Material.TOTEM_OF_UNDYING

    private fun takeFromInventory(player: Player): Boolean {
        val contents = player.inventory.storageContents
        for ((slot, stack) in contents.withIndex()) {
            if (stack == null || stack.type != Material.TOTEM_OF_UNDYING) continue
            if (stack.amount <= 1) player.inventory.setItem(slot, null) else stack.amount = stack.amount - 1
            return true
        }
        return false
    }

    /** 손 → 가방 순으로 하나 쓴다(허공 보호). */
    private fun consumeAny(player: Player): Boolean {
        val inv = player.inventory
        for (hand in listOf(inv.itemInMainHand, inv.itemInOffHand)) {
            if (hand.type == Material.TOTEM_OF_UNDYING) {
                hand.amount = hand.amount - 1
                return true
            }
        }
        return canUseInventory(player) && takeFromInventory(player)
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onVoid(event: EntityDamageEvent) {
        if (event.cause != EntityDamageEvent.DamageCause.VOID) return
        val player = event.entity as? Player ?: return
        val c = config
        if (!c.totemProtectFromVoid || c.totemDisabled(player.world.name) || c.totemVanilla(player.world.name) || c.totemVoidSkip(player.world.name)) return
        val id = player.uniqueId
        val now = System.currentTimeMillis()
        // 옮기는 중(비동기 이동이 끝나기 전)의 다음 허공 피해 — 막기만 한다.
        if ((rescuing[id] ?: 0) > now) {
            event.isCancelled = true
            return
        }
        // 방금 옮기기에 실패했다(감옥·수갑·이동 금지·월드 없음) — 같은 추락에서 다시 하지 않는다(허공 피해는 토템이 막지 못하니 그대로 죽는다, 토템은 남는다).
        if ((voidFailedUntil[id] ?: 0) > now) return
        if ((cooldownUntil[id] ?: 0) > now || !hasTotem(player)) return
        val to = player.respawnLocation ?: player.server.worlds.firstOrNull()?.spawnLocation ?: return
        event.isCancelled = true
        player.fallDistance = 0f
        rescuing[id] = now + 5_000L
        // 토템은 이동이 **성공한 뒤에** 쓴다(2026-10-10 리뷰 — 먼저 쓰면 막힌 이동마다 토템이 하나씩 사라졌다).
        ess.teleporter.teleport(player, to, TeleportKind.OTHER).whenComplete { ok, error ->
            player.scheduler.run(ess.plugin, { _ -> voidDone(player, ok == true && error == null) }, { rescuing.remove(id) })
        }
    }

    private fun voidDone(player: Player, moved: Boolean) {
        val id = player.uniqueId
        rescuing.remove(id)
        val now = System.currentTimeMillis()
        if (!moved) {
            voidFailedUntil[id] = now + 5_000L
            return
        }
        consumeAny(player) // 그 사이 토템을 옮겼으면 못 쓴다 — 이미 옮겼으니 구한 것은 그대로
        player.playEffect(EntityEffect.PROTECTED_FROM_DEATH)
        popped(player, now, vanillaApplied = false)
        ess.messages.send(player, "player-totem-void")
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        bars.remove(event.player.uniqueId)?.let { event.player.hideBossBar(it) }
        rescuing.remove(event.player.uniqueId)
        voidFailedUntil.remove(event.player.uniqueId)
    }

    /** 1초 — 웜업·쿨타임 보스바. */
    fun tick(now: Long) {
        cooldownUntil.entries.removeIf { it.value <= now }
        warmupUntil.entries.removeIf { it.value <= now }
        voidFailedUntil.entries.removeIf { it.value <= now }
        val c = config
        val ids = HashSet<UUID>(cooldownUntil.keys).apply { addAll(warmupUntil.keys); addAll(bars.keys) }
        for (id in ids) {
            val player = ess.plugin.server.getPlayer(id)
            val warm = warmupUntil[id]?.let { it - now }
            val cool = cooldownUntil[id]?.let { it - now }
            val show = player != null && c.totemBossBar && PlayerSettings.enabled(player, PlayerFeature.SETTING_TOTEM_BOSSBAR, true) && (warm != null || cool != null)
            if (!show) {
                bars.remove(id)?.let { bar -> player?.hideBossBar(bar) }
                continue
            }
            val (key, left, total) = if (warm != null) Triple("player-totem-bar-warmup", warm, c.totemWarmup * 1000) else Triple("player-totem-bar-cooldown", cool!!, c.totemCooldown * 1000)
            val title = ess.messages.component(key, Ph.of().time(Durations.formatShort((left + 999) / 1000)))
            val progress = (left.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
            val color = if (warm != null) BossBar.Color.GREEN else BossBar.Color.YELLOW
            val bar = bars.getOrPut(id) { BossBar.bossBar(title, progress, color, BossBar.Overlay.PROGRESS).also { player!!.showBossBar(it) } }
            bar.name(title)
            bar.progress(progress)
            bar.color(color)
        }
    }

    fun clear() {
        for ((id, bar) in bars) ess.plugin.server.getPlayer(id)?.hideBossBar(bar)
        bars.clear()
    }

    /** 부활 사건에서 할 일. */
    enum class Decision {
        /** 손대지 않는다(다른 플러그인이 살렸거나 막았다 · 바닐라 월드 · 손 토템 없고 웜업도 아님 — 가방은 [TRY_INVENTORY]). */
        IGNORE,

        /** 토템 금지 월드 — 막는다. */
        CANCEL,

        /** 쿨타임 — 막는다(손·가방 토템은 남는다). 토템이 있었으면 알린다. */
        CANCEL_COOLDOWN,

        /** 웜업 중 — 토템 없이도 살린다(손 토템이면 바닐라 그대로). 쿨타임보다 먼저 본다. */
        ALLOW_WARMUP,

        /** 손 토템 없음 — 가방 속 토템이 있으면 살린다. */
        TRY_INVENTORY,

        /** 손 토템으로 산다 — 쿨타임·웜업을 건다. */
        HAND,
    }

    companion object {
        /**
         * 부활 사건 판단(순수 — 검증기·시험이 진짜 사건을 쏘지 않고 본다). [cancelled] = 사건이 지금 취소돼 있나(손 토템이 없으면 처음부터 취소로 온다).
         * 손 토템이 있는데 취소돼 있으면 다른 플러그인이 막은 것이라 손대지 않는다(쿨타임·웜업도 걸지 않는다). 웜업은 쿨타임보다 먼저 본다 — 둘 다 켜면
         * 웜업 동안의 부활이 쿨타임에 막히던 것(2026-10-10 리뷰).
         */
        fun decide(handTotem: Boolean, cancelled: Boolean, disabledWorld: Boolean, vanillaWorld: Boolean, now: Long, cooldownUntil: Long?, warmupUntil: Long?): Decision {
            if (!handTotem && !cancelled) return Decision.IGNORE // 다른 플러그인(인첸트 REVIVE 등)이 살렸다
            if (handTotem && cancelled) return Decision.IGNORE // 다른 플러그인이 손 토템 부활을 막았다
            if (disabledWorld) return if (cancelled) Decision.IGNORE else Decision.CANCEL
            if (vanillaWorld) return Decision.IGNORE
            if (warmupUntil != null && warmupUntil > now) return Decision.ALLOW_WARMUP
            if (cooldownUntil != null && cooldownUntil > now) return Decision.CANCEL_COOLDOWN
            return if (handTotem) Decision.HAND else Decision.TRY_INVENTORY
        }

        /** 효과를 우리가 다시 걸어야 하나 — 바닐라가 걸었고 값이 바닐라(45 · 40 · 5)와 같으면 둔다. */
        fun effectsNeeded(vanillaApplied: Boolean, c: PlayerConfig): Boolean =
            !vanillaApplied || c.totemRegeneration != 45 || c.totemFireResistance != 40 || c.totemAbsorption != 5
    }
}
