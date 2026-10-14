package com.inmc.essentials.mail

import com.inmc.essentials.Ess
import com.inmc.essentials.gui.Menu
import com.inmc.essentials.util.Ph
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.util.Text
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * 우편함 화면(54칸). 위 45칸 = 우편(새것부터), 아래 줄 = 쪽 · 전부 받기 · 정보 · 다른 곳에서 받을 것(경매장 바로가기) · 읽은 편지 지우기 · 닫기.
 *
 * - 보상: 클릭 = 그것만 받기. 편지: 클릭 = 읽기(책) · 우클릭 = 첨부 받기 · Shift+우클릭 = 지우기(받을 것이 남은 편지는 못 지운다).
 * - [mailOwner] 가 보는 사람이 아니면 관리자 보기 — 받기는 없고 읽기(읽음 표시 안 함)·편지 지우기만. 받을 것이 남은 편지는 Shift+우클릭을
 *   한 번 더 해야 지워지고(첨부가 사라진다) 서버 로그에 남는다.
 */
class MailMenu(
    ess: Ess,
    viewer: Player,
    private val feature: MailFeature,
    private val mailOwner: UUID,
    private val ownerName: String,
    private var page: Int = 0,
) : Menu(ess, viewer, SIZE, if (viewer.uniqueId == mailOwner) ess.messages.raw("mail-menu-title") else ess.messages.raw("mail-menu-title-other").replace("{플레이어}", ownerName)) {

    private val admin: Boolean get() = viewer.uniqueId != mailOwner
    private val service: MailService get() = feature.service

    /** 관리자 보기에서 받을 것이 남은 편지를 지우려고 한 번 누른 것(id, 때) — 5초 안에 같은 것을 다시 눌러야 지운다. */
    private var armed: Pair<String, Long>? = null

    override fun draw() {
        clear()
        val entries = feature.store.of(mailOwner).sortedByDescending { it.parcel.at }
        page = Paging.clamp(page, entries.size)
        for ((slot, entry) in Paging.slice(entries, page).withIndex()) {
            set(slot, icon(entry)) { event -> click(entry, event.click) }
        }
        for (slot in Paging.PER_PAGE until SIZE) set(slot, Icon.FILLER)
        pager(page, entries.size) { page = it; refresh() }
        val claimable = entries.count { it.hasContent }
        val unread = entries.count { it.isLetter && it.readAt == null }
        set(
            SLOT_INFO,
            Icon.of(
                Material.BOOK, "<gold>우편 ${entries.size}통</gold>",
                "<gray>안 읽은 편지 <white>$unread</white>통</gray>", "<gray>받을 것이 남은 우편 <white>$claimable</white>통</gray>", "",
                "<dark_gray>편지는 ${feature.settings.letterDays.takeIf { it > 0 }?.let { "${it}일 뒤 지워집니다" } ?: "지워지지 않습니다"}</dark_gray>",
                "<dark_gray>보상은 지워지지 않습니다</dark_gray>",
            ),
        )
        if (!admin) {
            if (claimable > 0) {
                set(SLOT_CLAIM_ALL, Icon.of(Material.HOPPER, "<green>전부 받기</green>", "<gray>받을 것이 남은 우편 ${claimable}통</gray>", "", "<yellow>▶ 클릭</yellow>")) {
                    claimAll()
                }
            }
            val readLetters = entries.count { it.isLetter && it.readAt != null && !it.hasContent }
            if (readLetters > 0) {
                set(SLOT_CLEAN, Icon.of(Material.LAVA_BUCKET, "<red>읽은 편지 지우기</red>", "<gray>받을 것이 없는 읽은 편지 ${readLetters}통</gray>", "", "<yellow>▶ Shift+클릭</yellow>")) { event ->
                    if (!event.isShiftClick) return@set
                    val ids = feature.store.of(mailOwner).filter { it.isLetter && it.readAt != null && !it.hasContent }.map { it.id }
                    val removed = feature.store.remove(mailOwner, ids).size
                    ess.messages.send(viewer, "mail-deleted", Ph.of().count(removed))
                    refresh()
                }
            }
            for ((index, shortcut) in feature.settings.shortcuts.withIndex()) {
                val slot = SLOT_SHORTCUTS.getOrNull(index) ?: break
                shortcutIcon(shortcut)?.let { icon ->
                    set(slot, icon) {
                        viewer.closeInventory()
                        viewer.performCommand(shortcut.command)
                    }
                }
            }
        }
        navigation()
    }

    private fun click(entry: MailEntry, click: ClickType) {
        val current = feature.store.find(mailOwner, entry.id) ?: return refresh()
        when {
            click == ClickType.SHIFT_RIGHT && current.isLetter -> {
                if (current.hasContent) {
                    if (!admin) return ess.messages.send(viewer, "mail-delete-has-content")
                    val now = System.currentTimeMillis()
                    val pending = armed
                    if (pending == null || pending.first != current.id || now - pending.second > CONFIRM_MILLIS) {
                        armed = current.id to now
                        return ess.messages.send(viewer, "mail-delete-content-confirm", Ph.of().player(ownerName))
                    }
                    armed = null
                    ess.logger.info("${viewer.name} 이(가) ${ownerName} 의 받을 것이 남은 편지를 지웠습니다: ${service.describe(current)}")
                }
                feature.store.remove(mailOwner, listOf(current.id))
                ess.messages.send(viewer, "mail-deleted", Ph.of().count(1))
                refresh()
            }
            admin -> if (current.isLetter) {
                viewer.closeInventory()
                service.openLetter(viewer, current)
            }
            current.isLetter && click.isLeftClick -> {
                viewer.closeInventory()
                service.openLetter(viewer, current)
            }
            current.hasContent -> {
                if (!service.ensureSynced(viewer)) return
                val outcome = service.claimOne(viewer, current)
                ess.messages.send(viewer, if (outcome.done) "mail-claimed-one" else if (outcome.partial) "mail-claim-partial" else "mail-claim-held")
                refresh()
            }
        }
    }

    private fun claimAll() {
        if (!service.ensureSynced(viewer)) return
        val result = service.claim(viewer, null)
        when {
            result.claimed == 0 && result.leftBehind == 0 -> ess.messages.send(viewer, "mail-claim-nothing")
            result.partial -> ess.messages.send(viewer, "mail-claimed-partial", Ph.of().count(result.claimed).amount(result.leftBehind.toString()))
            else -> ess.messages.send(viewer, "mail-claimed", Ph.of().count(result.claimed))
        }
        refresh()
    }

    // --- 아이콘 -----------------------------------------------------------------------------------

    private fun icon(entry: MailEntry): ItemStack {
        val p = entry.parcel
        val lore = ArrayList<String>()
        val material: Material
        val name: String
        if (entry.isLetter) {
            val unread = entry.readAt == null
            material = if (unread) Material.WRITABLE_BOOK else Material.BOOK
            name = (if (unread) "<yellow>✉ 새 편지</yellow>" else "<gray>✉ 편지</gray>") + " <white>${esc(p.senderName ?: "?")}</white>"
            val preview = p.text.orEmpty().replace('\n', ' ').let { if (it.length > 40) it.take(40) + "…" else it }
            if (preview.isNotEmpty()) lore += "<gray>\"${esc(preview)}\"</gray>"
        } else {
            material = Material.CHEST
            name = "<gold>보상</gold> <white>${esc(p.source)}</white>"
        }
        lore += "<dark_gray>보낸 곳: ${esc(origin(p.origin))}</dark_gray>"
        if (entry.isLetter && p.source.isNotBlank()) lore += "<dark_gray>${esc(p.source)}</dark_gray>"
        lore += "<dark_gray>받은 때: ${service.date(p.at)}</dark_gray>"
        if (entry.isLetter && entry.hasContent) {
            if (p.expiresAt != null) lore += "<dark_gray>받을 것이 남아 있는 동안은 지워지지 않습니다</dark_gray>"
        } else {
            p.expiresAt?.let { lore += "<dark_gray>지워지는 때: ${service.date(it)}</dark_gray>" }
        }
        val contents = contents(entry)
        if (contents.isNotEmpty()) {
            lore += ""
            lore += "<white>들어 있는 것</white>"
            lore += contents
        }
        lore += ""
        lore += when {
            admin && entry.isLetter && entry.hasContent -> "<yellow>▶ 클릭: 읽기 · Shift+우클릭 두 번: 첨부째 지우기</yellow>"
            admin && entry.isLetter -> "<yellow>▶ 클릭: 읽기 · Shift+우클릭: 지우기</yellow>"
            admin -> "<gray>관리자 보기 — 받기 없음</gray>"
            entry.isLetter && entry.hasContent -> "<yellow>▶ 클릭: 읽기 · 우클릭: 첨부 받기</yellow>"
            entry.isLetter -> "<yellow>▶ 클릭: 읽기 · Shift+우클릭: 지우기</yellow>"
            entry.hasContent -> "<yellow>▶ 클릭: 받기</yellow>"
            else -> "<gray>받을 것이 없습니다</gray>"
        }
        val stack = Icon.of(material, name, lore)
        if (entry.hasContent || (entry.isLetter && entry.readAt == null)) stack.editMeta { it.setEnchantmentGlintOverride(true) }
        return stack
    }

    private fun contents(entry: MailEntry): List<String> {
        val p = entry.parcel
        val out = ArrayList<String>()
        for (bytes in p.items.take(MAX_ITEM_LINES)) {
            val stack = runCatching { ItemStack.deserializeBytes(bytes) }.getOrNull()
            out += if (stack == null) "<red>· 되살릴 수 없는 아이템</red>"
            else "<gray>· </gray>" + MM.serialize(stack.effectiveName()) + " <gray>x${stack.amount}</gray>"
        }
        if (p.items.size > MAX_ITEM_LINES) out += "<gray>· 그 밖에 ${p.items.size - MAX_ITEM_LINES}개</gray>"
        if (p.money > 0.0) out += "<gray>· </gray><gold>${ess.economy.format(p.money)}</gold>"
        for ((currency, amount) in p.moneyBy) if (amount > 0.0) out += "<gray>· </gray><gold>${ess.economy.format(amount, currency)}</gold>"
        if (p.commands.isNotEmpty()) out += "<gray>· 특전 ${p.commands.size}개</gray>"
        return out
    }

    /** 바로가기 — 그 플러그인이 켜져 있고 PAPI 가 개수를 숫자로 돌려줄 때만(아니면 칸을 비운다). */
    private fun shortcutIcon(shortcut: MailShortcut): ItemStack? {
        if (shortcut.plugin.isNotBlank() && !Bukkit.getPluginManager().isPluginEnabled(shortcut.plugin)) return null
        val count = if (shortcut.count.isBlank()) null else Text.substituteOnly(shortcut.count, null, viewer).trim().toIntOrNull() ?: return null
        val lore = buildList {
            if (count != null) add("<gray>받을 것 <white>${count}</white>건</gray>")
            add("<dark_gray>다른 곳에 보관 중 — 거기서 받습니다</dark_gray>")
            add("")
            add("<yellow>▶ 클릭: /${esc(shortcut.command)}</yellow>")
        }
        val stack = Icon.of(shortcut.icon, "<aqua>${esc(shortcut.name)}</aqua>", lore)
        if (count != null && count > 0) {
            stack.amount = count.coerceIn(1, 64)
            stack.editMeta { it.setEnchantmentGlintOverride(true) }
        }
        return stack
    }

    private fun origin(raw: String): String = when (raw) {
        MailFeature.LETTER_ORIGIN -> "편지"
        ess.plugin.name -> "에센셜"
        else -> raw.removePrefix("inmc-").removePrefix("InMc-")
    }

    /** 사람이 쓴 글(편지·출처) — 태그를 풀고 `%` 를 전각으로(아이콘 글은 PAPI 를 거친다). */
    private fun esc(text: String): String = MM.escapeTags(text).replace('%', '％')

    companion object {
        const val SIZE = 54
        const val SLOT_CLAIM_ALL = 48
        const val SLOT_INFO = 49
        const val SLOT_SHORTCUT_1 = 50
        const val SLOT_SHORTCUT_2 = 51
        const val SLOT_CLEAN = 52
        val SLOT_SHORTCUTS = listOf(SLOT_SHORTCUT_1, SLOT_SHORTCUT_2)
        private const val MAX_ITEM_LINES = 8
        private const val CONFIRM_MILLIS = 5_000L
        private val MM = MiniMessage.miniMessage()
    }
}
