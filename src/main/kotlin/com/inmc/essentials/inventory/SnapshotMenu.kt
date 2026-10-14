package com.inmc.essentials.inventory

import com.inmc.essentials.Ess
import com.inmc.essentials.gui.Menu
import kr.inmc.core.gui.Icon
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 읽기 전용 가방 보기 — 접속하지 않은 사람의 나갈 때 모습, 저장 인벤 미리보기. 클릭은 core Menu 가 전부 막는다(아이템이 손에 오지 않는다).
 *
 * 칸: 0~26 가방 윗칸(9~35) · 27~35 핫바(0~8) · 36 머리 · 37 몸 · 38 다리 · 39 발 · 40 왼손 · 아래 줄 단추. 엔더는 0~26.
 * 가방([items])·엔더([ender]) 중 null 인 쪽은 보이지 않고, 둘 다 있을 때만 넘기는 단추가 생긴다. [startWithEnder] 면 엔더부터.
 */
class SnapshotMenu(
    ess: Ess,
    viewer: Player,
    title: String,
    private val items: Array<ItemStack?>?,
    private val ender: Array<ItemStack?>?,
    private val info: List<String>,
    private val extra: List<Triple<Int, ItemStack, () -> Unit>> = emptyList(),
    startWithEnder: Boolean = false,
) : Menu(ess, viewer, 54, title) {

    private var showEnder = (startWithEnder && ender != null) || items == null

    override fun draw() {
        clear()
        if (showEnder && ender != null) {
            for (i in 0 until InvData.ENDER_SIZE) set(i, ender[i])
        } else if (items != null) {
            for (i in 9..35) set(i - 9, items.getOrNull(i))
            for (i in 0..8) set(27 + i, items.getOrNull(i))
            set(36, items.getOrNull(39) ?: Icon.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray>머리 (비어 있음)</gray>"))
            set(37, items.getOrNull(38) ?: Icon.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray>몸 (비어 있음)</gray>"))
            set(38, items.getOrNull(37) ?: Icon.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray>다리 (비어 있음)</gray>"))
            set(39, items.getOrNull(36) ?: Icon.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray>발 (비어 있음)</gray>"))
            set(40, items.getOrNull(40) ?: Icon.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray>왼손 (비어 있음)</gray>"))
        }
        for (slot in 45 until 54) set(slot, Icon.FILLER)
        if (ender != null && items != null) {
            set(SLOT_TOGGLE, Icon.of(if (showEnder) Material.CHEST else Material.ENDER_CHEST, if (showEnder) "<yellow>가방 보기</yellow>" else "<light_purple>엔더 보기</light_purple>")) {
                showEnder = !showEnder
                refresh()
            }
        }
        set(SLOT_INFO, Icon.of(Material.BOOK, "<gold>정보</gold>", info + listOf("", "<dark_gray>보기만 할 수 있습니다</dark_gray>")))
        for ((slot, icon, action) in extra) set(slot, icon) { action() }
        set(SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    companion object {
        const val SLOT_TOGGLE = 45
        const val SLOT_INFO = 49
        const val SLOT_CLOSE = 53
        const val SLOT_EXTRA_1 = 51
        const val SLOT_EXTRA_2 = 52
    }
}
