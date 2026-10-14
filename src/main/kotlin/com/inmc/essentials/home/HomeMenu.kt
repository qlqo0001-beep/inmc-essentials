package com.inmc.essentials.home

import com.inmc.essentials.Ess
import com.inmc.essentials.gui.Menu
import com.inmc.essentials.util.Ph
import kr.inmc.core.gui.ConfirmMenu
import kr.inmc.core.gui.DialogForm
import kr.inmc.core.gui.Icon
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import java.util.UUID

/**
 * 홈 화면(CMI Homes.Gui) — 홈마다 아이콘, 누르면 이동. 우클릭 = 손에 든 것으로 아이콘(빈손이면 기본으로), Shift+우클릭 = 지우기(확인),
 * Shift+클릭 = 비공개 켜고 끄기. 아래 줄: 침대 홈 · 쪽 · 기본 홈 · 정보 · 여기에 새 홈 · /뒤로 · /사망지점 · 닫기.
 * 관리자가 남의 홈을 보면([ownerId] ≠ 보는 사람) 누르면 관리자가 그 홈으로 간다.
 *
 * 이동은 **`/홈 이름` 을 친 것으로**([asTyped]) — commands 기능의 대기·쿨타임·비용이 명령 이름 `홈` 으로 걸리므로, 화면을 연 명령
 * (`/홈목록`·`homes`)과 무관하게 같은 규칙을 거친다. 고치기(아이콘·비공개·지우기)는 누른 순간의 기록을 다시 읽어 그것을 고친다.
 */
