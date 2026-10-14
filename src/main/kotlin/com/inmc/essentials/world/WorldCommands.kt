package com.inmc.essentials.world

import com.inmc.essentials.Ess
import com.inmc.essentials.command.Args
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import kr.inmc.core.util.Durations
import kr.inmc.core.util.VanillaNames
import org.bukkit.Bukkit
import org.bukkit.GameRule
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import java.util.concurrent.CompletableFuture

private typealias WCtx = CommandContext<CommandSourceStack>

/**
 * world 기능의 명령 — 계획 4절 표 그대로: `/바이옴찾기`(findbiome) · `/게임규칙` · `/청크복구`(fixchunk) · `/조명수정`(lfix) · `/몹제거`(killall) ·
 * `/날씨`(sun, rain, storm) · `/시간`(day, night). 인자는 전부 greedy 꼬리 하나를 직접 읽는다 — 한글·빈칸 든 바이옴 이름, CMI 식 순서 없는 깃발.
 * 별칭 `/sun`·`/day` 등은 친 이름이 곧 값이다(`/sun lock` = `/날씨 맑음 고정`). 그래서 이 다섯은 **따로 세운 루트**다 — 보기 권한(기본 모두)이
 * 아니라 바꾸기 권한만 걸어, 바꿀 수 없는 사람의 탭 목록에 뜨지 않게(별칭은 본 명령의 `requires` 를 같이 쓴다 — 2026-10-10 리뷰). 이름은 계획 표 그대로.
 */
internal class WorldCommands(private val ess: Ess, private val feature: WorldFeature) {

    private val s get() = feature.settings

    fun register() {
        ess.commands.add(root("바이옴찾기", WorldFeature.FINDBIOME, ::findBiome, ::suggestBiome), "가장 가까운 생물군계를 찾아 이동", listOf("findbiome"))
        ess.commands.add(root("게임규칙", WorldFeature.GAMERULE, ::gameRule, ::suggestGameRule), "월드 게임 규칙 보기·바꾸기")
        ess.commands.add(root("청크복구", WorldFeature.FIXCHUNK, ::fixChunk, ::suggestFixChunk), "손상된 청크 찾기·새로 만들기", listOf("fixchunk"))
        ess.commands.add(root("조명수정", WorldFeature.LFIX, ::lightFix, ::suggestLightFix), "주변 청크 빛 다시 계산", listOf("lfix"))
        ess.commands.add(root("몹제거", WorldFeature.KILLALL, ::killAll, ::suggestKillAll), "주변 몹 한꺼번에 지우기", listOf("killall"))
        ess.commands.add(root("날씨", WorldFeature.WEATHER, ::weather, ::suggestWeather, view = WorldFeature.WEATHER_CHECK), "월드 날씨 보기·바꾸기")
        for ((alias, what) in listOf("sun" to "맑음", "rain" to "비", "storm" to "폭풍")) {
            ess.commands.add(root(alias, WorldFeature.WEATHER, ::weather, ::suggestWeather), "날씨를 ${what}(으)로 (/날씨 $what)")
        }
        ess.commands.add(root("시간", WorldFeature.TIME, ::time, ::suggestTime, view = WorldFeature.TIME_CHECK), "월드 시간 보기·바꾸기·멈춤·현실 시간")
        for ((alias, what) in listOf("day" to "낮", "night" to "밤")) {
            ess.commands.add(root(alias, WorldFeature.TIME, ::time, ::suggestTime), "시간을 ${what}(으)로 (/시간 $what)")
        }
    }

    /** 루트 하나 — 인자 없이(빈 꼬리) · greedy 꼬리. [perm] 또는 [view](보기만) 중 하나가 있으면 들어온다(바꾸기 권한은 안에서 본다). */
    private fun root(
        name: String,
        perm: String,
        run: (WCtx, String) -> Unit,
        suggest: (WCtx, List<String>, String) -> List<String>,
        view: String? = null,
    ) = Commands.literal(name)
        .requires { src -> src.sender.hasPermission(perm) || (view != null && src.sender.hasPermission(view)) }
        .executes { ctx -> run(ctx, ""); 1 }
        .then(Commands.argument("내용", StringArgumentType.greedyString()).suggests(tail(suggest))
            .executes { ctx -> run(ctx, StringArgumentType.getString(ctx, "내용")); 1 })
        .build()

    /** greedy 꼬리 제안 — 지금 치는 낱말 자리에 [suggest] 가 준 것 중 앞이 맞는 것. */
    private fun tail(suggest: (WCtx, List<String>, String) -> List<String>): SuggestionProvider<CommandSourceStack> =
        SuggestionProvider { ctx, builder -> complete(ctx, builder, suggest) }

