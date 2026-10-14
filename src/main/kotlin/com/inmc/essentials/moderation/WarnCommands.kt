package com.inmc.essentials.moderation

import com.inmc.essentials.Ess
import com.inmc.essentials.gui.Menu
import com.inmc.essentials.moderation.ModCmds.greedy
import com.inmc.essentials.moderation.ModCmds.literal
import com.inmc.essentials.moderation.ModCmds.sender
import com.inmc.essentials.moderation.ModCmds.tokens
import com.inmc.essentials.store.UserRecord
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.context.CommandContext
import io.papermc.paper.command.brigadier.CommandSourceStack
import kr.inmc.core.gui.ConfirmMenu
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** `/경고`(warn) · `/경고목록`(warnings) · `/경고편집`(editwarnings). */
class WarnCommands(private val mod: Moderation, private val records: Records) {

    private val ess get() = mod.ess

    /** 전부 지우기 확인(보낸 사람 → 미리 본 때). */
    private val clearAllAsked = ConcurrentHashMap<String, Long>()

    fun register() {
        ess.commands.add(
            literal("경고", ModerationFeature.WARN).executes { ctx -> mod.usage(sender(ctx), "/경고 [플레이어] (분류) (사유) (-s)"); 1 }
                .then(ess.args.player("플레이어").executes { ctx -> warn(ctx); 1 }.then(greedy().suggests(categoryHints).executes { ctx -> warn(ctx); 1 }))
                .build(),
            "경고 주기", listOf("warn"),
        )
        ess.commands.add(
            literal("경고목록", ModerationFeature.WARNINGS).executes { ctx ->
                (sender(ctx) as? Player)?.let { list(it, ess.users.get(it.uniqueId) ?: UserRecord(it.uniqueId, it.name, 0L, 0L)) }
                    ?: ess.messages.send(sender(ctx), "player-only"); 1
            }
                .then(ess.args.player("플레이어").requires { it.sender.hasPermission(ModerationFeature.WARNINGS_OTHERS) }.executes { ctx ->
                    mod.user(sender(ctx), ModCmds.arg(ctx, "플레이어"))?.let { list(sender(ctx), it) }; 1
                })
                .build(),
            "경고 보기", listOf("warnings"),
        )
        ess.commands.add(
            literal("경고편집", ModerationFeature.EDITWARNINGS).executes { ctx -> mod.usage(sender(ctx), "/경고편집 [플레이어|전부지우기] (비우기)"); 1 }
                .then(greedy().suggests(editHints).executes { ctx -> edit(ctx); 1 })
                .build(),
            "경고 고치기·지우기", listOf("editwarnings"),
        )
    }

    private fun warn(ctx: CommandContext<CommandSourceStack>) {
        val sender = sender(ctx)
        val user = mod.user(sender, ModCmds.arg(ctx, "플레이어")) ?: return
        val flags = ModRules.flags(tokens(ctx))
        val s = mod.settings
        val first = flags.rest.firstOrNull()
        val category = first?.let { s.category(it) }
        val reason = (if (category != null) flags.rest.drop(1) else flags.rest).joinToString(" ")
        records.warn(sender, user, category ?: s.warnDefault, reason, flags.silent)
    }

    fun list(sender: CommandSender, user: UserRecord) {
        val now = System.currentTimeMillis()
        val list = mod.state.activeWarnings(user.id, now)
        if (list.isEmpty()) return ess.messages.send(sender, "moderation-warnings-none", Ph.of().player(user.name))
        ess.messages.send(sender, "moderation-warnings-header", Ph.of().player(user.name).value(list.sumOf { it.points }.toString()))
        list.forEachIndexed { i, w ->
            ess.messages.send(sender, "moderation-warnings-entry", Ph.of().raw("{번호}", (i + 1).toString()).raw("{날짜}", mod.dateText(w.created))
                .raw("{관리자}", w.source).raw("{분류}", w.category).reason(w.reason).raw("{점수}", w.points.toString())
                .time(w.expires?.let { mod.timeText((it - now) / 1000) } ?: mod.timeText(null)))
        }
    }

