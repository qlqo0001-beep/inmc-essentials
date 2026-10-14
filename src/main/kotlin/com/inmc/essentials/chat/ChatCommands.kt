package com.inmc.essentials.chat

import com.inmc.essentials.Ess
import com.inmc.essentials.command.Args
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import kr.inmc.core.integration.PlayerSettings
import kr.inmc.core.integration.Vanish
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID

/**
 * 계획 4절 표 그대로: 귓(msg, tell, w, m, whisper, 귓말, 귓속말) · 답장(r, reply) · 무시(ignore) · 도움요청(helpop) · 스태프(staffmsg, sc) ·
 * 스파이(spy) · 공지(broadcast, bc) · 채팅끄기(silence) · 타이틀(titlemsg).
 */
class ChatCommands(private val ess: Ess, private val feature: ChatFeature, private val service: ChatService) {

    fun register() {
        ess.commands.add(
            Commands.literal(ChatFeature.WHISPER).requires { it.sender.hasPermission(ChatFeature.MSG) }
                .executes { ctx -> ess.messages.send(ctx.source.sender, "chat-msg-usage"); 1 }
                .then(ess.args.player().suggests(whisperTargets)
                    .executes { ctx -> ess.messages.send(ctx.source.sender, "chat-msg-usage"); 1 }
                    .then(Commands.argument("메시지", StringArgumentType.greedyString())
                        .executes { ctx -> whisper(ctx); 1 }))
                .build(),
            "귓속말", listOf("msg", "tell", "w", "m", "whisper", "귓말", "귓속말"),
        )
        ess.commands.add(
            Commands.literal("답장").requires { it.sender.hasPermission(ChatFeature.REPLY) }
                .executes { ctx -> ess.messages.send(ctx.source.sender, "chat-reply-usage"); 1 }
                .then(Commands.argument("메시지", StringArgumentType.greedyString())
                    .executes { ctx -> service.reply(ctx.source.sender, StringArgumentType.getString(ctx, "메시지")); 1 })
                .build(),
            "마지막 대화 상대에게 답장", listOf("r", "reply"),
        )
        ess.commands.add(
            Commands.literal("무시").requires { it.sender.hasPermission(ChatFeature.IGNORE) }
                .executes { ctx -> player(ctx)?.let { list(it, it.uniqueId, it.name) }; 1 }
                .then(Commands.argument("대상", StringArgumentType.greedyString()).suggests(ignoreHints)
                    .executes { ctx -> ignore(ctx.source.sender, StringArgumentType.getString(ctx, "대상")); 1 })
                .build(),
            "플레이어 무시(귓속말·채팅·편지) — 다시 치면 풀기", listOf("ignore"),
        )
        ess.commands.add(
            Commands.literal("도움요청").requires { it.sender.hasPermission(ChatFeature.HELPOP) }
                .executes { ctx -> ess.messages.send(ctx.source.sender, "chat-helpop-usage"); 1 }
                .then(Commands.argument("메시지", StringArgumentType.greedyString())
                    .executes { ctx -> service.helpop(ctx.source.sender, StringArgumentType.getString(ctx, "메시지")); 1 })
                .build(),
            "접속한 운영진에게 도움 요청", listOf("helpop"),
        )
        ess.commands.add(
            Commands.literal("스태프").requires { it.sender.hasPermission(ChatFeature.STAFF) }
                .executes { ctx -> player(ctx)?.let { toggleStaff(it, null) }; 1 }
                .then(Commands.argument("메시지", StringArgumentType.greedyString()).suggests(staffHints)
                    .executes { ctx -> staff(ctx.source.sender, StringArgumentType.getString(ctx, "메시지")); 1 })
                .build(),
            "스태프 채널 — 글 또는 켜기/끄기(채팅이 전부 스태프로)", listOf("staffmsg", "sc"),
        )
        ess.commands.add(
            Commands.literal("스파이").requires { it.sender.hasPermission(ChatFeature.SPY) }
                .executes { ctx -> player(ctx)?.let { status(it, it) }; 1 }
                .then(Commands.argument("내용", StringArgumentType.greedyString()).suggests(spyHints)
                    .executes { ctx -> spy(ctx.source.sender, StringArgumentType.getString(ctx, "내용")); 1 })
                .build(),
            "명령·표지판·귓속말 스파이 켜고 끄기", listOf("spy"),
        )
        ess.commands.add(
            Commands.literal("공지").requires { it.sender.hasPermission(ChatFeature.BROADCAST) }
                .executes { ctx -> ess.messages.send(ctx.source.sender, "chat-broadcast-usage"); 1 }
                .then(Commands.argument("메시지", StringArgumentType.greedyString())
                    .executes { ctx -> broadcast(ctx.source.sender, StringArgumentType.getString(ctx, "메시지")); 1 })
                .build(),
            "서버 공지(-w:월드 -r:거리 -c:월드;x;y;z, ! = 머리말 없이)", listOf("broadcast", "bc"),
        )
        ess.commands.add(
            Commands.literal("채팅끄기").requires { it.sender.hasPermission(ChatFeature.SILENCE_USE) }
                .executes { ctx -> player(ctx)?.let(::silence); 1 }
                .build(),
            "내 화면에서만 공개 채팅 끄고 켜기", listOf("silence"),
        )
        ess.commands.add(
            Commands.literal("타이틀").requires { it.sender.hasPermission(ChatFeature.TITLE) }
                .executes { ctx -> ess.messages.send(ctx.source.sender, "chat-title-usage"); 1 }
                .then(ess.args.players()
                    .then(Commands.argument("내용", StringArgumentType.greedyString())
                        .executes { ctx -> title(ctx); 1 }))
                .build(),
            "화면 가운데 제목·부제목(\\n 으로 나눔, -in:/-keep:/-out: 틱)", listOf("titlemsg"),
        )
    }