    private fun complete(ctx: WCtx, builder: SuggestionsBuilder, suggest: (WCtx, List<String>, String) -> List<String>): CompletableFuture<Suggestions> {
        val text = builder.remaining
        val start = text.lastIndexOf(' ') + 1
        val typed = text.substring(start)
        val before = text.substring(0, start).split(' ').filter { it.isNotEmpty() }
        val b = builder.createOffset(builder.start + start)
        val lower = typed.lowercase()
        runCatching { suggest(ctx, before, typed) }.getOrDefault(emptyList()).distinct()
            .filter { it.lowercase().startsWith(lower) }.take(100).forEach { b.suggest(it) }
        return b.buildFuture()
    }

    private fun label(ctx: WCtx): String = ctx.input.trim().removePrefix("/").substringBefore(' ').substringAfterLast(':').lowercase()

    private fun player(ctx: WCtx): Player? =
        (ctx.source.executor as? Player ?: ctx.source.sender as? Player) ?: null.also { ess.messages.send(ctx.source.sender, "player-only") }

    private fun bool(value: Boolean): String = ess.messages.raw(if (value) "world-on" else "world-off")

    private fun worldNames(): List<String> = Bukkit.getWorlds().map { it.name }

    private fun findWorld(name: String): World? = Bukkit.getWorld(name) ?: Bukkit.getWorlds().firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun isAllWord(word: String): Boolean = word.lowercase() in setOf("all", "*", "전체", "전부", "모든월드")

    // =============================================================================================
    // 바이옴 찾기
    // =============================================================================================

    private fun findBiome(ctx: WCtx, raw: String) {
        val sender = ctx.source.sender
        val words = Args.split(raw)
        val first = words.firstOrNull()?.lowercase()
        when (first) {
            null -> return ess.messages.send(sender, "world-biome-usage")
            "멈춤", "stop" -> {
                val p = player(ctx) ?: return
                return ess.messages.send(p, if (feature.biomes.stop(p.uniqueId)) "world-biome-stopped" else "world-biome-nothing-to-stop")
            }
            "전부멈춤", "stopall" -> {
                if (!sender.hasPermission(WorldFeature.FINDBIOME_STOPALL)) return ess.messages.send(sender, "no-permission")
                return ess.messages.send(sender, "world-biome-stopped-all", Ph.of().count(feature.biomes.stopAll()))
            }
        }
        val p = player(ctx) ?: return
        var radius = s.findBiome.radius
        var coordsOnly = false
        val nameParts = ArrayList<String>()
        for (w in words) {
            val lower = w.lowercase()
            when {
                lower.startsWith("-r:") || lower.startsWith("-반경:") -> {
                    val n = lower.substringAfter(':').toIntOrNull()?.takeIf { it > 0 } ?: return ess.messages.send(p, "invalid-number", Ph.of().value(w))
                    radius = if (n > s.findBiome.maxRadius) {
                        ess.messages.send(p, "world-biome-radius-capped", Ph.of().value(s.findBiome.maxRadius.toString()))
                        s.findBiome.maxRadius
                    } else n
                }
                lower == "-좌표" || lower == "-coords" -> coordsOnly = true
                else -> nameParts += w
            }
        }
        val input = nameParts.joinToString(" ")
        if (input.isBlank()) return ess.messages.send(p, "world-biome-usage")
        val match = feature.names.resolve(input)
        if (match.exact.isEmpty()) {
            if (match.candidates.isEmpty()) return ess.messages.send(p, "world-biome-unknown", Ph.of().value(input))
            val list = match.candidates.map { it.display }.distinct().take(12).joinToString(", ")
            return ess.messages.send(p, "world-biome-ambiguous", Ph.of().value(input).raw("{목록}", list))
        }
        feature.biomes.start(p, match.exact, radius, coordsOnly, label(ctx))
    }

    private fun suggestBiome(ctx: WCtx, before: List<String>, typed: String): List<String> {
        if (before.isEmpty()) {
            val sender = ctx.source.sender
            val world = (sender as? Player)?.world
            val possible = world?.let { w -> runCatching { w.vanillaBiomeProvider().getBiomes(w).toSet() }.getOrNull() }
            val names = feature.names.entries().filter { possible == null || it.biome in possible }.flatMap { listOf(it.display.replace(' ', '_'), it.path) }
            return names + listOf("멈춤", "stop") + (if (sender.hasPermission(WorldFeature.FINDBIOME_STOPALL)) listOf("전부멈춤", "stopall") else emptyList())
        }
        return listOf("-r:${s.findBiome.radius}", "-좌표")
    }

    // =============================================================================================
    // 게임 규칙
    // =============================================================================================

    private fun rules(): List<GameRule<*>> = runCatching { RegistryAccess.registryAccess().getRegistry(RegistryKey.GAME_RULE).toList() }
        .getOrElse { GameRule.values().toList() }

    internal fun ruleKey(rule: GameRule<*>): String = runCatching { rule.key.key }.getOrElse { rule.name }

    internal fun ruleKorean(rule: GameRule<*>): String? = runCatching { VanillaNames.ofKey(rule.translationKey()) }.getOrNull()

