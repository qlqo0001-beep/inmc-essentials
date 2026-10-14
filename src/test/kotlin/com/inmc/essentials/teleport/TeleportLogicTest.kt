package com.inmc.essentials.teleport

import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 서버 없이 도는 이동 규칙 — 좌표 읽기 · 안전한 자리 찾는 순서 · 뒤로 기록 규칙 · 이동 요청 장부. */
class TeleportLogicTest {

    // --- 좌표 ---------------------------------------------------------------------------------------

    @Test
    fun `좌표는 정수면 블록 가운데 - 바닐라 tp 와 같다`() {
        val p = assertNotNull(CoordParser.parse("10 64 -3").parsed)
        assertEquals(10.5, p.x.resolve(0.0))
        assertEquals(64.0, p.y.resolve(0.0))
        assertEquals(-2.5, p.z.resolve(0.0))
        val q = assertNotNull(CoordParser.parse("10.25 64 -3.75").parsed)
        assertEquals(10.25, q.x.resolve(0.0))
        assertEquals(-3.75, q.z.resolve(0.0))
    }

    @Test
    fun `물결은 지금 자리에서 - 월드 시선 꼬리표 순서 무관`() {
        val p = assertNotNull(CoordParser.parse("~ ~5 ~-2.5 world_nether 90 -10 -p:Steve -rng:20 -s").parsed)
        assertEquals(100.0, p.x.resolve(100.0))
        assertEquals(69.0, p.y.resolve(64.0))
        assertEquals(7.5, p.z.resolve(10.0))
        assertEquals("world_nether", p.world)
        // CMI 순서(pitch yaw) — 90 은 pitch 일 수 있으니 CMI 대로 읽는다.
        assertEquals(-10f, p.yaw)
        assertEquals(90f, p.pitch)
        assertEquals("Steve", p.target)
        assertEquals(20, p.range)
        assertTrue(p.silent)
        val q = assertNotNull(CoordParser.parse("-s 1 2 3 45").parsed)
        assertNull(q.yaw)
        assertEquals(45f, q.pitch)
        assertNull(q.world)
    }

    @Test
    fun `꼬리 각도는 CMI 순서 pitch yaw - 첫 값이 90 밖이면 바닐라 순서 yaw pitch`() {
        assertEquals(90f to 10f, CoordParser.angles(listOf(10f, 90f)), "CMI: 100 64 100 world 10 90 = pitch 10, yaw 90")
        assertEquals(180f to -20f, CoordParser.angles(listOf(180f, -20f)), "바닐라 습관: yaw 180 pitch -20 — pitch 는 180 일 수 없다")
        assertEquals(-30f to 45f, CoordParser.angles(listOf(45f, -30f)))
        assertEquals((null as Float?) to 30f, CoordParser.angles(listOf(30f)))
        assertEquals(135f to (null as Float?), CoordParser.angles(listOf(135f)))
        assertEquals((null as Float?) to (null as Float?), CoordParser.angles(emptyList()))
        val p = assertNotNull(CoordParser.parse("100 64 100 world 10 90").parsed)
        assertEquals(90f, p.yaw)
        assertEquals(10f, p.pitch)
    }

    @Test
    fun `CMI 모양 월드 세미콜론 좌표`() {
        val p = assertNotNull(CoordParser.parse("world;1.5;70;-8;180;5").parsed)
        assertEquals("world", p.world)
        assertEquals(1.5, p.x.resolve(999.0))
        assertEquals(-8.0, p.z.resolve(0.0))
        assertEquals(180f, p.yaw)
        assertEquals(5f, p.pitch)
        assertEquals("world;1;2", CoordParser.parse("world;1;2").error)
    }

    @Test
    fun `틀린 좌표는 걸린 낱말을 돌려준다`() {
        assertEquals("abc", CoordParser.parse("1 abc 3").error)
        assertEquals("1 2", CoordParser.parse("1 2").error)
        assertEquals("-rng:0", CoordParser.parse("1 2 3 -rng:0").error)
        assertEquals("extra", CoordParser.parse("1 2 3 world extra").error)
        assertEquals("1e9", CoordParser.parse("1e9 2 3").error)
        assertEquals("~x", CoordParser.parse("~x 2 3").error)
    }

    // --- 안전한 자리 순서 --------------------------------------------------------------------------------

    @Test
    fun `안전한 자리는 기본이 위부터 다음에 아래 - 월드 높이 밖은 뺀다`() {
        assertEquals(listOf(64, 65, 66, 63, 62), SafeSpot.order(64, -64, 319, 2, downThenUp = false))
        assertEquals(listOf(64, 63, 62, 65, 66), SafeSpot.order(64, -64, 319, 2, downThenUp = true))
        assertEquals(listOf(-63, -62, -61), SafeSpot.order(-63, -63, 319, 2, downThenUp = true))
    }

