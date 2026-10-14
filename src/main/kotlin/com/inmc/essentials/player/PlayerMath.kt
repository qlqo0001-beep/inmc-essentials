package com.inmc.essentials.player

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.roundToLong

/*
 * player 기능의 순수 계산 — 서버 없이 시험한다(PlayerMathTest). Bukkit 타입을 받지 않는다.
 */

/** 명령 꼬리의 낱말·깃발. `-s`·`-nofeed` 같은 것은 [flags](소문자), `-p:이름` 은 [options]. 나머지는 [words] 순서대로. */
data class Tail(val words: List<String>, val flags: Set<String>, val options: Map<String, String>) {

    val silent: Boolean get() = "-s" in flags

    fun has(flag: String): Boolean = flag.lowercase() in flags

    fun word(index: Int): String? = words.getOrNull(index)

    /** 앞에 [first] 를 낱말로 끼운 것(사람 이름 자리에 온 것이 사람이 아니었을 때). */
    fun prepend(first: String): Tail = parse(first).let { head -> Tail(head.words + words, head.flags + flags, head.options + options) }

    companion object {
        val EMPTY = Tail(emptyList(), emptySet(), emptyMap())

        fun parse(text: String?): Tail {
            if (text.isNullOrBlank()) return EMPTY
            val words = ArrayList<String>()
            val flags = LinkedHashSet<String>()
            val options = LinkedHashMap<String, String>()
            for (token in text.trim().split(Regex("\\s+"))) {
                if (isFlag(token)) {
                    val colon = token.indexOf(':')
                    if (colon > 1) options[token.substring(1, colon).lowercase()] = token.substring(colon + 1)
                    else flags += token.lowercase()
                } else if (token.isNotEmpty()) {
                    words += token
                }
            }
            return Tail(words, flags, options)
        }

        /** `-s`·`-nofeed`·`-p:이름` 모양(숫자 `-10` 은 깃발이 아니다). */
        fun isFlag(token: String): Boolean = token.length > 1 && token.startsWith("-") && token[1].isLetter()
    }
}

/** 켜기/끄기 낱말. 모르면 null. */
object Toggle {
    private val ON = setOf("on", "true", "켜기", "켬", "1", "yes")
    private val OFF = setOf("off", "false", "끄기", "끔", "0", "no")
    val WORDS = listOf("켜기", "끄기")

    fun parse(word: String?): Boolean? = when (word?.lowercase()) {
        null -> null
        in ON -> true
        in OFF -> false
        else -> null
    }
}

/**
 * 시간 인자 — `30`(초) · `30s` · `5m` · `1h30m` · `1d` · 한글 `30분`·`1시간30분`·`2일`·`10초`. 앞의 `+`/`-` 는 더하기/빼기.
 * CMI tfly 의 `+30m` 을 그대로 받는다.
 */
object TimeArg {

    /** [sign]: 0 = 그 값으로, 1 = 더하기, -1 = 빼기. */
    data class Parsed(val seconds: Long, val sign: Int)

    private val UNIT = Regex("""(\d+)([dhms])""")

    fun parse(raw: String?): Parsed? {
        if (raw.isNullOrBlank()) return null
        var text = raw.trim().lowercase()
        val sign = when {
            text.startsWith("+") -> 1
            text.startsWith("-") -> -1
            else -> 0
        }
        if (sign != 0) text = text.substring(1)
        text = text.replace("시간", "h").replace("일", "d").replace("분", "m").replace("초", "s").replace(" ", "")
        if (text.isEmpty()) return null
        text.toLongOrNull()?.let { return if (it < 0) null else Parsed(it, sign) }
        var total = 0L
        var consumed = 0
        for (m in UNIT.findAll(text)) {
            if (m.range.first != consumed) return null
            val value = m.groupValues[1].toLongOrNull() ?: return null
            total += when (m.groupValues[2]) {
                "d" -> value * 86400L
                "h" -> value * 3600L
                "m" -> value * 60L
                else -> value
            }
            consumed = m.range.last + 1
        }
        return if (consumed == text.length && consumed > 0) Parsed(total, sign) else null
    }
}

