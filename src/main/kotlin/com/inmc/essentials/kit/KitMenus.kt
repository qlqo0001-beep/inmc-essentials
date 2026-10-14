package com.inmc.essentials.kit

import com.inmc.essentials.Ess
import com.inmc.essentials.gui.Menu
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.reward.GiveMode
import kr.inmc.core.util.Durations
import kr.inmc.core.util.Numbers
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import java.text.SimpleDateFormat
import java.util.Date

/**
 * `/킷` 목록 화면(CMI Kits.GUI) — 받을 수 있는 킷마다 아이콘과 상태(받기 · 쿨타임 남음 · 횟수 다 씀 · 권한 없음 · 비용). 클릭 = 받기, 우클릭 = 미리보기.
 * 고정 칸([Kit.slot])이 있으면 그 칸, 없으면 차례대로. 숨긴 킷은 안 보인다. 설정 show-locked 면 권한 없는 킷도 잠긴 모습으로.
 */
class KitListMenu(ess: Ess, viewer: Player, private val feature: KitsFeature, private var page: Int = 0) :
    Menu(ess, viewer, 54, "<dark_gray>킷</dark_gray>") {

    override fun draw() {
        clear()
        val service = feature.service
        val shown = feature.store.all().filter {
            !it.hidden && service.listed(viewer, it) && (feature.settings.showLocked || service.canAccess(viewer, it) || service.canPreview(viewer, it))
        }
        val fixed = shown.filter { it.slot in 0 until Paging.PER_PAGE }
        val flowing = shown.filter { it.slot !in 0 until Paging.PER_PAGE }
        val placed = LinkedHashMap<Int, Kit>()
        for (kit in fixed) placed.putIfAbsent(kit.slot, kit)
        var cursor = 0
        val pages = ArrayList<LinkedHashMap<Int, Kit>>()
        pages += placed
        for (kit in flowing + fixed.filter { placed[it.slot] !== it }) {
            while (true) {
                if (cursor >= Paging.PER_PAGE) { pages += LinkedHashMap(); cursor = 0 }
                val current = pages.last()
                if (current.containsKey(cursor)) { cursor++; continue }
                current[cursor++] = kit
                break
            }
        }
        page = page.coerceIn(0, pages.size - 1)
        for ((slot, kit) in pages[page]) set(slot, icon(kit)) { event -> click(kit, event.click) }
        if (feature.settings.fillEmpty) for (slot in 0 until Paging.PER_PAGE) if (pages[page][slot] == null) set(slot, Icon.FILLER)
        for (slot in Paging.PER_PAGE until 54) set(slot, Icon.FILLER)
        if (page > 0) set(Paging.SLOT_PREV, Icon.prevPage()) { page--; refresh() }
        if (page < pages.size - 1) set(Paging.SLOT_NEXT, Icon.nextPage()) { page++; refresh() }
        set(49, Icon.of(Material.BOOK, "<gold>킷</gold>", "<gray>클릭: 받기</gray>", "<gray>우클릭: 미리보기</gray>", "<dark_gray>쪽 ${page + 1}/${pages.size}</dark_gray>"))
        set(Paging.SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    private fun click(kit: Kit, click: ClickType) {
        val service = feature.service
        if (click.isRightClick) {
            if (!service.canPreview(viewer, kit)) return ess.messages.send(viewer, "kits-preview-denied")
            return KitPreviewMenu(ess, viewer, feature, kit) { KitListMenu(ess, viewer, feature, page).show() }.show()
        }
        val verdict = service.verdict(viewer, kit)
        if (verdict.denied != null) return service.tellDenied(viewer, kit, verdict)
        viewer.closeInventory()
        service.claim(viewer, kit)
    }

    private fun icon(kit: Kit): ItemStack {
        val service = feature.service
        val base = kit.icon?.let { ess.itemResolver.icon(it).stack.clone() } ?: ItemStack(Material.CHEST)
        val verdict = service.verdict(viewer, kit)
        val usage = feature.store.usage(viewer.uniqueId, kit.id)
        val lore = ArrayList<String>()
        lore += kit.description
        if (kit.description.isNotEmpty()) lore += ""
        lore += KitText.costLines(ess, kit)
        if (kit.cooldown > 0) lore += "<gray>쿨타임: <white>${Durations.formatShort(kit.cooldown)}</white></gray>"
        if (kit.uses > 0) lore += "<gray>받은 횟수: <white>${usage?.uses ?: 0}/${kit.uses}</white></gray>"
        usage?.takeIf { it.lastUsed > 0 }?.let { lore += "<dark_gray>마지막: ${SimpleDateFormat(feature.settings.dateFormat).format(Date(it.lastUsed))}</dark_gray>" }
        lore += ""
        lore += when (verdict.denied) {
            null -> "<green>▶ 클릭: 받기</green>"
            KitService.Denied.COOLDOWN -> "<red>⏳ ${Durations.formatShort(verdict.left)} 남음</red>"
            KitService.Denied.USED_UP -> "<red>✖ 횟수를 다 썼습니다</red>"
            KitService.Denied.PERMISSION -> "<red>🔒 권한이 없습니다</red>"
            KitService.Denied.SUPERSEDED -> "<red>✖ 같은 그룹의 더 높은 킷을 받을 수 있습니다</red>"
            KitService.Denied.CONDITION -> "<red>🔒 조건: ${verdict.missing}</red>"
            KitService.Denied.MONEY -> "<red>✖ 돈이 모자랍니다</red>"
            KitService.Denied.EXP -> "<red>✖ 경험치가 모자랍니다</red>"
            KitService.Denied.EMPTY -> "<red>✖ 빈 킷</red>"
            KitService.Denied.BUSY -> "<gray>받는 중…</gray>"
        }
        if (service.canPreview(viewer, kit)) lore += "<gray>우클릭: 미리보기</gray>"
        val material = if (verdict.denied == KitService.Denied.PERMISSION && kit.icon == null) Material.BARRIER else base.type
        val stack = if (material != base.type) ItemStack(material) else base
        return Icon.relabel(Icon.hideExtras(stack), kit.name, lore).also { it.amount = 1 }
    }
}

/**
 * 킷 미리보기(CMI KitPreview) — 묶음마다 보상(확률·개수)과 아래 줄의 쿨타임·횟수·돈·경험치·설명·뒤로(CMI Kits.Buttons).
 * 아이콘은 보여 주기용 사본 — 클릭은 core Menu 가 막는다.
 */
class KitPreviewMenu(ess: Ess, viewer: Player, private val feature: KitsFeature, private val kit: Kit, override val back: (() -> Unit)?) :
    Menu(ess, viewer, 54, "<dark_gray>미리보기 · </dark_gray>" + kit.name) {

    override fun draw() {
        clear()
        var slot = 0
        for ((index, pool) in kit.pools.withIndex()) {
            if (slot >= Paging.PER_PAGE) break
            if (kit.pools.size > 1) {
                set(slot++, Icon.of(Material.PAPER, "<yellow>묶음 ${index + 1}</yellow>", "<gray>${KitText.mode(pool.mode, pool.rollCount)}</gray>"))
            }
            val total = pool.entries.filter { it.chance > 0 }.sumOf { it.chance }
            for (entry in pool.entries) {
                if (slot >= Paging.PER_PAGE) break
                if (entry.isEmpty() || entry.chance <= 0.0) continue
                val stored = entry.item
                val icon = if (stored != null && entry.giveItem) ess.itemResolver.icon(stored).stack.clone() else ItemStack(if (entry.money > 0) Material.GOLD_INGOT else Material.COMMAND_BLOCK)
                val chance = when (pool.mode) {
                    GiveMode.ALL -> Numbers.chance(entry.chance) + "%"
                    else -> if (total > 0) Numbers.chance(entry.chance * 100.0 / total) + "%" else "-"
                }
                val lore = buildList {
                    add("<gray>개수: <white>" + (if (entry.minAmount == entry.maxAmount) "${entry.minAmount}" else "${entry.minAmount}~${entry.maxAmount}") + "</white></gray>")
                    if (pool.mode != GiveMode.ALL || entry.chance < 100.0) add("<gray>확률: <white>$chance</white></gray>")
                    if (entry.money > 0) add("<gray>돈: <white>${entry.moneyLabel()}</white></gray>")
                    if (entry.commands.isNotEmpty()) add("<gray>명령 ${entry.commands.size}개</gray>")
                }
                set(slot++, Icon.annotate(icon, null, lore).also { it.amount = entry.minAmount.coerceIn(1, it.maxStackSize) })
            }
        }
        for (s in Paging.PER_PAGE until 54) set(s, Icon.FILLER)
        val usage = feature.store.usage(viewer.uniqueId, kit.id)
        val left = KitRules.cooldownLeft(kit, usage, System.currentTimeMillis())
        set(46, Icon.of(Material.CLOCK, "<yellow>쿨타임</yellow>", if (kit.cooldown <= 0) "<gray>없음</gray>" else "<gray>${Durations.formatShort(kit.cooldown)}" + (if (left > 0) " · ${Durations.formatShort(left)} 남음" else "") + "</gray>"))
        set(47, Icon.of(Material.STONE_PRESSURE_PLATE, "<yellow>받은 횟수</yellow>", if (kit.uses <= 0) "<gray>무제한</gray>" else "<gray>${usage?.uses ?: 0}/${kit.uses}</gray>"))
        set(48, Icon.of(Material.GOLD_INGOT, "<yellow>돈</yellow>", if (kit.money <= 0) "<gray>없음</gray>" else "<gray>${ess.economy.format(kit.money, kit.currency.ifEmpty { null })}</gray>"))
        set(49, Icon.of(Material.EXPERIENCE_BOTTLE, "<yellow>경험치</yellow>", if (kit.exp <= 0) "<gray>없음</gray>" else "<gray>${kit.exp} 점</gray>"))
        set(50, Icon.of(Material.GREEN_WOOL, "<yellow>설명</yellow>", kit.description.ifEmpty { listOf("<gray>-</gray>") }))
        if (feature.service.verdict(viewer, kit).denied == null) {
            set(52, Icon.of(Material.LIME_DYE, "<green>▶ 받기</green>")) { viewer.closeInventory(); feature.service.claim(viewer, kit) }
        }
        back?.let { go -> set(45, Icon.back()) { go() } }
        set(53, Icon.close()) { viewer.closeInventory() }
    }
}

/** 화면·채팅에 같이 쓰는 글. */
object KitText {
    fun costLines(ess: Ess, kit: Kit): List<String> = buildList {
        if (kit.money > 0) add("<gray>비용: <gold>${ess.economy.format(kit.money, kit.currency.ifEmpty { null })}</gold></gray>")
        if (kit.exp > 0) add("<gray>경험치 비용: <green>${kit.exp} 점</green></gray>")
    }

    fun mode(mode: GiveMode, count: Int): String = when (mode) {
        GiveMode.ALL -> "전부(각자 확률)"
        GiveMode.ROLL_ONE -> "하나 뽑기(가중치)"
        GiveMode.ROLL_N -> "${count}개 뽑기(가중치)"
    }
}
