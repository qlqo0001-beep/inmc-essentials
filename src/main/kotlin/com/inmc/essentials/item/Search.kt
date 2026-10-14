package com.inmc.essentials.item

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import kr.inmc.core.integration.CustomItemHook
import kr.inmc.core.item.ItemRef
import kr.inmc.core.util.AtomicFiles
import org.bukkit.Bukkit
import org.bukkit.block.ShulkerBox
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.inventory.meta.BundleMeta
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `/검색 <조건…>`(search) — 카탈로그 cmd6:cmd-search. 접속자(메인)와 **접속하지 않은 사람**(`players/data/<uuid>.dat` — 비동기·읽기만)의
 * 가방·엔더·장비와 그 안의 셜커·꾸러미에서 아이템을 찾는다. 결과는 쪽으로(`/검색 결과 [쪽]`), 한 번에 하나만(`/검색 취소`).
 *
 * 조건: 아이템(`diamond`·`inmc:<id>`·`mmoitems:T:I`) 여럿 = 그중 하나 · `hand`(내 손과 같은 것) · `oversize`(묶음 최대치 넘은 것) ·
 * `이름:<글>` · `설명:<글>` · `인챈트:<id>[:최소레벨]` · `최소:<개수>`(한 사람 합) · `모드:survival|creative|adventure|spectator`.
 * `/검색 지우기 <조건…>` → [확인] — **접속 중인 사람에게서만** 지운다(남의 .dat 는 고치지 않는다).
 *
 * 스레드: 파일 목록·NBT 읽기·아이템 풀기만 비동기([BATCH_FILES] 개씩), 고르기(커스텀 아이템 알아보기·메타·셜커 안)와 이름 찾기는
 * 메인에서 그 묶음씩 — 비동기 → 메인 → 비동기 사슬이라 메모리도 한 묶음만 든다. 결과는 [RESULT_TTL_MS] 또는 주인이 나가면 지운다.
 */
class Search(private val ess: Ess, private val feature: ItemsFeature) {

    data class Hit(val player: String, val id: UUID, val where: String, val count: Int)

    data class Result(val hits: List<Hit>, val checked: Int, val at: Long)

    private val results = ConcurrentHashMap<String, Result>()
    private val purgePending = ConcurrentHashMap<String, Pair<String, Long>>()

    @Volatile
    private var running: AtomicBoolean? = null

    fun register() {
        val root = Commands.literal("검색").requires { it.sender.hasPermission(ItemsFeature.SEARCH) }
            .executes { ctx -> ess.messages.send(ctx.source.sender, "items-search-usage"); 1 }
        for (word in listOf("결과", "results")) root.then(Commands.literal(word)
            .executes { ctx -> show(ctx.source.sender, 0); 1 }
            .then(Commands.argument("쪽", IntegerArgumentType.integer(1)).executes { ctx -> show(ctx.source.sender, IntegerArgumentType.getInteger(ctx, "쪽") - 1); 1 }))
        for (word in listOf("취소", "cancel")) root.then(Commands.literal(word).executes { ctx -> cancel(ctx.source.sender); 1 })
        for (word in listOf("지우기", "purge")) root.then(Commands.literal(word).requires { it.sender.hasPermission(ItemsFeature.SEARCH_PURGE) }
            .then(Commands.argument("조건", StringArgumentType.greedyString()).suggests(hints)
                .executes { ctx -> purge(ctx.source.sender, StringArgumentType.getString(ctx, "조건")); 1 }))
        root.then(Commands.argument("조건", StringArgumentType.greedyString()).suggests(hints)
            .executes { ctx -> start(ctx.source.sender, StringArgumentType.getString(ctx, "조건")); 1 })
        ess.commands.add(root.build(), "모든 플레이어(오프라인 포함)의 가방·엔더·셜커에서 아이템 찾기", listOf("search"))
        ess.ticks.add("items-search-evict", 60) { now -> evict(now) }
    }

    /** 오래되거나 주인이 나간 결과·지우기 확인을 지운다(메모리). */
    private fun evict(now: Long) {
        results.entries.removeIf { (who, r) -> now - r.at > RESULT_TTL_MS || ownerGone(who) }
        purgePending.entries.removeIf { (who, p) -> now - p.second > 30_000L || ownerGone(who) }
    }

