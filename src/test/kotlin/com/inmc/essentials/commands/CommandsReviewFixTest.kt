package com.inmc.essentials.commands

import com.inmc.essentials.service.ImportReport
import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 검토에서 나온 것 — 별칭 인자 끼워 넣기 · 자기 부르기 · [randomPlayer] 한 번 · CMI 별칭·이벤트·일정 가져오기. */
class CommandsReviewFixTest {

    private fun lines(steps: List<CommandLines.Step>?): List<CommandLines.Parsed> =
        assertNotNull(steps).filterIsInstance<CommandLines.Step.Line>().map { it.parsed }

    // --- 별칭 인자는 나누고 머리를 읽은 뒤에 ---------------------------------------------------------------

    @Test
    fun `인자 속 두 쌍반점은 새 줄이 되지 않는다`() {
        val out = lines(AliasDef.steps(listOf("msg \$1-"), "귓", listOf("a;;asConsole!", "op", "x")))
        assertEquals(1, out.size)
        assertEquals(CommandLines.Action.COMMAND, out[0].action)
        assertFalse(out[0].asConsole)
        assertEquals("msg a;asConsole! op x", out[0].body)
    }

    @Test
    fun `인자 하나로 머리를 끼워 넣을 수 없다`() {
        val out = lines(AliasDef.steps(listOf("\$1-"), "x", listOf("asConsole!", "op", "me")))
        assertEquals(1, out.size)
        assertFalse(out[0].asConsole, "템플릿의 머리만 읽는다")
        assertEquals("asConsole! op me", out[0].body, "사람으로 그 글자 그대로 친 것과 같다")
        val kick = lines(AliasDef.steps(listOf("tp \$1 \$2"), "t", listOf(";;kickall!", "x")))
        assertEquals(1, kick.size)
        assertEquals(CommandLines.Action.COMMAND, kick[0].action)
    }

    @Test
    fun `템플릿의 머리와 delay 는 그대로 - 인자는 본문에만`() {
        val steps = assertNotNull(AliasDef.steps(listOf("asConsole! say \$1", "delay! 2", "broadcast! <b>\$1-</b>"), "a", listOf("<click:run_command:/op me>hi")))
        assertEquals(3, steps.size)
        val first = (steps[0] as CommandLines.Step.Line).parsed
        assertTrue(first.asConsole)
        assertEquals("say <click:run_command:/op me>hi", first.body, "명령 줄의 인자는 글자 그대로")
        assertEquals(2.0, (steps[1] as CommandLines.Step.Wait).seconds)
        val third = (steps[2] as CommandLines.Step.Line).parsed
        assertEquals(CommandLines.Action.BROADCAST, third.action)
        assertEquals("<b>\\<click:run_command:/op me>hi</b>", third.body, "방송 줄의 인자 태그는 글자로")
    }

    @Test
    fun `인자가 모자라면 null - 템플릿 함수는 인자 넣기 전 본문에만`() {
        assertNull(AliasDef.steps(listOf("heal \$1"), "h", emptyList()))
        val seen = mutableListOf<String>()
        val out = lines(AliasDef.steps(listOf("msg %a% \$1"), "m", listOf("%b%")) { seen += it; it.replace("%a%", "A") })
        assertEquals(listOf("msg %a% \$1"), seen)
        assertEquals("msg A %b%", out[0].body, "친 사람의 %…% 는 풀리지 않는다")
    }

    @Test
    fun `값 속의 달러 숫자는 다시 풀리지 않는다`() {
        assertEquals("say \$2 y", AliasDef.expandLine("say \$1 \$2", "s", listOf("\$2", "y")))
    }

    // --- 자기 부르기 --------------------------------------------------------------------------------

