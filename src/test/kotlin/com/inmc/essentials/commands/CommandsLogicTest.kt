package com.inmc.essentials.commands

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** commands 기능의 순수 규칙 — 자판 변환 · 설정 · 특수 머리 · 별칭 인자 · 닮음 · 반복 막기 · 쿨타임 · 일정 시각. */
class CommandsLogicTest {

    // --- 자판 변환 ---------------------------------------------------------------------------------

    @Test
    fun `한글 명령을 두벌식 키로 - 연구 시험과 같은 값`() {
        assertEquals("tmvhs", Keyboard.keys("스폰"))
        assertEquals("cnftjr", Keyboard.keys("출석"))
        assertEquals("ditod", Keyboard.keys("야생"))
        assertEquals("dnjvm", Keyboard.keys("워프"))
        assertEquals("tjfwjd", Keyboard.keys("설정"))
        assertEquals("qufcld", Keyboard.keys("별칭"))
    }

    @Test
    fun `한글 자판으로 친 영어 명령 - 넴주와 네ㅁ주 둘 다 spawn`() {
        assertEquals("spawn", Keyboard.keys("넴주"))
        assertEquals("spawn", Keyboard.keys("네ㅁ주"))
        assertEquals("spawn", Keyboard.keys("spawn"))
    }

    @Test
    fun `겹모음·겹받침·쌍자음은 나눠서 - 대문자는 쌍자음`() {
        assertEquals("rhk", Keyboard.keys("과"))
        assertEquals("ekfr", Keyboard.keys("닭"))
        assertEquals("Tkdwkd", Keyboard.keys("쌍장"))
    }

    @Test
    fun `색인 - 없는 이름만 바꾸고 하나일 때만`() {
        val index = KeyboardIndex(listOf("스폰", "spawn", "워프", "홈"))
        assertEquals("스폰", index.convert("tmvhs"))
        assertEquals("spawn", index.convert("넴주"))
        assertNull(index.convert("스폰"), "있는 이름은 그대로")
        assertNull(index.convert("hello"), "맞는 것이 없으면 그대로")
        // 대문자로 쳐도(Caps Lock) 소문자 키로 한 번 더 본다.
        assertEquals("스폰", index.convert("TMVHS"))
    }

    @Test
    fun `같은 키가 둘이면 바꾸지 않고 안내만`() {
        val both = KeyboardIndex(listOf("ghk", "호ㅏ"))
        assertNull(both.convert("화"))
        assertEquals(2, both.ambiguous("화").size)
    }

    @Test
    fun `영타형으로 쓸 수 있는 키`() {
        assertTrue(Keyboard.isTypable("tmvhs"))
        assertFalse(Keyboard.isTypable("스폰"))
        assertFalse(Keyboard.isTypable("tmv-hs"))
        assertFalse(Keyboard.isTypable(""))
    }

    // --- 설정 -----------------------------------------------------------------------------------------

    private fun shipped(): YamlConfiguration = YamlConfiguration.loadConfiguration(File("src/main/resources/settings/commands.yml"))

    @Test
    fun `배포 설정은 기본값과 같고 예제 규칙은 꺼져 있다`() {
        val s = CommandsSettings.from(shipped())
        assertEquals(CommandsSettings(), s)
        assertTrue(s.rules.isEmpty())
    }

    @Test
    fun `규칙 읽기 - 이름 정규화 · 인자 조건 · 움직임 · 그룹 권한의 점`() {
        val y = YamlConfiguration()
        y.loadFromString(
            """
            rules:
              h:
                commands: ['/Home', 'ess:홈']
                args: none
                cooldown: -5
                warmup: 3
                move: '2.5'
                group-cooldowns:
                  group.vip: 30
                  group.mvp: 10
                cost: 100.5
                currency: gold
                commit-on-success: true
              broken:
                commands: []
              off:
                enabled: false
                commands: [x]
            """.trimIndent(),
        )
        val s = CommandsSettings.from(y)
        assertEquals(1, s.rules.size)
        val r = s.rules.single()
        assertEquals(listOf("home", "홈"), r.commands)
        assertEquals(ArgMode.NONE, r.args)
        assertEquals(-1, r.cooldown, "-1 아래는 -1(평생 한 번)")
        assertEquals(2.5, r.move)
        assertEquals(mapOf("group.vip" to 30L, "group.mvp" to 10L), r.groupCooldowns)
        assertEquals(-1, r.cooldownFor { true }, "평생 한 번은 그룹이 덮지 않는다")
        assertTrue(r.commitOnSuccess)
        assertEquals("gold", r.currency)
        assertEquals(listOf(r), s.byCommand["home"])
    }