/**
 * `/시간제무적`·`/시간제비행` 의 값 계산. [PlayerRecord.UNTIL_RELOG] = 다시 접속할 때까지.
 */
object TimedMath {

    /** 지금 끝내기 낱말(`0` 은 CMI 처럼 "다시 접속할 때까지"). */
    val END_WORDS = setOf("끄기", "끝", "off", "end")

    /** 첫 자리 낱말이 사람이 아니라 값인가 — 깃발 · 끝내기 낱말 · 시간(`30`·`5m`·`+10m`·`-5m`·`0`). */
    fun looksLikeValue(word: String): Boolean = Tail.isFlag(word) || word.lowercase() in END_WORDS || TimeArg.parse(word) != null

    sealed class Next {
        /** 이 값으로 바꾼다(null = 끝). */
        data class To(val left: Long?) : Next()

        /** 없는 것을 끝내거나 줄이려 했다 — 아무것도 바꾸지 않는다. */
        data object NotSet : Next()

        /** "다시 접속할 때까지" 에 더하기·빼기 — 시간이 없어 계산할 수 없다. 바꾸지 않는다. */
        data object UntilRelog : Next()
    }

    /** [left] = 지금 남은 초(null = 없음). [end] = 끝내기 낱말. */
    fun next(left: Long?, time: TimeArg.Parsed, end: Boolean): Next = when {
        end -> if (left == null) Next.NotSet else Next.To(null)
        time.sign == 0 && time.seconds == 0L -> Next.To(PlayerRecord.UNTIL_RELOG)
        time.sign == 0 -> Next.To(time.seconds)
        left == PlayerRecord.UNTIL_RELOG -> Next.UntilRelog
        left == null && time.sign < 0 -> Next.NotSet
        else -> Next.To(((left ?: 0L) + time.sign * time.seconds).takeIf { it > 0 })
    }

    /**
     * 공용 DB 에서 서버를 옮긴 직후 다시 읽은 저장값([stored])과 이번 접속의 값([current])을 맞춘다 — 옛 서버의 늦은 나감 쓰기가 더 적은
     * 남은 초를 적었으면 그것(작은 쪽)을 쓴다. 이번 접속에서 끝났거나(null) "다시 접속할 때까지" 면 그대로.
     */
    fun reconcile(current: Long?, stored: Long?): Long? = when {
        current == null || current == PlayerRecord.UNTIL_RELOG -> current
        stored == null || stored <= 0 -> current
        else -> minOf(current, stored)
    }
}

/** `/타기` 종류 판단 — 막은 종류는 아무도, 아니면 `.ride.all` 또는 `.ride.<종류>`. */
object RideRule {
    fun allowed(type: String, blacklist: Set<String>, hasAll: Boolean, hasType: Boolean): Boolean = type !in blacklist && (hasAll || hasType)
}

/** 비행 유지 판단(서버 없이 시험). */
object FlightRule {
    /** 게임모드를 바꾼 뒤 비행을 다시 켜나 — 설정이 켜져 있고 서바이벌·어드벤처로 가고 `/비행`·`/시간제비행` 으로 켠 사람. */
    fun restoreOnGameMode(keepSetting: Boolean, survivalLike: Boolean, wantsFlight: Boolean): Boolean = keepSetting && survivalLike && wantsFlight

    /** 다시 살아난 뒤 비행을 돌려주나 — 죽기 전에 날 수 있었고 설정이 켜져 있었거나(그때 기록), `/비행`·`/시간제비행` 으로 켠 사람. */
    fun restoreOnRespawn(flewBeforeDeath: Boolean, wantsFlight: Boolean): Boolean = flewBeforeDeath || wantsFlight
}

/** 바닐라 경험치 공식(1.8 이후 그대로). */
object ExpMath {

    enum class Op { ADD, SET, TAKE, CLEAR }

    /** [level] 에서 다음 레벨까지 드는 경험치. */
    fun needed(level: Int): Int = when {
        level >= 30 -> 112 + (level - 30) * 9
        level >= 15 -> 37 + (level - 15) * 5
        else -> 7 + level * 2
    }

