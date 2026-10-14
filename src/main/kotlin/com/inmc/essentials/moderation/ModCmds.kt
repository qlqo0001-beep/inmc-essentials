package com.inmc.essentials.moderation

import com.inmc.essentials.command.Args
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import org.bukkit.command.CommandSender
import java.util.concurrent.CompletableFuture

/** 명령 짓기 도우미 — moderation 의 명령 파일들이 같이 쓴다. */
object ModCmds {

    fun literal(name: String, permission: String): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(name).requires { it.sender.hasPermission(permission) }

    /** greedy 꼬리(없으면 빈 글). */
    fun tail(ctx: CommandContext<CommandSourceStack>, name: String = "내용"): String =
        runCatching { StringArgumentType.getString(ctx, name) }.getOrDefault("")

    fun arg(ctx: CommandContext<CommandSourceStack>, name: String): String = StringArgumentType.getString(ctx, name)

    fun tokens(ctx: CommandContext<CommandSourceStack>, name: String = "내용"): List<String> = Args.split(tail(ctx, name))

    fun sender(ctx: CommandContext<CommandSourceStack>): CommandSender = ctx.source.sender

    /** 친 명령의 이름(별칭 포함, `/` 없이) — 쿨타임·되묻기 링크에. */
    fun label(ctx: CommandContext<CommandSourceStack>): String = ctx.input.removePrefix("/").substringBefore(' ')

    fun greedy(name: String = "내용") = Commands.argument(name, StringArgumentType.greedyString())

    /**
     * 꼬리 제안 — 지금 치는 낱말의 순번(0부터)에 따라 [options] 가 내놓는 보기 중 앞부분이 맞는 것. 앞 낱말들은 [options] 에 넘긴다.
     */
    fun hints(options: (CommandSender, List<String>) -> List<String>): SuggestionProvider<CommandSourceStack> =
        SuggestionProvider { ctx, builder -> suggest(ctx.source.sender, builder, options) }

    private fun suggest(sender: CommandSender, builder: SuggestionsBuilder, options: (CommandSender, List<String>) -> List<String>): CompletableFuture<Suggestions> {
        val text = builder.remaining
        val cut = text.lastIndexOf(' ') + 1
        val before = Args.split(text.substring(0, cut))
        val typed = text.substring(cut).lowercase()
        val sub = builder.createOffset(builder.start + cut)
        for (o in options(sender, before)) if (o.lowercase().startsWith(typed)) sub.suggest(o)
        return sub.buildFuture()
    }
}
