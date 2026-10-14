package com.inmc.essentials.item

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import kr.inmc.core.integration.Mail
import kr.inmc.core.item.ItemRef
import kr.inmc.core.item.StoredItem
import kr.inmc.core.util.VanillaNames
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.command.CommandSender
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * 아이템을 만들고(참조 · 손 · 남의 가방) 건네는 길 — `/지급` · `/전원지급` · `/머리` · `/몹머리` 가 같이 쓴다.
 *
 * - **개수는 되풀이로 만든다**: `ItemResolver.create` 는 한 묶음 최대치에서 자르고, 인첸트 factory 몇 가지는 개수를 무시한다(연구 give-kits 위험 1·2).
 *   그래서 `left > 0` 동안 만들고 만든 만큼 뺀다 — 칼 10개는 10번 만든다(굴림도 묶음마다 새로).
 * - 받는 사람마다 **새로** 만든다(같은 ItemStack 을 여럿에게 나눠 주지 않는다).
 * - 가방이 차면 남는 것은 우편(`Mail.send`) — 공급처가 없을 때만 발밑에 떨군다(규칙 9 — 둘 중 하나만).
 */
class ItemGive(private val ess: Ess) {

    /** 아이템 하나를 만드는 법. [build] 는 받는 사람마다 새로 부른다. */
    interface Source {
        val label: String
        fun build(amount: Int?): List<ItemStack>
    }

    /** [GiveSpec.items] 를 만드는 법으로. 못 알아보면 `(키, 낱말)` 실패. */
    fun sources(sender: CommandSender, spec: GiveSpec): Pair<List<Source>, Pair<String, String>?> {
        val out = ArrayList<Source>()
        for (token in spec.items) {
            val lower = token.lowercase()
            val source: Source = when {
                lower == GiveSpec.HAND -> {
                    val player = sender as? Player ?: return emptyList<Source>() to ("player-only" to token)
                    handSource(player) ?: return emptyList<Source>() to ("items-hand-empty" to player.name)
                }
                lower.startsWith(GiveSpec.HAND_OF) -> {
                    val who = ess.args.online(sender, token.substring(GiveSpec.HAND_OF.length))
                        ?: return emptyList<Source>() to ("player-not-found" to token.substring(GiveSpec.HAND_OF.length))
                    handSource(who) ?: return emptyList<Source>() to ("items-hand-empty" to who.name)
                }
                lower.startsWith(GiveSpec.INV_OF) -> {
                    val who = ess.args.online(sender, token.substring(GiveSpec.INV_OF.length))
                        ?: return emptyList<Source>() to ("player-not-found" to token.substring(GiveSpec.INV_OF.length))
                    inventorySource(who) ?: return emptyList<Source>() to ("items-inv-empty" to who.name)
                }
                else -> refSource(token) ?: return emptyList<Source>() to ("items-unknown-item" to token)
            }
            out += source
        }
        for ((key, _) in spec.enchants) if (enchantment(key) == null) return emptyList<Source>() to ("items-unknown-enchant" to key)
        return out to null
    }

    /** 참조 하나. 지금 만들 수 없으면 null(모르는 재질·지운 커스텀 아이템·없는 플러그인). */
    fun refSource(raw: String): Source? {
        val ref = ItemRef.parse(raw)
        if (ref is ItemRef.None) return null
        if (ref is ItemRef.Vanilla && (!ref.material.isItem || ref.material.isAir)) return null
        val stored = StoredItem(ref, (ref as? ItemRef.Vanilla)?.material ?: Material.STONE)
        val probe = ess.itemResolver.create(stored, 1) ?: return null
        val label = labelOf(probe)
        return object : Source {
            override val label = label
            override fun build(amount: Int?): List<ItemStack> = repeatCreate(amount ?: 1) { left -> ess.itemResolver.create(stored, left) }
        }
    }

    private fun handSource(player: Player): Source? {
        val hand = player.inventory.itemInMainHand.takeIf { !it.type.isAir }?.clone() ?: return null
        return object : Source {
            override val label = labelOf(hand)
            override fun build(amount: Int?): List<ItemStack> =
                if (amount == null) listOf(hand.clone())
                else repeatCreate(amount) { left -> hand.clone().also { it.amount = left.coerceAtMost(it.maxStackSize.coerceAtLeast(1)) } }
        }
    }

    private fun inventorySource(player: Player): Source? {
        val inv = player.inventory
        val copies = (inv.storageContents.toList() + inv.armorContents.toList() + listOf(inv.itemInOffHand))
            .filter { it != null && !it.type.isAir }.map { it!!.clone() }
        if (copies.isEmpty()) return null
        return object : Source {
            override val label = "${player.name} 의 가방(${copies.size}칸)"
            override fun build(amount: Int?): List<ItemStack> = copies.map { it.clone() }
        }
    }

    /** 받는 사람 한 명 몫을 새로 만든다(만들기 꼬리 적용). */
    fun build(sources: List<Source>, spec: GiveSpec): List<ItemStack> {
        val out = ArrayList<ItemStack>()
        for (source in sources) out += source.build(spec.amount)
        if (spec.hasBuilder) out.forEach { decorate(it, spec) }
        return out
    }

