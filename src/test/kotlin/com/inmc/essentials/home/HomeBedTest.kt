package com.inmc.essentials.home

import org.bukkit.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 침대 홈의 순수 규칙 — 침대 자리 열쇠 되읽기 · 침대가 되는 월드 · CMI 기본 홈 이름 바꾸기. */
class HomeBedTest {

    @Test
    fun `침대 자리 열쇠 - 쓰고 되읽으면 같다, 모양이 틀리면 null`() {
        val key = Home.blockKey("world", -12, 64, 300)
        assertEquals(BlockPos("world", -12, 64, 300), HomeRules.parseBlockKey(key))
        assertNull(HomeRules.parseBlockKey(null))
        assertNull(HomeRules.parseBlockKey("world;1;2"))
        assertNull(HomeRules.parseBlockKey("world;1;2;x"))
        assertNull(HomeRules.parseBlockKey(";1;2;3"))
        assertNull(HomeRules.parseBlockKey("world;1.5;2;3"))
    }

    @Test
    fun `침대 홈은 오버월드에서만 - 네더·엔드 침대는 터진다`() {
        assertTrue(HomeRules.bedWorks(World.Environment.NORMAL))
        assertFalse(HomeRules.bedWorks(World.Environment.NETHER))
        assertFalse(HomeRules.bedWorks(World.Environment.THE_END))
    }

    @Test
    fun `CMI 기본 홈 이름 - 우리 기본 이름으로 바꾸되 겹치면 안 바꾼다`() {
        assertEquals("home", HomeRules.defaultRename(listOf("Home", "mine"), "Home", "집"))
        assertEquals("home", HomeRules.defaultRename(listOf("HOME"), "home", "집"))
        // CMI 쪽에 이미 '집' 이 있으면 둘 다 그대로.
        assertNull(HomeRules.defaultRename(listOf("Home", "집"), "Home", "집"))
        // 기본 이름의 홈이 없거나 두 이름이 같으면 바꿀 것이 없다.
        assertNull(HomeRules.defaultRename(listOf("mine"), "Home", "집"))
        assertNull(HomeRules.defaultRename(listOf("Home"), "Home", "home"))
        assertNull(HomeRules.defaultRename(listOf("Home"), " ", "집"))
    }

    @Test
    fun `같은 자리 - 블록 단위로 비교한다(CMI 와 우리 좌표의 소수 차이는 무시)`() {
        val home = Home(owner = java.util.UUID(1, 2), name = "집", world = "world", x = 10.5, y = 64.0, z = -3.2)
        assertTrue(HomeRules.sameSpot(home, "world", 10.1, 64.9, -3.9))
        assertFalse(HomeRules.sameSpot(home, "world", 11.0, 64.0, -3.2))
        assertFalse(HomeRules.sameSpot(home, "world", 10.5, 63.9, -3.2))
        assertFalse(HomeRules.sameSpot(home, "world_nether", 10.5, 64.0, -3.2))
    }
}
