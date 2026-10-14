package com.inmc.essentials.admininfo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CMI 가져오기 판독(명령·글 색·투명 열·받은 보상)과 투표 누적 단계 고르기 — 서버 없이. 바탕은 CMI 9.8.9.8 jar 기본값. */
class AdminInfoImportTest {

    // --- 명령 -------------------------------------------------------------------------------------

    @Test
    fun `CMI 투표 명령 - 이름 자리 바꾸고 cmi 명령은 따로 알린다`() {
        val c = CmiConvert.commands(listOf("cmi broadcast !&6[playerDisplayName] &evoted!", "cmi give [playerName] diamond"))
        assertEquals(listOf("cmi broadcast !&6{player} &evoted!", "cmi give {player} diamond"), c.commands)
        assertEquals(c.commands, c.cmi)
        assertTrue(c.rejected.isEmpty())
    }

    @Test
    fun `CMI 플레이 보상 명령 - asConsole 는 떼고 특수 명령은 옮기지 않는다`() {
        val c = CmiConvert.commands(
            listOf("asConsole! cmi heal [playerName] -s", "asConsole! eco give [playerName] 20", "asPlayer! spawn", "delay! 5", "closeinv!", "  ", "say [serviceName]"),
        )
        assertEquals(listOf("cmi heal {player} -s", "eco give {player} 20", "say {service}"), c.commands)
        assertEquals(listOf("cmi heal {player} -s"), c.cmi)
        assertEquals(listOf("asPlayer! spawn", "delay! 5", "closeinv!"), c.rejected)
    }

    // --- 글 ---------------------------------------------------------------------------------------

    @Test
    fun `CMI 색 부호는 MiniMessage 로 - 이미 MiniMessage 면 그대로`() {
        val mini = CmiConvert.legacyToMini("&7Hourly reward")
        assertTrue(mini.contains("<gray>") && mini.contains("Hourly reward") && !mini.contains("&7"), mini)
        assertEquals("<red>보상</red>", CmiConvert.legacyToMini("<red>보상</red>"))
        assertEquals("1 & 2", CmiConvert.legacyToMini("1 & 2"))
    }

    @Test
    fun `CMI 보상 이름 - id 규칙에 맞게`() {
        assertEquals("7days", CmiConvert.rewardId("7days"))
        assertEquals("daily_reward_", CmiConvert.rewardId("Daily Reward!"))
        assertTrue(PlaytimeReward.validId(CmiConvert.rewardId("Hourly1")))
    }

    @Test
    fun `CMI 받은 한 번 보상 목록`() {
        assertEquals(setOf("1hour", "6hour"), CmiConvert.oneTimeClaims("1hour;6Hour; ;"))
        assertTrue(CmiConvert.oneTimeClaims(null).isEmpty())
    }

    // --- 투명 열 ------------------------------------------------------------------------------------

    @Test
    fun `CMI 투명 열은 VanishAction 순서의 자리값`() {
        // isVanished=1, damageToEntity=1, 나머지 기본(0), nightVision(9)=0, stopPlaytime(17)=0, joinVanished(19)=1, level=3
        val values = MutableList(23) { "0" }
        values[0] = "1"; values[1] = "1"; values[10] = "1"; values[19] = "1"; values[22] = "3"
        val v = CmiConvert.parseVanish(values.joinToString(":"))!!
        assertTrue(v.vanished)
        assertEquals(3, v.level)
        assertEquals(true, v.values["damageToEntity"])
        assertEquals(false, v.values["nightVision"])
        assertEquals(true, v.values["bossbar"])
        assertEquals(true, v.values["joinVanished"])
        assertFalse("isVanished" in v.values || "level" in v.values)
    }

    @Test
    fun `CMI 투명 열 - 콜론이 없으면 읽지 않고 짧으면 있는 것만`() {
        assertNull(CmiConvert.parseVanish("1"))
        assertNull(CmiConvert.parseVanish(null))
        val v = CmiConvert.parseVanish("0:1")!!
        assertFalse(v.vanished)
        assertEquals(mapOf("damageToEntity" to true), v.values)
    }

    @Test
    fun `우리 투명 옵션은 전부 CMI 자리에 있다`() {
        for (opt in VanishOption.entries) assertTrue(opt.cmiKey in CmiConvert.VANISH_ORDER, opt.cmiKey)
        for (k in CmiConvert.VANISH_ELSEWHERE) assertTrue(k in CmiConvert.VANISH_ORDER && VanishOption.entries.none { it.cmiKey == k }, k)
    }

    // --- 투표 누적 단계 ---------------------------------------------------------------------------------

    @Test
    fun `누적 단계는 새로 지나는 투표에만`() {
        val filled = listOf(100, 10)
        assertEquals(listOf(10), VoteMath.milestonesToPay(filled, emptySet(), 9, 10))
        assertEquals(emptyList(), VoteMath.milestonesToPay(filled, emptySet(), 150, 151)) // 가져온 수·올린 수 아래는 몰아 주지 않는다
        assertEquals(emptyList(), VoteMath.milestonesToPay(filled, setOf(100), 99, 100)) // 이미 받음(내렸다 올림)
        assertEquals(listOf(10, 100), VoteMath.milestonesToPay(filled, emptySet(), 0, 100))
        assertEquals(emptyList(), VoteMath.milestonesToPay(emptyList(), emptySet(), 9, 10)) // 빈 묶음 단계는 넘기지 않는다
    }
}
