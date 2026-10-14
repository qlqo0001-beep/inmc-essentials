package com.inmc.essentials.warp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** CMI 에서 가져온 워프 이름 맞추기 — 명령으로 닿고 채팅 클릭 문구를 깨지 않게. */
class WarpMigrationTest {

    private val s = WarpSettings()

    @Test
    fun `가져온 이름 - 맞으면 그대로, 아니면 밑줄로 바꾸고 겹치면 번호`() {
        val none: (String) -> Boolean = { false }
        assertEquals("광장", WarpRules.normalizeName("광장", s, none))
        assertEquals("shop_main", WarpRules.normalizeName("shop main", s, none))
        assertEquals("warp", WarpRules.normalizeName("<>", s, none))
        assertEquals("shop_main_2", WarpRules.normalizeName("shop'main", s) { it == "shop_main" })
        val strict = s.copy(minLength = 4)
        val short = WarpRules.normalizeName("a b", strict, none)
        assertTrue(WarpRules.nameProblem(short, strict) == null, short)
    }
}
