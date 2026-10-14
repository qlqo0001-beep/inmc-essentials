package com.inmc.essentials.item

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import kr.inmc.core.integration.Vanish
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.LeatherArmorMeta
import org.bukkit.persistence.PersistentDataType
import kotlin.math.PI
import kotlin.math.sin

/**
 * `/염색 [플레이어] [색|모드]`(dye) — 카탈로그 cmd2:cmd-dye. 손에 든 가죽(염색되는) 갑옷의 색을 바꾸거나(인자 없이: 지금 색 보기) 움직이는 색을 단다.
 *
 * - 고정: `r,g,b` · `#hex` · 색 이름(영어·한글) · `random`(무작위 한 번) · `지우기`(clear).
 * - 움직이는 색(CMI rainbow·day·biome·health + 더): `무지개` · `낮밤` · `바이옴` · `체력` · `숨쉬기[:#색]` · `팔레트[:#색,#색…]` ·
 *   `물결`(머리→발 무지개) · `피격[:#평소,#맞을때]` · `전투[:#평소,#전투]` · `배고픔[:#가득]` · `산소[:#가득]` · `디스코`.
 *   아이템 PDC `inmcess:dye` 에 모드를 적고, [DyeSettings.periodSeconds] 마다 **입은 갑옷만** 다시 칠한다. BoundToPlayer 면 `inmcess:dye_owner` 의 사람이 입을 때만.
 *   **칠하기는 보이는 것만**(`sendEquipmentChange` — 본인과 그 사람을 보는 사람에게): 진짜 아이템을 바꾸면 매번 PlayerArmorChangeEvent 가
 *   나서 커스텀아이템·인챈트가 능력치를 통째로 다시 셈한다(초마다 사람 수 × 2). 진짜 아이템의 색은 `/염색` 한 순간의 색으로 남는다.
 *   맞으면(피격·전투 모드뿐 아니라 전부) 다음 틱에 바로 다시 칠한다 — 바닐라가 내구도 변화로 진짜 색을 보내 덮기 때문, 피격 번쩍임은 1.5초 뒤 되돌린다.
 * - inmc 커스텀 아이템: 정의에 색이 있으면(다시 그릴 때 정의 색으로 돌아간다) 움직이는 색은 거절, 고정 색은 경고하고 바꾼다.
 */
class Dye(private val ess: Ess, private val feature: ItemsFeature) {

    private var counter = 0

    /** 사람마다 마지막으로 보낸 색(머리→발, -1 = 없음). 같으면 다시 보내지 않는다 — [RESEND_SECONDS] 마다는 새로 보는 사람 몫으로 다시. */
    private val lastSent = HashMap<java.util.UUID, IntArray>()

    fun register() {
        ess.commands.add(
            Commands.literal("염색").requires { it.sender.hasPermission(ItemsFeature.DYE) }
                .executes { ctx -> run(ctx.source.sender, ""); 1 }
                .then(Commands.argument("값", StringArgumentType.greedyString()).suggests(hints)
                    .executes { ctx -> run(ctx.source.sender, StringArgumentType.getString(ctx, "값")); 1 })
                .build(),
            "가죽 갑옷 염색·움직이는 색", listOf("dye"),
        )
        ess.ticks.add("items-dye", 1) { now ->
            counter++
            if (counter % feature.settings.dye.periodSeconds == 0) repaint(now, counter % RESEND_SECONDS < feature.settings.dye.periodSeconds)
        }
        feature.combat.onHurt = { player -> onHurt(player) }
    }

    /** 맞은 사람 — 움직이는 색을 입었으면 다음 틱(바닐라의 장비 갱신 뒤)에 다시 칠하고, 피격 번쩍임이 끝날 즈음 한 번 더. */
    private fun onHurt(player: Player) {
        val inv = player.inventory
        if (SLOTS.none { slot -> inv.getItem(slot).let { !it.type.isAir && it.hasItemMeta() && it.itemMeta.persistentDataContainer.has(KEY) } }) return
        Bukkit.getScheduler().runTask(ess.plugin, Runnable { if (player.isOnline) paintPlayer(player, System.currentTimeMillis(), true) })
        Bukkit.getScheduler().runTaskLater(ess.plugin, Runnable { if (player.isOnline) paintPlayer(player, System.currentTimeMillis(), true) }, HIT_REVERT_TICKS)
    }

