package com.inmc.essentials.hologram

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.inventory.EquipmentSlot

/**
 * 누르기(Interaction 의 우클릭 = 상호작용 사건, 좌클릭 = 공격 전 사건 — 던전 선택지와 같은 길) · 청크 내려가기 · 엔티티 올라오기(남은 것 청소) · 나가기.
 * 우클릭은 손마다 한 번씩 올 수 있어 주 손만 받는다.
 */
class HologramListener(private val engine: HologramEngine) : Listener {

    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) {
            if (event.rightClicked.persistentDataContainer.has(engine.tag, org.bukkit.persistence.PersistentDataType.STRING)) event.isCancelled = true
            return
        }
        val click = if (event.player.isSneaking) HoloClick.SHIFT_RIGHT else HoloClick.RIGHT
        if (engine.click(event.player, event.rightClicked, click)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onAttack(event: PrePlayerAttackEntityEvent) {
        val click = if (event.player.isSneaking) HoloClick.SHIFT_LEFT else HoloClick.LEFT
        if (engine.click(event.player, event.attacked, click)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkUnload(event: ChunkUnloadEvent) {
        engine.chunkUnloaded(event.world.name, event.chunk.x, event.chunk.z)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntitiesLoad(event: EntitiesLoadEvent) {
        engine.sweep(event.entities)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        engine.quit(event.player)
    }
}
