package com.inmc.essentials.admininfo

import com.inmc.essentials.Ess
import com.inmc.essentials.gui.Menu
import com.inmc.essentials.util.Ph
import kr.inmc.core.gui.DialogForm
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.gui.RewardBundleEditor
import kr.inmc.core.util.Durations
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * `/플레이보상` — [target] 의 보상 목록(CMI prewards 화면). 반복 보상은 다음까지 남은 시간과 쌓인 수, 한 번 보상은 받은 것·받을 수 있는 것과
 * 아직 안 닿은 것 중 가까운 `playtime-rewards.one-time-shown` 개. 남의 화면은 보기만.
 */
internal class PlaytimeRewardsView(
    ess: Ess,
    private val feature: AdminInfoFeature,
    viewer: Player,
    private val target: Player,
) : Menu(ess, viewer, 54, "<dark_gray>플레이 시간 보상" + (if (viewer == target) "" else " · ${target.name}") + "</dark_gray>") {

    private var page = 0

    override fun draw() {
        clear()
        val service = feature.prewards
        val mine = viewer == target
        val ticks = feature.playtime.ticks(target.uniqueId) ?: 0L
        val defs = service.all().filter { it.enabled && service.allowed(target, it) }
        val shown = mutableListOf<PlaytimeReward>()
        var upcoming = 0
        for (def in defs) {
            if (def.repeat) { shown += def; continue }
            val claimed = (service.stateOrNull(target.uniqueId, def.id)?.claimed ?: 0) > 0
            when {
                claimed || ticks >= def.periodTicks -> shown += def
                upcoming < feature.settings.rewardsOneTimeShown -> { shown += def; upcoming++ }
            }
        }
        if (shown.isEmpty()) set(22, Icon.of(Material.BARRIER, "<gray>받을 수 있는 보상이 없습니다</gray>"))
        val slice = Paging.slice(shown, page)
        for ((i, def) in slice.withIndex()) {
            val s = service.stateOrNull(target.uniqueId, def.id)
            val claimable = service.claimable(target, def)
            val lore = mutableListOf<String>()
            lore += def.description
            if (def.description.isNotEmpty()) lore += ""
            val material: Material
            if (def.repeat) {
                val left = (def.periodTicks - (s?.progress ?: 0)).coerceAtLeast(0)
                lore += "<gray>${AdminText.ticks(def.periodTicks)}마다</gray>" + if (def.autoClaim) " <dark_gray>(저절로)</dark_gray>" else ""
                lore += "<gray>다음까지 </gray><white>${AdminText.ticks(left)}</white>"
                if (claimable > 0) lore += "<green>받을 수 있음 x$claimable</green>"
                material = if (claimable > 0) Material.CHEST_MINECART else Material.CLOCK
            } else {
                val claimed = (s?.claimed ?: 0) > 0
                lore += "<gray>플레이 시간 ${AdminText.ticks(def.periodTicks)} 달성</gray>" + if (def.autoClaim) " <dark_gray>(저절로)</dark_gray>" else ""
                material = when {
                    claimed -> { lore += "<dark_gray>✔ 받았습니다</dark_gray>"; Material.MINECART }
                    claimable > 0 -> { lore += "<green>받을 수 있음</green>"; Material.CHEST_MINECART }
                    else -> { lore += "<gray>남은 시간 </gray><white>${AdminText.ticks(def.periodTicks - ticks)}</white>"; Material.GRAY_DYE }
                }
            }
            if (claimable > 0 && mine) lore += listOf("", "<yellow>클릭해서 받기</yellow>")
            set(i, Icon.of(material, def.name, lore)) {
                if (!mine || claimable <= 0) return@set
                if (service.claim(target, def) <= 0) ess.messages.send(viewer, "admininfo-prewards-nothing")
                refresh()
            }
        }
        pager(page, shown.size) { page = it; refresh() }
        if (feature.settings.rewardsEnabled) set(SLOT_INFO, Icon.of(Material.COMPASS, "<yellow>플레이 시간</yellow>", "<white>${AdminText.ticks(ticks)}</white>"))
        else set(SLOT_INFO, Icon.of(Material.REDSTONE_TORCH, "<red>플레이 시간 보상이 꺼져 있습니다</red>", "<gray>settings/admininfo.yml playtime-rewards.enabled</gray>"))
        if (viewer.hasPermission(AdminInfoFeature.PREWARDS_EDIT)) {
            set(SLOT_EDIT, Icon.of(Material.ANVIL, "<yellow>보상 편집</yellow>", "<gray>/플레이보상 편집</gray>")) { PlaytimeRewardsAdmin(ess, feature, viewer).show() }
        }
        navigation()
    }

    companion object {
        const val SLOT_INFO = 49
        const val SLOT_EDIT = 51
    }
}

