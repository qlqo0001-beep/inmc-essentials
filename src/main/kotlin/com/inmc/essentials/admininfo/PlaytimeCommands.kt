package com.inmc.essentials.admininfo

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.time.LocalDate
import java.util.UUID

/** `/플레이시간` · `/플레이시간순위` · `/상세플레이시간` · `/플레이시간편집` · `/플레이보상`. */
internal class PlaytimeCommands(private val ess: Ess, private val feature: AdminInfoFeature) {

    private val playtime get() = feature.playtime

    fun register() {
        ess.commands.add(playtimeNode(), "플레이 시간과 첫 접속", listOf("playtime"))
        ess.commands.add(topNode(), "플레이 시간 순위", listOf("playtimetop"))
        ess.commands.add(detailNode(), "오늘·이번 주·이번 달… 플레이 시간", listOf("cplaytime"))
        ess.commands.add(editNode(), "플레이 시간 더하기·빼기·정하기", listOf("editplaytime"))
        ess.commands.add(rewardsNode(), "플레이 시간 보상 받기 · 편집", listOf("prewards"))
    }

    /** 대상 — 없으면 본인, 남이면 [othersNode] 권한. (uuid, 이름) 또는 null(알림을 보냈다). */
    private fun target(ctx: CommandContext<CommandSourceStack>, input: String?, othersNode: String): Pair<UUID, String>? {
        val sender = ctx.source.sender
        if (input == null) {
            val me = AdminText.self(ctx) ?: run { ess.messages.send(sender, "player-only"); return null }
            return me.uniqueId to me.name
        }
        val found = AdminText.offline(ess, sender, input) ?: return null
        if ((sender as? Player)?.uniqueId != found.first && !sender.hasPermission(othersNode)) {
            ess.messages.send(sender, "no-permission")
            return null
        }
        return found
    }

    // --- /플레이시간 ---------------------------------------------------------------------------------

    private fun playtimeNode() = Commands.literal("플레이시간").requires(AdminText.perm(AdminInfoFeature.PLAYTIME))
        .executes { ctx -> show(ctx, null); 1 }
        .then(ess.args.player().executes { ctx -> show(ctx, StringArgumentType.getString(ctx, "플레이어")); 1 })
        .build()

    private fun show(ctx: CommandContext<CommandSourceStack>, input: String?) {
        val sender = ctx.source.sender
        val (id, name) = target(ctx, input, AdminInfoFeature.PLAYTIME_OTHERS) ?: return
        val ticks = playtime.ticks(id)
        val first = ess.users.get(id)?.firstJoin
        val ph = Ph.of().target(AdminText.escape(name)).time(ticks?.let { AdminText.ticks(it) } ?: "-").value(AdminText.date(first))
        ess.messages.send(sender, "admininfo-playtime-total", ph)
        ess.messages.send(sender, "admininfo-playtime-joined", ph)
        val place = playtime.top(System.currentTimeMillis()).indexOfFirst { it.first == id }
        if (place >= 0) ess.messages.send(sender, "admininfo-playtime-place", ph.rank((place + 1).toString()))
    }

    // --- /플레이시간순위 ----------------------------------------------------------------------------------

    private fun topNode() = Commands.literal("플레이시간순위").requires(AdminText.perm(AdminInfoFeature.PLAYTIME_TOP))
        .executes { ctx -> top(ctx.source.sender, 0); 1 }
        .then(Commands.argument("쪽", IntegerArgumentType.integer(1)).executes { ctx -> top(ctx.source.sender, IntegerArgumentType.getInteger(ctx, "쪽") - 1); 1 })
        .build()

    private fun top(sender: CommandSender, page: Int) {
        if (playtime.scanning) ess.messages.send(sender, "admininfo-playtime-scanning")
        val list = playtime.top(System.currentTimeMillis())
        val size = feature.settings.pageSize
        val pages = maxOf(1, (list.size + size - 1) / size)
        val p = page.coerceIn(0, pages - 1)
        ess.messages.send(sender, "admininfo-playtime-top-header", Ph.of().raw("{쪽}", (p + 1).toString()).raw("{전체쪽}", pages.toString()).count(list.size))
        if (list.isEmpty()) return ess.messages.send(sender, "admininfo-top-empty")
        val me = (sender as? Player)?.uniqueId
        for ((i, row) in list.withIndex().drop(p * size).take(size)) {
            val ph = Ph.of().rank((i + 1).toString()).player(AdminText.escape(row.second)).time(AdminText.ticks(row.third))
            ess.messages.send(sender, if (row.first == me) "admininfo-playtime-top-own" else "admininfo-playtime-top-line", ph)
        }
        val mine = list.indexOfFirst { it.first == me }
        if (me != null && mine >= 0 && mine !in (p * size) until (p * size + size)) {
            ess.messages.send(sender, "admininfo-playtime-top-own", Ph.of().rank((mine + 1).toString()).player(AdminText.escape(list[mine].second)).time(AdminText.ticks(list[mine].third)))
        }
    }

    // --- /상세플레이시간 ---------------------------------------------------------------------------------

