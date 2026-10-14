package com.inmc.essentials.gui

import com.inmc.essentials.Ess
import kr.inmc.core.gui.DialogForm
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.util.Text
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * core [kr.inmc.core.gui.Menu] 에 이 플러그인의 로케이터와 보는 사람을 붙인 얇은 층(inmc-menu `gui/Menu.kt` 와 같은 모양).
 * 클릭은 core `MenuListener`(켤 때 하나 등록)가 넘긴다. 값 입력은 채팅이 아니라 **입력창(Dialog)** — [ask].
 * 칸 번호는 `const val SLOT_…` 로(지뢰 12), 범위 밖 칸은 조용히 무시된다(지뢰 2).
 */
abstract class Menu(
    protected val ess: Ess,
    protected val viewer: Player,
    size: Int,
    title: Component,
) : kr.inmc.core.gui.Menu(size, title) {

    constructor(ess: Ess, viewer: Player, size: Int, title: String) : this(ess, viewer, size, Text.renderFlat(title))

    override val owner: Any get() = ess

    /** 뒤로 버튼이 여는 화면. null 이면 뒤로 버튼이 없다. */
    protected open val back: (() -> Unit)? = null

    protected fun navigation(backSlot: Int = Paging.SLOT_BACK, closeSlot: Int = Paging.SLOT_CLOSE) {
        back?.let { go -> set(backSlot, Icon.back()) { go() } }
        set(closeSlot, Icon.close()) { viewer.closeInventory() }
    }

    fun show() = open(viewer)

    /** 입력창을 띄운다. 확인하면 [onSubmit] 뒤에 이 화면을 다시 연다(그 안에서 다른 화면을 열면 그쪽이 이긴다). Esc·취소도 다시 연다(지뢰 6). */
    protected fun ask(form: DialogForm, reopen: () -> Unit = { show() }, onSubmit: (DialogForm.Values) -> Unit) {
        form.show(ess.plugin, viewer, onCancel = { reopen() }) { _, values ->
            onSubmit(values)
            if (viewer.openInventory.topInventory.holder !is kr.inmc.core.gui.Menu) reopen()
        }
    }

    protected fun pager(page: Int, total: Int, perPage: Int = Paging.PER_PAGE, go: (Int) -> Unit) {
        val pages = Paging.pageCount(total, perPage)
        if (page > 0) set(Paging.SLOT_PREV, Icon.prevPage()) { go(page - 1) }
        if (page < pages - 1) set(Paging.SLOT_NEXT, Icon.nextPage()) { go(page + 1) }
    }

    protected fun toggleIcon(name: String, value: Boolean, vararg lore: String): ItemStack =
        Icon.of(Icon.toggleMaterial(value), "<yellow>$name: </yellow>" + Icon.toggle(value), lore.toList() + listOf("", "<gray>클릭해서 바꾸기</gray>"))

    protected fun valueIcon(material: Material, name: String, value: String, vararg lore: String): ItemStack =
        Icon.of(material, "<yellow>$name: </yellow><white>$value</white>", lore.toList() + listOf("", "<gray>클릭해서 바꾸기</gray>"))
}
