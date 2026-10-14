package com.inmc.essentials.item

import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration

/**
 * `settings/items.yml` 의 불변 스냅샷(CMI config.yml 의 Optimizations.Commands.Give/Clear · ItemRenaming · Optimizations.ItemName/ItemLore ·
 * ShulkerBoxes · Dye · search · CustomHeads.yml EntityHeads 에 해당). 값이 이상하면 기본값 — 던지지 않는다(규칙 12).
 */
data class ItemsSettings(
    val give: GiveSettings = GiveSettings(),
    /** 몹 → 머리 목록(재질 이름 `ZOMBIE_HEAD` 또는 텍스처 해시). */
    val mobHeads: Map<String, List<String>> = emptyMap(),
    val naming: NamingSettings = NamingSettings(),
    val shulker: ShulkerSettings = ShulkerSettings(),
    val clear: ClearSettings = ClearSettings(),
    val dye: DyeSettings = DyeSettings(),
    val search: SearchSettings = SearchSettings(),
    /** 맞거나 때린 뒤 이 초 동안 "전투 중"(셜커 배낭 막기 · 염색 combat 모드). */
    val combatSeconds: Int = 10,
) {
    companion object {
        fun from(y: YamlConfiguration): ItemsSettings {
            val d = ItemsSettings()
            val heads = LinkedHashMap<String, List<String>>()
            (y.getConfigurationSection("mob-heads") ?: y.defaults?.getConfigurationSection("mob-heads"))?.let { s ->
                for (key in s.getKeys(false)) {
                    val list = s.getStringList(key).map { it.trim() }.filter { it.isNotEmpty() }
                    if (list.isNotEmpty()) heads[key.lowercase()] = list
                }
            }
            val rules = if (y.isList("naming.rules")) y.getStringList("naming.rules").mapNotNull { RenameRule.parse(it) } else d.naming.rules
            val blacklist = if (y.isList("naming.lore-type-blacklist")) materials(y.getStringList("naming.lore-type-blacklist")) else d.naming.loreTypeBlacklist
            return ItemsSettings(
                give = GiveSettings(
                    offlineDays = y.getInt("give.offline-days", d.give.offlineDays).coerceIn(1, 3650),
                    maxAmount = y.getInt("give.max-amount", d.give.maxAmount).coerceIn(1, 100_000),
                    perTick = y.getInt("give.recipients-per-tick", d.give.perTick).coerceIn(1, 5000),
                    notifyTarget = y.getBoolean("give.notify-target", d.give.notifyTarget),
                ),
                mobHeads = heads,
                naming = NamingSettings(
                    nameMaxLength = y.getInt("naming.name-max-length", d.naming.nameMaxLength).coerceAtLeast(0),
                    loreMaxLength = y.getInt("naming.lore-max-length", d.naming.loreMaxLength).coerceAtLeast(0),
                    loreTypeBlacklist = blacklist,
                    markChanged = y.getBoolean("naming.mark-changed", d.naming.markChanged),
                    prevent = y.getBoolean("naming.prevent", d.naming.prevent),
                    globalDisable = y.getBoolean("naming.global-disable", d.naming.globalDisable),
                    checkSource = y.getBoolean("naming.check-source", d.naming.checkSource),
                    rules = rules,
                    anvil = y.getBoolean("naming.anvil", d.naming.anvil),
                    anvilColors = y.getBoolean("naming.anvil-colors", d.naming.anvilColors),
                    anvilItalic = y.getBoolean("naming.anvil-italic-by-default", d.naming.anvilItalic),
                ),
                shulker = ShulkerSettings(
                    enabled = y.getBoolean("shulker-backpack.enabled", d.shulker.enabled),
                    shiftOnly = y.getBoolean("shulker-backpack.shift-only", d.shulker.shiftOnly),
                    fromHand = y.getBoolean("shulker-backpack.open-from-hand", d.shulker.fromHand),
                    preventInCombat = y.getBoolean("shulker-backpack.prevent-in-combat", d.shulker.preventInCombat),
                    requireEditPermission = y.getBoolean("shulker-backpack.require-edit-permission", d.shulker.requireEditPermission),
                    cost = y.getDouble("shulker-backpack.cost", d.shulker.cost).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
                    currency = y.getString("shulker-backpack.currency", "").orEmpty().trim(),
                    preventWhileMoving = y.getBoolean("shulker-backpack.prevent-while-moving", d.shulker.preventWhileMoving),
                    moveTolerance = y.getDouble("shulker-backpack.move-tolerance", d.shulker.moveTolerance).coerceIn(0.1, 64.0),
                    allowCustomItems = y.getBoolean("shulker-backpack.allow-custom-items", d.shulker.allowCustomItems),
                ),
                clear = ClearSettings.of(
                    confirm = y.getBoolean("clear.confirm", d.clear.confirm),
                    entries = y.getStringList("clear.exclude") + y.getStringList("clear.exclude-pdc"),
                ),
                dye = DyeSettings(
                    boundToPlayer = y.getBoolean("dye.bound-to-player", d.dye.boundToPlayer),
                    periodSeconds = y.getInt("dye.period-seconds", d.dye.periodSeconds).coerceIn(1, 60),
                    allowDynamicOnColoredCustom = y.getBoolean("dye.allow-dynamic-on-colored-custom-items", d.dye.allowDynamicOnColoredCustom),
                ),
                search = SearchSettings(
                    maxResults = y.getInt("search.max-results", d.search.maxResults).coerceIn(10, 100_000),
                    enablePurge = y.getBoolean("search.enable-purge", d.search.enablePurge),
                    logIntoFile = y.getBoolean("search.log-into-file", d.search.logIntoFile),
                    pageSize = y.getInt("search.page-size", d.search.pageSize).coerceIn(5, 50),
                ),
                combatSeconds = y.getInt("combat-seconds", d.combatSeconds).coerceIn(1, 600),
            )
        }

        private fun materials(names: List<String>): Set<Material> =
            names.mapNotNull { Material.matchMaterial(it.trim()) }.toSet()
    }
}