    private fun ownerGone(who: String): Boolean =
        runCatching { UUID.fromString(who) }.getOrNull()?.let { Bukkit.getPlayer(it) == null } ?: false

    // --- 찾기 ----------------------------------------------------------------------------------------

    fun start(sender: CommandSender, text: String) {
        val criteria = when (val parsed = SearchCriteria.parse(text)) {
            is SearchCriteria.Parsed.Error -> return ess.messages.send(sender, "items-search-bad", Ph.of().value(parsed.token))
            is SearchCriteria.Parsed.Ok -> parsed.criteria
        }
        val matcher = matcher(sender, criteria) ?: return
        if (running != null) return ess.messages.send(sender, "items-search-busy")
        val flag = AtomicBoolean(false)
        running = flag
        val job = Job(sender, text, criteria, matcher, flag, key(sender), feature.settings.search.maxResults)
        try {
            job.scanOnline()
            job.listFiles()
        } catch (t: Throwable) {
            running = null // 무엇이 던져도 다음 /검색 이 '바쁨' 에 갇히지 않게
            throw t
        }
    }

    /** 한 번의 검색. 상태는 메인에서만 만진다(비동기 쪽은 읽은 것을 넘길 뿐). */
    private inner class Job(
        val sender: CommandSender, val text: String, val criteria: SearchCriteria,
        val matcher: (ItemStack) -> Boolean, val flag: AtomicBoolean, val who: String, val max: Int,
    ) {
        private val hits = ArrayList<Hit>()
        private var checked = 0
        private var unreadable = 0
        private var total = 0
        private var files: List<File> = emptyList()
        private var next = 0
        private var reported = 0
        private val onlineIds = HashSet<UUID>()

        /** 파일 하나에서 읽은 것 — 비동기에서 만들어 메인으로. */
        inner class Loaded(val id: UUID, val mode: String?, val items: List<Pair<String, ItemStack>>)

        /** 접속자는 지금(메인) — 가방이 계속 바뀌므로 그 순간의 것. */
        fun scanOnline() {
            for (p in Bukkit.getOnlinePlayers()) {
                onlineIds += p.uniqueId
                if (!kr.inmc.core.integration.Vanish.canSee(sender, p)) continue // 투명이 새지 않게(규칙 8) — 볼 수 없는 사람은 결과에 없다
                checked++
                if (!criteria.allowsMode(p.gameMode.name)) continue
                collect(p.name, p.uniqueId, matcher, criteria, hits) { visit -> scanInventory(p.inventory, PlayerDataFile.WHERE_INVENTORY, visit); scanInventory(p.enderChest, PlayerDataFile.WHERE_ENDER, visit) }
            }
        }

        /** 파일 목록은 비동기로(디스크). */
        fun listFiles() {
            val worldFolder = Bukkit.getWorlds().first().worldFolder
            val skip = onlineIds.toSet()
            async {
                val list = PlayerDataFile.folders(worldFolder)
                    .flatMap { dir -> dir.listFiles { f -> f.name.endsWith(".dat") }?.toList().orEmpty() }
                    .filter { f -> runCatching { UUID.fromString(f.name.removeSuffix(".dat")) }.getOrNull()?.let { it !in skip } == true }
                    .distinctBy { it.name }
                main {
                    files = list
                    total = list.size + checked
                    ess.messages.send(sender, "items-search-started", Ph.of().count(total))
                    readNext()
                }
            }
        }

        /** 다음 묶음을 비동기로 읽고, 메인에서 고른 뒤 다시 다음 묶음. */
        private fun readNext() {
            if (flag.get() || hits.size >= max || next >= files.size) return finish()
            val batch = files.subList(next, (next + BATCH_FILES).coerceAtMost(files.size)).toList()
            next += batch.size
            async {
                val loaded = batch.map { file ->
                    runCatching {
                        val data = PlayerDataFile.read(Nbt.readFile(file))
                        val mode = when (data.gameType) { 0 -> "SURVIVAL"; 1 -> "CREATIVE"; 2 -> "ADVENTURE"; 3 -> "SPECTATOR"; else -> null }
                        val items = data.items.mapNotNull { entry -> runCatching { ItemStack.deserializeBytes(entry.bytes) }.getOrNull()?.let { entry.where to it } }
                        Loaded(UUID.fromString(file.name.removeSuffix(".dat")), mode, items)
                    }.getOrNull()
                }
                main {
                    try {
                        process(loaded)
                    } catch (t: Throwable) {
                        ess.logger.warning("/검색 고르기 실패 — 여기까지의 결과로 끝냅니다: $t")
                        flag.set(true)
                    }
                    readNext()
                }
            }
        }

        private fun process(loaded: List<Loaded?>) {
            for (data in loaded) {
                if (data == null) { unreadable++; continue }
                checked++
                if (data.mode != null && !criteria.allowsMode(data.mode)) continue
                val name = ess.users.get(data.id)?.name ?: data.id.toString()
                collect(name, data.id, matcher, criteria, hits) { visit -> for ((where, stack) in data.items) walk(stack, where, 0, visit) }
            }
            if (checked / PROGRESS_EVERY > reported) {
                reported = checked / PROGRESS_EVERY
                ess.messages.send(sender, "items-search-progress", Ph.of().count(checked).amount(total.toString()).value(hits.size.toString()))
            }
        }

        private fun finish() {
            running = null
            val result = Result(hits.take(max), checked, System.currentTimeMillis())
            results[who] = result
            if (flag.get()) ess.messages.send(sender, "items-search-cancelled", Ph.of().count(result.checked))
            ess.messages.send(sender, "items-search-done", Ph.of().count(result.hits.size).amount(result.checked.toString()).value(unreadable.toString()))
            show(sender, 0)
            if (feature.settings.search.logIntoFile) log(sender, text, result)
        }

        private fun async(block: () -> Unit) {
            Bukkit.getScheduler().runTaskAsynchronously(ess.plugin, Runnable {
                try {
                    block()
                } catch (t: Throwable) {
                    ess.logger.warning("/검색 파일 읽기 실패: $t")
                    main { flag.set(true); finish() }
                }
            })
        }

        private fun main(block: () -> Unit) {
            if (!ess.plugin.isEnabled) { running = null; return }
            Bukkit.getScheduler().runTask(ess.plugin, Runnable { block() })
        }
    }

