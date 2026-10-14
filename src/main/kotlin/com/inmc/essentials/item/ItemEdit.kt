package com.inmc.essentials.item

import com.inmc.essentials.Ess
import com.inmc.essentials.gui.Menu
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import kr.inmc.core.gui.Icon
import kr.inmc.core.util.VanillaNames
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.command.CommandSender
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ArmorMeta
import org.bukkit.inventory.meta.trim.ArmorTrim
import org.bukkit.inventory.meta.trim.TrimMaterial
import org.bukkit.inventory.meta.trim.TrimPattern
import org.bukkit.persistence.PersistentDataType

/**
 * `/이름`(itemname) · `/설명`(itemlore) · `/장식`(trim) + 모루 이름 제한 — 카탈로그 cmd4:cmd-itemname-lore · cmd8:cmd-trim.
 *
 * - 색(MiniMessage·`&` 코드)은 `inmcessentials.items.itemname.color` 가 있을 때만 — 없으면 글자 그대로.
 * - 이름 제한(CMI ItemRenaming): 전체 금지 · 최대 글자 · `재질:정규식` 금지 · 원래 이름 검사. 모루에도 같은 제한(PrepareAnvilEvent). 우회 `…rename.bypass`.
 * - inmc 커스텀 아이템은 다시 그릴 때(정의 수정 뒤) 이름·설명이 정의로 돌아간다 — 바꾸기는 하되 알린다.
 */
class ItemEdit(private val ess: Ess, private val feature: ItemsFeature) : Listener {

    private val naming: NamingSettings get() = feature.settings.naming

    fun register() {
        ess.commands.add(
            Commands.literal("이름").requires { it.sender.hasPermission(ItemsFeature.NAME) }
                .executes { ctx -> ess.messages.send(ctx.source.sender, "items-name-usage"); 1 }
                .then(Commands.argument("내용", StringArgumentType.greedyString()).suggests(nameHints)
                    .executes { ctx -> rename(ctx.source.sender, StringArgumentType.getString(ctx, "내용")); 1 })
                .build(),
            "손에 든 아이템 이름 바꾸기", listOf("itemname"),
        )
        ess.commands.add(
            Commands.literal("설명").requires { it.sender.hasPermission(ItemsFeature.LORE) }
                .executes { ctx -> ess.messages.send(ctx.source.sender, "items-lore-usage"); 1 }
                .then(Commands.argument("내용", StringArgumentType.greedyString()).suggests(loreHints)
                    .executes { ctx -> lore(ctx.source.sender, StringArgumentType.getString(ctx, "내용")); 1 })
                .build(),
            "손에 든 아이템 설명(로어) 바꾸기", listOf("itemlore"),
        )
        val trim = Commands.literal("장식").requires { it.sender.hasPermission(ItemsFeature.TRIM) }
            .executes { ctx -> (ctx.source.sender as? Player)?.let { openTrim(it, it, false) } ?: ess.messages.send(ctx.source.sender, "player-only"); 1 }
        for (word in CLEAR_WORDS) trim.then(Commands.literal(word).executes { ctx ->
            (ctx.source.sender as? Player)?.let { openTrim(it, it, true) } ?: ess.messages.send(ctx.source.sender, "player-only"); 1
        })
        val other = ess.args.player().requires { it.sender.hasPermission(ItemsFeature.TRIM_OTHERS) }
            .executes { ctx -> trimOther(ctx.source.sender, StringArgumentType.getString(ctx, "플레이어"), false); 1 }
        for (word in CLEAR_WORDS) other.then(Commands.literal(word).executes { ctx ->
            trimOther(ctx.source.sender, StringArgumentType.getString(ctx, "플레이어"), true); 1
        })
        trim.then(other)
        ess.commands.add(trim.build(), "손에 든 갑옷의 장식(트림) 고르기·지우기", listOf("trim"))
    }

    // --- 대상 ----------------------------------------------------------------------------------------

