package com.inmc.essentials.kit

import com.inmc.essentials.Ess
import com.inmc.essentials.gui.Menu
import com.inmc.essentials.util.Ph
import kr.inmc.core.gui.ConfirmMenu
import kr.inmc.core.gui.CurrencyPick
import kr.inmc.core.gui.DialogForm
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.gui.RewardBundleEditor
import kr.inmc.core.reward.GiveMode
import kr.inmc.core.reward.RewardBundle
import kr.inmc.core.reward.RewardEntry
import kr.inmc.core.util.Durations
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack

/**
 * `/킷편집`(kiteditor) — 킷 목록 → 킷 하나의 설정 → 묶음(보상)은 core [RewardBundleEditor](urb 보상 화면의 쓰는 법: 끌어 넣고 확인 · 제거 모드 ·
 * 상세에서 확률·수량·참조/스냅샷·돈·명령·공지). 카탈로그 cmd4:cmd-kiteditor · fix:cfg-kit-groups-conditions.
 * 바뀔 때마다 저장(DB `ess_kits`). 값 입력은 입력창(Dialog).
 */
class KitAdminMenu(ess: Ess, viewer: Player, private val feature: KitsFeature, private var page: Int = 0) :
    Menu(ess, viewer, 54, "<dark_gray>킷 편집</dark_gray>") {

    override fun draw() {
        clear()
        val kits = feature.store.all()
        page = Paging.clamp(page, kits.size + 1)
        for ((i, kit) in Paging.slice(kits, page).withIndex()) {
            val icon = kit.icon?.let { ess.itemResolver.icon(it).stack.clone() } ?: ItemStack(Material.CHEST)
            set(i, Icon.relabel(Icon.hideExtras(icon), kit.name, listOf(
                "<gray>id: <white>${kit.id}</white></gray>",
                "<gray>묶음 ${kit.pools.size}개 · 보상 ${kit.pools.sumOf { it.entries.size }}개</gray>",
                "<gray>쿨타임 ${if (kit.cooldown > 0) Durations.formatShort(kit.cooldown) else "없음"} · 횟수 ${if (kit.uses > 0) kit.uses else "무제한"}</gray>",
                if (kit.firstJoin) "<aqua>첫 접속 킷</aqua>" else "",
                if (kit.group.isNotEmpty()) "<gray>그룹 ${kit.group} · 무게 ${kit.weight}</gray>" else "",
                "", "<yellow>▶ 클릭: 고치기</yellow>",
            ))) { KitSettingsMenu(ess, viewer, feature, kit) { KitAdminMenu(ess, viewer, feature, page).show() }.show() }
        }
        for (slot in Paging.PER_PAGE until 54) set(slot, Icon.FILLER)
        pager(page, kits.size) { page = it; refresh() }
        set(SLOT_NEW, Icon.of(Material.LIME_DYE, "<green>새 킷</green>", "<gray>id(영문 소문자·숫자·_-)를 적습니다.</gray>", "<gray>지금 내 가방을 1번 묶음으로 담을 수도 있습니다.</gray>")) {
            ask(DialogForm("<yellow>새 킷</yellow>").text("id", "id (영문 소문자·숫자·_-)", "", 32).text("name", "보이는 이름(MiniMessage)", "").toggle("copy", "내 가방을 1번 묶음으로", false)) { v ->
                val id = Kit.sanitize(v.text("id")) ?: return@ask ess.messages.send(viewer, "kits-bad-id", Ph.of().value(v.text("id")))
                if (feature.store.get(id) != null) return@ask ess.messages.send(viewer, "kits-exists", Ph.of().value(id))
                val kit = Kit(id, name = v.text("name").ifBlank { id })
                if (v.bool("copy")) KitEditing.copyInventory(ess, viewer, kit.pools[0])
                feature.store.save(kit)
                ess.messages.send(viewer, "kits-created", Ph.of().value(id))
                KitSettingsMenu(ess, viewer, feature, kit) { KitAdminMenu(ess, viewer, feature, page).show() }.show()
            }
        }
        set(Paging.SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    companion object {
        private const val SLOT_NEW = 49
    }
}

/** 킷 하나의 설정. */
class KitSettingsMenu(ess: Ess, viewer: Player, private val feature: KitsFeature, private val kit: Kit, override val back: (() -> Unit)?) :
    Menu(ess, viewer, 54, "<dark_gray>킷 · </dark_gray>" + kit.name) {

    private fun save() = feature.store.save(kit)

    override fun draw() {
        clear()
        for (slot in 0 until 54) set(slot, Icon.FILLER)
        set(10, valueIcon(Material.NAME_TAG, "보이는 이름", kit.name)) {
            ask(DialogForm("<yellow>보이는 이름</yellow>").text("v", "이름(MiniMessage)", kit.name, 128)) { v -> kit.name = v.text("v").ifBlank { kit.id }; save() }
        }
        val iconStack = kit.icon?.let { ess.itemResolver.icon(it).stack.clone() } ?: ItemStack(Material.CHEST)
        set(11, Icon.relabel(Icon.hideExtras(iconStack), "<yellow>아이콘</yellow>", listOf("<gray>올린 아이템을 아이콘으로</gray>", "",
            "<gray>클릭: 아이템 올리기 · 우클릭: 기본 상자</gray>"))) { event ->
            if (event.isRightClick) {
                kit.icon = null
                save(); refresh()
                return@set
            }
            // 손에 든 것 대신 아이템 올리기(사용자 요청 2026-10-14).
            kr.inmc.core.gui.ItemInputMenu(ess, viewer, "키트 아이콘 — 아이템 올리기", submitLabel = "아이콘으로", back = { show() }) { stack ->
                kit.icon = ess.itemResolver.capture(stack)
                save()
                show()
            }.show()
        }
        set(12, Icon.of(Material.WRITABLE_BOOK, "<yellow>설명</yellow>", kit.description.ifEmpty { listOf("<gray>-</gray>") } + listOf("", "<gray>클릭해서 바꾸기(한 줄에 하나)</gray>"))) {
            ask(DialogForm("<yellow>설명</yellow>").text("v", "설명(한 줄에 하나, MiniMessage)", kit.description.joinToString("\n"), 2048, multiline = true)) { v ->
                kit.description = v.text("v").split('\n').map { it.trimEnd() }.filter { it.isNotEmpty() }.toMutableList(); save()
            }
        }
        set(13, toggleIcon("권한 필요", kit.requirePermission, "<gray>켜짐: ${kit.permission} 가 있어야 받음</gray>", "<gray>꺼짐: inmcessentials.kits.use 만</gray>")) {
            kit.requirePermission = !kit.requirePermission; save(); refresh()
        }
        set(14, Icon.of(Material.TRIPWIRE_HOOK, "<yellow>조건 권한</yellow>", kit.conditions.map { "<white>$it</white>" }.ifEmpty { listOf("<gray>없음</gray>") } + listOf("", "<gray>클릭해서 바꾸기(한 줄에 하나)</gray>"))) {
            ask(DialogForm("<yellow>조건 권한(CMI Conditions)</yellow>").text("v", "권한(한 줄에 하나)", kit.conditions.joinToString("\n"), 2048, multiline = true)) { v ->
                kit.conditions = v.text("v").split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList(); save()
            }
        }
        val random = feature.settings.groupMode == KitsSettings.GroupMode.RANDOM
        set(15, valueIcon(Material.ITEM_FRAME, "그룹 · 무게", if (kit.group.isEmpty()) "없음" else "${kit.group} · ${kit.weight}",
            if (random) "<gray>random: /킷 [그룹] 이 무게로 하나를 뽑고 그룹 전체가 쿨타임</gray>"
            else "<gray>priority(CMI): 받을 수 있는 것 중 무게가 가장 높은 것만 — 낮은 것은 막힘</gray>",
            "<gray>밀려도 보이기(CMI ShowDespiteWeight): <white>${if (kit.showDespiteWeight) "켜짐" else "꺼짐"}</white> — 우클릭</gray>")) { event ->
            if (event.click == ClickType.RIGHT) { kit.showDespiteWeight = !kit.showDespiteWeight; save(); refresh(); return@set }
            ask(DialogForm("<yellow>그룹 · 무게(CMI Group/Weight)</yellow>").text("g", "그룹(비우면 없음)", kit.group, 32).long("w", "무게(높을수록 우선)", kit.weight.toLong(), 1, 1_000_000)) { v ->
                kit.group = v.text("g").trim(); kit.weight = (v.long("w") ?: 1L).toInt(); save()
            }
        }
        set(16, toggleIcon("목록에서 숨김", kit.hidden, "<gray>숨겨도 /킷 ${kit.id} 로 받을 수 있습니다</gray>")) { kit.hidden = !kit.hidden; save(); refresh() }

        set(19, valueIcon(Material.CLOCK, "쿨타임", if (kit.cooldown > 0) Durations.formatShort(kit.cooldown) else "없음")) {
            ask(DialogForm("<yellow>쿨타임</yellow>").text("v", "예: 1d 12h · 30m · 0(없음)", Durations.format(kit.cooldown), 32)) { v ->
                kit.cooldown = Durations.parse(v.text("v"), kit.cooldown); save()
            }
        }
        set(20, valueIcon(Material.STONE_PRESSURE_PLATE, "받을 수 있는 횟수", if (kit.uses > 0) "${kit.uses}" else "무제한", "<gray>0 = 무제한, 1 = 한 번</gray>")) {
            ask(DialogForm("<yellow>받을 수 있는 횟수</yellow>").long("v", "횟수(0 = 무제한)", kit.uses.toLong(), 0, 1_000_000)) { v -> kit.uses = (v.long("v") ?: 0L).toInt(); save() }
        }
        set(21, valueIcon(Material.GOLD_INGOT, "돈 비용", if (kit.money > 0) ess.economy.format(kit.money, kit.currency.ifEmpty { null }) else "없음",
            CurrencyPick.line(ess.economy, kit.currency), "<gray>우클릭: 화폐 고르기</gray>")) { event ->
            if (event.click == ClickType.RIGHT && ess.economy.multiCurrency) {
                CurrencyPick.open(ess, viewer, kit.currency, { show() }) { picked -> kit.currency = picked; save(); show() }
            } else {
                ask(DialogForm("<yellow>돈 비용</yellow>").decimal("v", "금액(0 = 없음)", kit.money, 0.0, 1e15)) { v -> kit.money = v.decimal("v") ?: 0.0; save() }
            }
        }
        set(22, valueIcon(Material.EXPERIENCE_BOTTLE, "경험치 비용", if (kit.exp > 0) "${kit.exp} 점" else "없음", "<gray>경험치 점수(레벨이 아님)</gray>")) {
            ask(DialogForm("<yellow>경험치 비용</yellow>").long("v", "점수(0 = 없음)", kit.exp.toLong(), 0, 100_000_000)) { v -> kit.exp = (v.long("v") ?: 0L).toInt(); save() }
        }
        set(23, toggleIcon("미리보기 허용", kit.preview, "<gray>받을 권한이 있거나 ${kit.previewPermission} 가 있으면 미리보기</gray>")) { kit.preview = !kit.preview; save(); refresh() }
        set(24, toggleIcon("첫 접속 킷", kit.firstJoin, "<gray>처음 들어온 사람에게 한 번(검사·비용 없이)</gray>")) { kit.firstJoin = !kit.firstJoin; save(); refresh() }
        set(25, valueIcon(Material.PAINTING, "목록 칸", if (kit.slot >= 0) "${kit.slot}" else "차례대로", "<gray>/킷 화면의 고정 칸(0~44, -1 = 차례대로)</gray>")) {
            ask(DialogForm("<yellow>목록 칸</yellow>").long("v", "칸(-1 = 차례대로)", kit.slot.toLong(), -1, 44)) { v -> kit.slot = (v.long("v") ?: -1L).toInt(); save() }
        }

        // 묶음
        for ((index, pool) in kit.pools.withIndex()) {
            if (index >= POOL_SLOTS.size) break
            set(POOL_SLOTS[index], Icon.of(if (index == 0) Material.CHEST else Material.ENDER_CHEST, "<yellow>묶음 ${index + 1}</yellow>", listOf(
                "<gray>${KitText.mode(pool.mode, pool.rollCount)} · 보상 ${pool.entries.size}개</gray>",
                "", "<yellow>클릭: 보상 고치기</yellow>", "<gray>Shift+우클릭: 이 묶음 지우기</gray>",
            ))) { event ->
                if (event.click == ClickType.SHIFT_RIGHT) {
                    if (kit.pools.size <= 1) return@set ess.messages.send(viewer, "kits-last-pool")
                    ConfirmMenu(ess, "<red>묶음 ${index + 1} 을(를) 지울까요?</red>", listOf("<gray>보상 ${pool.entries.size}개가 사라집니다</gray>"), onConfirm = {
                        kit.pools.remove(pool); save(); show()
                    }, onCancel = { show() }).open(viewer)
                } else editPool(pool, index)
            }
        }
        if (kit.pools.size < POOL_SLOTS.size) {
            set(POOL_SLOTS[kit.pools.size], Icon.of(Material.LIME_DYE, "<green>묶음 더하기</green>", "<gray>뒤 묶음은 하나 뽑기(가중치)로 시작합니다.</gray>")) {
                kit.pools += RewardBundle(GiveMode.ROLL_ONE); save(); refresh()
            }
        }

        back?.let { go -> set(45, Icon.back()) { go() } }
        set(46, Icon.of(Material.ANVIL, "<yellow>id 바꾸기</yellow>", "<gray>지금: <white>${kit.id}</white></gray>",
            "<gray>사용 기록(쿨타임·횟수·1회용)도 새 id 로 옮깁니다</gray>", "<gray>권한 노드가 ${Kit.PERMISSION_PREFIX}[새 id] 로 바뀝니다</gray>")) {
            ask(DialogForm("<yellow>id 바꾸기</yellow>").text("v", "새 id (영문 소문자·숫자·_-)", kit.id, 32)) { v ->
                val id = Kit.sanitize(v.text("v")) ?: return@ask ess.messages.send(viewer, "kits-bad-id", Ph.of().value(v.text("v")))
                if (id == kit.id) return@ask
                if (feature.store.get(id) != null) return@ask ess.messages.send(viewer, "kits-exists", Ph.of().value(id))
                val moved = feature.store.rename(kit, id)
                ess.messages.send(viewer, "kits-renamed", Ph.of().value(kit.id).raw("{새id}", id).raw("{권한}", moved.permission))
                KitSettingsMenu(ess, viewer, feature, moved, back).show()
            }
        }
        set(47, Icon.of(Material.HOPPER, "<yellow>내 가방을 1번 묶음에 담기</yellow>", "<gray>CMI 킷 편집기의 '인벤토리 복제'</gray>", "<gray>가방·갑옷·왼손을 그대로(개수 그대로, 확률 100%)</gray>")) {
            val n = KitEditing.copyInventory(ess, viewer, kit.pools[0]); save()
            ess.messages.send(viewer, "kits-copied", Ph.of().count(n))
            refresh()
        }
        set(49, Icon.of(Material.EMERALD, "<green>시험 받기</green>", "<gray>검사·비용·기록 없이 나에게 줍니다</gray>")) {
            viewer.closeInventory(); feature.service.claim(viewer, kit, free = true, record = false)
        }
        set(51, Icon.of(Material.LAVA_BUCKET, "<red>킷 지우기</red>", "<gray>사용 기록은 남습니다</gray>")) {
            ConfirmMenu(ess, "<red>킷 ${kit.id} 을(를) 지울까요?</red>", onConfirm = {
                feature.store.remove(kit.id)
                ess.messages.send(viewer, "kits-removed", Ph.of().value(kit.id))
                back?.invoke() ?: viewer.closeInventory()
            }, onCancel = { show() }).open(viewer)
        }
        set(53, Icon.close()) { viewer.closeInventory() }
    }

    private fun editPool(pool: RewardBundle, index: Int) {
        object : RewardBundleEditor(ess, viewer, "<dark_gray>${kit.id} · 묶음 ${index + 1}</dark_gray>", pool, ess.itemResolver, { save() }, { show() }) {
            override val commandHints: List<String> = listOf(
                "<gray>한 줄에 하나, / 없이. 콘솔이 실행합니다.</gray>",
                "<gray>받는 사람: {player} · 킷 이름: {KITNAME} · 월드: {WORLDNAME}</gray>",
            )
        }.show()
    }

    companion object {
        private val POOL_SLOTS = listOf(28, 29, 30, 31, 32, 33, 34)
    }
}

object KitEditing {
    /** [player] 의 가방·갑옷·왼손을 [pool] 에 담는다(참조로 잡을 수 있으면 참조, 아니면 스냅샷). 담은 수. */
    fun copyInventory(ess: Ess, player: Player, pool: RewardBundle): Int {
        var n = 0
        for (stack in player.inventory.contents) {
            if (stack == null || stack.type.isAir) continue
            pool.entries += RewardEntry(item = ess.itemResolver.capture(stack), chance = 100.0, minAmount = stack.amount, maxAmount = stack.amount)
            n++
        }
        return n
    }
}
