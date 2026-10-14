package com.inmc.essentials.moderation

import com.inmc.essentials.Ess
import com.inmc.essentials.gui.Menu
import com.inmc.essentials.service.TeleportKind
import com.inmc.essentials.store.LogoutSpot
import com.inmc.essentials.util.Ph
import kr.inmc.core.gui.ConfirmMenu
import kr.inmc.core.gui.DialogForm
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta

private fun jailIcon(mod: Moderation, jails: JailService, jail: Jail, vararg tail: String): ItemStack = Icon.of(
    Material.IRON_BARS, "<white>${jail.name}</white>",
    listOf(
        "<gray>영역: </gray><white>${jail.area.describe()}</white>",
        "<gray>감방: </gray><white>${jail.cells.size}개</white>",
        "<gray>갇힌 사람: </gray><white>${jails.occupants(jail.name).size}명</white>",
        "<gray>석방 위치: </gray>" + (jail.outside?.let { "<white>${it.world} ${it.x.toInt()}, ${it.y.toInt()}, ${it.z.toInt()}</white>" } ?: "<red>없음</red> <dark_gray>(가두기 전 자리로)</dark_gray>"),
    ) + if (tail.isEmpty()) emptyList() else listOf("") + tail.toList(),
)

/** `/감옥편집` — 감옥 목록과 새 감옥(CMI 의 채팅 편집을 화면으로). */
class JailEditMenu(ess: Ess, viewer: Player, private val mod: Moderation, private val jails: JailService, private val cmds: JailCommands) :
    Menu(ess, viewer, SIZE, "<dark_gray>감옥 편집</dark_gray>") {

    override fun draw() {
        clear()
        for ((i, jail) in jails.jails.sortedBy { it.name }.take(Paging.PER_PAGE).withIndex()) {
            set(i, jailIcon(mod, jails, jail, "<yellow>▶ 클릭: 고치기</yellow>")) { JailDetailMenu(ess, viewer, mod, jails, cmds, jail.name).show() }
        }
        for (slot in Paging.PER_PAGE until SIZE) set(slot, Icon.FILLER)
        set(SLOT_NEW, Icon.of(Material.LIME_DYE, "<green>새 감옥</green>", "<gray>WorldEdit 선택 영역이 있으면 그 상자,</gray>",
            "<gray>없으면 선 자리 둘레 ${mod.settings.jailRadius}블록.</gray>", "<gray>선 자리가 영역 안이면 1번 감방이 됩니다.</gray>", "", "<yellow>▶ 클릭: 이름 적기</yellow>")) {
            ask(DialogForm("<yellow>새 감옥</yellow>").text("name", "감옥 이름", "", 32)) { v ->
                val name = v.text("name").trim()
                if (name.isNotEmpty()) cmds.create(viewer, name)
            }
        }
        navigation()
    }

    companion object {
        const val SIZE = 54
        const val SLOT_NEW = 49
    }
}