    /** `-p:<이름>` 을 떼어 대상을 고른다. 남은 글. 권한·대상이 없으면 알리고 null. */
    private fun target(sender: CommandSender, text: String, othersPerm: String): Pair<Player, String>? {
        val trimmed = text.trim()
        if (trimmed.startsWith("-p:", ignoreCase = true)) {
            val name = trimmed.substring(3).substringBefore(' ')
            val rest = trimmed.substring(3 + name.length).trim()
            if (!sender.hasPermission(othersPerm)) { ess.messages.send(sender, "no-permission"); return null }
            val who = ess.args.online(sender, name) ?: run { ess.messages.send(sender, "player-not-found", Ph.of().value(name)); return null }
            return who to rest
        }
        val self = sender as? Player ?: run { ess.messages.send(sender, "player-only"); return null }
        return self to trimmed
    }

    private fun held(sender: CommandSender, target: Player): ItemStack? {
        val hand = target.inventory.itemInMainHand
        if (hand.type.isAir) { ess.messages.send(sender, "items-hand-empty", Ph.of().value(target.name)); return null }
        return hand
    }

    private fun component(sender: CommandSender, raw: String): Component =
        if (sender.hasPermission(ItemsFeature.COLOR)) ItemGive.mini(raw)
        else Component.text(raw).decoration(TextDecoration.ITALIC, false)

    private fun warnCustom(sender: CommandSender, stack: ItemStack) {
        if (ess.customItems.identify(stack)?.namespace.equals("inmc", ignoreCase = true)) ess.messages.send(sender, "items-custom-redraw-warning")
    }

    // --- /이름 ---------------------------------------------------------------------------------------

    fun rename(sender: CommandSender, text: String) {
        val (target, rest) = target(sender, text, ItemsFeature.NAME_OTHERS) ?: return
        if (rest.isEmpty()) return ess.messages.send(sender, "items-name-usage")
        val stack = held(sender, target) ?: return
        val remove = rest.lowercase() in CLEAR_WORDS
        val newName = if (remove) null else component(sender, rest)
        val newPlain = newName?.let { PLAIN.serialize(it) }
        if (!sender.hasPermission(ItemsFeature.RENAME_BYPASS)) {
            NamingRules.problem(naming, stack.type, StoredName.of(stack), newPlain)?.let { reason ->
                val key = NAMING_PREFIX + reason // items-naming-disabled · -too-long · -blocked · -source-blocked
                return ess.messages.send(sender, key, Ph.of().count(naming.nameMaxLength))
            }
        }
        stack.editMeta { meta ->
            meta.displayName(newName)
            if (naming.markChanged) meta.persistentDataContainer.set(MODIFIED_NAME, PersistentDataType.BYTE, 1)
        }
        target.inventory.setItemInMainHand(stack)
        warnCustom(sender, stack)
        if (remove) ess.messages.send(sender, "items-name-removed", Ph.of().player(target.name))
        else ess.messages.send(sender, "items-name-set", Ph.of().player(target.name).value(newPlain.orEmpty()))
    }

    // --- /설명 ---------------------------------------------------------------------------------------

    fun lore(sender: CommandSender, text: String) {
        val (target, rest) = target(sender, text, ItemsFeature.LORE_OTHERS) ?: return
        if (rest.isEmpty()) return ess.messages.send(sender, "items-lore-usage")
        val stack = held(sender, target) ?: return
        if (stack.type in naming.loreTypeBlacklist && !sender.hasPermission(ItemsFeature.RENAME_BYPASS)) {
            return ess.messages.send(sender, "items-lore-blacklisted", Ph.of().item(ItemGive.labelOf(stack)))
        }
        val current = stack.itemMeta?.lore().orEmpty().toMutableList()
        val edit = LoreEdit.parse(rest) ?: return ess.messages.send(sender, "items-lore-usage")
        val lines = edit.apply(current, { component(sender, it) }, Component.empty())
            ?: return ess.messages.send(sender, "items-lore-no-line", Ph.of().count(current.size))
        val max = naming.loreMaxLength
        if (max > 0 && !sender.hasPermission(ItemsFeature.RENAME_BYPASS) && lines.any { PLAIN.serialize(it).length > max }) {
            return ess.messages.send(sender, "items-lore-too-long", Ph.of().count(max))
        }
        stack.editMeta { meta ->
            meta.lore(lines.ifEmpty { null })
            if (naming.markChanged) meta.persistentDataContainer.set(MODIFIED_LORE, PersistentDataType.BYTE, 1)
        }
        target.inventory.setItemInMainHand(stack)
        warnCustom(sender, stack)
        ess.messages.send(sender, "items-lore-set", Ph.of().player(target.name).count(lines.size))
    }

