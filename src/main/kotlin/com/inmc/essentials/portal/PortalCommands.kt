package com.inmc.essentials.portal

import com.inmc.essentials.Ess
import com.inmc.essentials.command.Args
import com.inmc.essentials.service.TeleportKind
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * `/포탈`(portals) — 계획 4절 표. 하위 명령은 한글 + 영어(CMI `new/nearest/forceupdate/setlocation/enabled`) 둘 다.
 * 전부 관리자(`inmcessentials.portals.admin`). 이름은 빈칸 없는 한 낱말(`Args.TOKEN` — 한글도 읽는다).
 */
class PortalCommands(private val ess: Ess, private val service: PortalService) {

    private val store get() = service.store

    fun register() {
        val root = Commands.literal("포탈").requires { it.sender.hasPermission(PortalsFeature.ADMIN) }
            .executes { ctx -> list(ctx.source.sender); 1 }
        sub(root, "목록", "list") { it.executes { ctx -> list(ctx.source.sender); 1 } }
        sub(root, "도움", "help") { it.executes { ctx -> ess.messages.send(ctx.source.sender, "portals-usage"); 1 } }
        sub(root, "만들기", "new") { it.then(name().executes { ctx -> create(ctx); 1 }) }
        sub(root, "위치1", "pos1") { it.executes { ctx -> corner(ctx, 0); 1 } }
        sub(root, "위치2", "pos2") { it.executes { ctx -> corner(ctx, 1); 1 } }
        sub(root, "영역", "redefine") { it.then(name().executes { ctx -> redefine(ctx); 1 }) }
        sub(root, "목적지", "setlocation") {
            it.then(name().executes { ctx -> target(ctx, null); 1 }
                .then(Commands.argument("위치", StringArgumentType.greedyString()).suggests(HERE_OR_CLEAR)
                    .executes { ctx -> target(ctx, StringArgumentType.getString(ctx, "위치")); 1 }))
        }
        sub(root, "안전위치", "safeloc") {
            it.then(name().executes { ctx -> safe(ctx, null); 1 }
                .then(Commands.argument("위치", StringArgumentType.greedyString()).suggests(HERE_OR_CLEAR)
                    .executes { ctx -> safe(ctx, StringArgumentType.getString(ctx, "위치")); 1 }))
        }
        sub(root, "켜기", "enable") { it.then(name().executes { ctx -> enable(ctx, true); 1 }) }
        sub(root, "끄기", "disable") { it.then(name().executes { ctx -> enable(ctx, false); 1 }) }
        sub(root, "켜고끄기", "enabled") { it.then(name().executes { ctx -> enable(ctx, null); 1 }) }
        sub(root, "가까이", "nearest") { it.executes { ctx -> nearest(ctx); 1 } }
        sub(root, "정보", "info") { it.then(name().executes { ctx -> info(ctx.source.sender, portal(ctx) ?: return@executes 1); 1 }) }
        sub(root, "편집", "edit") { it.then(name().executes { ctx -> edit(ctx); 1 }) }
        sub(root, "삭제", "remove") { it.then(name().executes { ctx -> remove(ctx); 1 }) }
        sub(root, "이동", "tp") {
            it.then(name().executes { ctx -> teleport(ctx, false); 1 }
                .then(Commands.literal("목적지").executes { ctx -> teleport(ctx, true); 1 })
                .then(Commands.literal("target").executes { ctx -> teleport(ctx, true); 1 }))
        }
        sub(root, "명령", "commands") {
            val arg = name().executes { ctx -> commands(ctx.source.sender, portal(ctx) ?: return@executes 1); 1 }
            for (word in listOf("추가", "add")) {
                arg.then(Commands.literal(word).then(Commands.argument("명령", StringArgumentType.greedyString()).executes { ctx -> addCommand(ctx); 1 }))
            }
            for (word in listOf("삭제", "remove")) {
                arg.then(Commands.literal(word).then(Commands.argument("번호", IntegerArgumentType.integer(1)).executes { ctx -> removeCommand(ctx); 1 }))
            }
            for (word in listOf("비우기", "clear")) arg.then(Commands.literal(word).executes { ctx -> clearCommands(ctx); 1 })
            it.then(arg)
        }
        sub(root, "다시읽기", "forceupdate") { it.executes { ctx -> reload(ctx.source.sender); 1 } }
        ess.commands.add(root.build(), "포탈 만들기·고치기(관리자)", listOf("portals"))
    }

    // --- 나무 짓기 --------------------------------------------------------------------------------------