    // --- 귓속말 ------------------------------------------------------------------------------------

    private fun whisper(ctx: CommandContext<CommandSourceStack>) {
        val sender = ctx.source.sender
        val input = StringArgumentType.getString(ctx, "플레이어")
        // 이름이 정확히 같은 (보이는) 접속자가 먼저 — 'Server'·'Console' 이라는 플레이어에게도 보낼 수 있게. 그다음 콘솔 낱말, 그다음 이름 찾기.
        val exact = Bukkit.getPlayerExact(input)?.takeIf { Vanish.canSee(sender, it) }
        val target: CommandSender = when {
            exact != null -> exact
            input.lowercase() in CONSOLE_NAMES -> Bukkit.getConsoleSender()
            else -> ess.args.online(sender, input) ?: return ess.messages.send(sender, "player-not-found", Ph.of().value(input))
        }
        service.whisper(sender, target, StringArgumentType.getString(ctx, "메시지"))
    }

    /** 보이는 접속자 + 콘솔. */
    private val whisperTargets = SuggestionProvider<CommandSourceStack> { ctx, builder ->
        val typed = builder.remainingLowerCase
        for (name in Vanish.visibleNames(ctx.source.sender)) if (name.lowercase().startsWith(typed)) builder.suggest(name)
        if ("콘솔".startsWith(typed)) builder.suggest("콘솔")
        builder.buildFuture()
    }

    // --- 무시 --------------------------------------------------------------------------------------

    private fun ignore(sender: CommandSender, text: String) {
        val tokens = Args.split(text)
        var ownerId: UUID? = (sender as? Player)?.uniqueId
        var ownerName = sender.name
        val rest = ArrayList<String>()
        for (token in tokens) {
            if (token.lowercase().startsWith("-p:")) {
                if (!sender.hasPermission(ChatFeature.IGNORE_OTHERS)) return ess.messages.send(sender, "no-permission")
                val input = token.substring(3)
                val record = ess.args.offline(sender, input) ?: return ess.messages.send(sender, "user-not-found", Ph.of().value(input))
                ownerId = record.id
                ownerName = record.name
            } else rest += token
        }
        val owner = ownerId ?: return ess.messages.send(sender, "player-only")
        val input = rest.firstOrNull() ?: return list(sender, owner, ownerName)
        val (target, targetName) = when {
            Args.isAll(input) -> ChatStore.ALL to ""
            else -> {
                val uuid = runCatching { UUID.fromString(input) }.getOrNull()
                val record = if (uuid != null) ess.users.get(uuid) else ess.args.offline(sender, input)
                when {
                    record != null -> record.id.toString() to record.name
                    uuid != null -> uuid.toString() to uuid.toString()
                    else -> return ess.messages.send(sender, "user-not-found", Ph.of().value(input))
                }
            }
        }
        if (target == owner.toString()) return ess.messages.send(sender, "chat-ignore-self")
        val store = feature.store
        val ph = Ph.of().target(targetName).player(ownerName)
        if (target in store.ignoring(owner)) {
            store.removeIgnore(owner, target)
            ess.messages.send(sender, if (target == ChatStore.ALL) "chat-ignore-removed-all" else "chat-ignore-removed", ph)
        } else {
            store.addIgnore(owner, target)
            ess.messages.send(sender, if (target == ChatStore.ALL) "chat-ignore-added-all" else "chat-ignore-added", ph)
            // 볼 수 있는 접속자일 때만 — 투명인 운영진이면 이 안내로 접속 여부가 샌다(CLAUDE.md 8).
            val online = runCatching { UUID.fromString(target) }.getOrNull()?.let(Bukkit::getPlayer)?.takeIf { Vanish.canSee(sender, it) }
            if (online != null && online.hasPermission(ChatFeature.IGNORE_BYPASS)) ess.messages.send(sender, "chat-ignore-bypass-note", ph)
        }
    }

