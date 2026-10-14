package com.inmc.essentials.decor

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import kr.inmc.core.util.VanillaNames
import org.bukkit.Art
import org.bukkit.Bukkit
import org.bukkit.entity.Painting
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.UUID

/**
 * 그림 바꾸기(CMI paintingEditor) — 웅크리고 그림을 우클릭하면 **같은 크기의** 다음 그림으로 바꾸고, 그 뒤 [DecorSettings.Painting.scrollSeconds] 초 동안
 * 웅크린 채 마우스 휠(단축바 넘기기)로 앞뒤로 넘긴다(그동안 단축바 칸은 그대로). 행동 표시줄에 이름·id·몇 번째인지.
 *
 * 보호 구역은 둘로 지킨다 — HIGH·ignoreCancelled 라 Lands·WorldGuard 가 막은 우클릭은 오지 않고, 통과해도 그 사람이 이 그림을 **뗄 수 있는지**
 * ([Probe.mayBreak] — 바꾸기는 떼었다 다시 거는 것)를 본다(그림 우클릭을 막지 않는 보호 플러그인도 떼기는 막는다 — 2026-10-10 리뷰).
 * 데이터팩 그림도 레지스트리에 있으므로 함께 돈다.
 *
 * 클라이언트는 그림을 우클릭할 때 INTERACT_AT 과 INTERACT 를 둘 다 보내고, 이 API 에서 `PlayerInteractAtEntityEvent` 는 부모의 HandlerList 를 쓰므로
 * 이 처리기가 두 번 불린다 — At 사건은 건너뛴다(한 번 클릭에 두 칸 넘어가던 것 — 2026-10-10 리뷰).
 */
class Paintings(private val ess: Ess, private val settings: () -> DecorSettings, private val probe: Probe) : Listener {

    private class Scroll(val painting: UUID, var until: Long)

    private val scrolling = HashMap<UUID, Scroll>()

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onClick(event: PlayerInteractEntityEvent) {
        if (event is PlayerInteractAtEntityEvent || probe.probing) return
        val painting = event.rightClicked as? Painting ?: return
        val player = event.player
        if (event.hand != EquipmentSlot.HAND || !player.isSneaking || !settings().painting.enabled || !player.hasPermission(DecorFeature.PAINTING)) return
        event.isCancelled = true
        if (!probe.mayBreak(player, painting)) return ess.messages.send(player, "decor-painting-protected")
        cycle(player, painting, 1)
        val seconds = settings().painting.scrollSeconds
        if (seconds > 0) scrolling[player.uniqueId] = Scroll(painting.uniqueId, System.currentTimeMillis() + seconds * 1000L)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onScroll(event: PlayerItemHeldEvent) {
        val player = event.player
        val scroll = scrolling[player.uniqueId] ?: return
        if (System.currentTimeMillis() > scroll.until || !player.isSneaking) {
            scrolling.remove(player.uniqueId)
            return
        }
        val painting = Bukkit.getEntity(scroll.painting) as? Painting
        if (painting == null || !painting.isValid || painting.world != player.world || painting.location.distanceSquared(player.location) > 64.0) {
            scrolling.remove(player.uniqueId)
            return
        }
        val diff = Math.floorMod(event.newSlot - event.previousSlot, 9)
        event.isCancelled = true
        scroll.until = System.currentTimeMillis() + settings().painting.scrollSeconds * 1000L
        cycle(player, painting, if (diff in 1..4) 1 else -1)
    }

    /** 같은 크기의 그림들 중 [step] 만큼 옆으로. */
    fun cycle(player: Player?, painting: Painting, step: Int): Art? {
        val current = painting.art
        val same = sameSize(current)
        if (same.size <= 1) {
            player?.let { ess.messages.send(it, "decor-painting-only-one") }
            return null
        }
        val index = same.indexOf(current).coerceAtLeast(0)
        val next = same[Math.floorMod(index + step, same.size)]
        if (!painting.setArt(next)) painting.setArt(next, true)
        player?.sendActionBar(ess.messages.component("decor-painting-changed", Ph.of().raw("{이름}", nameOf(next)).value(keyOf(next))
            .count(same.indexOf(next) + 1).raw("{전체}", same.size.toString()), player))
        return next
    }

    fun sameSize(art: Art): List<Art> = allArts().filter { it.blockWidth == art.blockWidth && it.blockHeight == art.blockHeight }

    fun allArts(): List<Art> = runCatching { RegistryAccess.registryAccess().getRegistry(RegistryKey.PAINTING_VARIANT).toList() }.getOrElse { emptyList() }
        .sortedBy { keyOf(it) }

    @Suppress("DEPRECATION")
    fun keyOf(art: Art): String = art.key.asString()

    @Suppress("DEPRECATION")
    fun nameOf(art: Art): String {
        val key = art.key
        return runCatching { VanillaNames.ofKey("painting.${key.namespace}.${key.key}.title") }.getOrNull() ?: key.key
    }

    fun quit(id: UUID) {
        scrolling.remove(id)
    }

    fun clear() {
        scrolling.clear()
    }
}
