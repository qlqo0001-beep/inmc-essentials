package com.inmc.essentials.world

import org.bukkit.NamespacedKey
import org.bukkit.entity.AbstractVillager
import org.bukkit.entity.Ambient
import org.bukkit.entity.Animals
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Enemy
import org.bukkit.entity.Entity
import org.bukkit.entity.Hanging
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Tameable
import org.bukkit.entity.Vehicle
import org.bukkit.entity.WaterMob
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.InventoryHolder
import org.bukkit.persistence.PersistentDataType

/**
 * `/몹제거` 의 깃발 읽기와 분류(CMI killall). 깃발: `-monsters` `-pets` `-npc` `-animals` `-ambient` `-vehicles` `-named` `-withitems` `-all`
 * `-force`(`-f`) `-lightning` `-list` `-s` `-m:종류,종류` `-r:반경` `-w:월드` — 한글 `-몬스터` `-애완` `-주민` `-동물` `-주변` `-탈것` `-이름`
 * `-아이템` `-전부` `-강제` `-번개` `-목록` `-조용히` `-종류:` `-반경:` `-월드:` 도.
 *
 * 기본(범주 없음)은 CMI 와 같이 **몬스터**. 보호(이름 붙음·길들임·끈에 묶임·아이템을 든 것·inmc 커스텀 몬스터·짐이 든 탈것)는 `-force` 없이는
 * 안 지운다 — `-named`·`-pets`·`-withitems` 는 그 보호만 푼다. **`-all` 은 보호를 풀지 않는다**(애완동물 범주를 고르지만 길들인 것은 `-pets` 를
 * 직접 써야 지운다). 탈것에 탄 것·플레이어·갑옷 거치대·액자·Citizens NPC·Shopkeepers 상인은 늘 뺀다.
 */
object KillAll {

    enum class Category { MONSTERS, PETS, NPC, ANIMALS, AMBIENT, VEHICLES }

    data class Options(
        val categories: Set<Category> = setOf(Category.MONSTERS),
        val types: Set<String> = emptySet(),
        val named: Boolean = false,
        val withItems: Boolean = false,
        /** `-pets` 를 직접 썼다 — 길들인 것의 보호를 푼다(`-all` 로 고른 애완동물 범주는 풀지 않는다). */
        val pets: Boolean = false,
        val force: Boolean = false,
        val lightning: Boolean = false,
        val listOnly: Boolean = false,
        val silent: Boolean = false,
        val radius: Int? = null,
        val world: String? = null,
    )

    sealed interface Parse {
        data class Ok(val options: Options) : Parse
        data class Bad(val token: String) : Parse
    }

    private val CATEGORY_WORDS: Map<String, Category> = mapOf(
        "monsters" to Category.MONSTERS, "monster" to Category.MONSTERS, "몬스터" to Category.MONSTERS, "적" to Category.MONSTERS,
        "pets" to Category.PETS, "pet" to Category.PETS, "애완" to Category.PETS, "애완동물" to Category.PETS,
        "npc" to Category.NPC, "npcs" to Category.NPC, "주민" to Category.NPC,
        "animals" to Category.ANIMALS, "animal" to Category.ANIMALS, "동물" to Category.ANIMALS,
        "ambient" to Category.AMBIENT, "주변" to Category.AMBIENT, "박쥐" to Category.AMBIENT,
        "vehicles" to Category.VEHICLES, "vehicle" to Category.VEHICLES, "탈것" to Category.VEHICLES,
    )

