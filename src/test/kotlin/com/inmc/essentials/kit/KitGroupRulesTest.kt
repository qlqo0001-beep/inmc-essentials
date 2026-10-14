package com.inmc.essentials.kit

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 킷 그룹 — CMI 무게 규칙(높은 것만) · /킷 <그룹> 의 고르기 · random 모드의 그룹 기록 · 설정 읽기. */
class KitGroupRulesTest {

    private val low = Kit("daily_member", group = "daily", weight = 1)
    private val high = Kit("daily_vip", group = "Daily", weight = 2)
    private val other = Kit("starter", weight = 9)
    private val all = listOf(low, high, other)

    @Test
    fun `낮은 무게는 더 높은 것을 받을 수 있을 때만 밀린다`() {
        assertTrue(KitRules.superseded(low, all) { true })
        assertFalse(KitRules.superseded(high, all) { true })
        assertFalse(KitRules.superseded(low, all) { it !== high }) // VIP 권한이 없으면 member 를 받는다
        assertFalse(KitRules.superseded(other, all) { true }) // 그룹 없음
    }

    @Test
    fun `그룹 고르기는 받을 수 있는 것 중 최고 무게`() {
        assertSame(high, KitRules.topOfGroup(listOf(low, high)) { true })
        assertSame(low, KitRules.topOfGroup(listOf(low, high)) { it === low })
        assertNull(KitRules.topOfGroup(listOf(low, high)) { false })
    }

    @Test
    fun `random 모드는 그룹 전체에 기록 · priority 는 그 킷만`() {
        assertEquals(listOf("daily_member", "daily_vip"), KitRules.recordKits(low, all, true).map { it.id })
        assertEquals(listOf("daily_member"), KitRules.recordKits(low, all, false).map { it.id })
        assertEquals(listOf("starter"), KitRules.recordKits(other, all, true).map { it.id })
    }

    @Test
    fun `밀려도 보이기는 YAML 로 오간다 · 설정 group-mode`() {
        val kit = Kit("a", group = "g", showDespiteWeight = true)
        assertTrue(Kit.fromYaml("a", kit.toYaml()).showDespiteWeight)
        val y = YamlConfiguration()
        assertEquals(KitsSettings.GroupMode.PRIORITY, KitsSettings.from(y).groupMode)
        y.set("group-mode", "Random")
        assertEquals(KitsSettings.GroupMode.RANDOM, KitsSettings.from(y).groupMode)
        y.set("group-mode", "??")
        assertEquals(KitsSettings.GroupMode.PRIORITY, KitsSettings.from(y).groupMode)
    }
}