    @Test
    fun `자기 자신을 부르는 별칭`() {
        assertTrue(AliasDef("공지", listOf("broadcast! 안녕", "delay! 1", "공지")).selfCalls())
        assertTrue(AliasDef("warp", listOf("warp \$1")).selfCalls(), "같은 이름을 덮어 다시 부르면 고리")
        assertTrue(AliasDef("스폰 설정", listOf("asConsole! 스폰 설정 world")).selfCalls())
        assertFalse(AliasDef("warp", listOf("inmc-essentials:warp \$1")).selfCalls(), "접두 이름은 원래 명령")
        assertFalse(AliasDef("warp", listOf("warp hub"), exact = true).selfCalls(), "exact 는 이름만 쳤을 때만 — 인자가 붙은 줄은 원래 명령")
        assertFalse(AliasDef("h", listOf("msg! [playerName] h", "heal")).selfCalls())
    }

    // --- [randomPlayer] 한 번 ---------------------------------------------------------------------------

    @Test
    fun `한 번 돌릴 때 randomPlayer 는 한 번만 고른다`() {
        val steps = CommandLines.steps(listOf("give [randomPlayer] diamond 1", "delay! 1", "msg! [randomPlayer] 받았다"))
        var calls = 0
        val values = CommandRunner.withRandom(emptyMap(), steps) { calls++; "A${calls}" }
        assertEquals(1, calls)
        val texts = steps.filterIsInstance<CommandLines.Step.Line>().map { CommandLines.variables(it.parsed.body, values) }
        assertEquals(listOf("give A1 diamond 1", "A1 받았다"), texts)
        assertEquals(mapOf("randomPlayer" to "X"), CommandRunner.withRandom(mapOf("randomPlayer" to "X"), steps) { error("다시 고르면 안 된다") })
        assertEquals(emptyMap(), CommandRunner.withRandom(emptyMap(), CommandLines.steps(listOf("say hi"))) { error("안 쓰면 안 고른다") })
    }

    // --- CMI 가져오기 -----------------------------------------------------------------------------------

    private fun yaml(text: String) = YamlConfiguration().apply { loadFromString(text) }

    @Test
    fun `CMI 별칭 - 이름이 나쁜 것은 버리고 꺼진 것·cmi 명령·자기 부르기는 꺼 둔다`() {
        val y = yaml(
            """
            CustomAlias:
              a.b:
                Cmds: [say hi]
              spawn:
                Enabled: false
                Cmds: [warp hub]
              heal:
                Cmds: ['cmi heal ${'$'}1']
                Perm: true
              loop:
                Cmds: [loop]
              ok:
                Cmds: ['/say ok']
                AddTabs: true
                BaseDisabled: true
                ExactMatch: true
            """.trimIndent(),
        )
        // 점이 든 키 `a.b` 는 YAML 경로(a → b)로 읽혀 `a` 가 빈 섹션으로 남는다 — Cmds 가 비어 "못 읽음" 이 되고 넣지 않는다(점이 든 이름 자체는 아래 시험).
        y.set("CustomAlias.a.b", null)
        val report = ImportReport()
        val out = CmiCommandImport.aliases(y, report).associateBy { it.name }
        assertFalse("a" in out, "나쁜 이름은 넣지 않는다")
        assertEquals(false, out["spawn"]?.enabled, "CMI 에서 꺼진 것은 꺼 둔다")
        assertEquals(false, out["heal"]?.enabled, "cmi 명령을 부르면 꺼 둔다")
        assertEquals("true", out["heal"]?.permission)
        assertEquals(false, out["loop"]?.enabled, "자기 부르기는 꺼 둔다")
        assertEquals(true, out["ok"]?.enabled)
        assertEquals(true, out["ok"]?.exact)
        assertEquals(listOf("say ok"), out["ok"]?.commands)
        val details = report.details()
        assertTrue(details.any { "AddTabs" in it } && details.any { "BaseDisabled" in it }, "옮기지 않는 키는 알린다: $details")
    }

