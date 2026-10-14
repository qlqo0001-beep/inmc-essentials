package com.inmc.essentials.home

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID
import kotlin.math.floor

/**
 * 홈 하나(`ess_homes` 한 줄). 불변 — 바꿀 때는 copy 를 [HomeStore.put] 에 넘긴다. 월드는 이름으로(쓸 때 찾는다).
 *
 * @property slot 화면에서의 자리(1부터, 쪽을 넘어 이어진다 — CMI `/sethome 이름 <칸>`). null 이면 빈 칸에 차례로.
 * @property bed 침대를 눌러 만든 홈(CMI homeInteractions). [bedBlock] 은 그 침대의 머리 블록 `월드;x;y;z`.
 */
data class Home(
    val owner: UUID,
    val name: String,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val icon: String? = null,
    val private: Boolean = false,
    val slot: Int? = null,
    val bed: Boolean = false,
    val bedBlock: String? = null,
    val created: Long = 0L,
) {

    val key: String get() = keyOf(name)

    fun location(): Location? = Bukkit.getWorld(world)?.let { Location(it, x, y, z, yaw, pitch) }

    fun describe(): String = "$world ${x.toInt()}, ${y.toInt()}, ${z.toInt()}"

    companion object {
        /** 이름의 비교 열쇠 — 대소문자 무시(`집`·`Home`·`home` 은 같은 홈). */
        fun keyOf(name: String): String = name.trim().lowercase()

        fun at(owner: UUID, name: String, location: Location, now: Long): Home =
            Home(owner, name, location.world.name, location.x, location.y, location.z, location.yaw, location.pitch, created = now)

        fun blockKey(world: String, x: Int, y: Int, z: Int): String = "$world;$x;$y;$z"
    }
}

/** 침대 머리 블록 자리 — [Home.bedBlock] 의 `월드;x;y;z` 를 읽은 것([HomeRules.parseBlockKey]). */
data class BlockPos(val world: String, val x: Int, val y: Int, val z: Int)

/** 월드 묶음별 개수 제한 한 줄(CMI Homes.WorldLimits.Groups). [amount] 0 = 그 월드들에선 못 만든다. 음수 묶음은 읽을 때 뺀다. */
data class WorldGroup(val name: String, val amount: Int, val worlds: Set<String>)

/**
 * `settings/homes.yml` 스냅샷(규칙 12 — 이상한 값은 기본값, 던지지 않는다). 재질은 이름 그대로 들고 쓸 때 찾는다(시험이 서버 없이 읽게).
 */