    @Test
    fun `옆으로는 가까운 고리부터`() {
        val cols = SafeSpot.columns(2)
        assertEquals(0 to 0, cols.first())
        assertEquals(25, cols.size)
        assertEquals(25, cols.toSet().size)
        assertTrue(cols.subList(1, 9).all { (x, z) -> maxOf(kotlin.math.abs(x), kotlin.math.abs(z)) == 1 })
        assertEquals(listOf(0 to 0), SafeSpot.columns(0))
    }

    // --- 뒤로 기록 규칙 ----------------------------------------------------------------------------------

    private fun spot(world: String, x: Double) = Spot(world, x, 64.0, 0.0, 0f, 0f, 0L)

    @Test
    fun `뒤로 - 설정된 원인 · 최소 거리 · 제외 월드 · 뒤로 자신`() {
        val s = TeleportSettings()
        assertTrue(TeleportService.shouldRecord(spot("world", 0.0), spot("world", 10.0), TeleportCause.COMMAND, s, suppressed = false))
        assertFalse(TeleportService.shouldRecord(spot("world", 0.0), spot("world", 4.9), TeleportCause.COMMAND, s, suppressed = false), "5블록 안")
        assertTrue(TeleportService.shouldRecord(spot("world", 0.0), spot("world_nether", 1.0), TeleportCause.PLUGIN, s, suppressed = false), "다른 월드는 거리 무관")
        assertFalse(TeleportService.shouldRecord(spot("world", 0.0), spot("world", 100.0), TeleportCause.ENDER_PEARL, s, suppressed = false))
        assertFalse(TeleportService.shouldRecord(spot("world", 0.0), spot("world", 100.0), TeleportCause.NETHER_PORTAL, s, suppressed = false), "포탈은 기본에서 뺐다")
        assertFalse(TeleportService.shouldRecord(spot("inmc_dungeon", 0.0), spot("world", 0.0), TeleportCause.PLUGIN, s, suppressed = false), "던전에서 떠남")
        assertFalse(TeleportService.shouldRecord(spot("world", 0.0), spot("world", 100.0), TeleportCause.PLUGIN, s, suppressed = true), "/뒤로 자신")
    }

    @Test
    fun `기록 만료`() {
        val s = Spot("world", 0.0, 0.0, 0.0, 0f, 0f, at = 1_000L)
        assertFalse(s.expired(0, Long.MAX_VALUE), "0 = 끝없음")
        assertFalse(s.expired(10, 11_000L))
        assertTrue(s.expired(10, 11_001L))
    }

    // --- 이동 요청 장부 ----------------------------------------------------------------------------------

    private val a = UUID(0, 1)
    private val b = UUID(0, 2)
    private val c = UUID(0, 3)

    private fun req(from: UUID, to: UUID, at: Long = 0L, here: Boolean = false, expires: Long = at + 60_000L) =
        TpaBook.Request(from, "p$from", to, "p$to", here, "tpa", at, expires)

    @Test
    fun `같은 상대에게 새 요청은 옛것을 바꾼다 - 수락은 가장 최근 것부터`() {
        val book = TpaBook()
        assertNull(book.add(req(a, c, at = 1)))
        book.add(req(b, c, at = 2))
        val replaced = book.add(req(a, c, at = 3, here = true))
        assertEquals(1L, replaced?.at)
        assertEquals(2, book.size())
        assertEquals(listOf(a, b), book.incoming(c).map { it.from })
        val first = book.take(c)
        assertEquals(a, first?.from)
        assertTrue(first!!.here)
        assertEquals(b, book.take(c, b)?.from)
        assertNull(book.take(c))
    }

    @Test
    fun `취소 · 만료 · 나감`() {
        val book = TpaBook()
        book.add(req(a, b, at = 0, expires = 10))
        book.add(req(a, c, at = 0, expires = 100))
        book.add(req(c, b, at = 0, expires = 100))
        assertEquals(listOf(a), book.expire(10).map { it.from })
        assertEquals(1, book.cancel(a).size)
        assertEquals(1, book.size())
        assertEquals(1, book.removeAll(b).size)
        assertEquals(0, book.size())
    }

    @Test
    fun `거절하면 그 사람의 요청을 한동안 막는다`() {
        val book = TpaBook()
        book.blockAfterDeny(a, b, until = 5_000L)
        assertEquals(4_000L, book.denyBlockLeft(a, b, now = 1_000L))
        assertEquals(0L, book.denyBlockLeft(b, a, now = 1_000L), "반대 방향은 막지 않는다")
        book.expire(5_000L)
        assertEquals(0L, book.denyBlockLeft(a, b, now = 1_000L), "끝나면 지운다")
    }
}
