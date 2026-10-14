package com.inmc.essentials.spawn

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 부활 순서 · 접속 자리 · 스폰 파일(inmc-menu 의 `SpawnTest` 를 옮기고 새 칸·첫 스폰·CMI 위치 읽기를 더했다). */
class SpawnConfigTest {

    private val order = SpawnConfig().respawn.map { it.source }

    @Test
    fun `기본 순서 - 침대 앵커 서버 (침대홈 홈 워프 꺼짐) 월드`() {
        assertEquals(listOf(Source.BED, Source.ANCHOR, Source.SERVER, Source.BED_HOME, Source.HOME, Source.WARP, Source.WORLD), order)
        assertEquals(listOf(true, true, true, false, false, false, true), SpawnConfig().respawn.map { it.enabled })
    }

    @Test
    fun `부활 - 순서대로 처음 되는 곳`() {
        val c = SpawnConfig()
        assertEquals(Source.BED, c.choose(bed = true, anchor = false, hasServerSpawn = true))
        assertEquals(Source.ANCHOR, c.choose(bed = false, anchor = true, hasServerSpawn = true))
        assertEquals(Source.SERVER, c.choose(bed = false, anchor = false, hasServerSpawn = true))
        // 홈 칸은 기본으로 꺼져 있다 — 홈이 있어도 월드 스폰.
        assertEquals(Source.WORLD, c.choose(bed = false, anchor = false, hasServerSpawn = false, bedHome = true, home = true, warp = true))

        // 서버 스폰을 맨 앞에 두면 침대가 있어도 서버 스폰.
        val serverFirst = c.move(2, -1).move(1, -1)
        assertEquals(Source.SERVER, serverFirst.respawn.first().source)
        assertEquals(Source.SERVER, serverFirst.choose(bed = true, anchor = true, hasServerSpawn = true))
        assertEquals(Source.BED, serverFirst.choose(bed = true, anchor = false, hasServerSpawn = false))

        // 끈 것은 건너뛰고, 다 안 되면 바닐라 그대로(null).
        val noWorld = c.toggle(order.indexOf(Source.WORLD))
        assertNull(noWorld.choose(bed = false, anchor = false, hasServerSpawn = false))
        assertEquals(Source.ANCHOR, c.toggle(0).choose(bed = true, anchor = true, hasServerSpawn = true))
    }

    @Test
    fun `부활 - 홈 워프 칸을 켜면 순서대로`() {
        val c = SpawnConfig(respawn = SpawnConfig.parseOrder(listOf("bed-home", "home", "warp", "server", "world")))
        assertEquals(Source.BED_HOME, c.choose(bed = false, anchor = false, hasServerSpawn = true, bedHome = true, home = true))
        assertEquals(Source.HOME, c.choose(bed = false, anchor = false, hasServerSpawn = true, bedHome = false, home = true))
        assertEquals(Source.WARP, c.choose(bed = false, anchor = false, hasServerSpawn = true, warp = true))
        assertEquals(Source.SERVER, c.choose(bed = false, anchor = false, hasServerSpawn = true))
    }

    @Test
    fun `부활 - 뒤 칸은 앞 칸이 이기면 묻지 않는다`() {
        val asked = mutableListOf<Source>()
        SpawnConfig(respawn = SpawnConfig.parseOrder(listOf("server", "home", "world"))).choose { asked += it; it == Source.SERVER }
        assertEquals(listOf(Source.SERVER), asked)
    }

    @Test
    fun `옮기기는 끝에서 멈춘다`() {
        val c = SpawnConfig()
        assertEquals(c, c.move(0, -1))
        assertEquals(c, c.move(c.respawn.lastIndex, +1))
        assertEquals(c, c.move(9, +1))
    }

    @Test
    fun `순서 읽기 - 모르는 것은 버리고, 빠진 것은 끈 채로 끝에 (옛 inmc-menu 파일)`() {
        val order = SpawnConfig.parseOrder(listOf("server", "-bed", "moon", "server", " World "))
        assertEquals(
            listOf(Step(Source.SERVER), Step(Source.BED, enabled = false), Step(Source.WORLD), Step(Source.ANCHOR, enabled = false),
                Step(Source.BED_HOME, enabled = false), Step(Source.HOME, enabled = false), Step(Source.WARP, enabled = false)),
            order,
        )
        assertEquals(SpawnConfig.DEFAULT_ORDER, SpawnConfig.parseOrder(emptyList()))
        assertEquals(Source.BED_HOME, Source.parse("BED_HOME"))
        assertEquals(Source.BED_HOME, Source.parse("bed-home"))
    }

