package com.inmc.essentials.admininfo

import com.inmc.essentials.Ess
import com.inmc.essentials.gui.Menu
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import kr.inmc.core.gui.DialogForm
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.gui.RewardBundleEditor
import kr.inmc.core.reward.RewardBundle
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** `/투표 [플레이어]` · `/투표순위 [쪽]` · `/투표편집 <플레이어> <더하기|빼기|설정|초기화> [수] [-s]` · `/투표편집 보상`. */
internal class VoteCommands(private val ess: Ess, private val feature: AdminInfoFeature) {

    private val votes get() = feature.votes

    fun register() {
        ess.commands.add(votesNode(), "투표 수와 다음 투표까지", listOf("votes"))
        ess.commands.add(topNode(), "투표 순위", listOf("votetop"))
        ess.commands.add(editNode(), "투표 수 고치기 · 투표 보상 편집", listOf("voteedit"))
    }

    private fun votesNode() = Commands.literal("투표").requires(AdminText.perm(AdminInfoFeature.VOTES))
        .executes { ctx -> show(ctx, null); 1 }
        .then(ess.args.player().requires(AdminText.perm(AdminInfoFeature.VOTES_OTHERS)).executes { ctx -> show(ctx, StringArgumentType.getString(ctx, "플레이어")); 1 })
        .build()

    private fun show(ctx: CommandContext<CommandSourceStack>, input: String?) {
        val sender = ctx.source.sender
        val (id, name) = if (input == null) {
            val me = AdminText.self(ctx) ?: return ess.messages.send(sender, "player-only")
            me.uniqueId to me.name
        } else AdminText.offline(ess, sender, input) ?: return
        val now = System.currentTimeMillis()
        val count = votes.votes(id)
        val self = (sender as? Player)?.uniqueId == id
        ess.messages.send(sender, if (self) "admininfo-votes-self" else "admininfo-votes-other", Ph.of().target(AdminText.escape(name)).count(count))
        val last = votes.lastVote(id)
        if (last != null) ess.messages.send(sender, "admininfo-votes-last", Ph.of().value(AdminText.date(last)).raw("{오늘}", votes.today(id, now).toString()))
        val hours = feature.settings.voteSiteHours
        if (self && last != null && hours > 0) {
            val wait = last + hours * 3_600_000L - now
            if (wait > 0) ess.messages.send(sender, "admininfo-votes-wait", Ph.of().time(AdminText.ago(wait))) else ess.messages.send(sender, "admininfo-votes-ready")
        }
        if (votes.hookedPlugin == null && sender.hasPermission(AdminInfoFeature.VOTES_EDIT)) ess.messages.send(sender, "admininfo-votes-no-votifier")
    }

    private fun topNode() = Commands.literal("투표순위").requires(AdminText.perm(AdminInfoFeature.VOTES_TOP))
        .executes { ctx -> top(ctx.source.sender, 0); 1 }
        .then(Commands.argument("쪽", IntegerArgumentType.integer(1)).executes { ctx -> top(ctx.source.sender, IntegerArgumentType.getInteger(ctx, "쪽") - 1); 1 })
        .build()

    private fun top(sender: CommandSender, page: Int) {
        val list = votes.top()
        val size = feature.settings.pageSize
        val pages = maxOf(1, (list.size + size - 1) / size)
        val p = page.coerceIn(0, pages - 1)
        ess.messages.send(sender, "admininfo-votes-top-header", Ph.of().raw("{쪽}", (p + 1).toString()).raw("{전체쪽}", pages.toString()).count(list.size))
        if (list.isEmpty()) return ess.messages.send(sender, "admininfo-top-empty")
        val me = (sender as? Player)?.uniqueId
        for ((i, row) in list.withIndex().drop(p * size).take(size)) {
            val ph = Ph.of().rank((i + 1).toString()).player(AdminText.escape(row.second)).count(row.third)
            ess.messages.send(sender, if (row.first == me) "admininfo-votes-top-own" else "admininfo-votes-top-line", ph)
        }
        val mine = list.indexOfFirst { it.first == me }
        if (me != null && mine >= 0 && mine !in (p * size) until (p * size + size)) {
            ess.messages.send(sender, "admininfo-votes-top-own", Ph.of().rank((mine + 1).toString()).player(AdminText.escape(list[mine].second)).count(list[mine].third))
        }
    }

    private val opSuggest = SuggestionProvider<CommandSourceStack> { _, b ->
        val text = b.remaining
        val start = text.lastIndexOf(' ') + 1
        val o = b.createOffset(b.start + start)
        val typed = text.substring(start)
        val options = if (text.substring(0, start).isBlank()) listOf("더하기", "빼기", "설정", "초기화") else listOf("1", "10", "-s")
        for (w in options) if (w.startsWith(typed)) o.suggest(w)
        o.buildFuture()
    }