    private fun sub(
        root: LiteralArgumentBuilder<CommandSourceStack>,
        ko: String,
        en: String,
        build: (LiteralArgumentBuilder<CommandSourceStack>) -> ArgumentBuilder<CommandSourceStack, *>,
    ) {
        for (word in listOf(ko, en)) root.then(build(Commands.literal(word)))
    }

    /** 포탈 이름 — 빈칸까지 한 낱말(한글 포함). 하위 명령마다 새로 짓는다(같은 노드를 두 부모에 달지 않게). */
    private fun name() = Commands.argument("이름", Args.TOKEN).suggests(NAMES)

    private val NAMES = SuggestionProvider<CommandSourceStack> { _, builder ->
        val typed = builder.remainingLowerCase
        for (portal in store.all()) if (portal.key.startsWith(typed) || portal.name.startsWith(builder.remaining)) builder.suggest(portal.name)
        builder.buildFuture()
    }

    private val HERE_OR_CLEAR = SuggestionProvider<CommandSourceStack> { _, builder ->
        for (word in listOf("여기", "지우기", "here", "clear")) if (word.startsWith(builder.remainingLowerCase)) builder.suggest(word)
        builder.buildFuture()
    }

    // --- 하는 일 ------------------------------------------------------------------------------------

    private fun list(sender: CommandSender) {
        if (sender is Player) return PortalListMenu(ess, sender, service).show()
        val portals = store.all().sortedBy { it.key }
        if (portals.isEmpty()) return ess.messages.send(sender, "portals-none")
        ess.messages.send(sender, "portals-list-header", Ph.of().count(portals.size))
        for (portal in portals) ess.messages.send(sender, "portals-list-entry", describe(portal))
    }

    private fun create(ctx: CommandContext<CommandSourceStack>) {
        val player = player(ctx) ?: return
        if (!service.editable(player)) return
        val name = ctx.getArgument("이름", String::class.java).trim()
        val ph = Ph.of().raw("{포탈}", name)
        if (!PortalRules.NAME.matches(name)) return ess.messages.send(player, "portals-invalid-name", ph)
        if (store.get(name) != null) return ess.messages.send(player, "portals-exists", ph)
        store.rejectedName(name)?.let { return ess.messages.send(player, "portals-rejected-exists", Ph.of().raw("{포탈}", it)) }
        val selected = service.selection(player) ?: return ess.messages.send(player, "portals-no-selection")
        val box = selected.box
        if (!fits(player, box, null)) return
        val portal = service.settings.create(name, box)
        store.put(portal)
        service.clearCorners(player)
        service.mark(player, player.location)
        ess.messages.send(player, "portals-created", Ph.of().raw("{포탈}", name).value(box.describe()).raw("{출처}", service.sourceLabel(selected)))
        if (portal.target == null) ess.messages.send(player, "portals-hint-target", Ph.of().raw("{포탈}", name))
    }

    private fun fits(sender: CommandSender, box: PortalBox, except: String?): Boolean = fits(ess, service, sender, box, except)

    companion object {
        private val HERE_WORDS = setOf("여기", "here")
        private val CLEAR_WORDS = setOf("지우기", "없음", "clear", "none", "remove")

        /** 만들기·다시 정하기(명령·편집 화면)가 같이 쓰는 영역 검사 — 크기·월드 경계·청크 수·겹침. 안 되면 알리고 false. */
        fun fits(ess: Ess, service: PortalService, sender: CommandSender, box: PortalBox, except: String?): Boolean {
            when (PortalRules.check(box, service.settings.maxVolume)) {
                PortalRules.BoxCheck.OK -> Unit
                PortalRules.BoxCheck.TOO_BIG -> {
                    ess.messages.send(sender, "portals-too-big", Ph.of().count(box.volume).value(service.settings.maxVolume.toString()))
                    return false
                }
                PortalRules.BoxCheck.OUT_OF_BOUNDS -> {
                    ess.messages.send(sender, "portals-out-of-bounds", Ph.of().value(box.describe()).count(box.chunkCount))
                    return false
                }
            }
            service.collision(box, except)?.let {
                ess.messages.send(sender, "portals-collides", Ph.of().raw("{포탈}", it.name))
                return false
            }
            return true
        }
    }

    private fun corner(ctx: CommandContext<CommandSourceStack>, index: Int) {
        val player = player(ctx) ?: return
        service.corner(player, index)
        val b = player.location.block
        ess.messages.send(player, "portals-corner", Ph.of().value((index + 1).toString()).world(b.world.name).raw("{좌표}", "${b.x}, ${b.y}, ${b.z}"))
    }

