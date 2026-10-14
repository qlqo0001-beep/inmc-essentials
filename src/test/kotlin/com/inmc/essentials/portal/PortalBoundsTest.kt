package com.inmc.essentials.portal

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 영역 상한(읽기·CMI·만들기) · 한 걸음에 지나간 블록(DDA) · CMI 파티클 이름·효과 명령·특수 접두. 서버 없이. */
class PortalBoundsTest {

    @Test
    fun `손으로 자릿수를 더 친 영역은 읽지 않고 까닭과 함께 그대로 남긴다`() {
        val yaml = YamlConfiguration().apply {
            set("portals.좋음.world", "w")
            set("portals.좋음.area", "0;60;0;3;70;3")
            set("portals.넓음.world", "w")
            set("portals.넓음.area", "0;60;0;3000000;70;3000000")
            set("portals.바깥.world", "w")
            set("portals.바깥.area", "0;60;0;1e10;61;1")
        }
        val read = PortalCodec.read(yaml, maxVolume = 200_000)
        assertEquals(listOf("좋음"), read.portals.map { it.name })
        assertEquals(setOf("넓음", "바깥"), read.rejected.keys)
        assertTrue(read.reasons["넓음"]!!.contains("max-volume") || read.reasons["넓음"]!!.contains("청크"), read.reasons.toString())
        assertTrue(read.reasons["바깥"]!!.contains("경계"), read.reasons.toString())
        // 되써 넣어도 원문이 남는다
        val again = YamlConfiguration().apply { loadFromString(PortalCodec.write(read).saveToString()) }
        assertEquals("0;60;0;3000000;70;3000000", again.getString("portals.넓음.area"))
    }

    @Test
    fun `상자 검사 - 크기, 월드 경계, 청크 수`() {
        assertEquals(PortalRules.BoxCheck.OK, PortalRules.check(PortalBox("w", 0, 0, 0, 2, 3, 0), 100))
        assertEquals(PortalRules.BoxCheck.TOO_BIG, PortalRules.check(PortalBox("w", 0, 0, 0, 9, 9, 9), 100))
        assertEquals(PortalRules.BoxCheck.OUT_OF_BOUNDS, PortalRules.check(PortalBox("w", 0, 0, 0, Int.MAX_VALUE, 1, 1), Long.MAX_VALUE))
        assertEquals(PortalRules.BoxCheck.OUT_OF_BOUNDS, PortalRules.check(PortalBox("w", 0, -9000, 0, 1, 1, 1), Long.MAX_VALUE))
        // max-volume 을 아주 크게 둬도 청크 상한이 마지막 방벽이다
        val wide = PortalBox("w", 0, 0, 0, 16 * 300, 0, 16 * 300)
        assertEquals(PortalRules.BoxCheck.OUT_OF_BOUNDS, PortalRules.check(wide, Long.MAX_VALUE))
        assertTrue(wide.chunks().isEmpty())
        assertEquals(4, PortalBox("w", 0, 0, 0, 16, 0, 16).chunks().size)
    }

    @Test
    fun `CMI 의 엉뚱한 Loc 은 넣지 않고 못 읽음으로`() {
        val cmi = YamlConfiguration().apply {
            set("world.big.Loc", "0:0:0:5000000:10:5000000")
            set("world.ok.Loc", "0:0:0:1:1:1")
        }
        val read = PortalCodec.fromCmi(cmi, maxVolume = 200_000)
        assertEquals(listOf("ok"), read.portals.map { it.name })
        assertTrue(read.unreadable.any { "big" in it }, read.unreadable.toString())
    }

