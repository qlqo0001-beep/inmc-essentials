package com.inmc.essentials.admininfo

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import kr.inmc.core.integration.Vanish
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Statistic
import org.bukkit.command.CommandSender
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import java.io.File

/** `/통계 [플레이어]`(접속하지 않은 사람은 통계 파일을 비동기로 읽어 보기만) · `/통계편집 <플레이어> <더하기|빼기|설정> <통계> [하위] <값> [-s]`(접속자만). */
internal class StatsCommands(private val ess: Ess, private val feature: AdminInfoFeature) {

    fun register() {
        ess.commands.add(statsNode(), "바닐라 통계 보기", listOf("stats"))
        ess.commands.add(editNode(), "바닐라 통계 고치기(접속자)", listOf("statsedit"))
    }

    private fun statsNode() = Commands.literal("통계").requires(AdminText.perm(AdminInfoFeature.STATS))
        .executes { ctx -> stats(ctx, null); 1 }
        .then(ess.args.player().requires(AdminText.perm(AdminInfoFeature.STATS_OTHERS)).executes { ctx -> stats(ctx, StringArgumentType.getString(ctx, "플레이어")); 1 })
        .build()

    private fun stats(ctx: CommandContext<CommandSourceStack>, input: String?) {
        val sender = ctx.source.sender
        val (id, name) = if (input == null) {
            val me = AdminText.self(ctx) ?: return ess.messages.send(sender, "player-only")
            me.uniqueId to me.name
        } else AdminText.offline(ess, sender, input) ?: return
        val online = Bukkit.getPlayer(id)?.takeIf { Vanish.canSee(sender, it) }
        val first = ess.users.get(id)?.firstJoin
        if (online != null) return show(sender, name, first, StatsSummary.lines(StatsSummary.Online(online)) { AdminText.ticks(it) })
        // 투명으로 숨는 접속자는 파일 값(마지막 저장)으로 — "접속 중" 이 새지 않게.
        val folder = PlaytimeService.statsFolder() ?: return ess.messages.send(sender, "admininfo-stats-no-file", Ph.of().target(AdminText.escape(name)))
        val file = File(folder, "$id.json")
        val blocks = BLOCK_KEYS
        ess.io.async({
            PlaytimeService.readStats(file)?.let { json -> StatsSummary.lines(StatsSummary.FromJson(json) { it in blocks }) { AdminText.ticks(it) } }
        }) { lines ->
            if (lines == null) ess.messages.send(sender, "admininfo-stats-no-file", Ph.of().target(AdminText.escape(name)))
            else show(sender, name, first, lines)
        }
    }

    private fun show(sender: CommandSender, name: String, first: Long?, lines: List<Pair<String, String>>) {
        ess.messages.send(sender, "admininfo-stats-header", Ph.of().target(AdminText.escape(name)).value(AdminText.date(first)))
        // 두 줄씩 한 줄에 — 50줄 가까운 목록이 채팅을 다 덮지 않게.
        for (pair in lines.chunked(2)) {
            val text = pair.joinToString("   ") { (label, value) -> Ph.of().subject(label).value(value).apply(ess.messages.raw("admininfo-stats-cell")) }
            ess.messages.send(sender, "admininfo-stats-line", Ph.of().value(text))
        }
    }

    // --- /통계편집 -------------------------------------------------------------------------------------

    private val editSuggest = SuggestionProvider<CommandSourceStack> { _, b ->
        val text = b.remaining
        val start = text.lastIndexOf(' ') + 1
        val o = b.createOffset(b.start + start)
        val typed = text.substring(start).lowercase()
        val words = com.inmc.essentials.command.Args.split(text.substring(0, start))
        val options: List<String> = when (words.size) {
            0 -> listOf("더하기", "빼기", "설정")
            1 -> Statistic.entries.map { it.name.lowercase() }
            2 -> when (statOf(words[1])?.type) {
                Statistic.Type.BLOCK -> Material.entries.filter { it.isBlock && !isLegacy(it) }.map { it.name.lowercase() }
                Statistic.Type.ITEM -> Material.entries.filter { it.isItem && !isLegacy(it) }.map { it.name.lowercase() }
                Statistic.Type.ENTITY -> EntityType.entries.filter { it != EntityType.UNKNOWN }.map { it.name.lowercase() }
                else -> listOf("1", "-s")
            }
            else -> listOf("1", "-s")
        }
        var n = 0
        for (w in options) if (w.startsWith(typed)) { o.suggest(w); if (++n >= 100) break }
        o.buildFuture()
    }