    internal fun findRule(input: String): GameRule<*>? {
        val want = BiomeNames.normalize(input.removePrefix("minecraft:"))
        return rules().firstOrNull { r -> listOfNotNull(ruleKey(r), r.name, ruleKorean(r)).any { BiomeNames.normalize(it) == want } }
    }

    private fun gameRule(ctx: WCtx, raw: String) {
        val sender = ctx.source.sender
        val words = Args.split(raw).toMutableList()
        var world = (sender as? Player)?.world
        if (words.isNotEmpty() && findRule(words[0]) == null) {
            world = findWorld(words[0]) ?: return ess.messages.send(sender, "world-unknown-world", Ph.of().value(words[0]))
            words.removeAt(0)
        }
        world ?: return ess.messages.send(sender, "world-need-world")
        if (words.isEmpty()) {
            ess.messages.send(sender, "world-gamerule-header", Ph.of().world(world.name).count(rules().size))
            val line = rules().joinToString("<gray>, </gray>") { r ->
                @Suppress("UNCHECKED_CAST")
                val value = world.getGameRuleValue(r as GameRule<Any>)
                val changed = value != r.defaultValue
                (if (changed) "<yellow>" else "<white>") + ruleKey(r) + "=" + value + (if (changed) "</yellow>" else "</white>")
            }
            return ess.messages.sendRaw(sender, line)
        }
        val rule = findRule(words[0]) ?: return ess.messages.send(sender, "world-gamerule-unknown", Ph.of().value(words[0]))
        @Suppress("UNCHECKED_CAST")
        val typed = rule as GameRule<Any>
        val ph = Ph.of().world(world.name).raw("{규칙}", ruleKey(rule)).raw("{이름}", ruleKorean(rule) ?: ruleKey(rule))
        if (words.size == 1) {
            return ess.messages.send(sender, "world-gamerule-value", ph.value(world.getGameRuleValue(typed).toString())
                .raw("{기본}", typed.defaultValue.toString()))
        }
        val value: Any = parseRuleValue(rule, words[1]) ?: return ess.messages.send(sender, "world-gamerule-bad-value", ph.value(words[1]))
        val targets = if (words.size >= 3 && isAllWord(words[2])) Bukkit.getWorlds() else listOf(world)
        for (w in targets) w.setGameRule(typed, value)
        ess.messages.send(sender, "world-gamerule-updated", ph.value(value.toString()).world(if (targets.size > 1) ess.messages.raw("world-all-worlds") else world.name))
    }

    /** 규칙 값 글 → 값(참·거짓 규칙은 켜기/끄기·on/off·1/0 도). 못 읽으면 null. 검증기도 부른다. */
    internal fun parseRuleValue(rule: GameRule<*>, raw: String): Any? {
        val text = raw.lowercase()
        return when (rule.type) {
            Boolean::class.javaObjectType -> when (text) {
                "true", "켜기", "켬", "on", "1", "yes" -> true
                "false", "끄기", "끔", "off", "0", "no" -> false
                else -> null
            }
            Int::class.javaObjectType -> text.toIntOrNull()
            else -> null
        }
    }

    private fun suggestGameRule(ctx: WCtx, before: List<String>, typed: String): List<String> {
        val ruleNames = rules().map { ruleKey(it) }
        return when (before.size) {
            0 -> ruleNames + worldNames()
            1 -> if (findRule(before[0]) != null) listOf("true", "false") else ruleNames
            2 -> if (findRule(before[0]) != null) listOf("all") else listOf("true", "false")
            else -> emptyList()
        }
    }

    // =============================================================================================
    // 청크 복구
    // =============================================================================================

    private val fixSubs = mapOf(
        "상태" to "stats", "stats" to "stats", "일시정지" to "pause", "pause" to "pause", "계속" to "continue", "continue" to "continue",
        "멈춤" to "stop", "stop" to "stop", "전부멈춤" to "stopall", "stopall" to "stopall", "속도" to "speed", "speed" to "speed",
        "자동속도" to "autospeed", "autospeed" to "autospeed", "메시지" to "messages", "messages" to "messages", "정보" to "info", "info" to "info",
        "복구" to "fix", "fix" to "fix", "시작" to "start", "start" to "start",
    )