    /** [level] 에 이르는 데까지 쌓인 경험치. */
    fun totalAt(level: Int): Long {
        val l = level.toDouble()
        return when {
            level <= 16 -> (l * l + 6 * l).toLong()
            level <= 31 -> (2.5 * l * l - 40.5 * l + 360).roundToLong()
            else -> (4.5 * l * l - 162.5 * l + 2220).roundToLong()
        }
    }

    fun total(level: Int, progress: Float): Long = totalAt(level) + (progress.coerceIn(0f, 1f) * needed(level)).roundToLong()

    /** 총 경험치 → (레벨, 진행도 0..1). */
    fun fromTotal(total: Long): Pair<Int, Float> {
        var left = total.coerceAtLeast(0)
        var level = 0
        while (level < MAX_LEVEL) {
            val need = needed(level)
            if (left < need) break
            left -= need
            level++
        }
        return level to (left.toFloat() / needed(level)).coerceIn(0f, 0.9999f)
    }

    /** 경험치 양 — [levels] 면 레벨 단위. */
    data class Amount(val value: Long, val levels: Boolean)

    private val PLAIN = Regex("""^(\d+)(l?)$""")
    private val RANGE = Regex("""^(\d+)-(\d+)(l?)$""")
    private val PERCENT = Regex("""^(\d+(?:\.\d+)?)(?:-(\d+(?:\.\d+)?))?%(l?)(?:\[(\d+)-(\d+)])?$""")

    /**
     * CMI `/exp` 의 양 — `10`(경험치) · `10L`(레벨) · `10-50`(그 사이 무작위) · `10%`(지금 총 경험치의 10%) · `10%L`(지금 레벨의 10%) ·
     * `10-20%`(무작위 비율) · `10%[5-50]`(비율이되 5~50 사이로). [random] 은 (최소, 최대) → 그 사이 값.
     */
    fun parseAmount(raw: String, currentTotal: Long, currentLevel: Int, random: (Long, Long) -> Long): Amount? {
        val text = raw.trim().lowercase()
        PLAIN.matchEntire(text)?.let { m -> return Amount(m.groupValues[1].toLongOrNull() ?: return null, m.groupValues[2] == "l") }
        RANGE.matchEntire(text)?.let { m ->
            val a = m.groupValues[1].toLongOrNull() ?: return null
            val b = m.groupValues[2].toLongOrNull() ?: return null
            return Amount(random(minOf(a, b), maxOf(a, b)), m.groupValues[3] == "l")
        }
        PERCENT.matchEntire(text)?.let { m ->
            val low = m.groupValues[1].toDoubleOrNull() ?: return null
            val high = m.groupValues[2].takeIf { it.isNotEmpty() }?.toDoubleOrNull()
            val levels = m.groupValues[3] == "l"
            val percent = if (high == null) low else random((minOf(low, high) * 100).toLong(), (maxOf(low, high) * 100).toLong()) / 100.0
            val base = if (levels) currentLevel.toDouble() else currentTotal.toDouble()
            var value = floor(base * percent / 100.0).toLong()
            val min = m.groupValues[4].takeIf { it.isNotEmpty() }?.toLongOrNull()
            val max = m.groupValues[5].takeIf { it.isNotEmpty() }?.toLongOrNull()
            if (min != null && max != null) value = value.coerceIn(minOf(min, max), maxOf(min, max))
            return Amount(value.coerceAtLeast(0), levels)
        }
        return null
    }

