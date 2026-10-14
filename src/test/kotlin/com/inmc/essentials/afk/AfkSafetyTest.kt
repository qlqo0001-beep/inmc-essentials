package com.inmc.essentials.afk

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 2026-10-10 리뷰로 고친 규칙들 — 손 잠수의 피해 보호 지연 · 보호 뺄 월드 · 명령 속 사유 거르기. */
class AfkSafetyTest {

    @Test
    fun `손 잠수는 정한 시간이 지나야 피해를 막고 자동 잠수는 바로`() {
        val c = AfkConfig(manualDamageDelay = 30, damageDisabledWorlds = setOf("inmc_dungeon"))
        assertTrue(c.protectsFromDamage(auto = true, afkMillis = 0, world = "world"))
        assertFalse(c.protectsFromDamage(auto = false, afkMillis = 29_999, world = "world"))
        assertTrue(c.protectsFromDamage(auto = false, afkMillis = 30_000, world = "world"))
        assertFalse(c.protectsFromDamage(auto = true, afkMillis = 600_000, world = "INMC_DUNGEON"))
        assertFalse(c.copy(preventDamage = false).protectsFromDamage(auto = true, afkMillis = 600_000, world = "world"))
        assertTrue(c.copy(manualDamageDelay = 0).protectsFromDamage(auto = false, afkMillis = 0, world = "world"))
    }

    @Test
    fun `새 설정 키를 읽는다`() {
        val yaml = YamlConfiguration().apply {
            set("protect.damage-manual-delay", 5)
            set("protect.damage-disabled-worlds", listOf("Arena"))
            set("manual.combat-block-seconds", 0)
        }
        val c = AfkConfig.from(yaml)
        assertEquals(5L, c.manualDamageDelay)
        assertEquals(setOf("arena"), c.damageDisabledWorlds)
        assertEquals(0L, c.combatBlockSeconds)
    }

    @Test
    fun `명령 속 사유는 태그·색·명령 구분·깃발을 지운다`() {
        assertEquals("밥 먹으러 감", AfkService.commandSafe("  밥   먹으러 감 "))
        val evil = AfkService.commandSafe("<click:run_command:'/lp user Evil permission set *'>클릭</click> -s §c&4 a;b|c \"q\" {player}")
        for (bad in listOf("<", ">", "/", "§", "&", ";", "|", "\"", "{", "}")) assertFalse(bad in evil, "'$bad' 가 남았습니다: $evil")
        assertFalse(evil.split(' ').any { it.startsWith("-") }, "깃발이 남았습니다: $evil")
        assertTrue("클릭" in evil)
        assertEquals("", AfkService.commandSafe("--- \n\t"))
    }
}
