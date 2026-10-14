package com.inmc.essentials.warp

import com.inmc.essentials.Ess
import com.inmc.essentials.command.Args
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import kr.inmc.core.integration.Vanish
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * `/워프`(warp, warps) · `/워프설정`(setwarp) · `/워프삭제`(delwarp, removewarp) · `/워프편집`(editwarp) · `/워프그룹`(warpgroups) — 계획 4절 표.
 */
class WarpCommands(private val ess: Ess, private val service: WarpService) {

    private val store get() = service.store

    fun register() {
        ess.commands.add(
            Commands.literal("워프").requires { it.sender.hasPermission(WarpsFeature.USE) }
                .executes { ctx -> open(ctx.source.sender, null, 0); 1 }
                .then(Commands.argument("이름", StringArgumentType.greedyString()).suggests(warpNames(withPlayers = true))
                    .executes { ctx -> warp(ctx); 1 })
                .build(),
            "워프로 — 이름 없이 치면 목록", listOf("warp", "warps"),
        )
        ess.commands.add(
            Commands.literal("워프설정").requires { it.sender.hasPermission(WarpsFeature.SET) }
                .then(Commands.argument("내용", StringArgumentType.greedyString()).suggests(setHints)
                    .executes { ctx -> set(ctx); 1 })
                .executes { ctx -> ess.messages.send(ctx.source.sender, "warps-set-usage"); 1 }
                .build(),
            "선 자리에 워프 만들기", listOf("setwarp"),
        )
        ess.commands.add(
            Commands.literal("워프삭제").requires { it.sender.hasPermission(WarpsFeature.SET) || it.sender.hasPermission(WarpsFeature.ADMIN) }
                .executes { ctx -> pickToRemove(ctx.source.sender); 1 }
                .then(Commands.argument("이름", StringArgumentType.greedyString()).suggests(warpNames(editableOnly = true))
                    .executes { ctx -> remove(ctx); 1 })
                .build(),
            "워프 지우기", listOf("delwarp", "removewarp"),
        )
        ess.commands.add(
            Commands.literal("워프편집").requires { it.sender.hasPermission(WarpsFeature.SET) || it.sender.hasPermission(WarpsFeature.ADMIN) }
                .executes { ctx -> ess.messages.send(ctx.source.sender, "warps-edit-usage"); 1 }
                .then(Commands.argument("이름", StringArgumentType.greedyString()).suggests(warpNames(editableOnly = true))
                    .executes { ctx -> edit(ctx); 1 })
                .build(),
            "워프 편집 화면 · 이름 바꾸기", listOf("editwarp"),
        )
        ess.commands.add(
            Commands.literal("워프그룹").requires { it.sender.hasPermission(WarpsFeature.USE) }
                .executes { ctx -> groups(ctx.source.sender); 1 }
                .build(),
            "워프 그룹 목록", listOf("warpgroups"),
        )
    }

    // --- /워프 ---------------------------------------------------------------------------------

    private fun open(sender: CommandSender, group: String?, page: Int) {
        if (sender is Player && service.settings.gui) return WarpMenu(ess, sender, service, group, page).show()
        val warps = service.visible(sender, group)
        if (warps.isEmpty()) return ess.messages.send(sender, "warps-none")
        val entries = warps.joinToString(ess.messages.raw("warps-list-separator")) { w ->
            val key = if (service.canUse(sender, w)) "warps-list-entry" else "warps-list-entry-locked"
            Ph.of().raw("{워프}", shown(w.name)).raw("{명령}", quoted("/워프 ${w.name}")).value(w.describe()).apply(ess.messages.raw(key))
        }
        ess.messages.send(sender, "warps-list", Ph.of().raw("{목록}", entries).count(warps.size).raw("{그룹}", WarpMenu.groupLabel(group)))
    }

