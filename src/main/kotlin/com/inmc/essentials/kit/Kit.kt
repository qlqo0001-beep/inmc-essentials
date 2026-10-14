package com.inmc.essentials.kit

import kr.inmc.core.item.StoredItem
import kr.inmc.core.reward.GiveMode
import kr.inmc.core.reward.RewardBundle
import kr.inmc.core.util.Text
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration

/**
 * 킷 하나(연구 give-kits 【2】). 받는 것은 [pools] — 1번 묶음은 보통 ALL(고정), 그 뒤 묶음은 ROLL_ONE/ROLL_N(가중치 추첨). 묶음마다 core
 * `RewardService.resolve` 로 굴려 합친다. 정의는 DB `ess_kits`(YAML 글)로 — 공용 DB 면 서버끼리 같은 킷.
 */
class Kit(
    val id: String,
    var name: String = id,
    var icon: StoredItem? = null,
    var description: MutableList<String> = mutableListOf(),
    /** true 면 `inmcessentials.kits.kit.<id>` 가 있어야 받는다(op 는 저절로). false 면 `…kits.use` 만. */
    var requirePermission: Boolean = true,
    /** 쿨타임(초, 0 = 없음). */
    var cooldown: Long = 0L,
    /** 받을 수 있는 횟수(0 = 무제한, 1 = 한 번). */
    var uses: Int = 0,
    /** 더 있어야 하는 권한(CMI Conditions). */
    var conditions: MutableList<String> = mutableListOf(),
    var money: Double = 0.0,
    var currency: String = "",
    /** 경험치 점수 비용(CMI ExpCost). */
    var exp: Int = 0,
    var preview: Boolean = true,
    /** 처음 들어온 사람에게 한 번(CMI Newbie.Kit). */
    var firstJoin: Boolean = false,
    /**
     * 같은 그룹 킷 여럿(CMI Group/Weight/CommandName). 기본(설정 group-mode: priority — CMI 와 같음): 받을 수 있는 것 중 [weight] 가
     * 가장 높은 것만 받고 낮은 것은 막힌다 · `/킷 <그룹>` 은 그 하나. random: `/킷 <그룹>` 이 무게로 하나를 뽑고 그룹 전체에 기록이 남는다.
     */
    var group: String = "",
    var weight: Int = 1,
    /** 더 높은 무게에 밀려 못 받아도 목록·미리보기에는 보인다(CMI ShowDespiteWeight). */
    var showDespiteWeight: Boolean = false,
    /** 목록 화면에 안 보인다(명령으로는 받을 수 있다). */
    var hidden: Boolean = false,
    /** 목록 화면의 고정 칸(-1 = 차례대로). */
    var slot: Int = -1,
    val pools: MutableList<RewardBundle> = mutableListOf(RewardBundle(GiveMode.ALL)),
) {
    val permission: String get() = PERMISSION_PREFIX + id
    val previewPermission: String get() = PERMISSION_PREFIX + id + ".preview"

    fun isEmpty(): Boolean = pools.all { it.isEmpty() }

    fun toYaml(): String {
        val y = YamlConfiguration()
        y.set("name", name)
        icon?.save(y.createSection("icon"))
        y.set("description", description)
        y.set("require-permission", requirePermission)
        y.set("cooldown", cooldown)
        y.set("uses", uses)
        y.set("conditions", conditions)
        y.set("money", money)
        y.set("currency", currency)
        y.set("exp", exp)
        y.set("preview", preview)
        y.set("first-join", firstJoin)
        y.set("group", group)
        y.set("weight", weight)
        y.set("show-despite-weight", showDespiteWeight)
        y.set("hidden", hidden)
        y.set("slot", slot)
        val list = y.createSection("pools")
        pools.forEachIndexed { i, pool -> pool.save(list.createSection(i.toString())) }
        return y.saveToString()
    }

    fun copyOf(newId: String = id): Kit = fromYaml(newId, toYaml())

    companion object {
        /** 킷마다의 권한 앞부분. 노드 자체(`inmcessentials.kits.kit`)는 "모든 킷"(KitsFeature.ALL_KITS). */
        const val PERMISSION_PREFIX = KitsFeature.ALL_KITS + "."

        private val ID = Regex("^[a-z0-9_-]{1,32}$")

        fun validId(id: String): Boolean = ID.matches(id)

        /** 아무 이름 → id(소문자·숫자·_-, 32자). 남는 것이 없으면 null. */
        fun sanitize(raw: String): String? =
            raw.trim().lowercase().replace(' ', '_').filter { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }.take(32).ifEmpty { null }

        fun fromYaml(id: String, text: String): Kit {
            val y = YamlConfiguration()
            y.loadFromString(text)
            val pools = mutableListOf<RewardBundle>()
            y.getConfigurationSection("pools")?.let { s ->
                for (key in s.getKeys(false).sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }) pools += RewardBundle.load(s.getConfigurationSection(key))
            }
            if (pools.isEmpty()) pools += RewardBundle(GiveMode.ALL)
            return Kit(
                id = id,
                name = y.getString("name", id) ?: id,
                icon = y.getConfigurationSection("icon")?.let { StoredItem.load(it) },
                description = y.getStringList("description").toMutableList(),
                requirePermission = y.getBoolean("require-permission", true),
                cooldown = y.getLong("cooldown", 0L).coerceAtLeast(0L),
                uses = y.getInt("uses", 0).coerceAtLeast(0),
                conditions = y.getStringList("conditions").toMutableList(),
                money = y.getDouble("money", 0.0).coerceAtLeast(0.0),
                currency = y.getString("currency", "").orEmpty(),
                exp = y.getInt("exp", 0).coerceAtLeast(0),
                preview = y.getBoolean("preview", true),
                firstJoin = y.getBoolean("first-join", false),
                group = y.getString("group", "").orEmpty(),
                weight = y.getInt("weight", 1).coerceAtLeast(1),
                showDespiteWeight = y.getBoolean("show-despite-weight", false),
                hidden = y.getBoolean("hidden", false),
                slot = y.getInt("slot", -1),
                pools = pools,
            )
        }
    }
}

