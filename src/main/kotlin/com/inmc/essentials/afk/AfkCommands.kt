package com.inmc.essentials.afk

import com.inmc.essentials.Ess
import com.inmc.essentials.command.Args
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import kr.inmc.core.integration.Vanish
import kr.inmc.core.util.Durations
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** `/잠수 [사유…] [-p:플레이어] [-s]` · `/잠수확인 [플레이어|all]`. */
internal class AfkCommands(private val ess: Ess, private val feature: AfkFeature) {

    private val service get() = feature.service

    fun register() {
        ess.commands.add(afk(), "잠수 켜고 끄기(사유를 적을 수 있다)", listOf("afk"))
        ess.commands.add(check(), "잠수 상태·쉰 시간 보기", listOf("afkcheck"))
    }

    private fun perm(node: String): (CommandSourceStack) -> Boolean = { it.sender.hasPermission(node) }

    private fun afk(): LiteralCommandNode<CommandSourceStack> {
        val suggest = SuggestionProvider<CommandSourceStack> { ctx, b ->
            val text = b.remaining
            val start = text.lastIndexOf(' ') + 1
            val typed = text.substring(start)
            val o = b.createOffset(b.start + start)
            if (ctx.source.sender.hasPermission(AfkFeature.OTHERS)) {
                if (typed.startsWith("-p:")) {
                    for (name in Vanish.visibleNames(ctx.source.sender)) if ("-p:$name".lowercase().startsWith(typed.lowercase())) o.suggest("-p:$name")
                } else if ("-p:".startsWith(typed)) o.suggest("-p:")
            }
            if ("-s".startsWith(typed)) o.suggest("-s")
            o.buildFuture()
        }
        return Commands.literal("잠수").requires(perm(AfkFeature.AFK))
            .executes { ctx -> toggle(ctx, null); 1 }
            .then(Commands.argument("사유", StringArgumentType.greedyString()).suggests(suggest).executes { ctx -> toggle(ctx, StringArgumentType.getString(ctx, "사유")); 1 })
            .build()
    }

    private fun toggle(ctx: CommandContext<CommandSourceStack>, text: String?) {
        val sender = ctx.source.sender
        val words = text?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
        val silent = words.any { it.equals("-s", ignoreCase = true) }
        val who = words.firstOrNull { it.startsWith("-p:", ignoreCase = true) }?.substring(3)
        val reason = words.filterNot { it.equals("-s", ignoreCase = true) || it.startsWith("-p:", ignoreCase = true) }.joinToString(" ").take(MAX_REASON).ifBlank { null }
        val target: Player = if (who != null) {
            if (!sender.hasPermission(AfkFeature.OTHERS)) return ess.messages.send(sender, "no-permission")
            ess.args.online(sender, who) ?: return ess.messages.send(sender, "player-not-found", Ph.of().value(who))
        } else {
            (ctx.source.executor as? Player ?: sender as? Player) ?: return ess.messages.send(sender, "player-only")
        }
        if (service.isAfk(target)) {
            service.leave(target, silent)
            if (target != sender) ess.messages.send(sender, "afk-unset-other", Ph.of().target(target.name))
        } else {
            // 싸우다 스스로 잠수해 피하지 못하게(남이 시키는 -p: 는 관리자 일이라 막지 않는다).
            val left = if (who == null) service.combatLeft(target.uniqueId) else 0L
            if (left > 0) return ess.messages.send(sender, "afk-in-combat", Ph.of().time(Durations.formatShort(left)))
            service.enter(target, auto = false, reason = reason, silent = silent)
            if (target != sender) ess.messages.send(sender, "afk-set-other", Ph.of().target(target.name))
        }
    }

    private fun check(): LiteralCommandNode<CommandSourceStack> =
        Commands.literal("잠수확인").requires(perm(AfkFeature.CHECK))
            .executes { ctx ->
                val me = ctx.source.executor as? Player ?: ctx.source.sender as? Player
                if (me == null) ess.messages.send(ctx.source.sender, "player-only") else show(ctx.source.sender, me, null)
                1
            }
            .then(
                ess.args.players("대상").executes { ctx ->
                    val sender = ctx.source.sender
                    val input = StringArgumentType.getString(ctx, "대상")
                    if (Args.isAll(input)) {
                        val all = ess.args.targets(sender, input).orEmpty().sortedWith(compareByDescending<Player> { service.isAfk(it) }.thenBy { it.name })
                        ess.messages.send(sender, "afk-check-header", Ph.of().count(all.size).amount(all.count { service.isAfk(it) }.toString()))
                        for ((i, p) in all.withIndex()) show(sender, p, i + 1)
                    } else {
                        val target = ess.args.online(sender, input)
                        if (target == null) ess.messages.send(sender, "player-not-found", Ph.of().value(input)) else show(sender, target, null)
                    }
                    1
                },
            ).build()

    /** 한 사람의 상태 한 줄. [place] 가 있으면 목록의 줄. */
    private fun show(sender: CommandSender, target: Player, place: Int?) {
        val now = System.currentTimeMillis()
        val idle = Durations.formatShort(((now - service.lastActive(target)) / 1000).coerceAtLeast(0))
        val entry = service.entry(target.uniqueId)
        val ph = Ph.of().target(target.name).time(idle).raw("{순번}", (place ?: 0).toString())
        if (entry != null) {
            ph.value(Durations.formatShort(((now - entry.since) / 1000).coerceAtLeast(0)))
            ph.reason(entry.reason?.let { AfkService.escape(it) } ?: ess.messages.raw("afk-no-reason"))
            ess.messages.send(sender, if (place != null) "afk-check-line-afk" else "afk-check-afk", ph)
        } else {
            ess.messages.send(sender, if (place != null) "afk-check-line-active" else "afk-check-active", ph)
        }
    }

    companion object {
        const val MAX_REASON = 100
    }
}