    fun run(sender: CommandSender, text: String) {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        val silent = tokens.removeAll { it.equals("-s", ignoreCase = true) }
        var target = sender as? Player
        val first = tokens.firstOrNull()
        if (first != null) {
            val named = Vanish.visibleOnline(sender).firstOrNull { it.name.equals(first, ignoreCase = true) }
            if (named != null) { target = named; tokens.removeAt(0) }
        }
        if (target == null) return ess.messages.send(sender, "player-only")
        if (target != sender && !sender.hasPermission(ItemsFeature.DYE_OTHERS)) return ess.messages.send(sender, "no-permission")
        val stack = target.inventory.itemInMainHand
        val meta = stack.itemMeta as? LeatherArmorMeta ?: return ess.messages.send(sender, "items-dye-not-dyeable")
        if (tokens.isEmpty()) {
            val rgb = meta.color.asRGB()
            val mode = stack.itemMeta.persistentDataContainer.get(KEY, PersistentDataType.STRING)
            return ess.messages.send(sender, "items-dye-current", Ph.of().value(DyeColors.rgbText(rgb)).raw("{색}", DyeColors.hex(rgb)).raw("{모드}", mode ?: "-"))
        }
        val spec = DyeColors.parse(tokens.joinToString(" ")) ?: return ess.messages.send(sender, "items-dye-unknown", Ph.of().value(tokens.joinToString(" ")))
        if (spec is DyeColors.Spec.Dynamic && !sender.hasPermission(ItemsFeature.DYE_DYNAMIC)) return ess.messages.send(sender, "no-permission")
        val ref = ess.customItems.identify(stack)
        if (ref != null && ref.namespace.equals("inmc", ignoreCase = true)) {
            val defined = (ess.customItems.create(ref)?.itemMeta as? LeatherArmorMeta)?.color
            val colored = defined != null && defined != Bukkit.getItemFactory().defaultLeatherColor
            if (colored && spec is DyeColors.Spec.Dynamic && !feature.settings.dye.allowDynamicOnColoredCustom) {
                return ess.messages.send(sender, "items-dye-custom-refused")
            }
            if (colored) ess.messages.send(sender, "items-dye-custom-warning")
        }
        val owner = target.uniqueId.toString()
        stack.editMeta(LeatherArmorMeta::class.java) { m ->
            val pdc = m.persistentDataContainer
            when (spec) {
                is DyeColors.Spec.Static -> {
                    m.setColor(spec.rgb?.let { Color.fromRGB(it) })
                    pdc.remove(KEY)
                    pdc.remove(OWNER)
                }
                is DyeColors.Spec.Dynamic -> {
                    pdc.set(KEY, PersistentDataType.STRING, spec.serialize())
                    pdc.set(OWNER, PersistentDataType.STRING, owner)
                    m.setColor(Color.fromRGB(DyeColors.color(spec, context(target, 0, System.currentTimeMillis()))))
                }
            }
        }
        target.inventory.setItemInMainHand(stack)
        val label = when (spec) {
            is DyeColors.Spec.Static -> spec.rgb?.let { DyeColors.hex(it) } ?: "기본"
            is DyeColors.Spec.Dynamic -> spec.serialize()
        }
        ess.messages.send(sender, "items-dye-set", Ph.of().player(target.name).value(label))
        if (target != sender && !silent) ess.messages.send(target, "items-dye-set-by", Ph.of().player(sender.name).value(label))
    }

    /** 입은 갑옷 중 움직이는 색이 있는 것만 다시 칠한다(보이는 것만 — 진짜 아이템은 그대로). [force] 면 색이 같아도 보낸다. */
    fun repaint(now: Long, force: Boolean = false) {
        val online = Bukkit.getOnlinePlayers()
        if (lastSent.size > online.size) lastSent.keys.retainAll(online.map { it.uniqueId }.toSet())
        for (player in online) paintPlayer(player, now, force)
    }

