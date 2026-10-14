package com.inmc.essentials.world

/**
 * 가운데에서 바깥으로 도는 격자 커서(순수 — `WorldMathTest`). 고리 k 는 체비쇼프 거리 k 인 칸 전부이고, 고리 안에서는 가까운 칸부터.
 * 바이옴 찾기가 워커 스레드에서 이것을 한 칸씩 꺼낸다 — 멈췄다 이어 가도 같은 자리에서 다시 시작한다(설 자리를 못 찾아 이어 찾을 때).
 *
 * @param maxRing 마지막 고리(반경 / 간격).
 */
class Spiral(val maxRing: Int) {

    private var ring = 0
    private var points: LongArray = longArrayOf(pack(0, 0))
    private var index = 0

    /** 지금까지 꺼낸 칸 수. */
    var taken: Long = 0
        private set

    /** 전체 칸 수 — (2k+1)². */
    val total: Long get() = (2L * maxRing + 1).let { it * it }

    val currentRing: Int get() = ring

    fun hasNext(): Boolean = ring < maxRing || index < points.size

    /** 다음 칸 (dx, dz) — 간격 단위. 다 돌았으면 null. */
    fun next(): Pair<Int, Int>? {
        while (index >= points.size) {
            if (ring >= maxRing) return null
            ring++
            points = ringPoints(ring)
            index = 0
        }
        val p = points[index++]
        taken++
        return unpackX(p) to unpackZ(p)
    }

    companion object {

        /** 고리 [k] 의 칸들 — 유클리드 거리 순(같으면 각도 순으로 고정). */
        fun ringPoints(k: Int): LongArray {
            if (k == 0) return longArrayOf(pack(0, 0))
            val list = ArrayList<Long>(8 * k)
            for (x in -k..k) {
                list += pack(x, -k)
                list += pack(x, k)
            }
            for (z in -k + 1 until k) {
                list += pack(-k, z)
                list += pack(k, z)
            }
            list.sortWith(compareBy<Long>({ unpackX(it).toLong() * unpackX(it) + unpackZ(it).toLong() * unpackZ(it) }, { Math.atan2(unpackZ(it).toDouble(), unpackX(it).toDouble()) }))
            return list.toLongArray()
        }

        fun pack(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xffffffffL)

        fun unpackX(p: Long): Int = (p shr 32).toInt()

        fun unpackZ(p: Long): Int = p.toInt()
    }
}