/** 한 사람의 한 킷 기록. */
data class KitUsage(val lastUsed: Long, val uses: Int)

/** 킷 규칙(순수 — 서버 없이 시험). */
object KitRules {

    /** 남은 쿨타임(초). 없으면 0. */
    fun cooldownLeft(kit: Kit, usage: KitUsage?, now: Long): Long {
        if (kit.cooldown <= 0L || usage == null || usage.lastUsed <= 0L) return 0L
        val left = usage.lastUsed + kit.cooldown * 1000L - now
        return if (left <= 0L) 0L else (left + 999L) / 1000L
    }

    fun usedUp(kit: Kit, usage: KitUsage?): Boolean = kit.uses > 0 && (usage?.uses ?: 0) >= kit.uses

    fun sameGroup(a: Kit, b: Kit): Boolean = a.group.isNotEmpty() && a.group.equals(b.group, ignoreCase = true)

    /** CMI 무게 규칙 — [kit] 보다 무게가 높은 같은 그룹 킷 중 [accessible] 한 것이 있으면 밀린다. */
    fun superseded(kit: Kit, all: Collection<Kit>, accessible: (Kit) -> Boolean): Boolean =
        kit.group.isNotEmpty() && all.any { it !== kit && sameGroup(it, kit) && it.weight > kit.weight && accessible(it) }

    /** `/킷 <그룹>`(priority) — 받을 수 있는 것 중 무게가 가장 높은 것(같으면 앞의 것). */
    fun topOfGroup(members: List<Kit>, accessible: (Kit) -> Boolean): Kit? =
        members.filter(accessible).maxByOrNull { it.weight }

    /** 사용 기록을 남길 킷 — random 모드의 그룹 킷은 그룹 전체(한 번 뽑으면 그룹이 쿨타임·횟수에 걸린다). */
    fun recordKits(kit: Kit, all: Collection<Kit>, randomGroups: Boolean): List<Kit> =
        if (!randomGroups || kit.group.isEmpty()) listOf(kit) else listOf(kit) + all.filter { it !== kit && sameGroup(it, kit) }

    /** 무게로 하나(무게 0 이하는 1로 본다). [roll] 은 0 ≤ x < 1. */
    fun <T> weighted(items: List<T>, weight: (T) -> Int, roll: Double): T? {
        if (items.isEmpty()) return null
        val total = items.sumOf { weight(it).coerceAtLeast(1) }
        var left = roll * total
        for (item in items) {
            left -= weight(item).coerceAtLeast(1)
            if (left < 0) return item
        }
        return items.last()
    }

    /** CMI Group/CommandName → 우리 그룹: Group 이 있으면 Group(무게 규칙의 뜻), 없으면 CommandName(같은 명령 이름 묶음), 둘 다 없으면 없음. */
    fun importGroup(cmiGroup: String?, commandName: String?): String =
        cmiGroup?.trim()?.takeIf { it.isNotEmpty() } ?: commandName?.trim().orEmpty()