    // --- 모루 ----------------------------------------------------------------------------------------

    /**
     * 모루 — (1) 이름 제한(설정 naming.anvil, 우회 `…rename.bypass`) (2) 색 이름(설정 naming.anvil-colors, `…itemname.color` 가 있는 사람):
     * 적은 글에 `&` 코드·MiniMessage 가 있으면 결과 아이템의 이름을 그 색으로 — 기울임꼴은 naming.anvil-italic-by-default(CMI ItalicByDefault).
     * 제한은 색을 뺀 글자로 본다.
     */
    @EventHandler(priority = EventPriority.HIGH)
    fun onAnvil(event: PrepareAnvilEvent) {
        val s = naming
        val result = event.result ?: return
        val viewer = event.view.player
        val source = event.inventory.firstItem ?: return
        val typed = event.view.renameText ?: return
        val oldPlain = StoredName.of(source)
        if (typed.isEmpty() || typed == oldPlain) return // 이름을 바꾸지 않는 모루 사용(수리·인첸트)은 그대로
        val colored = s.anvilColors && viewer.hasPermission(ItemsFeature.COLOR) && ItemGive.hasFormatting(typed)
        val name = if (colored) ItemGive.mini(typed, italic = s.anvilItalic) else null
        val plain = name?.let { PLAIN.serialize(it) } ?: typed
        if (s.anvil && !viewer.hasPermission(ItemsFeature.RENAME_BYPASS) && NamingRules.problem(s, result.type, oldPlain, plain) != null) {
            event.result = null
            return
        }
        if (name != null) event.result = result.clone().also { it.editMeta { meta -> meta.displayName(name) } }
    }

    // --- /장식 ---------------------------------------------------------------------------------------

    private fun trimOther(sender: CommandSender, name: String, clear: Boolean) {
        val viewer = sender as? Player ?: return ess.messages.send(sender, "player-only")
        val target = ess.args.online(sender, name) ?: return ess.messages.send(sender, "player-not-found", Ph.of().value(name))
        openTrim(viewer, target, clear)
    }

    fun openTrim(viewer: Player, target: Player, clear: Boolean) {
        val stack = held(viewer, target) ?: return
        if (stack.itemMeta !is ArmorMeta) return ess.messages.send(viewer, "items-trim-not-armor")
        if (clear) {
            stack.editMeta(ArmorMeta::class.java) { it.trim = null }
            target.inventory.setItemInMainHand(stack)
            return ess.messages.send(viewer, "items-trim-cleared", Ph.of().player(target.name))
        }
        TrimMenu(ess, viewer, target, stack.type).show()
    }