    /** 깃발 목록 → 옵션. 모르는 깃발이면 [Parse.Bad]. */
    fun parse(tokens: List<String>): Parse {
        val categories = LinkedHashSet<Category>()
        var types = emptySet<String>()
        var named = false
        var withItems = false
        var pets = false
        var force = false
        var lightning = false
        var listOnly = false
        var silent = false
        var radius: Int? = null
        var world: String? = null
        var all = false
        for (raw in tokens) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            if (!t.startsWith("-")) return Parse.Bad(t)
            val body = t.drop(1)
            val (key, value) = body.substringBefore(':').lowercase() to (if (':' in body) body.substringAfter(':') else null)
            when {
                value != null && key in setOf("m", "mob", "mobs", "type", "종류") ->
                    types = types + value.split(',').map { it.trim().lowercase().removePrefix("minecraft:") }.filter { it.isNotEmpty() }
                value != null && key in setOf("r", "range", "radius", "반경", "범위") -> radius = value.toIntOrNull()?.takeIf { it > 0 } ?: return Parse.Bad(t)
                value != null && key in setOf("w", "world", "월드") -> world = value.takeIf { it.isNotBlank() } ?: return Parse.Bad(t)
                value != null -> return Parse.Bad(t)
                key in CATEGORY_WORDS -> CATEGORY_WORDS.getValue(key).let { c ->
                    categories += c
                    if (c == Category.PETS) pets = true
                }
                key in setOf("named", "name", "이름") -> named = true
                key in setOf("withitems", "items", "아이템") -> withItems = true
                key in setOf("all", "전부", "모두") -> all = true
                key in setOf("force", "f", "강제") -> force = true
                key in setOf("lightning", "번개") -> lightning = true
                key in setOf("list", "목록") -> listOnly = true
                key in setOf("s", "silent", "조용히") -> silent = true
                else -> return Parse.Bad(t)
            }
        }
        val cats = when {
            all -> Category.entries.toSet()
            categories.isEmpty() && types.isEmpty() && !named && !withItems -> setOf(Category.MONSTERS)
            else -> categories
        }
        return Parse.Ok(Options(cats, types, named, withItems, pets, force, lightning, listOnly, silent, radius, world))
    }

    /** 몹이 주운 장비를 볼 칸들(몸 칸은 거치대·말에만 있어 뺀다). */
    val EQUIPMENT_SLOTS = listOf(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)

    /**
     * 이 장비 칸이 **몹이 주운 것**인가 — 바닐라는 주운 아이템의 떨굼 확률을 2.0 으로 표시한다(`Mob.setGuaranteedDrop`). 플러그인이 준 "반드시 떨구는"
     * 장비(inmc-monster 의 drop-chance 100)는 정확히 1.0 이라 주운 것이 아니다 — 지우기가 보스 장비를 공짜로 떨구면 안 된다(2026-10-14 리뷰).
     */
    fun pickedUp(dropChance: Float): Boolean = dropChance > 1f

    sealed interface Scope {
        /** `-r` 을 줬는데 가운데(그 월드에 서 있는 사람)가 없다 — 거절. */
        data object NeedCenter : Scope
        /** 그 월드에 읽힌 것 전부. */
        data object Whole : Scope
        data class Radius(val radius: Int, val capped: Boolean) : Scope
    }

    /**
     * 지울 범위(순수 — `WorldRulesTest`). `-r` 이 있으면 그 반경(최대 [max] 로 자름) — 가운데가 없으면 [Scope.NeedCenter](콘솔 · `-w` 로 다른 월드에서
     * 반경이 조용히 버려져 월드 전체가 지워지던 것 — 2026-10-10 리뷰). `-r` 이 없으면: 가운데가 있고 `-w` 를 안 줬으면 기본 반경, 아니면 월드 전체.
     */
    fun scope(radius: Int?, defaultRadius: Int, max: Int, hasCenter: Boolean, worldGiven: Boolean): Scope = when {
        radius != null && !hasCenter -> Scope.NeedCenter
        radius != null -> Scope.Radius(radius.coerceAtMost(max), radius > max)
        hasCenter && !worldGiven -> Scope.Radius(defaultRadius, false)
        else -> Scope.Whole
    }

    /** 커스텀 몬스터 표식 — inmc-monster 의 `mob_id`(비어 있지 않으면 커스텀 몹, ARCHITECTURE "남의 PDC 를 읽는 곳"). */
    val CUSTOM_MOB_KEY = NamespacedKey("inmc-monster", "mob_id")

    /** 이 엔티티의 범주(지울 수 없는 종류면 null). */
    fun categoryOf(entity: Entity): Category? = when {
        entity is Player || entity is ArmorStand || entity is Hanging -> null
        entity.hasMetadata("NPC") -> null // Citizens NPC — 지우면 Citizens 가 다시 세우거나 기록이 꼬인다
        entity.hasMetadata("shopkeeper") -> null // Shopkeepers 상인(주민) — 지우면 상점이 사라진다(그 플러그인이 다루게 둔다)
        entity is Tameable && entity.isTamed -> Category.PETS
        entity is AbstractVillager -> Category.NPC
        entity is Enemy -> Category.MONSTERS
        entity is Animals || entity is WaterMob -> Category.ANIMALS
        entity is Ambient -> Category.AMBIENT
        entity is Vehicle && entity !is LivingEntity -> Category.VEHICLES
        entity is LivingEntity -> Category.ANIMALS // 골렘·눈사람 등 — 동물 범주로(기본 몬스터 제거에는 안 걸린다)
        else -> null
    }

    /** 보호받는 까닭(없으면 null) — `-force` 가 없으면 지우지 않는다. */
    enum class Protection { NAMED, TAMED, LEASHED, ITEMS, CUSTOM_MOB, RIDING, CARGO }

    fun protection(entity: Entity, options: Options): Protection? {
        // 탈것에 탄 것은 늘 뺀다(CMI). 사람이 탄 탈것도 — 지우면 사람이 떨어진다.
        if (entity.isInsideVehicle || entity.passengers.any { it is Player }) return Protection.RIDING
        if (options.force) return null
        if (entity.customName() != null && !options.named) return Protection.NAMED
        if (entity is Tameable && entity.isTamed && !options.pets) return Protection.TAMED
        if (entity is LivingEntity && entity.isLeashed) return Protection.LEASHED
        if (!entity.persistentDataContainer.get(CUSTOM_MOB_KEY, PersistentDataType.STRING).isNullOrEmpty()) return Protection.CUSTOM_MOB
        if (entity is LivingEntity && !options.withItems && holdsPickedItems(entity)) return Protection.ITEMS
        // 상자·호퍼 수레·상자 보트 — remove() 는 안의 아이템을 떨구지 않고 없앤다.
        if (entity !is LivingEntity && entity is InventoryHolder && !entity.inventory.isEmpty) return Protection.CARGO
        return null
    }

    /** 주운 아이템을 든 몹(사람이 떨군 장비를 쥐고 있을 수 있다 — CMI "With items"). */
    private fun holdsPickedItems(entity: LivingEntity): Boolean {
        if (!entity.canPickupItems) return false
        val eq = entity.equipment ?: return false
        return listOf(eq.itemInMainHand, eq.itemInOffHand, eq.helmet, eq.chestplate, eq.leggings, eq.boots).any { it != null && !it.type.isAir }
    }

    /** 이 옵션으로 지울 대상인가(보호는 따로 [protection]). */
    fun selected(entity: Entity, category: Category, options: Options): Boolean {
        val type = entity.type.key.key
        if (options.types.isNotEmpty()) return type in options.types
        return category in options.categories || (options.named && entity.customName() != null)
    }
}
