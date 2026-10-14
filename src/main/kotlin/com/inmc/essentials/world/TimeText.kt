package com.inmc.essentials.world

import java.time.LocalTime

/**
 * 게임 시간 글자 ↔ 틱(순수 — `WorldMathTest`). 0틱 = 오전 6시(마크 규칙), 1시간 = 1000틱.
 *
 * 받는 모양(CMI `/time` 과 같다): `13:00:00` · `13:00` · `1pm`·`1am` · `오후1`·`오전 7` · `13`(시) · `7000ticks`·`7000t`·`7000틱`. `24:00` 은 자정.
 */
object TimeText {

    const val TICKS_PER_DAY = 24_000L

    data class Parsed(val ticks: Long)

    private val CLOCK = Regex("""^(\d{1,2})(?::(\d{1,2}))?(?::(\d{1,2}))?$""")
    private val TICKS = Regex("""^(\d+)\s*(ticks|tick|t|틱)$""", RegexOption.IGNORE_CASE)
    private val AMPM = Regex("""^(\d{1,2})(?::(\d{1,2}))?\s*(am|pm)$""", RegexOption.IGNORE_CASE)
    private val KOREAN_AMPM = Regex("""^(오전|오후)\s*(\d{1,2})(?:(?::|시\s*)(\d{1,2})분?)?시?$""")
    private val KOREAN_CLOCK = Regex("""^(\d{1,2})시(?:\s*(\d{1,2})분)?$""")

    /** 시각 글자 → 그 날 안의 틱(0..23999). 못 읽으면 null. */
    fun parse(raw: String): Parsed? {
        val text = raw.trim().lowercase()
        if (text.isEmpty()) return null
        TICKS.matchEntire(text)?.let { m -> return m.groupValues[1].toLongOrNull()?.let { Parsed(Math.floorMod(it, TICKS_PER_DAY)) } }
        AMPM.matchEntire(text)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (h !in 1..12 || min !in 0..59) return null
            val hour = (h % 12) + if (m.groupValues[3] == "pm") 12 else 0
            return Parsed(clockToTicks(hour, min, 0))
        }
        KOREAN_AMPM.matchEntire(text)?.let { m ->
            val h = m.groupValues[2].toInt()
            val min = m.groupValues[3].ifEmpty { "0" }.toInt()
            if (h !in 0..12 || min !in 0..59) return null
            val hour = (h % 12) + if (m.groupValues[1] == "오후") 12 else 0
            return Parsed(clockToTicks(hour, min, 0))
        }
        KOREAN_CLOCK.matchEntire(text)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (h !in 0..24 || min !in 0..59) return null
            return Parsed(clockToTicks(h, min, 0))
        }
        CLOCK.matchEntire(text)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            val sec = m.groupValues[3].ifEmpty { "0" }.toInt()
            if (h !in 0..24 || min !in 0..59 || sec !in 0..59) return null
            return Parsed(clockToTicks(h, min, sec))
        }
        return null
    }

    /**
     * 더하기·빼기 양 → 틱. `0:30`(시:분) · `2`(시간) · `30m`·`1h30m`(게임 시간) · `500t`(틱) · `30분`·`1시간`.
     * 못 읽으면 null.
     */
    fun parseDelta(raw: String): Long? {
        val text = raw.trim().lowercase().replace(" ", "")
        if (text.isEmpty()) return null
        TICKS.matchEntire(text)?.let { return it.groupValues[1].toLongOrNull() }
        Regex("""^(\d+):(\d{1,2})$""").matchEntire(text)?.let { m ->
            val min = m.groupValues[2].toInt()
            if (min !in 0..59) return null
            return m.groupValues[1].toLong() * 1000 + min * 1000L / 60
        }
        text.toLongOrNull()?.let { return if (it >= 0) it * 1000 else null }
        var total = 0L
        var matched = false
        for (m in Regex("""(\d+)(h|m|s|시간|분|초)""").findAll(text)) {
            val n = m.groupValues[1].toLong()
            total += when (m.groupValues[2]) {
                "h", "시간" -> n * 1000
                "m", "분" -> n * 1000 / 60
                else -> n * 1000 / 3600
            }
            matched = true
        }
        return if (matched && Regex("""^((\d+)(h|m|s|시간|분|초))+$""").matches(text)) total else null
    }

    /** 시·분·초 → 틱. 24:00 은 0:00 과 같다. */
    fun clockToTicks(hour: Int, minute: Int, second: Int): Long {
        val dayTicks = (Math.floorMod(hour - 6, 24)) * 1000L + minute * 1000L / 60 + second * 1000L / 3600
        return Math.floorMod(dayTicks, TICKS_PER_DAY)
    }

    /** 현실 시각 → 틱. */
    fun ofReal(time: LocalTime): Long = clockToTicks(time.hour, time.minute, time.second)

    /** 틱 → (시, 분). */
    fun clock(ticks: Long): Pair<Int, Int> {
        val t = Math.floorMod(ticks, TICKS_PER_DAY)
        val hour = ((t / 1000) + 6) % 24
        val minute = (t % 1000) * 60 / 1000
        return hour.toInt() to minute.toInt()
    }

    fun format24(ticks: Long): String = clock(ticks).let { (h, m) -> "%02d:%02d".format(h, m) }

    /** `오후 1:05` 모양. */
    fun format12(ticks: Long): String = clock(ticks).let { (h, m) ->
        val half = if (h < 12) "오전" else "오후"
        val hh = (h % 12).let { if (it == 0) 12 else it }
        "$half $hh:%02d".format(m)
    }

    /** [from] 에서 [to] 까지 앞으로 가는 틱(0..23999) — 시간은 거꾸로 돌리지 않는다(달·작물이 꼬인다). */
    fun forward(from: Long, to: Long): Long = Math.floorMod(to - from, TICKS_PER_DAY)
}

/**
 * 날씨 지속 시간 글자 → 초(현실 시간). CMI `/sun 120` · `1h` · `10m` · `s/m/h/d/w/M/Y` + 한글 `30초`·`10분`·`1시간`·`2일`·`1주`.
 * 숫자만이면 초. 못 읽으면 null.
 */
object DurationText {

    private val PART = Regex("""(\d+)(s|m|h|d|w|M|Y|y|초|분|시간|일|주|달|년)""")

    fun parse(raw: String): Long? {
        val text = raw.trim().replace(" ", "")
        if (text.isEmpty()) return null
        text.toLongOrNull()?.let { return it.takeIf { n -> n > 0 } }
        if (!Regex("""^((\d+)(s|m|h|d|w|M|Y|y|초|분|시간|일|주|달|년))+$""").matches(text)) return null
        var total = 0L
        for (m in PART.findAll(text)) {
            val n = m.groupValues[1].toLong()
            total += n * when (m.groupValues[2]) {
                "s", "초" -> 1L
                "m", "분" -> 60L
                "h", "시간" -> 3600L
                "d", "일" -> 86_400L
                "w", "주" -> 604_800L
                "M", "달" -> 2_592_000L
                else -> 31_536_000L
            }
        }
        return total.takeIf { it > 0 }
    }
}