    private fun cancel(sender: CommandSender) {
        val flag = running ?: return ess.messages.send(sender, "items-search-none-running")
        flag.set(true)
        ess.messages.send(sender, "items-search-cancelling")
    }

    fun show(sender: CommandSender, page: Int) {
        val result = results[key(sender)] ?: return ess.messages.send(sender, "items-search-no-result")
        if (result.hits.isEmpty()) return ess.messages.send(sender, "items-search-empty")
        val size = feature.settings.search.pageSize
        val pages = (result.hits.size + size - 1) / size
        val p = page.coerceIn(0, pages - 1)
        ess.messages.send(sender, "items-search-header", Ph.of().count(result.hits.size).raw("{쪽}", "${p + 1}/$pages"))
        result.hits.drop(p * size).take(size).forEachIndexed { i, hit ->
            ess.messages.send(sender, "items-search-entry", Ph.of().rank((p * size + i + 1).toString()).player(hit.player).value(hit.where).count(hit.count))
        }
        if (p < pages - 1) ess.messages.send(sender, "items-search-next", Ph.of().raw("{다음}", (p + 2).toString()))
    }

    private fun log(sender: CommandSender, text: String, result: Result) {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file: File = ess.io.file("search", "search-$stamp.txt")
        val body = buildString {
            appendLine("# /검색 $text — ${sender.name} — 본 사람 ${result.checked}명 · 찾음 ${result.hits.size}")
            for (hit in result.hits) appendLine("${hit.player}\t${hit.id}\t${hit.where}\t${hit.count}")
        }
        ess.io.asyncRun { AtomicFiles.write(file, body) }
    }

    // --- 지우기 ---------------------------------------------------------------------------------------