    @Test
    fun `CMI 별칭 - 점이 든 이름은 못 읽음으로만`() {
        val y = YamlConfiguration()
        // 점이 든 이름은 경로 구분자를 바꿔 넣는다(CMI 파일에서 읽으면 이렇게 들어온다).
        y.options().pathSeparator('|')
        y.set("CustomAlias|a.b|Cmds", listOf("say hi"))
        val report = ImportReport()
        val out = CmiCommandImport.aliases(y, report)
        assertTrue(out.isEmpty(), "나쁜 이름은 넣지 않는다: $out")
        assertTrue(report.details().any { it.startsWith("못 읽음") && "a.b" in it })
    }

    @Test
    fun `CMI 이벤트 명령 - CMI 가 켜져 있으면 꺼 두고 이름을 돌려준다`() {
        val y = yaml(
            """
            playerKillPlayer:
              Enabled: true
              Commands: ['eco give [sourceName] 50']
            joinServer:
              Enabled: true
              Commands: ['asConsole! cmi heal [playerName]']
            nosuchEvent:
              Enabled: true
              Commands: [say x]
            quitServer:
              Enabled: false
              Commands: [say bye]
            """.trimIndent(),
        )
        val running = CmiCommandImport.events(y, { false }, cmiRunning = true, ImportReport())
        val byId = running.items.associateBy { it.id }
        assertEquals(false, byId[EventCommands.KILL_PLAYER]?.enabled)
        assertEquals(false, byId[EventCommands.JOIN]?.enabled)
        assertEquals(listOf(EventCommands.KILL_PLAYER), running.heldBack, "cmi 명령이라 꺼진 것은 따로 알린다")
        assertFalse(EventCommands.QUIT in byId, "CMI 에서 꺼진 것은 건너뜀")
        val after = CmiCommandImport.events(y, { false }, cmiRunning = false, ImportReport())
        assertEquals(true, after.items.first { it.id == EventCommands.KILL_PLAYER }.enabled)
        assertTrue(after.heldBack.isEmpty())
        val skip = CmiCommandImport.events(y, { it == EventCommands.KILL_PLAYER }, cmiRunning = false, ImportReport())
        assertTrue(skip.items.none { it.id == EventCommands.KILL_PLAYER }, "이미 켜진 사건은 건너뜀")
    }

    @Test
    fun `CMI 일정 - CMI 가 켜져 있으면 켜진 일정도 꺼 둔다`() {
        val y = yaml(
            """
            hourly:
              Enabled: true
              Delay: 3600
              Commands: ['asConsole! give [randomPlayer] diamond 1']
            cmiOne:
              Enabled: true
              Delay: 60
              Commands: ['cmi broadcast hi']
            off:
              Enabled: false
              Delay: 60
              Commands: [say x]
            broken:
              Enabled: true
              Commands: [say x]
            """.trimIndent(),
        )
        val report = ImportReport()
        val r = CmiCommandImport.schedules(y, cmiRunning = true, report)
        assertEquals(false, r.items["hourly"]?.get("Enabled"))
        assertEquals(false, r.items["cmiOne"]?.get("Enabled"))
        assertEquals(listOf("hourly"), r.heldBack)
        assertFalse("broken" in r.items, "Delay·PerformOn 이 없으면 못 읽음")
        assertTrue(report.details().any { it.startsWith("못 읽음") && "broken" in it })
        val free = CmiCommandImport.schedules(y, cmiRunning = false, ImportReport())
        assertEquals(true, free.items["hourly"]?.get("Enabled"))
    }

    @Test
    fun `cmi 명령 알아보기 - 머리를 떼고 본다`() {
        assertTrue(CmiCommandImport.callsCmi(listOf("asConsole! cmi heal x")))
        assertTrue(CmiCommandImport.callsCmi(listOf("say a;;cmi tp x")))
        assertFalse(CmiCommandImport.callsCmi(listOf("cmiextra x", "msg! x cmi 는 좋다")))
    }
}
