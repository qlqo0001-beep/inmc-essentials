package com.inmc.essentials.player

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 2026-10-10 리뷰로 고친 규칙들 — 귀속 저주 모자 · 크래시 뒤 효과 줄이기 · 못 읽은 기록 얹기 · 토템 효과 · 허공 구하기 월드. */
class PlayerSafetyTest {

    @Test
    fun `귀속 저주 모자는 창작 모드가 아니면 못 벗긴다`() {
        assertFalse(HatRule.canReplace(oldBound = true, creative = false))
        assertTrue(HatRule.canReplace(oldBound = true, creative = true))
        assertTrue(HatRule.canReplace(oldBound = false, creative = false))
    }

    @Test
    fun `크래시로 끝난 접속 뒤에는 나간 시각을 믿지 않는다`() {
        assertTrue(OfflineMath.knowsQuit(lastJoin = 1_000, lastQuit = 2_000))
        assertFalse(OfflineMath.knowsQuit(lastJoin = 5_000, lastQuit = 2_000)) // 지난 접속(5000)이 나감 없이 끝났다
        assertFalse(OfflineMath.knowsQuit(lastJoin = 5_000, lastQuit = null))
    }

    @Test
    fun `못 읽은 채 돈 접속은 저장값 위에 켠 것만 얹는다`() {
        val stored = PlayerRecord(god = true, tflyLeft = 7200, collision = false)
        assertEquals(stored, PlayerRecord().over(stored)) // 아무것도 안 바꿨으면 저장값 그대로(지우지 않는다)
        assertEquals(
            PlayerRecord(god = true, fly = true, collision = false, tgodLeft = 60, tflyLeft = 7200),
            PlayerRecord(fly = true, tgodLeft = 60).over(stored),
        )
        assertEquals(PlayerRecord(tflyLeft = 30), PlayerRecord(tflyLeft = 30).over(PlayerRecord(tflyLeft = 7200)))
    }

    @Test
    fun `손 토템 없이 살린 부활은 효과를 직접 건다`() {
        val vanilla = PlayerConfig()
        assertFalse(Totems.effectsNeeded(vanillaApplied = true, vanilla))
        assertTrue(Totems.effectsNeeded(vanillaApplied = false, vanilla))
        assertTrue(Totems.effectsNeeded(vanillaApplied = true, vanilla.copy(totemAbsorption = 10)))
    }

    @Test
    fun `허공 구하기를 빼는 월드 — 기본은 던전`() {
        assertTrue(PlayerConfig().totemVoidSkip("inmc_dungeon"))
        assertFalse(PlayerConfig().totemVoidSkip("world"))
        val yaml = YamlConfiguration().apply { set("totem.protect-from-void-skip-worlds", listOf("Arena")) }
        val c = PlayerConfig.from(yaml)
        assertTrue(c.totemVoidSkip("arena"))
        assertFalse(c.totemVoidSkip("inmc_dungeon"))
    }
}
