package com.inmc.essentials.warp

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID

/**
 * 워프 하나(`warps.yml` 의 목록 한 칸). 불변 — 바꿀 때는 copy 를 [WarpStore.put] 에 넘긴다.
 *
 * @property permission true 면 `inmcessentials.warps.warp.<이름>`(또는 `.all`) 이 있어야 쓴다(CMI requirePerm).
 * @property showLocked 권한이 없어도 화면에 잠긴 채로 보인다(CMI reqPermission 표시).
 * @property hidden 관리자만 본다.
 * @property iconItem 손에 든 아이템을 그대로 아이콘으로(`ItemStack.serializeAsBytes` 의 Base64). 없으면 [icon] 재질.
 * @property slot 전체 화면에서의 자리(1부터, 쪽을 넘어 이어진다).
 * @property extra 추가 위치 `월드;x;y;z;yaw;pitch` — 이동할 때 기본 위치와 함께 무작위로 고른다(CMI secondary locations).
 */
data class Warp(
    val name: String,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val group: String? = null,
    val permission: Boolean = false,
    val showLocked: Boolean = true,
    val hidden: Boolean = false,
    val icon: String? = null,
    val iconItem: String? = null,
    val displayName: String? = null,
    val description: List<String> = emptyList(),
    val autoLore: Boolean = true,
    val slot: Int? = null,
    val randomYaw: Boolean = false,
    val extra: List<String> = emptyList(),
    val creator: UUID? = null,
    val creatorName: String? = null,
    val created: Long = 0L,
) {
    val key: String get() = keyOf(name)

    fun location(): Location? = Bukkit.getWorld(world)?.let { Location(it, x, y, z, yaw, pitch) }

    /** 기본 위치 + 추가 위치 중 지금 월드가 열린 것. */
    fun locations(): List<Location> = listOfNotNull(location()) + extra.mapNotNull { WarpCodec.parseSpot(it) }

    fun describe(): String = "$world ${x.toInt()}, ${y.toInt()}, ${z.toInt()}"

    fun moved(location: Location): Warp =
        copy(world = location.world.name, x = location.x, y = location.y, z = location.z, yaw = location.yaw, pitch = location.pitch)

    companion object {
        fun keyOf(name: String): String = name.trim().lowercase()

        fun spot(location: Location): String =
            "${location.world.name};${location.x};${location.y};${location.z};${location.yaw};${location.pitch}"
    }
}

/** 파일 한 벌 — [rejected] 는 읽지 못한 원문(다음 저장에 그대로 되써 넣는다 — 지뢰 13). */
data class WarpFile(val warps: List<Warp> = emptyList(), val rejected: List<Map<*, *>> = emptyList())

/** `warps.yml` ↔ [Warp]. 키에 점이 든 이름(지뢰 1)을 피하려고 맵이 아니라 **목록**으로 적는다. 순수(`WarpRulesTest`). */
object WarpCodec {

    fun read(y: YamlConfiguration): WarpFile {
        val warps = ArrayList<Warp>()
        val rejected = ArrayList<Map<*, *>>()
        for (raw in y.getMapList("warps")) {
            val warp = fromMap(raw)
            if (warp == null || warps.any { it.key == warp.key }) rejected += raw else warps += warp
        }
        return WarpFile(warps, rejected)
    }

    fun write(file: WarpFile): YamlConfiguration = YamlConfiguration().also { y ->
        y.options().setHeader(HEADER.lines())
        y.set("warps", file.warps.map { toMap(it) } + file.rejected)
    }

    fun toMap(w: Warp): Map<String, Any?> = LinkedHashMap<String, Any?>().apply {
        put("name", w.name)
        put("world", w.world)
        put("x", w.x)
        put("y", w.y)
        put("z", w.z)
        put("yaw", w.yaw.toDouble())
        put("pitch", w.pitch.toDouble())
        w.group?.let { put("group", it) }
        put("permission", w.permission)
        put("show-locked", w.showLocked)
        put("hidden", w.hidden)
        w.icon?.let { put("icon", it) }
        w.iconItem?.let { put("icon-item", it) }
        w.displayName?.let { put("display-name", it) }
        if (w.description.isNotEmpty()) put("description", w.description)
        put("auto-lore", w.autoLore)
        w.slot?.let { put("slot", it) }
        put("random-yaw", w.randomYaw)
        if (w.extra.isNotEmpty()) put("extra", w.extra)
        w.creator?.let { put("creator", it.toString()) }
        w.creatorName?.let { put("creator-name", it) }
        put("created", w.created)
    }

