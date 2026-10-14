package com.inmc.essentials.server

import com.inmc.essentials.service.CmiLocation
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** server 기능의 순수 규칙 — CMI 형식 읽기 · 커스텀 텍스트 · CMI 표식 · TPS 셈 · 설정 · 로그 가리기. */
class ServerLogicTest {

    // --- CMI 위치 -----------------------------------------------------------------------------------

    @Test
    fun `DB 위치는 pitch 다음 yaw - 테섭 표본`() {
        val loc = CmiFormats.location("spawn:-24.14:81.0:162.8:15.34:-100.95", CmiFormats.Order.PITCH_YAW)!!
        assertEquals(CmiLocation("spawn", -24.14, 81.0, 162.8, yaw = -100.95f, pitch = 15.34f), loc)
        // 둘 다 ±90 안이면 열의 순서대로(DB = pitch:yaw)
        val small = CmiFormats.location("world:0.07:85.0:-2.68:6.89:-56.1", CmiFormats.Order.PITCH_YAW)!!
        assertEquals(-56.1f, small.yaw)
        assertEquals(6.89f, small.pitch)
    }

    @Test
    fun `config 위치는 세미콜론 yaw 다음 pitch`() {
        val loc = CmiFormats.location("spawn;-3.53;84;163.54;90.15;-0.14", CmiFormats.Order.YAW_PITCH)!!
        assertEquals(90.15f, loc.yaw)
        assertEquals(-0.14f, loc.pitch)
        assertEquals(84.0, loc.y)
    }

    @Test
    fun `위치 - 방향 없는 네 칸 · 소수점 쉼표 · 망가진 것은 던진다`() {
        assertEquals(CmiLocation("w", 1.5, 2.0, 3.0), CmiFormats.location("w;1,5;2;3", CmiFormats.Order.YAW_PITCH))
        assertNull(CmiFormats.location("  ", CmiFormats.Order.YAW_PITCH))
        assertFailsWith<CmiFormats.Bad> { CmiFormats.location("w:1:2", CmiFormats.Order.PITCH_YAW) }
        assertFailsWith<CmiFormats.Bad> { CmiFormats.location("w:a:2:3", CmiFormats.Order.PITCH_YAW) }
    }

    // --- 홈 · 우편 · 킷 · 그 밖 ---------------------------------------------------------------------------

    @Test
    fun `홈 JSON - 위치 다음 표식 · 옛 형식 · 못 읽은 것은 알린다`() {
        val problems = mutableListOf<String>()
        val homes = CmiFormats.homes(
            """{"집":["world:1.0:64.0:2.0:10.0:20.0","p",3,"STONE","f"],"광산":"mine:5:6:7:0:0","옛집${'$'}-p${'$'}-2${'$'}-OAK_LOG":"world:9:9:9","망가짐":["x"]}""",
        ) { problems += it }
        assertEquals(listOf("집", "광산", "옛집"), homes.map { it.name })
        val home = homes[0]
        assertTrue(home.private)
        assertEquals("STONE", home.icon)
        assertEquals(10f, home.location.yaw, "홈 안 위치는 yaw 다음 pitch")
        assertTrue(homes[2].private)
        assertEquals("OAK_LOG", homes[2].icon)
        assertEquals(1, problems.size)
        assertTrue(problems[0].contains("망가짐"))
    }

    @Test
    fun `우편 - JSON 목록과 옛 콜론 형식`() {
        val problems = mutableListOf<String>()
        val mail = CmiFormats.mail("""[["Steve","1700000000000","안녕","0"],["Alex","x","시각 이상"],["only"],["Tmp","1700000000000","임시","604800"],["Bad","1","글","x"]]""") { problems += it }
        assertEquals(4, mail.size)
        assertEquals("Steve", mail[0].sender)
        assertEquals(1700000000000, mail[0].time)
        assertNull(mail[0].keepForSeconds) // "0" = CMI 기본 만료
        assertNull(mail[1].time)
        assertNull(mail[1].keepForSeconds)
        assertEquals(604800L, mail[2].keepForSeconds) // sendtemp 의 보관 초
        assertNull(mail[3].keepForSeconds) // 숫자가 아니면 알리고 기본 만료
        assertEquals(2, problems.size)
        assertTrue(problems.any { it.contains("보관 기간") })
        val old = CmiFormats.mail("Steve:1:안녕:하세요") {}
        assertEquals("안녕:하세요", old.single().message)
    }

    @Test
    fun `킷 사용 - 테섭 표본`() {
        val kits = CmiFormats.kits("""{"Advanced":{"t":1791380718546,"u":1},"Ad2":{"t":1791380716221,"u":3},"Newbie":{"t":1791380717110,"u":1}}""") {}
        assertEquals(mapOf("Advanced" to 1791380718546, "Ad2" to 1791380716221, "Newbie" to 1791380717110), kits)
        val problems = mutableListOf<String>()
        assertEquals(mapOf("A" to 5L), CmiFormats.kits("A-X-5;B-X-?") { problems += it })
        assertEquals(1, problems.size)
    }