data class GiveSettings(
    /** `**` 대상: 최근 이 날 안에 들어온 사람(CMI mailall 의 7일 — 여기는 30일). `-d:<일>` 로 바꿀 수 있다. */
    val offlineDays: Int = 30,
    val maxAmount: Int = 2304,
    /** 여러 사람 지급을 한 틱에 몇 명씩(서버가 멈추지 않게). */
    val perTick: Int = 200,
    val notifyTarget: Boolean = true,
)

data class NamingSettings(
    /** 이름 최대 글자(색 빼고, 0 = 제한 없음 — CMI ItemRenaming.MaxLength). */
    val nameMaxLength: Int = 64,
    /** 설명 한 줄 최대 글자(CMI Optimizations.ItemLore.MaxLength). */
    val loreMaxLength: Int = 64,
    /** /설명 을 막는 재질(CMI ItemLore.TypeBlackList — 배포 설정의 기본은 gold_nugget). 기본값에 Material 을 두지 않는다(시험이 서버 없이 기능을 만든다). */
    val loreTypeBlacklist: Set<Material> = emptySet(),
    /** 바꾼 아이템에 표식(PDC `inmcess:modified_name`·`modified_lore`) — CMI MarkChanged. */
    val markChanged: Boolean = false,
    val prevent: Boolean = false,
    val globalDisable: Boolean = false,
    val checkSource: Boolean = true,
    /** 배포 설정의 기본은 `spawner:([A-z]+ (?i)\w*spawner)`(CMI 와 같음). 기본값에 Material 을 두지 않는다. */
    val rules: List<RenameRule> = emptyList(),
    /** 모루 이름 바꾸기에도 같은 제한을 건다(CMI Modules anvilRename). */
    val anvil: Boolean = true,
    /** 모루 이름에 색(& 코드·MiniMessage) — `…itemname.color` 가 있는 사람만. */
    val anvilColors: Boolean = true,
    /** 모루로 색을 넣은 이름을 기울임꼴로(바닐라 이름처럼 — CMI ItemRenaming.Anvil.ItalicByDefault). */
    val anvilItalic: Boolean = true,
)

/** 재질(null = 전부)과 막을 이름(정규식, 이름 전체가 맞으면 막음). CMI `ItemRenaming.List` 의 `재질:정규식`. */
data class RenameRule(val material: Material?, val regex: Regex) {
    fun blocks(material: Material, plainName: String): Boolean =
        (this.material == null || this.material == material) && regex.matches(plainName)