data class HomeSettings(
    val defaultName: String = "집",
    val namePattern: Regex = DEFAULT_PATTERN,
    val nameMaxLength: Int = 16,
    val confirmOverwrite: Boolean = true,
    val defaultLimit: Int = 1,
    val groups: Map<String, Int> = mapOf("newbie" to 2, "advanced" to 3, "vip" to 5),
    val blockExtraHomes: Boolean = true,
    val notifyOnLogin: Boolean = true,
    val worldLimitsUse: Boolean = false,
    val worldGroups: List<WorldGroup> = emptyList(),
    val protectionCheck: Boolean = false,
    val bedEnabled: Boolean = true,
    val bedShiftOnly: Boolean = false,
    val bedRemoveOnBreak: Boolean = true,
    val bedName: String = "침대",
    val bedCountsTowardLimit: Boolean = false,
    val guiUse: Boolean = true,
    val maxSlot: Int = 99,
    val defaultIcon: String = "WHITE_BED",
    val bedIcon: String = "RED_BED",
) {
    companion object {
        val DEFAULT_PATTERN = Regex("[a-zA-Z0-9가-힣ㄱ-ㅎㅏ-ㅣ_-]+")

        fun from(y: YamlConfiguration): HomeSettings {
            val d = HomeSettings()
            val pattern = y.getString("name-pattern")?.let { runCatching { Regex(it) }.getOrNull() } ?: d.namePattern
            val groups = y.getConfigurationSection("limits.groups")?.let { sec ->
                sec.getKeys(false).associate { it.lowercase() to sec.getInt(it, 0).coerceAtLeast(0) }
            } ?: d.groups
            val worldGroups = y.getConfigurationSection("world-limits.groups")?.let { sec ->
                sec.getKeys(false).mapNotNull { name ->
                    val amount = sec.getInt("$name.amount", -1)
                    val worlds = sec.getStringList("$name.worlds").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    if (amount < 0 || worlds.isEmpty()) null else WorldGroup(name, amount, worlds)
                }
            }.orEmpty()
            return HomeSettings(
                defaultName = y.getString("default-name")?.trim()?.takeIf { it.isNotEmpty() } ?: d.defaultName,
                namePattern = pattern,
                nameMaxLength = y.getInt("name-max-length", d.nameMaxLength).coerceIn(1, 64),
                confirmOverwrite = y.getBoolean("confirm-overwrite", d.confirmOverwrite),
                defaultLimit = y.getInt("limits.default", d.defaultLimit).coerceAtLeast(0),
                groups = groups,
                blockExtraHomes = y.getBoolean("limits.block-extra-homes", d.blockExtraHomes),
                notifyOnLogin = y.getBoolean("limits.notify-on-login", d.notifyOnLogin),
                worldLimitsUse = y.getBoolean("world-limits.use", d.worldLimitsUse),
                worldGroups = worldGroups,
                protectionCheck = y.getBoolean("protection.check", d.protectionCheck),
                bedEnabled = y.getBoolean("bed.enabled", d.bedEnabled),
                bedShiftOnly = y.getBoolean("bed.shift-only", d.bedShiftOnly),
                bedRemoveOnBreak = y.getBoolean("bed.remove-on-break", d.bedRemoveOnBreak),
                bedName = y.getString("bed.name")?.trim()?.takeIf { it.isNotEmpty() } ?: d.bedName,
                bedCountsTowardLimit = y.getBoolean("bed.counts-toward-limit", d.bedCountsTowardLimit),
                guiUse = y.getBoolean("gui.use", d.guiUse),
                maxSlot = y.getInt("gui.max-slot", d.maxSlot).coerceIn(1, 990),
                defaultIcon = y.getString("gui.default-icon")?.trim()?.takeIf { it.isNotEmpty() } ?: d.defaultIcon,
                bedIcon = y.getString("gui.bed-icon")?.trim()?.takeIf { it.isNotEmpty() } ?: d.bedIcon,
            )
        }
    }
}

/** `/홈설정` 꼬리를 읽은 것(순수 — [HomeArgs.parseSet]). */
data class SetRequest(
    val name: String? = null,
    val private: Boolean = false,
    val overwrite: Boolean = false,
    val slot: Int? = null,
    val icon: String? = null,
    val owner: String? = null,
    val location: String? = null,
)

/** 홈의 순수 규칙 — 한도 · 이름 · 기본 홈 고르기 · 화면 자리(`HomeRulesTest`). */
object HomeRules {

    /** 한도 권한 접두(`inmcessentials.homes.limit.<그룹|숫자>`) — 노드를 글자로 짓는다(선언할 수 없다 — 그룹은 설정에서 온다). */
    const val LIMIT_PREFIX = "inmcessentials" + ".homes.limit."

    /**
     * CMI 시절 노드(넘어가는 동안 함께 인정한다 — LuckPerms 에 이미 준 것이 그대로 통하게). `…sethome.<그룹|숫자>` 는 한도,
     * `…unlimited` 는 무제한, `…bypasslimit` 은 월드 한도 무시, `…bypass` 는 보호구역 무시, `…customloc` 은 위치 지정.
     */
    const val LEGACY_PREFIX = "cmi.command.sethome."
    const val LEGACY_UNLIMITED = LEGACY_PREFIX + "unlimited"
    const val LEGACY_BYPASS_LIMIT = LEGACY_PREFIX + "bypasslimit"
    const val LEGACY_BYPASS_PROTECTION = LEGACY_PREFIX + "bypass"
    const val LEGACY_CUSTOM_LOCATION = LEGACY_PREFIX + "customloc"

    /**
     * 한도. [granted] = 그 사람이 **가진** 권한 노드(소문자). [unlimited] 면 null(무제한) — `…sethome.unlimited`(CMI)를 가져도.
     * 그룹·숫자 권한(우리 것·CMI 것)이 하나라도 있으면 그중 가장 큰 것, 없으면 기본값(CMI: 그룹이 없으면 1).
     */
    fun limit(granted: Collection<String>, unlimited: Boolean, settings: HomeSettings): Int? {
        if (unlimited || LEGACY_UNLIMITED in granted) return null
        var best: Int? = null
        for (node in granted) {
            val suffix = when {
                node.startsWith(LIMIT_PREFIX) -> node.removePrefix(LIMIT_PREFIX)
                node.startsWith(LEGACY_PREFIX) -> node.removePrefix(LEGACY_PREFIX)
                else -> continue
            }
            val value = suffix.toIntOrNull() ?: settings.groups[suffix] ?: continue
            if (value < 0) continue
            best = maxOf(best ?: value, value)
        }
        return best ?: settings.defaultLimit
    }