    /** (레벨, 진행도)에 [op] 를 적용한 새 (레벨, 진행도). 레벨 단위 더하기·빼기는 진행도를 남긴다(설정은 0 으로). */
    fun apply(op: Op, amount: Amount?, level: Int, progress: Float): Pair<Int, Float> {
        if (op == Op.CLEAR || amount == null) return 0 to 0f
        val v = amount.value.coerceIn(0, Int.MAX_VALUE.toLong())
        if (amount.levels) {
            return when (op) {
                Op.SET -> v.coerceAtMost(MAX_LEVEL.toLong()).toInt() to 0f
                Op.ADD -> (level + v).coerceAtMost(MAX_LEVEL.toLong()).toInt() to progress
                Op.TAKE -> (level - v).coerceAtLeast(0).toInt() to progress
                Op.CLEAR -> 0 to 0f
            }
        }
        val total = total(level, progress)
        val next = when (op) {
            Op.SET -> v
            Op.ADD -> total + v
            Op.TAKE -> (total - v).coerceAtLeast(0)
            Op.CLEAR -> 0
        }
        return fromTotal(next)
    }

    /** 레벨 상한 — 이보다 크면 바닐라 총 경험치(Int)가 넘친다. */
    const val MAX_LEVEL = 21_863
}

/**
 * `/최대체력` — **MAX_HEALTH 의 기본값만** 바꾼다(research '# maxhp'). 장비·칭호·던전은 자기 수정자를 따로 달므로 기본값만 만지면
 * 아무와도 싸우지 않는다. CMI 는 최종값(장비 포함)에서 더하고 빼 장비 보너스가 기본값에 섞였다 — 그렇게 하지 않는다.
 */
object MaxHpMath {

    enum class Op { SET, ADD, TAKE, CLEAR }

    /** 수정자 묶음(조회 화면). */
    enum class Group { GEAR, SET, TITLE, DUNGEON, OTHER }

    /** 새 기본값. 범위는 1 ~ [cap]. CLEAR 는 속성의 기본값([default], 20). */
    fun newBase(op: Op, base: Double, amount: Double, default: Double, cap: Double): Double {
        val raw = when (op) {
            Op.SET -> amount
            Op.ADD -> base + amount
            Op.TAKE -> base - amount
            Op.CLEAR -> default
        }
        return raw.coerceIn(1.0, cap.coerceAtLeast(1.0))
    }

    /** 바꾼 뒤의 체력: 최대가 줄면 잘라 맞추고, 늘면 늘어난 만큼 채운다(새 최대를 넘지 않게) — CMI 와 같다. */
    fun healthAfter(health: Double, oldMax: Double, newMax: Double): Double =
        if (newMax < oldMax) health.coerceAtMost(newMax).coerceAtLeast(0.0)
        else (health + (newMax - oldMax)).coerceAtMost(newMax)

    /**
     * 수정자 열쇠 → 묶음. `inmc:*` = 커스텀아이템 장비, 그중 `inmc:set_*` = 세트·부적·장착 칸, `titleforge:*` = 칭호,
     * `inmcdungeon:*` = 던전 축복, 나머지 = 기타(ARCHITECTURE 의 수정자 네임스페이스).
     */
    fun group(namespace: String, key: String): Group = when (namespace) {
        "inmc" -> if (key.startsWith("set_")) Group.SET else Group.GEAR
        "titleforge" -> Group.TITLE
        "inmcdungeon" -> Group.DUNGEON
        else -> Group.OTHER
    }

    /** 소수 하나까지, 정수면 정수로. */
    fun format(value: Double): String =
        if (abs(value - value.roundToLong()) < 0.05) value.roundToLong().toString() else String.format(java.util.Locale.ROOT, "%.1f", value)
}

/** 걷기·비행 속도 — CMI 처럼 걷기 0~5(1 = 기본 0.2), 비행 0~10(1 = 기본 0.1). */
object SpeedMath {
    const val WALK_MAX = 5.0
    const val FLY_MAX = 10.0

    fun toBukkit(amount: Double, fly: Boolean): Float =
        (amount * (if (fly) 0.1 else 0.2)).coerceIn(0.0, 1.0).toFloat()

    fun fromBukkit(value: Float, fly: Boolean): Double = value / (if (fly) 0.1 else 0.2)

    /** 권한으로 받은 상한들 중 가장 큰 것(없으면 [absolute]). 둘 다 [absolute] 를 넘지 않는다. */
    fun cap(granted: Collection<Double>, absolute: Double): Double = (granted.maxOrNull() ?: absolute).coerceAtMost(absolute)
}