    /**
     * CMI 가 처음 깔아 주는 Kits.yml 예제(Newbie·Advanced·Ad1~3) **그대로**인가 — 이름만이 아니라 킷마다 내용(지연·비용·그룹·무게·설명·
     * 명령·조건·아이템 종류·개수·이름·키 목록)까지 jar 기본값과 같아야 한다. 하나라도 고쳤으면 운영자가 쓰던 킷이니 가져온다.
     */
    fun isCmiDefault(yaml: ConfigurationSection): Boolean {
        val names = yaml.getKeys(false)
        val defaults = cmiDefaults()
        if (names != defaults.getKeys(false)) return false
        return names.all { name ->
            val a = yaml.getConfigurationSection(name) ?: return@all false
            val b = defaults.getConfigurationSection(name) ?: return@all false
            fingerprint(a) == fingerprint(b)
        }
    }

    /** CMI 킷 섹션 하나의 내용 지문(순수) — 주석·따옴표·들여쓰기와 무관. */
    fun fingerprint(s: ConfigurationSection): String = buildList {
        add("keys=" + s.getKeys(false).sorted())
        add("delay=" + s.getLong("Delay", 0L))
        add("money=" + s.getDouble("MoneyCost", 0.0))
        add("exp=" + s.getInt("ExpCost", 0))
        add("group=" + s.getString("Group", "").orEmpty())
        add("command=" + s.getString("CommandName", "").orEmpty())
        add("weight=" + s.getInt("Weight", 1))
        add("desc=" + s.getStringList("Description"))
        add("commands=" + s.getStringList("Commands"))
        add("conditions=" + s.getStringList("Conditions"))
        val items = s.getConfigurationSection("Items")
        add("items=" + items?.getKeys(false)?.sorted()?.joinToString(";") { key ->
            val item = items.getConfigurationSection(key)
            if (item == null) "$key:?" else "$key:${item.getString("type")}x${item.getInt("amount", 1)}:${metaText(item.get("meta"))}"
        })
    }.joinToString("|")

    /**
     * 아이템 `meta` 의 이름·설명·인챈트 — 서버에서 읽으면 `==: ItemMeta` 가 ItemMeta 로 풀리고(jar 기본값·시험에서는 섹션) 둘을 견주므로 **양쪽을
     * 같은 모양으로 고른다**(2026-10-14 검토): 이름·설명은 색 코드를 뗀 평문(ItemMeta 쪽은 컴포넌트 → 레거시 되돌림에서 `§r`·`§f` 가 끼어들 수 있다),
     * 인챈트는 [enchantKey] 로 고른 열쇠(판마다 이름이 바뀌어 ARROW_DAMAGE → power)와 레벨. 색만 바꾼 것은 같다고 본다.
     */
    private fun metaText(meta: Any?): String = when (meta) {
        is ConfigurationSection -> metaText(
            meta.getString("display-name"), meta.getStringList("lore"),
            meta.getConfigurationSection("enchants")?.getValues(false)?.mapValues { it.value.toString().toIntOrNull() ?: 0 }.orEmpty(),
        )
        is org.bukkit.inventory.meta.ItemMeta -> metaText(
            if (meta.hasDisplayName()) meta.displayName()?.let { Text.plain(it) } else null,
            meta.lore()?.map { Text.plain(it) } ?: emptyList(),
            meta.enchants.entries.associate { (ench, level) -> ench.key.key to level },
        )
        null -> ""
        else -> meta.toString()
    }

    /** 이름·설명·인챈트의 지문 글(순수) — 색 코드를 떼고, 인챈트는 [enchantKey] 로 골라 정렬. */
    fun metaText(name: String?, lore: List<String>, enchants: Map<String, Int>): String =
        "name=" + stripColor(name) + "|lore=" + lore.map(::stripColor) +
            "|ench=" + enchants.entries.map { enchantKey(it.key) to it.value }.sortedBy { it.first }

    /** 레거시 `§x` 색·꾸밈 코드(16진 `§x§R§R…` 포함)를 뗀다(순수). */
    fun stripColor(text: String?): String = text.orEmpty().replace(LEGACY_CODE, "")

    /** 인챈트 이름을 지금 판의 열쇠로(순수): 소문자·`minecraft:` 뗌 · 1.20.4 이전 Bukkit 이름(`ARROW_DAMAGE`)은 새 이름(`power`)으로. */
    fun enchantKey(name: String): String {
        val plain = name.trim().lowercase().removePrefix("minecraft:")
        return LEGACY_ENCHANTS[plain] ?: plain
    }

    private val LEGACY_CODE = Regex("§[0-9a-fk-orxA-FK-ORX]")