    private fun fixChunk(ctx: WCtx, raw: String) {
        val sender = ctx.source.sender
        val words = Args.split(raw)
        if (words.isEmpty()) return ess.messages.send(sender, "world-fix-usage")
        val sub = fixSubs[words[0].lowercase()]
        val rest = if (sub != null) words.drop(1) else words
        // w <월드> · r <범위|g> · c <x:z> (w:월드 처럼 붙여 써도 된다)
        val opts = HashMap<String, String>()
        val free = ArrayList<String>()
        var i = 0
        while (i < rest.size) {
            val w = rest[i]
            val key = w.substringBefore(':').lowercase()
            if (key in setOf("w", "r", "c", "월드", "범위", "가운데") && ':' in w) {
                opts[norm(key)] = w.substringAfter(':')
            } else if (w.lowercase() in setOf("w", "r", "c", "월드", "범위", "가운데") && i + 1 < rest.size) {
                opts[norm(w.lowercase())] = rest[i + 1]
                i++
            } else free += w
            i++
        }
        val worldArg = opts["w"]
        when (sub ?: "start") {
            "start" -> {
                val world = worldArg?.let { findWorld(it) ?: return ess.messages.send(sender, "world-unknown-world", Ph.of().value(it)) }
                    ?: (sender as? Player)?.world ?: return ess.messages.send(sender, "world-need-world")
                val range = opts["r"]?.let { r ->
                    if (r.equals("g", true) || isAllWord(r)) null else r.toIntOrNull()?.takeIf { it >= 0 } ?: return ess.messages.send(sender, "invalid-number", Ph.of().value(r))
                }
                val center = opts["c"]?.let { c ->
                    val x = c.substringBefore(':').toIntOrNull()
                    val z = c.substringAfter(':', "").toIntOrNull()
                    if (x == null || z == null) return ess.messages.send(sender, "world-fix-bad-center", Ph.of().value(c))
                    x to z
                } ?: (sender as? Player)?.takeIf { it.world == world }?.location?.let { (it.blockX shr 4) to (it.blockZ shr 4) } ?: (0 to 0)
                if (range == null && opts["r"] == null && free.isNotEmpty()) return ess.messages.send(sender, "world-fix-usage")
                feature.chunks.start(sender, world, range, center)
            }
            "stopall" -> ess.messages.send(sender, "world-fix-stopped-all", Ph.of().count(feature.chunks.stopAll()))
            else -> {
                val scan = pickScan(sender, worldArg) ?: return
                when (sub) {
                    "stats" -> feature.chunks.stats(sender, scan)
                    "pause" -> {
                        feature.chunks.pause(scan, true)
                        ess.messages.send(sender, "world-fix-paused")
                    }
                    "continue" -> {
                        feature.chunks.pause(scan, false)
                        ess.messages.send(sender, "world-fix-continuing")
                    }
                    "stop" -> {
                        if (scan.finished) return ess.messages.send(sender, "world-fix-none")
                        feature.chunks.stop(scan)
                    }
                    "speed" -> {
                        val n = free.firstOrNull()?.toIntOrNull()?.takeIf { it in 1..100_000 } ?: return ess.messages.send(sender, "invalid-number", Ph.of().value(free.firstOrNull() ?: ""))
                        scan.speed = n
                        ess.messages.send(sender, "world-fix-speed", Ph.of().value(n.toString()))
                    }
                    "autospeed" -> {
                        scan.autoSpeed = parseToggle(free.firstOrNull()) ?: !scan.autoSpeed
                        ess.messages.send(sender, "world-fix-autospeed", Ph.of().value(bool(scan.autoSpeed)))
                    }
                    "messages" -> {
                        scan.messages = parseToggle(free.firstOrNull()) ?: !scan.messages
                        ess.messages.send(sender, "world-fix-messages", Ph.of().value(bool(scan.messages)))
                    }
                    "info" -> {
                        if (scan.found.isEmpty()) return ess.messages.send(sender, "world-fix-no-info")
                        ess.messages.send(sender, "world-fix-progress", feature.chunks.progress(scan))
                        scan.found.take(INFO_LINES).forEachIndexed { n, f ->
                            ess.messages.send(sender, "world-fix-scan-line", Ph.of().count(n + 1).raw("{x}", f.x.toString()).raw("{z}", f.z.toString()).reason(f.reason))
                        }
                        if (scan.found.size > INFO_LINES) ess.messages.send(sender, "world-fix-more", Ph.of().count(scan.found.size - INFO_LINES))
                    }
                    "fix" -> {
                        if (!scan.finished) return ess.messages.send(sender, "world-fix-still-running")
                        if (scan.found.isEmpty()) return ess.messages.send(sender, "world-fix-no-info")
                        ess.messages.send(sender, "world-fix-fix-started", Ph.of().count(feature.chunks.fix(sender, scan)))
                    }
                }
            }
        }
    }

    private fun norm(key: String): String = when (key) {
        "월드" -> "w"
        "범위" -> "r"
        "가운데" -> "c"
        else -> key
    }

    private fun pickScan(sender: CommandSender, worldArg: String?): ChunkRepair.Scan? {
        if (worldArg != null) return feature.chunks.scanOf(worldArg) ?: null.also { ess.messages.send(sender, "world-fix-none") }
        val running = feature.chunks.running()
        val mine = running.firstOrNull { it.owner != null && it.owner == (sender as? Player)?.uniqueId }
        if (mine != null) return mine
        if (running.size == 1) return running.first()
        if (running.size > 1) return null.also { ess.messages.send(sender, "world-fix-pick-world") }
        val here = (sender as? Player)?.world?.name
        return here?.let { feature.chunks.scanOf(it) } ?: null.also { ess.messages.send(sender, "world-fix-none") }
    }

