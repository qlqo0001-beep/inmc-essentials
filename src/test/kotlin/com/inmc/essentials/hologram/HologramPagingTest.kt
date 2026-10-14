package com.inmc.essentials.hologram

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 빈 쪽 건너뛰기 · 상한을 넘은 사람의 공용 글(사람 토큰 지우기) · CMI 특수 접두. 서버 없이. */
class HologramPagingTest {

    @Test
    fun `줄이 있는 쪽만 넘긴다 - 다 비면 첫 쪽 하나`() {
        assertEquals(listOf(0, 2), HoloRules.shownPages(listOf(1, 0, 3)))
        assertEquals(listOf(0), HoloRules.shownPages(listOf(0, 0)))
        assertEquals(listOf(1), HoloRules.shownPages(listOf(0, 2)))
        val shown = listOf(0, 2, 3)
        // 기준 쪽 + 넘긴 만큼 → 보여 줄 쪽의 자리 → 정의의 쪽 번호
        assertEquals(2, shown[HoloRules.pageOf(0, 1, shown.size)])
        assertEquals(0, shown[HoloRules.pageOf(2, 1, shown.size)])
        // 빈 쪽을 가리키면 그 뒤로 처음 줄이 있는 쪽, 없으면 처음
        assertEquals(1, HoloRules.shownIndexOf(shown, 1))
        assertEquals(2, HoloRules.shownIndexOf(shown, 3))
        assertEquals(0, HoloRules.shownIndexOf(listOf(0, 2), 5))
    }

    @Test
    fun `공용 글에서는 사람 토큰과 사람 PAPI 를 대신 글로 - 전역 PAPI 는 둔다`() {
        val global = listOf("server_")
        assertEquals("안녕 ? (온라인 %server_online%) ?", HoloRules.stripPersonal("안녕 {player} (온라인 %server_online%) %vault_eco_balance%", global, "?"))
        assertEquals("안녕 ", HoloRules.stripPersonal("안녕 {PLAYER}", global, ""))
        assertEquals("그대로", HoloRules.stripPersonal("그대로", global, "?"))
    }

    @Test
    fun `CMI 특수 접두`() {
        assertEquals("chance!", HoloRules.cmiSpecialPrefix("chance! 50 give {player} diamond"))
        assertNull(HoloRules.cmiSpecialPrefix("asConsole! say hi"))
        assertNull(HoloRules.cmiSpecialPrefix("[nextPage]"))
    }
}