    private fun warp(ctx: CommandContext<CommandSourceStack>) {
        val sender = ctx.source.sender
        val tokens = Args.split(StringArgumentType.getString(ctx, "이름"))
        val first = tokens.firstOrNull() ?: return open(sender, null, 0)
        val lower = first.lowercase()
        if (lower.startsWith("-g:") || lower.startsWith("그룹:")) {
            val page = tokens.drop(1).firstNotNullOfOrNull { pageOf(it) } ?: 0
            return open(sender, first.substringAfter(':').trim(), page)
        }
        pageOf(first)?.let { return open(sender, null, it) }
        val ph = Ph.of().raw("{워프}", first)
        val warp = store.get(first)?.takeIf { !it.hidden || service.isAdmin(sender) || service.canUse(sender, it) }
            ?: return ess.messages.send(sender, "warps-not-found", ph)
        val silent = tokens.drop(1).any { it == "-s" }
        val targetInput = tokens.drop(1).firstOrNull { it != "-s" }
        val who = if (targetInput != null) {
            if (!sender.hasPermission(WarpsFeature.OTHERS)) return ess.messages.send(sender, "no-permission")
            ess.args.online(sender, targetInput) ?: return ess.messages.send(sender, "player-not-found", Ph.of().value(targetInput))
        } else {
            sender as? Player ?: return ess.messages.send(sender, "warps-console-target")
        }
        // 남을 보낼 때는 그 워프의 권한 요구를 건너뛴다(관리 명령 — CMI 와 같다). 자기 이동은 권한을 본다.
        if (who == sender && !service.canUse(sender, warp)) return ess.messages.send(sender, "warps-no-access", ph)
        service.go(sender, warp, label(ctx), who, silent)
    }

    private fun pageOf(token: String): Int? {
        val lower = token.lowercase()
        if (!lower.startsWith("-p:") && !lower.startsWith("쪽:")) return null
        return lower.substringAfter(':').trim().toIntOrNull()?.minus(1)?.coerceAtLeast(0)
    }

    // --- /워프설정 ------------------------------------------------------------------------------

    private fun set(ctx: CommandContext<CommandSourceStack>) {
        val player = player(ctx) ?: return
        val tokens = Args.split(StringArgumentType.getString(ctx, "내용"))
        val request = WarpRules.parseSet(tokens)
        val isNew = request.name?.let { store.get(it) } == null
        val retry = (listOf(label(ctx)) + tokens.filterNot { it.lowercase() in OVERWRITE_WORDS }).joinToString(" ")
        val warp = service.create(player, request, retry) ?: return
        if (isNew && service.settings.guiOnCreation) WarpEditMenu(ess, player, service, warp.name).show()
    }

    // --- /워프삭제 ------------------------------------------------------------------------------

    private fun remove(ctx: CommandContext<CommandSourceStack>) {
        val sender = ctx.source.sender
        val name = Args.split(StringArgumentType.getString(ctx, "이름")).firstOrNull() ?: return
        val ph = Ph.of().raw("{워프}", name)
        val warp = store.get(name) ?: return ess.messages.send(sender, "warps-not-found", ph)
        if (!service.canEdit(sender, warp)) return ess.messages.send(sender, "warps-not-yours", ph)
        if (service.remove(sender, warp.name)) ess.messages.send(sender, "warps-removed", Ph.of().raw("{워프}", warp.name))
    }

    private fun pickToRemove(sender: CommandSender) {
        val warps = store.all().filter { service.canEdit(sender, it) }.sortedBy { it.key }
        if (warps.isEmpty()) return ess.messages.send(sender, "warps-none")
        val entries = warps.joinToString(ess.messages.raw("warps-list-separator")) { w ->
            Ph.of().raw("{워프}", shown(w.name)).raw("{명령}", quoted("/워프삭제 ${w.name}")).apply(ess.messages.raw("warps-remove-entry"))
        }
        ess.messages.send(sender, "warps-remove-pick", Ph.of().raw("{목록}", entries))
    }

    // --- /워프편집 ------------------------------------------------------------------------------

    private fun edit(ctx: CommandContext<CommandSourceStack>) {
        val sender = ctx.source.sender
        val tokens = Args.split(StringArgumentType.getString(ctx, "이름"))
        val name = tokens.firstOrNull() ?: return
        val ph = Ph.of().raw("{워프}", name)
        val warp = store.get(name) ?: return ess.messages.send(sender, "warps-not-found", ph)
        if (!service.canEdit(sender, warp)) return ess.messages.send(sender, "warps-not-yours", ph)
        val next = tokens.getOrNull(1)
        if (next != null) {
            service.rename(sender, warp, next)
            return
        }
        val player = sender as? Player ?: return ess.messages.send(sender, "player-only")
        WarpEditMenu(ess, player, service, warp.name).show()
    }

