package com.inmc.essentials.player

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
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

internal typealias Ctx = CommandContext<CommandSourceStack>

/**
 * player 기능 명령의 공용 틀.
 *
 * 대부분의 명령이 CMI 처럼 `[대상] [값…] [-s]` 모양이다 — 첫 낱말이 사람이면 그 사람, 아니면(값·깃발이면) 나 자신. 그래서
 * [flex] 는 `ess.args.player()`(빈칸까지 한 낱말 — 한글 닉네임도) 를 첫 자리에 두고, 그 낱말을 `ess.args.online` 으로 찾지 못했는데
 * 값처럼 보이면 나에게 쓴 것으로 읽는다. 뒤의 값·깃발은 greedy 꼬리 하나([Tail]).
 */
internal class Cmds(private val ess: Ess) {

    fun perm(node: String): (CommandSourceStack) -> Boolean = { it.sender.hasPermission(node) }

    fun str(ctx: Ctx, name: String): String = StringArgumentType.getString(ctx, name)

    /** 친 명령 이름(앞의 `/`·`플러그인:` 없이). */
    fun label(ctx: Ctx): String = ctx.input.trim().removePrefix("/").substringBefore(' ').substringAfterLast(':')

    /** 명령을 친 플레이어(아니면 "플레이어만" 을 말하고 null). */
    fun self(ctx: Ctx): Player? =
        (ctx.source.executor as? Player ?: ctx.source.sender as? Player) ?: null.also { ess.messages.send(ctx.source.sender, "player-only") }

    /** [viewer] 에게 [actor] 의 이름 — 투명이라 못 보면 "관리자", 콘솔도 "관리자"(CLAUDE.md 8). */
    fun nameFor(viewer: CommandSender, actor: CommandSender): String = when {
        actor !is Player -> ess.messages.raw("player-someone")
        !Vanish.canSee(viewer, actor) -> ess.messages.raw("player-someone")
        else -> actor.name
    }

    /** 남에게 한 것이면 그 사람에게 알린다(-s 면 안 알림 · 자기 자신이면 안 알림). */
    fun notify(sender: CommandSender, target: Player, silent: Boolean, key: String, ph: Ph) {
        if (silent || target == sender) return
        ph.player(nameFor(target, sender))
        ess.messages.sendRaw(target, ess.messages.raw(key), ph)
    }

    /**
     * 꼬리 제안 — 첫 칸이면 보이는 사람 이름 + [first], 그 뒤 칸이면 [rest]. 이미 친 낱말 다음 자리부터 제안한다.
     */
    fun tailSuggest(rest: List<String>): SuggestionProvider<CommandSourceStack> = SuggestionProvider { _, builder ->
        val text = builder.remaining
        val start = text.lastIndexOf(' ') + 1
        val typed = text.substring(start).lowercase()
        val b = builder.createOffset(builder.start + start)
        val used = text.substring(0, start).split(' ').map { it.lowercase() }.toSet()
        for (o in rest) if (o.lowercase().startsWith(typed) && o.lowercase() !in used) b.suggest(o)
        b.buildFuture()
    }

    /** 첫 자리(사람 인자)의 제안 — 보이는 사람 이름 + [options](나에게 쓰는 값). */
    fun firstSuggest(options: List<String>, all: Boolean): SuggestionProvider<CommandSourceStack> = SuggestionProvider { ctx, builder ->
        val typed = builder.remainingLowerCase
        if (all && "all".startsWith(typed)) builder.suggest("all")
        for (o in options) if (o.lowercase().startsWith(typed)) builder.suggest(o)
        for (name in Vanish.visibleNames(ctx.source.sender)) if (name.lowercase().startsWith(typed)) builder.suggest(name)
        builder.buildFuture()
    }

    /**
     * `/이름 [대상|all] [꼬리…]`. [run] 은 대상들(남이면 [othersPerm] 를 본 뒤) · 꼬리 · 남을 골랐나를 받는다.
     * [looksLikeValue] 가 첫 낱말을 값으로 보면(사람으로 못 찾았을 때) 나에게 쓴 것으로 읽는다.
     */
    fun flex(
        name: String,
        basePerm: String,
        othersPerm: String,
        firstOptions: List<String>,
        tailOptions: List<String>,
        allowAll: Boolean = false,
        looksLikeValue: (String) -> Boolean = { Tail.isFlag(it) || it.lowercase() in firstOptions.map(String::lowercase) || TimeArg.parse(it) != null },
        run: (ctx: Ctx, targets: List<Player>, tail: Tail, other: Boolean) -> Unit,
    ): LiteralCommandNode<CommandSourceStack> {
        fun go(ctx: Ctx, first: String?, rest: String?): Int {
            val sender = ctx.source.sender
            if (first == null) {
                val me = self(ctx) ?: return 0
                run(ctx, listOf(me), Tail.EMPTY, false)
                return 1
            }
            val tail = Tail.parse(rest)
            if (allowAll && Args.isAll(first)) {
                if (!sender.hasPermission(othersPerm)) return 0.also { ess.messages.send(sender, "no-permission") }
                run(ctx, ess.args.targets(sender, first).orEmpty(), tail, true)
                return 1
            }
            // 값처럼 보이는 낱말은 이름이 **정확히** 같을 때만 사람으로 읽는다 — `/속도 2` 가 "Steve2" 를 고르지 않게(부분 이름 찾기).
            val found = ess.args.online(sender, first)?.takeIf { !looksLikeValue(first) || it.name.equals(first, ignoreCase = true) }
            if (found == null || (found != sender && !sender.hasPermission(othersPerm) && looksLikeValue(first))) {
                if (looksLikeValue(first)) {
                    val me = self(ctx) ?: return 0
                    run(ctx, listOf(me), tail.prepend(first), false)
                    return 1
                }
                ess.messages.send(sender, "player-not-found", Ph.of().value(first))
                return 0
            }
            if (found != sender && !sender.hasPermission(othersPerm)) return 0.also { ess.messages.send(sender, "no-permission") }
            run(ctx, listOf(found), tail, found != sender)
            return 1
        }
        val first = ess.args.player("대상").suggests(firstSuggest(firstOptions, allowAll))
            .executes { ctx -> go(ctx, str(ctx, "대상"), null) }
            .then(Commands.argument("값", StringArgumentType.greedyString()).suggests(tailSuggest(tailOptions)).executes { ctx -> go(ctx, str(ctx, "대상"), str(ctx, "값")) })
        return Commands.literal(name).requires(perm(basePerm))
            .executes { ctx -> go(ctx, null, null) }
            .then(first)
            .build()
    }
}
