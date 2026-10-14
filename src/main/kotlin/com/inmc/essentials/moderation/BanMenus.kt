package com.inmc.essentials.moderation

import com.destroystokyo.paper.profile.PlayerProfile
import com.inmc.essentials.Ess
import com.inmc.essentials.gui.Menu
import kr.inmc.core.gui.ConfirmMenu
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import org.bukkit.BanEntry
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import java.net.InetAddress

/** 밴 목록 화면(CMI banlist·ipbanlist) — 사람 머리(또는 IP 종이)마다 사유·차단한 사람·때·만료. 누르면 상세(해제 버튼). */
class BanListMenu(
    ess: Ess,
    viewer: Player,
    private val mod: Moderation,
    private val bans: Bans,
    private val ip: Boolean,
    private var page: Int = 0,
) : Menu(ess, viewer, SIZE, if (ip) "<dark_red>IP 차단 목록</dark_red>" else "<dark_red>차단 목록</dark_red>") {

    override fun draw() {
        clear()
        val entries: List<BanEntry<*>> = if (ip) bans.ipEntries() else bans.profileEntries()
        val pages = Paging.pageCount(entries.size, Paging.PER_PAGE)
        page = page.coerceIn(0, (pages - 1).coerceAtLeast(0))
        for ((i, entry) in entries.drop(page * Paging.PER_PAGE).take(Paging.PER_PAGE).withIndex()) {
            set(i, icon(mod, bans, entry, click = true, viewer)) { BanDetailMenu(ess, viewer, mod, bans, entry) { BanListMenu(ess, viewer, mod, bans, ip, page).show() }.show() }
        }
        for (slot in Paging.PER_PAGE until SIZE) set(slot, Icon.FILLER)
        pager(page, entries.size) { page = it; refresh() }
        set(SLOT_SWITCH, Icon.of(if (ip) Material.PLAYER_HEAD else Material.PAPER, if (ip) "<yellow>사람 차단 목록 보기</yellow>" else "<yellow>IP 차단 목록 보기</yellow>",
            "<gray>지금 ${entries.size}건</gray>")) { BanListMenu(ess, viewer, mod, bans, !ip).show() }
        navigation()
    }

    companion object {
        const val SIZE = 54
        const val SLOT_SWITCH = 49

        /** 연관 계정(CMI banlist·ipbanlist 의 "같은 IP 계정") — [viewer] 에게 `checkaccount.showip` 가 있을 때만. */
        private fun related(mod: Moderation, bans: Bans, entry: BanEntry<*>, viewer: Player): List<String> {
            if (!viewer.hasPermission(ModerationFeature.CHECKACCOUNT_SHOWIP)) return emptyList()
            val state = mod.state
            val excluded = mod.settings.accountExcluded.map { it.lowercase() }.toSet()
            fun name(id: java.util.UUID) = mod.ess.users.get(id)?.name ?: id.toString()
            val target = entry.banTarget
            val ids: Set<java.util.UUID> = when (target) {
                is InetAddress -> state.usersOf(target.hostAddress)
                is PlayerProfile -> target.id?.let { me -> state.ips[me].orEmpty().filter { it.ip !in excluded }.flatMap { state.usersOf(it.ip) }.toSet() - me }.orEmpty()
                else -> emptySet()
            }
            val names = ids.map { name(it) }.filter { it.lowercase() !in excluded }.sorted()
            if (names.isEmpty()) return emptyList()
            return listOf("<gray>연관 계정: </gray><white>${names.take(6).joinToString(", ")}</white>" + if (names.size > 6) " <dark_gray>외 ${names.size - 6}</dark_gray>" else "")
        }

        fun icon(mod: Moderation, bans: Bans, entry: BanEntry<*>, click: Boolean, viewer: Player): ItemStack {
            val now = System.currentTimeMillis()
            val expires = entry.expiration?.time
            val lore = buildList {
                add("<gray>사유: </gray><white>${entry.reason ?: "-"}</white>")
                add("<gray>차단한 사람: </gray><white>${entry.source ?: "?"}</white>")
                entry.created?.let { add("<gray>차단한 때: </gray><white>${mod.dateText(it.time)}</white>") }
                add("<gray>남은 기간: </gray><white>${mod.timeText(expires?.let { ((it - now) / 1000).coerceAtLeast(1) })}</white>")
                addAll(related(mod, bans, entry, viewer))
                if (click) {
                    add("")
                    add("<yellow>▶ 클릭: 자세히 · 해제</yellow>")
                }
            }
            val target = entry.banTarget
            if (target is PlayerProfile) {
                @Suppress("UNCHECKED_CAST")
                val name = bans.nameOf(entry as BanEntry<PlayerProfile>)
                val stack = Icon.of(Material.PLAYER_HEAD, "<red>$name</red>", lore)
                (stack.itemMeta as? SkullMeta)?.let { meta ->
                    runCatching { meta.playerProfile = target }
                    stack.itemMeta = meta
                }
                return stack
            }
            val address = bans.label(entry)
            return Icon.of(Material.PAPER, "<red>$address</red>", lore)
        }
    }
}

/** 밴 하나(CMI checkban) — 정보와 해제(확인). */
class BanDetailMenu(
    ess: Ess,
    viewer: Player,
    private val mod: Moderation,
    private val bans: Bans,
    private val entry: BanEntry<*>,
    override val back: (() -> Unit)?,
) : Menu(ess, viewer, SIZE, "<dark_red>차단 정보</dark_red>") {

    override fun draw() {
        clear()
        for (slot in 0 until SIZE) set(slot, Icon.FILLER)
        set(SLOT_INFO, BanListMenu.icon(mod, bans, entry, click = false, viewer))
        if (viewer.hasPermission(ModerationFeature.UNBAN)) {
            set(SLOT_UNBAN, Icon.of(Material.LIME_DYE, "<green>차단 풀기</green>", "<gray>되돌릴 수 없습니다(다시 차단은 가능).</gray>")) {
                val input = bans.key(entry)
                ConfirmMenu(ess, "<green>차단을 풀까요?</green>", listOf("<gray>${bans.label(entry)}</gray>"), onConfirm = {
                    viewer.closeInventory()
                    bans.unban(viewer, input, silent = false)
                    back?.invoke()
                }, onCancel = { show() }).open(viewer)
            }
        }
        navigation(SLOT_BACK, SLOT_CLOSE)
    }

    companion object {
        const val SIZE = 27
        const val SLOT_INFO = 11
        const val SLOT_UNBAN = 15
        const val SLOT_BACK = 18
        const val SLOT_CLOSE = 26
    }
}