    /** [player] 가 입은 움직이는 색 갑옷의 지금 색(칸 → 0xRRGGBB). 진짜 아이템은 건드리지 않는다. */
    fun colors(player: Player, now: Long): Map<EquipmentSlot, Int> {
        val bound = feature.settings.dye.boundToPlayer
        val out = LinkedHashMap<EquipmentSlot, Int>()
        val inv = player.inventory
        for ((index, slot) in SLOTS.withIndex()) {
            val item = inv.getItem(slot)
            if (item.type.isAir || !item.hasItemMeta()) continue
            val meta = item.itemMeta as? LeatherArmorMeta ?: continue
            val raw = meta.persistentDataContainer.get(KEY, PersistentDataType.STRING) ?: continue
            if (bound && meta.persistentDataContainer.get(OWNER, PersistentDataType.STRING) != player.uniqueId.toString()) continue
            val spec = DyeColors.parse(raw) as? DyeColors.Spec.Dynamic ?: continue
            out[slot] = DyeColors.color(spec, context(player, index, now))
        }
        return out
    }

    private fun paintPlayer(player: Player, now: Long, force: Boolean) {
        val colors = colors(player, now)
        val last = lastSent.getOrPut(player.uniqueId) { IntArray(SLOTS.size) { -1 } }
        val send = LinkedHashMap<EquipmentSlot, ItemStack?>()
        for ((index, slot) in SLOTS.withIndex()) {
            val rgb = colors[slot]
            if (rgb == null) { last[index] = -1; continue }
            if (!force && last[index] == rgb) continue
            last[index] = rgb
            send[slot] = player.inventory.getItem(slot).clone().also { copy ->
                copy.editMeta(LeatherArmorMeta::class.java) { it.setColor(Color.fromRGB(rgb)) }
            }
        }
        if (send.isEmpty()) return
        player.sendEquipmentChange(player, send)
        for (viewer in player.trackedBy) viewer.sendEquipmentChange(player, send)
    }

    fun context(player: Player, slot: Int, now: Long): DyeColors.Context {
        val max = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        val biome = runCatching { player.world.getBiome(player.location).key().value() }.getOrDefault("plains")
        return DyeColors.Context(
            now = now,
            worldTime = player.world.time,
            biome = biome,
            health = (player.health / max).coerceIn(0.0, 1.0),
            food = player.foodLevel / 20.0,
            air = if (player.maximumAir > 0) player.remainingAir.toDouble() / player.maximumAir else 1.0,
            hurtAgo = now - feature.combat.lastHurt(player.uniqueId),
            combat = feature.combat.inCombat(player.uniqueId, feature.settings.combatSeconds, now),
            slot = slot,
        )
    }

    private val hints = SuggestionProvider<CommandSourceStack> { _, builder ->
        val remaining = builder.remaining
        val start = remaining.lastIndexOf(' ') + 1
        val word = remaining.substring(start).lowercase()
        val out = builder.createOffset(builder.start + start)
        for (w in DyeColors.HINTS) if (w.lowercase().startsWith(word)) out.suggest(w)
        out.buildFuture()
    }

    companion object {
        val KEY = NamespacedKey(Ess.NAMESPACE, "dye")
        val OWNER = NamespacedKey(Ess.NAMESPACE, "dye_owner")

        /** 머리 → 발(물결의 차례). */
        val SLOTS = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)

        /** 색이 같아도 이 초마다 한 번은 다시 보낸다(그새 보기 시작한 사람 몫). */
        private const val RESEND_SECONDS = 5

        /** 피격 번쩍임(1.5초)이 끝난 뒤 되돌리는 칠하기. */
        private const val HIT_REVERT_TICKS = 32L
    }
}

/** 색 읽기·움직이는 색 계산(순수 — 서버 없이 시험). 색은 0xRRGGBB. */
object DyeColors {

    enum class Mode(val words: Set<String>) {
        RAINBOW(setOf("rainbow", "무지개")),
        DAY(setOf("day", "낮밤")),
        BIOME(setOf("biome", "바이옴")),
        HEALTH(setOf("health", "체력")),
        BREATHE(setOf("breathe", "숨쉬기")),
        PALETTE(setOf("palette", "팔레트")),
        WAVE(setOf("wave", "물결")),
        HIT(setOf("hit", "피격")),
        COMBAT(setOf("combat", "전투")),
        HUNGER(setOf("hunger", "배고픔")),
        AIR(setOf("air", "산소")),
        DISCO(setOf("disco", "디스코")),
    }

