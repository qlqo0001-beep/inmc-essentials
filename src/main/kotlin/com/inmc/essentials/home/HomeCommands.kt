package com.inmc.essentials.home

import com.inmc.essentials.Ess
import com.inmc.essentials.command.Args
import com.inmc.essentials.store.UserRecord
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import kr.inmc.core.integration.Vanish
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * `/홈`(home) · `/홈설정`(sethome) · `/홈삭제`(delhome, removehome) · `/홈목록`(homes) — 계획 4절 표 그대로.
 * 이름 인자는 한글이라 `greedyString()` 꼬리를 쪼갠다(`Args.split`).
 */
class HomeCommands(private val ess: Ess, private val service: HomeService) {

    private val store get() = service.store

    fun register() {
        ess.commands.add(
            Commands.literal("홈").requires { it.sender.hasPermission(HomesFeature.USE) }
                .executes { ctx -> player(ctx)?.let { open(it, label(ctx)) }; 1 }
                .then(Commands.argument("이름", StringArgumentType.greedyString()).suggests(homeNames)
                    .executes { ctx -> go(ctx); 1 })
                .build(),
            "홈으로 — 하나면 바로, 여럿이면 화면", listOf("home"),
        )
        ess.commands.add(
            Commands.literal("홈설정").requires { it.sender.hasPermission(HomesFeature.USE) }
                .executes { ctx -> set(ctx, ""); 1 }
                .then(Commands.argument("내용", StringArgumentType.greedyString()).suggests(setHints)
                    .executes { ctx -> set(ctx, StringArgumentType.getString(ctx, "내용")); 1 })
                .build(),
            "선 자리를 홈으로", listOf("sethome"),
        )
        ess.commands.add(
            Commands.literal("홈삭제").requires { it.sender.hasPermission(HomesFeature.USE) }
                .executes { ctx -> player(ctx)?.let { pickToRemove(it) }; 1 }
                .then(Commands.argument("이름", StringArgumentType.greedyString()).suggests(homeNames)
                    .executes { ctx -> remove(ctx); 1 })
                .build(),
            "홈 지우기", listOf("delhome", "removehome"),
        )
        ess.commands.add(
            Commands.literal("홈목록").requires { it.sender.hasPermission(HomesFeature.USE) }
                .executes { ctx -> player(ctx)?.let { list(it, it.uniqueId, it.name) }; 1 }
                .then(Commands.argument("대상", StringArgumentType.greedyString()).suggests(listHints)
                    .executes { ctx -> listOther(ctx); 1 })
                .build(),
            "홈 목록 · 남의 홈 · 근처 홈(near:반경)", listOf("homes"),
        )
    }

    // --- /홈 -----------------------------------------------------------------------------------

    private fun open(player: Player, label: String) {
        val homes = store.of(player.uniqueId)
        when {
            homes.isEmpty() -> ess.messages.send(player, "homes-none")
            homes.size == 1 -> service.go(player, homes[0], label, player)
            else -> list(player, player.uniqueId, player.name)
        }
    }

    private fun go(ctx: CommandContext<CommandSourceStack>) {
        val sender = ctx.source.sender
        val (name, ownerInput, whoInput) = HomeArgs.parseGo(Args.split(StringArgumentType.getString(ctx, "이름"))) ?: return
        val owner = if (ownerInput != null) {
            if (!sender.hasPermission(HomesFeature.OTHERS) && !sender.hasPermission(HomesFeature.VISIT)) return ess.messages.send(sender, "no-permission")
            ess.args.offline(sender, ownerInput) ?: return ess.messages.send(sender, "user-not-found", Ph.of().value(ownerInput))
        } else {
            val self = player(ctx) ?: return
            ess.users.get(self.uniqueId) ?: UserRecord(self.uniqueId, self.name, 0L, 0L)
        }
        val ph = Ph.of().raw("{홈}", name).player(owner.name)
        val home = store.get(owner.id, name) ?: return ess.messages.send(sender, "homes-not-found", ph)
        service.accessProblem(sender, home)?.let { key -> return ess.messages.send(sender, key, ph) }
        val who = if (whoInput != null) {
            if (!sender.hasPermission(HomesFeature.OTHERS)) return ess.messages.send(sender, "no-permission")
            ess.args.online(sender, whoInput) ?: return ess.messages.send(sender, "player-not-found", Ph.of().value(whoInput))
        } else {
            sender as? Player ?: return ess.messages.send(sender, "homes-console-who")
        }
        service.go(sender, home, label(ctx), who)
    }

    // --- /홈설정 -------------------------------------------------------------------------------