    /** 장식 고르기 — 위 세 줄 무늬, 넷째·다섯째 줄 재료, 아래 줄 미리보기·적용·지우기. 적용 때 손을 다시 읽는다(그새 바뀌었으면 안 한다). */
    private inner class TrimMenu(ess: Ess, viewer: Player, private val target: Player, private val type: Material) :
        Menu(ess, viewer, 54, "<dark_gray>갑옷 장식</dark_gray>") {

        private val patterns: List<TrimPattern> = registry(RegistryKey.TRIM_PATTERN)
        private val materials: List<TrimMaterial> = registry(RegistryKey.TRIM_MATERIAL)
        private var pattern: TrimPattern? = (target.inventory.itemInMainHand.itemMeta as? ArmorMeta)?.trim?.pattern
        private var material: TrimMaterial? = (target.inventory.itemInMainHand.itemMeta as? ArmorMeta)?.trim?.material

        override fun draw() {
            clear()
            patterns.take(27).forEachIndexed { i, p ->
                set(i, choice(patternIcon(p), name(p), p == pattern)) { pattern = p; refresh() }
            }
            materials.take(18).forEachIndexed { i, m ->
                set(27 + i, choice(materialIcon(m), name(m), m == material)) { material = m; refresh() }
            }
            for (slot in 45 until 54) set(slot, Icon.FILLER)
            set(SLOT_CLEAR, Icon.of(Material.BUCKET, "<red>장식 지우기</red>")) { apply(null) }
            val ready = pattern != null && material != null
            set(SLOT_APPLY, Icon.of(if (ready) Material.SMITHING_TABLE else Material.GRAY_DYE, if (ready) "<green>✔ 적용</green>" else "<gray>무늬와 재료를 고르세요</gray>",
                "<gray>무늬: ${pattern?.let { name(it) } ?: "-"}</gray>", "<gray>재료: ${material?.let { name(it) } ?: "-"}</gray>")) {
                val p = pattern
                val m = material
                if (p != null && m != null) apply(ArmorTrim(m, p))
            }
            set(SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
        }

        private fun apply(trim: ArmorTrim?) {
            val stack = target.inventory.itemInMainHand
            if (stack.type != type || stack.itemMeta !is ArmorMeta) {
                viewer.closeInventory()
                return ess.messages.send(viewer, "items-trim-changed")
            }
            stack.editMeta(ArmorMeta::class.java) { it.trim = trim }
            target.inventory.setItemInMainHand(stack)
            viewer.closeInventory()
            if (trim == null) ess.messages.send(viewer, "items-trim-cleared", Ph.of().player(target.name))
            else ess.messages.send(viewer, "items-trim-set", Ph.of().player(target.name).value(name(trim.pattern) + " · " + name(trim.material)))
        }

        private fun choice(material: Material, label: String, selected: Boolean): ItemStack =
            Icon.of(material, (if (selected) "<green>▶ " else "<yellow>") + label, if (selected) "<green>골랐습니다</green>" else "<gray>클릭: 고르기</gray>").apply {
                if (selected) editMeta { it.addEnchant(Enchantment.UNBREAKING, 1, true); it.addItemFlags(ItemFlag.HIDE_ENCHANTS) }
            }
    }

    // --- 제안 ---------------------------------------------------------------------------------------

    private val nameHints = SuggestionProvider<CommandSourceStack> { ctx, builder ->
        if (builder.remaining.isEmpty()) {
            builder.suggest("지우기")
            if (ctx.source.sender.hasPermission(ItemsFeature.NAME_OTHERS)) builder.suggest("-p:")
        }
        builder.buildFuture()
    }

    private val loreHints = SuggestionProvider<CommandSourceStack> { _, builder ->
        if (builder.remaining.isEmpty()) for (hint in listOf("1", "+", "*", "지우기", "-p:")) builder.suggest(hint)
        builder.buildFuture()
    }

    companion object {
        val CLEAR_WORDS = setOf("지우기", "remove", "clear")
        const val NAMING_PREFIX = "items-naming-"
        val MODIFIED_NAME = NamespacedKey(Ess.NAMESPACE, "modified_name")
        val MODIFIED_LORE = NamespacedKey(Ess.NAMESPACE, "modified_lore")
        private const val SLOT_CLEAR = 47
        private const val SLOT_APPLY = 49
        private const val SLOT_CLOSE = 53
        val PLAIN: PlainTextComponentSerializer = PlainTextComponentSerializer.plainText()

        private fun <T : org.bukkit.Keyed> registry(key: RegistryKey<T>): List<T> =
            runCatching { RegistryAccess.registryAccess().getRegistry(key).toList() }.getOrDefault(emptyList())

        private fun name(value: org.bukkit.Translatable): String =
            VanillaNames.ofKey(value.translationKey) ?: value.translationKey.substringAfterLast('.')

        private fun patternIcon(pattern: TrimPattern): Material {
            val key = runCatching { RegistryAccess.registryAccess().getRegistry(RegistryKey.TRIM_PATTERN).getKey(pattern)?.key }.getOrNull()
            return key?.let { Material.matchMaterial(it + "_armor_trim_smithing_template") } ?: Material.PAPER
        }

        private fun materialIcon(material: TrimMaterial): Material {
            val key = runCatching { RegistryAccess.registryAccess().getRegistry(RegistryKey.TRIM_MATERIAL).getKey(material)?.key }.getOrNull()
                ?: return Material.PAPER
            return when (key) {
                "lapis" -> Material.LAPIS_LAZULI
                "amethyst" -> Material.AMETHYST_SHARD
                "resin" -> Material.matchMaterial("resin_brick") ?: Material.PAPER
                else -> Material.matchMaterial(key)?.takeIf { it.isItem } ?: Material.matchMaterial(key + "_ingot") ?: Material.PAPER
            }
        }
    }
}

/** 아이템의 지금 이름(글자만) — 없으면 null. */
object StoredName {
    fun of(stack: ItemStack): String? = kr.inmc.core.item.StoredItem.plainName(stack)
}

/** 이름 제한(순수) — CMI ItemRenaming. 막으면 까닭(`disabled`·`too-long`·`blocked`·`source-blocked`), 되면 null. */
object NamingRules {
    fun problem(s: NamingSettings, material: Material, oldPlain: String?, newPlain: String?): String? {
        if (s.globalDisable) return "disabled"
        if (newPlain != null && s.nameMaxLength > 0 && newPlain.length > s.nameMaxLength) return "too-long"
        if (s.prevent) {
            if (newPlain != null && s.rules.any { it.blocks(material, newPlain) }) return "blocked"
            if (s.checkSource && oldPlain != null && s.rules.any { it.blocks(material, oldPlain) }) return "source-blocked"
        }
        return null
    }
}

/**
 * `/설명` 의 꼬리(순수): `<줄> <글>` 바꾸기 · `<줄> 지우기` · `<줄> 끼우기 <글>` · `* <줄1|줄2>` 통째로 · `+ <글>` 덧붙이기 · `지우기` 전부 지우기 ·
 * 그 밖의 글은 덧붙이기.
 */
sealed interface LoreEdit {
    data class Set(val line: Int, val text: String) : LoreEdit
    data class Remove(val line: Int) : LoreEdit
    data class Insert(val line: Int, val text: String) : LoreEdit
    data class Replace(val lines: List<String>) : LoreEdit
    data class Append(val text: String) : LoreEdit
    data object Clear : LoreEdit

