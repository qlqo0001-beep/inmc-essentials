package com.inmc.essentials.moderation

import com.inmc.essentials.command.Args
import com.inmc.essentials.moderation.ModCmds.greedy
import com.inmc.essentials.moderation.ModCmds.literal
import com.inmc.essentials.moderation.ModCmds.sender
import com.inmc.essentials.moderation.ModCmds.tokens
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.context.CommandContext
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.entity.Player

/**
 * `/밴` · `/기간밴`(tempban) · `/아이피밴` · `/기간아이피밴`(tempipban) · `/밴해제`(unban) · `/밴목록` · `/밴확인`(checkban) · `/추방` — 계획 4절.
 * `ban`·`ban-ip`·`banlist`·`kick`·`pardon` 은 바닐라 이름이라 영어 별칭이 없다(바닐라 것도 같은 목록을 쓴다).
 */
class BanCommands(private val mod: Moderation, private val bans: Bans, private val records: Records) {

    private val ess get() = mod.ess

    fun register() {
        val player = { ess.args.player("플레이어") }
        ess.commands.add(
            literal("밴", ModerationFeature.BAN).executes { ctx -> mod.usage(sender(ctx), "/밴 [플레이어] (사유) (-s)"); 1 }
                .then(player().executes { ctx -> ban(ctx, temp = false); 1 }.then(greedy().executes { ctx -> ban(ctx, temp = false); 1 }))
                .build(),
            "플레이어 차단(영구)",
        )
        ess.commands.add(
            literal("기간밴", ModerationFeature.TEMPBAN).executes { ctx -> mod.usage(sender(ctx), "/기간밴 [플레이어] [시간] (사유) (-s)"); 1 }
                .then(player().then(greedy().executes { ctx -> ban(ctx, temp = true); 1 }))
                .build(),
            "플레이어 기간 차단", listOf("tempban"),
        )
        ess.commands.add(
            literal("아이피밴", ModerationFeature.IPBAN).executes { ctx -> mod.usage(sender(ctx), "/아이피밴 [IP/플레이어] (사유) (-s)"); 1 }
                .then(player().executes { ctx -> ipBan(ctx, temp = false); 1 }.then(greedy().executes { ctx -> ipBan(ctx, temp = false); 1 }))
                .build(),
            "IP 차단(영구)",
        )
        ess.commands.add(
            literal("기간아이피밴", ModerationFeature.IPBAN).executes { ctx -> mod.usage(sender(ctx), "/기간아이피밴 [IP/플레이어] [시간] (사유) (-s)"); 1 }
                .then(player().then(greedy().executes { ctx -> ipBan(ctx, temp = true); 1 }))
                .build(),
            "IP 기간 차단", listOf("tempipban"),
        )
        ess.commands.add(
            literal("밴해제", ModerationFeature.UNBAN).executes { ctx -> mod.usage(sender(ctx), "/밴해제 [플레이어/IP] (-s)"); 1 }
                .then(player().suggests(bannedNames).executes { ctx -> unban(ctx); 1 }.then(greedy().executes { ctx -> unban(ctx); 1 }))
                .build(),
            "차단 풀기(사람·IP)", listOf("unban"),
        )
        ess.commands.add(
            literal("밴목록", ModerationFeature.BANLIST).executes { ctx -> list(ctx, ip = false); 1 }
                .then(greedy().suggests(ModCmds.hints { _, _ -> listOf("아이피", "ip") }).executes { ctx ->
                    list(ctx, ip = tokens(ctx).firstOrNull()?.lowercase() in setOf("아이피", "ip")); 1
                })
                .build(),
            "차단 목록(화면)",
        )
        ess.commands.add(
            // 이름 없이 = 목록은 /밴목록 권한이 있을 때만(checkban 만으로 전체 목록을 보지 않게).
            literal("밴확인", ModerationFeature.CHECKBAN).executes { ctx ->
                if (sender(ctx).hasPermission(ModerationFeature.BANLIST)) list(ctx, ip = false) else mod.usage(sender(ctx), "/밴확인 [플레이어/IP]"); 1
            }
                .then(player().suggests(bannedNames).executes { ctx -> check(ctx); 1 })
                .build(),
            "차단 확인", listOf("checkban"),
        )
        ess.commands.add(
            literal("추방", ModerationFeature.KICK).executes { ctx -> mod.usage(sender(ctx), "/추방 [플레이어/all] (사유) (-s)"); 1 }
                .then(ess.args.players("대상").executes { ctx -> kick(ctx); 1 }.then(greedy().executes { ctx -> kick(ctx); 1 }))
                .build(),
            "추방(한 명·전원)",
        )
    }

