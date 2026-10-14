package com.inmc.essentials.command

import com.inmc.essentials.Ess
import com.inmc.essentials.feature.Features
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * `/도움말 [쪽|명령]` (별칭 `ehelp`) — 이 플러그인의 명령을 기능별로(2026-10-15 사용자 요청 "플러그인 명령어 인게임 도움말").
 *
 * - 목록은 [CommandRegistry] 에 모인 명령 중 **보는 사람이 쓸 수 있는 것만**(노드의 `requires` 그대로 — 권한 규칙을 따로 적지 않는다).
 *   한 줄 = 이름 · 영어 별칭 · 등록할 때 적은 설명. 누르면 입력창에 그 명령.
 * - `/도움말 <명령>` 은 그 명령의 쓰는 법 전부(Brigadier `getAllUsage` — 쓸 수 있는 갈래만).
 * - 부를 때마다 등록부를 읽는다 — 켜다 실패해 빠진 기능의 명령은 나오지 않는다.
 */
class HelpCommand(private val ess: Ess) {

    fun build(): LiteralCommandNode<CommandSourceStack> {
        val names = SuggestionProvider<CommandSourceStack> { ctx, b ->
            val typed = b.remainingLowerCase
            usable(ctx.source).flatMap { listOf(it.node.literal) + it.aliases }.filter { it.lowercase().startsWith(typed) }.forEach { b.suggest(it) }
            b.buildFuture()
        }
        return Commands.literal(NAME)
            .executes { ctx -> list(ctx.source, 1); 1 }
            .then(Commands.argument("쪽|명령", Args.TOKEN).suggests(names).executes { ctx ->
                val arg = ctx.getArgument("쪽|명령", String::class.java).removePrefix("/")
                val page = arg.toIntOrNull()
                if (page != null) list(ctx.source, page) else detail(ctx.source, arg)
                1
            })
            .build()
    }

    private fun usable(source: CommandSourceStack): List<CommandRegistry.Entry> =
        ess.commands.all().filter { runCatching { it.node.canUse(source) }.getOrDefault(false) }

    private fun list(source: CommandSourceStack, page: Int) {
        val sender = source.sender
        val viewer = sender as? Player
        val entries = usable(source)
        if (entries.isEmpty()) return ess.messages.send(sender, "help-empty")
        val rows = rows(entries.map { Row(it.owner, it.node.literal, it.aliases, it.description) })
        val pages = pageCount(rows.size, PER_PAGE)
        val p = page.coerceIn(1, pages)
        ess.messages.send(sender, "help-header", Ph.of().count(entries.size).raw("{쪽}", p.toString()).raw("{전체}", pages.toString()))
        for (row in rows.drop((p - 1) * PER_PAGE).take(PER_PAGE)) {
            when (row) {
                // 묶음 머리줄은 접두 없이(줄마다 `[ 에센셜 ]` 이 붙으면 어수선하다) — 머리말 한 줄에만 접두.
                is Line.Group -> sender.sendMessage(ess.messages.component("help-group", Ph.of().value(row.label), viewer))
                is Line.Command -> sender.sendMessage(
                    ess.messages.component(
                        "help-line",
                        Ph.of().value(row.name).raw("{별칭}", if (row.aliases.isEmpty()) "" else row.aliases.joinToString(", ")).raw("{설명}", row.description),
                        viewer,
                    ).clickEvent(ClickEvent.suggestCommand("/${row.name} "))
                        .hoverEvent(HoverEvent.showText(ess.messages.component("help-line-hover", Ph.of().value(row.name), viewer))),
                )
            }
        }
        if (p == pages && sender.hasPermission(Ess.ADMIN)) ess.messages.send(sender, "help-admin-root")
        if (pages > 1) {
            var nav = net.kyori.adventure.text.Component.empty()
            if (p > 1) nav = nav.append(ess.messages.component("help-prev", null, viewer).clickEvent(ClickEvent.runCommand("/$NAME ${p - 1}")))
            if (p > 1 && p < pages) nav = nav.append(net.kyori.adventure.text.Component.text("   "))
            if (p < pages) nav = nav.append(ess.messages.component("help-next", null, viewer).clickEvent(ClickEvent.runCommand("/$NAME ${p + 1}")))
            sender.sendMessage(nav)
        }
    }

