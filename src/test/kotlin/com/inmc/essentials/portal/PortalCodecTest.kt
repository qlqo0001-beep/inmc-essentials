package com.inmc.essentials.portal

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** portals.yml 왕복 · 못 읽은 칸 되써 넣기 · CMI Saves/Portals.yml 판독(합성 YAML — 테섭 파일은 비어 있다). */
class PortalCodecTest {

    private fun reload(yaml: YamlConfiguration) = YamlConfiguration().apply { loadFromString(yaml.saveToString()) }

    @Test
    fun `저장하고 다시 읽으면 같다`() {
        val portal = Portal(
            name = "광장",
            box = PortalBox("world", -3, 64, 10, 0, 66, 10),
            target = PortalSpot("world_nether", 1.5, 70.0, -2.5, 180f, 0f),
            safe = PortalSpot("world", 0.0, 64.0, 12.0),
            enabled = false, permission = true, kickBack = false, informPermission = false, commandsWithoutTarget = false,
            commands = listOf("player:warp 광장", "say {player}"), effects = listOf("blindness:2:1"), cooldown = 7, sound = "block.portal.travel",
            particles = Portal.Particles(false, "DUST", 0x11AA22, 33, ParticleOrigin.TOP, 25, 12, false),
        )
        val plain = Portal("b", PortalBox("w", 0, 0, 0, 0, 1, 0))
        val back = PortalCodec.read(reload(PortalCodec.write(PortalFile(listOf(portal, plain)))))
        assertEquals(setOf(portal, plain), back.portals.toSet())
        assertTrue(back.rejected.isEmpty())
    }

    @Test
    fun `못 읽은 칸은 지우지 않고 그대로 되써 넣는다`() {
        val yaml = YamlConfiguration().apply {
            set("portals.좋음.world", "w")
            set("portals.좋음.area", "0;0;0;1;1;1")
            set("portals.나쁨.world", "w")
            set("portals.나쁨.area", "엉터리")
            set("portals.나쁨.commands", listOf("say 남겨 둘 것"))
        }
        val read = PortalCodec.read(yaml)
        assertEquals(listOf("좋음"), read.portals.map { it.name })
        assertEquals(setOf("나쁨"), read.rejected.keys)
        val again = reload(PortalCodec.write(read))
        assertEquals("엉터리", again.getString("portals.나쁨.area"))
        assertEquals(listOf("say 남겨 둘 것"), again.getStringList("portals.나쁨.commands"))
    }

    @Test
    fun `CMI 포탈 - 월드 아래 포탈, 영역·목적지·권한·파티클·명령`() {
        val cmi = YamlConfiguration().apply {
            loadFromString(
                """
                world:
                  Spawn_Gate:
                    Loc: '10.0:64.0:5.0:12.0:67.0:5.0'
                    world: world
                    enabled: true
                    effect: portal
                    showParticles: true
                    Tp: 'world_nether;0.5;70.0;0.5;90.0;0.0'
                    safeLoc: 'world:9.5:64.0:3.5:0.0:0.0'
                    kickBack: true
                    cancelWarmup: true
                    particlesByPermission: true
                    requiresPerm: true
                    informOnMissingPerm: false
                    particleOrigin: Bottom
                    particleAmount: 15
                    particleHide: 20
                    activationRange: 6
                    commandsWithoutTp: false
                    commands:
                    - cmi effect [playerName] blindness 2 1 -s
                    - 'asPlayer! spawn'
                    bungeeServer: ''
                    whatIsThis: 3
                  broken:
                    Loc: 'x'
                """.trimIndent(),
            )
        }
        val read = PortalCodec.fromCmi(cmi)
        val p = read.portals.single()
        assertEquals("Spawn_Gate", p.name)
        assertEquals(PortalBox("world", 10, 64, 5, 12, 67, 5), p.box)
        assertEquals(PortalSpot("world_nether", 0.5, 70.0, 0.5, 90f, 0f), p.target)
        assertEquals(PortalSpot("world", 9.5, 64.0, 3.5), p.safe)
        assertTrue(p.permission && p.kickBack && !p.informPermission && !p.commandsWithoutTarget)
        // CMI 기본 명령(cmi effect … blindness 2 1 -s)은 CMI 없이 돌도록 효과 한 줄로 바뀐다.
        assertEquals(listOf("asPlayer! spawn"), p.commands)
        assertEquals(listOf("blindness:2:1"), p.effects)
        assertTrue(read.notes.any { "blindness:2:1" in it })
        assertEquals(PortalCommand(true, "spawn"), PortalRules.parseCommand(p.commands[0]))
        assertEquals(Portal.Particles(true, "PORTAL", null, 15, ParticleOrigin.BOTTOM, 20, 6, false), p.particles)
        assertTrue(read.unreadable.any { "broken" in it }, "영역을 못 읽은 포탈을 보고해야 한다: ${read.unreadable}")
        assertTrue(read.unreadable.any { "whatIsThis" in it }, "모르는 속성을 보고해야 한다: ${read.unreadable}")
        assertTrue(read.notes.any { "cancelWarmup" in it })
    }

