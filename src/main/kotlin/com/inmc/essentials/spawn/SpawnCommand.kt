package com.inmc.essentials.spawn

import com.inmc.essentials.Ess
import com.inmc.essentials.service.TeleportKind
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import kr.inmc.core.integration.Vanish
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * `/스폰`(spawn) — 서버 스폰으로 · `/스폰 <플레이어> [-s]`(남 보내기) · `/스폰 설정`(선 곳을 서버 스폰으로) · `/스폰 처음설정`(첫 스폰) ·
 * `/스폰 관리`(관리 화면). inmc-menu 의 트리를 그대로 옮기고 CMI 의 남 보내기·첫 스폰을 더했다.
 *
 * 대기·쿨타임·비용은 여기 없다 — commands 기능이 명령 이름(`스폰`·`spawn`)으로 건다. 이동이 성공한 순간 `ess.gate?.commit` 을 부른다.
 */
class SpawnCommand(private val ess: Ess, private val service: SpawnService) {

    fun register() {
        ess.commands.add(tree().build(), "서버 스폰으로", listOf("spawn"))
    }

    private fun tree(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("스폰").requires { canUse(it.sender) }
            .executes { ctx -> player(ctx)?.let { self(it, label(ctx)) }; 1 }
            .then(Commands.literal("설정").requires { isAdmin(it.sender) }.executes { ctx ->
                player(ctx)?.let { p ->
                    val point = SpawnPoint.of(p.location)
                    service.update { it.copy(point = point) }
                    ess.messages.send(p, "spawn-set", Ph.of().value(point.describe()))
                }
                1
            })
            .then(Commands.literal("처음설정").requires { isAdmin(it.sender) }.executes { ctx ->
                player(ctx)?.let { p ->
                    val point = SpawnPoint.of(p.location)
                    service.update { it.copy(firstPoint = point) }
                    ess.messages.send(p, "spawn-first-set", Ph.of().value(point.describe()))
                }
                1
            })
            .then(Commands.literal("관리").requires { isAdmin(it.sender) }.executes { ctx ->
                player(ctx)?.let { SpawnAdminMenu(ess, it, service).show() }
                1
            })
            .then(ess.args.player("플레이어").requires { it.sender.hasPermission(SpawnFeature.OTHERS) }
                .executes { ctx -> others(ctx, silent = false); 1 }
                .then(Commands.literal("-s").executes { ctx -> others(ctx, silent = true); 1 }))

    private fun self(player: Player, label: String) {
        player.closeInventory()
        go(player) { ok ->
            if (ok) {
                ess.gate?.commit(player, label)
                ess.messages.send(player, "spawn-done")
            } else {
                ess.messages.send(player, "spawn-failed")
            }
        }
    }

    private fun others(ctx: CommandContext<CommandSourceStack>, silent: Boolean) {
        val sender = ctx.source.sender
        val input = StringArgumentType.getString(ctx, "플레이어")
        val target = ess.args.online(sender, input) ?: return ess.messages.send(sender, "player-not-found", Ph.of().value(input))
        if (target == sender) return self(target, label(ctx))
        go(target) { ok ->
            if (!ok) return@go ess.messages.send(sender, "spawn-failed-other", Ph.of().target(target.name))
            ess.messages.send(sender, "spawn-sent", Ph.of().target(target.name))
            if (!silent) {
                // 투명인 관리자가 보냈으면 이름을 숨긴다(규칙 8).
                val who = if (sender is Player && !Vanish.canSee(target, sender)) "관리자" else sender.name
                ess.messages.send(target, "spawn-sent-target", Ph.of().player(who))
            }
        }
    }

    /** 이동 — 흩는 반경이 있으면 고른 칸의 청크를 비동기로 올린 뒤. 메인 스레드에서 [after] 를 부른다. */
    private fun go(player: Player, after: (Boolean) -> Unit) {
        service.scatterAsync(service.target(), service.config.radius).whenComplete { to, scatterError ->
            onMain(player) {
                if (scatterError != null || to == null || !player.isOnline) return@onMain after(false)
                ess.teleporter.teleport(player, to, TeleportKind.SPAWN).whenComplete { ok, error ->
                    val success = error == null && ok == true
                    onMain(player) { after(success) }
                }
            }
        }
    }

    private fun onMain(player: Player, block: () -> Unit) {
        if (Bukkit.isPrimaryThread()) block() else player.scheduler.run(ess.plugin, { block() }, null)
    }

    private fun player(ctx: CommandContext<CommandSourceStack>): Player? =
        (ctx.source.executor as? Player ?: ctx.source.sender as? Player) ?: null.also { ess.messages.send(ctx.source.sender, "player-only") }

    private fun label(ctx: CommandContext<CommandSourceStack>): String = ctx.input.removePrefix("/").substringBefore(' ')

    companion object {
        /**
         * 쓸 수 있나 — 넘어가는 동안 옛 노드 `inmcmenu.spawn` 도 인정한다. 옛 노드를 **명시적으로 끈** 사람(감옥·행사 그룹)은 막는다
         * (새 노드는 기본 TRUE 라 옛 끄기가 묻히지 않게).
         */
        fun canUse(sender: CommandSender): Boolean {
            if (sender.isPermissionSet(SpawnFeature.OLD_USE) && !sender.hasPermission(SpawnFeature.OLD_USE)) return false
            return sender.hasPermission(SpawnFeature.USE) || sender.hasPermission(SpawnFeature.OLD_USE)
        }

        fun isAdmin(sender: CommandSender): Boolean = sender.hasPermission(SpawnFeature.ADMIN) || sender.hasPermission(SpawnFeature.OLD_ADMIN)
    }
}