    /** 적용한 새 줄 목록. 없는 줄을 지우라면 null. */
    fun <T> apply(current: List<T>, make: (String) -> T, blank: T): List<T>? {
        val out = current.toMutableList()
        when (this) {
            is Set -> {
                while (out.size < line) out += blank
                out[line - 1] = make(text)
            }
            is Remove -> { if (line > out.size) return null; out.removeAt(line - 1) }
            is Insert -> {
                while (out.size < line - 1) out += blank
                out.add(line - 1, make(text))
            }
            is Replace -> { out.clear(); lines.forEach { out += make(it) } }
            is Append -> out += make(text)
            Clear -> out.clear()
        }
        return out
    }

    companion object {
        private val REMOVE = setOf("지우기", "remove")
        private val INSERT = setOf("끼우기", "insert")

        fun parse(text: String): LoreEdit? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val first = trimmed.substringBefore(' ')
            val rest = trimmed.substring(first.length).trim()
            if (first.lowercase() in REMOVE && rest.isEmpty()) return Clear
            if (first == "*") return if (rest.isEmpty()) Clear else Replace(rest.split('|'))
            if (first == "+") return if (rest.isEmpty()) null else Append(rest)
            val line = first.toIntOrNull()
            if (line != null && line >= 1 && line <= 256) {
                val second = rest.substringBefore(' ')
                val after = rest.substring(second.length).trim()
                return when {
                    second.lowercase() in REMOVE -> Remove(line)
                    second.lowercase() in INSERT -> Insert(line, after)
                    else -> Set(line, rest)
                }
            }
            return Append(trimmed)
        }
    }
}