    /** 한도에 드는 홈 수 — 침대 홈은 설정에 따라 뺀다. */
    fun counted(homes: Collection<Home>, settings: HomeSettings): Int = homes.count { !it.bed || settings.bedCountsTowardLimit }

    /** [world] 가 든 월드 묶음(대소문자 무시). 없으면 null. */
    fun worldGroup(world: String, settings: HomeSettings): WorldGroup? =
        settings.worldGroups.firstOrNull { g -> g.worlds.any { it.equals(world, ignoreCase = true) } }

    /** 이름이 쓸 수 있는가 — 안 되면 까닭(메시지 키), 되면 null. */
    fun nameProblem(name: String, settings: HomeSettings): String? = when {
        name.isBlank() -> "homes-invalid-name"
        name.length > settings.nameMaxLength -> "homes-name-too-long"
        !settings.namePattern.matches(name) -> "homes-invalid-name"
        else -> null
    }

    /**
     * 가져온 이름(CMI)을 규칙에 맞춘다 — 맞으면 그대로, 아니면 못 쓰는 글자 묶음을 `_` 로 바꾸고 길이를 자르고, [taken] 이면 `_2`·`_3` 을 붙인다.
     * 빈칸·따옴표·`<` 가 든 이름은 명령(`/홈 이름`)으로 닿지 않고 채팅 클릭 문구를 깨뜨린다.
     */
    fun normalizeName(raw: String, settings: HomeSettings, taken: (String) -> Boolean): String {
        val trimmed = raw.trim()
        if (nameProblem(trimmed, settings) == null) return trimmed
        val replaced = trimmed.map { c -> if (settings.namePattern.matches(c.toString())) c else '_' }.joinToString("")
            .replace(Regex("_+"), "_").trim('_').ifEmpty { "home" }
        val base = replaced.take(settings.nameMaxLength)
        if (!taken(base)) return base
        var n = 2
        while (true) {
            val suffix = "_$n"
            val candidate = base.take((settings.nameMaxLength - suffix.length).coerceAtLeast(1)) + suffix
            if (!taken(candidate)) return candidate
            n++
        }
    }

    /** 침대 홈을 만들 수 있는 월드인가 — 오버월드만(네더·엔드의 침대는 터진다: 침대 캐기로 진짜 침대 홈을 잃지 않게). */
    fun bedWorks(environment: World.Environment): Boolean = environment == World.Environment.NORMAL

    /** [Home.blockKey] 를 되읽는다 — 모양이 틀리면 null. */
    fun parseBlockKey(raw: String?): BlockPos? {
        val parts = raw?.split(';') ?: return null
        if (parts.size != 4 || parts[0].isEmpty()) return null
        val x = parts[1].toIntOrNull() ?: return null
        val y = parts[2].toIntOrNull() ?: return null
        val z = parts[3].toIntOrNull() ?: return null
        return BlockPos(parts[0], x, y, z)
    }

    /**
     * CMI 가져오기 — 그 사람의 CMI 홈 중 CMI 기본 이름(`Settings/Homes.yml` `Homes.DefaultName`)의 홈을 우리 기본 이름([ourDefault])으로
     * 바꿀까. 바꿀 것의 이름 열쇠, 안 바꾸면 null. 두 이름이 같거나, 그 이름의 홈이 없거나, CMI 쪽에 이미 우리 기본 이름의 홈이 있으면 안 바꾼다.
     * CMI 자료만 보고 정한다 — 다시 가져와도 같은 이름이 나와 '이미 있음' 으로 건너뛴다.
     */
    fun defaultRename(cmiNames: Collection<String>, cmiDefault: String, ourDefault: String): String? {
        val from = Home.keyOf(cmiDefault)
        val to = Home.keyOf(ourDefault)
        if (from.isEmpty() || from == to) return null
        val keys = cmiNames.map(Home::keyOf).toSet()
        return from.takeIf { it in keys && to !in keys }
    }