    @Test
    fun `접속 자리 - 첫 접속은 첫 스폰, 매 접속은 뺄 월드를 본다`() {
        val point = SpawnPoint("spawn", 0.0, 64.0, 0.0, 0f, 0f)
        val c = SpawnConfig(point = point, firstJoin = true, everyJoin = true)
        assertEquals(JoinTarget.FIRST, c.joinTarget(isNew = true, fromWorld = null, ignoredWorlds = emptySet()))
        assertEquals(JoinTarget.SERVER, c.joinTarget(isNew = false, fromWorld = "world", ignoredWorlds = emptySet()))
        assertEquals(JoinTarget.NONE, c.joinTarget(isNew = false, fromWorld = "Dungeon", ignoredWorlds = setOf("dungeon")))
        assertEquals(JoinTarget.NONE, SpawnConfig(point = point).joinTarget(isNew = false, fromWorld = "world", ignoredWorlds = emptySet()))
        assertEquals(JoinTarget.NONE, SpawnConfig().joinTarget(isNew = true, fromWorld = null, ignoredWorlds = emptySet()))
        // 첫 스폰만 있어도 첫 접속은 된다.
        assertEquals(JoinTarget.FIRST, SpawnConfig(firstPoint = point).joinTarget(isNew = true, fromWorld = null, ignoredWorlds = emptySet()))
        assertEquals(JoinTarget.NONE, SpawnConfig(point = point, firstJoin = false).joinTarget(isNew = true, fromWorld = null, ignoredWorlds = emptySet()))
    }

    @Test
    fun `CMI 위치 글자 읽기 - 세미콜론과 콜론`() {
        assertEquals(SpawnPoint("spawn", -3.53, 84.0, 163.54, 90.15f, -0.14f), SpawnPoint.parseCmi("spawn;-3.53;84;163.54;90.15;-0.14"))
        assertEquals(SpawnPoint("world", 1.0, 2.0, 3.0, 0f, 0f), SpawnPoint.parseCmi("world:1:2:3"))
        assertNull(SpawnPoint.parseCmi(""))
        assertNull(SpawnPoint.parseCmi("world;x;2;3"))
        assertNull(SpawnPoint.parseCmi(";1;2;3"))
    }

    @Test
    fun `파일 왕복`() {
        val c = SpawnConfig(
            point = SpawnPoint("spawn.world", 10.5, 64.0, -3.5, 90f, 0f),
            firstPoint = SpawnPoint("lobby", 1.0, 70.0, 1.0, 0f, 0f),
            firstJoin = false, everyJoin = true, respawn = SpawnConfig().toggle(1).move(3, -2).respawn,
            respawnWarp = "광장", radius = 12, cmiImported = true,
        )
        val back = SpawnConfig.from(YamlConfiguration().apply { loadFromString(c.toYaml().saveToString()) })
        assertEquals(c, back)
        assertEquals(SpawnConfig(), SpawnConfig.from(YamlConfiguration()))
    }

    @Test
    fun `옛 inmc-menu 파일을 그대로 읽는다 - 대기 쿨타임 키는 무시`() {
        val old = YamlConfiguration().apply {
            loadFromString(
                """
                spawn:
                  world: spawn
                  x: 1.5
                  y: 64.0
                  z: 2.5
                  yaw: 0.0
                  pitch: 0.0
                first-join: true
                every-join: false
                respawn-order: [bed, anchor, server, world]
                wait: 3
                cooldown: 0
                """.trimIndent(),
            )
        }
        val c = SpawnConfig.from(old)
        assertEquals(SpawnPoint("spawn", 1.5, 64.0, 2.5, 0f, 0f), c.point)
        assertEquals(listOf(Source.BED, Source.ANCHOR, Source.SERVER, Source.WORLD), c.respawn.filter { it.enabled }.map { it.source })
    }

    @Test
    fun `운영 설정 - 이상한 값은 기본값으로`() {
        val s = SpawnSettings.from(YamlConfiguration().apply {
            loadFromString("join-invulnerable-seconds: 999\nrespawn-invulnerable-seconds: -1\nignored-worlds: [' dungeon ', '']")
        })
        assertEquals(SpawnSettings.MAX_INVULNERABLE, s.joinInvulnerable)
        assertEquals(0, s.respawnInvulnerable)
        assertEquals(setOf("dungeon"), s.ignoredWorlds)
        assertEquals(SpawnSettings(), SpawnSettings.from(YamlConfiguration()))
    }
}
