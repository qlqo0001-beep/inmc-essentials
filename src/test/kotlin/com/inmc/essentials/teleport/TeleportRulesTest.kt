package com.inmc.essentials.teleport

import com.inmc.essentials.service.TeleportKind
import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 리뷰 뒤 더한 규칙(서버 없이) — 시스템 이동(OTHER)과 /이동 가르기 · 감옥 이동 뒤로 빼기 · 효과 열쇠 · 설정 경고 · CMI acceptingTPA 읽기. */
class TeleportRulesTest {

    private fun yaml(text: String) = YamlConfiguration().apply { loadFromString(text.trimIndent()) }

    @Test
    fun `다른 기능의 OTHER 는 시스템 이동 - 금지 아이템을 안 보고 안전한 자리가 없으면 그 자리로`() {
        val s = TeleportSettings(blacklistEnabled = true, blacklistKinds = TeleportKind.entries.toSet())
        assertFalse(s.blacklistApplies(TeleportKind.OTHER), "토템 구조·앉기·접속 복귀가 금지 아이템으로 막히면 안 된다")
        assertFalse(EssTeleporter.isChosen(TeleportKind.OTHER, null))
        assertTrue(EssTeleporter.exactFallback(TeleportKind.OTHER, chosen = false))
        assertTrue(EssTeleporter.exactFallback(TeleportKind.ADMIN, chosen = false))
        assertFalse(EssTeleporter.exactFallback(TeleportKind.HOME, chosen = false))
    }

    @Test
    fun `이동 좌표이동 자기 이동은 사람이 고른 이동 - TP 칸으로 금지 아이템을 보고 위험하면 막는다`() {
        assertTrue(EssTeleporter.isChosen(TeleportKind.OTHER, TeleportSettings.FX_TP))
        assertTrue(EssTeleporter.isChosen(TeleportKind.OTHER, TeleportSettings.FX_TPPOS))
        assertFalse(EssTeleporter.isChosen(TeleportKind.ADMIN, TeleportSettings.FX_TP), "강제이동은 관리자 이동")
        assertFalse(EssTeleporter.exactFallback(TeleportKind.OTHER, chosen = true))
        val on = TeleportSettings(blacklistEnabled = true)
        assertTrue(on.blacklistAppliesToTp(), "기본 TP 칸 켜짐(CMI EnabledFor.tp)")
        assertFalse(on.copy(blacklistTp = false).blacklistAppliesToTp())
        assertFalse(TeleportSettings().blacklistAppliesToTp(), "금지 아이템 자체가 꺼져 있으면 안 본다")
    }

    @Test
    fun `금지 아이템 기본 칸에 뒤로 사망지점이 들어 있다 - 뒤로가 운반 통로가 되지 않게`() {
        val s = TeleportSettings(blacklistEnabled = true)
        assertTrue(s.blacklistApplies(TeleportKind.BACK))
        assertTrue(s.blacklistApplies(TeleportKind.DEATH_BACK))
        assertFalse(s.blacklistApplies(TeleportKind.PORTAL))
    }

    @Test
    fun `설정 kinds 의 TP 는 자기 이동 칸 - 빠지면 꺼진다`() {
        val with = TeleportSettings.from(yaml("blacklisted-items: { kinds: [tp, home] }"))
        assertTrue(with.blacklistTp)
        assertEquals(setOf(TeleportKind.HOME), with.blacklistKinds)
        val without = TeleportSettings.from(yaml("blacklisted-items: { kinds: [home] }"))
        assertFalse(without.blacklistTp)
    }

    @Test
    fun `감옥 이동과 뒤로 자신은 뒤로 기록에서 뺀다`() {
        val s = TeleportSettings()
        assertTrue(EssTeleporter.suppressesBack(TeleportKind.JAIL, s), "풀려난 뒤 /뒤로 로 감방에 돌아가지 않게")
        assertTrue(EssTeleporter.suppressesBack(TeleportKind.BACK, s))
        assertFalse(EssTeleporter.suppressesBack(TeleportKind.BACK, s.copy(backRecordBackMoves = true)))
        assertFalse(EssTeleporter.suppressesBack(TeleportKind.HOME, s))
        assertTrue(EssTeleporter.suppressesBack(TeleportKind.JAIL, s.copy(backRecordBackMoves = true)))
    }