    private fun list(sender: CommandSender, owner: UUID, ownerName: String) {
        val set = feature.store.ignoring(owner)
        if (set.isEmpty()) return ess.messages.send(sender, "chat-ignore-empty", Ph.of().player(ownerName))
        ess.messages.send(sender, "chat-ignore-list-header", Ph.of().player(ownerName).count(set.size))
        for ((index, raw) in set.sorted().withIndex()) {
            val name = if (raw == ChatStore.ALL) ess.messages.raw("chat-ignore-all-name")
            else runCatching { UUID.fromString(raw) }.getOrNull()?.let { ess.users.get(it)?.name } ?: raw
            val command = if (raw == ChatStore.ALL) "/무시 all" else "/무시 $name"
            ess.messages.send(sender, "chat-ignore-list-entry", Ph.of().rank("${index + 1}").target(name).raw("{명령}", command))
        }
    }

    private val ignoreHints = SuggestionProvider<CommandSourceStack> { ctx, builder ->
        val typed = builder.remaining.substringAfterLast(' ').lowercase()
        val head = builder.remaining.substring(0, builder.remaining.length - typed.length)
        val sender = ctx.source.sender
        val own = (sender as? Player)?.uniqueId?.let { feature.store.ignoring(it) }.orEmpty()
            .mapNotNull { raw -> if (raw == ChatStore.ALL) "all" else runCatching { UUID.fromString(raw) }.getOrNull()?.let { ess.users.get(it)?.name } }
        for (name in (Vanish.visibleNames(sender) + own + "all").distinct()) if (name.lowercase().startsWith(typed)) builder.suggest(head + name)
        builder.buildFuture()
    }

    // --- 스태프 ------------------------------------------------------------------------------------

    private fun staff(sender: CommandSender, text: String) {
        val word = text.trim().lowercase()
        val mode = when (word) {
            in ON_WORDS -> true
            in OFF_WORDS -> false
            in TOGGLE_WORDS -> null
            else -> return service.staff(sender, text)
        }
        val player = sender as? Player ?: return ess.messages.send(sender, "player-only")
        toggleStaff(player, mode)
    }

    private fun toggleStaff(player: Player, mode: Boolean?) {
        val on = mode ?: (player.uniqueId !in feature.staffMode)
        if (on) feature.staffMode += player.uniqueId else feature.staffMode -= player.uniqueId
        ess.messages.send(player, if (on) "chat-staff-on" else "chat-staff-off")
    }

    private val staffHints = SuggestionProvider<CommandSourceStack> { _, builder ->
        val typed = builder.remainingLowerCase
        for (word in listOf("켜기", "끄기", "토글")) if (word.startsWith(typed)) builder.suggest(word)
        builder.buildFuture()
    }

    // --- 스파이 ------------------------------------------------------------------------------------

    private fun spy(sender: CommandSender, text: String) {
        val tokens = Args.split(text)
        val kinds = tokens.firstOrNull()?.let { SPY_KINDS[it.lowercase()] } ?: return ess.messages.send(sender, "chat-spy-unknown-kind", Ph.of().value(tokens.firstOrNull().orEmpty()))
        var state: Boolean? = null
        var who: Player? = null
        for (token in tokens.drop(1)) {
            val lower = token.lowercase()
            when {
                lower in ON_WORDS -> state = true
                lower in OFF_WORDS -> state = false
                lower in TOGGLE_WORDS -> state = null
                else -> {
                    if (!sender.hasPermission(ChatFeature.SPY_OTHERS)) return ess.messages.send(sender, "no-permission")
                    who = ess.args.online(sender, token) ?: return ess.messages.send(sender, "player-not-found", Ph.of().value(token))
                }
            }
        }
        val target = who ?: (sender as? Player) ?: return ess.messages.send(sender, "player-only")
        val store = feature.store
        val on = state ?: kinds.any { !store.spying(target.uniqueId, it) }
        for (kind in kinds) store.setSpy(target.uniqueId, kind, on)
        val names = kinds.joinToString(", ") { kindName(it) }
        val ph = Ph.of().value(names).player(service.nameOf(target))
        if (target != sender) ess.messages.send(sender, if (on) "chat-spy-other-on" else "chat-spy-other-off", ph)
        ess.messages.send(target, if (on) "chat-spy-on" else "chat-spy-off", ph)
        if (!target.hasPermission(ChatFeature.SPY)) ess.messages.send(sender, "chat-spy-no-permission-note", ph)
    }