    @Test
    fun `그룹 쿨타임 - 맞는 것 중 가장 짧은 것이 기본을 덮는다`() {
        val r = CommandRule("x", listOf("x"), cooldown = 100, groupCooldowns = mapOf("a" to 50, "b" to 20))
        assertEquals(100, r.cooldownFor { false })
        assertEquals(50, r.cooldownFor { it == "a" })
        assertEquals(20, r.cooldownFor { true })
    }

    @Test
    fun `움직임 값`() {
        assertEquals(-1.0, CommandsSettings.parseMove("any"))
        assertEquals(-1.0, CommandsSettings.parseMove("true"))
        assertEquals(0.0, CommandsSettings.parseMove("block"))
        assertEquals(0.0, CommandsSettings.parseMove("false"))
        assertEquals(3.0, CommandsSettings.parseMove("3"))
        assertEquals(0.0, CommandsSettings.parseMove("엉망"))
    }

    @Test
    fun `인자 조건`() {
        assertTrue(ArgMode.ANY.accepts(true) && ArgMode.ANY.accepts(false))
        assertTrue(ArgMode.NONE.accepts(false) && !ArgMode.NONE.accepts(true))
        assertTrue(ArgMode.SOME.accepts(true) && !ArgMode.SOME.accepts(false))
    }

    @Test
    fun `배포 이벤트 파일 - 모든 사건이 알려진 이름이고 전부 꺼져 있다`() {
        val y = YamlConfiguration.loadConfiguration(File("src/main/resources/commands/events.yml"))
        val unknown = mutableListOf<String>()
        val rules = CommandsSettings.events(y) { unknown += it }
        assertEquals(emptyList(), unknown)
        assertEquals(EventCommands.ALL.toSet(), rules.keys)
        assertTrue(rules.values.none { it.enabled })
    }

    @Test
    fun `CMI EventCommands 모양도 읽는다 - 대소문자 무시`() {
        val y = YamlConfiguration()
        y.loadFromString(
            """
            playerkillplayer:
              Enabled: true
              Commands: [a]
              SourceCommands: [b]
            sneakingSwapHandItems:
              Enabled: true
              CancelEvent: true
              Commands: [c]
            nope:
              Enabled: true
            """.trimIndent(),
        )
        val unknown = mutableListOf<String>()
        val rules = CommandsSettings.events(y) { unknown += it }
        assertEquals(listOf("nope"), unknown)
        assertEquals(listOf("b"), rules[EventCommands.KILL_PLAYER]?.sourceCommands)
        assertTrue(rules[EventCommands.SNEAK_SWAP]!!.cancel)
    }

    // --- 특수 머리 ------------------------------------------------------------------------------------

    @Test
    fun `머리 여럿을 이어 읽는다`() {
        val p = CommandLines.parse("perm:vip.notice! asConsole! moneycost:10! broadcast! 안녕!")
        assertEquals("vip.notice", p.permission)
        assertTrue(p.asConsole)
        assertEquals(10.0, p.moneyCost)
        assertEquals(CommandLines.Action.BROADCAST, p.action)
        assertEquals("안녕!", p.body)
    }

    @Test
    fun `느낌표가 든 평범한 명령은 그대로`() {
        val p = CommandLines.parse("/say 안녕!")
        assertEquals(CommandLines.Action.COMMAND, p.action)
        assertEquals("say 안녕!", p.body)
        assertNull(p.permission)
    }

    @Test
    fun `delay 와 나누기`() {
        assertEquals(0.3, CommandLines.delay("delay! 0.3"))
        assertEquals(5.0, CommandLines.delay("  Delay!5 "))
        assertNull(CommandLines.delay("delay! 다섯"))
        assertEquals(listOf("a", "b", "c"), CommandLines.split(listOf("a;;b", " ", "c")))
    }

