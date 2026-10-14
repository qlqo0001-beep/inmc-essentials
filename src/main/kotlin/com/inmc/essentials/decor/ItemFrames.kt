package com.inmc.essentials.decor

import org.bukkit.entity.GlowItemFrame
import org.bukkit.entity.ItemFrame
import java.util.logging.Logger

/**
 * 액자 ↔ 발광 액자(엔티티 종류가 다르다 — 바꾸려면 새로 띄운다). 들어 있던 아이템·회전·방향·투명·고정·무적·소리·떨굼 확률·PDC 를 옮긴다.
 *
 * 복사가 생기지 않게: 옛 액자의 아이템을 먼저 비우고 지운 뒤 새 액자에 넣는다. 새 액자를 못 띄우면 옛 종류로 다시 띄워 아이템을 돌려놓는다.
 */
object ItemFrames {

    /** 다른 플러그인이 표식을 붙인 액자(가구·지도 그림 등) — PDC 에 우리 것·바닐라 것이 아닌 열쇠가 있다. `/액자 … 주변` 은 `강제` 없이 건너뛴다. */
    fun foreign(frame: ItemFrame): Boolean = frame.persistentDataContainer.keys.any { it.namespace != "minecraft" && it.namespace != com.inmc.essentials.Ess.NAMESPACE }

    fun swapGlow(frame: ItemFrame, glow: Boolean, logger: Logger? = null): ItemFrame? {
        if ((frame is GlowItemFrame) == glow) return frame
        val loc = frame.location
        val facing = frame.facing
        val item = frame.item.clone()
        val rotation = frame.rotation
        val visible = frame.isVisible
        val fixed = frame.isFixed
        val invulnerable = frame.isInvulnerable
        val silent = frame.isSilent
        val dropChance = frame.itemDropChance
        val oldType: Class<out ItemFrame> = if (frame is GlowItemFrame) GlowItemFrame::class.java else ItemFrame::class.java
        val newType: Class<out ItemFrame> = if (glow) GlowItemFrame::class.java else ItemFrame::class.java
        val pdc = frame.persistentDataContainer
        val spawn: (Class<out ItemFrame>) -> ItemFrame? = { type ->
            runCatching {
                loc.world.spawn(loc, type) { f ->
                    f.setFacingDirection(facing, true)
                    f.setItem(item, false)
                    f.rotation = rotation
                    f.isVisible = visible
                    f.isFixed = fixed
                    f.isInvulnerable = invulnerable
                    f.isSilent = silent
                    f.itemDropChance = dropChance
                    runCatching { pdc.copyTo(f.persistentDataContainer, true) }
                }
            }.onFailure { logger?.warning("액자를 다시 띄우지 못했습니다: ${it.message}") }.getOrNull()
        }
        frame.setItem(null, false)
        frame.remove()
        return spawn(newType) ?: run {
            // 둘 다 못 띄우면 아이템만은 그 자리에 떨군다(사라지지 않게).
            if (spawn(oldType) == null && !item.type.isAir) loc.world.dropItemNaturally(loc, item)
            null
        }
    }
}
