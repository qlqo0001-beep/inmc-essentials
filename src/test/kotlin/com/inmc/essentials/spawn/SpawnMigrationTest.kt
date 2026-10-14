package com.inmc.essentials.spawn

import org.bukkit.Material
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CMI 부활 순서 옮기기 · 흩기 칸 고르기(순수). */
class SpawnMigrationTest {

    @Test
    fun `CMI 부활 순서 - 테섭 값`() {
        val r = SpawnConfig.fromCmiOrder(listOf("anchor", "bedLocation", "spawn", "homeLocation", "worldSpawn"), spawnRespawn = false)
        assertEquals(listOf(Source.ANCHOR, Source.BED, Source.SERVER, Source.HOME, Source.WORLD), r.steps.take(5).map { it.source })
        // Main.RespawnLocation 이 거짓이면 CMI 는 spawn 칸을 건너뛴다.
        assertEquals(listOf(Source.ANCHOR, Source.BED, Source.HOME, Source.WORLD), r.steps.filter { it.enabled }.map { it.source })
        assertEquals(Source.entries.toSet(), r.steps.map { it.source }.toSet())
        assertNull(r.warp)
        assertTrue(r.unknown.isEmpty())
    }

    @Test
    fun `CMI 부활 순서 - 워프·모르는 칸·빈 목록`() {
        val r = SpawnConfig.fromCmiOrder(listOf("warp!광장", "spawn", "random", "warp!다른"), spawnRespawn = true)
        assertEquals(listOf(Source.WARP, Source.SERVER), r.steps.filter { it.enabled }.map { it.source })
        assertEquals("광장", r.warp)
        assertEquals(listOf("random"), r.unknown)
        assertEquals(SpawnConfig.DEFAULT_ORDER, SpawnConfig.fromCmiOrder(emptyList(), true).steps)
    }

    @Test
    fun `흩기 칸은 반경 안`() {
        for (r1 in listOf(0.0, 0.25, 0.5, 0.99)) for (r2 in listOf(0.0, 0.5, 1.0)) {
            val (x, z) = SpawnService.column(10, -20, 50, r1, r2)
            assertTrue(hypot(x - 10.5, z + 19.5) <= 50.0001, "$x,$z")
        }
    }

    @Test
    fun `땅 위 두 칸 - 비었으면 서고 불·덤불·위더 장미·가루눈·고인 물이면 안 선다`() {
        // Material.isSolid 는 서버가 필요해(레지스트리) 단단함은 가짜로 넘긴다 — 여기서는 돌만 단단하다.
        val solid: (Material) -> Boolean = { it == Material.STONE }
        assertTrue(SpawnService.standable(listOf(Material.AIR, Material.AIR), solid))
        assertTrue(SpawnService.standable(listOf(Material.POPPY, Material.AIR), solid))
        assertFalse(SpawnService.standable(listOf(Material.FIRE, Material.AIR), solid))
        assertFalse(SpawnService.standable(listOf(Material.AIR, Material.SOUL_FIRE), solid))
        assertFalse(SpawnService.standable(listOf(Material.SWEET_BERRY_BUSH, Material.AIR), solid))
        assertFalse(SpawnService.standable(listOf(Material.WITHER_ROSE, Material.AIR), solid))
        assertFalse(SpawnService.standable(listOf(Material.POWDER_SNOW, Material.AIR), solid))
        assertFalse(SpawnService.standable(listOf(Material.WATER, Material.AIR), solid))
        assertFalse(SpawnService.standable(listOf(Material.STONE, Material.AIR), solid))
    }
}
