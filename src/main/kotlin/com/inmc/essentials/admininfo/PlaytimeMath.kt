package com.inmc.essentials.admininfo

import java.time.DayOfWeek
import java.time.LocalDate

/** 플레이 시간 계산 중 서버 없이 시험할 수 있는 것(틱 = 1/20초 — 바닐라 통계 `PLAY_ONE_MINUTE` 의 단위). */
object PlaytimeMath {

    const val TICKS_PER_SECOND = 20L

    /** 편집 종류. 저장은 [word](순서값 금지). */
    enum class Op(val word: String) {
        ADD("add"), TAKE("take"), SET("set");

        companion object {
            fun parse(raw: String): Op? = when (raw.trim().lowercase()) {
                "더하기", "추가", "add", "give", "+" -> ADD
                "빼기", "take", "remove", "-" -> TAKE
                "설정", "set", "=" -> SET
                else -> null
            }
        }
    }

    /** 접속하지 않은 사람에게 걸어 둔 편집 — 다음 접속 때 바닐라 통계에 적용. [set] 이면 [ticks] 로 정하고, 아니면 [ticks] 를 더한다(음수 가능). */
    data class PendingEdit(val set: Boolean, val ticks: Long) {

        fun applyTo(current: Long): Long = (if (set) ticks else current + ticks).coerceIn(0L, Int.MAX_VALUE.toLong())

        companion object {
            fun of(op: Op, ticks: Long): PendingEdit = when (op) {
                Op.ADD -> PendingEdit(false, ticks)
                Op.TAKE -> PendingEdit(false, -ticks)
                Op.SET -> PendingEdit(true, ticks)
            }
        }
    }

    /**
     * 걸려 있던 편집 [old] 뒤에 [next] 를 이어 붙인다. 설정 뒤의 더하기는 "설정(값 + 더한 양)", 무엇 뒤든 설정은 그 설정,
     * 더하기 뒤의 더하기는 합.
     */
    fun compose(old: PendingEdit?, next: PendingEdit): PendingEdit = when {
        old == null || next.set -> next
        old.set -> PendingEdit(true, (old.ticks + next.ticks).coerceAtLeast(0L))
        else -> PendingEdit(false, old.ticks + next.ticks)
    }

    /**
     * 시간 양 읽기: `1d 2h 30m 10s`, `1일 2시간 30분 10초`, `90m`, 그냥 숫자는 **분**(관리자가 "60" 이라 치면 한 시간이 아니라 60분으로 —
     * CMI editplaytime 도 분이 아니라 초였지만 플레이 시간에 초 단위 편집은 드물어 분으로 받는다 — 문서에 적음). 못 읽으면 null.
     */
    fun parseAmountSeconds(raw: String): Long? {
        val text = raw.trim().lowercase()
        if (text.isEmpty()) return null
        text.toLongOrNull()?.let { return if (it < 0) null else it * 60 }
        val units = mapOf("d" to 86400L, "일" to 86400L, "h" to 3600L, "시간" to 3600L, "m" to 60L, "분" to 60L, "s" to 1L, "초" to 1L)
        var total = 0L
        var matched = false
        for (m in Regex("""(\d+)\s*(시간|일|분|초|[dhms])""").findAll(text)) {
            total += m.groupValues[1].toLong() * (units[m.groupValues[2]] ?: return null)
            matched = true
        }
        // 숫자·단위 말고 다른 글자가 섞였으면 거절("1h 뭐").
        if (!matched || text.replace(Regex("""(\d+)\s*(시간|일|분|초|[dhms])"""), "").isNotBlank()) return null
        return total
    }

    /** `/상세플레이시간` 의 구간. 날짜는 서버 시간대의 하루(epochDay). */
    enum class Range(val label: String) {
        TODAY("오늘"), YESTERDAY("어제"), THIS_WEEK("이번 주"), WEEK("최근 7일"), THIS_MONTH("이번 달"), MONTH("최근 30일"),
        THIS_YEAR("올해"), YEAR("최근 365일"), ALL("기록 전체");

        /** [today] 기준 이 구간의 첫날과 끝날(둘 다 포함). [ALL] 은 아무 날이나. */
        fun span(today: LocalDate): LongRange {
            val t = today.toEpochDay()
            return when (this) {
                TODAY -> t..t
                YESTERDAY -> (t - 1)..(t - 1)
                THIS_WEEK -> today.with(DayOfWeek.MONDAY).toEpochDay()..t
                WEEK -> (t - 6)..t
                THIS_MONTH -> today.withDayOfMonth(1).toEpochDay()..t
                MONTH -> (t - 29)..t
                THIS_YEAR -> today.withDayOfYear(1).toEpochDay()..t
                YEAR -> (t - 364)..t
                ALL -> Long.MIN_VALUE..Long.MAX_VALUE
            }
        }
    }

    /** 하루별 틱([days] — epochDay → 틱)을 구간마다 합친다. */
    fun sums(days: Map<Long, Long>, today: LocalDate): Map<Range, Long> =
        Range.entries.associateWith { range -> val span = range.span(today); days.entries.sumOf { (d, t) -> if (d in span) t else 0L } }

    /**
     * 순위에서 뺄 사람인가 — 이름이 제외 목록(소문자)에 있거나, [offlineDays] 일 넘게 안 들어왔다(0 = 안 봄). [lastSeen] 은 마지막으로 본 때
     * (접속 중이면 지금), 모르면 null(빼지 않는다).
     */
    fun excluded(name: String, lastSeen: Long?, now: Long, exclude: Set<String>, offlineDays: Int): Boolean {
        if (name.lowercase() in exclude) return true
        if (offlineDays <= 0 || lastSeen == null) return false
        return now - lastSeen > offlineDays * 86_400_000L
    }
}
