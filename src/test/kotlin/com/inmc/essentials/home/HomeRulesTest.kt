package com.inmc.essentials.home

import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 홈의 순수 규칙 — 한도(그룹·숫자·무제한) · 월드 한도 · 이름 · 기본 홈 · 화면 자리 · 명령 꼬리 · 설정 읽기. */
class HomeRulesTest {

    private val s = HomeSettings()
    private val owner = UUID(0, 1)

    private fun home(name: String, created: Long, bed: Boolean = false, world: String = "world") =
        Home(owner, name, world, 0.0, 64.0, 0.0, created = created, bed = bed)

    @Test
    fun `한도 - 그룹 권한이 없으면 기본, 있으면 큰 쪽, 숫자 권한도`() {
        val p = HomeRules.LIMIT_PREFIX
        assertEquals(1, HomeRules.limit(emptyList(), false, s))
        assertEquals(3, HomeRules.limit(listOf("${p}newbie", "${p}advanced"), false, s))
        assertEquals(10, HomeRules.limit(listOf("${p}vip", "${p}10"), false, s))
        assertEquals(1, HomeRules.limit(listOf("${p}unknown"), false, s))
        assertNull(HomeRules.limit(listOf("${p}vip"), true, s))
        // 그룹이 기본보다 작아도 그룹 값(CMI 와 같다 — 기본은 '그룹이 없을 때').
        val small = s.copy(defaultLimit = 3, groups = mapOf("trial" to 1))
        assertEquals(1, HomeRules.limit(listOf("${p}trial"), false, small))
    }

    @Test
    fun `한도에 침대 홈은 설정에 따라 센다`() {
        val homes = listOf(home("a", 1), home("침대", 2, bed = true))
        assertEquals(1, HomeRules.counted(homes, s))
        assertEquals(2, HomeRules.counted(homes, s.copy(bedCountsTowardLimit = true)))
    }

    @Test
    fun `월드 묶음 - 대소문자 무시`() {
        val settings = s.copy(worldGroups = listOf(WorldGroup("nether", 0, setOf("world_nether")), WorldGroup("res", 3, setOf("Res1", "res2"))))
        assertEquals("nether", HomeRules.worldGroup("WORLD_NETHER", settings)?.name)
        assertEquals(3, HomeRules.worldGroup("res1", settings)?.amount)
        assertNull(HomeRules.worldGroup("world", settings))
    }

    @Test
    fun `이름 - 한글 영문 숫자 밑줄 붙임표, 길이`() {
        assertNull(HomeRules.nameProblem("집", s))
        assertNull(HomeRules.nameProblem("my_home-2", s))
        assertEquals("homes-invalid-name", HomeRules.nameProblem("a.b", s))
        assertEquals("homes-invalid-name", HomeRules.nameProblem("<red>", s))
        assertEquals("homes-name-too-long", HomeRules.nameProblem("a".repeat(17), s))
        assertEquals("homes-invalid-name", HomeRules.nameProblem(" ", s))
    }

    @Test
    fun `기본 홈 - 기본 이름 먼저, 없으면 먼저 만든 홈, 침대 홈은 빼고`() {
        val homes = listOf(home("광산", 5), home("농장", 3), home("침대", 1, bed = true))
        assertEquals("농장", HomeRules.pickDefault(homes, "집")?.name)
        assertEquals("집", HomeRules.pickDefault(homes + home("집", 9), "집")?.name)
        assertEquals("Home", HomeRules.pickDefault(listOf(home("Home", 9), home("a", 1)), "home")?.name)
        assertNull(HomeRules.pickDefault(listOf(home("침대", 1, bed = true)), "집"))
    }

    @Test
    fun `목록 순서 - 침대 홈 먼저, 그다음 만든 순서`() {
        val sorted = HomeRules.sorted(listOf(home("b", 2), home("침대", 9, bed = true), home("a", 1)))
        assertEquals(listOf("침대", "a", "b"), sorted.map { it.name })
    }

    @Test
    fun `화면 자리 - 정한 칸 먼저, 겹치면 먼저 온 것, 나머지는 빈 칸을 앞에서부터`() {
        data class Item(val id: String, val slot: Int?)
        val layout = HomeRules.arrange(listOf(Item("a", null), Item("b", 2), Item("c", 2), Item("d", 50), Item("e", null))) { it.slot }
        assertEquals(mapOf(1 to "b", 49 to "d", 0 to "a", 2 to "c", 3 to "e"), layout.mapValues { it.value.id })
    }

    @Test
    fun `홈설정 꼬리 읽기`() {
        assertEquals(SetRequest(name = "집"), HomeArgs.parseSet(listOf("집")))
        assertEquals(SetRequest(), HomeArgs.parseSet(emptyList()))
        assertEquals(
            SetRequest(name = "광산", private = true, overwrite = true, slot = 7, icon = "diamond_pickaxe", owner = "Steve", location = "world;1;2;3"),
            HomeArgs.parseSet(listOf("광산", "비공개", "7", "diamond_pickaxe", "덮어쓰기", "주인:Steve", "위치:world;1;2;3")),
        )
        assertEquals(SetRequest(name = "a", private = true, owner = "Alex"), HomeArgs.parseSet(listOf("a", "-p", "-p:Alex")))
        // 첫 낱말이 숫자여도 이름이다.
        assertEquals(SetRequest(name = "1", slot = 2), HomeArgs.parseSet(listOf("1", "2")))
    }

    @Test
    fun `홈 이동 꼬리 읽기`() {
        assertEquals(Triple("집", null, null), HomeArgs.parseGo(listOf("집")))
        assertEquals(Triple("집", "Steve", null), HomeArgs.parseGo(listOf("집", "Steve")))
        assertEquals(Triple("집", "Steve", "Alex"), HomeArgs.parseGo(listOf("집", "Steve", "Alex")))
        assertEquals(Triple("집", "Steve", null), HomeArgs.parseGo(listOf("Steve:집")))
        assertNull(HomeArgs.parseGo(emptyList()))
        assertEquals(listOf("world", "1", "2.5", "3"), HomeArgs.parseLocation("world;1;2.5;3"))
        assertNull(HomeArgs.parseLocation("world;1;x;3"))
    }

    @Test
    fun `설정 읽기 - 이상한 값은 기본값`() {
        val y = YamlConfiguration().apply {
            loadFromString(
                """
                name-pattern: '[unclosed'
                name-max-length: 0
                limits:
                  default: -5
                  groups: { VIP: 7 }
                world-limits:
                  use: true
                  groups:
                    a: { amount: 0, worlds: [world_nether] }
                    b: { amount: -1, worlds: [x] }
                    c: { amount: 2, worlds: [] }
                """.trimIndent(),
            )
        }
        val r = HomeSettings.from(y)
        assertEquals(HomeSettings.DEFAULT_PATTERN.pattern, r.namePattern.pattern)
        assertEquals(1, r.nameMaxLength)
        assertEquals(0, r.defaultLimit)
        assertEquals(mapOf("vip" to 7), r.groups)
        assertEquals(listOf(WorldGroup("a", 0, setOf("world_nether"))), r.worldGroups)
        val d = HomeSettings.from(YamlConfiguration())
        assertEquals(HomeSettings().copy(namePattern = d.namePattern), d)
    }
}