    @Test
    fun `한 걸음에 1블록 넘게 가도 지나간 블록을 다 준다`() {
        // x 0.7 → 2.3: 1 과 2 를 지난다(시작 블록 0 은 빼고)
        assertEquals(listOf(Triple(1, 64, 0), Triple(2, 64, 0)), PortalRules.blocksAlong(0.7, 64.0, 0.5, 2.3, 64.0, 0.5))
        // 같은 블록 안
        assertTrue(PortalRules.blocksAlong(0.2, 64.0, 0.2, 0.8, 64.9, 0.8).isEmpty())
        // 대각선 — 끝 블록에 닿고, 한 걸음에 한 축씩만 바뀐다
        val diag = PortalRules.blocksAlong(0.5, 64.0, 0.5, 3.5, 64.0, 2.5)
        assertEquals(Triple(3, 64, 2), diag.last())
        var prev = Triple(0, 64, 0)
        for (b in diag) {
            val moved = listOf(b.first - prev.first, b.second - prev.second, b.third - prev.third).count { it != 0 }
            assertEquals(1, moved, "한 칸씩: $diag")
            prev = b
        }
        // 음의 방향 · 상한
        assertEquals(Triple(-3, 64, 0), PortalRules.blocksAlong(0.5, 64.0, 0.5, -2.5, 64.0, 0.5).last())
        val far = PortalRules.blocksAlong(0.5, 64.0, 0.5, 500.5, 64.0, 0.5, maxSteps = 10)
        assertEquals(11, far.size)
        assertEquals(Triple(500, 64, 0), far.last())
    }

    @Test
    fun `CMI 파티클 이름과 a 옵션`() {
        val known = listOf("PORTAL", "DUST", "WITCH", "END_ROD", "SOUL_FIRE_FLAME", "HAPPY_VILLAGER")
        assertEquals("DUST", PortalRules.particleName("RedDust;a{3}", known))
        assertEquals("DUST", PortalRules.particleName("coloreddust", known))
        assertEquals("WITCH", PortalRules.particleName("witchMagic;a{5};s{0.1}", known))
        assertEquals("SOUL_FIRE_FLAME", PortalRules.particleName("soulfireflame", known))
        assertEquals("END_ROD", PortalRules.particleName("end rod", known))
        assertEquals("HAPPY_VILLAGER", PortalRules.particleName("villagerHappy", known))
        assertNull(PortalRules.particleName("BLOCK", known))
        assertNull(PortalRules.particleName("", known))
        assertEquals(3, PortalRules.particleAmount("RedDust;a{3};s{0.1}"))
        assertNull(PortalRules.particleAmount("portal"))

        val cmi = YamlConfiguration().apply {
            set("world.red.Loc", "0:0:0:1:1:1")
            set("world.red.effect", "RedDust;a{7}")
            set("world.red.color", 16711680)
            set("world.odd.Loc", "5:0:5:6:1:6")
            set("world.odd.effect", "mysteryfx")
        }
        val read = PortalCodec.fromCmi(cmi, particles = known)
        val red = read.portals.single { it.name == "red" }
        assertEquals("DUST", red.particles.type)
        assertEquals(0xFF0000, red.particles.color)
        assertEquals(7, red.particles.amount)
        assertEquals("PORTAL", read.portals.single { it.name == "odd" }.particles.type)
        assertTrue(read.notes.any { "mysteryfx" in it }, read.notes.toString())
    }

    @Test
    fun `CMI 효과 명령은 효과 한 줄로, 모르는 특수 접두는 알린다`() {
        assertEquals("blindness:2:1", PortalRules.cmiEffectCommand("cmi effect [playerName] blindness 2 1 -s"))
        assertEquals("speed:5:2", PortalRules.cmiEffectCommand("asConsole! cmi effect {player} SPEED 5 2"))
        assertNull(PortalRules.cmiEffectCommand("cmi effect Steve blindness 2 1"))
        assertNull(PortalRules.cmiEffectCommand("say hi"))
        assertEquals("delay!", PortalRules.cmiSpecialPrefix("delay! 5 say hi"))
        assertNull(PortalRules.cmiSpecialPrefix("asPlayer! spawn"))
        assertNull(PortalRules.cmiSpecialPrefix("say hi!"))

        val cmi = YamlConfiguration().apply {
            set("world.g.Loc", "0:0:0:1:1:1")
            set("world.g.commands", listOf("cmi effect [playerName] blindness 2 1 -s", "delay! 3 say {player}"))
        }
        val read = PortalCodec.fromCmi(cmi)
        val g = read.portals.single()
        assertEquals(listOf("blindness:2:1"), g.effects)
        assertEquals(listOf("delay! 3 say {player}"), g.commands)
        assertTrue(read.notes.any { "delay!" in it }, read.notes.toString())
    }
}
