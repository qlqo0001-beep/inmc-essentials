package com.inmc.essentials.commands

import org.bukkit.configuration.ConfigurationSection
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

/**
 * 일정의 시각 하나(CMI `PerformOn` 의 한 항목, 순수). 빠진 칸: 월·일·요일·시 = 아무 때나, 분·초 = 0.
 * `Day` 는 날짜(1~31) 또는 요일 이름(영어·한국어), `FirstMonthDay`·`LastMonthDay` 는 "그 달 첫/마지막 ○요일".
 */
data class TimeFrame(
    val month: Int? = null,
    val day: Int? = null,
    val weekday: DayOfWeek? = null,
    val firstWeekday: DayOfWeek? = null,
    val lastWeekday: DayOfWeek? = null,
    val hour: Int? = null,
    val minute: Int = 0,
    val second: Int = 0,
) {
    fun matches(t: LocalDateTime): Boolean {
        if (month != null && t.monthValue != month) return false
        if (day != null && t.dayOfMonth != day) return false
        if (weekday != null && t.dayOfWeek != weekday) return false
        if (firstWeekday != null && t.toLocalDate() != t.toLocalDate().with(TemporalAdjusters.firstInMonth(firstWeekday))) return false
        if (lastWeekday != null && t.toLocalDate() != t.toLocalDate().with(TemporalAdjusters.lastInMonth(lastWeekday))) return false
        if (hour != null && t.hour != hour) return false
        return t.minute == minute && t.second == second
    }

    companion object {

        private val NAMES: Map<String, DayOfWeek> = buildMap {
            for (d in DayOfWeek.entries) {
                put(d.name.lowercase(), d)
                put(d.name.lowercase().take(3), d)
            }
            val ko = listOf("월", "화", "수", "목", "금", "토", "일")
            for ((i, k) in ko.withIndex()) {
                put(k, DayOfWeek.of(i + 1))
                put(k + "요일", DayOfWeek.of(i + 1))
            }
        }

        fun weekday(raw: Any?): DayOfWeek? = raw?.toString()?.trim()?.lowercase()?.let { NAMES[it] }

        /** 대소문자 무시로 키를 찾는다(CMI 는 `Hour`, 손으로 쓰면 `hour`). */
        fun from(map: Map<*, *>): TimeFrame? {
            fun get(key: String): Any? = map.entries.firstOrNull { it.key.toString().equals(key, ignoreCase = true) }?.value
            fun int(key: String): Int? = get(key)?.toString()?.trim()?.toIntOrNull()
            val dayRaw = get("Day")
            val dayNumber = dayRaw?.toString()?.trim()?.toIntOrNull()
            val frame = TimeFrame(
                month = int("Month")?.takeIf { it in 1..12 },
                day = dayNumber?.takeIf { it in 1..31 },
                weekday = if (dayNumber == null) weekday(dayRaw) else null,
                firstWeekday = weekday(get("FirstMonthDay")),
                lastWeekday = weekday(get("LastMonthDay")),
                hour = int("Hour")?.takeIf { it in 0..23 },
                minute = int("Minute")?.takeIf { it in 0..59 } ?: 0,
                second = int("Second")?.takeIf { it in 0..59 } ?: 0,
            )
            // 요일 이름을 못 읽었으면 버린다 — 아무 날이나 도는 것보다 낫다.
            if (dayRaw != null && dayNumber == null && frame.weekday == null) return null
            return frame
        }
    }
}

/** 일정 하나(CMI `Schedules.yml` 의 한 이름 — 같은 키). */
data class Schedule(
    val name: String,
    val enabled: Boolean,
    val delay: Long = 0,
    val repeat: Boolean = true,
    val frames: List<TimeFrame> = emptyList(),
    val minPlayers: Int = 0,
    val maxPlayers: Int = 0,
    val minPlayersCommands: List<String> = emptyList(),
    val feedback: Boolean = true,
    val randomize: Boolean = false,
    val duplicateRandomize: Boolean = false,
    val singleLinear: Boolean = false,
    val dontTranslate: Boolean = false,
    val commands: List<String> = emptyList(),
) {
    /** 시각이 정해진 일정인가(아니면 [delay] 초마다). */
    val timed: Boolean get() = frames.isNotEmpty()

    companion object {

        /** 파일 전체. 못 읽은 것은 [problem] 으로(조용히 버리지 않는다). */
        fun readAll(yaml: ConfigurationSection, problem: (String) -> Unit = {}): List<Schedule> =
            yaml.getKeys(false).mapNotNull { name -> yaml.getConfigurationSection(name)?.let { read(name, it, problem) } }

        fun read(name: String, s: ConfigurationSection, problem: (String) -> Unit = {}): Schedule? {
            fun key(vararg names: String): String = names.firstOrNull { s.contains(it) } ?: names.first()
            val frames = mutableListOf<TimeFrame>()
            val performOn = key("PerformOn", "perform-on")
            if (s.isList(performOn)) {
                for (entry in s.getMapList(performOn)) {
                    TimeFrame.from(entry)?.let { frames += it } ?: problem("일정 $name — 시각 $entry 을(를) 읽지 못했습니다")
                }
            } else {
                s.getConfigurationSection(performOn)?.let { section ->
                    for (k in section.getKeys(false)) {
                        val child = section.getConfigurationSection(k) ?: continue
                        TimeFrame.from(child.getValues(false))?.let { frames += it } ?: problem("일정 $name — 시각 $k 을(를) 읽지 못했습니다")
                    }
                }
            }
            val commands = s.getStringList(key("Commands", "commands"))
            val delay = s.getLong(key("Delay", "delay"), 0).coerceAtLeast(0)
            if (frames.isEmpty() && delay <= 0) {
                problem("일정 $name — Delay(초)도 PerformOn(시각)도 없습니다")
                return null
            }
            return Schedule(
                name = name,
                enabled = s.getBoolean(key("Enabled", "enabled"), false),
                delay = delay,
                repeat = s.getBoolean(key("Repeat", "repeat"), true),
                frames = frames,
                minPlayers = s.getInt(key("MinPlayers", "min-players"), 0).coerceAtLeast(0),
                maxPlayers = s.getInt(key("MaxPlayers", "max-players"), 0).coerceAtLeast(0),
                minPlayersCommands = s.getStringList(key("MinPlayersCommands", "min-players-commands")),
                feedback = s.getBoolean(key("FeedBack", "Feedback", "feedback"), true),
                randomize = s.getBoolean(key("Randomize", "randomize"), false),
                duplicateRandomize = s.getBoolean(key("DuplicateRandomize", "duplicate-randomize"), false),
                singleLinear = s.getBoolean(key("SingleLinear", "single-linear"), false),
                dontTranslate = s.getBoolean(key("DontTranslatePlaceholders", "dont-translate-placeholders"), false),
                commands = commands,
            )
        }
    }
}