    /** 같은 자리인가(블록 단위 — CMI 와 우리 좌표의 소수 차이는 무시). 가져오기에서 이미 있는 기본 홈이 같은 홈인지 가른다. */
    fun sameSpot(home: Home, world: String, x: Double, y: Double, z: Double): Boolean =
        home.world == world && floor(home.x) == floor(x) && floor(home.y) == floor(y) && floor(home.z) == floor(z)

    /** 기본 홈(부활 '기본 홈' 칸) — 기본 이름의 홈, 없으면 가장 먼저 만든 홈. 침대 홈은 뺀다(따로 칸이 있다). */
    fun pickDefault(homes: Collection<Home>, defaultName: String): Home? {
        val regular = homes.filter { !it.bed }
        return regular.firstOrNull { it.key == Home.keyOf(defaultName) } ?: regular.minByOrNull { it.created }
    }

    /** 목록 순서 — 침대 홈 먼저, 그다음 만든 순서. */
    fun sorted(homes: Collection<Home>): List<Home> = homes.sortedWith(compareBy<Home>({ !it.bed }, { it.created }, { it.key }))

    /**
     * 화면 자리 고르기 — [fixed] 가 자리(1부터)를 정한 것은 그 자리에(먼저 온 것이 이긴다), 나머지는 남은 칸을 앞에서부터.
     * 결과는 0부터 이어지는 절대 칸 번호 → 항목. 쪽은 부르는 쪽이 `칸 / 쪽당 칸` 으로 나눈다.
     */
    fun <T> arrange(items: List<T>, fixed: (T) -> Int?): Map<Int, T> {
        val out = LinkedHashMap<Int, T>()
        val rest = ArrayList<T>()
        for (item in items) {
            val slot = fixed(item)?.minus(1)
            if (slot != null && slot >= 0 && slot !in out) out[slot] = item else rest += item
        }
        var cursor = 0
        for (item in rest) {
            while (cursor in out) cursor++
            out[cursor] = item
        }
        return out
    }
}

/** 명령 꼬리 읽기(순수 — `HomeRulesTest`). */
object HomeArgs {

    private val PRIVATE = setOf("-p", "비공개", "-private")
    private val OVERWRITE = setOf("-overwrite", "-override", "덮어쓰기", "-o")
    private val OWNER = listOf("-player:", "주인:", "-p:")
    private val LOCATION = listOf("-l:", "위치:")

    /**
     * `/홈설정 [이름] [-p|비공개] [칸] [재질] [덮어쓰기] [주인:이름] [위치:월드;x;y;z]` — 순서는 자유. 첫 낱말(표시가 아닌 것)이 이름,
     * 숫자는 칸, 두 번째 낱말은 아이콘 재질(쓸 때 찾는다).
     */
    fun parseSet(tokens: List<String>): SetRequest {
        var r = SetRequest()
        for (raw in tokens) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            val lower = t.lowercase()
            when {
                lower in PRIVATE -> r = r.copy(private = true)
                lower in OVERWRITE -> r = r.copy(overwrite = true)
                OWNER.any { lower.startsWith(it) } -> r = r.copy(owner = t.substringAfter(':').trim().ifEmpty { null })
                LOCATION.any { lower.startsWith(it) } -> r = r.copy(location = t.substringAfter(':').trim().ifEmpty { null })
                r.name != null && t.toIntOrNull() != null -> r = r.copy(slot = t.toInt())
                r.name == null -> r = r.copy(name = t)
                r.icon == null -> r = r.copy(icon = t)
            }
        }
        return r
    }

    /** `월드;x;y;z[;yaw;pitch]` → 위치 조각. 못 읽으면 null. */
    fun parseLocation(raw: String): List<String>? {
        val parts = raw.split(';').map { it.trim() }
        if (parts.size < 4 || parts[0].isEmpty()) return null
        if (parts.drop(1).any { it.toDoubleOrNull() == null }) return null
        return parts
    }

    /** `/홈 <이름> [주인] [보낼 사람]` · `주인:이름` 꼴도. 결과 = (이름, 주인 입력, 보낼 사람 입력). */
    fun parseGo(tokens: List<String>): Triple<String, String?, String?>? {
        val first = tokens.firstOrNull() ?: return null
        if (':' in first && tokens.size == 1) {
            val owner = first.substringBefore(':').trim()
            val name = first.substringAfter(':').trim()
            if (owner.isNotEmpty() && name.isNotEmpty()) return Triple(name, owner, null)
        }
        return Triple(first, tokens.getOrNull(1), tokens.getOrNull(2))
    }
}