    private fun purge(sender: CommandSender, text: String) {
        if (!feature.settings.search.enablePurge) return ess.messages.send(sender, "items-search-purge-disabled")
        val who = key(sender)
        if (text.trim().lowercase() in setOf("확인", "confirm")) {
            val (criteriaText, at) = purgePending.remove(who) ?: return ess.messages.send(sender, "items-clear-no-pending")
            if (System.currentTimeMillis() - at > 30_000L) return ess.messages.send(sender, "items-clear-no-pending")
            val criteria = (SearchCriteria.parse(criteriaText) as? SearchCriteria.Parsed.Ok)?.criteria ?: return
            if (!criteria.selectsItems) return ess.messages.send(sender, "items-search-purge-no-item")
            val matcher = matcher(sender, criteria) ?: return
            var removed = 0
            var people = 0
            for (p in Bukkit.getOnlinePlayers()) {
                // 찾기(collect)와 같은 거르기 — 모드가 다르거나 합이 최소에 못 미치는 사람은 건드리지 않는다.
                if (!criteria.allowsMode(p.gameMode.name)) continue
                if (criteria.minCount > 0 && matchedTotal(p, matcher) < criteria.minCount) continue
                // 열린 셜커 배낭은 먼저 되써 넣고 닫는다 — 열어 둔 채 고치면 배낭이 다음 클릭에 창 내용을 다시 써 지운 것이 돌아온다.
                feature.backpack.closeFor(p)
                val n = purgeInventory(p.inventory, matcher) + purgeInventory(p.enderChest, matcher)
                if (n > 0) { removed += n; people++ }
            }
            return ess.messages.send(sender, "items-search-purged", Ph.of().count(removed).amount(people.toString()))
        }
        val parsed = SearchCriteria.parse(text) as? SearchCriteria.Parsed.Ok ?: return ess.messages.send(sender, "items-search-bad", Ph.of().value(text))
        // 아이템을 고르는 조건(아이템·hand·oversize·이름·설명·인챈트)이 없으면 모든 아이템이 걸린다 — 지우기에서는 받지 않는다.
        if (!parsed.criteria.selectsItems) return ess.messages.send(sender, "items-search-purge-no-item")
        purgePending[who] = text to System.currentTimeMillis()
        ess.messages.send(sender, "items-search-purge-confirm", Ph.of().value(text))
    }

    /** 한 사람의 가방·엔더(셜커·꾸러미 안 포함)에서 [matcher] 에 걸리는 개수 — 찾기의 '최소:' 와 같은 셈. */
    internal fun matchedTotal(p: Player, matcher: (ItemStack) -> Boolean): Int {
        var n = 0
        val visit: (String, ItemStack) -> Unit = { _, stack -> if (matcher(stack)) n += stack.amount }
        scanInventory(p.inventory, PlayerDataFile.WHERE_INVENTORY, visit)
        scanInventory(p.enderChest, PlayerDataFile.WHERE_ENDER, visit)
        return n
    }

    /** 지운 개수. 셜커·꾸러미 안은 그 아이템의 메타를 고쳐 지운다. */
    internal fun purgeInventory(inv: Inventory, matcher: (ItemStack) -> Boolean): Int {
        var removed = 0
        for (slot in 0 until inv.size) {
            val stack = inv.getItem(slot) ?: continue
            if (stack.type.isAir) continue
            if (matcher(stack)) { removed += stack.amount; inv.setItem(slot, null); continue }
            val inner = purgeNested(stack, matcher, 0)
            if (inner > 0) { removed += inner; inv.setItem(slot, stack) }
        }
        return removed
    }

    private fun purgeNested(stack: ItemStack, matcher: (ItemStack) -> Boolean, depth: Int): Int {
        if (depth > 3 || !stack.hasItemMeta()) return 0
        var removed = 0
        val meta = stack.itemMeta
        if (meta is BlockStateMeta && meta.hasBlockState()) {
            val box = meta.blockState as? ShulkerBox ?: return 0
            val contents = box.inventory.contents
            for ((i, inner) in contents.withIndex()) {
                if (inner == null || inner.type.isAir) continue
                if (matcher(inner)) { removed += inner.amount; contents[i] = null } else removed += purgeNested(inner, matcher, depth + 1)
            }
            if (removed > 0) { box.inventory.contents = contents; meta.blockState = box; stack.itemMeta = meta }
        } else if (meta is BundleMeta) {
            val kept = ArrayList<ItemStack>()
            for (inner in meta.items) if (matcher(inner)) removed += inner.amount else { removed += purgeNested(inner, matcher, depth + 1); kept += inner }
            if (removed > 0) { meta.setItems(kept); stack.itemMeta = meta }
        }
        return removed
    }