    private fun parseToggle(word: String?): Boolean? = when (word?.lowercase()) {
        null -> null
        "true", "on", "켜기", "켬", "1" -> true
        "false", "off", "끄기", "끔", "0" -> false
        else -> null
    }

    private fun suggestFixChunk(ctx: WCtx, before: List<String>, typed: String): List<String> = when {
        before.isEmpty() -> listOf("w", "r", "c", "상태", "일시정지", "계속", "멈춤", "전부멈춤", "속도", "자동속도", "메시지", "정보", "복구")
        before.last().lowercase() == "w" -> worldNames()
        before.last().lowercase() == "r" -> listOf("10", "50", "g")
        before.last().lowercase() == "c" -> (ctx.source.sender as? Player)?.location?.let { listOf("${it.blockX shr 4}:${it.blockZ shr 4}") } ?: listOf("0:0")
        before.first().lowercase() in setOf("자동속도", "autospeed", "메시지", "messages") -> listOf("켜기", "끄기")
        else -> listOf("w", "r", "c")
    }

    // =============================================================================================
    // 조명 수정
    // =============================================================================================

    private fun lightFix(ctx: WCtx, raw: String) {
        val sender = ctx.source.sender
        val words = Args.split(raw)
        when (words.firstOrNull()?.lowercase()) {
            // 멈춤 알림(끝난 청크 수)은 stop 이 보낸다.
            "멈춤", "stop" -> return if (!feature.light.stop(sender)) ess.messages.send(sender, "world-lfix-none") else Unit
            "전부멈춤", "stopall" -> return ess.messages.send(sender, "world-lfix-stopped-all", Ph.of().count(feature.light.stopAll()))
        }
        var range = 3
        var target: Player? = sender as? Player
        for (w in words) {
            val n = w.toIntOrNull()
            if (n != null) {
                range = n
                continue
            }
            if (!sender.hasPermission(WorldFeature.LFIX_OTHERS)) return ess.messages.send(sender, "no-permission")
            target = ess.args.online(sender, w) ?: return ess.messages.send(sender, "player-not-found", Ph.of().value(w))
        }
        target ?: return ess.messages.send(sender, "world-need-player")
        feature.light.start(sender, target, range)
    }

    private fun suggestLightFix(ctx: WCtx, before: List<String>, typed: String): List<String> = when (before.size) {
        0 -> listOf("1", "3", "5", "멈춤", "전부멈춤")
        1 -> if (ctx.source.sender.hasPermission(WorldFeature.LFIX_OTHERS)) kr.inmc.core.integration.Vanish.visibleNames(ctx.source.sender).toList() else emptyList()
        else -> emptyList()
    }

    // =============================================================================================
    // 몹 제거
    // =============================================================================================

    private fun killAll(ctx: WCtx, raw: String) {
        val sender = ctx.source.sender
        val options = when (val parsed = KillAll.parse(Args.split(raw))) {
            is KillAll.Parse.Bad -> return ess.messages.send(sender, "world-killall-bad", Ph.of().value(parsed.token))
            is KillAll.Parse.Ok -> parsed.options
        }
        if (options.force && !sender.hasPermission(WorldFeature.KILLALL_FORCE)) return ess.messages.send(sender, "no-permission")
        val here = sender as? Player
        val world = options.world?.let { findWorld(it) ?: return ess.messages.send(sender, "world-unknown-world", Ph.of().value(it)) } ?: here?.world
            ?: return ess.messages.send(sender, "world-need-world")
        val center = here?.takeIf { it.world == world }?.location
        // -r 은 그 사람 자리 둘레 — 가운데가 없으면(콘솔 · -w 로 다른 월드) 반경을 버리고 월드 전체를 지우지 않고 거절한다(2026-10-10 리뷰).
        val radius = when (val scope = KillAll.scope(options.radius, s.killAll.radius, s.killAll.maxRadius, hasCenter = center != null, worldGiven = options.world != null)) {
            KillAll.Scope.NeedCenter -> return ess.messages.send(sender, "world-killall-need-center")
            KillAll.Scope.Whole -> null
            is KillAll.Scope.Radius -> scope.radius.also {
                if (scope.capped) ess.messages.send(sender, "world-killall-capped", Ph.of().value(s.killAll.maxRadius.toString()))
            }
        }
        val candidates: Collection<Entity> = if (radius != null && center != null) world.getNearbyEntities(center, radius.toDouble(), radius.toDouble(), radius.toDouble()) { it.location.distanceSquared(center) <= radius.toDouble() * radius }
        else world.entities
        val removed = LinkedHashMap<String, Int>()
        val byCategory = LinkedHashMap<KillAll.Category, MutableMap<String, Int>>()
        var protectedCount = 0
        for (entity in candidates) {
            val category = KillAll.categoryOf(entity) ?: continue
            if (!KillAll.selected(entity, category, options)) continue
            if (KillAll.protection(entity, options) != null) {
                protectedCount++
                continue
            }
            val type = typeName(entity)
            if (options.listOnly) {
                byCategory.getOrPut(category) { LinkedHashMap() }.merge(type, 1, Int::plus)
                continue
            }
            if (options.lightning) world.strikeLightningEffect(entity.location)
            removed.merge(type, 1, Int::plus)
            dropCargo(entity)
            entity.remove()
        }
        if (options.listOnly) {
            if (byCategory.isEmpty()) return ess.messages.send(sender, "world-killall-nothing")
            for ((category, types) in byCategory) {
                ess.messages.send(sender, "world-killall-list", Ph.of().raw("{분류}", ess.messages.raw("world-killall-cat-${category.name.lowercase()}"))
                    .count(types.values.sum()).value(types.entries.joinToString(", ") { "${it.key} ${it.value}" }))
            }
            if (protectedCount > 0) ess.messages.send(sender, "world-killall-protected", Ph.of().count(protectedCount))
            return
        }
        if (options.silent) return
        if (removed.isEmpty()) {
            ess.messages.send(sender, "world-killall-nothing")
        } else {
            ess.messages.send(sender, "world-killall-total", Ph.of().count(removed.values.sum()).value(removed.entries.joinToString(", ") { "${it.key} ${it.value}" }))
        }
        if (protectedCount > 0) ess.messages.send(sender, "world-killall-protected", Ph.of().count(protectedCount))
    }