/** 감옥 하나 고치기 — 석방 위치·영역·감방·보기·이동·지우기. 아래 칸은 감방(클릭 이동 · Shift+우클릭 지우기). */
class JailDetailMenu(
    ess: Ess,
    viewer: Player,
    private val mod: Moderation,
    private val jails: JailService,
    private val cmds: JailCommands,
    private val name: String,
) : Menu(ess, viewer, SIZE, "<dark_gray>감옥: $name</dark_gray>") {

    override val back: (() -> Unit) = { JailEditMenu(ess, viewer, mod, jails, cmds).show() }

    override fun draw() {
        clear()
        val jail = jails.jail(name) ?: return back()
        for (slot in 0 until CELLS_FROM) set(slot, Icon.FILLER)
        set(SLOT_INFO, jailIcon(mod, jails, jail))
        set(SLOT_OUTSIDE, Icon.of(Material.OAK_DOOR, "<aqua>석방 위치를 여기로</aqua>", "<gray>풀려난 사람이 이 자리로 갑니다.</gray>")) {
            jails.replace(jail.copy(outside = LogoutSpot.of(viewer.location)))
            ess.messages.send(viewer, "moderation-jailedit-outside-set", Ph.of().raw("{감옥}", jail.name))
            refresh()
        }
        set(SLOT_AREA, Icon.of(Material.WOODEN_AXE, "<aqua>영역 다시 정하기</aqua>", "<gray>WorldEdit 선택 · 없으면 선 자리 둘레</gray>")) {
            cmds.area(viewer, jail.name)
            refresh()
        }
        set(SLOT_CELL, Icon.of(Material.RED_BED, "<aqua>여기에 감방 더하기</aqua>", "<gray>영역 안에 서 있어야 합니다.</gray>")) {
            cmds.addCell(viewer, jail.name)
            refresh()
        }
        set(SLOT_SHOW, Icon.of(Material.SPYGLASS, "<aqua>영역 보기</aqua>", "<gray>10초 동안 모서리를 입자로</gray>")) {
            viewer.closeInventory()
            jails.show(viewer, jail)
        }
        set(SLOT_TP, Icon.of(Material.ENDER_PEARL, "<aqua>감옥으로 가기</aqua>")) {
            viewer.closeInventory()
            (jail.cells.firstOrNull()?.spot ?: jail.area.center()).toLocation()?.let { ess.teleporter.teleport(viewer, it, TeleportKind.ADMIN) }
        }
        set(SLOT_JAILED, Icon.of(Material.PLAYER_HEAD, "<aqua>갇힌 사람</aqua>", "<gray>${jails.occupants(jail.name).size}명</gray>")) {
            JailedMenu(ess, viewer, mod, jails, jail.name, null, back = { show() }).show()
        }
        set(SLOT_DELETE, Icon.of(Material.LAVA_BUCKET, "<red>감옥 지우기</red>", "<gray>갇힌 사람은 모두 풀려납니다.</gray>")) {
            ConfirmMenu(ess, "<red>감옥 ${jail.name} 을(를) 지울까요?</red>", listOf("<gray>갇힌 ${jails.occupants(jail.name).size}명은 풀려납니다.</gray>"), onConfirm = {
                val freed = jails.remove(jail.name)
                ess.messages.send(viewer, "moderation-jailedit-removed", Ph.of().raw("{감옥}", jail.name).count(freed))
                back()
            }, onCancel = { show() }).open(viewer)
        }
        for ((i, cell) in jail.cells.take(CELLS_TO - CELLS_FROM).withIndex()) {
            val s = cell.spot
            set(CELLS_FROM + i, Icon.of(Material.IRON_DOOR, "<white>${cell.id}번 감방</white>",
                "<gray>${s.world} ${s.x.toInt()}, ${s.y.toInt()}, ${s.z.toInt()}</gray>", "<gray>갇힌 사람: ${jails.occupants(jail.name, cell.id).size}명</gray>", "",
                "<yellow>▶ 클릭: 이동</yellow>", "<red>▶ Shift+우클릭: 지우기</red>")) { e ->
                if (e.isShiftClick && e.isRightClick) {
                    ConfirmMenu(ess, "<red>${cell.id}번 감방을 지울까요?</red>", onConfirm = {
                        jails.jail(jail.name)?.let { j -> jails.replace(j.copy(cells = j.cells.filterNot { it.id == cell.id })) }
                        for (j in jails.occupants(jail.name, cell.id)) {
                            j.cell = null
                            mod.store.putJailed(j)
                        }
                        ess.messages.send(viewer, "moderation-jailedit-cell-removed", Ph.of().raw("{감옥}", jail.name).raw("{감방}", cell.id.toString()))
                        show()
                    }, onCancel = { show() }).open(viewer)
                } else {
                    viewer.closeInventory()
                    s.toLocation()?.let { ess.teleporter.teleport(viewer, it, TeleportKind.ADMIN) }
                }
            }
        }
        navigation()
    }

    companion object {
        const val SIZE = 54
        const val SLOT_INFO = 4
        const val SLOT_OUTSIDE = 10
        const val SLOT_AREA = 11
        const val SLOT_CELL = 12
        const val SLOT_SHOW = 13
        const val SLOT_TP = 14
        const val SLOT_JAILED = 15
        const val SLOT_DELETE = 16
        const val CELLS_FROM = 18
        const val CELLS_TO = 45
    }
}