/** `/근처` — 방향과 대략 거리. */
object NearMath {

    /** 0 = 북(-z), 1 = 북동, 2 = 동(+x) … 7 = 북서. */
    fun direction(dx: Double, dz: Double): Int {
        val angle = Math.toDegrees(atan2(dx, -dz)).let { if (it < 0) it + 360 else it }
        return (((angle + 22.5) / 45.0).toInt()) % 8
    }

    /** 가까우면 10 단위, 멀면 50·100 단위로 반올림(정확한 자리를 숨긴다). 최소 10. */
    fun approximate(distance: Double): Long {
        val step = when {
            distance < 100 -> 10L
            distance < 500 -> 50L
            else -> 100L
        }
        return ((distance / step).roundToLong() * step).coerceAtLeast(10)
    }

    /**
     * 목록·가리키기에 넣나 — 관전 모드는 빼고, 투명 물약을 먹은 사람은 `invisibility.hide` 면 뺀다(`obfuscate` 도 켜면 목록에는 가린 이름으로 남지만
     * 가리키기는 안 된다 — [forPoint]).
     */
    fun listed(spectator: Boolean, invisible: Boolean, hide: Boolean, obfuscate: Boolean, forPoint: Boolean): Boolean = when {
        spectator -> false
        invisible && hide -> !forPoint && obfuscate
        else -> true
    }
}

/** `/모자` 로 머리에 쓸 수 있나(CMI Optimizations.Hat). 재질 판단(블록인지 등)은 부르는 쪽이 넘긴다 — 서버 없이 시험하려고. */
object HatRule {

    data class Item(
        val material: String,
        val headwear: Boolean,
        val block: Boolean,
        val mobHead: Boolean,
        val hasLore: Boolean,
        val enchanted: Boolean,
    )

    enum class Verdict { OK, NOT_WHITELISTED, LORE, NOT_HAT, ENCHANTED }

    fun check(item: Item, config: PlayerConfig): Verdict {
        if (config.hatWhitelist.isNotEmpty() && item.material.lowercase() !in config.hatWhitelist) return Verdict.NOT_WHITELISTED
        if (item.headwear) return Verdict.OK
        if (config.hatBlockWithLore && item.hasLore) return Verdict.LORE
        val hatType = item.block || item.mobHead
        if (config.hatBlockNoneHat && !hatType) return Verdict.NOT_HAT
        if (config.hatBlockNoneHatEnchanted && !hatType && item.enchanted) return Verdict.ENCHANTED
        return Verdict.OK
    }

    /**
     * 쓰던 모자를 벗겨도 되나 — 귀속 저주(Curse of Binding)가 붙은 것은 창작 모드가 아니면 바닐라처럼 못 벗긴다
     * (CMI `hat.info.incorrect` "Incorrect item in head slot, cant replace with new one").
     */
    fun canReplace(oldBound: Boolean, creative: Boolean): Boolean = !oldBound || creative
}

/** 나가 있던 동안의 계산(효과 시간 줄이기). */
object OfflineMath {
    /** 나간 시각을 믿을 수 있나 — 지난 접속이 크래시로 끝나면 나간 시각이 그 접속보다 앞(또는 없음)이다. */
    fun knowsQuit(lastJoin: Long, lastQuit: Long?): Boolean = lastQuit != null && lastQuit >= lastJoin
}

/** 효과 이름 — CMI·옛 Bukkit 이름을 지금 열쇠로. */
object EffectNames {
    private val LEGACY = mapOf(
        "nightvision" to "night_vision", "night-vision" to "night_vision",
        "slow" to "slowness", "fast_digging" to "haste", "slow_digging" to "mining_fatigue",
        "increase_damage" to "strength", "heal" to "instant_health", "harm" to "instant_damage",
        "jump" to "jump_boost", "confusion" to "nausea", "damage_resistance" to "resistance",
        "waterbreathing" to "water_breathing", "fireresistance" to "fire_resistance",
    )

    fun key(raw: String): String {
        val text = raw.trim().lowercase().removePrefix("minecraft:")
        return LEGACY[text] ?: text
    }
}