    sealed interface Spec {
        /** null = 색 지우기. */
        data class Static(val rgb: Int?) : Spec
        data class Dynamic(val mode: Mode, val colors: List<Int>) : Spec {
            fun serialize(): String = mode.name.lowercase() + if (colors.isEmpty()) "" else ":" + colors.joinToString(",") { hex(it) }
        }
    }

    data class Context(
        val now: Long, val worldTime: Long, val biome: String, val health: Double, val food: Double, val air: Double,
        val hurtAgo: Long, val combat: Boolean, val slot: Int,
    )

    val NAMES: Map<String, Int> = linkedMapOf(
        "white" to 0xF9FFFE, "흰색" to 0xF9FFFE, "orange" to 0xF9801D, "주황색" to 0xF9801D, "magenta" to 0xC74EBD, "자홍색" to 0xC74EBD,
        "light_blue" to 0x3AB3DA, "하늘색" to 0x3AB3DA, "yellow" to 0xFED83D, "노란색" to 0xFED83D, "lime" to 0x80C71F, "연두색" to 0x80C71F,
        "pink" to 0xF38BAA, "분홍색" to 0xF38BAA, "gray" to 0x474F52, "회색" to 0x474F52, "light_gray" to 0x9D9D97, "회백색" to 0x9D9D97,
        "cyan" to 0x169C9C, "청록색" to 0x169C9C, "purple" to 0x8932B8, "보라색" to 0x8932B8, "blue" to 0x3C44AA, "파란색" to 0x3C44AA,
        "brown" to 0x835432, "갈색" to 0x835432, "green" to 0x5E7C16, "초록색" to 0x5E7C16, "red" to 0xB02E26, "빨간색" to 0xB02E26,
        "black" to 0x1D1D21, "검은색" to 0x1D1D21,
    )

    val HINTS: List<String> = listOf("#ff0000", "255,0,0", "random", "지우기") + Mode.entries.map { it.words.last() } + NAMES.keys.filter { it.first() > 'z' }

    private val CLEAR = setOf("clear", "지우기", "none")
    private val RANDOM = setOf("random", "랜덤", "무작위")