    private fun detail(source: CommandSourceStack, name: String) {
        val sender = source.sender
        val entry = find(usable(source), name) ?: return ess.messages.send(sender, "help-unknown", Ph.of().value(name))
        ess.messages.send(
            sender, "help-detail-header",
            Ph.of().value(entry.node.literal).raw("{별칭}", entry.aliases.joinToString(", ").ifEmpty { "-" }).raw("{설명}", entry.description),
        )
        val usages = CommandDispatcher<CommandSourceStack>().getAllUsage(entry.node, source, true).toList()
        for (usage in usages.take(MAX_USAGES)) {
            val line = ("/${entry.node.literal} $usage").trimEnd()
            sender.sendMessage(
                ess.messages.component("help-detail-usage", Ph.of().value(line), sender as? Player)
                    .clickEvent(ClickEvent.suggestCommand(suggestion(line))),
            )
        }
        if (usages.size > MAX_USAGES) ess.messages.send(sender, "help-detail-more", Ph.of().count(usages.size - MAX_USAGES))
    }

    /** 명령 줄 목록의 한 칸 — 서버 없이 시험하려고 [Row] 로 받는다. */
    data class Row(val owner: String?, val name: String, val aliases: List<String>, val description: String)

    sealed interface Line {
        data class Group(val label: String) : Line
        data class Command(val name: String, val aliases: List<String>, val description: String) : Line
    }

    companion object {
        const val NAME = "도움말"
        val ALIASES = listOf("ehelp")
        val NAMES: Set<String> = setOf(NAME) + ALIASES
        const val PER_PAGE = 12
        const val MAX_USAGES = 20

        /** 기능 id → 묶음 이름. 없는 id(기능 밖에서 넣은 명령)는 "기타". */
        val GROUPS: Map<String, String> = linkedMapOf(
            "teleport" to "순간이동", "homes" to "홈", "warps" to "워프", "spawn" to "스폰", "chat" to "채팅", "mail" to "우편",
            "player" to "플레이어", "afk" to "잠수", "items" to "아이템", "inventory" to "인벤토리", "kits" to "킷", "world" to "월드",
            "decor" to "꾸미기", "portals" to "포탈", "holograms" to "홀로그램", "commands" to "명령", "server" to "서버",
            "moderation" to "제재", "admininfo" to "관리 정보",
        )

        /** 기능 순서([Features.IDS])로 묶고 묶음 안은 이름순. 묶음마다 머리줄 하나. */
        fun rows(entries: List<Row>): List<Line> {
            val order = Features.IDS
            return entries.groupBy { it.owner }
                .toList()
                .sortedBy { (owner, _) -> order.indexOf(owner).let { if (it < 0) Int.MAX_VALUE else it } }
                .flatMap { (owner, list) ->
                    listOf<Line>(Line.Group(GROUPS[owner] ?: "기타")) +
                        list.sortedBy { it.name }.map { Line.Command(it.name, it.aliases, it.description) }
                }
        }

        fun pageCount(size: Int, per: Int): Int = maxOf(1, (size + per - 1) / per)

        /** 이름 또는 별칭(대소문자 무시)으로. */
        fun find(entries: List<CommandRegistry.Entry>, name: String): CommandRegistry.Entry? {
            val key = name.lowercase()
            return entries.firstOrNull { it.node.literal.lowercase() == key } ?: entries.firstOrNull { e -> e.aliases.any { it.lowercase() == key } }
        }

        /** 입력창에 넣을 글 — 첫 `<`·`[` 앞까지(인자 자리는 사람이 채운다). */
        fun suggestion(usageLine: String): String {
            val cut = usageLine.indexOfFirst { it == '<' || it == '[' || it == '(' }
            return if (cut < 0) "$usageLine " else usageLine.substring(0, cut)
        }
    }
}