    // --- 고르기 ---------------------------------------------------------------------------------------

    /** 조건을 아이템 하나의 판정으로. 손이 비었는데 hand 를 쓰거나 모르는 참조면 알리고 null. */
    internal fun matcher(sender: CommandSender, c: SearchCriteria): ((ItemStack) -> Boolean)? {
        val refs = c.refs.map { raw -> ItemRef.parse(raw).takeIf { it !is ItemRef.None } ?: run { ess.messages.send(sender, "items-unknown-item", Ph.of().value(raw)); return null } }
        // 만들기 전용 참조(물고기·부여서·상점 블록)는 어떤 아이템에도 맞지 않는다 — 조용히 0개가 되지 않게 알린다.
        refs.firstOrNull { it is ItemRef.Namespaced && it.namespace.lowercase() in CustomItemHook.makeOnlyNamespaces() }
            ?.let { ess.messages.send(sender, "items-make-only", Ph.of().value(it.serialize())); return null }
        val hand = if (c.hand) (sender as? Player)?.inventory?.itemInMainHand?.takeIf { !it.type.isAir }?.clone()
            ?: run { ess.messages.send(sender, "items-hand-empty", Ph.of().value(sender.name)); return null } else null
        val enchants = c.enchants.map { (key, level) -> (ItemGive.enchantment(key) ?: run { ess.messages.send(sender, "items-unknown-enchant", Ph.of().value(key)); return null }) to level }
        val resolver = ess.itemResolver
        return { stack ->
            val kind = (refs.isEmpty() && hand == null && !c.oversize) ||
                refs.any { ref -> if (ref is ItemRef.Vanilla) stack.type == ref.material else ref in resolver.identifyAll(stack) } ||
                (hand != null && stack.isSimilar(hand)) || (c.oversize && stack.amount > stack.maxStackSize)
            kind && (c.name == null || (kr.inmc.core.item.StoredItem.plainName(stack)?.contains(c.name, ignoreCase = true) == true)) &&
                (c.lore == null || stack.itemMeta?.lore()?.any { ItemEdit.PLAIN.serialize(it).contains(c.lore, ignoreCase = true) } == true) &&
                enchants.all { (e, level) -> stack.getEnchantmentLevel(e) >= level }
        }
    }

    internal fun collect(name: String, id: UUID, matcher: (ItemStack) -> Boolean, c: SearchCriteria, hits: MutableList<Hit>, scan: ((String, ItemStack) -> Unit) -> Unit) {
        val counts = LinkedHashMap<String, Int>()
        scan { where, stack -> if (matcher(stack)) counts.merge(where, stack.amount, Int::plus) }
        if (counts.values.sum() < c.minCount) return
        for ((where, count) in counts) synchronized(hits) { hits += Hit(name, id, where, count) }
    }

    internal fun scanInventory(inv: Inventory, where: String, visit: (String, ItemStack) -> Unit) {
        for (stack in inv.contents) if (stack != null && !stack.type.isAir) walk(stack, where, 0, visit)
    }

    /** 아이템과 그 안(셜커·꾸러미, 세 겹까지). */
    private fun walk(stack: ItemStack, where: String, depth: Int, visit: (String, ItemStack) -> Unit) {
        visit(where, stack)
        if (depth >= 3 || !stack.hasItemMeta()) return
        val meta = runCatching { stack.itemMeta }.getOrNull() ?: return
        if (meta is BlockStateMeta && meta.hasBlockState()) {
            val box = runCatching { meta.blockState as? ShulkerBox }.getOrNull() ?: return
            for (inner in box.inventory.contents) if (inner != null && !inner.type.isAir) walk(inner, "$where→셜커", depth + 1, visit)
        } else if (meta is BundleMeta) {
            for (inner in meta.items) walk(inner, "$where→꾸러미", depth + 1, visit)
        }
    }

