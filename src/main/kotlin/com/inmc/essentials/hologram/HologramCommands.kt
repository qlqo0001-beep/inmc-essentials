package com.inmc.essentials.hologram

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
import kr.inmc.core.integration.PlayerSettings
import kr.inmc.core.integration.Vanish
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.Base64

/**
 * `/홀로그램`(hologram, holo) — 계획 4절 표. 하위 명령은 한글 + CMI 영어(new/remove/addline/setline/insertline/removeline/movehere/
 * teleport/list/near/page). CMI 의 `/hologrampages` 는 `/홀로그램 넘기기`, 개인 보기 옵션(visibleHolograms)은 `/홀로그램 보기`.
 * 줄 가리키기: `3`(1쪽의 3번째) · `2:3`(2쪽 3번째).
 */
class HologramCommands(private val ess: Ess, private val feature: HologramsFeature, private val engine: HologramEngine) {

    private val store get() = engine.store

    fun register() {
        val root = Commands.literal("홀로그램")
            .requires { s -> listOf(HologramsFeature.ADMIN, HologramsFeature.TOGGLE, HologramsFeature.PAGES).any { s.sender.hasPermission(it) } }
            .executes { ctx -> rootCommand(ctx.source.sender); 1 }
        admin(root, "목록", "list") { it.executes { ctx -> list(ctx.source.sender); 1 } }
        admin(root, "도움", "help") { it.executes { ctx -> ess.messages.send(ctx.source.sender, "holograms-usage"); 1 } }
        admin(root, "가까이", "near") {
            it.executes { ctx -> near(ctx, DEFAULT_NEAR); 1 }
                .then(Commands.argument("반경", IntegerArgumentType.integer(1, 512)).executes { ctx -> near(ctx, IntegerArgumentType.getInteger(ctx, "반경")); 1 })
        }
        admin(root, "만들기", "new") {
            it.then(newName().executes { ctx -> create(ctx, null); 1 }
                .then(Commands.argument("글", StringArgumentType.greedyString()).executes { ctx -> create(ctx, StringArgumentType.getString(ctx, "글")); 1 }))
        }
        admin(root, "삭제", "remove") { it.then(name().executes { ctx -> remove(ctx); 1 }) }
        admin(root, "줄추가", "addline") {
            it.then(name().then(Commands.argument("글", StringArgumentType.greedyString()).executes { ctx -> addLine(ctx); 1 }))
        }
        admin(root, "줄수정", "setline") {
            it.then(name().then(lineArg().then(Commands.argument("글", StringArgumentType.greedyString()).executes { ctx -> setLine(ctx, insert = false); 1 })))
        }
        admin(root, "줄끼우기", "insertline") {
            it.then(name().then(lineArg().then(Commands.argument("글", StringArgumentType.greedyString()).executes { ctx -> setLine(ctx, insert = true); 1 })))
        }
        admin(root, "줄삭제", "removeline") { it.then(name().then(lineArg().executes { ctx -> removeLine(ctx); 1 })) }
        admin(root, "여기로", "movehere") { it.then(name().executes { ctx -> moveHere(ctx); 1 }) }
        admin(root, "이동", "teleport") { it.then(name().executes { ctx -> teleport(ctx); 1 }) }
        admin(root, "편집", "edit") { it.then(name().executes { ctx -> edit(ctx); 1 }) }
        admin(root, "쪽", "page") {
            val arg = name().executes { ctx -> pages(ctx); 1 }
            for (word in listOf("추가", "add")) arg.then(Commands.literal(word).executes { ctx -> addPage(ctx); 1 })
            for (word in listOf("삭제", "remove")) {
                arg.then(Commands.literal(word).then(Commands.argument("번호", IntegerArgumentType.integer(1)).executes { ctx -> removePage(ctx); 1 }))
            }
            it.then(arg)
        }
        admin(root, "동작", "action") {
            it.then(name().then(Commands.argument("대상", Args.TOKEN).suggests(TARGET_HINTS)
                .then(Commands.argument("클릭", Args.TOKEN).suggests(CLICK_HINTS).executes { ctx -> action(ctx, null); 1 }
                    .then(Commands.argument("명령", StringArgumentType.greedyString()).executes { ctx -> action(ctx, StringArgumentType.getString(ctx, "명령")); 1 }))))
        }
        admin(root, "다시읽기", "reload") { it.executes { ctx -> reload(ctx.source.sender); 1 } }
        for (word in listOf("넘기기", "pages")) {
            root.then(Commands.literal(word).requires { it.sender.hasPermission(HologramsFeature.PAGES) }
                .then(name().executes { ctx -> turn(ctx, ""); 1 }
                    .then(Commands.argument("내용", StringArgumentType.greedyString()).suggests(TURN_HINTS)
                        .executes { ctx -> turn(ctx, StringArgumentType.getString(ctx, "내용")); 1 })))
        }
        for (word in listOf("보기", "toggle")) {
            root.then(Commands.literal(word).requires { it.sender.hasPermission(HologramsFeature.TOGGLE) }.executes { ctx -> toggle(ctx); 1 })
        }
        ess.commands.add(root.build(), "홀로그램 — 만들기·고치기(관리자) · 보기 켜고 끄기 · 쪽 넘기기", listOf("hologram", "holo"))
    }

