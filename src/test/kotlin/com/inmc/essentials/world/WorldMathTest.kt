package com.inmc.essentials.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** world 기능의 순수 계산 — 시각 글자 · 지속 시간 · 나선 · 잠자기 · 포탈 크기 · 몹제거 깃발 · 바이옴 이름 맞추기. */
class WorldMathTest {

    @Test
    fun `시각 글자를 틱으로 - CMI 의 모양들`() {
        assertEquals(6000L, TimeText.parse("12:00")?.ticks)
        assertEquals(7000L, TimeText.parse("13:00:00")?.ticks)
        assertEquals(7000L, TimeText.parse("1pm")?.ticks)
        assertEquals(7000L, TimeText.parse("13")?.ticks)
        assertEquals(7000L, TimeText.parse("7000ticks")?.ticks)
        assertEquals(7000L, TimeText.parse("7000t")?.ticks)
        assertEquals(18000L, TimeText.parse("24:00")?.ticks)
        assertEquals(18000L, TimeText.parse("0:00")?.ticks)
        assertEquals(0L, TimeText.parse("06:00")?.ticks)
        assertEquals(1000L, TimeText.parse("7am")?.ticks)
        assertEquals(19000L, TimeText.parse("1am")?.ticks)
        assertEquals(7000L, TimeText.parse("오후 1")?.ticks)
        assertEquals(1500L, TimeText.parse("오전 7:30")?.ticks)
        assertEquals(7500L, TimeText.parse("13시 30분")?.ticks)
        assertNull(TimeText.parse("25:00"))
        assertNull(TimeText.parse("낮"))
        assertNull(TimeText.parse("7000"))
    }

    @Test
    fun `틱을 시각으로 되돌린다`() {
        assertEquals("12:00", TimeText.format24(6000))
        assertEquals("06:00", TimeText.format24(0))
        assertEquals("00:00", TimeText.format24(18000))
        assertEquals("오후 1:00", TimeText.format12(7000))
        assertEquals("오전 12:00", TimeText.format12(18000))
        assertEquals("13:30", TimeText.format24(TimeText.parse("13:30")!!.ticks))
    }

    @Test
    fun `더하기 양과 앞으로 가는 거리`() {
        assertEquals(500L, TimeText.parseDelta("0:30"))
        assertEquals(2000L, TimeText.parseDelta("2"))
        assertEquals(1500L, TimeText.parseDelta("1h30m"))
        assertEquals(300L, TimeText.parseDelta("300t"))
        assertEquals(500L, TimeText.parseDelta("30분"))
        assertNull(TimeText.parseDelta("abc"))
        assertEquals(1000L, TimeText.forward(23500, 500))
        assertEquals(0L, TimeText.forward(100, 100))
    }

    @Test
    fun `날씨 지속 시간은 초 - 단위와 한글`() {
        assertEquals(120L, DurationText.parse("120"))
        assertEquals(600L, DurationText.parse("10m"))
        assertEquals(3600L, DurationText.parse("1h"))
        assertEquals(5400L, DurationText.parse("1h30m"))
        assertEquals(86_400L, DurationText.parse("1d"))
        assertEquals(604_800L, DurationText.parse("1w"))
        assertEquals(2_592_000L, DurationText.parse("1M"))
        assertEquals(600L, DurationText.parse("10분"))
        assertNull(DurationText.parse("lock"))
        assertNull(DurationText.parse("0"))
        assertNull(DurationText.parse("world"))
    }

    @Test
    fun `나선은 가운데부터 고리마다 전부 한 번씩 돈다`() {
        val spiral = Spiral(3)
        val seen = LinkedHashSet<Pair<Int, Int>>()
        while (true) seen += spiral.next() ?: break
        assertEquals(49, seen.size)
        assertEquals(49L, spiral.total)
        assertEquals(0 to 0, seen.first())
        // 고리 1 이 고리 2 보다 먼저, 같은 고리 안에서는 가까운 칸(축 위)이 모서리보다 먼저.
        val order = seen.toList()
        assertTrue(order.indexOf(1 to 0) < order.indexOf(2 to 0))
        assertTrue(order.indexOf(0 to 2) < order.indexOf(2 to 2))
        assertNull(spiral.next())
    }

    @Test
    fun `잠자기 - 기다림 · 가속 · 바로 아침 · 밤이 아니면 아무것도`() {
        val s = WorldSettings.Sleep()
        assertEquals(SleepMath.Result.Waiting(2), SleepMath.decide(s, 4, 1, true))
        assertEquals(SleepMath.Result.Speed(50, 2), SleepMath.decide(s, 4, 2, true))
        assertEquals(SleepMath.Result.Speed(100, 2), SleepMath.decide(s, 4, 4, true))
        assertEquals(SleepMath.Result.Speed(5, 1), SleepMath.decide(s.copy(minSleeping = WorldSettings.Threshold(1, false)), 40, 1, true))
        assertEquals(SleepMath.Result.Idle, SleepMath.decide(s, 4, 4, false))
        assertEquals(SleepMath.Result.Idle, SleepMath.decide(s, 4, 0, true))
        assertEquals(SleepMath.Result.Skip, SleepMath.decide(s.copy(skipAt = WorldSettings.Threshold(75, true)), 4, 3, true))
        // 사람 수 기준이 인원보다 크면 인원 전부로 낮춘다(혼자 있는 서버).
        assertIs<SleepMath.Result.Speed>(SleepMath.decide(s.copy(minSleeping = WorldSettings.Threshold(3, false)), 1, 1, true))
    }