    @Test
    fun `rand 와 변수`() {
        val random = Random(1)
        repeat(50) {
            val n = CommandLines.rand("%rand/1-5%", random).toInt()
            assertTrue(n in 1..5)
        }
        assertEquals("give Steve 3", CommandLines.variables("give [PlayerName] [n]", mapOf("playerName" to "Steve", "n" to "3")))
    }

    // --- 별칭 -----------------------------------------------------------------------------------------

    @Test
    fun `별칭 인자 넣기`() {
        assertEquals(listOf("tppos 1 2 3"), AliasDef.expand(listOf("tppos $1-"), "a", listOf("1", "2", "3")))
        assertEquals(listOf("msg Steve 안녕 하세요"), AliasDef.expand(listOf("msg $1 $2-"), "a", listOf("Steve", "안녕", "하세요")))
        assertEquals(listOf("heal"), AliasDef.expand(listOf("heal $1-"), "a", emptyList()), "\$1- 는 비어도 된다")
        assertNull(AliasDef.expand(listOf("heal $1"), "a", emptyList()), "\$1 이 없으면 사용법")
        assertEquals(listOf("echo h"), AliasDef.expand(listOf("echo $0"), "h", emptyList()))
        assertEquals(listOf("a 10"), AliasDef.expand(listOf("a $10"), "x", (1..10).map { it.toString() }))
    }

    @Test
    fun `별칭 맞추기 - 글자가 와일드보다 먼저 · exact`() {
        val sub = AliasDef("스폰 설정", listOf("x"))
        val any = AliasDef("스폰 ?", listOf("y"))
        val exact = AliasDef("밥", listOf("z"), exact = true)
        val tokens = listOf("스폰", "설정")
        assertTrue(sub.match(tokens) > any.match(tokens))
        assertEquals(-1, sub.match(listOf("스폰")))
        assertEquals(-1, exact.match(listOf("밥", "더")))
        assertTrue(exact.match(listOf("밥")) > 0)
        assertFalse(any.single)
        assertTrue(exact.single)
    }

    @Test
    fun `별칭 권한 노드와 이름 규칙`() {
        assertNull(AliasDef("a", listOf("x")).node())
        assertEquals("${CommandsFeature.ALIAS_NODE}.밥", AliasDef("밥 ?", listOf("x"), permission = "true").node())
        assertEquals("my.node", AliasDef("a", listOf("x"), permission = "my.node").node())
        assertNotNull(AliasDef.nameProblem("a.b"))
        assertNotNull(AliasDef.nameProblem("ns:a"))
        assertNull(AliasDef.nameProblem("스폰 설정"))
    }

    @Test
    fun `별칭 파일 왕복`() {
        val defs = listOf(AliasDef("밥", listOf("배고픔", "delay! 1", "msg! [playerName] 냠"), "true", "설명", listOf("[player]"), tab = false, exact = true, enabled = false, usage = "/밥"))
        val text = AliasDef.write(defs).saveToString()
        val back = AliasDef.read(YamlConfiguration().apply { loadFromString(text) })
        assertEquals(defs, back)
    }

    @Test
    fun `배포 별칭 파일은 읽힌다`() {
        val problems = mutableListOf<String>()
        val defs = AliasDef.read(YamlConfiguration.loadConfiguration(File("src/main/resources/commands/aliases.yml"))) { problems += it }
        assertEquals(emptyList(), problems)
        assertTrue(defs.isNotEmpty() && defs.none { it.enabled })
    }

    // --- 닮음 · 반복 막기 · 쿨타임 ---------------------------------------------------------------------------

    @Test
    fun `닮음 - CMI 예 spawnmb 와 spawnmob`() {
        assertTrue(Similarity.percent("spawnmb", "spawnmob") >= 75)
        assertEquals(100, Similarity.percent("a", "a"))
        assertEquals("spawnmob", Similarity.best("spawnmb", listOf("spawn", "spawnmob", "warp"), 75))
        assertNull(Similarity.best("zzz", listOf("spawn"), 75))
        assertNull(Similarity.best("spawn", listOf("spawn"), 75), "같은 이름은 추천하지 않는다")
    }