    private fun redefine(ctx: CommandContext<CommandSourceStack>) {
        val player = player(ctx) ?: return
        val portal = editing(ctx) ?: return
        val selected = service.selection(player) ?: return ess.messages.send(player, "portals-no-selection")
        val box = selected.box
        if (!fits(player, box, portal.name)) return
        store.put(portal.copy(box = box))
        service.clearCorners(player)
        service.mark(player, player.location)
        ess.messages.send(player, "portals-redefined", Ph.of().raw("{포탈}", portal.name).value(box.describe()).raw("{출처}", service.sourceLabel(selected)))
    }

    private fun target(ctx: CommandContext<CommandSourceStack>, raw: String?) {
        val sender = ctx.source.sender
        val portal = editing(ctx) ?: return
        val ph = Ph.of().raw("{포탈}", portal.name)
        if (raw != null && raw.trim().lowercase() in CLEAR_WORDS) {
            store.put(portal.copy(target = null))
            return ess.messages.send(sender, "portals-target-cleared", ph)
        }
        val spot = location(ctx, raw) ?: return
        store.put(portal.copy(target = spot))
        ess.messages.send(sender, "portals-target-set", ph.value(spot.describe()))
    }

    private fun safe(ctx: CommandContext<CommandSourceStack>, raw: String?) {
        val sender = ctx.source.sender
        val portal = editing(ctx) ?: return
        val ph = Ph.of().raw("{포탈}", portal.name)
        if (raw != null && raw.trim().lowercase() in CLEAR_WORDS) {
            store.put(portal.copy(safe = null))
            return ess.messages.send(sender, "portals-safe-cleared", ph)
        }
        val spot = location(ctx, raw) ?: return
        store.put(portal.copy(safe = spot))
        ess.messages.send(sender, "portals-safe-set", ph.value(spot.describe()))
    }

    /** 비었거나 `여기` = 선 자리, 아니면 `월드;x;y;z;yaw;pitch`(`:` 도) 또는 `x y z [yaw pitch]`(지금 월드). */
    private fun location(ctx: CommandContext<CommandSourceStack>, raw: String?): PortalSpot? {
        val sender = ctx.source.sender
        val text = raw?.trim().orEmpty()
        val here = (sender as? Player)?.location ?: ctx.source.location
        if (text.isEmpty() || text.lowercase() in HERE_WORDS) {
            if (sender !is Player) {
                ess.messages.send(sender, "player-only")
                return null
            }
            return PortalSpot.of(here)
        }
        PortalSpot.parse(text)?.let { return it }
        val numbers = Args.split(text).map { it.toDoubleOrNull() }
        if (numbers.size in 3..5 && numbers.all { it != null } && here.world != null) {
            val n = numbers.map { it!! }
            return PortalSpot(here.world.name, n[0], n[1], n[2], n.getOrNull(3)?.toFloat() ?: here.yaw, n.getOrNull(4)?.toFloat() ?: here.pitch)
        }
        ess.messages.send(sender, "portals-location-invalid", Ph.of().value(text))
        return null
    }

    private fun enable(ctx: CommandContext<CommandSourceStack>, on: Boolean?) {
        val portal = editing(ctx) ?: return
        val value = on ?: !portal.enabled
        store.put(portal.copy(enabled = value))
        ess.messages.send(ctx.source.sender, if (value) "portals-enabled" else "portals-disabled", Ph.of().raw("{포탈}", portal.name))
    }

    private fun nearest(ctx: CommandContext<CommandSourceStack>) {
        val player = player(ctx) ?: return
        val portal = service.byDistance(player.location).firstOrNull() ?: return ess.messages.send(player, "portals-none")
        val distance = if (portal.box.world == player.world.name) {
            kotlin.math.sqrt(portal.box.distanceSquared(player.location.x, player.location.y, player.location.z)).toInt().toString()
        } else "?"
        ess.messages.send(player, "portals-nearest", Ph.of().raw("{포탈}", portal.name).value(distance))
        info(player, portal)
    }

    fun info(sender: CommandSender, portal: Portal) {
        ess.messages.send(sender, "portals-info", describe(portal))
    }

    private fun describe(portal: Portal): Ph = Ph.of()
        .raw("{포탈}", portal.name)
        .raw("{영역}", portal.box.describe())
        .raw("{목적지}", portal.target?.describe() ?: ess.messages.raw("portals-word-none"))
        .raw("{상태}", ess.messages.raw(if (portal.enabled) "portals-word-on" else "portals-word-off"))
        .raw("{권한}", if (portal.permission) PortalRules.node(portal) else ess.messages.raw("portals-word-none"))
        .count(portal.commands.size)
        .time(portal.cooldown.toString())

    private fun edit(ctx: CommandContext<CommandSourceStack>) {
        val player = player(ctx) ?: return
        val portal = portal(ctx) ?: return
        PortalEditMenu(ess, player, service, portal.name).show()
    }

