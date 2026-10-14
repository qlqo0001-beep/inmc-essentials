package com.inmc.essentials.moderation

import com.inmc.essentials.moderation.ModCmds.greedy
import com.inmc.essentials.moderation.ModCmds.literal
import com.inmc.essentials.moderation.ModCmds.sender
import com.inmc.essentials.moderation.ModCmds.tokens
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.context.CommandContext
import io.papermc.paper.command.brigadier.CommandSourceStack
import kr.inmc.core.integration.Vanish
import org.bukkit.Bukkit

/** `/뮤트`(mute) · `/그림자뮤트`(shadowmute) · `/뮤트해제`(unmute) · `/채팅잠금`(mutechat) · `/채팅잠금해제`(unmutechat). */
class MuteCommands(private val mod: Moderation, private val records: Records) {

    private val ess get() = mod.ess

    private val timeHints = ModCmds.hints { _, before -> if (before.isEmpty()) listOf("10m", "1h", "1d", "+30s", "-15m", "-s") else listOf("-s") }

    fun register() {
        val player = { ess.args.player("플레이어") }
        ess.commands.add(
            literal("뮤트", ModerationFeature.MUTE).executes { ctx -> mod.usage(sender(ctx), "/뮤트 [플레이어] (시간|+시간|-시간) (사유) (-s)"); 1 }
                .then(player().executes { ctx -> mute(ctx, shadow = false); 1 }.then(greedy().suggests(timeHints).executes { ctx -> mute(ctx, shadow = false); 1 }))
                .build(),
            "채팅 막기", listOf("mute"),
        )
        ess.commands.add(
            literal("그림자뮤트", ModerationFeature.SHADOWMUTE).executes { ctx -> mod.usage(sender(ctx), "/그림자뮤트 [플레이어] (시간) (사유) (-s)"); 1 }
                .then(player().executes { ctx -> mute(ctx, shadow = true); 1 }.then(greedy().suggests(timeHints).executes { ctx -> mute(ctx, shadow = true); 1 }))
                .build(),
            "본인 모르게 채팅 막기", listOf("shadowmute"),
        )
        ess.commands.add(
            literal("뮤트해제", ModerationFeature.UNMUTE).executes { ctx -> mod.usage(sender(ctx), "/뮤트해제 [플레이어] (-s)"); 1 }
                .then(player().suggests(mutedNames).executes { ctx -> unmute(ctx); 1 }.then(greedy().executes { ctx -> unmute(ctx); 1 }))
                .build(),
            "뮤트 풀기", listOf("unmute"),
        )
        ess.commands.add(
            literal("채팅잠금", ModerationFeature.MUTECHAT).executes { ctx -> records.lockChat(sender(ctx), null, null, false); 1 }
                .then(greedy().suggests(timeHints).executes { ctx -> lockChat(ctx); 1 })
                .build(),
            "공개 채팅 잠그기", listOf("mutechat"),
        )
        ess.commands.add(
            literal("채팅잠금해제", ModerationFeature.MUTECHAT).executes { ctx -> records.unlockChat(sender(ctx), false); 1 }
                .then(greedy().suggests(ModCmds.hints { _, _ -> listOf("-s") }).executes { ctx ->
                    records.unlockChat(sender(ctx), ModRules.flags(tokens(ctx)).silent); 1
                })
                .build(),
            "공개 채팅 잠금 풀기", listOf("unmutechat"),
        )
    }

    private fun mute(ctx: CommandContext<CommandSourceStack>, shadow: Boolean) {
        val sender = sender(ctx)
        val user = mod.user(sender, ModCmds.arg(ctx, "플레이어")) ?: return
        val flags = ModRules.flags(tokens(ctx))
        val first = flags.rest.firstOrNull()
        val change = first?.let { ModRules.parseChange(it) }
        if (first != null && change == null && (first.startsWith("+") || (first.startsWith("-") && first.length > 1 && first[1].isDigit()))) {
            return ess.messages.send(sender, "moderation-bad-time", Ph.of().value(first))
        }
        val reason = (if (change != null) flags.rest.drop(1) else flags.rest).joinToString(" ")
        records.mute(sender, user, change, reason, flags.silent, shadow)
    }

    private fun unmute(ctx: CommandContext<CommandSourceStack>) {
        val sender = sender(ctx)
        val user = mod.user(sender, ModCmds.arg(ctx, "플레이어")) ?: return
        records.unmute(sender, user, ModRules.flags(tokens(ctx)).silent)
    }

    private fun lockChat(ctx: CommandContext<CommandSourceStack>) {
        val sender = sender(ctx)
        val flags = ModRules.flags(tokens(ctx))
        val first = flags.rest.firstOrNull()
        val seconds = first?.let { ModRules.parseSeconds(it) }
        val reason = (if (seconds != null) flags.rest.drop(1) else flags.rest).joinToString(" ")
        records.lockChat(sender, seconds, reason, flags.silent)
    }

    /** 뮤트된 사람 이름 — 접속 중인 투명인 사람은 볼 수 있을 때만(CLAUDE.md 8). */
    private val mutedNames = ModCmds.hints { sender, before ->
        if (before.isNotEmpty()) emptyList()
        else mod.state.mutes.keys.filter { id -> Bukkit.getPlayer(id)?.let { Vanish.canSee(sender, it) } ?: true }.mapNotNull { ess.users.get(it)?.name }
    }
}