    private fun key(sender: CommandSender): String = (sender as? Player)?.uniqueId?.toString() ?: sender.name

    private val hints = SuggestionProvider<CommandSourceStack> { ctx, builder ->
        val remaining = builder.remaining
        val start = remaining.lastIndexOf(' ') + 1
        val word = remaining.substring(start).lowercase()
        val out = builder.createOffset(builder.start + start)
        for (w in SearchCriteria.HINTS) if (w.startsWith(word)) out.suggest(w)
        // 만들기 전용 네임스페이스는 빼고(찾기는 참조로 맞춰 보는데, 그것들은 identify 가 늘 null 이라 아무것도 못 찾는다).
        if (word.isNotEmpty()) for (c in feature.giveCommands.candidates(ctx.source.sender, word, includeMakeOnly = false).filter { !it.startsWith("-") && !it.endsWith("{") }) out.suggest(c)
        out.buildFuture()
    }

    companion object {
        private const val PROGRESS_EVERY = 200

        /** 한 번에 읽고(비동기) 고르는(메인) 파일 수. */
        private const val BATCH_FILES = 25

        private const val RESULT_TTL_MS = 30 * 60_000L
    }
}

/** `/검색` 조건(순수). */
data class SearchCriteria(
    val refs: List<String>,
    val hand: Boolean,
    val oversize: Boolean,
    val name: String?,
    val lore: String?,
    val enchants: List<Pair<String, Int>>,
    val minCount: Int,
    val modes: Set<String>,
) {
    fun allowsMode(mode: String): Boolean = modes.isEmpty() || mode.uppercase() in modes

    /** 아이템을 고르는 조건이 하나라도 있나(없으면 모든 아이템이 걸린다 — 지우기는 거절). '최소:'·'모드:' 는 사람을 고를 뿐이다. */
    val selectsItems: Boolean get() = refs.isNotEmpty() || hand || oversize || name != null || lore != null || enchants.isNotEmpty()

    sealed interface Parsed {
        data class Ok(val criteria: SearchCriteria) : Parsed
        data class Error(val token: String) : Parsed
    }

    companion object {
        val HINTS = listOf("hand", "oversize", "이름:", "설명:", "인챈트:", "최소:", "모드:survival", "모드:creative")

        fun parse(text: String): Parsed {
            val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return Parsed.Error(text)
            val refs = ArrayList<String>()
            var hand = false
            var oversize = false
            var name: String? = null
            var lore: String? = null
            val enchants = ArrayList<Pair<String, Int>>()
            var min = 0
            val modes = LinkedHashSet<String>()
            for (token in tokens) {
                val lower = token.lowercase()
                val value = token.substringAfter(':', "")
                when {
                    lower == "hand" || lower == "손" -> hand = true
                    lower == "oversize" || lower == "넘침" -> oversize = true
                    lower.startsWith("이름:") || lower.startsWith("name:") -> name = value.replace('_', ' ').ifEmpty { return Parsed.Error(token) }
                    lower.startsWith("설명:") || lower.startsWith("lore:") -> lore = value.replace('_', ' ').ifEmpty { return Parsed.Error(token) }
                    lower.startsWith("인챈트:") || lower.startsWith("ench:") -> {
                        val key = value.substringBefore(':').lowercase().ifEmpty { return Parsed.Error(token) }
                        val level = if (value.contains(':')) value.substringAfter(':').toIntOrNull() ?: return Parsed.Error(token) else 1
                        enchants += key to level
                    }
                    lower.startsWith("최소:") || lower.startsWith("min:") -> min = value.toIntOrNull()?.takeIf { it >= 1 } ?: return Parsed.Error(token)
                    lower.startsWith("모드:") || lower.startsWith("mode:") -> {
                        val mode = value.uppercase()
                        if (mode !in setOf("SURVIVAL", "CREATIVE", "ADVENTURE", "SPECTATOR")) return Parsed.Error(token)
                        modes += mode
                    }
                    lower.startsWith("-") -> return Parsed.Error(token)
                    else -> refs += token
                }
            }
            return Parsed.Ok(SearchCriteria(refs, hand, oversize, name, lore, enchants, min, modes))
        }
    }
}