    private fun remove(ctx: CommandContext<CommandSourceStack>) {
        val portal = editing(ctx) ?: return
        store.remove(portal.name)
        ess.messages.send(ctx.source.sender, "portals-removed", Ph.of().raw("{포탈}", portal.name))
    }

    private fun teleport(ctx: CommandContext<CommandSourceStack>, toTarget: Boolean) {
        val player = player(ctx) ?: return
        val portal = portal(ctx) ?: return
        val label = label(ctx)
        val where: Location = (if (toTarget) portal.target?.toLocation() else service.center(portal))
            ?: return ess.messages.send(player, "portals-target-missing", Ph.of().raw("{포탈}", portal.name).world(portal.target?.world ?: portal.box.world))
        ess.teleporter.teleport(player, where, TeleportKind.ADMIN).thenAccept { ok ->
            if (ok == true) player.scheduler.run(ess.plugin, { _ ->
                ess.gate?.commit(player, label)
                ess.messages.send(player, "portals-teleported", Ph.of().raw("{포탈}", portal.name))
            }, null)
        }
    }

    private fun commands(sender: CommandSender, portal: Portal) {
        val ph = Ph.of().raw("{포탈}", portal.name)
        if (portal.commands.isEmpty()) return ess.messages.send(sender, "portals-commands-none", ph)
        ess.messages.send(sender, "portals-commands-header", ph.count(portal.commands.size))
        for ((index, command) in portal.commands.withIndex()) {
            ess.messages.send(sender, "portals-commands-entry", Ph.of().raw("{포탈}", portal.name).rank((index + 1).toString()).value(command))
        }
    }

    private fun addCommand(ctx: CommandContext<CommandSourceStack>) {
        val portal = editing(ctx) ?: return
        val text = StringArgumentType.getString(ctx, "명령").trim()
        if (PortalRules.parseCommand(text) == null) return ess.messages.send(ctx.source.sender, "portals-usage")
        if (!service.mayAddConsole(ctx.source.sender, portal.commands, portal.commands + text)) return
        store.put(portal.copy(commands = portal.commands + text))
        ess.messages.send(ctx.source.sender, "portals-command-added", Ph.of().raw("{포탈}", portal.name).value(text).count(portal.commands.size + 1))
    }

    private fun removeCommand(ctx: CommandContext<CommandSourceStack>) {
        val portal = editing(ctx) ?: return
        val index = IntegerArgumentType.getInteger(ctx, "번호") - 1
        if (index !in portal.commands.indices) {
            return ess.messages.send(ctx.source.sender, "portals-command-index", Ph.of().value(portal.commands.size.toString()))
        }
        val removed = portal.commands[index]
        store.put(portal.copy(commands = portal.commands.filterIndexed { i, _ -> i != index }))
        ess.messages.send(ctx.source.sender, "portals-command-removed", Ph.of().raw("{포탈}", portal.name).value(removed))
    }

    private fun clearCommands(ctx: CommandContext<CommandSourceStack>) {
        val portal = editing(ctx) ?: return
        store.put(portal.copy(commands = emptyList()))
        ess.messages.send(ctx.source.sender, "portals-commands-cleared", Ph.of().raw("{포탈}", portal.name))
    }

    private fun reload(sender: CommandSender) {
        store.reload { read, error ->
            if (error != null) return@reload ess.messages.send(sender, "portals-reload-failed", Ph.of().value(error).count(read.portals.size))
            for (online in Bukkit.getOnlinePlayers()) service.mark(online, online.location)
            ess.messages.send(sender, "portals-reloaded", Ph.of().count(read.portals.size).value(read.rejected.size.toString()))
        }
    }

    // --- 도움 ---------------------------------------------------------------------------------------

    private fun portal(ctx: CommandContext<CommandSourceStack>): Portal? {
        val name = ctx.getArgument("이름", String::class.java)
        return store.get(name) ?: null.also { ess.messages.send(ctx.source.sender, "portals-not-found", Ph.of().raw("{포탈}", name)) }
    }

    /** 고칠 포탈 — 파일이 잠겼으면(깨짐) 알리고 null. */
    private fun editing(ctx: CommandContext<CommandSourceStack>): Portal? {
        if (!service.editable(ctx.source.sender)) return null
        return portal(ctx)
    }

    private fun player(ctx: CommandContext<CommandSourceStack>): Player? =
        (ctx.source.executor as? Player ?: ctx.source.sender as? Player) ?: null.also { ess.messages.send(ctx.source.sender, "player-only") }

    private fun label(ctx: CommandContext<CommandSourceStack>): String = ctx.input.removePrefix("/").substringBefore(' ')
}