    private fun edit(ctx: CommandContext<CommandSourceStack>) {
        val sender = sender(ctx)
        val args = tokens(ctx)
        val first = args.firstOrNull() ?: return
        if (first.lowercase() in CLEAR_ALL) {
            val key = (sender as? Player)?.uniqueId?.toString() ?: "console"
            val count = mod.state.warnings.values.sumOf { it.size }
            if (args.getOrNull(1)?.lowercase() in CONFIRM) {
                val asked = clearAllAsked.remove(key)
                if (asked == null || System.currentTimeMillis() - asked > 60_000) return ess.messages.send(sender, "moderation-data-no-preview")
                val n = records.clearAllWarnings()
                return ess.messages.send(sender, "moderation-editwarnings-all-cleared", Ph.of().count(n))
            }
            val now = System.currentTimeMillis()
            clearAllAsked.values.removeIf { now - it > 60_000 }
            clearAllAsked[key] = now
            return ess.messages.send(sender, "moderation-editwarnings-confirm", Ph.of().count(count).raw("{명령}", "/${ModCmds.label(ctx)} $first 확인"))
        }
        val user = mod.user(sender, first) ?: return
        when (args.getOrNull(1)?.lowercase()) {
            in CLEAR -> {
                records.clearWarnings(user.id)
                ess.messages.send(sender, "moderation-editwarnings-cleared", Ph.of().player(user.name))
            }
            in REMOVE -> {
                val n = args.getOrNull(2)?.toIntOrNull()
                val list = mod.state.activeWarnings(user.id, System.currentTimeMillis())
                val w = n?.let { list.getOrNull(it - 1) } ?: return ess.messages.send(sender, "moderation-editwarnings-not-found", Ph.of().raw("{번호}", args.getOrNull(2) ?: "?"))
                records.removeWarning(user, w)
                ess.messages.send(sender, "moderation-editwarnings-removed", Ph.of().player(user.name).raw("{번호}", n.toString()))
            }
            null -> if (sender is Player) WarningsMenu(ess, sender, mod, records, user).show() else list(sender, user)
            else -> mod.usage(sender, "/경고편집 [플레이어] (비우기 | 삭제 [번호])")
        }
    }

    private val categoryHints = ModCmds.hints { _, before ->
        if (before.isEmpty()) (listOf(mod.settings.warnDefault) + mod.settings.warnCategories).map { it.name } + listOf("-s") else listOf("-s")
    }

    private val editHints = ModCmds.hints { sender, before ->
        when (before.size) {
            0 -> listOf("전부지우기") + kr.inmc.core.integration.Vanish.visibleNames(sender)
            1 -> if (before[0].lowercase() in CLEAR_ALL) listOf("확인") else listOf("비우기", "삭제")
            else -> emptyList()
        }
    }

    companion object {
        val CLEAR_ALL = setOf("전부지우기", "clearall")
        val CLEAR = setOf("비우기", "clear")
        val REMOVE = setOf("삭제", "remove")
        val CONFIRM = setOf("확인", "confirm")
    }
}

/** `/경고편집 <플레이어>` 화면 — 경고마다 종이(때·준 사람·분류·점수·사유·만료), 누르면 지우기(확인). 아래 줄: 전부 지우기. */
class WarningsMenu(ess: Ess, viewer: Player, private val mod: Moderation, private val records: Records, private val user: UserRecord) :
    Menu(ess, viewer, SIZE, "<dark_gray>경고: ${user.name}</dark_gray>") {

    private val id: UUID get() = user.id

    override fun draw() {
        clear()
        val now = System.currentTimeMillis()
        val list = mod.state.activeWarnings(id, now)
        for ((i, w) in list.take(Paging.PER_PAGE).withIndex()) {
            set(i, Icon.of(Material.PAPER, "<yellow>${i + 1}. ${w.category}</yellow> <gray>+${w.points}점</gray>",
                "<gray>사유: </gray><white>${w.reason}</white>", "<gray>준 사람: </gray><white>${w.source}</white>",
                "<gray>때: </gray><white>${mod.dateText(w.created)}</white>",
                "<gray>만료: </gray><white>${w.expires?.let { mod.timeText((it - now) / 1000) } ?: mod.timeText(null)}</white>", "", "<red>▶ 클릭: 지우기</red>")) {
                ConfirmMenu(ess, "<red>이 경고를 지울까요?</red>", listOf("<gray>${w.category} · ${w.reason}</gray>"), onConfirm = {
                    records.removeWarning(user, w)
                    show()
                }, onCancel = { show() }).open(viewer)
            }
        }
        for (slot in Paging.PER_PAGE until SIZE) set(slot, Icon.FILLER)
        set(SLOT_INFO, Icon.of(Material.BOOK, "<gold>${user.name}</gold>", "<gray>경고 ${list.size}개 · 합계 ${list.sumOf { it.points }}점</gray>"))
        if (list.isNotEmpty()) set(SLOT_CLEAR, Icon.of(Material.LAVA_BUCKET, "<red>경고 전부 지우기</red>")) {
            ConfirmMenu(ess, "<red>${user.name} 의 경고 ${list.size}개를 지울까요?</red>", onConfirm = {
                records.clearWarnings(id)
                ess.messages.send(viewer, "moderation-editwarnings-cleared", Ph.of().player(user.name))
                show()
            }, onCancel = { show() }).open(viewer)
        }
        navigation()
    }

    companion object {
        const val SIZE = 54
        const val SLOT_INFO = 49
        const val SLOT_CLEAR = 50
    }
}