    @Test
    fun `비율 기준은 올림`() {
        assertEquals(2, WorldSettings.Threshold(50, true).required(3))
        assertEquals(1, WorldSettings.Threshold(50, true).required(1))
        assertEquals(0, WorldSettings.Threshold(0, true).required(10))
        assertEquals(WorldSettings.Threshold(3, false), WorldSettings.Threshold.parse("3", WorldSettings.Threshold(1, true)))
        assertEquals(WorldSettings.Threshold(50, true), WorldSettings.Threshold.parse("50 %", WorldSettings.Threshold(1, false)))
        assertEquals(WorldSettings.Threshold(1, true), WorldSettings.Threshold.parse("200%", WorldSettings.Threshold(1, true)))
    }

    @Test
    fun `포탈 크기는 포탈 블록의 가로 세로`() {
        val blocks = (0 until 4).flatMap { x -> (0 until 5).map { y -> Triple(x, 64 + y, 10) } }
        assertEquals(4 to 5, PortalSize.of(blocks))
        val turned = (0 until 2).flatMap { z -> (0 until 3).map { y -> Triple(7, y, z) } }
        assertEquals(2 to 3, PortalSize.of(turned))
        assertNull(PortalSize.of(emptyList()))
    }

    @Test
    fun `몹제거 깃발`() {
        val plain = (KillAll.parse(emptyList()) as KillAll.Parse.Ok).options
        assertEquals(setOf(KillAll.Category.MONSTERS), plain.categories)
        val many = (KillAll.parse(listOf("-animals", "-r:30", "-m:zombie,minecraft:skeleton", "-w:world_nether", "-lightning", "-s", "-f")) as KillAll.Parse.Ok).options
        assertEquals(setOf(KillAll.Category.ANIMALS), many.categories)
        assertEquals(30, many.radius)
        assertEquals(setOf("zombie", "skeleton"), many.types)
        assertEquals("world_nether", many.world)
        assertTrue(many.lightning && many.silent && many.force)
        val all = (KillAll.parse(listOf("-전부", "-목록")) as KillAll.Parse.Ok).options
        assertEquals(KillAll.Category.entries.toSet(), all.categories)
        assertTrue(all.listOnly)
        assertEquals(KillAll.Parse.Bad("-what"), KillAll.parse(listOf("-what")))
        assertEquals(KillAll.Parse.Bad("zombie"), KillAll.parse(listOf("zombie")))
        assertEquals(KillAll.Parse.Bad("-r:abc"), KillAll.parse(listOf("-r:abc")))
        // -named 만 쓰면 이름 붙은 것만(기본 몬스터 범주를 붙이지 않는다).
        val named = (KillAll.parse(listOf("-named")) as KillAll.Parse.Ok).options
        assertTrue(named.named && named.categories.isEmpty())
    }

    @Test
    fun `몹제거 -all 은 길들인 것의 보호를 풀지 않는다 - -pets 를 직접 써야`() {
        val all = (KillAll.parse(listOf("-all")) as KillAll.Parse.Ok).options
        assertTrue(KillAll.Category.PETS in all.categories)
        assertTrue(!all.pets && !all.force)
        val pets = (KillAll.parse(listOf("-pets")) as KillAll.Parse.Ok).options
        assertTrue(pets.pets && pets.categories == setOf(KillAll.Category.PETS))
        val korean = (KillAll.parse(listOf("-전부", "-애완")) as KillAll.Parse.Ok).options
        assertTrue(korean.pets && korean.categories == KillAll.Category.entries.toSet())
    }

    private data class B(val key: String, val tf: String?, val ko: String?)

    private fun match(input: String, all: List<B>) =
        BiomeNames.matchBy(input, all, { BiomeNames.aliasesOf(it.tf, it.ko, it.key) }) { it.tf ?: it.ko ?: it.key }

    @Test
    fun `바이옴 이름 - 타이틀포지 이름 · 한국어 · id 다 받고 같은 이름이면 전부`() {
        val all = listOf(
            B("minecraft:plains", null, "평원"),
            B("minecraft:sunflower_plains", "<해바라기>", "해바라기 평원"),
            B("terralith:brushland", "관목 지대", null),
            B("terralith:shrubland", "관목 지대", null),
            B("minecraft:deep_dark", null, "깊은 어둠"),
        )
        assertEquals(listOf("minecraft:plains"), match("평원", all).first.map { it.key })
        assertEquals(listOf("minecraft:plains"), match("PLAINS", all).first.map { it.key })
        assertEquals(listOf("minecraft:sunflower_plains"), match("해바라기평원", all).first.map { it.key })
        assertEquals(listOf("minecraft:sunflower_plains"), match("Sunflower Plains", all).first.map { it.key })
        assertEquals(setOf("terralith:brushland", "terralith:shrubland"), match("관목 지대", all).first.map { it.key }.toSet())
        // 부분 일치가 한 이름으로만 모이면 그것 — 여럿이면 후보로.
        assertEquals(listOf("minecraft:deep_dark"), match("어둠", all).first.map { it.key })
        val ambiguous = match("plain", all)
        assertTrue(ambiguous.first.isEmpty() && ambiguous.second.size == 2)
        assertTrue(match("없는곳", all).let { it.first.isEmpty() && it.second.isEmpty() })
    }

    @Test
    fun `동굴 바이옴 판정 - 데이터팩 이름도`() {
        assertTrue(BiomeNames.isCave("minecraft:deep_dark"))
        assertTrue(BiomeNames.isCave("minecraft:lush_caves"))
        assertTrue(BiomeNames.isCave("minecraft:dripstone_caves"))
        assertTrue(BiomeNames.isCave("terralith:cave/deep_caves"))
        assertTrue(!BiomeNames.isCave("minecraft:plains"))
        assertTrue(BiomeNames.isDeepDark("minecraft:deep_dark") && !BiomeNames.isDeepDark("minecraft:lush_caves"))
    }
}
