package com.inmc.essentials.teleport

import com.inmc.essentials.service.TeleportKind
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause
import java.io.InputStreamReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 배포 설정 = 코드 기본값, 이상한 값은 기본값으로, CMI 모양 값 읽기. */
class TeleportSettingsTest {

    private fun shipped(): YamlConfiguration =
        assertNotNull(javaClass.classLoader.getResourceAsStream("settings/teleport.yml"))
            .use { YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8)) }

    @Test
    fun `배포 설정 파일은 코드 기본값과 같다`() {
        assertEquals(TeleportSettings(), TeleportSettings.from(shipped()))
    }

    @Test
    fun `빈 설정은 기본값이다`() {
        assertEquals(TeleportSettings(), TeleportSettings.from(YamlConfiguration()))
    }

    @Test
    fun `이상한 값은 기본값으로 돌고 범위는 잘린다`() {
        val yaml = YamlConfiguration().apply {
            loadFromString(
                """
                invulnerability:
                  seconds: 999
                  per-world:
                    World_Nether: 3
                    broken: abc
                tpa:
                  expire-seconds: 'x'
                  warmup-seconds: -5
                back:
                  causes: [command, NETHER_PORTAL, nonsense]
                  excluded-worlds: [Inmc_Dungeon, '']
                blacklisted-items:
                  kinds: [tpa, admin, nope]
                """.trimIndent(),
            )
        }
        val s = TeleportSettings.from(yaml)
        assertEquals(60, s.invulnerableSeconds)
        assertEquals(mapOf("world_nether" to 3), s.invulnerablePerWorld)
        assertEquals(3, s.invulnerabilityFor("WORLD_NETHER"))
        assertEquals(60, s.invulnerabilityFor("world"))
        assertEquals(60, s.tpaExpireSeconds)
        assertEquals(0, s.tpaWarmupSeconds)
        assertEquals(setOf(TeleportCause.COMMAND, TeleportCause.NETHER_PORTAL), s.backCauses)
        assertEquals(setOf("inmc_dungeon"), s.backExcludedWorlds)
        assertTrue(s.backExcluded("INMC_DUNGEON"))
        assertEquals(setOf(TeleportKind.TPA, TeleportKind.ADMIN), s.blacklistKinds)
    }

    @Test
    fun `관리자 이동과 감옥은 금지 아이템을 보지 않는다 - 목록에 적어도`() {
        val s = TeleportSettings(blacklistEnabled = true, blacklistKinds = TeleportKind.entries.toSet())
        assertFalse(s.blacklistApplies(TeleportKind.ADMIN))
        assertFalse(s.blacklistApplies(TeleportKind.JAIL))
        assertTrue(s.blacklistApplies(TeleportKind.TPA))
        assertFalse(TeleportSettings().blacklistApplies(TeleportKind.TPA), "기본은 꺼져 있다(CMI 와 같다)")
    }

    @Test
    fun `금지 아이템 줄 읽기 - CMI 모양과 표 모양`() {
        assertEquals(BlacklistRule("Diamond", 0), BlacklistRule.parse("Diamond"))
        assertEquals(BlacklistRule("ironore", 5), BlacklistRule.parse("ironore:5"))
        assertEquals(BlacklistRule("minecraft:iron_ore", 0), BlacklistRule.parse("minecraft:iron_ore"))
        assertEquals(BlacklistRule("mmoitems:SWORD:EXCALIBUR", 2), BlacklistRule.parse("mmoitems:SWORD:EXCALIBUR:2"))
        assertNull(BlacklistRule.parse("  "))
        val rules = TeleportSettings.rules(listOf("diamond", mapOf("item" to "inmc:ruby", "max" to 3), mapOf("max" to 1), 42))
        assertEquals(listOf(BlacklistRule("diamond"), BlacklistRule("inmc:ruby", 3)), rules)
    }

    @Test
    fun `소리와 파티클 읽기`() {
        assertEquals(SoundSpec("block.anvil.land", 0.5f, 2f), SoundSpec.parse("block.anvil.land:0.5:2"))
        assertEquals(SoundSpec("minecraft:entity.enderman.teleport", 1f, 1f), SoundSpec.parse("minecraft:entity.enderman.teleport"))
        assertEquals(SoundSpec("entity_enderman_teleport", 0.5f, 1f), SoundSpec.parse("entity_enderman_teleport:0.5:1"))
        assertNull(SoundSpec.parse(""))
        assertEquals(ParticleSpec("portal", 40), ParticleSpec.parse("portal:40"))
        assertEquals(ParticleSpec("portal", 30), ParticleSpec.parse("portal"))
        assertNull(ParticleSpec.parse(" "))
    }

    @Test
    fun `효과 표는 종류 이름으로 읽고 빈 칸은 뺀다`() {
        val yaml = YamlConfiguration().apply {
            loadFromString(
                """
                effects:
                  back: { from: 'portal:10', to: 'reverse_portal' }
                  death-back: { sound: 'entity.wither.spawn:1:1' }
                  home: { from: '' }
                  nope: { from: 'x' }
                """.trimIndent(),
            )
        }
        val effects = TeleportSettings.from(yaml).effects
        assertEquals(setOf(TeleportKind.BACK, TeleportKind.DEATH_BACK), effects.keys)
        assertEquals(KindEffect(ParticleSpec("portal", 10), ParticleSpec("reverse_portal", 30)), effects[TeleportKind.BACK])
    }
}