    private fun ban(ctx: CommandContext<CommandSourceStack>, temp: Boolean) {
        val sender = sender(ctx)
        val user = mod.user(sender, ModCmds.arg(ctx, "플레이어")) ?: return
        val flags = ModRules.flags(tokens(ctx))
        var rest = flags.rest
        var seconds: Long? = null
        if (temp) {
            val raw = rest.firstOrNull() ?: return mod.usage(sender, "/기간밴 [플레이어] [시간] (사유) (-s)")
            seconds = ModRules.parseSeconds(raw) ?: return ess.messages.send(sender, "moderation-bad-time", Ph.of().value(raw))
            rest = rest.drop(1)
            if (!mod.withinLimit(sender, seconds, ModerationFeature.TEMPBAN_MAX, mod.settings.tempbanMaxWithout)) return
        }
        bans.ban(sender, user, seconds, rest.joinToString(" "), flags.silent)
    }

    private fun ipBan(ctx: CommandContext<CommandSourceStack>, temp: Boolean) {
        val sender = sender(ctx)
        val flags = ModRules.flags(tokens(ctx))
        var rest = flags.rest
        var seconds: Long? = null
        if (temp) {
            val raw = rest.firstOrNull() ?: return mod.usage(sender, "/기간아이피밴 [IP/플레이어] [시간] (사유) (-s)")
            seconds = ModRules.parseSeconds(raw) ?: return ess.messages.send(sender, "moderation-bad-time", Ph.of().value(raw))
            rest = rest.drop(1)
            if (!mod.withinLimit(sender, seconds, ModerationFeature.TEMPBAN_MAX, mod.settings.tempbanMaxWithout)) return
        }
        bans.ipBan(sender, ModCmds.arg(ctx, "플레이어"), seconds, rest.joinToString(" "), flags.silent)
    }

    private fun unban(ctx: CommandContext<CommandSourceStack>) {
        val flags = ModRules.flags(tokens(ctx))
        bans.unban(sender(ctx), ModCmds.arg(ctx, "플레이어"), flags.silent)
    }

    private fun list(ctx: CommandContext<CommandSourceStack>, ip: Boolean) {
        val sender = sender(ctx)
        if (sender is Player) return BanListMenu(ess, sender, mod, bans, ip).show()
        val entries = if (ip) bans.ipEntries() else bans.profileEntries()
        if (entries.isEmpty()) return ess.messages.send(sender, "moderation-banlist-empty")
        ess.messages.send(sender, "moderation-banlist-header", Ph.of().count(entries.size))
        val now = System.currentTimeMillis()
        entries.forEachIndexed { i, e ->
            val name = bans.label(e)
            ess.messages.send(sender, "moderation-banlist-entry", Ph.of().player(name).reason(e.reason ?: "-").raw("{관리자}", e.source ?: "?")
                .raw("{번호}", (i + 1).toString()).time(mod.timeText(e.expiration?.time?.let { ((it - now) / 1000).coerceAtLeast(1) })))
        }
    }

    private fun check(ctx: CommandContext<CommandSourceStack>) {
        val sender = sender(ctx)
        val input = ModCmds.arg(ctx, "플레이어")
        val entry = if (ModRules.isIp(input)) bans.ipEntry(input) else {
            val user = mod.user(sender, input) ?: return
            bans.entry(user.id, user.name)
        } ?: return ess.messages.send(sender, "moderation-checkban-none", Ph.of().player(input))
        if (sender is Player) return BanDetailMenu(ess, sender, mod, bans, entry) { BanListMenu(ess, sender, mod, bans, ModRules.isIp(input)).show() }.show()
        val now = System.currentTimeMillis()
        ess.messages.send(sender, "moderation-checkban-detail", Ph.of().player(input).reason(entry.reason ?: "-").raw("{관리자}", entry.source ?: "?")
            .raw("{날짜}", entry.created?.let { mod.dateText(it.time) } ?: "?")
            .time(mod.timeText(entry.expiration?.time?.let { ((it - now) / 1000).coerceAtLeast(1) })))
    }

    private fun kick(ctx: CommandContext<CommandSourceStack>) {
        val sender = sender(ctx)
        val input = ModCmds.arg(ctx, "대상")
        val flags = ModRules.flags(tokens(ctx))
        val all = Args.isAll(input)
        if (all && !sender.hasPermission(ModerationFeature.KICK_ALL)) return ess.messages.send(sender, "no-permission")
        val targets = ess.args.targets(sender, input) ?: return ess.messages.send(sender, "player-not-found", Ph.of().value(input))
        records.kick(sender, targets, flags.rest.joinToString(" "), flags.silent, all)
    }

    /** 밴해제·밴확인 제안 — 차단된 사람 이름. */
    private val bannedNames = ModCmds.hints { _, before ->
        if (before.isNotEmpty()) emptyList() else bans.profileEntries().map { bans.nameOf(it) } + bans.ipEntries().map { bans.label(it) }
    }
}