    // --- 나무 짓기 --------------------------------------------------------------------------------------

    private fun admin(
        root: LiteralArgumentBuilder<CommandSourceStack>,
        ko: String,
        en: String,
        build: (LiteralArgumentBuilder<CommandSourceStack>) -> ArgumentBuilder<CommandSourceStack, *>,
    ) {
        for (word in listOf(ko, en)) root.then(build(Commands.literal(word).requires { it.sender.hasPermission(HologramsFeature.ADMIN) }))
    }

    private fun name() = Commands.argument("이름", Args.TOKEN).suggests(NAMES)

    /** 새 이름 — 있는 이름을 제안하지 않는다. */
    private fun newName() = Commands.argument("이름", Args.TOKEN)

    private fun lineArg() = Commands.argument("줄", Args.TOKEN).suggests(LINES)

    /** 홀로그램 이름 제안 — 관리자는 전부, 그 밖(`/홀로그램 넘기기`)은 지금 그 사람에게 보이는 것(권한·조건·거리)만 — 숨긴 이름이 새지 않게. */
    private val NAMES = SuggestionProvider<CommandSourceStack> { ctx, builder ->
        val typed = builder.remainingLowerCase
        val sender = ctx.source.sender
        val admin = sender.hasPermission(HologramsFeature.ADMIN)
        val viewer = sender as? Player
        for (h in store.all()) {
            if (!h.key.startsWith(typed)) continue
            if (!admin && (viewer == null || !engine.visibleNear(viewer, h))) continue
            builder.suggest(h.name)
        }
        builder.buildFuture()
    }

    private val LINES = SuggestionProvider<CommandSourceStack> { ctx, builder ->
        val h = runCatching { ctx.getArgument("이름", String::class.java) }.getOrNull()?.let { store.get(it) }
        if (h != null) {
            for ((p, page) in h.pages.withIndex()) for (l in page.lines.indices) {
                val text = if (h.pages.size == 1) "${l + 1}" else "${p + 1}:${l + 1}"
                if (text.startsWith(builder.remaining)) builder.suggest(text)
            }
        }
        builder.buildFuture()
    }

    private val TARGET_HINTS = SuggestionProvider<CommandSourceStack> { _, builder ->
        for (word in listOf("쪽", "쪽:1", "1", "1:1", "page")) if (word.startsWith(builder.remaining)) builder.suggest(word)
        builder.buildFuture()
    }