/** `/감옥목록` — 감옥들(누르면 갇힌 사람). */
class JailListMenu(ess: Ess, viewer: Player, private val mod: Moderation, private val jails: JailService) :
    Menu(ess, viewer, SIZE, "<dark_gray>감옥 목록</dark_gray>") {

    override fun draw() {
        clear()
        for ((i, jail) in jails.jails.sortedBy { it.name }.take(Paging.PER_PAGE).withIndex()) {
            set(i, jailIcon(mod, jails, jail, "<yellow>▶ 클릭: 갇힌 사람</yellow>")) { JailedMenu(ess, viewer, mod, jails, jail.name, null, back = { show() }).show() }
        }
        for (slot in Paging.PER_PAGE until SIZE) set(slot, Icon.FILLER)
        set(SLOT_ALL, Icon.of(Material.PLAYER_HEAD, "<aqua>갇힌 사람 전부</aqua>", "<gray>${mod.state.jailed.size}명</gray>")) {
            JailedMenu(ess, viewer, mod, jails, null, null, back = { show() }).show()
        }
        navigation()
    }

    companion object {
        const val SIZE = 54
        const val SLOT_ALL = 49
    }
}

/** 갇힌 사람들 — 머리마다 감옥·감방·남은 시간·사유·가둔 사람. 누르면 석방(확인). */
class JailedMenu(
    ess: Ess,
    viewer: Player,
    private val mod: Moderation,
    private val jails: JailService,
    private val jail: String?,
    private val cell: Int?,
    override val back: (() -> Unit)?,
    private var page: Int = 0,
) : Menu(ess, viewer, SIZE, "<dark_gray>갇힌 사람" + (jail?.let { " · $it" } ?: "") + (cell?.let { " · ${it}번" } ?: "") + "</dark_gray>") {

    override fun draw() {
        clear()
        val list = (if (jail == null) mod.state.jailed.values.toList() else jails.occupants(jail, cell)).sortedBy { it.created }
        val pages = Paging.pageCount(list.size, Paging.PER_PAGE)
        page = page.coerceIn(0, (pages - 1).coerceAtLeast(0))
        for ((i, j) in list.drop(page * Paging.PER_PAGE).take(Paging.PER_PAGE).withIndex()) {
            val name = ess.users.get(j.id)?.name ?: j.id.toString()
            val online = Bukkit.getPlayer(j.id)
            val stack = Icon.of(Material.PLAYER_HEAD, (if (online != null) "<green>" else "<gray>") + name, listOf(
                "<gray>감옥: </gray><white>${j.jail}</white> <gray>· 감방 </gray><white>${j.cell ?: "-"}</white>",
                "<gray>남은 시간: </gray><white>${JailService.remainingText(mod, j)}</white>",
                "<gray>사유: </gray><white>${j.reason}</white>",
                "<gray>가둔 사람: </gray><white>${j.source}</white> <dark_gray>${mod.dateText(j.created)}</dark_gray>",
            ) + if (viewer.hasPermission(ModerationFeature.UNJAIL)) listOf("", "<yellow>▶ 클릭: 석방</yellow>") else emptyList())
            (stack.itemMeta as? SkullMeta)?.let { meta ->
                runCatching { meta.playerProfile = Bukkit.createProfile(j.id, ess.users.get(j.id)?.name) }
                stack.itemMeta = meta
            }
            set(i, stack) {
                if (!viewer.hasPermission(ModerationFeature.UNJAIL)) return@set
                ConfirmMenu(ess, "<green>$name 을(를) 풀어 줄까요?</green>", onConfirm = {
                    jails.release(viewer, j.id, silent = false, auto = false)
                    show()
                }, onCancel = { show() }).open(viewer)
            }
        }
        for (slot in Paging.PER_PAGE until SIZE) set(slot, Icon.FILLER)
        pager(page, list.size) { page = it; refresh() }
        navigation()
    }

    companion object {
        const val SIZE = 54
    }
}
