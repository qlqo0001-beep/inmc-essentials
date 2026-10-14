package com.inmc.essentials.admininfo

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** `/투명 [대상|목록] [켜기|끄기] [-s]` · `/투명설정 [대상] [옵션] [켜기|끄기]`. */
internal class VanishCommands(private val ess: Ess, private val feature: AdminInfoFeature) {

    private val vanish get() = feature.vanish

    fun register() {
        ess.commands.add(vanishNode(), "관리자 투명 켜고 끄기 · 목록", listOf("vanish", "v"))
        ess.commands.add(editNode(), "투명 중 세부 동작 고르기", listOf("vanishedit"))
    }

    private val stateWords = listOf("켜기", "끄기", "-s")

    private val stateSuggest = SuggestionProvider<CommandSourceStack> { _, b ->
        val text = b.remaining
        val start = text.lastIndexOf(' ') + 1
        val o = b.createOffset(b.start + start)
        val typed = text.substring(start).lowercase()
        for (w in stateWords) if (w.startsWith(typed)) o.suggest(w)
        o.buildFuture()
    }

    private fun vanishNode() = Commands.literal("투명").requires(AdminText.perm(AdminInfoFeature.VANISH))
        .executes { ctx -> run(ctx, null, null); 1 }
        .then(Commands.literal("목록").requires(AdminText.perm(AdminInfoFeature.VANISH_LIST)).executes { ctx -> list(ctx.source.sender); 1 })
        .then(
            ess.args.player("대상").suggests { ctx, b ->
                val typed = b.remainingLowerCase
                for (w in stateWords) if (w.startsWith(typed)) b.suggest(w)
                ess.args.names.getSuggestions(ctx, b)
            }
                .executes { ctx -> run(ctx, StringArgumentType.getString(ctx, "대상"), null); 1 }
                .then(
                    Commands.argument("옵션", StringArgumentType.greedyString()).suggests(stateSuggest)
                        .executes { ctx -> run(ctx, StringArgumentType.getString(ctx, "대상"), StringArgumentType.getString(ctx, "옵션")); 1 },
                ),
        ).build()

    private fun run(ctx: CommandContext<CommandSourceStack>, token: String?, tail: String?) {
        val sender = ctx.source.sender
        val first = token?.trim().orEmpty()
        val selfWords = first.isEmpty() || VanishOption.parseState(first) != null || first.equals("-s", true)
        val (words, silent) = AdminText.flags(if (selfWords) "$first ${tail.orEmpty()}" else tail)
        val target: Player = if (selfWords) {
            AdminText.self(ctx) ?: return ess.messages.send(sender, "player-only")
        } else {
            if (!sender.hasPermission(AdminInfoFeature.VANISH_OTHERS)) return ess.messages.send(sender, "no-permission")
            ess.args.online(sender, first) ?: return ess.messages.send(sender, "player-not-found", Ph.of().value(AdminText.escape(first)))
        }
        val wanted = words.firstNotNullOfOrNull { VanishOption.parseState(it) } ?: !vanish.isActive(target)
        if (!vanish.set(target, wanted, silent)) {
            ess.messages.send(sender, if (wanted) "admininfo-vanish-already-on" else "admininfo-vanish-already-off", Ph.of().target(VanishService.displayName(target)))
            return
        }
        ess.messages.send(target, if (wanted) "admininfo-vanish-on" else "admininfo-vanish-off")
        if (target != sender) {
            ess.messages.send(sender, if (wanted) "admininfo-vanish-on-other" else "admininfo-vanish-off-other", Ph.of().target(VanishService.displayName(target)))
        }
    }

    /** 투명인 사람 — 접속 중(다른 플러그인의 투명 포함)과 저장된 투명(접속 안 함). */
    private fun list(sender: CommandSender) {
        val online = Bukkit.getOnlinePlayers().filter { kr.inmc.core.integration.Vanish.isVanished(it) }
        val offline = vanish.storedVanished().filter { Bukkit.getPlayer(it) == null }
        if (online.isEmpty() && offline.isEmpty()) return ess.messages.send(sender, "admininfo-vanish-list-empty")
        ess.messages.send(sender, "admininfo-vanish-list-header", Ph.of().count(online.size + offline.size))
        var i = 0
        for (p in online) {
            i++
            val since = vanish.since(p.uniqueId)?.let { AdminText.ago(System.currentTimeMillis() - it) } ?: "-"
            ess.messages.send(sender, "admininfo-vanish-list-online", Ph.of().raw("{순번}", i.toString()).player(VanishService.displayName(p)).time(since))
        }
        for (id in offline) {
            i++
            val name = ess.users.get(id)?.name ?: id.toString()
            ess.messages.send(sender, "admininfo-vanish-list-offline", Ph.of().raw("{순번}", i.toString()).player(AdminText.escape(name)))
        }
    }

    // --- 투명설정 ---------------------------------------------------------------------------------------

    private val optionSuggest = SuggestionProvider<CommandSourceStack> { _, b ->
        val text = b.remaining
        val start = text.lastIndexOf(' ') + 1
        val o = b.createOffset(b.start + start)
        val typed = text.substring(start).lowercase()
        val words = text.substring(0, start).trim()
        if (words.isEmpty()) {
            for (opt in VanishOption.entries) if (opt.cmiKey.lowercase().startsWith(typed)) o.suggest(opt.cmiKey)
        } else {
            for (w in listOf("켜기", "끄기")) if (w.startsWith(typed)) o.suggest(w)
        }
        o.buildFuture()
    }

    private fun editNode() = Commands.literal("투명설정").requires(AdminText.perm(AdminInfoFeature.VANISHEDIT))
        .executes { ctx ->
            val me = AdminText.self(ctx)
            if (me == null) ess.messages.send(ctx.source.sender, "player-only") else VanishMenu(ess, feature, me, me.uniqueId, me.name).show()
            1
        }
        .then(
            ess.args.player("대상").executes { ctx -> edit(ctx, StringArgumentType.getString(ctx, "대상"), null); 1 }
                .then(
                    Commands.argument("옵션", StringArgumentType.greedyString()).suggests(optionSuggest)
                        .executes { ctx -> edit(ctx, StringArgumentType.getString(ctx, "대상"), StringArgumentType.getString(ctx, "옵션")); 1 },
                ),
        ).build()

    private fun edit(ctx: CommandContext<CommandSourceStack>, input: String, tail: String?) {
        val sender = ctx.source.sender
        val (id, name) = AdminText.offline(ess, sender, input) ?: return
        val me = sender as? Player
        if (me?.uniqueId != id && !sender.hasPermission(AdminInfoFeature.VANISHEDIT_OTHERS)) return ess.messages.send(sender, "no-permission")
        val words = com.inmc.essentials.command.Args.split(tail.orEmpty())
        if (words.isEmpty()) {
            if (me == null) return ess.messages.send(sender, "player-only")
            VanishMenu(ess, feature, me, id, name).show()
            return
        }
        val option = VanishOption.parse(words[0]) ?: return ess.messages.send(sender, "admininfo-vanishedit-unknown", Ph.of().value(AdminText.escape(words[0])))
        val value = words.getOrNull(1)?.let { VanishOption.parseState(it) ?: return ess.messages.send(sender, "admininfo-vanishedit-unknown", Ph.of().value(AdminText.escape(it))) }
            ?: !vanish.option(id, option)
        vanish.setOption(id, option, value)
        ess.messages.send(sender, "admininfo-vanishedit-set", Ph.of().target(AdminText.escape(name)).subject(option.label).value(if (value) "켜짐" else "꺼짐"))
    }
}
