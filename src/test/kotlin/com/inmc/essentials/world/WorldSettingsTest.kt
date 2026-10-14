package com.inmc.essentials.world

import com.inmc.essentials.service.ImportReport
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `settings/world.yml` 읽기와 CMI config 옮기기. */
class WorldSettingsTest {

    private fun shipped(): YamlConfiguration = YamlConfiguration.loadConfiguration(File("src/main/resources/settings/world.yml"))

    @Test
    fun `배포 설정은 코드의 기본값과 같다`() {
        assertEquals(WorldSettings(), WorldSettings.from(shipped()))
    }

    @Test
    fun `이상한 값은 기본값 - 던지지 않는다`() {
        val y = YamlConfiguration()
        y.loadFromString(
            """
            limits:
              enabled: yes
              check-every: -3
              gamemode:
                World_Creative: Creative
                broken: flying
              no-fly: [World_A]
              spawn-reasons:
                world: [spawner, None]
            sleep:
              info: hologram
              min-sleeping: lots
              base-speed: 0
            nether-portal:
              max-width: 99
              worlds:
                world_nether:
                  prevent-creation: true
            time:
              presets:
                noon: '12:00'
                bad: 'whenever'
            find-biome:
              step: 1
              radius: 999999
            """.trimIndent(),
        )
        val s = WorldSettings.from(y)
        val d = WorldSettings()
        assertTrue(s.limits.enabled)
        assertEquals(d.limits.checkEverySeconds, s.limits.checkEverySeconds)
        assertEquals(mapOf("world_creative" to "creative"), s.limits.gamemode)
        assertEquals("creative", s.limits.gamemodeOf("WORLD_CREATIVE"))
        assertTrue(s.limits.flyBlocked("world_a"))
        assertTrue(s.limits.spawnBlocked("World", "SPAWNER") && !s.limits.spawnBlocked("world", "NATURAL"))
        assertEquals(d.sleep.info, s.sleep.info)
        assertEquals(d.sleep.minSleeping, s.sleep.minSleeping)
        assertEquals(d.sleep.baseSpeed, s.sleep.baseSpeed)
        assertEquals(23, s.portal.base.maxWidth)
        assertTrue(s.portal.of("WORLD_NETHER").preventCreation && !s.portal.of("world").preventCreation)
        assertEquals(6000L, s.time.presets["noon"])
        assertTrue("bad" !in s.time.presets && s.time.presets["day"] == 6000L)
        assertEquals(d.findBiome.step, s.findBiome.step)
        assertEquals(d.findBiome.maxRadius, s.findBiome.radius)
    }

    @Test
    fun `CMI config 의 월드 설정을 옮긴다 - 서버에 없는 월드는 건너뛴다`() {
        val cmi = YamlConfiguration()
        cmi.loadFromString(
            """
            WorldLimits:
              Enabled: true
              Gamemode:
              - testWorld:Survival
              - world_creative:Creative
              Fly:
              - world_nether:False
              - world:True
              ElytraFlight:
              - worldName:False
              FlyAboveRoof: false
              FlyAboveRoofLimitations:
              - world-320
              - world_nether-128
              GodMode:
              - world_pvp:False
              SpawnReasons:
                world:
                - None
                world_nether:
                - SPAWNER
                - NATURAL
            Sleeping:
              Speedup:
                Enabled: true
                Worlds: []
                OnlyDurringNight: false
                InfoType: bossbar
                BaseSpeed: 60
                MinSpeed: 3
                MinBeforeSpeeding: '2'
            NetherPortal:
              PreventCreation: true
              MaxHeight: 10
              MaxWidth: 8
            Optimizations:
              PreventEntityBoatEnter:
                Monsters: true
                Animals: false
                Villagers: true
            Time:
              Day: '13:00'
              Night: 'banana'
              AutoTime:
                Interval: 30
                Smooth: false
                SmoothSpeed: 50
                Worlds:
                - ''
                - world
              TimeSpeed:
                world:
                  Enabled: false
            """.trimIndent(),
        )
        val out = shipped()
        val report = ImportReport()
        CmiWorldImport.apply(cmi, out, setOf("world", "world_nether", "world_creative", "world_pvp"), report)
        val s = WorldSettings.from(out)
        assertTrue(s.limits.enabled && !s.limits.flyAboveRoof)
        assertEquals(mapOf("world_creative" to "creative"), s.limits.gamemode)
        assertEquals(setOf("world_nether"), s.limits.noFly)
        assertEquals(setOf("world_pvp"), s.limits.noGod)
        assertEquals(emptySet(), s.limits.noElytra)
        assertEquals(128, s.limits.roofOf("world_nether"))
        assertEquals(setOf("SPAWNER", "NATURAL"), s.limits.spawnReasons["world_nether"])
        assertTrue("world" !in s.limits.spawnReasons)
        assertEquals(WorldSettings.Sleep.Info.BOSSBAR, s.sleep.info)
        assertEquals(60, s.sleep.baseSpeed)
        assertEquals(WorldSettings.Threshold(2, false), s.sleep.minSleeping)
        assertTrue(!s.sleep.onlyNight)
        assertEquals(WorldSettings.PortalRule(true, 8, 10), s.portal.base)
        assertEquals(WorldSettings.Riders(monsters = true, villagers = true), s.vehicles.boats)
        assertEquals(7000L, s.time.presets["day"])
        assertEquals(18000L, s.time.presets["night"])
        assertEquals(setOf("world"), s.time.autoRealWorlds)
        assertEquals(30, s.time.autoRealIntervalSeconds)
        assertEquals(50, s.time.smoothSpeed)
        val details = report.details()
        assertTrue(details.any { "testWorld" in it }, "없는 월드를 건너뛴 기록: $details")
        assertTrue(details.any { "worldName" in it })
        assertTrue(details.any { "Time.Night" in it && "못 읽음" in it })
        assertTrue(details.any { "TimeSpeed" in it })
    }

    @Test
    fun `CMI 에서 꺼 둔 모듈은 옮긴 뒤에도 꺼진다 - 죽은 뒤 비행은 보고서에`() {
        val cmi = YamlConfiguration()
        cmi.loadFromString(
            """
            WorldLimits:
              Enabled: true
              ElytraFlight:
              - world:False
              ReenabledFlyAfterDeath: false
            Sleeping:
              Speedup:
                Enabled: true
            NetherPortal:
              PreventCreation: true
              MaxHeight: 10
              MaxWidth: 8
            """.trimIndent(),
        )
        val modules = YamlConfiguration()
        modules.loadFromString("worldLimits: false\nworldLimitsElytra: false\nnightSpeedup: false\nportalCreation: false\nsignEdit: true\n")
        val out = shipped()
        val report = ImportReport()
        CmiWorldImport.apply(cmi, out, setOf("world"), report, modules)
        val s = WorldSettings.from(out)
        assertTrue(!s.limits.enabled)
        assertEquals(emptySet(), s.limits.noElytra)
        assertTrue(!s.sleep.enabled)
        assertEquals(WorldSettings.PortalRule(false, 23, 23), s.portal.base)
        val details = report.details()
        assertTrue(details.any { "ReenabledFlyAfterDeath" in it }, "죽은 뒤 비행을 조용히 버리지 않는다: $details")
        assertTrue(details.any { "nightSpeedup" in it } && details.any { "worldLimits=" in it } && details.any { "portalCreation" in it })
    }
}