    private val CLICK_HINTS = SuggestionProvider<CommandSourceStack> { _, builder ->
        for (word in listOf("좌", "우", "shift좌", "shift우", "아무", "left", "right", "shift-left", "shift-right", "any")) {
            if (word.startsWith(builder.remainingLowerCase)) builder.suggest(word)
        }
        builder.buildFuture()
    }

    private val TURN_HINTS = SuggestionProvider<CommandSourceStack> { ctx, builder ->
        val remaining = builder.remaining
        val space = remaining.lastIndexOf(' ')
        val words = listOf("다음", "이전", "next", "prev")
        if (space < 0) {
            for (w in words) if (w.startsWith(remaining.lowercase())) builder.suggest(w)
            if (ctx.source.sender.hasPermission(HologramsFeature.PAGES_OTHERS)) {
                for (n in Vanish.visibleNames(ctx.source.sender)) if (n.lowercase().startsWith(remaining.lowercase())) builder.suggest(n)
            }
            builder.buildFuture()
        } else {
            val offset = builder.createOffset(builder.start + space + 1)
            for (w in words) if (w.startsWith(remaining.substring(space + 1).lowercase())) offset.suggest(w)
            offset.buildFuture()
        }
    }

    // --- 하는 일 ------------------------------------------------------------------------------------

    private fun rootCommand(sender: CommandSender) {
        if (sender.hasPermission(HologramsFeature.ADMIN)) return list(sender)
        ess.messages.send(sender, "holograms-player-usage")
    }

    private fun list(sender: CommandSender) {
        if (sender is Player) return HologramListMenu(ess, sender, engine, feature).show()
        val all = store.all().sortedBy { it.key }
        if (all.isEmpty()) return ess.messages.send(sender, "holograms-none")
        ess.messages.send(sender, "holograms-list-header", Ph.of().count(all.size))
        for (h in all) ess.messages.send(sender, "holograms-list-entry", describe(h).value(""))
    }

    private fun near(ctx: CommandContext<CommandSourceStack>, radius: Int) {
        val player = player(ctx) ?: return
        val here = player.location
        val near = store.all().filter { it.world == player.world.name }
            .map { it to distance(it, here) }.filter { it.second <= radius }.sortedBy { it.second }
        if (near.isEmpty()) return ess.messages.send(player, "holograms-none-near", Ph.of().value(radius.toString()))
        ess.messages.send(player, "holograms-near-header", Ph.of().count(near.size).value(radius.toString()))
        for ((h, d) in near) ess.messages.send(player, "holograms-list-entry", describe(h).value("%.1fm".format(d)))
    }

    private fun describe(h: Hologram): Ph = Ph.of().raw("{홀로그램}", h.name).world(h.world)
        .raw("{좌표}", "%.1f, %.1f, %.1f".format(h.x, h.y, h.z)).count(h.lineCount).raw("{쪽}", h.pages.size.toString())