    /** Bukkit 1.20.4 이전 `Enchantment` 상수 이름 → 1.20.5+ 열쇠(바뀐 것만 — 같은 것은 소문자로 그대로 맞는다). */
    private val LEGACY_ENCHANTS: Map<String, String> = mapOf(
        "arrow_damage" to "power", "arrow_fire" to "flame", "arrow_infinite" to "infinity", "arrow_knockback" to "punch",
        "damage_all" to "sharpness", "damage_arthropods" to "bane_of_arthropods", "damage_undead" to "smite",
        "dig_speed" to "efficiency", "durability" to "unbreaking", "loot_bonus_blocks" to "fortune", "loot_bonus_mobs" to "looting",
        "luck" to "luck_of_the_sea", "oxygen" to "respiration", "protection_environmental" to "protection",
        "protection_explosions" to "blast_protection", "protection_fall" to "feather_falling", "protection_fire" to "fire_protection",
        "protection_projectile" to "projectile_protection", "sweeping_edge" to "sweeping_edge", "water_worker" to "aqua_affinity",
    )

    /**
     * 검증기용 — jar 기본값 글에 CMI 가 실제 파일에 쓰는 `==: ItemMeta`·`meta-type` 줄을 끼운 것. 서버에서 읽으면 `meta` 가 ItemMeta 로 풀리므로
     * [isCmiDefault] 가 ItemMeta ↔ 섹션 비교에서도 true 여야 한다(서버 없는 시험에서는 역직렬화할 수 없어 쓰지 않는다).
     */
    internal fun cmiDefaultsAsItemMeta(): String =
        CMI_DEFAULT_KITS.replace("      meta:\n", "      meta:\n        ==: ItemMeta\n        meta-type: UNSPECIFIC\n")

    /**
     * 검증기용 — 실제 `plugins/CMI/Kits/Kits.yml` 글이 jar 예제 그대로인가(순수, 글로만 본다): 주석·빈 줄·`==:`·`meta-type:` 줄을 빼고 줄 끝 공백을 떼 견준다.
     * 그렇다면 [isCmiDefault] 도 true 여야 한다.
     */
    internal fun looksLikeCmiDefaultText(text: String): Boolean = normalizeText(text) == normalizeText(CMI_DEFAULT_KITS)

    private fun normalizeText(text: String): List<String> = text.lines().map { it.trimEnd() }
        .filter { l -> l.isNotBlank() && !l.trimStart().startsWith("#") && !l.trimStart().startsWith("==:") && !l.trimStart().startsWith("meta-type:") }

    /** CMI 9.x jar 의 `Kits/Kits.yml` 예제의 값(주석 뺌 — 지문 비교용). */
    private fun cmiDefaults(): YamlConfiguration = YamlConfiguration().apply { loadFromString(CMI_DEFAULT_KITS) }

    internal const val CMI_DEFAULT_KITS = """
Newbie:
  CommandName: Newbie
  Description:
  - '&eWill give you some stuff for money and exp'
  - '&eCan be used every X minutes'
  Delay: 36000
  MoneyCost: 100
  ExpCost: 30
  Group: 'groupName'
  Items:
    '1':
      v: 2730
      type: STONE
      amount: 70
      meta:
        display-name: §3Stone
        lore:
        - §2Name §3{USERNAME}
        - §3Display name §2{DISPLAYNAME}
        - §2Kit §3{KITNAME}
        - §3World name §2{WORLDNAME}
        enchants:
          ARROW_DAMAGE: 3
          THORNS: 5
    '3':
      v: 2730
      type: OAK_PLANKS
      amount: 30
  Commands:
  - 'asConsole! msg! [playerName] &eYou just got &6{KITNAME} &ekit!'
  Conditions:
  - cmi.kit.get
  - cmi.command.fly
Advanced:
  Delay: 36000
  Items:
    '1':
      v: 2730
      type: STONE
Ad2:
  CommandName: Ad
  Weight: 2
  Delay: 36060
  Group: resources
  Items:
    '1':
      v: 2730
      type: OAK_PLANKS
      amount: 10
Ad1:
  CommandName: Ad
  Weight: 1
  Delay: 36000
  Group: resources
  Items:
    '1':
      v: 2730
      type: STONE
      amount: 10
Ad3:
  Delay: 36001
  Group: resources
  Items:
    '1':
      v: 2730
      type: DIRT
      amount: 10
"""

    /** CMI 명령의 변수를 우리 것으로(`[playerName]`·`{USERNAME}` → `{player}`) — 앞의 `asConsole!` 는 뗀다. */
    fun cmiCommand(raw: String): String =
        raw.trim().removePrefix("asConsole!").trim()
            .replace("[playerName]", "{player}").replace("{USERNAME}", "{player}").replace("[player]", "{player}")
}