    companion object {
        fun parse(raw: String): RenameRule? {
            val text = raw.trim()
            val at = text.indexOf(':')
            if (at <= 0) return null
            val name = text.substring(0, at).trim()
            val pattern = text.substring(at + 1)
            val material = when (name.lowercase()) {
                "*" -> null
                "mobspawner" -> Material.SPAWNER
                else -> Material.matchMaterial(name) ?: return null
            }
            val regex = runCatching { Regex(pattern) }.getOrNull() ?: return null
            return RenameRule(material, regex)
        }
    }
}

data class ShulkerSettings(
    val enabled: Boolean = true,
    /** true 면 Shift+우클릭으로만(그냥 우클릭은 바닐라처럼 집기). */
    val shiftOnly: Boolean = false,
    /** 손에 들고 허공 우클릭으로도 연다(블록을 보고 있으면 바닐라대로 놓는다). */
    val fromHand: Boolean = true,
    val preventInCombat: Boolean = true,
    /** true 면 `inmcessentials.items.shulker.edit` 이 있어야 내용을 고친다(없으면 보기만) — CMI RequirePermission. */
    val requireEditPermission: Boolean = false,
    /** 열 때마다 드는 돈(0 = 없음, 면제 `…shulker.free`) — CMI CostToOpen. */
    val cost: Double = 0.0,
    val currency: String = "",
    /** 연 채로 움직이면 닫는다(그리고 달리기·활공·탈것 중에는 못 연다). */
    val preventWhileMoving: Boolean = false,
    val moveTolerance: Double = 1.5,
    /** 커스텀 아이템(inmc·MMOItems·ItemsAdder …)인 셜커도 배낭으로 연다 — 기본은 그 플러그인의 능력과 다투지 않게 건드리지 않는다. */
    val allowCustomItems: Boolean = false,
)

data class ClearSettings(
    /** 아무 조건 없이 전부 비울 때 한 번 더 누르게(CMI Clear.Confirmation). */
    val confirm: Boolean = true,
    /** 이 PDC 열쇠(`네임스페이스:이름`)가 붙은 아이템은 지우지 않는다. */
    val excludeKeys: List<String> = emptyList(),
    /** 아이템 custom_data 안에 이 경로가 있으면 지우지 않는다(CMI Clear.Exclude 의 `NBT:<경로>` — 점은 한 겹 아래). */
    val excludeNbt: List<String> = emptyList(),
) {
    companion object {
        /** `NBT:<경로>` → [excludeNbt], `PDC:<열쇠>`·`<네임스페이스>:<이름>` → [excludeKeys](순수). */
        fun of(confirm: Boolean, entries: List<String>): ClearSettings {
            val keys = ArrayList<String>()
            val nbt = ArrayList<String>()
            for (raw in entries.map { it.trim() }.filter { it.isNotEmpty() }) {
                when {
                    raw.startsWith("nbt:", ignoreCase = true) -> raw.substring(4).trim().takeIf { it.isNotEmpty() }?.let { nbt += it }
                    raw.startsWith("pdc:", ignoreCase = true) -> raw.substring(4).trim().lowercase().takeIf { it.contains(':') }?.let { keys += it }
                    raw.contains(':') -> keys += raw.lowercase()
                }
            }
            return ClearSettings(confirm, keys.distinct(), nbt.distinct())
        }
    }
}

data class DyeSettings(
    /** 움직이는 색은 염색받은 사람이 입었을 때만 바뀐다(CMI Dye.BoundToPlayer). */
    val boundToPlayer: Boolean = false,
    /** 움직이는 색을 다시 칠하는 주기(초). 입은 갑옷만 본다. */
    val periodSeconds: Int = 1,
    /** 정의에 색이 있는 inmc 커스텀 아이템에도 움직이는 색을 허용(다시 그릴 때 색이 정의로 돌아간다). */
    val allowDynamicOnColoredCustom: Boolean = false,
)

data class SearchSettings(
    val maxResults: Int = 2000,
    /** `/검색 지우기` 를 쓸 수 있게(CMI search.EnablePurge). */
    val enablePurge: Boolean = false,
    /** 결과를 `search/search-<시각>.txt` 로 남긴다(CMI search.LogIntoFile). */
    val logIntoFile: Boolean = true,
    val pageSize: Int = 10,
)