    /** n{} l{} e{} unbreakable. 관리자 명령이라 MiniMessage 를 그대로 받는다. */
    fun decorate(stack: ItemStack, spec: GiveSpec) {
        stack.editMeta { meta ->
            spec.name?.let { meta.displayName(mini(it)) }
            if (spec.lore.isNotEmpty()) meta.lore(spec.lore.map { mini(it) })
            for ((key, level) in spec.enchants) enchantment(key)?.let { meta.addEnchant(it, level, true) }
            if (spec.unbreakable) meta.isUnbreakable = true
        }
    }

    /**
     * 접속자에게 건넨다. [slot] 이 비어 있으면 첫 묶음을 그 칸에. 넘친 것은 우편(없으면 발밑). 넘친 묶음 수.
     */
    fun deliver(player: Player, stacks: List<ItemStack>, source: String, slot: Int? = null): Int {
        val rest = stacks.toMutableList()
        if (slot != null && rest.isNotEmpty()) {
            val current = player.inventory.getItem(slot)
            if (current == null || current.type.isAir) player.inventory.setItem(slot, rest.removeAt(0))
        }
        val leftovers = ArrayList<ItemStack>()
        for (stack in rest) leftovers += player.inventory.addItem(stack).values
        if (leftovers.isEmpty()) return 0
        if (Mail.available && Mail.send(player.uniqueId, origin(), source, leftovers)) {
            ess.messages.send(player, "items-overflow-mailed", Ph.of().count(leftovers.size))
        } else {
            for (left in leftovers) player.world.dropItemNaturally(player.location, left)
            ess.messages.send(player, "items-overflow-dropped", Ph.of().count(leftovers.size))
        }
        return leftovers.size
    }

    /** 접속하지 않은 사람에게 우편으로. 공급처가 없으면 false. */
    fun mail(id: UUID, stacks: List<ItemStack>, source: String): Boolean =
        Mail.available && Mail.send(id, origin(), source, stacks)

    fun origin(): String = ess.plugin.name

    companion object {

        /** 묶음을 되풀이해 만든다 — [create] 는 남은 수를 받아 한 묶음(또는 null)을 돌려준다. */
        fun repeatCreate(amount: Int, create: (Int) -> ItemStack?): List<ItemStack> =
            repeatGeneric(amount, create, { if (it.type.isAir) 0 else it.amount }, { s, n -> s.amount = n })

        /** [repeatCreate] 와 같은 되풀이를 개수만으로(순수 — 시험용). [create] 는 한 번에 만든 개수(0 이하 = 실패). */
        fun repeatCreateCount(amount: Int, create: (Int) -> Int): List<Int> =
            repeatGeneric(amount, { left -> create(left).takeIf { it > 0 }?.let { intArrayOf(it) } }, { it[0] }, { a, n -> a[0] = n }).map { it[0] }

        private fun <T> repeatGeneric(amount: Int, create: (Int) -> T?, size: (T) -> Int, resize: (T, Int) -> Unit): List<T> {
            val out = ArrayList<T>()
            var left = amount.coerceAtLeast(1)
            var guard = 0
            while (left > 0 && guard++ < MAX_STACKS) {
                val made = create(left) ?: break
                val n = size(made)
                if (n <= 0) break
                val take = n.coerceAtMost(left)
                resize(made, take)
                out += made
                left -= take
            }
            return out
        }

        /** 사람이 읽는 이름(이름이 있으면 그것, 없으면 바닐라 한글 이름). */
        fun labelOf(stack: ItemStack): String =
            StoredItem.plainName(stack) ?: VanillaNames.of(stack.type) ?: stack.type.key.value()

        fun enchantment(key: String): Enchantment? {
            val namespaced = if (key.contains(':')) NamespacedKey.fromString(key) else NamespacedKey.minecraft(key)
            return namespaced?.let { runCatching { Registry.ENCHANTMENT.get(it) }.getOrNull() }
        }

        /** MiniMessage(+ `&` 코드). 기울임꼴은 글이 정하지 않았으면 [italic] 대로(아이템 이름 기본은 끔). */
        fun mini(text: String, italic: Boolean = false): Component =
            MiniMessage.miniMessage().deserialize(kr.inmc.core.util.Text.legacyToMiniMessage(text))
                .decorationIfAbsent(TextDecoration.ITALIC, if (italic) TextDecoration.State.TRUE else TextDecoration.State.FALSE)

        private val LEGACY_CODE = Regex("&[0-9a-fk-orA-FK-OR#]")
        private val MINI_TAG = Regex("<[a-zA-Z_#!/][^<>]*>")

        /** 글에 색·꾸밈(`&c` · `&#rrggbb` · `<red>` …)이 있나(순수). */
        fun hasFormatting(text: String): Boolean = LEGACY_CODE.containsMatchIn(text) || MINI_TAG.containsMatchIn(text)

        fun onlineByUuid(id: UUID): Player? = Bukkit.getPlayer(id)

        /** 한 아이템을 몇 묶음까지 만들까(무한 반복 막기 — 3456 = 큰 상자 두 개). */
        private const val MAX_STACKS = 3456
    }
}