    @Test
    fun `반복 막기 - 창 안의 세 번째를 막고 창이 지나면 풀린다`() {
        val w = SpamWindow(5000, 80, 2)
        assertFalse(w.check("/home a", 0))
        assertFalse(w.check("/home a", 1000))
        assertTrue(w.check("/home a", 2000))
        assertTrue(w.check("/home b", 2500), "80% 이상 닮으면 같은 명령")
        assertFalse(w.check("/home a", 7000))
    }

    @Test
    fun `쿨타임 셈`() {
        assertNull(CooldownMath.remaining(null, 1000, 60))
        assertNull(CooldownMath.remaining(0, 1000, 0))
        assertEquals(59_000, CooldownMath.remaining(0, 1000, 60))
        assertNull(CooldownMath.remaining(0, 61_000, 60))
        assertEquals(Long.MAX_VALUE, CooldownMath.remaining(0, 999_999_999, -1))
    }

    // --- 일정 -----------------------------------------------------------------------------------------

    @Test
    fun `시각 맞추기 - 빠진 시는 매시 · 분초는 0`() {
        val frame = TimeFrame.from(mapOf("Hour" to 18))!!
        assertTrue(frame.matches(LocalDateTime.of(2026, 10, 10, 18, 0, 0)))
        assertFalse(frame.matches(LocalDateTime.of(2026, 10, 10, 18, 0, 1)))
        val everyHalf = TimeFrame.from(mapOf("minute" to 30))!!
        assertTrue(everyHalf.matches(LocalDateTime.of(2026, 10, 10, 3, 30, 0)))
        assertTrue(everyHalf.matches(LocalDateTime.of(2026, 10, 10, 21, 30, 0)))
    }

    @Test
    fun `요일 이름과 그 달의 첫·마지막 요일`() {
        assertEquals(DayOfWeek.MONDAY, TimeFrame.weekday("monday"))
        assertEquals(DayOfWeek.FRIDAY, TimeFrame.weekday("금요일"))
        assertEquals(DayOfWeek.SUNDAY, TimeFrame.weekday("일"))
        val lastFriday = TimeFrame.from(mapOf("LastMonthDay" to "friday", "Hour" to 18))!!
        assertTrue(lastFriday.matches(LocalDateTime.of(2026, 10, 30, 18, 0, 0))) // 2026-10-30 은 금요일, 그 달의 마지막
        assertFalse(lastFriday.matches(LocalDateTime.of(2026, 10, 23, 18, 0, 0)))
        val firstTue = TimeFrame.from(mapOf("FirstMonthDay" to "tuesday", "Hour" to 12, "Minute" to 30))!!
        assertTrue(firstTue.matches(LocalDateTime.of(2026, 10, 6, 12, 30, 0)))
        assertNull(TimeFrame.from(mapOf("Day" to "어느날")), "못 읽은 요일은 버린다")
    }

    @Test
    fun `CMI Schedules 예제를 그대로 읽는다`() {
        val y = YamlConfiguration()
        y.loadFromString(
            """
            AllInOneJustExample:
              Enabled: false
              MinPlayers: 3
              MaxPlayers: 10
              FeedBack: false
              Delay: 600
              Repeat: true
              PerformOn:
              - Month: 12
                Day: monday
                Hour: 18
                Minute: 36
                Second: 15
              - Hour: 18
              - LastMonthDay: friday
                Hour: 18
              Commands:
              - cmi give [randomPlayer] diamond %rand/1-5%
            Broken:
              Enabled: true
              Commands: [a]
            """.trimIndent(),
        )
        val problems = mutableListOf<String>()
        val list = Schedule.readAll(y) { problems += it }
        assertEquals(1, list.size)
        val s = list.single()
        assertEquals(3, s.frames.size)
        assertEquals(DayOfWeek.MONDAY, s.frames[0].weekday)
        assertEquals(3, s.minPlayers)
        assertFalse(s.feedback)
        assertTrue(s.timed)
        assertEquals(1, problems.size, "Delay 도 시각도 없는 일정은 알린다")
    }

    @Test
    fun `배포 일정 파일은 문제 없이 읽히고 전부 꺼져 있다`() {
        val problems = mutableListOf<String>()
        val list = Schedule.readAll(YamlConfiguration.loadConfiguration(File("src/main/resources/commands/schedules.yml"))) { problems += it }
        assertEquals(emptyList(), problems)
        assertTrue(list.isNotEmpty() && list.none { it.enabled })
    }
}
