package com.inmc.essentials.commands

/**
 * 같은(비슷한) 명령 반복 막기(순수) — CMI `Command.CommandFilter.Duplicate`. [intervalMs] 안에 [similarity]% 이상 닮은 명령을 이미
 * [maxRepeats] 번 쳤으면 막는다. 막힌 것은 기록하지 않는다(막힌 채로 계속 치면 창이 늘어나지 않게).
 */
class SpamWindow(private val intervalMs: Long, private val similarity: Int, private val maxRepeats: Int) {

    private val entries = ArrayDeque<Pair<Long, String>>()

    /** 막아야 하면 true. 아니면 기록하고 false. */
    fun check(text: String, now: Long): Boolean {
        while (entries.isNotEmpty() && now - entries.first().first > intervalMs) entries.removeFirst()
        val normalized = text.trim().lowercase()
        val same = entries.count { Similarity.percent(it.second, normalized) >= similarity }
        if (same >= maxRepeats) return true
        entries.addLast(now to normalized)
        while (entries.size > 32) entries.removeFirst()
        return false
    }
}

/** 쿨타임 셈(순수). */
object CooldownMath {

    /** 남은 밀리초. 쓸 수 있으면 null. 평생 한 번([cooldownSeconds] < 0)을 이미 썼으면 [Long.MAX_VALUE]. */
    fun remaining(lastUsed: Long?, now: Long, cooldownSeconds: Long): Long? {
        if (lastUsed == null || cooldownSeconds == 0L) return null
        if (cooldownSeconds < 0) return Long.MAX_VALUE
        val left = lastUsed + cooldownSeconds * 1000 - now
        return left.takeIf { it > 0 }
    }
}