    private fun set(ctx: CommandContext<CommandSourceStack>, text: String) {
        val player = player(ctx) ?: return
        val tokens = Args.split(text)
        val request = HomeArgs.parseSet(tokens)
        var ownerId = player.uniqueId
        var ownerName = player.name
        request.owner?.let { input ->
            if (!player.hasPermission(HomesFeature.OTHERS)) return ess.messages.send(player, "no-permission")
            val record = ess.args.offline(player, input) ?: return ess.messages.send(player, "user-not-found", Ph.of().value(input))
            ownerId = record.id
            ownerName = record.name
        }
        val location = request.location?.let { raw ->
            if (!player.hasPermission(HomesFeature.CUSTOM_LOCATION) && !player.hasPermission(HomeRules.LEGACY_CUSTOM_LOCATION)) {
                return ess.messages.send(player, "no-permission")
            }
            parseLocation(raw) ?: return ess.messages.send(player, "homes-location-invalid", Ph.of().value(raw))
        } ?: player.location
        val retry = (listOf(label(ctx)) + tokens.filterNot { it.lowercase() in OVERWRITE_WORDS }).joinToString(" ")
        service.create(player, ownerId, ownerName, request, location, retry)
    }

    private fun parseLocation(raw: String): Location? {
        val parts = HomeArgs.parseLocation(raw) ?: return null
        val world = Bukkit.getWorld(parts[0]) ?: return null
        return Location(world, parts[1].toDouble(), parts[2].toDouble(), parts[3].toDouble(),
            parts.getOrNull(4)?.toFloat() ?: 0f, parts.getOrNull(5)?.toFloat() ?: 0f)
    }

    // --- /홈삭제 -------------------------------------------------------------------------------

    private fun remove(ctx: CommandContext<CommandSourceStack>) {
        val sender = ctx.source.sender
        val tokens = Args.split(StringArgumentType.getString(ctx, "이름"))
        val name = tokens.firstOrNull() ?: return
        val ownerInput = tokens.getOrNull(1)
        val owner = if (ownerInput != null) {
            if (!sender.hasPermission(HomesFeature.OTHERS)) return ess.messages.send(sender, "no-permission")
            ess.args.offline(sender, ownerInput) ?: return ess.messages.send(sender, "user-not-found", Ph.of().value(ownerInput))
        } else {
            val self = player(ctx) ?: return
            UserRecord(self.uniqueId, self.name, 0L, 0L)
        }
        val ph = Ph.of().raw("{홈}", name).player(owner.name)
        if (!store.remove(owner.id, name)) return ess.messages.send(sender, "homes-not-found", ph)
        ess.messages.send(sender, if (ownerInput != null) "homes-removed-other" else "homes-removed", ph)
    }

    private fun pickToRemove(player: Player) {
        val homes = HomeRules.sorted(store.of(player.uniqueId))
        if (homes.isEmpty()) return ess.messages.send(player, "homes-none")
        val entries = joinEntries(homes) { h -> Ph.of().raw("{홈}", shown(h.name)).raw("{명령}", quoted("/홈삭제 ${h.name}")).apply(ess.messages.raw("homes-remove-entry")) }
        ess.messages.send(player, "homes-remove-pick", Ph.of().raw("{목록}", entries))
    }

    // --- /홈목록 -------------------------------------------------------------------------------

    /** 화면(설정이 켜졌고 플레이어면) 또는 채팅 목록. */
    private fun list(viewer: CommandSender, ownerId: java.util.UUID, ownerName: String) {
        val homes = HomeRules.sorted(store.of(ownerId))
        val self = viewer is Player && viewer.uniqueId == ownerId
        if (viewer is Player && service.settings.guiUse && (homes.isNotEmpty() || !self)) {
            return HomeMenu(ess, viewer, service, ownerId, ownerName).show()
        }
        if (homes.isEmpty()) return ess.messages.send(viewer, if (self) "homes-none" else "homes-none-other", Ph.of().player(ownerName))
        val suffix = if (self) "" else " $ownerName"
        val entries = joinEntries(homes) { h ->
            val key = if (h.private) "homes-list-entry-private" else "homes-list-entry"
            Ph.of().raw("{홈}", shown(h.name)).raw("{명령}", quoted("/홈 ${h.name}$suffix")).value(h.describe()).apply(ess.messages.raw(key))
        }
        val limit = if (self) service.limitText(service.limitOf(viewer as Player)) else "-"
        ess.messages.send(viewer, if (self) "homes-list" else "homes-list-other",
            Ph.of().raw("{목록}", entries).count(service.counted(ownerId)).value(limit).player(ownerName))
    }

    private fun listOther(ctx: CommandContext<CommandSourceStack>) {
        val sender = ctx.source.sender
        val input = StringArgumentType.getString(ctx, "대상").trim()
        if (input.lowercase().startsWith(NEAR)) return near(sender, input.substring(NEAR.length))
        if (!sender.hasPermission(HomesFeature.OTHERS)) return ess.messages.send(sender, "no-permission")
        val record = ess.args.offline(sender, input) ?: return ess.messages.send(sender, "user-not-found", Ph.of().value(input))
        list(sender, record.id, record.name)
    }

