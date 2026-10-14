package com.inmc.essentials.portal

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 포탈 규칙 — 상자·청크 색인 열쇠·명령 접두·효과·쿨타임·밀어내기·파티클 자리·설정 읽기. 서버 없이. */
class PortalRulesTest {

    private val box = PortalBox.of("world", 10, 64, -5, 8, 66, -3)

    @Test
    fun `두 모서리는 아무 순서로 받아 양 끝을 포함한다`() {
        assertEquals(PortalBox("world", 8, 64, -5, 10, 66, -3), box)
        assertTrue(box.contains("world", 8, 64, -5))
        assertTrue(box.contains("world", 10, 66, -3))
        assertFalse(box.contains("world", 11, 64, -5))
        assertFalse(box.contains("other", 9, 65, -4))
        assertEquals(3L * 3 * 3, box.volume)
    }

    @Test
    fun `청크 색인 열쇠는 상자가 걸친 청크를 모두 덮는다 - 음수 좌표 포함`() {
        val wide = PortalBox.of("w", -17, 0, 15, 16, 1, 16)
        val chunks = wide.chunks().toSet()
        for (cx in -2..1) for (cz in 0..1) assertTrue(PortalRules.chunkKey(cx, cz) in chunks, "청크 $cx,$cz")
        assertEquals(8, chunks.size)
        assertTrue(PortalRules.chunkKey(-1, 0) != PortalRules.chunkKey(0, -1))
    }

    @Test
    fun `겹침과 거리`() {
        assertTrue(box.intersects(PortalBox.of("world", 10, 66, -3, 20, 70, 0)))
        assertFalse(box.intersects(PortalBox.of("world", 11, 64, -5, 20, 70, 0)))
        assertFalse(box.intersects(PortalBox.of("nether", 8, 64, -5, 10, 66, -3)))
        assertEquals(0.0, box.distanceSquared(9.5, 65.0, -4.0))
        assertEquals(4.0, box.distanceSquared(13.0, 65.0, -4.0))
    }

    @Test
    fun `영역·위치 문자열은 세미콜론과 CMI 콜론을 다 읽는다`() {
        assertEquals(PortalBox("w", 1, 2, 3, 4, 5, 6), PortalBox.parse("w", "4;5;6;1;2;3"))
        assertEquals(PortalBox("w", -2, 60, 0, 1, 62, 3), PortalBox.parse("w", "-1.5:60.0:0.2:1.9:62.0:3.0"))
        assertNull(PortalBox.parse("w", "1:2:3"))
        assertNull(PortalBox.parse("w", "a:b:c:d:e:f"))
        assertEquals(PortalSpot("world", 1.5, 64.0, -3.25, 90f, 10f), PortalSpot.parse("world;1.5;64;-3.25;90;10"))
        assertEquals(PortalSpot("spawn", 0.0, 80.0, 0.0), PortalSpot.parse("spawn:0:80:0"))
        assertNull(PortalSpot.parse("world;1;2"))
        val spot = PortalSpot("world", 1.5, 64.0, -3.0, 90f, 0f)
        assertEquals(spot, PortalSpot.parse(spot.text()))
    }

    @Test
    fun `명령 접두 - player 는 그 사람, 나머지는 콘솔, 앞의 빗금은 뗀다`() {
        assertEquals(PortalCommand(true, "warp spawn"), PortalRules.parseCommand("player: /warp spawn"))
        assertEquals(PortalCommand(true, "spawn"), PortalRules.parseCommand("asPlayer! spawn"))
        assertEquals(PortalCommand(false, "say hi"), PortalRules.parseCommand("콘솔:say hi"))
        assertEquals(PortalCommand(false, "give {player} diamond"), PortalRules.parseCommand("/give {player} diamond"))
        assertNull(PortalRules.parseCommand("player:"))
        assertNull(PortalRules.parseCommand("   "))
    }