    private fun distance(h: Hologram, at: Location): Double {
        val dx = h.x - at.x
        val dy = h.y - at.y
        val dz = h.z - at.z
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun create(ctx: CommandContext<CommandSourceStack>, first: String?) {
        val player = player(ctx) ?: return
        if (!feature.editable(player)) return
        val name = ctx.getArgument("이름", String::class.java).trim()
        val ph = Ph.of().raw("{홀로그램}", name)
        if (!HoloRules.NAME.matches(name)) return ess.messages.send(player, "holograms-invalid-name", ph)
        if (store.get(name) != null) return ess.messages.send(player, "holograms-exists", ph)
        store.rejectedName(name)?.let { return ess.messages.send(player, "holograms-rejected-exists", Ph.of().raw("{홀로그램}", it)) }
        val line = if (first.isNullOrBlank()) HoloLine(text = name) else lineOf(player, first) ?: return
        val at = feature.spawnPoint(player)
        store.put(Hologram(name, at.world.name, at.x, at.y, at.z, pages = listOf(HoloPage(listOf(line)))))
        ess.messages.send(player, "holograms-created", ph)
    }

    /** 명령의 글 → 줄. `icon:손`(손에 든 것) · `icon:<참조>` · 그 밖은 글. */
    private fun lineOf(sender: CommandSender, input: String): HoloLine? {
        val spec = HoloRules.iconSpec(input) ?: return HoloLine(text = input)
        if (spec.lowercase() in HAND_WORDS) {
            val player = sender as? Player ?: return null.also { ess.messages.send(sender, "player-only") }
            val stack = player.inventory.itemInMainHand
            if (stack.type.isAir) return null.also { ess.messages.send(sender, "holograms-hand-empty") }
            val stored = ess.itemResolver.capture(stack)
            return HoloLine(icon = stored.ref.serialize(), material = stored.material.name, snapshot = stored.snapshot?.let { Base64.getEncoder().encodeToString(it) })
        }
        val ref = kr.inmc.core.item.ItemRef.parse(spec)
        if (ref is kr.inmc.core.item.ItemRef.None) return null.also { ess.messages.send(sender, "holograms-icon-invalid", Ph.of().value(spec)) }
        return HoloLine(icon = ref.serialize())
    }

    private fun remove(ctx: CommandContext<CommandSourceStack>) {
        val h = editing(ctx) ?: return
        store.remove(h.name)
        ess.messages.send(ctx.source.sender, "holograms-removed", Ph.of().raw("{홀로그램}", h.name))
    }

    private fun addLine(ctx: CommandContext<CommandSourceStack>) {
        val sender = ctx.source.sender
        val h = editing(ctx) ?: return
        val text = StringArgumentType.getString(ctx, "글")
        if (text.trim().equals("!nextpage!", ignoreCase = true)) {
            store.put(h.copy(pages = h.pages + HoloPage()))
            // 빈 쪽은 줄을 더할 때까지 건너뛴다(넘겨도 안 보인다) — 다음 줄추가가 그 쪽에 들어간다고 알린다.
            return ess.messages.send(sender, "holograms-page-added-empty", Ph.of().raw("{홀로그램}", h.name).raw("{쪽}", (h.pages.size + 1).toString()))
        }
        val line = lineOf(sender, text) ?: return
        val last = h.pages.lastIndex
        store.put(h.copy(pages = h.pages.mapIndexed { i, page -> if (i == last) page.copy(lines = page.lines + line) else page }))
        ess.messages.send(sender, "holograms-line-added", Ph.of().raw("{홀로그램}", h.name).raw("{줄}", "${h.pages.size}:${h.pages[last].lines.size + 1}"))
    }

    private fun setLine(ctx: CommandContext<CommandSourceStack>, insert: Boolean) {
        val sender = ctx.source.sender
        val h = editing(ctx) ?: return
        val (p, l) = lineRef(ctx, h, allowEnd = insert) ?: return
        val line = lineOf(sender, StringArgumentType.getString(ctx, "글")) ?: return
        val pages = h.pages.mapIndexed { i, page ->
            if (i != p) page
            else if (insert) page.copy(lines = page.lines.toMutableList().apply { add(l, line) })
            else page.copy(lines = page.lines.mapIndexed { j, old -> if (j == l) line.copy(actions = old.actions) else old })
        }
        store.put(h.copy(pages = pages))
        ess.messages.send(sender, if (insert) "holograms-line-inserted" else "holograms-line-set", Ph.of().raw("{홀로그램}", h.name).raw("{줄}", "${p + 1}:${l + 1}"))
    }

    private fun removeLine(ctx: CommandContext<CommandSourceStack>) {
        val h = editing(ctx) ?: return
        val (p, l) = lineRef(ctx, h, allowEnd = false) ?: return
        store.put(h.copy(pages = h.pages.mapIndexed { i, page -> if (i == p) page.copy(lines = page.lines.filterIndexed { j, _ -> j != l }) else page }))
        ess.messages.send(ctx.source.sender, "holograms-line-removed", Ph.of().raw("{홀로그램}", h.name).raw("{줄}", "${p + 1}:${l + 1}"))
        if (h.pages[p].lines.size == 1 && h.pages.size > 1) {
            ess.messages.send(ctx.source.sender, "holograms-page-empty", Ph.of().raw("{홀로그램}", h.name).raw("{쪽}", (p + 1).toString()))
        }
    }

    /** `줄` 인자 → (쪽, 줄) 0부터. [allowEnd] 면 마지막 다음 자리도(끼우기). */
    private fun lineRef(ctx: CommandContext<CommandSourceStack>, h: Hologram, allowEnd: Boolean): Pair<Int, Int>? {
        val raw = ctx.getArgument("줄", String::class.java)
        val ref = HoloRules.lineRef(raw)
        val page = ref?.let { h.pages.getOrNull(it.first) }
        val max = page?.lines?.size?.let { if (allowEnd) it + 1 else it } ?: 0
        if (ref == null || page == null || ref.second !in 0 until max) {
            ess.messages.send(ctx.source.sender, "holograms-line-invalid", Ph.of().value(raw).raw("{홀로그램}", h.name))
            return null
        }
        return ref
    }

    private fun moveHere(ctx: CommandContext<CommandSourceStack>) {
        val player = player(ctx) ?: return
        val h = editing(ctx) ?: return
        val at = feature.spawnPoint(player)
        store.put(h.copy(world = at.world.name, x = at.x, y = at.y, z = at.z, yaw = if (h.billboard == "FIXED") player.location.yaw + 180f else h.yaw))
        ess.messages.send(player, "holograms-moved", Ph.of().raw("{홀로그램}", h.name))
    }

    private fun teleport(ctx: CommandContext<CommandSourceStack>) {
        val player = player(ctx) ?: return
        val h = hologram(ctx) ?: return
        val label = label(ctx)
        val world = Bukkit.getWorld(h.world) ?: return ess.messages.send(player, "holograms-world-missing", Ph.of().raw("{홀로그램}", h.name).world(h.world))
        val to = Location(world, h.x, h.y - h.pages.first().lines.size * feature.settings.lineHeight - 1.6, h.z, player.location.yaw, player.location.pitch)
        ess.teleporter.teleport(player, to, TeleportKind.ADMIN).thenAccept { ok ->
            if (ok == true) player.scheduler.run(ess.plugin, { _ ->
                ess.gate?.commit(player, label)
                ess.messages.send(player, "holograms-teleported", Ph.of().raw("{홀로그램}", h.name))
            }, null)
        }
    }

    private fun edit(ctx: CommandContext<CommandSourceStack>) {
        val player = player(ctx) ?: return
        val h = hologram(ctx) ?: return
        HologramEditMenu(ess, player, engine, feature, h.name, 0).show()
    }

    private fun pages(ctx: CommandContext<CommandSourceStack>) {
        val h = hologram(ctx) ?: return
        ess.messages.send(ctx.source.sender, "holograms-pages-info", Ph.of().raw("{홀로그램}", h.name).raw("{쪽}", h.pages.size.toString())
            .value(h.pages.mapIndexed { i, p -> "${i + 1}쪽 ${p.lines.size}줄" }.joinToString(" · ")))
    }

    private fun addPage(ctx: CommandContext<CommandSourceStack>) {
        val h = editing(ctx) ?: return
        store.put(h.copy(pages = h.pages + HoloPage(listOf(HoloLine(text = "${h.pages.size + 1}쪽")))))
        ess.messages.send(ctx.source.sender, "holograms-page-added", Ph.of().raw("{홀로그램}", h.name).raw("{쪽}", (h.pages.size + 1).toString()))
    }

    private fun removePage(ctx: CommandContext<CommandSourceStack>) {
        val h = editing(ctx) ?: return
        val index = IntegerArgumentType.getInteger(ctx, "번호") - 1
        if (index !in h.pages.indices || h.pages.size <= 1) {
            return ess.messages.send(ctx.source.sender, "holograms-page-invalid", Ph.of().raw("{홀로그램}", h.name).raw("{쪽}", h.pages.size.toString()))
        }
        store.put(h.copy(pages = h.pages.filterIndexed { i, _ -> i != index }))
        ess.messages.send(ctx.source.sender, "holograms-page-removed", Ph.of().raw("{홀로그램}", h.name).raw("{쪽}", (index + 1).toString()))
    }

    /** `/홀로그램 동작 <이름> <줄|쪽|쪽:N> <클릭> [명령]` — 명령을 더한다. 명령 없이 치면 그 클릭의 명령을 비운다. */
    private fun action(ctx: CommandContext<CommandSourceStack>, command: String?) {
        val sender = ctx.source.sender
        val h = editing(ctx) ?: return
        val target = ctx.getArgument("대상", String::class.java).trim()
        val click = HoloClick.parse(ctx.getArgument("클릭", String::class.java))
            ?: return ess.messages.send(sender, "holograms-click-invalid", Ph.of().value(ctx.getArgument("클릭", String::class.java)))
        val lower = target.lowercase()
        val pageTarget = lower == "쪽" || lower == "page" || lower.startsWith("쪽:") || lower.startsWith("page:")
        val updated: Hologram
        val label: String
        if (pageTarget) {
            val p = (target.substringAfter(':', "1").toIntOrNull() ?: 1) - 1
            val page = h.pages.getOrNull(p) ?: return ess.messages.send(sender, "holograms-page-invalid", Ph.of().raw("{홀로그램}", h.name).raw("{쪽}", h.pages.size.toString()))
            val list = if (command == null) emptyList() else page.actions.map[click].orEmpty() + command.trim()
            if (!feature.mayAddConsole(sender, page.actions.map[click].orEmpty(), list)) return
            updated = h.copy(pages = h.pages.mapIndexed { i, pg -> if (i == p) pg.copy(actions = pg.actions.with(click, list)) else pg })
            label = "${p + 1}쪽"
        } else {
            val ref = HoloRules.lineRef(target)
            val page = ref?.let { h.pages.getOrNull(it.first) }
            val line = ref?.let { page?.lines?.getOrNull(it.second) }
                ?: return ess.messages.send(sender, "holograms-line-invalid", Ph.of().value(target).raw("{홀로그램}", h.name))
            val list = if (command == null) emptyList() else line.actions.map[click].orEmpty() + command.trim()
            if (!feature.mayAddConsole(sender, line.actions.map[click].orEmpty(), list)) return
            val newLine = line.copy(actions = line.actions.with(click, list))
            updated = h.copy(pages = h.pages.mapIndexed { i, pg ->
                if (i == ref.first) pg.copy(lines = pg.lines.mapIndexed { j, ln -> if (j == ref.second) newLine else ln }) else pg
            })
            label = "${ref.first + 1}:${ref.second + 1}"
        }
        store.put(updated)
        val ph = Ph.of().raw("{홀로그램}", h.name).raw("{줄}", label).raw("{클릭}", click.label)
        if (command == null) ess.messages.send(sender, "holograms-action-cleared", ph) else ess.messages.send(sender, "holograms-action-added", ph.value(command.trim()))
    }

    private fun turn(ctx: CommandContext<CommandSourceStack>, rest: String) {
        val sender = ctx.source.sender
        val typed = ctx.getArgument("이름", String::class.java)
        // 관리자가 아니면 지금 내게 보이는(권한·조건·거리) 홀로그램만 — 숨긴 홀로그램은 "없음" 과 같게(이름이 있다는 것도 새지 않게).
        val h = store.get(typed)?.takeIf { sender.hasPermission(HologramsFeature.ADMIN) || (sender is Player && engine.visibleNear(sender, it)) }
            ?: return ess.messages.send(sender, "holograms-not-found", Ph.of().raw("{홀로그램}", typed))
        val tokens = Args.split(rest)
        var who: Player? = null
        var how: String? = null
        when (tokens.size) {
            0 -> Unit
            1 -> if (isDirection(tokens[0])) how = tokens[0] else who = target(sender, tokens[0]) ?: return
            else -> {
                who = target(sender, tokens[0]) ?: return
                how = tokens[1]
            }
        }
        val player = who ?: sender as? Player ?: return ess.messages.send(sender, "player-only")
        if (player != sender && !sender.hasPermission(HologramsFeature.PAGES_OTHERS)) return ess.messages.send(sender, "no-permission")
        if (engine.pageCount(h) <= 1) return ess.messages.send(sender, "holograms-single-page", Ph.of().raw("{홀로그램}", h.name))
        val word = how?.lowercase() ?: "next"
        when {
            word in NEXT_WORDS -> engine.turn(player, h, 1)
            word in PREV_WORDS -> engine.turn(player, h, -1)
            word.toIntOrNull() != null -> engine.show(player, h, word.toInt() - 1)
            else -> return ess.messages.send(sender, "holograms-usage")
        }
        if (player != sender) {
            ess.messages.send(sender, "holograms-page-turned", Ph.of().raw("{홀로그램}", h.name).target(player.name).raw("{쪽}", (engine.pageOf(player, h) + 1).toString()))
        }
    }

    private fun isDirection(token: String): Boolean = token.lowercase().let { it in NEXT_WORDS || it in PREV_WORDS || it.toIntOrNull() != null }

    private fun target(sender: CommandSender, input: String): Player? =
        ess.args.online(sender, input) ?: null.also { ess.messages.send(sender, "player-not-found", Ph.of().value(input)) }

    private fun toggle(ctx: CommandContext<CommandSourceStack>) {
        val player = player(ctx) ?: return
        val on = !PlayerSettings.enabled(player, HologramsFeature.SETTING_VISIBLE, true)
        PlayerSettings.set(player, HologramsFeature.SETTING_VISIBLE, on)
        engine.refreshAll()
        ess.messages.send(player, if (on) "holograms-visible-on" else "holograms-visible-off")
    }

    private fun reload(sender: CommandSender) {
        store.reload { read, error ->
            if (error != null) return@reload ess.messages.send(sender, "holograms-reload-failed", Ph.of().value(error).count(read.holograms.size))
            ess.messages.send(sender, "holograms-reloaded", Ph.of().count(read.holograms.size).value(read.rejected.size.toString()))
        }
    }

    // --- 도움 ---------------------------------------------------------------------------------------

    private fun hologram(ctx: CommandContext<CommandSourceStack>): Hologram? {
        val name = ctx.getArgument("이름", String::class.java)
        return store.get(name) ?: null.also { ess.messages.send(ctx.source.sender, "holograms-not-found", Ph.of().raw("{홀로그램}", name)) }
    }

    /** 고칠 홀로그램 — 파일이 잠겼으면(깨짐) 알리고 null. */
    private fun editing(ctx: CommandContext<CommandSourceStack>): Hologram? {
        if (!feature.editable(ctx.source.sender)) return null
        return hologram(ctx)
    }

    private fun player(ctx: CommandContext<CommandSourceStack>): Player? =
        (ctx.source.executor as? Player ?: ctx.source.sender as? Player) ?: null.also { ess.messages.send(ctx.source.sender, "player-only") }

    private fun label(ctx: CommandContext<CommandSourceStack>): String = ctx.input.removePrefix("/").substringBefore(' ')

    companion object {
        const val DEFAULT_NEAR = 32
        private val HAND_WORDS = setOf("손", "hand", "%iteminhand%")
        private val NEXT_WORDS = setOf("다음", "next")
        private val PREV_WORDS = setOf("이전", "prev", "previous")
    }
}