    /**
     * 짐(-force 로 지우는 상자 수레·상자 보트·말의 상자)과 **몹이 주운 장비**는 그 자리에 떨군다 — remove() 는 그냥 없앤다.
     * 주운 장비 = 떨굼 확률이 1 보다 큰 칸([KillAll.pickedUp] — 바닐라는 주운 아이템을 2.0 으로 표시한다). 태어날 때 입은 장비(확률 0.085)와 플러그인이 준
     * 반드시 떨구는 장비(정확히 1.0 — 죽여야 얻는 보스 장비)는 떨구지 않는다 — 지우기가 장비 복사·농장이 되지 않게(2026-10-10 리뷰: 죽은 사람의 검을 주운
     * 좀비를 지우면 검이 사라지던 것 · 2026-10-14 리뷰: 1 이상으로 보면 보스 장비까지 떨구던 것).
     */
    internal fun dropCargo(entity: Entity) {
        if (entity is Player) return
        val at = entity.location
        if (entity is LivingEntity) {
            val eq = entity.equipment
            if (eq != null) for (slot in KillAll.EQUIPMENT_SLOTS) {
                val stack = runCatching { eq.getItem(slot) }.getOrNull() ?: continue
                if (stack.type.isAir || !KillAll.pickedUp(runCatching { eq.getDropChance(slot) }.getOrDefault(0f))) continue
                at.world.dropItemNaturally(at, stack.clone())
                eq.setItem(slot, null)
            }
        }
        val inventory = (entity as? org.bukkit.inventory.InventoryHolder)?.inventory ?: return
        if (inventory.isEmpty) return
        for (stack in inventory.contents) if (stack != null && !stack.type.isAir) at.world.dropItemNaturally(at, stack)
        inventory.clear()
    }

    private fun typeName(entity: Entity): String = runCatching { VanillaNames.of(entity.type) }.getOrNull() ?: entity.type.key.key

    private fun suggestKillAll(ctx: WCtx, before: List<String>, typed: String): List<String> {
        if (typed.startsWith("-m:") || typed.startsWith("-종류:")) {
            val prefix = typed.substringBefore(':') + ":" + typed.substringAfter(':').substringBeforeLast(',', "").let { if (it.isEmpty()) "" else "$it," }
            return org.bukkit.entity.EntityType.entries.filter { it.isAlive && it.isSpawnable }.map { prefix + it.key.key }
        }
        if (typed.startsWith("-w:")) return worldNames().map { "-w:$it" }
        return listOf("-monsters", "-pets", "-npc", "-animals", "-ambient", "-vehicles", "-named", "-withitems", "-all", "-force", "-lightning", "-list", "-s",
            "-m:", "-r:${s.killAll.radius}", "-w:").filter { it !in before }
    }

    // =============================================================================================
    // 날씨
    // =============================================================================================

    private val weatherWords = mapOf(
        "sun" to TimeWeather.Weather.SUN, "clear" to TimeWeather.Weather.SUN, "맑음" to TimeWeather.Weather.SUN, "해" to TimeWeather.Weather.SUN,
        "rain" to TimeWeather.Weather.RAIN, "비" to TimeWeather.Weather.RAIN,
        "storm" to TimeWeather.Weather.STORM, "thunder" to TimeWeather.Weather.STORM, "폭풍" to TimeWeather.Weather.STORM, "천둥" to TimeWeather.Weather.STORM,
    )