class HomeMenu(
    ess: Ess,
    viewer: Player,
    private val service: HomeService,
    private val ownerId: UUID,
    private val ownerName: String,
    private var page: Int = 0,
) : Menu(ess, viewer, SIZE, title(service, viewer, ownerId, ownerName)) {

    private val self: Boolean get() = viewer.uniqueId == ownerId

    private val canEdit: Boolean get() = self || viewer.hasPermission(HomesFeature.OTHERS)

    override fun draw() {
        clear()
        val homes = HomeRules.sorted(service.store.of(ownerId))
        val layout = HomeRules.arrange(homes) { it.slot }
        val pages = ((layout.keys.maxOrNull() ?: 0) / PER_PAGE) + 1
        page = page.coerceIn(0, pages - 1)
        for ((index, home) in layout) {
            if (index / PER_PAGE != page) continue
            set(index % PER_PAGE, icon(home)) { event -> click(event, home) }
        }

        for (slot in PER_PAGE until SIZE) set(slot, Icon.FILLER)
        service.bedHome(ownerId)?.let { bed ->
            set(SLOT_BED, Icon.of(Material.RED_BED, "<aqua>침대 홈</aqua> <white>${bed.name}</white>", "<gray>${bed.describe()}</gray>", "", "<yellow>▶ 클릭: 이동</yellow>")) {
                travel(bed)
            }
        }
        service.defaultHome(ownerId)?.let { home ->
            set(SLOT_DEFAULT, Icon.of(Material.OAK_DOOR, "<aqua>기본 홈</aqua> <white>${home.name}</white>", "<gray>${home.describe()}</gray>", "", "<yellow>▶ 클릭: 이동</yellow>")) {
                travel(home)
            }
        }
        if (page > 0) set(SLOT_PREV, Icon.prevPage()) { page--; refresh() }
        if (page < pages - 1) set(SLOT_NEXT, Icon.nextPage()) { page++; refresh() }

        val limit = if (self) service.limitOf(viewer) else null
        val counted = service.counted(ownerId)
        set(SLOT_INFO, Icon.of(Material.BOOK, "<gold>홈 ${counted}/${service.limitText(limit)}</gold>",
            "<gray>클릭: 이동</gray>", "<gray>우클릭: 손에 든 것으로 아이콘(빈손 = 기본)</gray>",
            "<gray>Shift+클릭: 비공개 켜고 끄기</gray>", "<gray>Shift+우클릭: 지우기</gray>", "",
            "<gray>/홈설정 [이름] [칸] [재질] [비공개]</gray>", "<dark_gray>쪽 ${page + 1}/$pages</dark_gray>"))
        if (self) {
            set(SLOT_NEW, Icon.of(Material.LIME_DYE, "<green>여기에 새 홈</green>", "<gray>지금 선 자리에 홈을 만듭니다.</gray>", "", "<yellow>▶ 클릭: 이름 적기</yellow>")) {
                ask(DialogForm("<yellow>새 홈</yellow>").text("name", "홈 이름", "", service.settings.nameMaxLength)) { v ->
                    val name = v.text("name").trim().ifEmpty { service.settings.defaultName }
                    service.create(viewer, ownerId, ownerName, SetRequest(name = name), viewer.location, "홈설정 $name")
                }
            }
        }
        if (self && commandExists(CMD_BACK)) {
            set(SLOT_BACK_CMD, Icon.of(Material.BONE, "<aqua>뒤로</aqua>", "<gray>마지막으로 순간이동하기 전 자리</gray>", "", "<yellow>▶ 클릭: /$CMD_BACK</yellow>")) {
                viewer.closeInventory()
                viewer.performCommand(CMD_BACK)
            }
        }
        if (self && commandExists(CMD_DEATH)) {
            set(SLOT_DEATH_CMD, Icon.of(Material.TOTEM_OF_UNDYING, "<aqua>사망 지점</aqua>", "<gray>마지막으로 죽은 자리</gray>", "", "<yellow>▶ 클릭: /$CMD_DEATH</yellow>")) {
                viewer.closeInventory()
                viewer.performCommand(CMD_DEATH)
            }
        }
        set(SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    private fun icon(home: Home): org.bukkit.inventory.ItemStack {
        val lore = buildList {
            add("<gray>${home.describe()}</gray>")
            if (home.bed) add("<aqua>침대로 만든 홈</aqua>")
            add("<gray>공개: </gray>" + if (home.private) "<red>비공개</red>" else "<green>공개</green>")
            home.slot?.let { add("<gray>칸: <white>$it</white></gray>") }
            if (Bukkit.getWorld(home.world) == null) add("<red>월드가 없습니다</red>")
            add("")
            add("<yellow>▶ 클릭: 이동</yellow>")
            if (canEdit) {
                add("<yellow>▶ 우클릭: 손에 든 것으로 아이콘</yellow>")
                add("<yellow>▶ Shift+클릭: 비공개 켜고 끄기</yellow>")
                add("<red>▶ Shift+우클릭: 지우기</red>")
            }
        }
        val stack = Icon.of(service.iconOf(home), (if (home.private) "<gray>" else "<white>") + home.name, lore)
        return stack
    }

    private fun click(event: InventoryClickEvent, shown: Home) {
        val ph = Ph.of().raw("{홈}", shown.name)
        // 화면을 그린 뒤 지워졌거나(다른 창·명령) 옮겨졌을 수 있다 — 지금 것을 고친다.
        val home = service.store.get(ownerId, shown.name) ?: return ess.messages.send(viewer, "homes-not-found", ph).also { refresh() }
        when {
            event.isShiftClick && event.isRightClick && canEdit -> ConfirmMenu(ess, "<red>홈 ${home.name} 을(를) 지울까요?</red>", listOf("<gray>${home.describe()}</gray>"),
                onConfirm = {
                    if (canEdit && service.store.remove(ownerId, home.name)) ess.messages.send(viewer, "homes-removed", ph)
                    show()
                }, onCancel = { show() }).open(viewer)
            event.isShiftClick && canEdit -> {
                val changed = home.copy(private = !home.private)
                service.store.put(changed)
                ess.messages.send(viewer, if (changed.private) "homes-private-on" else "homes-private-off", ph)
                refresh()
            }
            event.isRightClick && canEdit -> {
                val hand = viewer.inventory.itemInMainHand.type
                if (hand.isAir) {
                    service.store.put(home.copy(icon = null))
                    ess.messages.send(viewer, "homes-icon-reset", ph)
                } else {
                    service.store.put(home.copy(icon = hand.name))
                    ess.messages.send(viewer, "homes-icon-set", ph.value(hand.name.lowercase()))
                }
                refresh()
            }
            else -> travel(home)
        }
    }

    /** `/홈 이름 [주인]` 을 친 것으로 — 명령 관문(대기·쿨타임·비용)과 권한 검사를 명령과 똑같이 거친다. */
    private fun travel(home: Home) {
        service.accessProblem(viewer, home)?.let { key -> return ess.messages.send(viewer, key, Ph.of().raw("{홈}", home.name)) }
        viewer.closeInventory()
        asTyped(viewer, "$CMD_HOME ${home.name}" + if (self) "" else " $ownerName")
    }

    private fun commandExists(name: String): Boolean = Bukkit.getCommandMap().getCommand(name) != null

    companion object {
        const val SIZE = 54
        const val PER_PAGE = 45
        const val SLOT_BED = 45
        const val SLOT_PREV = 46
        const val SLOT_NEXT = 47
        const val SLOT_DEFAULT = 48
        const val SLOT_INFO = 49
        const val SLOT_NEW = 50
        const val SLOT_BACK_CMD = 51
        const val SLOT_DEATH_CMD = 52
        const val SLOT_CLOSE = 53

        /** 이동 명령 — 대기·쿨타임이 이 이름에 걸린다(계획 4절 표). */
        const val CMD_HOME = "홈"

        /** teleport 기능의 명령(4절 표) — 있을 때만 버튼을 보인다. */
        const val CMD_BACK = "뒤로"
        const val CMD_DEATH = "사망지점"

        /**
         * 명령 한 줄을 **친 것으로** 돌린다 — `PlayerCommandPreprocessEvent` 를 쏘고 취소되지 않았을 때만(바뀐 글로) 실행한다(commands 기능의
         * `CommandRunner.dispatch` 와 같은 모양 — 관문의 대기는 사건을 취소하고 대기 뒤에 직접 실행하므로 친 것과 같다).
         * `player.chat("/…")` 는 쓰지 않는다: 클라이언트 채팅 설정이 '숨김' 인 사람에게는 Paper 가 명령을 가르기 전에 돌아가 조용히 버려진다(2026-10-14).
         */
        fun asTyped(player: Player, line: String) {
            val event = PlayerCommandPreprocessEvent(player, "/$line")
            Bukkit.getPluginManager().callEvent(event)
            if (!event.isCancelled) player.performCommand(event.message.removePrefix("/"))
        }

        private fun title(service: HomeService, viewer: Player, ownerId: UUID, ownerName: String): String {
            val self = viewer.uniqueId == ownerId
            val limit = if (self) service.limitText(service.limitOf(viewer)) else "-"
            return if (self) "<dark_gray>내 홈 ${service.counted(ownerId)}/$limit</dark_gray>" else "<dark_gray>${ownerName}의 홈</dark_gray>"
        }
    }
}