/** `/플레이보상 편집` — 보상 정의 목록 · 새 보상. */
internal class PlaytimeRewardsAdmin(
    ess: Ess,
    private val feature: AdminInfoFeature,
    viewer: Player,
) : Menu(ess, viewer, 54, "<dark_gray>플레이 시간 보상 편집</dark_gray>") {

    private var page = 0

    override fun draw() {
        clear()
        val defs = feature.prewards.all()
        for ((i, def) in Paging.slice(defs, page).withIndex()) {
            val lore = listOf(
                "<gray>id: ${def.id}</gray>",
                "<gray>${if (def.repeat) "반복 · ${Durations.formatShort(def.seconds)}마다" else "한 번 · ${Durations.formatShort(def.seconds)} 달성"}</gray>",
                "<gray>저절로 받기: </gray>" + Icon.toggle(def.autoClaim) + " <gray>· 켜짐: </gray>" + Icon.toggle(def.enabled),
                "<gray>보상 ${def.bundle.entries.size}개</gray>",
                "",
                "<gray>클릭해서 고치기</gray>",
            )
            set(i, Icon.of(if (def.repeat) Material.CLOCK else Material.EXPERIENCE_BOTTLE, def.name, lore)) { PlaytimeRewardEdit(ess, feature, viewer, def).show() }
        }
        pager(page, defs.size) { page = it; refresh() }
        set(SLOT_NEW, Icon.of(Material.LIME_DYE, "<green>새 보상</green>", "<gray>id·종류·시간을 정합니다</gray>")) { create() }
        navigation()
    }

    private fun create() {
        val form = DialogForm("<yellow>새 플레이 시간 보상</yellow>")
            .text("id", "id (영문 소문자·숫자·_-)", "", 32)
            .choice("kind", "종류", listOf("repeat" to "반복(주기마다)", "once" to "한 번(누적 달성)"), "repeat")
            .text("time", "시간(예: 1h · 30m · 1d 2h)", "1h", 32)
        ask(form) { v ->
            val id = v.text("id").trim().lowercase()
            if (!PlaytimeReward.validId(id)) return@ask ess.messages.send(viewer, "admininfo-prewards-bad-id", Ph.of().value(AdminText.escape(id)))
            if (feature.prewards.get(id) != null) return@ask ess.messages.send(viewer, "admininfo-prewards-exists", Ph.of().value(id))
            val seconds = Durations.parse(v.text("time"), 0)
            if (seconds <= 0) return@ask ess.messages.send(viewer, "invalid-number", Ph.of().value(AdminText.escape(v.text("time"))))
            val def = PlaytimeReward(id, name = "<yellow>$id</yellow>", repeat = v.choice("kind") != "once", seconds = seconds)
            feature.prewards.save(def)
            PlaytimeRewardEdit(ess, feature, viewer, def).show()
        }
    }

    companion object {
        const val SLOT_NEW = 49
    }
}

/** 보상 하나 고치기. 바꿀 때마다 저장. */
internal class PlaytimeRewardEdit(
    ess: Ess,
    private val feature: AdminInfoFeature,
    viewer: Player,
    private val def: PlaytimeReward,
) : Menu(ess, viewer, 36, "<dark_gray>보상 · ${def.id}</dark_gray>") {

    override val back: (() -> Unit) = { PlaytimeRewardsAdmin(ess, feature, viewer).show() }

    private fun save() = feature.prewards.save(def)

    override fun draw() {
        clear()
        set(10, valueIcon(Material.NAME_TAG, "보이는 이름", def.name)) {
            ask(DialogForm("<yellow>보이는 이름</yellow>").text("v", "이름(MiniMessage)", def.name, 128)) { v -> def.name = v.text("v").ifBlank { def.id }; save() }
        }
        set(11, valueIcon(Material.WRITABLE_BOOK, "설명", "${def.description.size}줄", *def.description.toTypedArray())) {
            ask(DialogForm("<yellow>설명</yellow>").text("v", "한 줄에 하나", def.description.joinToString("\n"), 2000, multiline = true)) { v ->
                def.description = v.text("v").split('\n').map { it.trimEnd() }.filter { it.isNotEmpty() }.toMutableList(); save()
            }
        }
        set(12, toggleIcon("반복 보상", def.repeat, "<gray>켜짐: 시간마다 반복(CMI PayEvery)</gray>", "<gray>꺼짐: 누적 달성 때 한 번(CMI PayFor)</gray>")) { def.repeat = !def.repeat; save(); refresh() }
        set(13, valueIcon(Material.CLOCK, "시간", Durations.formatShort(def.seconds))) {
            ask(DialogForm("<yellow>시간</yellow>").text("v", "예: 1h · 30m · 1d 2h", Durations.format(def.seconds), 32)) { v ->
                val s = Durations.parse(v.text("v"), 0)
                if (s > 0) { def.seconds = s; save() } else ess.messages.send(viewer, "invalid-number", Ph.of().value(AdminText.escape(v.text("v"))))
            }
        }
        set(14, toggleIcon("저절로 받기", def.autoClaim, "<gray>꺼지면 /플레이보상 화면에서 누릅니다</gray>")) { def.autoClaim = !def.autoClaim; save(); refresh() }
        set(15, toggleIcon("쌓기", def.stack, "<gray>반복 보상을 안 받고 두면 쌓입니다(꺼지면 하나까지)</gray>")) { def.stack = !def.stack; save(); refresh() }
        set(16, toggleIcon("권한 필요", def.permission, "<gray>${def.permissionNode}</gray>")) { def.permission = !def.permission; save(); refresh() }
        set(19, toggleIcon("켜짐", def.enabled)) { def.enabled = !def.enabled; save(); refresh() }
        set(22, Icon.of(Material.CHEST, "<yellow>보상 고르기</yellow>", "<gray>${def.bundle.entries.size}개 · 아이템을 끌어 넣고 확인</gray>")) {
            object : RewardBundleEditor(ess, viewer, "<dark_gray>${def.id} · 보상</dark_gray>", def.bundle, ess.itemResolver, { save() }, { show() }) {}.show()
        }
        set(25, Icon.of(Material.LAVA_BUCKET, "<red>지우기</red>", "<gray>Shift+클릭 — 모든 사람의 진행도 지웁니다</gray>")) { e ->
            if (!e.isShiftClick) return@set
            feature.prewards.remove(def.id)
            ess.messages.send(viewer, "admininfo-prewards-removed", Ph.of().value(def.id))
            PlaytimeRewardsAdmin(ess, feature, viewer).show()
        }
        navigation(backSlot = 27, closeSlot = 35)
    }
}
