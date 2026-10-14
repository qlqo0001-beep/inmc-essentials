package com.inmc.essentials.item

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import kr.inmc.core.integration.CustomItemHook
import kr.inmc.core.integration.Vanish
import kr.inmc.core.item.ItemRef
import org.bukkit.NamespacedKey
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * `/비우기 [대상] [옵션…]` — CMI `/cmi clear`(카탈로그 cmd1:inv-clear). 바닐라 `/clear` 는 그대로 둔다(이름이 겹치지 않게 별칭 없음).
 *
 * - 범위 `+핫바`(quickbar) · `+가방`(inventory 0~35) · `+윗칸`(partinventory 9~35) · `+갑옷칸`(armorslots) · `+손`(mainhand) · `+왼손`(offhand) ·
 *   종류 `+무기`(weapons) · `+도구`(tools) · `+갑옷`(armors) — 여럿이면 범위끼리·종류끼리 합치고 둘은 함께 만족.
 * - 아이템 `재질[:개수]`·`inmc:<id>[:개수]` — 개수가 있으면 그만큼만. `-exact` 면 재질 참조는 순수 바닐라(이름·설명 없는 것)만.
 * - `-s` 조용히. 조건 없이 전부 비우면 `[확인]` 을 한 번 더(설정 clear.confirm). 제외: 설정 clear.exclude 의 `NBT:<경로>`(custom_data) · PDC 열쇠가 붙은 것.
 * - 비우기 전에 그 사람의 열린 셜커 배낭을 되써 넣고 닫는다.
 */
class Clear(private val ess: Ess, private val feature: ItemsFeature) {

    private class Pending(val target: UUID, val text: String, val at: Long)

    private val pending = ConcurrentHashMap<String, Pending>()

    fun register() {
        ess.commands.add(
            Commands.literal("비우기").requires { it.sender.hasPermission(ItemsFeature.CLEAR) }
                .executes { ctx -> run(ctx.source.sender, ""); 1 }
                .then(Commands.argument("옵션", StringArgumentType.greedyString()).suggests(hints)
                    .executes { ctx -> run(ctx.source.sender, StringArgumentType.getString(ctx, "옵션")); 1 })
                .build(),
            "가방 비우기(범위·아이템 골라서)",
        )
        ess.ticks.add("items-clear-evict", 60) { now -> pending.values.removeIf { now - it.at > CONFIRM_MS } }
    }

    fun run(sender: CommandSender, text: String) {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        if (tokens.size == 1 && tokens[0].lowercase() in CONFIRM) return confirm(sender)
        // 첫 낱말이 보이는 접속자의 정확한 이름이면 대상.
        var target = sender as? Player
        val first = tokens.firstOrNull()
        if (first != null && !first.startsWith("+") && !first.startsWith("-")) {
            val named = Vanish.visibleOnline(sender).firstOrNull { it.name.equals(first, ignoreCase = true) }
            if (named != null) { target = named; tokens.removeAt(0) }
        }
        if (target == null) return ess.messages.send(sender, "items-clear-who")
        if (target != sender && !sender.hasPermission(ItemsFeature.CLEAR_OTHERS)) return ess.messages.send(sender, "no-permission")
        val spec = when (val parsed = ClearSpec.parse(tokens)) {
            is ClearSpec.Parsed.Error -> return ess.messages.send(sender, "items-clear-bad", Ph.of().value(parsed.token))
            is ClearSpec.Parsed.Ok -> parsed.spec
        }
        val refs = spec.items.map { (raw, amount) -> (ItemRef.parse(raw).takeIf { it !is ItemRef.None } ?: return ess.messages.send(sender, "items-unknown-item", Ph.of().value(raw))) to amount }
        // 만들기 전용 참조(물고기·부여서·상점 블록)는 어떤 아이템에도 맞지 않는다 — 조용히 0개가 되지 않게 알린다.
        refs.firstOrNull { (ref, _) -> ref is ItemRef.Namespaced && ref.namespace.lowercase() in CustomItemHook.makeOnlyNamespaces() }
            ?.let { (ref, _) -> return ess.messages.send(sender, "items-make-only", Ph.of().value(ref.serialize())) }
        if (spec.isEverything && feature.settings.clear.confirm && !spec.confirmed && sender is Player) {
            pending[key(sender)] = Pending(target.uniqueId, tokens.joinToString(" "), System.currentTimeMillis())
            return ess.messages.send(sender, "items-clear-confirm", Ph.of().player(target.name))
        }
        val removed = clear(target, spec, refs)
        ess.messages.send(sender, "items-clear-done", Ph.of().count(removed).player(target.name))
        if (target != sender && !spec.silent && removed > 0) ess.messages.send(target, "items-clear-target", Ph.of().count(removed).player(sender.name))
    }

