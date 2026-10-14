package com.inmc.essentials.inventory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** /인벤 54칸 창의 칸 대응 · 저장 인벤 id 고르기 · keep 정리. */
class InvViewRulesTest {

    @Test
    fun `창 칸과 대상 칸은 41칸 모두 짝이 맞고 나머지는 칸막이`() {
        val mapped = (0 until InvView.SIZE).filter { InvView.toTarget(it) >= 0 }
        assertEquals(41, mapped.size)
        for (t in 0 until InvView.TARGET_SIZE) assertEquals(t, InvView.toTarget(InvView.toView(t)))
        assertEquals(39, InvView.toTarget(36)) // 머리
        assertEquals(36, InvView.toTarget(39)) // 발
        assertEquals(40, InvView.toTarget(40)) // 왼손
        assertEquals(0, InvView.toTarget(27)) // 핫바 첫 칸
        assertEquals(-1, InvView.toTarget(41))
        assertEquals(-1, InvView.toTarget(53))
    }

    @Test
    fun `저장 인벤 - #id 는 id, 그 밖은 번호`() {
        assertEquals(SavedInventories.Ref.Id(123), SavedInventories.ref("#123"))
        assertEquals(SavedInventories.Ref.Index(1), SavedInventories.ref("2"))
        assertEquals(SavedInventories.Ref.Index(0), SavedInventories.ref("마지막"))
        assertNull(SavedInventories.ref("#x"))
        assertNull(SavedInventories.ref("#0"))
    }

    @Test
    fun `keep 정리 - 넘는 오래된 것만, 지키는 줄은 남긴다`() {
        val heads = (5 downTo 1).map { SavedHead(it.toLong(), java.util.UUID(0, 0), "a", it.toLong(), "x", "manual", 1) }
        assertEquals(listOf(2L, 1L), InventoryStore.trimmed(heads, 3, -1).map { it.id })
        assertEquals(listOf(2L), InventoryStore.trimmed(heads, 3, 1).map { it.id })
        assertEquals(emptyList(), InventoryStore.trimmed(heads, 5, -1).map { it.id })
    }
}
