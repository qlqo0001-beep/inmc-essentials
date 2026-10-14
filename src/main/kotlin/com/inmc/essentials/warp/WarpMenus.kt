package com.inmc.essentials.warp

import com.inmc.essentials.Ess
import com.inmc.essentials.gui.Menu
import com.inmc.essentials.service.TeleportKind
import com.inmc.essentials.util.Ph
import kr.inmc.core.gui.ConfirmMenu
import kr.inmc.core.gui.DialogForm
import kr.inmc.core.gui.Icon
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerCommandPreprocessEvent

/**
 * 워프 화면(CMI Warps.GUI) — 그룹별로 거른다(null = 전체, "" = 그룹 없음). 전체 화면에서는 워프의 칸(slot)을 지킨다.
 * 누르면 이동, 못 쓰는 것은 잠긴 채(설정에 따라), 고칠 수 있으면 Shift+우클릭 = 편집 화면.
 *
 * 이동은 **`/워프 이름` 을 친 것으로**([travel]) — commands 기능의 대기·쿨타임·비용이 명령 이름 `워프` 로 걸리므로, 화면을 연 명령
 * (`/워프그룹` 등)과 무관하게 같은 규칙을 거친다.
 */
class WarpMenu(
    ess: Ess,
    viewer: Player,
    private val service: WarpService,
    private val group: String?,
    private var page: Int = 0,
) : Menu(ess, viewer, SIZE, title(group)) {

    override fun draw() {
        clear()
        val warps = service.visible(viewer, group)
        val layout = if (group == null) WarpRules.arrange(warps) { it.slot } else warps.withIndex().associate { it.index to it.value }
        val pages = ((layout.keys.maxOrNull() ?: 0) / PER_PAGE) + 1
        page = page.coerceIn(0, pages - 1)
        for ((index, warp) in layout) {
            if (index / PER_PAGE != page) continue
            val usable = service.canUse(viewer, warp)
            val editable = service.canEdit(viewer, warp)
            val hints = buildList {
                add("")
                if (usable) add("<yellow>▶ 클릭: 이동</yellow>")
                if (editable) add("<yellow>▶ Shift+우클릭: 편집</yellow>")
                if (warp.hidden) add("<dark_gray>(숨김 — 관리자만 봅니다)</dark_gray>")
            }
            set(index % PER_PAGE, service.icon(warp, !usable, hints)) { event ->
                when {
                    event.isShiftClick && event.isRightClick && editable -> WarpEditMenu(ess, viewer, service, warp.name, back = { WarpMenu(ess, viewer, service, group, page).show() }).show()
                    !usable -> ess.messages.send(viewer, "warps-no-access", Ph.of().raw("{워프}", warp.name))
                    else -> travel(viewer, warp.name)
                }
            }
        }
        if (warps.isEmpty()) set(SLOT_EMPTY, Icon.of(Material.PAPER, "<gray>워프가 없습니다</gray>"))

        for (slot in PER_PAGE until SIZE) set(slot, Icon.FILLER)
        set(SLOT_GROUPS, Icon.of(Material.CHEST, "<aqua>그룹</aqua>", "<gray>지금: <white>${groupLabel(group)}</white></gray>", "", "<yellow>▶ 클릭: 그룹 고르기</yellow>")) {
            WarpGroupMenu(ess, viewer, service).show()
        }
        if (page > 0) set(SLOT_PREV, Icon.prevPage()) { page--; refresh() }
        if (page < pages - 1) set(SLOT_NEXT, Icon.nextPage()) { page++; refresh() }
        set(SLOT_INFO, Icon.of(Material.BOOK, "<gold>워프 ${warps.size}개</gold>", "<gray>/워프 [이름] · /워프그룹</gray>",
            "<dark_gray>쪽 ${page + 1}/$pages</dark_gray>"))
        if (viewer.hasPermission(WarpsFeature.SET)) {
            set(SLOT_NEW, Icon.of(Material.LIME_DYE, "<green>여기에 새 워프</green>", "<gray>지금 선 자리에 워프를 만듭니다.</gray>", "", "<yellow>▶ 클릭: 이름 적기</yellow>")) {
                ask(DialogForm("<yellow>새 워프</yellow>").text("name", "워프 이름", "", service.settings.maxLength)) { v ->
                    val name = v.text("name").trim()
                    if (name.isEmpty()) return@ask
                    val warp = service.create(viewer, WarpRequest(name = name, group = group?.takeIf { it.isNotEmpty() }), "워프설정 $name")
                    if (warp != null && service.settings.guiOnCreation) WarpEditMenu(ess, viewer, service, warp.name, back = { show() }).show()
                }
            }
        }
        set(SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    companion object {
        const val SIZE = 54
        const val PER_PAGE = 45
        const val SLOT_EMPTY = 22
        const val SLOT_GROUPS = 45
        const val SLOT_PREV = 46
        const val SLOT_NEXT = 47
        const val SLOT_INFO = 49
        const val SLOT_NEW = 51
        const val SLOT_CLOSE = 53

        /** 이동 명령 — 대기·쿨타임이 이 이름에 걸린다(계획 4절 표). */
        const val CMD_WARP = "워프"

        /**
         * `/워프 이름` 을 친 것으로(명령 관문·권한 검사를 명령과 똑같이) — `PlayerCommandPreprocessEvent` 를 쏘고 취소되지 않았을 때만(바뀐 글로) 실행한다
         * (commands 기능의 `CommandRunner.dispatch` 와 같은 모양). `player.chat("/…")` 는 쓰지 않는다: 클라이언트 채팅 설정이 '숨김' 인 사람에게는
         * Paper 가 명령을 가르기 전에 돌아가 조용히 버려진다(2026-10-14).
         */
        fun travel(viewer: Player, name: String) {
            viewer.closeInventory()
            val event = PlayerCommandPreprocessEvent(viewer, "/$CMD_WARP $name")
            Bukkit.getPluginManager().callEvent(event)
            if (!event.isCancelled) viewer.performCommand(event.message.removePrefix("/"))
        }

        fun groupLabel(group: String?): String = when (group) {
            null -> "전체"
            "" -> "그룹 없음"
            else -> group
        }

        private fun title(group: String?): String = "<dark_gray>워프 — ${groupLabel(group)}</dark_gray>"
    }
}

/** `/워프그룹`(CMI warpgroups) — 그룹마다 워프 수, 누르면 그 그룹의 워프 화면. 관리자: 우클릭 = 그룹 이름 바꾸기, Shift+우클릭 = 그룹 풀기. */
class WarpGroupMenu(ess: Ess, viewer: Player, private val service: WarpService) : Menu(ess, viewer, SIZE, "<dark_gray>워프 그룹</dark_gray>") {

    override fun draw() {
        clear()
        val visible = service.visible(viewer)
        val groups = WarpRules.groups(visible)
        val entries = listOf<String?>(null) + groups + (if (visible.any { it.group == null }) listOf("") else emptyList())
        val admin = service.isAdmin(viewer)
        for ((index, group) in entries.withIndex().take(PER_PAGE)) {
            val members = visible.filter { WarpRules.inGroup(it, group) }
            val firstIcon = members.firstOrNull()?.let { service.icon(it, false).type } ?: Material.CHEST
            val material = when (group) {
                null -> Material.ENDER_CHEST
                "" -> Material.BARREL
                else -> firstIcon
            }
            val lore = buildList {
                add("<gray>워프 ${members.size}개</gray>")
                add("")
                add("<yellow>▶ 클릭: 열기</yellow>")
                if (admin && !group.isNullOrEmpty()) {
                    add("<yellow>▶ 우클릭: 그룹 이름 바꾸기</yellow>")
                    add("<red>▶ Shift+우클릭: 그룹 풀기(워프는 남음)</red>")
                }
            }
            set(index, Icon.of(material, "<aqua>${WarpMenu.groupLabel(group)}</aqua>", lore)) { event ->
                when {
                    admin && !group.isNullOrEmpty() && event.isShiftClick && event.isRightClick -> ConfirmMenu(ess, "<red>그룹 $group 을(를) 풀까요?</red>",
                        listOf("<gray>워프 ${members.size}개의 그룹이 비워집니다.</gray>"),
                        onConfirm = {
                            val changed = service.store.all().filter { WarpRules.inGroup(it, group) }.map { it.copy(group = null) }
                            service.store.putAll(changed)
                            ess.messages.send(viewer, "warps-group-cleared", Ph.of().raw("{그룹}", group).count(changed.size))
                            show()
                        }, onCancel = { show() }).open(viewer)
                    admin && !group.isNullOrEmpty() && event.isRightClick -> ask(DialogForm("<yellow>그룹 이름 바꾸기</yellow>").text("name", "새 이름", group, 32)) { v ->
                        val name = v.text("name").trim()
                        if (name.isEmpty() || name == group) return@ask
                        val changed = service.store.all().filter { WarpRules.inGroup(it, group) }.map { it.copy(group = name) }
                        service.store.putAll(changed)
                        ess.messages.send(viewer, "warps-group-renamed", Ph.of().raw("{그룹}", name).value(group).count(changed.size))
                    }
                    else -> WarpMenu(ess, viewer, service, group).show()
                }
            }
        }
        for (slot in PER_PAGE until SIZE) set(slot, Icon.FILLER)
        set(SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    companion object {
        const val SIZE = 54
        const val PER_PAGE = 45
        const val SLOT_CLOSE = 53
    }
}

/**
 * `/워프편집 <이름>`(CMI editwarp GUI) — 이름 · 여기로 옮기기 · 아이콘(손/기본) · 그룹 · 권한 필요 · 잠긴 채 보이기 · 숨김 · 보이는 이름 ·
 * 설명 · 자동 설명 · 칸 · 방향 무작위 · 추가 위치 · 가 보기 · 지우기. 고치면 바로 저장한다.
 */
class WarpEditMenu(
    ess: Ess,
    viewer: Player,
    private val service: WarpService,
    private var name: String,
    override val back: (() -> Unit)? = null,
) : Menu(ess, viewer, SIZE, "<dark_gray>워프 편집</dark_gray>") {

    override fun draw() {
        clear()
        val warp = service.store.get(name)
        if (warp == null) {
            set(SLOT_PREVIEW, Icon.of(Material.BARRIER, "<red>워프가 없습니다</red>"))
            navigation(SLOT_BACK, SLOT_CLOSE)
            return
        }
        val ph = Ph.of().raw("{워프}", warp.name)
        set(SLOT_PREVIEW, service.icon(warp, false, listOf("", "<dark_gray>${warp.describe()}</dark_gray>")))

        set(SLOT_RENAME, valueIcon(Material.NAME_TAG, "이름", warp.name)) {
            ask(DialogForm("<yellow>워프 이름</yellow>").text("name", "새 이름", warp.name, service.settings.maxLength)) { v ->
                val next = v.text("name").trim()
                val now = current() ?: return@ask
                if (next.isEmpty() || next == now.name) return@ask
                if (service.rename(viewer, now, next)) name = next
            }
        }
        set(SLOT_MOVE, Icon.of(Material.COMPASS, "<yellow>여기로 옮기기</yellow>", "<gray>지금: <white>${warp.describe()}</white></gray>", "", "<yellow>▶ 클릭: 내가 선 곳으로</yellow>")) {
            if (change { it.moved(viewer.location) } != null) ess.messages.send(viewer, "warps-moved", ph)
            refresh()
        }
        set(SLOT_ICON, Icon.of(Material.ITEM_FRAME, "<yellow>아이콘</yellow>", "<gray>올린 아이템의 모양·이름·설명을 씁니다.</gray>",
            "<dark_gray>(안의 내용물은 옮기지 않습니다)</dark_gray>", "",
            "<yellow>▶ 클릭: 아이템 올리기</yellow>", "<yellow>▶ 우클릭: 기본으로</yellow>")) { event ->
            if (event.isRightClick) {
                if (change { it.copy(icon = null, iconItem = null) } != null) ess.messages.send(viewer, "warps-icon-reset", ph)
                refresh()
                return@set
            }
            // 손에 든 것 대신 아이템 올리기(사용자 요청 2026-10-14 — 모든 INMC 관리 화면).
            kr.inmc.core.gui.ItemInputMenu(ess, viewer, "워프 아이콘 — 아이템 올리기", hint = listOf("<gray>모양·이름·설명을 이 아이템에서 씁니다.</gray>"),
                submitLabel = "아이콘으로", back = { show() }) { stack ->
                if (change { it.copy(icon = stack.type.name, iconItem = service.encode(stack)) } != null) {
                    ess.messages.send(viewer, "warps-icon-set", ph.value(stack.type.name.lowercase()))
                }
                show()
            }.show()
        }
        set(SLOT_GROUP, valueIcon(Material.CHEST, "그룹", warp.group ?: "없음", "<gray>비우면 그룹을 뺍니다.</gray>")) {
            ask(DialogForm("<yellow>그룹</yellow>").text("group", "그룹 이름(비우면 없음)", warp.group.orEmpty(), 32)) { v ->
                val group = v.text("group").trim().ifEmpty { null }
                if (change { it.copy(group = group) } != null) ess.messages.send(viewer, "warps-group-set", ph.raw("{그룹}", group ?: "없음"))
            }
        }
        set(SLOT_PERMISSION, toggleIcon("권한 필요", warp.permission, "<gray>켜면 이 권한이 있어야 씁니다:</gray>",
            "<white>${WarpRules.WARP_PREFIX}${warp.key}</white>", "<dark_gray>(${WarpsFeature.ALL} = 모든 워프)</dark_gray>")) {
            change { it.copy(permission = !it.permission) }
            refresh()
        }
        set(SLOT_SHOW_LOCKED, toggleIcon("권한 없어도 보이기", warp.showLocked, "<gray>권한이 없는 사람에게 잠긴 채로 보입니다.</gray>")) {
            change { it.copy(showLocked = !it.showLocked) }
            refresh()
        }
        set(SLOT_HIDDEN, toggleIcon("숨김", warp.hidden, "<gray>목록·화면·제안에서 숨깁니다(관리자만 봄).</gray>", "<gray>이름을 아는 사람은 /워프 이름 으로 갑니다.</gray>")) {
            change { it.copy(hidden = !it.hidden) }
            refresh()
        }
        set(SLOT_DISPLAY, valueIcon(Material.OAK_SIGN, "보이는 이름", warp.displayName ?: "(이름 그대로)", "<gray>MiniMessage 색 서식을 씁니다.</gray>")) {
            ask(DialogForm("<yellow>보이는 이름</yellow>").text("display", "보이는 이름(비우면 이름 그대로)", warp.displayName.orEmpty(), 128)) { v ->
                val display = v.text("display").trim().ifEmpty { null }
                change { it.copy(displayName = display) }
            }
        }
        set(SLOT_DESCRIPTION, Icon.of(Material.WRITABLE_BOOK, "<yellow>설명</yellow>", warp.description.ifEmpty { listOf("<gray>(없음)</gray>") } + listOf("", "<yellow>▶ 클릭: 고치기(줄마다 한 줄)</yellow>"))) {
            ask(DialogForm("<yellow>설명</yellow>").text("lore", "설명(줄바꿈으로 여러 줄)", warp.description.joinToString("\n"), 1024, multiline = true)) { v ->
                val lines = v.text("lore").lines().map { it.trimEnd() }.dropLastWhile { it.isEmpty() }
                change { it.copy(description = lines) }
            }
        }
        set(SLOT_AUTO_LORE, toggleIcon("자동 설명", warp.autoLore, "<gray>월드·좌표·그룹을 설명 아래에 붙입니다.</gray>")) {
            change { it.copy(autoLore = !it.autoLore) }
            refresh()
        }
        set(SLOT_SLOT, valueIcon(Material.HOPPER, "화면 칸", warp.slot?.toString() ?: "자동", "<gray>전체 화면에서의 자리(1부터, 쪽당 45칸).</gray>", "<gray>0 = 자동.</gray>")) {
            ask(DialogForm("<yellow>화면 칸</yellow>").long("slot", "칸(0 = 자동)", (warp.slot ?: 0).toLong(), 0, WarpService.MAX_SLOT.toLong())) { v ->
                val slot = v.long("slot")?.toInt()?.takeIf { it > 0 }
                val taken = slot?.let { s -> service.store.all().firstOrNull { it.slot == s && it.key != Warp.keyOf(name) } }
                if (taken != null) return@ask ess.messages.send(viewer, "warps-slot-taken", ph.raw("{다른워프}", taken.name).value(slot.toString()))
                change { it.copy(slot = slot) }
            }
        }
        set(SLOT_RANDOM_YAW, toggleIcon("도착 방향 무작위", warp.randomYaw, "<gray>도착할 때 바라보는 방향을 무작위로.</gray>")) {
            change { it.copy(randomYaw = !it.randomYaw) }
            refresh()
        }
        set(SLOT_EXTRA, Icon.of(Material.ENDER_EYE, "<yellow>추가 위치 ${warp.extra.size}곳</yellow>",
            listOf("<gray>이동할 때 기본 위치와 추가 위치 중 무작위로.</gray>") + warp.extra.take(8).map { "<dark_gray>$it</dark_gray>" } +
                listOf("", "<yellow>▶ 클릭: 내가 선 곳을 더하기</yellow>", "<red>▶ Shift+우클릭: 모두 지우기</red>"))) { event ->
            if (event.isShiftClick && event.isRightClick) {
                if (change { it.copy(extra = emptyList()) } != null) ess.messages.send(viewer, "warps-extra-cleared", ph)
            } else {
                change { it.copy(extra = it.extra + Warp.spot(viewer.location)) }?.let { changed ->
                    ess.messages.send(viewer, "warps-extra-added", ph.count(changed.extra.size + 1))
                }
            }
            refresh()
        }
        set(SLOT_VISIT, Icon.of(Material.ENDER_PEARL, "<yellow>가 보기</yellow>", "<gray>${warp.describe()}</gray>")) {
            val now = current() ?: return@set
            // 관리자가 아니면(만든 사람) /워프 와 같은 규칙(대기·쿨타임·비용·권한)으로.
            if (!service.isAdmin(viewer)) return@set WarpMenu.travel(viewer, now.name)
            val to = now.location() ?: return@set ess.messages.send(viewer, "warps-world-missing", ph.world(now.world))
            viewer.closeInventory()
            ess.teleporter.teleport(viewer, to, TeleportKind.ADMIN)
        }
        set(SLOT_DELETE, Icon.of(Material.LAVA_BUCKET, "<red>워프 지우기</red>", "<gray>되돌릴 수 없습니다.</gray>")) {
            ConfirmMenu(ess, "<red>워프 ${warp.name} 을(를) 지울까요?</red>", listOf("<gray>${warp.describe()}</gray>"),
                onConfirm = {
                    val now = current()
                    if (now != null && service.remove(viewer, now.name)) ess.messages.send(viewer, "warps-removed", ph)
                    back?.invoke() ?: viewer.closeInventory()
                }, onCancel = { show() }).open(viewer)
        }
        fillEmpty(Icon.FILLER)
        navigation(SLOT_BACK, SLOT_CLOSE)
    }

    /** 누른 순간의 워프 — 화면을 그린 뒤 지워졌거나 고칠 권한이 없어졌으면 알리고 null(낡은 사본을 되써 넣지 않게). */
    private fun current(): Warp? {
        val warp = service.store.get(name) ?: return null.also { ess.messages.send(viewer, "warps-not-found", Ph.of().raw("{워프}", name)) }
        if (!service.canEdit(viewer, warp)) return null.also { ess.messages.send(viewer, "warps-not-yours", Ph.of().raw("{워프}", warp.name)) }
        return warp
    }

    /** 지금의 워프를 고쳐 저장한다 — 고친 것, 못 했으면 null. */
    private fun change(edit: (Warp) -> Warp): Warp? {
        val now = current() ?: return null
        return edit(now).also { service.store.put(it, old = now.name) }
    }

    companion object {
        const val SIZE = 45
        const val SLOT_PREVIEW = 4
        const val SLOT_RENAME = 10
        const val SLOT_MOVE = 11
        const val SLOT_ICON = 12
        const val SLOT_GROUP = 13
        const val SLOT_PERMISSION = 14
        const val SLOT_SHOW_LOCKED = 15
        const val SLOT_HIDDEN = 16
        const val SLOT_DISPLAY = 19
        const val SLOT_DESCRIPTION = 20
        const val SLOT_AUTO_LORE = 21
        const val SLOT_SLOT = 22
        const val SLOT_RANDOM_YAW = 23
        const val SLOT_EXTRA = 24
        const val SLOT_VISIT = 25
        const val SLOT_BACK = 36
        const val SLOT_DELETE = 40
        const val SLOT_CLOSE = 44
    }
}