    private fun editNode() = Commands.literal("통계편집").requires(AdminText.perm(AdminInfoFeature.STATS_EDIT))
        .then(
            ess.args.player().then(
                Commands.argument("내용", StringArgumentType.greedyString()).suggests(editSuggest).executes { ctx ->
                    edit(ctx.source.sender, StringArgumentType.getString(ctx, "플레이어"), StringArgumentType.getString(ctx, "내용")); 1
                },
            ),
        ).build()

    private fun edit(sender: CommandSender, input: String, tail: String) {
        val target = ess.args.online(sender, input) ?: return ess.messages.send(sender, "player-not-found", Ph.of().value(AdminText.escape(input)))
        val (words, silent) = AdminText.flags(tail)
        if (words.size < 3) return ess.messages.send(sender, "admininfo-statsedit-usage")
        val op = PlaytimeMath.Op.parse(words[0]) ?: return ess.messages.send(sender, "admininfo-statsedit-usage")
        val stat = statOf(words[1]) ?: return ess.messages.send(sender, "admininfo-statsedit-unknown", Ph.of().value(AdminText.escape(words[1])))
        val typed = stat.type != Statistic.Type.UNTYPED
        if (typed && words.size < 4) return ess.messages.send(sender, "admininfo-statsedit-need-sub", Ph.of().value(stat.type.name.lowercase()))
        val amountWord = if (typed) words[3] else words[2]
        val amount = amountWord.toLongOrNull()?.takeIf { it >= 0 } ?: return ess.messages.send(sender, "invalid-number", Ph.of().value(AdminText.escape(amountWord)))
        var sub = ""
        val result = try {
            when (stat.type) {
                Statistic.Type.UNTYPED -> {
                    val before = target.getStatistic(stat).toLong()
                    target.setStatistic(stat, apply(before, op, amount).toInt())
                    before to target.getStatistic(stat).toLong()
                }
                Statistic.Type.BLOCK, Statistic.Type.ITEM -> {
                    val m = Material.matchMaterial(words[2]) ?: return ess.messages.send(sender, "admininfo-statsedit-unknown", Ph.of().value(AdminText.escape(words[2])))
                    sub = m.name.lowercase()
                    val before = target.getStatistic(stat, m).toLong()
                    target.setStatistic(stat, m, apply(before, op, amount).toInt())
                    before to target.getStatistic(stat, m).toLong()
                }
                Statistic.Type.ENTITY -> {
                    val e = EntityType.entries.firstOrNull { it != EntityType.UNKNOWN && it.name.equals(words[2], true) }
                        ?: return ess.messages.send(sender, "admininfo-statsedit-unknown", Ph.of().value(AdminText.escape(words[2])))
                    sub = e.name.lowercase()
                    val before = target.getStatistic(stat, e).toLong()
                    target.setStatistic(stat, e, apply(before, op, amount).toInt())
                    before to target.getStatistic(stat, e).toLong()
                }
            }
        } catch (e: IllegalArgumentException) {
            return ess.messages.send(sender, "admininfo-statsedit-cant", Ph.of().value(AdminText.escape(e.message ?: stat.name)))
        }
        val (before, after) = result
        if (stat == Statistic.PLAY_ONE_MINUTE) feature.playtime.resync(target)
        val ph = Ph.of().target(AdminText.escape(target.name)).subject(stat.name.lowercase()).raw("{하위}", sub.ifEmpty { "-" })
            .raw("{전}", before.toString()).raw("{후}", after.toString())
        ess.messages.send(sender, "admininfo-statsedit-done", ph)
        if (target != sender && !silent) ess.messages.send(target, "admininfo-statsedit-notify", ph)
    }

    private fun apply(before: Long, op: PlaytimeMath.Op, amount: Long): Long = when (op) {
        PlaytimeMath.Op.ADD -> before + amount
        PlaytimeMath.Op.TAKE -> before - amount
        PlaytimeMath.Op.SET -> amount
    }.coerceIn(0L, Int.MAX_VALUE.toLong())

    private fun statOf(word: String): Statistic? = Statistic.entries.firstOrNull { it.name.equals(word, true) }

    @Suppress("DEPRECATION")
    private fun isLegacy(m: Material): Boolean = m.isLegacy

    companion object {
        /** 블록 재료의 바닐라 열쇠(`minecraft:stone`) — 통계 파일의 "used" 를 놓은 블록/쓴 아이템으로 가른다. 메인에서 한 번 만든다. */
        @Suppress("DEPRECATION")
        val BLOCK_KEYS: Set<String> by lazy { Material.entries.filter { !it.isLegacy && it.isBlock && it.isItem }.map { it.key.toString() }.toSet() }
    }
}