    private fun status(sender: CommandSender, target: Player) {
        ess.messages.send(sender, "chat-spy-status-header", Ph.of().player(service.nameOf(target)))
        for (kind in ChatStore.Spy.entries) {
            val on = feature.store.spying(target.uniqueId, kind)
            ess.messages.send(sender, "chat-spy-status-line", Ph.of().value(kindName(kind)).raw("{상태}", ess.messages.raw(if (on) "chat-state-on" else "chat-state-off"))
                .raw("{명령}", "/스파이 ${KIND_WORD.getValue(kind)} 토글"))
        }
    }

    private fun kindName(kind: ChatStore.Spy): String = ess.messages.raw(
        when (kind) {
            ChatStore.Spy.COMMAND -> "chat-spy-kind-command"
            ChatStore.Spy.SIGN -> "chat-spy-kind-sign"
            ChatStore.Spy.CHAT -> "chat-spy-kind-chat"
        },
    )

    private val spyHints = SuggestionProvider<CommandSourceStack> { ctx, builder ->
        val remaining = builder.remaining
        val parts = remaining.split(' ')
        val typed = parts.last().lowercase()
        val head = remaining.substring(0, remaining.length - parts.last().length)
        val options = when (parts.size) {
            1 -> listOf("명령", "표지판", "채팅", "전체")
            2 -> listOf("켜기", "끄기", "토글")
            3 -> if (ctx.source.sender.hasPermission(ChatFeature.SPY_OTHERS)) Vanish.visibleNames(ctx.source.sender) else emptyList()
            else -> emptyList()
        }
        for (option in options) if (option.lowercase().startsWith(typed)) builder.suggest(head + option)
        builder.buildFuture()
    }

    // --- 공지 · 채팅끄기 · 타이틀 ---------------------------------------------------------------------------

    private fun broadcast(sender: CommandSender, text: String) {
        val count = service.broadcast(sender, text)
        if (count >= 0 && sender !is Player) ess.messages.send(sender, "chat-broadcast-sent", Ph.of().count(count))
    }

    private fun silence(player: Player) {
        val on = !PlayerSettings.enabled(player, ChatFeature.SILENCE, false)
        PlayerSettings.set(player, ChatFeature.SILENCE, on)
        ess.messages.send(player, if (on) "chat-silence-on" else "chat-silence-off")
    }

    private fun title(ctx: CommandContext<CommandSourceStack>) {
        val sender = ctx.source.sender
        val input = StringArgumentType.getString(ctx, "대상")
        val targets = ess.args.targets(sender, input) ?: return ess.messages.send(sender, "player-not-found", Ph.of().value(input))
        if (!service.title(targets, StringArgumentType.getString(ctx, "내용"))) return ess.messages.send(sender, "chat-title-usage")
        ess.messages.send(sender, "chat-title-sent", Ph.of().count(targets.size))
    }

    // --- 공용 --------------------------------------------------------------------------------------

    private fun player(ctx: CommandContext<CommandSourceStack>): Player? =
        (ctx.source.executor as? Player ?: ctx.source.sender as? Player) ?: null.also { ess.messages.send(ctx.source.sender, "player-only") }

    companion object {
        val CONSOLE_NAMES = setOf("콘솔", "console", "server", "서버")
        val ON_WORDS = setOf("켜기", "켬", "on", "enable", "true")
        val OFF_WORDS = setOf("끄기", "끔", "off", "disable", "false")
        val TOGGLE_WORDS = setOf("토글", "toggle")

        private val ALL_KINDS = ChatStore.Spy.entries.toList()

        /** 낱말 → 스파이 종류. CMI 옵션 이름(cmdSpy·signSpy·chatSpy)도. */
        val SPY_KINDS: Map<String, List<ChatStore.Spy>> = mapOf(
            "명령" to listOf(ChatStore.Spy.COMMAND), "명령어" to listOf(ChatStore.Spy.COMMAND), "command" to listOf(ChatStore.Spy.COMMAND),
            "cmd" to listOf(ChatStore.Spy.COMMAND), "cmdspy" to listOf(ChatStore.Spy.COMMAND),
            "표지판" to listOf(ChatStore.Spy.SIGN), "sign" to listOf(ChatStore.Spy.SIGN), "signspy" to listOf(ChatStore.Spy.SIGN),
            "채팅" to listOf(ChatStore.Spy.CHAT), "귓속말" to listOf(ChatStore.Spy.CHAT), "chat" to listOf(ChatStore.Spy.CHAT),
            "chatspy" to listOf(ChatStore.Spy.CHAT), "socialspy" to listOf(ChatStore.Spy.CHAT),
            "전체" to ALL_KINDS, "all" to ALL_KINDS,
        )

        private val KIND_WORD = mapOf(ChatStore.Spy.COMMAND to "명령", ChatStore.Spy.SIGN to "표지판", ChatStore.Spy.CHAT to "채팅")
    }
}
