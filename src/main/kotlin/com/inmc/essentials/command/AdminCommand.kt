package com.inmc.essentials.command

import com.inmc.essentials.Ess
import com.inmc.essentials.EssentialsPlugin
import com.inmc.essentials.feature.Features
import com.inmc.essentials.util.Ph
import com.inmc.essentials.verify.Verifier
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import org.bukkit.entity.Player

/**
 * `/에센셜` (별칭 `ess`·`inmcess`) — 리로드 · 검증 · 모듈. 다른 기능의 관리 하위 명령은 `ess.commands.addAdmin(…)` 으로 여기 붙는다.
 * [CommandRegistry] 가 명령 등록 사건마다 새로 짓는다.
 */
class AdminCommand(private val ess: Ess) {

    fun build(children: List<LiteralCommandNode<CommandSourceStack>>): LiteralCommandNode<CommandSourceStack> {
        val root = Commands.literal(NAME).requires { it.sender.hasPermission(Ess.ADMIN) }
            .executes { ctx -> ess.messages.send(ctx.source.sender, "help-admin"); 1 }
            .then(Commands.literal("리로드").executes { ctx ->
                val sender = ctx.source.sender
                (ess.plugin as EssentialsPlugin).reload(sender)
                1
            })
            .then(Commands.literal("검증").executes { ctx -> player(ctx)?.let { Verifier(ess).run(it) }; 1 })
            .then(Commands.literal("모듈").executes { ctx -> modules(ctx); 1 })
        for (child in children) root.then(child)
        return root.build()
    }

    private fun player(ctx: CommandContext<CommandSourceStack>): Player? =
        (ctx.source.executor as? Player ?: ctx.source.sender as? Player) ?: null.also { ess.messages.send(ctx.source.sender, "player-only") }

    private fun modules(ctx: CommandContext<CommandSourceStack>) {
        val sender = ctx.source.sender
        ess.messages.send(sender, "modules-header", Ph.of().count(Features.IDS.size))
        for (id in Features.IDS) {
            val key = when {
                ess.enabled(id) -> "module-on"
                id in ess.failed -> "module-failed"
                else -> "module-off"
            }
            ess.messages.send(sender, key, Ph.of().value(id))
        }
    }

    companion object {
        const val NAME = "에센셜"
        val ALIASES = listOf("ess", "inmcess")
        val NAMES: Set<String> = setOf(NAME) + ALIASES

        /** 기능이 [CommandRegistry.addAdmin] 으로 같은 이름을 붙이지 못하게. */
        val BUILT_IN: Set<String> = setOf("리로드", "검증", "모듈")
    }
}