    private fun weather(ctx: WCtx, raw: String) {
        val sender = ctx.source.sender
        val words = Args.split(raw)
        val implied = weatherWords[label(ctx)]
        if (implied == null && words.isEmpty()) return showWeather(sender, (sender as? Player)?.world?.let { listOf(it) } ?: Bukkit.getWorlds())
        if (!sender.hasPermission(WorldFeature.WEATHER)) {
            return if (implied == null && words.size == 1 && findWorld(words[0]) != null) showWeather(sender, listOf(findWorld(words[0])!!))
            else ess.messages.send(sender, "no-permission")
        }
        var type = implied
        var lock = false
        var unlock = false
        var seconds: Long? = null
        val worlds = ArrayList<World>()
        for (w in words) {
            val lower = w.lowercase()
            when {
                type == null && lower in weatherWords -> type = weatherWords.getValue(lower)
                lower in setOf("lock", "고정") -> lock = true
                lower in setOf("unlock", "고정해제", "해제") -> unlock = true
                isAllWord(lower) -> worlds += Bukkit.getWorlds()
                findWorld(w) != null -> worlds += findWorld(w)!!
                DurationText.parse(w) != null -> seconds = DurationText.parse(w)
                else -> return ess.messages.send(sender, "world-weather-bad", Ph.of().value(w))
            }
        }
        // 낱말이 월드 이름(·all)뿐이면 보기 — 자기 월드를 채우기 전에 정한다(`/날씨 고정` 이 보기로 새지 않게).
        val onlyWorlds = type == null && !lock && !unlock && seconds == null && worlds.isNotEmpty()
        if (worlds.isEmpty()) (sender as? Player)?.world?.let { worlds += it } ?: return ess.messages.send(sender, "world-need-world")
        if (type == null) {
            if (unlock) {
                worlds.distinct().forEach { feature.timeWeather.unlock(it) }
                return ess.messages.send(sender, "world-weather-unlocked", Ph.of().world(worlds.distinct().joinToString(", ") { it.name }))
            }
            if (onlyWorlds) return showWeather(sender, worlds.distinct())
            if (lock && seconds == null) {
                // `/날씨 고정` — 지금 날씨 그대로 고정(CMI lock).
                for (world in worlds.distinct()) {
                    feature.timeWeather.lock(world)
                    ess.messages.send(sender, "world-weather-set", Ph.of().world(world.name).value(feature.timeWeather.weatherName(feature.timeWeather.weatherOf(world)))
                        .time(ess.messages.raw("world-weather-locked")))
                }
                return
            }
            return ess.messages.send(sender, "world-weather-usage")
        }
        for (world in worlds.distinct()) {
            feature.timeWeather.setWeather(world, type, seconds, lock)
            val time = if (lock) ess.messages.raw("world-weather-locked") else feature.timeWeather.weatherLeftSeconds(world)?.let { Durations.formatShort(it) } ?: "-"
            ess.messages.send(sender, "world-weather-set", Ph.of().world(world.name).value(feature.timeWeather.weatherName(type)).time(time))
        }
    }

    private fun showWeather(sender: CommandSender, worlds: List<World>) {
        for (world in worlds) {
            val tw = feature.timeWeather
            val left = tw.weatherLeftSeconds(world)
            val time = if (left == null) ess.messages.raw("world-weather-locked") else Durations.formatShort(left)
            ess.messages.send(sender, "world-weather-current", Ph.of().world(world.name).value(tw.weatherName(tw.weatherOf(world))).time(time))
        }
    }

    private fun suggestWeather(ctx: WCtx, before: List<String>, typed: String): List<String> {
        if (!ctx.source.sender.hasPermission(WorldFeature.WEATHER)) return worldNames()
        val implied = weatherWords[label(ctx)]
        val hasType = implied != null || before.any { it.lowercase() in weatherWords }
        return (if (!hasType) listOf("맑음", "비", "폭풍", "고정해제") else listOf("고정", "10m", "1h")) + worldNames() + "all"
    }

    // =============================================================================================
    // 시간
    // =============================================================================================

    private val timeSubs = mapOf(
        "더하기" to "add", "add" to "add", "빼기" to "take", "take" to "take", "정지" to "freeze", "멈춤" to "freeze", "freeze" to "freeze",
        "재개" to "unfreeze", "unfreeze" to "unfreeze", "실제" to "realtime", "현실" to "realtime", "realtime" to "realtime",
        "자동실제" to "auto", "autorealtime" to "auto",
    )

    private val presetWords = mapOf("낮" to "day", "day" to "day", "밤" to "night", "night" to "night", "아침" to "morning", "morning" to "morning",
        "저녁" to "dusk", "dusk" to "dusk", "해질녘" to "dusk")