    // --- /워프그룹 ------------------------------------------------------------------------------

    private fun groups(sender: CommandSender) {
        if (sender is Player && service.settings.gui) return WarpGroupMenu(ess, sender, service).show()
        val visible = service.visible(sender)
        val groups = WarpRules.groups(visible)
        if (groups.isEmpty()) return ess.messages.send(sender, "warps-groups-none")
        ess.messages.send(sender, "warps-groups-header", Ph.of().count(groups.size))
        for ((index, group) in groups.withIndex()) {
            val count = visible.count { WarpRules.inGroup(it, group) }
            ess.messages.send(sender, "warps-groups-entry", Ph.of().rank((index + 1).toString()).raw("{그룹}", shown(group)).count(count).raw("{명령}", quoted("/워프 그룹:$group")))
        }
    }

    // --- 도움 -----------------------------------------------------------------------------------

    /** 이름을 MiniMessage 글자 그대로(태그로 읽히지 않게). */
    private fun shown(name: String): String = MiniMessage.miniMessage().escapeTags(name)

    /** 홑따옴표 인자(`<click:run_command:'…'>`) 안에 넣을 글자 — 역빗금과 홑따옴표를 막는다. */
    private fun quoted(command: String): String = command.replace("\\", "\\\\").replace("'", "\\'")

    private fun player(ctx: CommandContext<CommandSourceStack>): Player? =
        (ctx.source.executor as? Player ?: ctx.source.sender as? Player) ?: null.also { ess.messages.send(ctx.source.sender, "player-only") }

    private fun label(ctx: CommandContext<CommandSourceStack>): String = ctx.input.removePrefix("/").substringBefore(' ')

    /** 첫 낱말 = 보이는 워프 이름(또는 고칠 수 있는 것만), 둘째 = 사람 이름(남 보내기 권한). */
    private fun warpNames(withPlayers: Boolean = false, editableOnly: Boolean = false) = SuggestionProvider<CommandSourceStack> { ctx, builder ->
        val sender = ctx.source.sender
        val remaining = builder.remaining
        val space = remaining.lastIndexOf(' ')
        if (space < 0) {
            val typed = remaining.lowercase()
            val warps = if (editableOnly) store.all().filter { service.canEdit(sender, it) } else service.visible(sender)
            for (w in warps) if (w.name.lowercase().startsWith(typed)) builder.suggest(w.name)
            if (!editableOnly && "그룹:".startsWith(typed)) for (g in WarpRules.groups(service.visible(sender))) builder.suggest("그룹:$g")
            builder.buildFuture()
        } else if (withPlayers && remaining.count { it == ' ' } == 1 && sender.hasPermission(WarpsFeature.OTHERS)) {
            val offset = builder.createOffset(builder.start + space + 1)
            val typed = remaining.substring(space + 1).lowercase()
            for (name in Vanish.visibleNames(sender)) if (name.lowercase().startsWith(typed)) offset.suggest(name)
            offset.buildFuture()
        } else builder.buildFuture()
    }

    private val setHints = SuggestionProvider<CommandSourceStack> { ctx, builder ->
        val remaining = builder.remaining
        val space = remaining.lastIndexOf(' ')
        if (space < 0) {
            val typed = remaining.lowercase()
            for (w in store.all()) if (service.canEdit(ctx.source.sender, w) && w.name.lowercase().startsWith(typed)) builder.suggest(w.name)
            return@SuggestionProvider builder.buildFuture()
        }
        val offset = builder.createOffset(builder.start + space + 1)
        val typed = remaining.substring(space + 1).lowercase()
        val groups = WarpRules.groups(store.all()).map { "그룹:$it" }
        for (hint in SET_HINTS + groups) if (hint.lowercase().startsWith(typed)) offset.suggest(hint)
        offset.buildFuture()
    }

    private companion object {
        val OVERWRITE_WORDS = setOf("-confirm", "-overwrite", "덮어쓰기")
        val SET_HINTS = listOf("true", "false", "손", "머리", "그룹:", "덮어쓰기")
    }
}
