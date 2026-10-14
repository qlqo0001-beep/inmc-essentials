package com.inmc.essentials.hologram

import com.inmc.essentials.Ess
import com.inmc.essentials.gui.Menu
import com.inmc.essentials.service.TeleportKind
import com.inmc.essentials.util.Ph
import kr.inmc.core.gui.ConfirmMenu
import kr.inmc.core.gui.DialogForm
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.Base64
import org.bukkit.inventory.ItemStack

/** `/홀로그램` · `/홀로그램 목록` — 가까운 순(다른 월드는 뒤). 클릭 = 편집, Shift+우클릭 = 그리로 이동. */
class HologramListMenu(ess: Ess, viewer: Player, private val engine: HologramEngine, private val feature: HologramsFeature?, private var page: Int = 0) :
    Menu(ess, viewer, SIZE, "<dark_gray>홀로그램</dark_gray>") {

    override fun draw() {
        clear()
        val here = viewer.location
        val all = engine.store.all().sortedWith(compareBy({ it.world != here.world?.name }, { dist(it, here) }))
        page = Paging.clamp(page, all.size)
        for ((index, h) in Paging.slice(all, page).withIndex()) {
            val first = h.pages.firstOrNull()?.lines?.firstOrNull { !it.isIcon }?.text ?: h.name
            val lore = listOf(
                "<gray>$first</gray>",
                "<gray>${h.world} %.1f, %.1f, %.1f${if (h.world == here.world?.name) " · %.0fm".format(dist(h, here)) else ""}</gray>".format(h.x, h.y, h.z),
                "<gray>쪽 ${h.pages.size} · 줄 ${h.lineCount}${if (h.enabled) "" else " · <red>꺼짐</red>"}</gray>",
                "",
                "<yellow>▶ 클릭: 편집</yellow>",
                "<yellow>▶ Shift+우클릭: 그리로 이동</yellow>",
            )
            set(index, Icon.of(if (h.enabled) Material.NAME_TAG else Material.GRAY_DYE, "<aqua>${h.name}</aqua>", lore)) { event ->
                if (event.isShiftClick && event.isRightClick) {
                    viewer.closeInventory()
                    val world = Bukkit.getWorld(h.world) ?: return@set
                    ess.teleporter.teleport(viewer, Location(world, h.x, h.y - 2.0, h.z, viewer.location.yaw, viewer.location.pitch), TeleportKind.ADMIN)
                } else {
                    val f = feature ?: (ess.feature("holograms") as? HologramsFeature) ?: return@set
                    HologramEditMenu(ess, viewer, engine, f, h.name, 0, back = { HologramListMenu(ess, viewer, engine, feature, page).show() }).show()
                }
            }
        }
        if (all.isEmpty()) set(SLOT_EMPTY, Icon.of(Material.PAPER, "<gray>홀로그램이 없습니다</gray>", "<gray>/홀로그램 만들기 <이름> [첫 줄]</gray>"))
        for (slot in Paging.PER_PAGE until SIZE) set(slot, Icon.FILLER)
        pager(page, all.size) { page = it; refresh() }
        set(SLOT_INFO, Icon.of(Material.BOOK, "<gold>홀로그램 ${all.size}개</gold>", "<gray>/홀로그램 도움</gray>"))
        set(Paging.SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    private fun dist(h: Hologram, at: Location): Double {
        val dx = h.x - at.x
        val dy = h.y - at.y
        val dz = h.z - at.z
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    companion object {
        const val SIZE = 54
        const val SLOT_EMPTY = 22
        const val SLOT_INFO = 49
    }
}

/**
 * 홀로그램 편집 — 한 쪽의 줄들(클릭 = 글 고치기 · 우클릭 = 누르기 동작 · Shift+좌클릭 = 위로 · Shift+우클릭 = 지우기) + 줄·쪽 더하기, 쪽 동작,
 * 설정(보이는 거리·갱신·자동 넘김·권한·조건·방향·크기·배경·그림자·벽 너머·누르면 넘기기), 여기로 옮기기, 이동, 지우기. 바꾸면 바로 저장·다시 세운다.
 */
class HologramEditMenu(
    ess: Ess,
    viewer: Player,
    private val engine: HologramEngine,
    private val feature: HologramsFeature,
    private val name: String,
    private var page: Int,
    override val back: (() -> Unit)? = null,
) : Menu(ess, viewer, SIZE, "<dark_gray>홀로그램 편집 — $name</dark_gray>") {

    private val store get() = engine.store

    private fun current(): Hologram? = store.get(name)

    private fun save(change: (Hologram) -> Hologram) {
        val h = current() ?: return viewer.closeInventory()
        if (!feature.editable(viewer)) return
        store.put(change(h))
        refresh()
    }

    /** 지금 정의(그리는 중의 지역 변수 `current` 와 이름이 겹치지 않게). */
    private fun def(): Hologram? = store.get(name)

    private fun editPage(change: (HoloPage) -> HoloPage) = save { h ->
        h.copy(pages = h.pages.mapIndexed { i, p -> if (i == page) change(p) else p })
    }

    /**
     * [pg] 쪽 [index] 번 줄이 화면을 그린 때의 [expected] 그대로일 때만 고친다 — 확인 화면·입력 창이 떠 있는 동안 다른 관리자(명령·자기 편집 화면)가
     * 줄을 끼우거나 지웠으면 엉뚱한 줄을 고치지 않고 알린 뒤 다시 그린다.
     */
    private fun editLine(pg: Int, index: Int, expected: HoloLine, change: (List<HoloLine>) -> List<HoloLine>) {
        val h = current() ?: return viewer.closeInventory()
        if (!feature.editable(viewer)) return
        if (h.pages.getOrNull(pg)?.lines?.getOrNull(index) != expected) {
            ess.messages.send(viewer, "holograms-changed", Ph.of().raw("{홀로그램}", h.name))
            return refresh()
        }
        store.put(h.copy(pages = h.pages.mapIndexed { i, p -> if (i == pg) p.copy(lines = change(p.lines)) else p }))
        refresh()
    }

    /** [pg] 쪽의 누르기 동작 — 그린 때의 [expected] 그대로일 때만(위와 같은 까닭). */
    private fun editPageActions(pg: Int, expected: HoloActions, next: HoloActions) {
        val h = current() ?: return viewer.closeInventory()
        if (!feature.editable(viewer)) return
        if (h.pages.getOrNull(pg)?.actions != expected) {
            ess.messages.send(viewer, "holograms-changed", Ph.of().raw("{홀로그램}", h.name))
            return refresh()
        }
        store.put(h.copy(pages = h.pages.mapIndexed { i, p -> if (i == pg) p.copy(actions = next) else p }))
        refresh()
    }

    override fun draw() {
        clear()
        val h = current() ?: return set(SLOT_INFO, Icon.of(Material.BARRIER, "<red>지워진 홀로그램입니다</red>"))
        page = page.coerceIn(0, h.pages.lastIndex)
        val pg = page
        val current = h.pages[pg]
        for ((index, line) in current.lines.withIndex().take(MAX_LINES)) {
            val actions = HoloClick.entries.mapNotNull { c -> line.actions.map[c]?.takeIf { it.isNotEmpty() }?.let { "<gray>${c.label}: ${it.size}개</gray>" } }
            val lore = buildList {
                if (line.isIcon) add("<gray>아이콘: ${line.icon ?: "스냅샷"}</gray>") else add("<dark_gray>${MiniMessage.miniMessage().escapeTags(line.text)}</dark_gray>")
                addAll(actions)
                add("")
                add(if (line.isIcon) "<yellow>▶ 클릭: 아이템 올려 바꾸기</yellow>" else "<yellow>▶ 클릭: 글 고치기</yellow>")
                add("<yellow>▶ 우클릭: 누르기 동작</yellow>")
                add("<yellow>▶ Shift+좌클릭: 위로</yellow>")
                add("<red>▶ Shift+우클릭: 지우기</red>")
            }
            val icon = if (line.isIcon) Icon.annotate(engine.icon(line), "<white>${index + 1}. 아이콘</white>", lore)
            else Icon.of(Material.PAPER, "<white>${index + 1}.</white> " + line.text.ifEmpty { "<dark_gray>(빈 줄)</dark_gray>" }, lore)
            set(index, icon) { event ->
                when {
                    event.isShiftClick && event.isRightClick -> ConfirmMenu(ess, "<red>${index + 1}번 줄을 지울까요?</red>", onConfirm = {
                        editLine(pg, index, line) { lines -> lines.filterIndexed { j, _ -> j != index } }
                        def()?.pages?.getOrNull(pg)?.let { p ->
                            if (p.lines.isEmpty() && (def()?.pages?.size ?: 0) > 1) {
                                ess.messages.send(viewer, "holograms-page-empty", Ph.of().raw("{홀로그램}", name).raw("{쪽}", (pg + 1).toString()))
                            }
                        }
                        show()
                    }, onCancel = { show() }).open(viewer)
                    event.isShiftClick -> if (index > 0) editLine(pg, index, line) { lines ->
                        lines.toMutableList().apply { add(index - 1, removeAt(index)) }
                    }
                    event.isRightClick -> askActions("${index + 1}번 줄", line.actions) { a ->
                        editLine(pg, index, line) { lines -> lines.mapIndexed { j, ln -> if (j == index) ln.copy(actions = a) else ln } }
                    }
                    line.isIcon -> askIcon("${index + 1}번 줄 아이콘") { icon ->
                        editLine(pg, index, line) { lines -> lines.mapIndexed { j, ln -> if (j == index) icon.copy(actions = ln.actions) else ln } }
                    }
                    else -> ask(DialogForm("<yellow>${index + 1}번 줄</yellow>").line("<gray>MiniMessage · & 색 · PAPI · {player}</gray>")
                        .text("text", "글", line.text, 512)) { v ->
                        editLine(pg, index, line) { lines -> lines.mapIndexed { j, ln -> if (j == index) ln.copy(text = v.text("text")) else ln } }
                    }
                }
            }
        }
        for (slot in current.lines.size.coerceAtMost(MAX_LINES) until MAX_LINES) set(slot, null)
        for (slot in MAX_LINES until SIZE) set(slot, Icon.FILLER)

        set(SLOT_ADD_TEXT, Icon.of(Material.LIME_DYE, "<green>글 줄 더하기</green>", "<gray>이 쪽의 끝에</gray>")) {
            ask(DialogForm("<yellow>새 줄</yellow>").line("<gray>MiniMessage · & 색 · PAPI · {player}</gray>").text("text", "글", "", 512)) { v ->
                editPage { p -> p.copy(lines = p.lines + HoloLine(text = v.text("text"))) }
            }
        }
        set(SLOT_ADD_ICON, Icon.of(Material.ITEM_FRAME, "<green>아이콘 줄 더하기</green>", "<gray>커스텀아이템·MMOItems 는 참조로</gray>", "",
            "<yellow>▶ 클릭: 아이템 올리기</yellow>", "<yellow>▶ 아래 가방의 아이템을 Shift+클릭: 이 쪽 끝에 바로</yellow>")) {
            askIcon("아이콘 줄 더하기") { icon -> editPage { p -> p.copy(lines = p.lines + icon) } }
        }
        set(SLOT_PAGE_ACTIONS, valueIcon(Material.COMMAND_BLOCK, "${page + 1}쪽 누르기 동작", if (current.actions.isEmpty) "없음" else "있음",
            "<gray>동작이 없는 줄을 누르면 이것이 돕니다.</gray>")) {
            askActions("${pg + 1}쪽", current.actions) { a -> editPageActions(pg, current.actions, a) }
        }
        set(SLOT_ADD_PAGE, Icon.of(Material.WRITABLE_BOOK, "<green>쪽 더하기</green>", "<gray>지금 ${h.pages.size}쪽</gray>")) {
            save { it.copy(pages = it.pages + HoloPage(listOf(HoloLine(text = "${it.pages.size + 1}쪽")))) }
            page = (current()?.pages?.lastIndex ?: 0)
            refresh()
        }
        if (h.pages.size > 1) {
            set(SLOT_REMOVE_PAGE, Icon.of(Material.RED_DYE, "<red>${page + 1}쪽 지우기</red>")) {
                ConfirmMenu(ess, "<red>${pg + 1}쪽을 지울까요?</red>", onConfirm = {
                    val removed = pg
                    val now = def()
                    if (!feature.editable(viewer)) Unit
                    else if (now == null || now.pages.getOrNull(removed) != current || now.pages.size <= 1) {
                        ess.messages.send(viewer, "holograms-changed", Ph.of().raw("{홀로그램}", name))
                    } else {
                        store.put(now.copy(pages = now.pages.filterIndexed { i, _ -> i != removed }))
                        page = (removed - 1).coerceAtLeast(0)
                    }
                    show()
                }, onCancel = { show() }).open(viewer)
            }
        }
        set(SLOT_SETTINGS, Icon.of(Material.COMPARATOR, "<yellow>설정</yellow>",
            "<gray>보이는 거리: ${h.viewRange ?: "기본(${feature.settings.viewRange})"}</gray>",
            "<gray>갱신: ${h.updateSeconds ?: "기본(${feature.settings.updateSeconds})"}초 · ${h.updateRange ?: "기본(${feature.settings.updateRange})"}블록 안 · 자동 넘김: ${h.pageSeconds ?: "기본(${feature.settings.pageSeconds})"}초</gray>",
            "<gray>권한: ${h.permission ?: "없음"}</gray>",
            "<gray>조건: ${h.condition?.let { MiniMessage.miniMessage().escapeTags(it) } ?: "없음"}${if (h.condition != null && !HoloRules.conditionValid(h.condition)) " <red>(못 읽음 — 아무에게도 안 보임)</red>" else ""}</gray>",
            "<gray>방향 ${h.billboard} · 크기 ${h.scale} · 배경 ${h.background}</gray>",
            "<gray>그림자 ${h.shadow} · 벽 너머 ${h.seeThrough} · 누르면 넘기기 ${h.clickPages}</gray>",
            "<gray>정렬 ${alignmentLabel(h.alignment)} · 감기 너비 ${h.lineWidth} · 글자 불투명도 ${h.textOpacity}</gray>",
            "", "<yellow>▶ 클릭: 바꾸기</yellow>")) { askSettings(h) }
        set(SLOT_ENABLED, toggleIcon("켜짐", h.enabled)) { save { it.copy(enabled = !it.enabled) } }
        set(SLOT_MOVE_HERE, Icon.of(Material.COMPASS, "<yellow>여기로 옮기기</yellow>", "<gray>눈높이 조금 위</gray>")) {
            val at = feature.spawnPoint(viewer)
            save { it.copy(world = at.world.name, x = at.x, y = at.y, z = at.z, yaw = if (it.billboard == "FIXED") viewer.location.yaw + 180f else it.yaw) }
        }
        set(SLOT_TELEPORT, Icon.of(Material.ENDER_PEARL, "<yellow>그리로 이동</yellow>")) {
            val world = Bukkit.getWorld(h.world) ?: return@set ess.messages.send(viewer, "holograms-world-missing", Ph.of().raw("{홀로그램}", h.name).world(h.world))
            viewer.closeInventory()
            ess.teleporter.teleport(viewer, Location(world, h.x, h.y - 2.0, h.z, viewer.location.yaw, viewer.location.pitch), TeleportKind.ADMIN)
        }
        if (page > 0) set(SLOT_PREV, Icon.of(Material.SPECTRAL_ARROW, "<yellow>◀ ${page}쪽</yellow>")) { page--; refresh() }
        if (page < h.pages.lastIndex) set(SLOT_NEXT, Icon.of(Material.SPECTRAL_ARROW, "<yellow>${page + 2}쪽 ▶</yellow>")) { page++; refresh() }
        set(SLOT_INFO, Icon.of(Material.BOOK, "<gold>${h.name}</gold> <gray>${page + 1}/${h.pages.size}쪽</gray>",
            "<gray>${h.world} %.1f, %.1f, %.1f</gray>".format(h.x, h.y, h.z),
            "<gray>줄이 ${MAX_LINES}개를 넘으면 /홀로그램 줄수정 으로</gray>"))
        set(SLOT_DELETE, Icon.of(Material.LAVA_BUCKET, "<red>홀로그램 지우기</red>")) {
            ConfirmMenu(ess, "<red>홀로그램 ${h.name} 을(를) 지울까요?</red>", onConfirm = {
                if (!feature.editable(viewer)) show()
                else {
                    store.remove(h.name)
                    ess.messages.send(viewer, "holograms-removed", Ph.of().raw("{홀로그램}", h.name))
                    back?.invoke() ?: viewer.closeInventory()
                }
            }, onCancel = { show() }).open(viewer)
        }
        navigation()
    }

    /** 아이템 → 아이콘 줄(손에 든 것 대신 올린 것·Shift+클릭한 것 — 사용자 요청 2026-10-14). */
    private fun iconLine(stack: ItemStack): HoloLine {
        val stored = ess.itemResolver.capture(stack)
        return HoloLine(icon = stored.ref.serialize(), material = stored.material.name, snapshot = stored.snapshot?.let { Base64.getEncoder().encodeToString(it) })
    }

    /** 아이템 올리기 창을 열고, 올린 것으로 아이콘 줄을 만들어 [done]. 끝나면 이 화면으로. */
    private fun askIcon(title: String, done: (HoloLine) -> Unit) {
        kr.inmc.core.gui.ItemInputMenu(ess, viewer, title, submitLabel = "아이콘 줄로", back = { show() }) { stack ->
            done(iconLine(stack))
            show()
        }.show()
    }

    /** 가방의 아이템을 Shift+클릭 — 이 쪽 끝에 아이콘 줄(목록에 더하기, 사본). */
    override fun onPlayerInventoryClick(event: org.bukkit.event.inventory.InventoryClickEvent): Boolean {
        if (!event.isShiftClick) return false
        val stack = event.currentItem?.takeIf { !it.type.isAir }?.clone() ?: return false
        if (current() == null) return true
        val icon = iconLine(stack)
        editPage { p -> p.copy(lines = p.lines + icon) }
        return true
    }

    private fun askActions(label: String, actions: HoloActions, done: (HoloActions) -> Unit) {
        val form = DialogForm("<yellow>$label — 누르기 동작</yellow>")
            .line("<gray>한 줄에 명령 하나. player: 로 시작하면 그 사람이, 아니면 콘솔이 칩니다. {player} · PAPI</gray>")
            .line("<gray>[nextPage] · [prevPage] 는 쪽 넘기기. 칸이 비면 Shift 없는 것 → 아무 클릭 순으로 찾습니다.</gray>")
        for (click in HoloClick.entries) form.text(fieldOf(click), click.label, actions.map[click].orEmpty().joinToString("\n"), 2048, multiline = true)
        ask(form) { v ->
            var out = HoloActions.NONE
            for (click in HoloClick.entries) out = out.with(click, v.text(fieldOf(click)).lines().map { it.trim() }.filter { it.isNotEmpty() })
            // 콘솔 권한이 없으면 콘솔 명령을 새로 넣거나 바꿀 수 없다(지우기·순서 바꾸기는 된다).
            if (!feature.mayAddConsole(viewer, actions.map.values.flatten(), out.map.values.flatten())) return@ask
            done(out)
        }
    }

    private fun fieldOf(click: HoloClick): String = click.id.replace("-", "")

    private fun askSettings(h: Hologram) {
        val form = DialogForm("<yellow>${h.name} — 설정</yellow>")
            .long("range", "보이는 거리(블록, 비우면 기본)", h.viewRange?.toLong(), 1, 256, optional = true)
            .long("update", "글 갱신(초, 0 = 안 함, 비우면 기본)", h.updateSeconds?.toLong(), 0, 86_400, optional = true)
            .long("urange", "갱신 거리(블록 — 이 안에 사람이 있을 때만, 0 = 보이는 거리 전체, 비우면 기본)", h.updateRange?.toLong(), 0, 256, optional = true)
            .long("pages", "자동 쪽 넘김(초, 0 = 안 함, 비우면 기본)", h.pageSeconds?.toLong(), 0, 86_400, optional = true)
            .text("permission", "보려면 필요한 권한(비우면 누구나)", h.permission.orEmpty(), 128)
            .text("condition", "보이기 조건(예: %player_level% >= 10)", h.condition.orEmpty(), 256)
            .choice("billboard", "방향", HologramCodec.BILLBOARDS.map { it to billboardLabel(it) }, h.billboard)
            .decimal("yaw", "고정 방향일 때 각도", h.yaw.toDouble(), -360.0, 360.0)
            .decimal("scale", "크기", h.scale, 0.1, 10.0)
            .text("background", "배경(default · none · #AARRGGBB)", h.background, 16)
            .toggle("shadow", "글 그림자", h.shadow)
            .toggle("see", "벽 너머로 보이기", h.seeThrough)
            .toggle("click", "누르면 쪽 넘기기", h.clickPages)
            .decimal("width", "누르는 너비(0 = 글 길이로)", h.clickWidth, 0.0, 16.0)
            .choice("align", "글 정렬(여러 줄로 감길 때)", Hologram.ALIGNMENTS.map { it to alignmentLabel(it) }, h.alignment)
            .long("linewidth", "감기 너비(이 너비를 넘으면 다음 줄로 — 기본 ${Hologram.DEFAULT_LINE_WIDTH})", h.lineWidth.toLong(), 1, 10_000)
            .long("opacity", "글자 불투명도(0~255, 255 = 불투명 · 아주 낮으면 마크가 불투명으로 그린다)", h.textOpacity.toLong(), 0, 255)
        ask(form) { v ->
            save {
                it.copy(
                    viewRange = v.long("range")?.toInt(),
                    updateSeconds = v.long("update")?.toInt(),
                    updateRange = v.long("urange")?.toInt(),
                    pageSeconds = v.long("pages")?.toInt(),
                    permission = v.text("permission").trim().takeIf { s -> s.isNotEmpty() },
                    condition = v.text("condition").trim().takeIf { s -> s.isNotEmpty() },
                    billboard = v.choice("billboard")?.takeIf { b -> b in HologramCodec.BILLBOARDS } ?: it.billboard,
                    yaw = (v.decimal("yaw") ?: 0.0).toFloat(),
                    scale = v.decimal("scale") ?: 1.0,
                    background = v.text("background").trim().ifEmpty { "default" },
                    shadow = v.bool("shadow"),
                    seeThrough = v.bool("see"),
                    clickPages = v.bool("click"),
                    clickWidth = v.decimal("width") ?: 0.0,
                    alignment = v.choice("align")?.takeIf { a -> a in Hologram.ALIGNMENTS } ?: it.alignment,
                    lineWidth = v.long("linewidth")?.toInt() ?: Hologram.DEFAULT_LINE_WIDTH,
                    textOpacity = v.long("opacity")?.toInt() ?: 255,
                )
            }
            val condition = v.text("condition").trim()
            if (condition.isNotEmpty() && !HoloRules.conditionValid(condition)) ess.messages.send(viewer, "holograms-condition-invalid", Ph.of().value(condition))
        }
    }

    private fun alignmentLabel(id: String): String = when (id) {
        "LEFT" -> "왼쪽"
        "RIGHT" -> "오른쪽"
        else -> "가운데"
    }

    private fun billboardLabel(id: String): String = when (id) {
        "CENTER" -> "늘 나를 봄"
        "VERTICAL" -> "세로축만 돎"
        "HORIZONTAL" -> "가로축만 돎"
        else -> "고정"
    }

    companion object {
        const val SIZE = 54
        const val MAX_LINES = 36
        const val SLOT_ADD_TEXT = 36
        const val SLOT_ADD_ICON = 37
        const val SLOT_PAGE_ACTIONS = 38
        const val SLOT_ADD_PAGE = 39
        const val SLOT_REMOVE_PAGE = 40
        const val SLOT_SETTINGS = 41
        const val SLOT_ENABLED = 42
        const val SLOT_MOVE_HERE = 43
        const val SLOT_TELEPORT = 44
        const val SLOT_PREV = 46
        const val SLOT_NEXT = 47
        const val SLOT_INFO = 48
        const val SLOT_DELETE = 49
    }
}
