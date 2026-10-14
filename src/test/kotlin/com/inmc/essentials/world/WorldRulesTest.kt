package com.inmc.essentials.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 2026-10-10 리뷰 뒤의 world 규칙 — 몹제거 범위 · 바이옴 찾기 세로 표본(네더 천장). */
class WorldRulesTest {

    @Test
    fun `몹제거 범위 - 가운데 없는 반경은 거절, 반경 없으면 기본 또는 월드 전체`() {
        assertEquals(KillAll.Scope.NeedCenter, KillAll.scope(20, 16, 64, hasCenter = false, worldGiven = false))
        assertEquals(KillAll.Scope.NeedCenter, KillAll.scope(20, 16, 64, hasCenter = false, worldGiven = true))
        assertEquals(KillAll.Scope.Radius(20, false), KillAll.scope(20, 16, 64, hasCenter = true, worldGiven = true))
        assertEquals(KillAll.Scope.Radius(64, true), KillAll.scope(500, 16, 64, hasCenter = true, worldGiven = false))
        assertEquals(KillAll.Scope.Radius(16, false), KillAll.scope(null, 16, 64, hasCenter = true, worldGiven = false))
        assertEquals(KillAll.Scope.Whole, KillAll.scope(null, 16, 64, hasCenter = true, worldGiven = true))
        assertEquals(KillAll.Scope.Whole, KillAll.scope(null, 16, 64, hasCenter = false, worldGiven = false))
    }

    @Test
    fun `몹제거 - 주운 장비는 바닐라 표시(2) 만, 플러그인의 반드시 떨구는 장비(1) 는 아니다`() {
        assertTrue(KillAll.pickedUp(2.0f))
        assertFalse(KillAll.pickedUp(1.0f), "반드시 떨구는 장비(1.0)는 주운 것이 아니다")
        assertFalse(KillAll.pickedUp(0.085f))
        assertFalse(KillAll.pickedUp(0f))
    }

    @Test
    fun `바이옴 찾기 - 네더는 기반암 천장 아래에서만`() {
        val top = BiomeSearch.standTop(0, 256, 128, ceiling = true)
        assertTrue(top + 1 < 123, "머리 ${top + 1} 이 천장(123~) 아래여야 한다")
        val nether = BiomeSearch.heights(0, 256, 32, 128, cave = false, ceiling = true)
        assertTrue(nether.isNotEmpty() && nether.all { it in 8..top }, nether.toList().toString())
        // 보통 월드 — 지표는 바다 높이부터, 동굴은 바다 높이까지
        assertEquals(listOf(63, 95, 127, 159, 191), BiomeSearch.heights(-64, 320, 63, 384, cave = false, ceiling = false).toList())
        assertTrue(BiomeSearch.heights(-64, 320, 63, 384, cave = true, ceiling = false).all { it in -56..63 })
        assertEquals(318, BiomeSearch.standTop(-64, 320, 384, ceiling = false))
    }
}
