package com.inmc.essentials.decor

import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.Entity
import org.bukkit.entity.Hanging
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.hanging.HangingBreakEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.util.Vector

/**
 * 명령으로 거치대·액자·그림을 만질 때의 보호 구역 확인 — 그 동작의 사건을 **가짜로** 쏴 보고, 보호 플러그인(Lands·WorldGuard)이 막으면 못 만진다.
 *
 * 가짜 사건이 다른 플러그인에게 진짜처럼 보이지 않게(2026-10-10 리뷰 — 인첸트 우클릭 발동·업적·기록):
 *  - **보조 손**(`OFF_HAND`)으로 쏜다. 클릭 기능을 거는 쪽(inmc-enchants `RIGHT_CLICK_ENTITY` 등)은 주 손만 보지만, 보호 플러그인은 두 손을 다 막는다
 *    (보조 손으로 액자에 아이템을 넣는 길이 있어서 — WorldGuard `UseEntityEvent` 는 손을 가리지 않는다).
 *  - **HIGHEST** 에서 그때까지의 판정(취소됐나)을 **처음 한 번만** 적어 두고 **우리가 취소한다** — MONITOR·`ignoreCancelled` 쪽(CoreProtect 기록·업적·
 *    잠수 풀기)이 받지 않는다. 보호 플러그인도 HIGHEST 에서 판정하고 우리보다 늦게 불리면 그 판정은 못 본다(그러면 허락으로 본다) — 보통은 NORMAL 이하.
 *
 * **처리기 하나에 사건 종류 하나**: 이 서버 API 에서 `PlayerInteractAtEntityEvent` 는 자기 HandlerList 가 없어 `PlayerInteractEntityEvent` 의 것을 쓴다
 * (paper-api 26.1.2 javap). 그래서 [onEntity] 하나가 두 사건을 다 받는다 — At 용 처리기를 따로 두면 같은 사건을 두 번 받아 두 번째가 우리의 취소를
 * "막힘" 으로 덮어썼다(2026-10-10 리뷰: 명령으로 편집기를 늘 못 열던 것). `HangingBreakByEntityEvent` 도 `HangingBreakEvent` 의 것을 쓴다.
 *
 * 표지판은 이것을 쓰지 않는다 — 바꾸는 길이 늘 `SignChangeEvent` 를 지나고 Lands·WorldGuard 가 그 사건을 막는다(서버 jar 로 확인).
 */
class Probe : Listener {

    private var active: Event? = null
    private var verdict: Boolean? = null

    /** 우리가 쏜 가짜 사건을 돌리는 중 — 우리 리스너가 그것으로 편집기를 열거나 그림을 바꾸지 않게. */
    val probing: Boolean get() = active != null

    /** 거치대 — `PlayerInteractAtEntityEvent`(바닐라에서 거치대 장비를 바꾸는 클릭). */
    fun mayTouchStand(player: Player, entity: Entity): Boolean =
        run(PlayerInteractAtEntityEvent(player, entity, Vector(0.0, 1.0, 0.0), EquipmentSlot.OFF_HAND))

    /** 액자 등 — `PlayerInteractEntityEvent`. */
    fun mayTouch(player: Player, entity: Entity): Boolean = run(PlayerInteractEntityEvent(player, entity, EquipmentSlot.OFF_HAND))

    /** 그림 바꾸기 — 그림을 바꾸는 것은 떼었다 다시 거는 것과 같다: 그 사람이 이 그림을 뗄 수 있나(`HangingBreakByEntityEvent`). */
    fun mayBreak(player: Player, hanging: Hanging): Boolean {
        val source = runCatching { DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(player).withDirectEntity(player).build() }.getOrNull()
            ?: return false // 피해 원인을 못 지으면 확인을 못 한다 — 막는 쪽으로.
        return run(HangingBreakByEntityEvent(hanging, player, source, HangingBreakEvent.RemoveCause.ENTITY))
    }

    /** 거치대 옮기기 — 그 블록에 거치대를 놓을 수 있나(`EntityPlaceEvent` — 바닐라가 거치대를 놓을 때 쏘는 사건). */
    fun mayPlace(player: Player, entity: Entity, block: Block): Boolean =
        run(EntityPlaceEvent(entity, player, block, BlockFace.UP, EquipmentSlot.OFF_HAND))

    private fun run(event: Event): Boolean {
        if (active != null) return false // 다시 들어옴 — 다른 플러그인이 우리 확인 중에 또 부른 것. 막는 쪽으로.
        active = event
        verdict = null
        return try {
            Bukkit.getPluginManager().callEvent(event)
            verdict ?: !(event as Cancellable).isCancelled
        } finally {
            active = null
            verdict = null
        }
    }

    /** `PlayerInteractEntityEvent` 와 `PlayerInteractAtEntityEvent` 를 둘 다 받는다(위 KDoc). */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntity(event: PlayerInteractEntityEvent) = capture(event)

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onHanging(event: HangingBreakEvent) = capture(event)

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlace(event: EntityPlaceEvent) = capture(event)

    private fun capture(event: Cancellable) {
        if (event !== active) return
        if (verdict == null) verdict = !event.isCancelled // 처음 한 번만 — 같은 사건을 또 받아도 우리의 취소를 판정으로 읽지 않게
        event.isCancelled = true
    }
}