    @Test
    fun `CMI 포탈 이름의 점·빈칸은 밑줄로 바꾸고 알린다`() {
        val cmi = YamlConfiguration().apply {
            set("world.a b.Loc", "0:0:0:1:1:1")
        }
        val read = PortalCodec.fromCmi(cmi)
        assertEquals("a_b", read.portals.single().name)
        assertTrue(read.notes.any { "a_b" in it })
    }

    @Test
    fun `있는데 못 읽는 목적지·안전 위치는 못 읽은 칸으로 - 까닭과 함께, 원문 그대로 되써 넣는다`() {
        val yaml = YamlConfiguration().apply {
            set("portals.a.world", "w")
            set("portals.a.area", "0;0;0;1;1;1")
            set("portals.a.target", "spawn;10;64") // 세 칸뿐
            set("portals.b.world", "w")
            set("portals.b.area", "5;0;5;6;1;6")
            set("portals.b.safe", "w,1,2,3")
            set("portals.c.world", "w")
            set("portals.c.area", "9;0;9;9;1;9")
            set("portals.c.target", "")
        }
        val read = PortalCodec.read(yaml)
        assertEquals(listOf("c"), read.portals.map { it.name }) // 빈 목적지는 "없음"
        assertEquals(setOf("a", "b"), read.rejected.keys)
        assertTrue(read.reasons["a"]!!.contains("target"), read.reasons.toString())
        assertTrue(read.reasons["b"]!!.contains("safe"), read.reasons.toString())
        val again = reload(PortalCodec.write(read))
        assertEquals("spawn;10;64", again.getString("portals.a.target"))
        assertEquals("w,1,2,3", again.getString("portals.b.safe"))
    }

    @Test
    fun `CMI 포탈 - 없는 열쇠는 CMI 기본값(알림 끔 · 파티클 10 · 범위 16)`() {
        // PortalManager.save 는 informOnMissingPerm(true 일 때만) · particleAmount(10 아닐 때만) · activationRange(16 아닐 때만)를 기본값이면 쓰지 않는다.
        val cmi = YamlConfiguration().apply {
            loadFromString(
                """
                world:
                  vip:
                    Loc: '0:64:0:1:66:0'
                    world: world
                    Tp: 'world;100;70;100;0;0'
                    requiresPerm: true
                """.trimIndent(),
            )
        }
        val p = PortalCodec.fromCmi(cmi).portals.single()
        assertTrue(p.permission)
        assertEquals(false, p.informPermission)
        assertEquals(PortalCodec.CMI_DEFAULT_PARTICLE_AMOUNT, p.particles.amount)
        assertEquals(PortalCodec.CMI_DEFAULT_ACTIVATION_RANGE, p.particles.range)
    }

    @Test
    fun `CMI 포탈 - 명령에 남는 CMI 변수 · cmi PAPI · 표시 이름 바꿈을 알린다`() {
        val cmi = YamlConfiguration().apply {
            set("world.g.Loc", "0:0:0:1:1:1")
            set("world.g.commands", listOf("broadcast [playerName] 이 [world] 로 갔다", "say %cmi_user_balance%", "say [playerDisplayName]"))
        }
        val read = PortalCodec.fromCmi(cmi)
        assertEquals("broadcast {player} 이 [world] 로 갔다", read.portals.single().commands[0])
        assertTrue(read.notes.any { "[world]" in it }, read.notes.toString())
        assertTrue(read.notes.any { "%cmi_user_balance%" in it }, read.notes.toString())
        assertTrue(read.notes.any { "표시 이름" in it }, read.notes.toString())
    }
}