    /** `near:반경` — 같은 월드에서 가까운 홈(모든 사람의 것, CMI `/homes near:`). 관리자용. */
    private fun near(sender: CommandSender, rawRange: String) {
        if (!sender.hasPermission(HomesFeature.NEAR)) return ess.messages.send(sender, "no-permission")
        val player = sender as? Player ?: return ess.messages.send(sender, "player-only")
        val range = rawRange.trim().toIntOrNull()?.coerceIn(1, MAX_NEAR) ?: return ess.messages.send(sender, "invalid-number", Ph.of().value(rawRange))
        val here = player.location
        val found = store.all()
            .filter { it.world.equals(here.world.name, ignoreCase = true) }
            .map { it to sqrt((it.x - here.x) * (it.x - here.x) + (it.z - here.z) * (it.z - here.z)) }
            .filter { it.second <= range }
            .sortedBy { it.second }
            .take(NEAR_SHOWN)
            .toList()
        if (found.isEmpty()) return ess.messages.send(sender, "homes-near-none", Ph.of().value(range.toString()))
        ess.messages.send(sender, "homes-near-header", Ph.of().value(range.toString()).count(found.size))
        for ((index, pair) in found.withIndex()) {
            val (home, distance) = pair
            val ownerName = ess.users.get(home.owner)?.name ?: home.owner.toString().take(8)
            ess.messages.send(sender, "homes-near-entry", Ph.of().rank((index + 1).toString()).player(ownerName).raw("{홈}", shown(home.name))
                .value(distance.toInt().toString()).raw("{방향}", direction(here, home)).raw("{명령}", quoted("/홈 ${home.name} $ownerName")))
        }
    }

    private fun direction(from: Location, home: Home): String {
        val angle = Math.toDegrees(atan2(home.z - from.z, home.x - from.x))
        // 마크 좌표: +x 동, +z 남.
        val dirs = listOf("동", "남동", "남", "남서", "서", "북서", "북", "북동")
        val index = Math.floorMod(Math.round(angle / 45.0).toInt(), 8)
        return dirs[index]
    }

    // --- 도움 -----------------------------------------------------------------------------------

    /** 이름을 MiniMessage 글자 그대로(태그로 읽히지 않게) — 운영자가 name-pattern 을 넓혔을 때도 채팅 문구가 깨지지 않게. */
    private fun shown(name: String): String = MiniMessage.miniMessage().escapeTags(name)

    /** 홑따옴표 인자(`<click:run_command:'…'>`) 안에 넣을 글자 — `\` 와 `'` 를 막는다. */
    private fun quoted(command: String): String = command.replace("\\", "\\\\").replace("'", "\\'")

    private fun joinEntries(homes: List<Home>, entry: (Home) -> String): String =
        homes.joinToString(ess.messages.raw("homes-list-separator")) { entry(it) }

    private fun player(ctx: CommandContext<CommandSourceStack>): Player? =
        (ctx.source.executor as? Player ?: ctx.source.sender as? Player) ?: null.also { ess.messages.send(ctx.source.sender, "player-only") }

    private fun label(ctx: CommandContext<CommandSourceStack>): String = ctx.input.removePrefix("/").substringBefore(' ')

    /** 첫 낱말 = 자기 홈 이름, 둘째 = 사람 이름(남의 홈을 볼 수 있으면). */
    private val homeNames = SuggestionProvider<CommandSourceStack> { ctx, builder ->
        val sender = ctx.source.sender
        val remaining = builder.remaining
        val space = remaining.lastIndexOf(' ')
        if (space < 0) {
            val typed = remaining.lowercase()
            (sender as? Player)?.let { p -> for (h in store.of(p.uniqueId)) if (h.name.lowercase().startsWith(typed)) builder.suggest(h.name) }
            builder.buildFuture()
        } else if (remaining.count { it == ' ' } <= 2 && (sender.hasPermission(HomesFeature.OTHERS) || sender.hasPermission(HomesFeature.VISIT))) {
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
            val own = (ctx.source.sender as? Player)?.let { store.of(it.uniqueId).map { h -> h.name } }.orEmpty()
            for (name in own + service.settings.defaultName) if (name.lowercase().startsWith(typed)) builder.suggest(name)
            return@SuggestionProvider builder.buildFuture()
        }
        val offset = builder.createOffset(builder.start + space + 1)
        val typed = remaining.substring(space + 1).lowercase()
        for (hint in SET_HINTS) if (hint.startsWith(typed)) offset.suggest(hint)
        offset.buildFuture()
    }

    private val listHints = SuggestionProvider<CommandSourceStack> { ctx, builder ->
        val sender = ctx.source.sender
        val typed = builder.remainingLowerCase
        if (sender.hasPermission(HomesFeature.NEAR) && NEAR.startsWith(typed)) builder.suggest("${NEAR}100")
        if (sender.hasPermission(HomesFeature.OTHERS)) for (name in Vanish.visibleNames(sender)) if (name.lowercase().startsWith(typed)) builder.suggest(name)
        builder.buildFuture()
    }

    private companion object {
        const val NEAR = "near:"
        const val MAX_NEAR = 10_000
        const val NEAR_SHOWN = 20
        val OVERWRITE_WORDS = setOf("-overwrite", "-override", "덮어쓰기", "-o")
        val SET_HINTS = listOf("비공개", "덮어쓰기", "손", "주인:", "위치:")
    }
}