    private fun confirm(sender: CommandSender) {
        val p = pending.remove(key(sender))
        if (p == null || System.currentTimeMillis() - p.at > CONFIRM_MS) return ess.messages.send(sender, "items-clear-no-pending")
        val target = org.bukkit.Bukkit.getPlayer(p.target) ?: return ess.messages.send(sender, "player-not-found", Ph.of().value(p.target.toString()))
        val prefix = if (target == sender) "" else target.name + " "
        run(sender, prefix + p.text + " " + ClearSpec.CONFIRMED_FLAG)
    }

    /** 지운 아이템 수(개수 합). */
    fun clear(target: Player, spec: ClearSpec, refs: List<Pair<ItemRef, Int?>>): Int {
        // 열린 셜커 배낭은 먼저 되써 넣고 닫는다(그 상자를 지우거나 옮기기 전에 창과 상자를 맞춰 둔다).
        feature.backpack.closeFor(target)
        val inv = target.inventory
        val left = refs.map { it.second ?: Int.MAX_VALUE }.toIntArray()
        val settings = feature.settings.clear
        val excluded = settings.excludeKeys.mapNotNull { NamespacedKey.fromString(it) }
        var removed = 0
        for (slot in spec.slots(inv.heldItemSlot)) {
            val stack = inv.getItem(slot) ?: continue
            if (stack.type.isAir) continue
            if (!spec.typeMatches(stack.type.name)) continue
            if (excluded.any { key -> stack.hasItemMeta() && stack.itemMeta.persistentDataContainer.has(key) }) continue
            if (settings.excludeNbt.isNotEmpty() && customData(stack)?.let { data -> settings.excludeNbt.any { ClearSpec.hasPath(data, it) } } == true) continue
            if (refs.isEmpty()) {
                removed += stack.amount
                inv.setItem(slot, null)
                continue
            }
            val index = refs.indices.firstOrNull { left[it] > 0 && matches(stack, refs[it].first, spec.exact) } ?: continue
            val take = stack.amount.coerceAtMost(left[index])
            left[index] -= take
            removed += take
            if (take >= stack.amount) inv.setItem(slot, null) else { stack.amount -= take; inv.setItem(slot, stack) }
        }
        return removed
    }

    /** 아이템의 custom_data(`components."minecraft:custom_data"`) — 없으면 null. 직렬화한 NBT 를 읽는다(지울 때만 · 설정에 NBT 경로가 있을 때만). */
    private fun customData(stack: ItemStack): Map<*, *>? {
        if (!stack.hasItemMeta()) return null
        val root = runCatching { Nbt.readCompressed(stack.serializeAsBytes().inputStream()) }.getOrNull() ?: return null
        return (root["components"] as? Map<*, *>)?.get("minecraft:custom_data") as? Map<*, *>
    }

    private fun matches(stack: ItemStack, ref: ItemRef, exact: Boolean): Boolean = when (ref) {
        is ItemRef.Vanilla -> stack.type == ref.material && (!exact || ess.itemResolver.isPlainVanilla(stack))
        else -> ref in ess.itemResolver.identifyAll(stack)
    }

    private fun key(sender: CommandSender): String = (sender as? Player)?.uniqueId?.toString() ?: sender.name

    private val hints = SuggestionProvider<CommandSourceStack> { ctx, builder ->
        val remaining = builder.remaining
        val start = remaining.lastIndexOf(' ') + 1
        val word = remaining.substring(start).lowercase()
        val out = builder.createOffset(builder.start + start)
        val words = ClearSpec.SCOPE_HINTS + listOf("-s", "-exact", "확인")
        for (w in words) if (w.startsWith(word)) out.suggest(w)
        if (start == 0 && ctx.source.sender.hasPermission(ItemsFeature.CLEAR_OTHERS)) {
            for (name in Vanish.visibleNames(ctx.source.sender)) if (name.lowercase().startsWith(word)) out.suggest(name)
        }
        out.buildFuture()
    }

    companion object {
        private val CONFIRM = setOf("확인", "confirm")
        private const val CONFIRM_MS = 30_000L
    }
}