    fun parse(text: String): Spec? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val lower = t.lowercase()
        if (lower in CLEAR) return Spec.Static(null)
        if (lower in RANDOM) return Spec.Static((Math.random() * 0xFFFFFF).toInt())
        val head = lower.substringBefore(':')
        Mode.entries.firstOrNull { head in it.words }?.let { mode ->
            val colors = if (t.contains(':')) t.substringAfter(':').split(',').map { single(it.trim()) ?: return null } else emptyList()
            return Spec.Dynamic(mode, colors)
        }
        return single(t)?.let { Spec.Static(it) }
    }

    /** 색 하나 — `#rrggbb` · `rrggbb` · `r,g,b` · 이름. */
    fun single(text: String): Int? {
        val t = text.trim().lowercase()
        NAMES[t]?.let { return it }
        val hex = t.removePrefix("#")
        if (hex.length == 6 && hex.all { it in "0123456789abcdef" }) return hex.toInt(16)
        val parts = t.split(',').map { it.trim().toIntOrNull() }
        if (parts.size == 3 && parts.all { it != null && it in 0..255 }) return (parts[0]!! shl 16) or (parts[1]!! shl 8) or parts[2]!!
        return null
    }

    fun hex(rgb: Int): String = "#" + String.format("%06x", rgb and 0xFFFFFF)

    fun rgbText(rgb: Int): String = "${(rgb shr 16) and 0xFF},${(rgb shr 8) and 0xFF},${rgb and 0xFF}"

    fun color(spec: Spec.Dynamic, c: Context): Int {
        val colors = spec.colors
        return when (spec.mode) {
            Mode.RAINBOW -> hsv(((c.now % 12_000L) / 12_000.0).toFloat())
            Mode.WAVE -> hsv((((c.now % 12_000L) / 12_000.0 + c.slot * 0.08) % 1.0).toFloat())
            Mode.DAY -> cycle(DAY_KEYS, c.worldTime % 24_000L, 24_000L)
            Mode.BIOME -> biome(c.biome)
            Mode.HEALTH -> if (c.health < 0.5) lerp(0xC62828, 0xF9A825, c.health * 2) else lerp(0xF9A825, 0x2E7D32, (c.health - 0.5) * 2)
            Mode.BREATHE -> scale(colors.getOrElse(0) { 0x3366FF }, 0.35 + 0.65 * (0.5 + 0.5 * sin(2 * PI * (c.now % 4000L) / 4000.0)))
            Mode.PALETTE -> {
                val list = colors.ifEmpty { listOf(0xE53935, 0xFDD835, 0x43A047, 0x1E88E5, 0x8E24AA) }
                if (list.size == 1) list[0]
                else {
                    val step = 3000L
                    val pos = (c.now % (list.size * step)).toDouble() / step
                    val i = pos.toInt()
                    lerp(list[i % list.size], list[(i + 1) % list.size], pos - i)
                }
            }
            Mode.HIT -> if (c.hurtAgo in 0 until 1500) colors.getOrElse(1) { 0xFF2020 } else colors.getOrElse(0) { 0xF9FFFE }
            Mode.COMBAT -> if (c.combat) colors.getOrElse(1) { 0xC62828 } else colors.getOrElse(0) { 0x2E7D32 }
            Mode.HUNGER -> lerp(0x5A3A1A, colors.getOrElse(0) { 0xF2C14E }, c.food.coerceIn(0.0, 1.0))
            Mode.AIR -> lerp(0xE53935, colors.getOrElse(0) { 0x3FA9F5 }, c.air.coerceIn(0.0, 1.0))
            Mode.DISCO -> {
                val seed = (c.now / 1000L) * 31 + c.slot
                hsv(((seed * 2654435761L) and 0xFFFF).toFloat() / 0xFFFF)
            }
        }
    }

    /** 낮밤 열쇠(월드 시각 → 색). */
    private val DAY_KEYS = listOf(0L to 0xFFE9A8, 6000L to 0x87CEEB, 11_500L to 0xFF8C42, 13_000L to 0x6A4C93, 18_000L to 0x0B1D51, 23_000L to 0xFF9E7A)

    private fun cycle(keys: List<Pair<Long, Int>>, t: Long, period: Long): Int {
        for (i in keys.indices) {
            val (at, color) = keys[i]
            val (nextAt, next) = keys.getOrElse(i + 1) { (keys[0].first + period) to keys[0].second }
            if (t in at until nextAt) return lerp(color, next, (t - at).toDouble() / (nextAt - at))
        }
        return keys.last().second
    }

    fun biome(key: String): Int {
        val k = key.lowercase()
        return when {
            "warped" in k -> 0x167E86
            "nether" in k || "crimson" in k || "soul" in k || "basalt" in k -> 0xA8322D
            "end" in k && ("the_end" in k || "end_" in k || k == "end") -> 0x8E6EC8
            "deep_dark" in k -> 0x0E2A33
            "ocean" in k || "river" in k -> 0x2E6FD8
            "badlands" in k -> 0xC1662F
            "desert" in k || "beach" in k -> 0xE3C770
            "jungle" in k || "bamboo" in k -> 0x2FA82F
            "swamp" in k || "mangrove" in k -> 0x4C6B3A
            "snow" in k || "frozen" in k || "ice" in k || "grove" in k || "peaks" in k -> 0xF2F8FF
            "taiga" in k -> 0x3F6E4A
            "savanna" in k -> 0xBDB25F
            "mushroom" in k -> 0xA36FA8
            "cherry" in k -> 0xF4B6D0
            "dark_forest" in k || "pale" in k -> 0x2D4A1E
            "forest" in k || "birch" in k -> 0x4C9A2A
            "plains" in k || "meadow" in k -> 0x7CBD4F
            "cave" in k || "dripstone" in k -> 0x6B8E23
            else -> 0x8BB55E
        }
    }

    fun lerp(a: Int, b: Int, t: Double): Int {
        val f = t.coerceIn(0.0, 1.0)
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * f).toInt().coerceIn(0, 255)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun scale(rgb: Int, factor: Double): Int {
        fun ch(shift: Int) = (((rgb shr shift) and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun hsv(hue: Float): Int = java.awt.Color.HSBtoRGB(hue, 0.85f, 1.0f) and 0xFFFFFF
}
