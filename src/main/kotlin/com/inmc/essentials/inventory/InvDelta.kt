package com.inmc.essentials.inventory

import org.bukkit.inventory.ItemStack
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * 칸 목록의 변화 계산(순수 — 서버 없이 시험). 아이템 종류는 [T] 로 두고 같은 종류인가·개수·개수 바꾸기를 함수로 받는다.
 *
 * - [net]: 꺼낸 것과 넣은 것을 맞비긴다(사본 창 안에서 옮긴 것 — /인벤 · 조용히 열기의 되돌리기).
 * - [diff] · [apply]: 접속하지 않은 사람 편집을 접속 때 **칸별 변화로** 얹는다(그새 가방이 바뀌었을 때 — [OfflineEdits]).
 */
object InvDelta {

    /** 맞비긴 뒤 남은 (꺼낸 것, 넣은 것). 같은 종류끼리 개수를 서로 뺀다. */
    fun <T> net(
        taken: List<T>, added: List<T>,
        similar: (T, T) -> Boolean, amount: (T) -> Int, withAmount: (T, Int) -> T,
    ): Pair<List<T>, List<T>> {
        val takenLeft = IntArray(taken.size) { amount(taken[it]) }
        val addedLeft = IntArray(added.size) { amount(added[it]) }
        for (a in added.indices) {
            for (t in taken.indices) {
                if (addedLeft[a] <= 0) break
                if (takenLeft[t] <= 0 || !similar(added[a], taken[t])) continue
                val n = minOf(addedLeft[a], takenLeft[t])
                addedLeft[a] -= n
                takenLeft[t] -= n
            }
        }
        val outTaken = taken.indices.filter { takenLeft[it] > 0 }.map { withAmount(taken[it], takenLeft[it]) }
        val outAdded = added.indices.filter { addedLeft[it] > 0 }.map { withAmount(added[it], addedLeft[it]) }
        return outTaken to outAdded
    }

    /** 칸별 변화 — 관리자가 [removed] 칸에서 뺀 것, [added] 칸에 넣은 것. */
    class Ops<T>(val removed: List<Pair<Int, T>>, val added: List<Pair<Int, T>>) {
        val isEmpty: Boolean get() = removed.isEmpty() && added.isEmpty()
    }

    /** 기준([base]) → 고친 것([edited]) 의 칸별 변화. 같은 종류면 개수 차이만, 다르면 통째로 빼고 넣은 것. */
    fun <T> diff(
        base: List<T?>, edited: List<T?>,
        same: (T?, T?) -> Boolean, similar: (T, T) -> Boolean, amount: (T) -> Int, withAmount: (T, Int) -> T,
    ): Ops<T> {
        val removed = ArrayList<Pair<Int, T>>()
        val added = ArrayList<Pair<Int, T>>()
        for (i in 0 until maxOf(base.size, edited.size)) {
            val b = base.getOrNull(i)
            val e = edited.getOrNull(i)
            if (same(b, e)) continue
            if (b != null && e != null && similar(b, e)) {
                val d = amount(e) - amount(b)
                if (d > 0) added += i to withAmount(e, d) else if (d < 0) removed += i to withAmount(b, -d)
            } else {
                if (b != null) removed += i to b
                if (e != null) added += i to e
            }
        }
        return Ops(removed, added)
    }

    /** [apply] 의 결과 — 새 칸 목록, 빼지 못한 것(칸, 남은 것), 넣을 자리가 없던 것. */
    class Applied<T>(val slots: List<T?>, val notRemoved: List<Pair<Int, T>>, val leftovers: List<T>)

    /**
     * 지금 칸([current]) 위에 [ops] 를 얹는다. **빼기 먼저**(그 칸 → 다른 칸 차례로, 같은 종류일 때만 · 있는 만큼만), 그다음 **넣기**
     * (그 칸이 비었고 [fits] 면 그 칸 · 같은 종류면 묶음 최대치까지 → [storage] 칸의 같은 종류에 채우기 → 빈 [storage] 칸).
     * 다 못 뺀 것은 [Applied.notRemoved], 자리가 없는 것은 [Applied.leftovers](우편으로).
     */
    fun <T> apply(
        current: List<T?>, ops: Ops<T>,
        similar: (T, T) -> Boolean, amount: (T) -> Int, withAmount: (T, Int) -> T, maxStack: (T) -> Int,
        fits: (Int, T) -> Boolean, storage: (Int) -> Boolean,
    ): Applied<T> {
        val slots = current.toMutableList()
        val notRemoved = ArrayList<Pair<Int, T>>()
        val leftovers = ArrayList<T>()
        fun take(slot: Int, want: T, need: Int): Int {
            val cur = slots.getOrNull(slot) ?: return need
            if (!similar(cur, want)) return need
            val n = minOf(need, amount(cur))
            val rest = amount(cur) - n
            slots[slot] = if (rest <= 0) null else withAmount(cur, rest)
            return need - n
        }
        for ((slot, item) in ops.removed) {
            var need = take(slot, item, amount(item))
            for (i in slots.indices) {
                if (need <= 0) break
                if (i != slot) need = take(i, item, need)
            }
            if (need > 0) notRemoved += slot to withAmount(item, need)
        }
        fun put(slot: Int, item: T, left: Int, allowEmpty: Boolean): Int {
            if (left <= 0 || slot !in slots.indices) return left
            val cur = slots[slot]
            val max = maxStack(item).coerceAtLeast(1)
            return when {
                cur == null -> if (allowEmpty && fits(slot, item)) { val n = minOf(left, max); slots[slot] = withAmount(item, n); left - n } else left
                similar(cur, item) && amount(cur) < max -> { val n = minOf(left, max - amount(cur)); slots[slot] = withAmount(cur, amount(cur) + n); left - n }
                else -> left
            }
        }
        for ((slot, item) in ops.added) {
            var left = put(slot, item, amount(item), allowEmpty = true)
            for (i in slots.indices) if (i != slot && storage(i) && slots[i] != null) left = put(i, item, left, allowEmpty = false)
            for (i in slots.indices) if (i != slot && storage(i) && slots[i] == null) left = put(i, item, left, allowEmpty = true)
            while (left > 0) {
                val n = minOf(left, maxStack(item).coerceAtLeast(1))
                leftovers += withAmount(item, n)
                left -= n
            }
        }
        return Applied(slots, notRemoved, leftovers)
    }
}

/** 칸 목록의 지문(SHA-256) — 칸 번호와 그 칸의 바이트(빈 칸은 없음 표시)를 차례로. */
object InvHash {

    fun ofBytes(slots: List<ByteArray?>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for ((i, bytes) in slots.withIndex()) {
            md.update(ByteBuffer.allocate(8).putInt(i).putInt(bytes?.size ?: -1).array())
            if (bytes != null) md.update(bytes)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 아이템 칸의 지문. 같은 아이템도 바이트 순서가 달라질 수 있어 **다르면 칸별 비교로 다시 본다**([OfflineEdits]). */
    fun of(items: Array<ItemStack?>): String =
        ofBytes(items.map { item -> item?.takeIf { !it.type.isAir && !it.isEmpty }?.serializeAsBytes() })
}
