package com.inmc.essentials.commands

/** 글 두 개가 얼마나 닮았나(순수) — 도배 막기(CMI `CommandFilter.Duplicate.Percentage`)와 비슷한 명령 추천(`SimilarCommandChecker`). */
object Similarity {

    /** 0~100. 편집 거리(레벤슈타인)를 긴 쪽 길이로 나눈 것의 나머지. 둘 다 비면 100. */
    fun percent(a: String, b: String): Int {
        if (a == b) return 100
        val longer = maxOf(a.length, b.length)
        if (longer == 0) return 100
        return ((longer - distance(a, b)) * 100.0 / longer).toInt()
    }

    fun distance(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }

    /** [candidates] 중 [typed] 와 가장 닮은 것([threshold]% 이상일 때만). 같은 점수면 짧은 것. */
    fun best(typed: String, candidates: Collection<String>, threshold: Int): String? {
        if (threshold <= 0 || typed.isEmpty()) return null
        var best: String? = null
        var score = -1
        for (c in candidates) {
            val p = percent(typed, c)
            if (p > score || (p == score && best != null && c.length < best.length)) {
                best = c
                score = p
            }
        }
        return best?.takeIf { score >= threshold && it != typed }
    }
}
