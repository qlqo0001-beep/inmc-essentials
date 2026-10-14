package com.inmc.essentials.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CMI 에서 넘어올 때의 규칙 — 옛 한도 노드(cmi.command.sethome.*) · 가져온 이름 맞추기. */
class HomeMigrationTest {

    private val s = HomeSettings()

    @Test
    fun `CMI 한도 노드도 센다 - 그룹 대소문자 무시·숫자·unlimited`() {
        val legacy = HomeRules.LEGACY_PREFIX
        assertEquals(5, HomeRules.limit(listOf("${legacy}vip"), false, s))
        assertEquals(12, HomeRules.limit(listOf("${legacy}12", "${HomeRules.LIMIT_PREFIX}3"), false, s))
        assertNull(HomeRules.limit(listOf(HomeRules.LEGACY_UNLIMITED), false, s))
        // bypass·customloc 같은 다른 CMI 노드는 한도로 읽지 않는다.
        assertEquals(1, HomeRules.limit(listOf(HomeRules.LEGACY_BYPASS_LIMIT, HomeRules.LEGACY_BYPASS_PROTECTION, HomeRules.LEGACY_CUSTOM_LOCATION), false, s))
    }

    @Test
    fun `가져온 이름 - 맞으면 그대로, 아니면 밑줄로 바꾸고 겹치면 번호`() {
        val none: (String) -> Boolean = { false }
        assertEquals("집", HomeRules.normalizeName(" 집 ", s, none))
        assertEquals("my_base", HomeRules.normalizeName("my base", s, none))
        assertEquals("a_b", HomeRules.normalizeName("a'<b>", s, none))
        assertEquals("home", HomeRules.normalizeName("'''", s, none))
        val taken = setOf("my_base", "my_base_2")
        assertEquals("my_base_3", HomeRules.normalizeName("my  base", s) { it in taken })
        val long = HomeRules.normalizeName("x".repeat(30) + " y", s, none)
        assertTrue(long.length <= s.nameMaxLength && HomeRules.nameProblem(long, s) == null, long)
    }
}