    private fun detailNode() = Commands.literal("상세플레이시간").requires(AdminText.perm(AdminInfoFeature.PLAYTIME_DETAIL))
        .executes { ctx -> detail(ctx, null); 1 }
        .then(ess.args.player().executes { ctx -> detail(ctx, StringArgumentType.getString(ctx, "플레이어")); 1 })
        .build()

    private fun detail(ctx: CommandContext<CommandSourceStack>, input: String?) {
        val sender = ctx.source.sender
        val (id, name) = target(ctx, input, AdminInfoFeature.PLAYTIME_DETAIL_OTHERS) ?: return
        Bukkit.getPlayer(id)?.let { playtime.sampleOne(it) }
        playtime.days(id) { days ->
            val sums = PlaytimeMath.sums(days, LocalDate.now())
            ess.messages.send(sender, "admininfo-playtime-detail-header", Ph.of().target(AdminText.escape(name)))
            for ((range, ticks) in sums) {
                ess.messages.send(sender, "admininfo-playtime-detail-line", Ph.of().subject(range.label).time(AdminText.ticks(ticks)))
            }
            ess.messages.send(sender, "admininfo-playtime-detail-total", Ph.of().time(playtime.ticks(id)?.let { AdminText.ticks(it) } ?: "-"))
        }
    }

    // --- /플레이시간편집 -----------------------------------------------------------------------------------

    private val opSuggest = SuggestionProvider<CommandSourceStack> { _, b ->
        val text = b.remaining
        val start = text.lastIndexOf(' ') + 1
        val o = b.createOffset(b.start + start)
        val typed = text.substring(start)
        val head = text.substring(0, start).trim()
        val options = if (head.isEmpty()) listOf("더하기", "빼기", "설정") else listOf("1h", "30m", "1d", "-s")
        for (w in options) if (w.startsWith(typed)) o.suggest(w)
        o.buildFuture()
    }

    private fun editNode() = Commands.literal("플레이시간편집").requires(AdminText.perm(AdminInfoFeature.PLAYTIME_EDIT))
        .then(
            ess.args.player().then(
                Commands.argument("내용", StringArgumentType.greedyString()).suggests(opSuggest).executes { ctx ->
                    edit(ctx.source.sender, StringArgumentType.getString(ctx, "플레이어"), StringArgumentType.getString(ctx, "내용")); 1
                },
            ),
        ).build()

    private fun edit(sender: CommandSender, input: String, tail: String) {
        val (words, silent) = AdminText.flags(tail)
        val op = words.firstOrNull()?.let { PlaytimeMath.Op.parse(it) } ?: return ess.messages.send(sender, "admininfo-playtime-edit-usage")
        val amount = words.drop(1).joinToString(" ")
        val seconds = PlaytimeMath.parseAmountSeconds(amount) ?: return ess.messages.send(sender, "invalid-number", Ph.of().value(AdminText.escape(amount)))
        val (id, name) = AdminText.offline(ess, sender, input) ?: return
        val (before, after) = playtime.edit(id, name, op, seconds * PlaytimeMath.TICKS_PER_SECOND)
        val online = Bukkit.getPlayer(id)
        val ph = Ph.of().target(AdminText.escape(name)).raw("{전}", before?.let { AdminText.ticks(it) } ?: "-").raw("{후}", after?.let { AdminText.ticks(it) } ?: "-")
        ess.messages.send(sender, if (online != null) "admininfo-playtime-edit-done" else "admininfo-playtime-edit-queued", ph)
        if (online != null && online != sender && !silent) ess.messages.send(online, "admininfo-playtime-edit-notify", ph)
    }

    // --- /플레이보상 -------------------------------------------------------------------------------------

    private fun rewardsNode() = Commands.literal("플레이보상").requires(AdminText.perm(AdminInfoFeature.PREWARDS))
        .executes { ctx ->
            val me = AdminText.self(ctx)
            when {
                me == null -> ess.messages.send(ctx.source.sender, "player-only")
                // 꺼져 있으면 받을 수 없다 — 편집 권한이 있는 사람만 화면(편집 단추)을 연다.
                !feature.settings.rewardsEnabled && !me.hasPermission(AdminInfoFeature.PREWARDS_EDIT) -> ess.messages.send(me, "admininfo-prewards-disabled")
                else -> PlaytimeRewardsView(ess, feature, me, me).show()
            }
            1
        }
        .then(
            Commands.literal("편집").requires(AdminText.perm(AdminInfoFeature.PREWARDS_EDIT)).executes { ctx ->
                val me = AdminText.self(ctx)
                if (me == null) ess.messages.send(ctx.source.sender, "player-only") else PlaytimeRewardsAdmin(ess, feature, me).show()
                1
            },
        )
        .then(
            ess.args.player().requires(AdminText.perm(AdminInfoFeature.PREWARDS_OTHERS)).executes { ctx ->
                val sender = ctx.source.sender
                val me = AdminText.self(ctx) ?: return@executes 1.also { ess.messages.send(sender, "player-only") }
                val input = StringArgumentType.getString(ctx, "플레이어")
                val target = ess.args.online(sender, input)
                if (target == null) ess.messages.send(sender, "player-not-found", Ph.of().value(AdminText.escape(input))) else PlaytimeRewardsView(ess, feature, me, target).show()
                1
            },
        ).build()
}