    @Test
    fun `효과 한 줄`() {
        assertEquals(EffectSpec("blindness", 2, 1), PortalRules.parseEffect("blindness:2:1"))
        assertEquals(EffectSpec("speed", 10, 2), PortalRules.parseEffect("minecraft:speed 10 2"))
        assertEquals(EffectSpec("glowing", 1, 1), PortalRules.parseEffect("GLOWING"))
        assertNull(PortalRules.parseEffect("blindness:0:1"))
        assertNull(PortalRules.parseEffect("blindness:x"))
        assertNull(PortalRules.parseEffect("눈멀기"))
    }

    @Test
    fun `쿨타임 남은 초는 올림`() {
        assertEquals(0, PortalRules.cooldownLeft(null, 5, 1000))
        assertEquals(0, PortalRules.cooldownLeft(0, 0, 1000))
        assertEquals(5, PortalRules.cooldownLeft(1000, 5, 1000))
        assertEquals(1, PortalRules.cooldownLeft(1000, 5, 5500))
        assertEquals(0, PortalRules.cooldownLeft(1000, 5, 6000))
    }

    @Test
    fun `밀어내기는 들어서려던 방향의 반대, 제자리면 가운데에서 바깥`() {
        val (dx, dz) = PortalRules.pushBack(7.5, -4.0, 8.5, -4.0, box)
        assertEquals(-1.0, dx, 1e-9)
        assertEquals(0.0, dz, 1e-9)
        val (ox, oz) = PortalRules.pushBack(10.9, -3.5, 10.9, -3.5, box)
        assertTrue(ox > 0.9 && kotlin.math.abs(oz) < 0.1)
    }

    @Test
    fun `파티클 자리는 상자 안(줄임 포함), 윗면·아랫면·가운데`() {
        for (r in listOf(0.0, 0.5, 0.999)) {
            val (x, y, z) = PortalRules.particlePoint(box, ParticleOrigin.ANY, 50, r, r, r)
            assertTrue(x in 8.75..10.25 && y in 64.75..66.25 && z in -4.25..-3.75 + 1.0, "$x $y $z")
        }
        assertEquals(67.0, PortalRules.particlePoint(box, ParticleOrigin.TOP, 0, 0.3, 0.3, 0.3).second)
        assertEquals(65.5, PortalRules.particlePoint(box, ParticleOrigin.CENTER, 0, 0.3, 0.3, 0.3).second)
        assertEquals(ParticleOrigin.TOP, ParticleOrigin.ANY.next())
        assertEquals(ParticleOrigin.ANY, ParticleOrigin.CENTER.next())
        assertEquals(ParticleOrigin.BOTTOM, ParticleOrigin.parse("bottom"))
    }

    @Test
    fun `이름 규칙 - 한글·영문·숫자·밑줄·빼기, 점과 빈칸은 안 된다`() {
        assertTrue(PortalRules.NAME.matches("광장_포탈-1"))
        assertFalse(PortalRules.NAME.matches("a.b"))
        assertFalse(PortalRules.NAME.matches("a b"))
        assertFalse(PortalRules.NAME.matches("x".repeat(33)))
        assertEquals("inmcessentials.portals.portal.광장", PortalRules.node(Portal("광장", box)))
    }

    @Test
    fun `설정 - 배포 파일은 기본값과 같고 이상한 값은 기본값으로`() {
        val shipped = YamlConfiguration().apply { loadFromString(java.io.File("src/main/resources/settings/portals.yml").readText()) }
        assertEquals(PortalSettings(), PortalSettings.from(shipped))
        val weird = YamlConfiguration().apply {
            set("particle-seconds", -3)
            set("push-strength", "많이")
            set("defaults.effects", listOf("speed:2:1", "엉터리"))
        }
        val s = PortalSettings.from(weird)
        assertEquals(1, s.particleSeconds)
        assertEquals(0.6, s.pushStrength)
        assertEquals(listOf("speed:2:1"), s.defaults.effects)
        assertEquals(PortalBox("w", 0, 0, 0, 1, 1, 1), s.create("새", PortalBox("w", 0, 0, 0, 1, 1, 1)).box)
        assertNotNull(PortalRules.parseColor("#AA00FF"))
        assertEquals(0xAA00FF, PortalRules.parseColor("aa00ff"))
        assertNull(PortalRules.parseColor(""))
    }
}