/** `/비우기` 옵션(순수). */
data class ClearSpec(
    val slotScopes: Set<Scope>,
    val typeScopes: Set<Scope>,
    val items: List<Pair<String, Int?>>,
    val exact: Boolean,
    val silent: Boolean,
    val confirmed: Boolean,
) {
    enum class Scope(val words: Set<String>, val bySlot: Boolean) {
        QUICKBAR(setOf("quickbar", "핫바"), true),
        INVENTORY(setOf("inventory", "가방"), true),
        PART_INVENTORY(setOf("partinventory", "윗칸"), true),
        ARMOR_SLOTS(setOf("armorslots", "갑옷칸"), true),
        MAINHAND(setOf("mainhand", "손"), true),
        OFFHAND(setOf("offhand", "왼손"), true),
        WEAPONS(setOf("weapons", "무기"), false),
        TOOLS(setOf("tools", "도구"), false),
        ARMORS(setOf("armors", "갑옷"), false),
    }

    /** 아무 조건도 없다 = 가방 전부(확인을 받는다). */
    val isEverything: Boolean get() = slotScopes.isEmpty() && typeScopes.isEmpty() && items.isEmpty()

    /** 볼 칸(PlayerInventory 번호: 0~35 가방 · 36~39 갑옷 · 40 왼손). */
    fun slots(held: Int): List<Int> {
        if (slotScopes.isEmpty()) return (0..40).toList()
        val out = LinkedHashSet<Int>()
        for (scope in slotScopes) when (scope) {
            Scope.QUICKBAR -> out += 0..8
            Scope.INVENTORY -> out += 0..35
            Scope.PART_INVENTORY -> out += 9..35
            Scope.ARMOR_SLOTS -> out += 36..39
            Scope.MAINHAND -> out += held
            Scope.OFFHAND -> out += 40
            else -> {}
        }
        return out.toList()
    }

    fun typeMatches(material: String): Boolean =
        typeScopes.isEmpty() || typeScopes.any { kind(it, material) }

    sealed interface Parsed {
        data class Ok(val spec: ClearSpec) : Parsed
        data class Error(val token: String) : Parsed
    }

    companion object {
        const val CONFIRMED_FLAG = "-confirmed"

        val SCOPE_HINTS: List<String> = Scope.entries.flatMap { s -> s.words.map { "+$it" } }

        fun parse(tokens: List<String>): Parsed {
            val slots = LinkedHashSet<Scope>()
            val types = LinkedHashSet<Scope>()
            val items = ArrayList<Pair<String, Int?>>()
            var exact = false
            var silent = false
            var confirmed = false
            for (token in tokens) {
                val lower = token.lowercase()
                when {
                    lower == "-s" -> silent = true
                    lower == "-exact" || lower == "-정확" -> exact = true
                    lower == CONFIRMED_FLAG -> confirmed = true
                    lower.startsWith("+") -> {
                        val scope = Scope.entries.firstOrNull { lower.removePrefix("+") in it.words } ?: return Parsed.Error(token)
                        if (scope.bySlot) slots += scope else types += scope
                    }
                    lower.startsWith("-") -> return Parsed.Error(token)
                    else -> items += item(token)
                }
            }
            return Parsed.Ok(ClearSpec(slots, types, items, exact, silent, confirmed))
        }

        /** `참조[:개수]` — 마지막 `:` 뒤가 숫자면 개수. */
        fun item(token: String): Pair<String, Int?> {
            val at = token.lastIndexOf(':')
            if (at > 0) {
                val n = token.substring(at + 1).toIntOrNull()
                if (n != null && n > 0) return token.substring(0, at) to n
            }
            return token to null
        }

        /**
         * custom_data 안에 [path] 가 있나(순수) — 먼저 그 글 그대로를 한 열쇠로(`ItemJoin Name`), 없으면 점으로 갈라 한 겹씩(`ItemJoin.Name`).
         */
        fun hasPath(data: Map<*, *>, path: String): Boolean {
            if (data.containsKey(path)) return true
            var node: Any? = data
            for (part in path.split('.')) {
                node = (node as? Map<*, *>)?.get(part) ?: return false
            }
            return true
        }

        /** 재질 이름으로 종류를 가른다(순수). */
        fun kind(scope: Scope, material: String): Boolean {
            val m = material.uppercase()
            return when (scope) {
                Scope.WEAPONS -> m.endsWith("_SWORD") || m.endsWith("_AXE") || m in setOf("BOW", "CROSSBOW", "TRIDENT", "MACE") || m.endsWith("_SPEAR")
                Scope.TOOLS -> m.endsWith("_PICKAXE") || m.endsWith("_SHOVEL") || m.endsWith("_HOE") || m.endsWith("_AXE") ||
                    m in setOf("SHEARS", "FISHING_ROD", "FLINT_AND_STEEL", "BRUSH", "CARROT_ON_A_STICK", "WARPED_FUNGUS_ON_A_STICK")
                Scope.ARMORS -> m.endsWith("_HELMET") || m.endsWith("_CHESTPLATE") || m.endsWith("_LEGGINGS") || m.endsWith("_BOOTS") ||
                    m in setOf("ELYTRA", "SHIELD") || m.endsWith("_HORSE_ARMOR")
                else -> true
            }
        }
    }
}