    /** 못 읽으면 null(이름·월드·좌표가 없을 때). 그 밖의 이상한 값은 기본값으로. */
    fun fromMap(m: Map<*, *>): Warp? {
        val name = (m["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val world = (m["world"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val x = num(m["x"]) ?: return null
        val y = num(m["y"]) ?: return null
        val z = num(m["z"]) ?: return null
        return Warp(
            name = name, world = world, x = x, y = y, z = z,
            yaw = num(m["yaw"])?.toFloat() ?: 0f, pitch = num(m["pitch"])?.toFloat() ?: 0f,
            group = (m["group"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            permission = m["permission"] as? Boolean ?: false,
            showLocked = m["show-locked"] as? Boolean ?: true,
            hidden = m["hidden"] as? Boolean ?: false,
            icon = (m["icon"] as? String)?.takeIf { it.isNotBlank() },
            iconItem = (m["icon-item"] as? String)?.takeIf { it.isNotBlank() },
            displayName = (m["display-name"] as? String)?.takeIf { it.isNotBlank() },
            description = (m["description"] as? List<*>)?.map { it.toString() }.orEmpty(),
            autoLore = m["auto-lore"] as? Boolean ?: true,
            slot = num(m["slot"])?.toInt()?.takeIf { it > 0 },
            randomYaw = m["random-yaw"] as? Boolean ?: false,
            extra = (m["extra"] as? List<*>)?.map { it.toString() }.orEmpty(),
            creator = (m["creator"] as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() },
            creatorName = m["creator-name"] as? String,
            created = num(m["created"])?.toLong() ?: 0L,
        )
    }

    private fun num(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }

    /** `월드;x;y;z[;yaw;pitch]` → 위치(그 월드가 열려 있을 때). */
    fun parseSpot(raw: String): Location? {
        val parts = splitSpot(raw) ?: return null
        val world = Bukkit.getWorld(parts.first) ?: return null
        val n = parts.second
        return Location(world, n[0], n[1], n[2], n.getOrElse(3) { 0.0 }.toFloat(), n.getOrElse(4) { 0.0 }.toFloat())
    }

    /** 순수 — (월드, 숫자들). 못 읽으면 null. */
    fun splitSpot(raw: String): Pair<String, List<Double>>? {
        val parts = raw.split(';').map { it.trim() }
        if (parts.size < 4 || parts[0].isEmpty()) return null
        val numbers = parts.drop(1).map { it.toDoubleOrNull() ?: return null }
        return parts[0] to numbers
    }

    val HEADER = """
        inmc-essentials 워프. 게임 안 /워프편집 에서 고치는 것이 편합니다(고치면 이 파일을 다시 씁니다).
        손으로 고쳤으면 바로 /에센셜 리로드 — 바뀐 파일을 다시 읽습니다. 리로드 전에 게임 안에서 워프를 고치면 손으로 고친 것이 덮입니다.
        읽지 못한 칸은 지우지 않고 그대로 둡니다 — 서버 로그에 까닭이 남습니다.
    """.trimIndent()
}

/** `settings/warps.yml` 스냅샷(이상한 값은 기본값 — 규칙 12). */
data class WarpSettings(
    val gui: Boolean = true,
    val guiOnCreation: Boolean = true,
    val minLength: Int = 1,
    val maxLength: Int = 16,
    val namePattern: Regex = DEFAULT_PATTERN,
    val showCreator: Boolean = false,
    val requirePermissionDefault: Boolean = false,
    val defaultLimit: Int = -1,
    val defaultIcon: String = "ENDER_PEARL",
    val lockedIcon: String = "GRAY_DYE",
) {
    companion object {
        val DEFAULT_PATTERN = Regex("[a-zA-Z0-9가-힣ㄱ-ㅎㅏ-ㅣ_-]+")

        fun from(y: YamlConfiguration): WarpSettings {
            val d = WarpSettings()
            val min = y.getInt("name-min-length", d.minLength).coerceIn(1, 32)
            return WarpSettings(
                gui = y.getBoolean("gui", d.gui),
                guiOnCreation = y.getBoolean("gui-on-creation", d.guiOnCreation),
                minLength = min,
                maxLength = y.getInt("name-max-length", d.maxLength).coerceIn(min, 64),
                namePattern = y.getString("name-pattern")?.let { runCatching { Regex(it) }.getOrNull() } ?: d.namePattern,
                showCreator = y.getBoolean("show-creator", d.showCreator),
                requirePermissionDefault = y.getBoolean("require-permission", d.requirePermissionDefault),
                defaultLimit = y.getInt("default-limit", d.defaultLimit).coerceAtLeast(-1),
                defaultIcon = y.getString("default-icon")?.trim()?.takeIf { it.isNotEmpty() } ?: d.defaultIcon,
                lockedIcon = y.getString("locked-icon")?.trim()?.takeIf { it.isNotEmpty() } ?: d.lockedIcon,
            )
        }
    }
}

/** `/워프설정` 꼬리(순수 — [WarpRules.parseSet]). [permission]·[autoLore] 는 적었을 때만 값(안 적으면 기존·기본). */
data class WarpRequest(
    val name: String? = null,
    val permission: Boolean? = null,
    val autoLore: Boolean? = null,
    val iconFromHand: Boolean = false,
    val iconHead: String? = null,
    val icon: String? = null,
    val slot: Int? = null,
    val group: String? = null,
    val location: String? = null,
    val overwrite: Boolean = false,
)

/** 워프의 순수 규칙(`WarpRulesTest`). */
object WarpRules {

    /** 워프별 권한 접두 — 글자로 짓는 노드(이름마다라 선언할 수 없다. 선언 안 된 노드는 op 가 저절로 갖는다 — 지뢰 11, 여기서는 그것이 맞다). */
    const val WARP_PREFIX = "inmcessentials" + ".warps.warp."

    /** 만들 수 있는 워프 개수 권한 접두(`…limit.<숫자>`). */
    const val LIMIT_PREFIX = "inmcessentials" + ".warps.limit."

    fun nameProblem(name: String, s: WarpSettings): String? = when {
        name.length < s.minLength || name.length > s.maxLength -> "warps-name-length"
        !s.namePattern.matches(name) -> "warps-invalid-name"
        else -> null
    }

    /**
     * 가져온 이름(CMI)을 규칙에 맞춘다 — 맞으면 그대로, 아니면 못 쓰는 글자 묶음을 `_` 로 바꾸고 길이를 맞추고, [taken] 이면 `_2`·`_3` 을 붙인다.
     * 빈칸·따옴표·`<` 가 든 이름은 `/워프 이름` 으로 닿지 않고 채팅 클릭 문구를 깨뜨린다.
     */
    fun normalizeName(raw: String, s: WarpSettings, taken: (String) -> Boolean): String {
        val trimmed = raw.trim()
        if (nameProblem(trimmed, s) == null) return trimmed
        var base = trimmed.map { c -> if (s.namePattern.matches(c.toString())) c else '_' }.joinToString("")
            .replace(Regex("_+"), "_").trim('_').ifEmpty { "warp" }.take(s.maxLength)
        while (base.length < s.minLength) base += "_"
        if (!taken(base)) return base
        var n = 2
        while (true) {
            val suffix = "_$n"
            val candidate = base.take((s.maxLength - suffix.length).coerceAtLeast(1)) + suffix
            if (!taken(candidate)) return candidate
            n++
        }
    }

    /** 만들 수 있는 개수(null = 무제한). 숫자 권한 중 큰 것, 없으면 설정의 기본(-1 = 무제한). */
    fun limit(granted: Collection<String>, s: WarpSettings): Int? {
        val best = granted.filter { it.startsWith(LIMIT_PREFIX) }.mapNotNull { it.removePrefix(LIMIT_PREFIX).toIntOrNull() }.filter { it >= 0 }.maxOrNull()
        val value = best ?: s.defaultLimit
        return if (value < 0) null else value
    }

    private val TRUE = setOf("true", "권한", "켬", "yes")
    private val FALSE = setOf("false", "끔", "no")
    private val HAND = setOf("hand", "손")
    private val OVERWRITE = setOf("-confirm", "-overwrite", "덮어쓰기")

    /**
     * `/워프설정 <이름> [권한 true/false] [손|머리|머리:이름|재질] [칸] [자동설명 true/false] [-g:그룹|그룹:이름] [월드;x;y;z;yaw;pitch] [덮어쓰기]`
     * — CMI `setwarp` 의 순서를 따르되 순서가 바뀌어도 읽는다. 첫 참/거짓은 권한, 둘째는 자동 설명.
     */
    fun parseSet(tokens: List<String>): WarpRequest {
        var r = WarpRequest()
        for (raw in tokens) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            val lower = t.lowercase()
            when {
                r.name == null -> r = r.copy(name = t)
                lower in OVERWRITE -> r = r.copy(overwrite = true)
                lower in TRUE || lower in FALSE -> {
                    val value = lower in TRUE
                    r = if (r.permission == null) r.copy(permission = value) else r.copy(autoLore = value)
                }
                lower in HAND -> r = r.copy(iconFromHand = true)
                lower == "head" || lower == "머리" -> r = r.copy(iconHead = "")
                lower.startsWith("head:") || lower.startsWith("머리:") -> r = r.copy(iconHead = t.substringAfter(':').trim())
                lower.startsWith("-g:") || lower.startsWith("그룹:") -> r = r.copy(group = t.substringAfter(':').trim().ifEmpty { null })
                ';' in t -> r = r.copy(location = t)
                t.toIntOrNull() != null -> r = r.copy(slot = t.toInt())
                else -> r = r.copy(icon = t)
            }
        }
        return r
    }

    /** 화면 자리(홈과 같은 규칙 — 정한 자리 먼저, 나머지는 빈 칸을 앞에서부터). 결과는 0부터 이어지는 절대 칸. */
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

    /** 그룹 이름들(대소문자 무시로 하나씩, 처음 본 표기) — 이름순. */
    fun groups(warps: Collection<Warp>): List<String> =
        warps.mapNotNull { it.group }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }

    /** 그룹 거르기 — null = 전체, "" = 그룹 없음. */
    fun inGroup(warp: Warp, group: String?): Boolean = when (group) {
        null -> true
        "" -> warp.group == null
        else -> warp.group.equals(group, ignoreCase = true)
    }
}