    private fun editNode() = Commands.literal("투표편집").requires(AdminText.perm(AdminInfoFeature.VOTES_EDIT))
        .then(Commands.literal("보상").executes { ctx ->
            val me = AdminText.self(ctx)
            if (me == null) ess.messages.send(ctx.source.sender, "player-only") else VoteRewardsMenu(ess, feature, me).show()
            1
        })
        .then(
            ess.args.player().executes { ctx -> edit(ctx.source.sender, StringArgumentType.getString(ctx, "플레이어"), ""); 1 }
                .then(
                    Commands.argument("내용", StringArgumentType.greedyString()).suggests(opSuggest).executes { ctx ->
                        edit(ctx.source.sender, StringArgumentType.getString(ctx, "플레이어"), StringArgumentType.getString(ctx, "내용")); 1
                    },
                ),
        ).build()

    private fun edit(sender: CommandSender, input: String, tail: String) {
        val (id, name) = AdminText.offline(ess, sender, input) ?: return
        val (words, silent) = AdminText.flags(tail)
        val current = votes.votes(id)
        if (words.isEmpty()) return ess.messages.send(sender, "admininfo-votes-other", Ph.of().target(AdminText.escape(name)).count(current))
        val op = words[0].lowercase()
        val amount = words.getOrNull(1)?.let { it.toIntOrNull() ?: return ess.messages.send(sender, "invalid-number", Ph.of().value(AdminText.escape(it))) }
        val next = when (op) {
            "초기화", "clear" -> 0
            "더하기", "add", "+" -> current + (amount ?: 1)
            "빼기", "take", "-" -> current - (amount ?: 1)
            "설정", "set", "=" -> amount ?: return ess.messages.send(sender, "admininfo-votes-edit-usage")
            else -> return ess.messages.send(sender, "admininfo-votes-edit-usage")
        }
        val result = votes.set(id, name, next)
        val ph = Ph.of().target(AdminText.escape(name)).count(result).raw("{전}", current.toString())
        ess.messages.send(sender, "admininfo-votes-edit-done", ph)
        val online = Bukkit.getPlayer(id)
        if (online != null && online != sender && !silent) ess.messages.send(online, "admininfo-votes-edit-notify", ph.player(AdminText.escape(sender.name)))
    }
}

/** `/투표편집 보상` — 투표마다 묶음 하나 + 누적 단계마다 묶음. */
internal class VoteRewardsMenu(
    ess: Ess,
    private val feature: AdminInfoFeature,
    viewer: Player,
) : Menu(ess, viewer, 54, "<dark_gray>투표 보상</dark_gray>") {

    private var page = 0

    override fun draw() {
        clear()
        val votes = feature.votes
        set(SLOT_PER_VOTE, Icon.of(Material.CHEST, "<yellow>투표할 때마다</yellow>", "<gray>보상 ${votes.perVote.entries.size}개</gray>", "<gray>명령의 {service} = 투표 사이트</gray>", "", "<gray>클릭해서 고치기</gray>")) {
            val copy = votes.perVote
            object : RewardBundleEditor(ess, viewer, "<dark_gray>투표 보상</dark_gray>", copy, ess.itemResolver, { b -> votes.savePerVote(b) }, { show() }) {}.show()
        }
        val list = votes.milestones.entries.sortedBy { it.key }
        for ((i, entry) in Paging.slice(list, page, PER_PAGE).withIndex()) {
            val (count, bundle) = entry
            set(
                FIRST + i,
                Icon.of(Material.EXPERIENCE_BOTTLE, "<yellow>누적 ${count}회</yellow>", "<gray>보상 ${bundle.entries.size}개</gray>", "", "<gray>클릭: 고치기 · Shift+우클릭: 지우기</gray>"),
            ) { e ->
                if (e.isShiftClick && e.isRightClick) {
                    votes.saveMilestone(count, null)
                    refresh()
                } else {
                    object : RewardBundleEditor(ess, viewer, "<dark_gray>투표 ${count}회 보상</dark_gray>", bundle, ess.itemResolver, { b -> votes.saveMilestone(count, b) }, { show() }) {}.show()
                }
            }
        }
        pager(page, list.size, PER_PAGE) { page = it; refresh() }
        set(SLOT_ADD, Icon.of(Material.LIME_DYE, "<green>누적 단계 더하기</green>", "<gray>그 수에 닿으면 한 번 받습니다</gray>")) {
            ask(DialogForm("<yellow>누적 단계</yellow>").long("n", "투표 수", 10, 1, 1_000_000)) { v ->
                val n = v.long("n")?.toInt() ?: return@ask
                if (votes.milestones[n] == null) votes.saveMilestone(n, RewardBundle())
            }
        }
        navigation()
    }

    companion object {
        const val SLOT_PER_VOTE = 4
        const val FIRST = 18
        const val PER_PAGE = 27
        const val SLOT_ADD = 49
    }
}