    @Test
    fun `무시 · 뮤트 · 감옥 · 메타 · 남은 시간`() {
        val a = UUID.randomUUID()
        val problems = mutableListOf<String>()
        assertEquals(listOf(a), CmiFormats.ignores("$a;nope") { problems += it })
        assertEquals(1, problems.size)
        assertNull(CmiFormats.mute("1:끝남"), "이미 풀린 뮤트")
        val forever = CmiFormats.mute("0:욕설")!!
        assertNull(forever.until)
        assertEquals("욕설", forever.reason)
        assertFailsWith<CmiFormats.Bad> { CmiFormats.mute("x:y") }
        val jail = CmiFormats.jail("main:2:$a", 99L, "탈옥")!!
        assertEquals("main", jail.jail)
        assertEquals(2, jail.cell)
        assertEquals(mapOf("k" to "v", "n" to "3"), CmiFormats.meta("""{"k":"v","n":3}"""))
        assertEquals(listOf("a", "b"), CmiFormats.list("""["a","b"]"""))
        assertEquals(listOf("a", "b"), CmiFormats.list("a;;b"))
        assertEquals(60, CmiFormats.remainingSeconds(1_700_000_060_000, 1_700_000_000_000))
        assertNull(CmiFormats.remainingSeconds(1_600_000_000_000, 1_700_000_000_000))
        assertEquals(300, CmiFormats.remainingSeconds(300, 1_700_000_000_000))
    }

    // --- 커스텀 텍스트 -----------------------------------------------------------------------------------

    @Test
    fun `CMI rules 모양 - 표식 줄 · 쪽 나눔 · 자동 쪽`() {
        val text = "<AutoPage>\n&6규칙\n&e1\n&e2\n&e3\n<NextPage>\n둘째\n\n"
        val doc = TextDoc.parse("rules", text, 2)
        assertEquals(listOf(listOf("&6규칙", "&e1"), listOf("&e2", "&e3"), listOf("둘째")), doc.pages)
        val flags = TextDoc.parse("x", "<Hidden>\n<ReqPermission>\n<AutoAlias>\n글", 10)
        assertTrue(flags.hidden && flags.requiresPermission && flags.autoAlias)
        assertEquals(listOf(listOf("글")), flags.pages)
    }

    @Test
    fun `텍스트 이름 규칙`() {
        assertTrue(TextDoc.validName("rules"))
        assertTrue(TextDoc.validName("규칙"))
        assertFalse(TextDoc.validName("../x"))
        assertFalse(TextDoc.validName("a b"))
        assertFalse(TextDoc.validName("a.txt"))
    }

    @Test
    fun `CMI 채팅 표식을 MiniMessage 로`() {
        assertEquals(
            "<hover:show_text:'보기'><click:run_command:'/rules'>규칙</click></hover>",
            CmiText.toMiniMessage("<T>규칙</T><H>보기</H><C>rules</C>"),
        )
        assertEquals("앞 <click:suggest_command:'/msg '>귓말</click> 뒤", CmiText.toMiniMessage("앞 <T>귓말</T><SC>/msg </SC> 뒤"))
        assertEquals("<click:open_url:'https://a.b'>주소</click>", CmiText.toMiniMessage("<T>주소</T><URL>https://a.b</URL>"))
        assertEquals("콘솔", CmiText.toMiniMessage("<T>콘솔</T><CC>op x</CC>"), "콘솔 명령 표식은 버린다")
        assertEquals("<#ff0000>빨강", CmiText.toMiniMessage("{#ff0000}빨강"))
        assertEquals("<hover:show_text:'it\\'s'>a</hover>", CmiText.toMiniMessage("<T>a</T><H>it's</H>"))
    }

    @Test
    fun `배포 텍스트는 읽힌다`() {
        for (name in TextService.DEFAULTS) {
            val doc = TextDoc.parse(name, File("src/main/resources/texts/$name.txt").readText(), 10)
            assertTrue(doc.pages.first().isNotEmpty(), "$name 이 비었습니다")
        }
    }

    // --- TPS ----------------------------------------------------------------------------------------

    @Test
    fun `TPS 창 - 1초마다 20틱이면 20 · 느려진 구간`() {
        val s = TpsSampler(10)
        assertNull(s.tps(3))
        var tick = 0L
        for (i in 0..5) {
            s.record(i * 1000L, tick)
            tick += if (i == 3) 10 else 20
        }
        assertEquals(20.0, s.tps(1))
        val per = s.perSecond(5)
        assertEquals(5, per.size)
        assertEquals(10.0, per.minOf { it.second })
        // 고리 버퍼가 넘쳐도 최근 것만
        for (i in 6..30) {
            s.record(i * 1000L, tick)
            tick += 20
        }
        assertEquals(20.0, s.tps(600))
    }

    // --- 설정 · 로그 ------------------------------------------------------------------------------------

    @Test
    fun `배포 설정은 코드의 기본값과 같다`() {
        assertEquals(ServerSettings(), ServerSettings.from(YamlConfiguration.loadConfiguration(File("src/main/resources/settings/server.yml"))))
    }

    @Test
    fun `상태 파일 왕복`() {
        val s = ServerState(true, "정기 점검", listOf("a", "b"), 50)
        assertEquals(s, ServerState.from(YamlConfiguration().apply { loadFromString(s.toYaml()) }))
        assertEquals(ServerState(), ServerState.from(YamlConfiguration()))
    }

    @Test
    fun `로그 - 비밀번호 명령은 인자를 가린다`() {
        val hidden = setOf("login", "register")
        assertEquals("/login ***", Logs.mask("/login 1234", hidden))
        assertEquals("/authme:login ***", Logs.mask("/authme:login 1234", hidden))
        assertEquals("/spawn", Logs.mask("/spawn", hidden))
        assertEquals("/msg a 안녕", Logs.mask("/msg a 안녕", hidden))
    }
}