    @Test
    fun `명령마다 효과는 종류의 효과보다 먼저`() {
        val s = TeleportSettings.from(
            yaml(
                """
                effects:
                  ADMIN: { to: 'portal:5' }
                  tpall: { to: 'end_rod:20', sound: 'entity.player.levelup' }
                  nope: { to: 'x' }
                """,
            ),
        )
        assertEquals(ParticleSpec("end_rod", 20), s.effectFor(TeleportKind.ADMIN, TeleportSettings.FX_TPALL)?.to)
        assertEquals(ParticleSpec("portal", 5), s.effectFor(TeleportKind.ADMIN, TeleportSettings.FX_TPHERE)?.to, "열쇠의 효과가 없으면 종류")
        assertEquals(ParticleSpec("portal", 5), s.effectFor(TeleportKind.ADMIN, null)?.to)
        assertEquals(setOf("TPALL"), s.commandEffects.keys)
    }

    @Test
    fun `월드별 무적 - 표 꼴과 CMI 목록 꼴`() {
        assertEquals("world_nether" to 3, TeleportSettings.perWorldEntry("World_Nether-3"))
        assertEquals("my-world" to 5, TeleportSettings.perWorldEntry("my-world:5"))
        assertEquals("my-world" to 60, TeleportSettings.perWorldEntry("my-world-99"))
        assertEquals(null, TeleportSettings.perWorldEntry("world"))
        assertEquals(null, TeleportSettings.perWorldEntry("-3"))
        val s = TeleportSettings.from(yaml("invulnerability: { per-world: [world_nether-3, broken] }"))
        assertEquals(mapOf("world_nether" to 3), s.invulnerablePerWorld)
    }

    @Test
    fun `읽지 못한 값은 경고로 남긴다`() {
        val problems = TeleportSettings.problems(
            yaml(
                """
                back: { causes: [COMAND, plugin] }
                blacklisted-items:
                  kinds: [tp, other, nope]
                  items: [diamond, '', 42]
                invulnerability: { per-world: [world_nether-3, broken] }
                effects: { tpall: { to: 'x' }, nope: { to: 'x' } }
                """,
            ),
        )
        val text = problems.joinToString("\n")
        assertTrue("'COMAND'" in text, text)
        assertTrue("'other'" in text, text)
        assertTrue("'nope'" in text, text)
        assertTrue("'broken'" in text, text)
        assertTrue("effects.nope" in text, text)
        assertFalse("effects.tpall" in text, text)
        assertFalse("'tp'" in text, text)
        assertTrue(problems.count { it.startsWith("blacklisted-items.items") } == 2, text)
        assertTrue(TeleportSettings.problems(yaml("back: { causes: [nope] }")).any { "아무것도 기록되지 않습니다" in it })
        assertEquals(emptyList(), TeleportSettings.problems(YamlConfiguration()))
    }

    @Test
    fun `이동 요청 제외 월드와 불려 갈 자리 만료`() {
        val s = TeleportSettings()
        assertTrue(s.tpaExcluded("INMC_DUNGEON"), "던전 기본")
        assertFalse(s.tpaExcluded("world"))
        val spot = Spot("world", 0.0, 0.0, 0.0, 0f, 0f, at = 0L)
        assertFalse(s.pendingExpired(spot, s.pendingExpireSeconds * 1000L))
        assertTrue(s.pendingExpired(spot, s.pendingExpireSeconds * 1000L + 1))
        assertFalse(s.copy(pendingExpireSeconds = 0).pendingExpired(spot, Long.MAX_VALUE), "0 = 끝없음")
    }

    @Test
    fun `CMI 개인 옵션의 acceptingTPA 읽기`() {
        assertEquals(AcceptingTpa.Value(false), AcceptingTpa.parse("acceptingTPA:false"))
        assertEquals(AcceptingTpa.Value(false), AcceptingTpa.parse("{\"shiftEdit\":true,\"acceptingTPA\":false}"))
        assertEquals(AcceptingTpa.Value(false), AcceptingTpa.parse("acceptingTPA=0"))
        assertEquals(AcceptingTpa.Value(true), AcceptingTpa.parse("x;AcceptingTpa%%true;y"))
        assertEquals(AcceptingTpa.Unreadable, AcceptingTpa.parse("acceptingTPA:maybe"))
        assertEquals(AcceptingTpa.Absent, AcceptingTpa.parse("shiftEdit:true"))
    }
}