    private fun time(ctx: WCtx, raw: String) {
        val sender = ctx.source.sender
        val words = Args.split(raw).toMutableList()
        val labelPreset = presetWords[label(ctx)]
        if (labelPreset == null && words.isEmpty()) return showTime(sender, (sender as? Player)?.world?.let { listOf(it) } ?: Bukkit.getWorlds())
        if (!sender.hasPermission(WorldFeature.TIME)) {
            return if (labelPreset == null && words.size == 1 && findWorld(words[0]) != null) showTime(sender, listOf(findWorld(words[0])!!))
            else ess.messages.send(sender, "no-permission")
        }
        val smooth = words.removeAll { it.lowercase() in setOf("-smooth", "-부드럽게") }
        val sub = if (labelPreset == null) words.firstOrNull()?.lowercase()?.let { timeSubs[it] } else null
        if (sub != null) words.removeAt(0)
        // 남은 낱말: 값(시각·양·시작/정지) + 월드들
        val worlds = ArrayList<World>()
        val values = ArrayList<String>()
        for (w in words) when {
            isAllWord(w) -> worlds += Bukkit.getWorlds()
            findWorld(w) != null && TimeText.parse(w) == null -> worlds += findWorld(w)!!
            else -> values += w
        }
        if (worlds.isEmpty()) (sender as? Player)?.world?.let { worlds += it } ?: return ess.messages.send(sender, "world-need-world")
        val targets = worlds.distinct()
        val tw = feature.timeWeather
        when (sub) {
            "add", "take" -> {
                val delta = values.firstOrNull()?.let { TimeText.parseDelta(it) } ?: return ess.messages.send(sender, "world-time-cant-set", Ph.of().value(values.firstOrNull() ?: ""))
                for (w in targets) {
                    tw.addTime(w, if (sub == "add") delta else -delta)
                    ess.messages.send(sender, "world-time-set", tw.timePh(w))
                }
            }
            "freeze", "unfreeze" -> for (w in targets) {
                tw.freeze(w, sub == "freeze")
                ess.messages.send(sender, if (sub == "freeze") "world-time-frozen" else "world-time-unfrozen", Ph.of().world(w.name))
            }
            "realtime" -> for (w in targets) {
                tw.setTime(w, tw.realTicks(), smooth || s.time.autoRealSmooth)
                val real = tw.realTicks()
                ess.messages.send(sender, "world-time-set", Ph.of().world(w.name).raw("{24시}", TimeText.format24(real)).raw("{12시}", TimeText.format12(real)).raw("{틱}", real.toString()))
            }
            "auto" -> {
                val on = when (values.firstOrNull()?.lowercase()) {
                    "시작", "start", "켜기", "on" -> true
                    "정지", "stop", "끄기", "off" -> false
                    else -> return ess.messages.send(sender, "world-time-usage")
                }
                for (w in targets) {
                    tw.setAuto(w, on)
                    ess.messages.send(sender, if (on) "world-time-auto-start" else "world-time-auto-stop", Ph.of().world(w.name))
                }
                feature.saveAutoReal(tw.autoWorlds())
            }
            else -> {
                val text = labelPreset ?: values.firstOrNull() ?: return ess.messages.send(sender, "world-time-usage")
                val preset = presetWords[text.lowercase()] ?: text.lowercase()
                val ticks = s.time.presets[preset] ?: TimeText.parse(text)?.ticks ?: return ess.messages.send(sender, "world-time-cant-set", Ph.of().value(text))
                for (w in targets) {
                    if (tw.isAuto(w)) ess.messages.send(sender, "world-time-auto-overrides", Ph.of().world(w.name))
                    tw.setTime(w, ticks, smooth)
                    val ph = Ph.of().world(w.name).raw("{24시}", TimeText.format24(ticks)).raw("{12시}", TimeText.format12(ticks)).raw("{틱}", ticks.toString())
                    ess.messages.send(sender, "world-time-set", ph)
                }
            }
        }
    }

    private fun showTime(sender: CommandSender, worlds: List<World>) {
        val tw = feature.timeWeather
        for (world in worlds) {
            val ph = tw.timePh(world).raw("{상태}", (if (tw.isFrozen(world) && !tw.isAuto(world)) ess.messages.raw("world-time-frozen-mark") else "") +
                (if (tw.isAuto(world)) ess.messages.raw("world-time-real-mark") else ""))
            ess.messages.send(sender, "world-time-check", ph)
        }
    }

    private fun suggestTime(ctx: WCtx, before: List<String>, typed: String): List<String> {
        if (!ctx.source.sender.hasPermission(WorldFeature.TIME)) return worldNames()
        if (presetWords[label(ctx)] != null) return worldNames() + listOf("all", "-smooth")
        val first = before.firstOrNull()?.lowercase()
        return when {
            before.isEmpty() -> listOf("낮", "밤", "아침", "저녁", "13:00", "1pm", "7000t", "더하기", "빼기", "정지", "재개", "실제", "자동실제") + s.time.presets.keys
            timeSubs[first] == "auto" && before.size == 1 -> listOf("시작", "정지")
            timeSubs[first] in setOf("add", "take") && before.size == 1 -> listOf("0:30", "1", "500t")
            else -> worldNames() + listOf("all", "-smooth")
        }
    }

    private companion object {
        const val INFO_LINES = 20
    }
}
