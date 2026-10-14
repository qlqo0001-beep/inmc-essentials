package com.inmc.essentials.server

/**
 * 1초마다 (시각, 서버 틱 수) 를 적어 두고 창마다 TPS 를 센다(순수) — CMI `/cmi tps` 의 3·10·30·60·600초. 따로 매 틱 작업을 두지 않는다
 * (반복 작업은 하나 — 규칙 11): 1초 틱 두 번 사이에 서버 틱이 몇 번 돌았나를 본다.
 */
class TpsSampler(private val capacity: Int = 601) {

    private val times = LongArray(capacity)
    private val ticks = LongArray(capacity)
    private var head = 0
    private var size = 0

    @Synchronized
    fun record(nowMs: Long, tick: Long) {
        times[head] = nowMs
        ticks[head] = tick
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    private fun at(back: Int): Int = ((head - 1 - back) % capacity + capacity) % capacity

    /** 최근 [seconds] 초의 평균 TPS(20 이 상한). 기록이 모자라면 있는 만큼. 두 점이 안 되면 null. */
    @Synchronized
    fun tps(seconds: Int): Double? {
        if (size < 2) return null
        val back = seconds.coerceIn(1, size - 1)
        val newest = at(0)
        val oldest = at(back)
        val dt = (times[newest] - times[oldest]) / 1000.0
        if (dt <= 0) return null
        return ((ticks[newest] - ticks[oldest]) / dt).coerceAtMost(20.0)
    }

    /** 최근 [seconds] 초 동안 1초 구간마다의 TPS — (그 구간의 끝 시각, TPS). 오래된 것부터. */
    @Synchronized
    fun perSecond(seconds: Int): List<Pair<Long, Double>> {
        if (size < 2) return emptyList()
        val n = seconds.coerceIn(1, size - 1)
        val out = ArrayList<Pair<Long, Double>>(n)
        for (back in n - 1 downTo 0) {
            val a = at(back + 1)
            val b = at(back)
            val dt = (times[b] - times[a]) / 1000.0
            if (dt > 0) out += times[b] to ((ticks[b] - ticks[a]) / dt).coerceAtMost(20.0)
        }
        return out
    }
}
