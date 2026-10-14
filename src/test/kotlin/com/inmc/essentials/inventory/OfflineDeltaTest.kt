package com.inmc.essentials.inventory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 오프라인 편집의 순수 규칙 — 지문 · 칸별 변화(빼기·넣기·맞비기기) · 적용 방식 · MV 그룹 열쇠. 아이템 대신 가짜 묶음으로. */
class OfflineDeltaTest {

    private data class S(val type: String, val amount: Int)

    private val similar = { a: S, b: S -> a.type == b.type }
    private val amount = { s: S -> s.amount }
    private val withAmount = { s: S, n: Int -> s.copy(amount = n) }
    private val same = { a: S?, b: S? -> a == b }

    private fun diff(base: List<S?>, edited: List<S?>) = InvDelta.diff(base, edited, same, similar, amount, withAmount)

    private fun apply(current: List<S?>, ops: InvDelta.Ops<S>, storage: (Int) -> Boolean = { true }, fits: (Int, S) -> Boolean = { _, _ -> true }) =
        InvDelta.apply(current, ops, similar, amount, withAmount, { 64 }, fits, storage)

    @Test
    fun `지문은 칸 번호와 바이트를 함께 본다`() {
        val a = InvHash.ofBytes(listOf(byteArrayOf(1, 2), null, byteArrayOf(3)))
        assertEquals(a, InvHash.ofBytes(listOf(byteArrayOf(1, 2), null, byteArrayOf(3))))
        assertNotEquals(a, InvHash.ofBytes(listOf(null, byteArrayOf(1, 2), byteArrayOf(3))), "칸이 옮겨졌으면 달라야 한다")
        assertNotEquals(a, InvHash.ofBytes(listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))), "바이트를 이어 붙인 모양이 같아도 칸 경계가 다르면 달라야 한다")
        assertNotEquals(InvHash.ofBytes(listOf(null)), InvHash.ofBytes(listOf(ByteArray(0))), "빈 칸과 빈 바이트는 다르다")
        assertEquals(64, a.length)
    }

    @Test
    fun `변화 - 같은 종류는 개수 차이만, 다르면 통째로`() {
        val ops = diff(
            base = listOf(S("dirt", 10), S("stone", 5), null, S("gold", 1)),
            edited = listOf(S("dirt", 4), S("diamond", 2), S("apple", 3), S("gold", 1)),
        )
        assertEquals(listOf(0 to S("dirt", 6), 1 to S("stone", 5)), ops.removed)
        assertEquals(listOf(1 to S("diamond", 2), 2 to S("apple", 3)), ops.added)
        assertTrue(diff(listOf(S("a", 1)), listOf(S("a", 1))).isEmpty)
    }

    @Test
    fun `얹기 - 뺀 것은 있을 때만, 넣은 것은 그 칸 또는 다른 칸, 남으면 넘침`() {
        // 관리자가 0칸 흙 6개를 빼고 1칸에 다이아 2개를 넣었다. 그새 플레이어는 흙을 3칸으로 옮겼고 1칸에 돌을 넣었다.
        val ops = diff(listOf(S("dirt", 6), null, null, null), listOf(null, S("diamond", 2), null, null))
        val out = apply(listOf(null, S("stone", 1), null, S("dirt", 6)), ops)
        assertEquals(listOf(S("diamond", 2), S("stone", 1), null, null), out.slots, "흙은 옮겨진 칸에서 빼고, 다이아는 (제 칸이 찼으니) 첫 빈 칸으로")
        assertTrue(out.notRemoved.isEmpty())
        assertTrue(out.leftovers.isEmpty())
    }

    @Test
    fun `얹기 - 이미 없는 것은 빼지 못함으로, 자리가 없으면 넘침으로`() {
        val ops = InvDelta.Ops(removed = listOf(0 to S("gold", 5)), added = listOf(1 to S("apple", 70)))
        val out = apply(listOf(S("gold", 2), S("stone", 64)), ops, storage = { it == 1 })
        assertEquals(listOf(0 to S("gold", 3)), out.notRemoved, "있던 2개만 빼고 나머지 3개는 빼지 못함")
        assertEquals(null, out.slots[0])
        assertEquals(listOf(S("apple", 64), S("apple", 6)), out.leftovers, "넘친 것은 묶음 최대치로 나눈다")
    }

    @Test
    fun `얹기 - 갑옷처럼 맞지 않는 칸에는 넣지 않고 저장 칸으로`() {
        val ops = InvDelta.Ops(removed = emptyList(), added = listOf(1 to S("dirt", 3)))
        val out = apply(listOf(null, null), ops, storage = { it == 0 }, fits = { slot, _ -> slot != 1 })
        assertEquals(listOf(S("dirt", 3), null), out.slots)
    }

    @Test
    fun `맞비기기 - 사본 안에서 옮긴 것은 아무것도 오가지 않는다`() {
        val (taken, added) = InvDelta.net(listOf(S("dirt", 64)), listOf(S("dirt", 64)), similar, amount, withAmount)
        assertTrue(taken.isEmpty() && added.isEmpty())
        val (t2, a2) = InvDelta.net(listOf(S("dirt", 64), S("stone", 3)), listOf(S("dirt", 10)), similar, amount, withAmount)
        assertEquals(listOf(S("dirt", 54), S("stone", 3)), t2)
        assertTrue(a2.isEmpty())
    }

    @Test
    fun `적용 방식 - 바탕과 같으면 덮기, 고친 것과 같으면 이미, 표가 남았으면 손대지 않기, 그 밖은 칸별`() {
        assertEquals(OfflineEdits.Mode.OVERWRITE, OfflineEdits.decide(hashEqual = true, baseEqual = false, editedEqual = false, wasClaimed = true))
        assertEquals(OfflineEdits.Mode.OVERWRITE, OfflineEdits.decide(hashEqual = false, baseEqual = true, editedEqual = false, wasClaimed = false))
        assertEquals(OfflineEdits.Mode.ALREADY, OfflineEdits.decide(hashEqual = false, baseEqual = false, editedEqual = true, wasClaimed = true))
        assertEquals(OfflineEdits.Mode.AMBIGUOUS, OfflineEdits.decide(hashEqual = false, baseEqual = false, editedEqual = false, wasClaimed = true))
        assertEquals(OfflineEdits.Mode.DELTA, OfflineEdits.decide(hashEqual = false, baseEqual = false, editedEqual = false, wasClaimed = false))
    }

    @Test
    fun `가방 칸 열쇠 - 같아야 같은 가방, 모르면 같다`() {
        assertTrue(InvGroups.matches(null, "main"))
        assertTrue(InvGroups.matches("main", null))
        assertTrue(InvGroups.matches("main", "main"))
        assertTrue(InvGroups.matches("", ""))
        assertFalse(InvGroups.matches("main", "build"))
    }

    @Test
    fun `종류마다 칸 수와 열쇠`() {
        assertEquals(41, PendingKind.INVENTORY.size)
        assertEquals(27, PendingKind.ENDER.size)
        assertEquals(PendingKind.ENDER, PendingKind.of("ender"))
        assertEquals(null, PendingKind.of("x"))
    }
}
