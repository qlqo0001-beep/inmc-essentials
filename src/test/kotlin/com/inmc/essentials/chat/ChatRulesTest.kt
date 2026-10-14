package com.inmc.essentials.chat

import net.kyori.adventure.text.Component
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 채팅의 순수 규칙 — 바닐라 귓속말 돌리기 · 스파이 거르기 · 귓속말 앞 기호 · 거리 · 공지/타이틀 옵션 · 도배 · 답장 상대 · 링크 · 설정 읽기. */
class ChatRulesTest {

    private val s = ChatSettings()

    @Test
    fun `바닐라 이름공간 귓속말만 우리 명령으로 돌린다`() {
        assertEquals("/귓 철수 안녕", ChatRules.rewriteVanillaWhisper("/minecraft:msg 철수 안녕", "귓"))
        assertEquals("/귓 철수 안녕", ChatRules.rewriteVanillaWhisper("/MINECRAFT:Tell 철수 안녕", "귓"))
        assertEquals("/귓", ChatRules.rewriteVanillaWhisper("/minecraft:w", "귓"))
        assertNull(ChatRules.rewriteVanillaWhisper("/msg 철수 안녕", "귓"))
        assertNull(ChatRules.rewriteVanillaWhisper("/minecraft:me 춤", "귓"))
        assertNull(ChatRules.rewriteVanillaWhisper("/other:msg a b", "귓"))
    }

    @Test
    fun `명령 스파이 - 숨김 목록과 보기 목록`() {
        assertEquals("login", ChatRules.commandRoot("/authme:LOGIN 1234"))
        assertFalse(ChatRules.spyShows("/login 1234", s, all = true, bypass = false))
        assertTrue(ChatRules.spyShows("/login 1234", s, all = true, bypass = true))
        val only = s.copy(spyOnly = setOf("tp"))
        assertTrue(ChatRules.spyShows("/tp a", only, all = false, bypass = false))
        assertFalse(ChatRules.spyShows("/홈 a", only, all = false, bypass = false))
        assertTrue(ChatRules.spyShows("/홈 a", only, all = true, bypass = false))
        assertFalse(ChatRules.spyShows("/", s, all = true, bypass = true))
    }

    @Test
    fun `귓속말 앞 기호는 권한이 있을 때만`() {
        assertEquals(ChatRules.Whisper("안녕", clean = true, noReply = true), ChatRules.parseWhisper("!- 안녕", true, true))
        assertEquals(ChatRules.Whisper("안녕", clean = true, noReply = false), ChatRules.parseWhisper("!안녕", true, false))
        assertEquals(ChatRules.Whisper("!안녕", clean = false, noReply = false), ChatRules.parseWhisper("!안녕", false, false))
        assertEquals(ChatRules.Whisper("-안녕", clean = true, noReply = false), ChatRules.parseWhisper("!-안녕", true, false))
    }

    @Test
    fun `거리 - 끔이면 제한 없음, 동적 표는 인원으로, 외침은 거리 채팅이 켜졌을 때만`() {
        assertNull(ChatRules.generalRange(s, 10))
        val ranged = s.copy(rangeGeneral = 100)
        assertEquals(100.0, ChatRules.generalRange(ranged, 10))
        assertEquals(ChatRules.NO_SHOUT, ChatRules.shoutRange(s))
        assertEquals(200.0, ChatRules.shoutRange(ranged))
        assertNull(ChatRules.shoutRange(ranged.copy(rangeShout = 0)))
        assertEquals(ChatRules.NO_SHOUT, ChatRules.shoutRange(ranged.copy(rangeShout = -1)))
        val dynamic = ranged.copy(dynamicRange = true)
        assertNull(ChatRules.generalRange(dynamic, 5)) // 1-0 → 제한 없음
        assertEquals(1000.0, ChatRules.generalRange(dynamic, 10))
        assertEquals(200.0, ChatRules.generalRange(dynamic, 77))
        assertEquals(100.0, ChatRules.generalRange(dynamic.copy(dynamicLimits = listOf(5 to 50)), 2)) // 표의 첫 줄보다 적으면 기본
        assertTrue(ChatRules.inRange(null, false, Double.MAX_VALUE))
        assertTrue(ChatRules.inRange(10.0, true, 100.0))
        assertFalse(ChatRules.inRange(10.0, true, 100.1))
        assertFalse(ChatRules.inRange(10.0, false, 1.0))
        assertTrue(ChatRules.isShout("!모두 들어"))
        assertFalse(ChatRules.isShout("!"))
        assertFalse(ChatRules.isShout("! "))
    }

    @Test
    fun `공지 옵션은 어디에 있어도 읽고 느낌표는 머리말 없이`() {
        val b = ChatRules.parseBroadcast("!점검 -w:world,world_nether 10분 뒤 -r:50 -c:world;1;64;-2")
        assertEquals("점검 10분 뒤", b.text)
        assertTrue(b.clean)
        assertEquals(setOf("world", "world_nether"), b.worlds)
        assertEquals(50.0, b.range)
        assertEquals(listOf("world", "1", "64", "-2"), b.center)
        val korean = ChatRules.parseBroadcast("안녕 -월드:world -거리:abc")
        assertEquals("안녕", korean.text)
        assertFalse(korean.clean)
        assertNull(korean.range)
    }

    @Test
    fun `타이틀은 글자 그대로의 역슬래시 n 으로 나누고 시간 옵션을 읽는다`() {
        val t = ChatRules.parseTitle("어서 와 \\n 즐거운 하루 -in:5 -keep:40 -out:abc", s)
        assertEquals("어서 와", t.title)
        assertEquals("즐거운 하루 -out:abc", t.subtitle)
        assertEquals(5, t.fadeIn)
        assertEquals(40, t.stay)
        assertEquals(s.titleFadeOut, t.fadeOut)
        assertEquals("", ChatRules.parseTitle("제목만", s).subtitle)
    }

    @Test
    fun `비슷함과 도배 막기`() {
        assertEquals(100, ChatRules.similarity("안녕 하세요", "안녕하세요"))
        assertTrue(ChatRules.similarity("ㅋㅋㅋㅋㅋㅋㅋㅋㅋ", "ㅋㅋㅋㅋㅋㅋㅋㅋ") >= 80)
        assertTrue(ChatRules.similarity("사과 팝니다", "철 삽니다") < 80)
        val guard = SpamGuard()
        val id = UUID(0, 1)
        val settings = s.copy(spamMinAmount = 2, spamIntervalSeconds = 5)
        assertFalse(guard.check(id, "public", "도배", 0L, settings))
        assertFalse(guard.check(id, "public", "도배", 1000L, settings))
        assertTrue(guard.check(id, "public", "도 배", 2000L, settings)) // 빈칸만 다르면 같은 말
        assertFalse(guard.check(id, "private", "도배", 2000L, settings)) // 채널이 다르면 따로
        assertFalse(guard.check(id, "public", "도배", 7000L, settings)) // 시간이 지나면 풀린다
        guard.forget(id)
        assertFalse(guard.check(id, "public", "도배", 7001L, settings))
    }

    @Test
    fun `답장 - 시간 안의 대화 상대가 이기고 지나면 마지막 보낸 사람`() {
        val me = UUID(0, 1)
        val a = UUID(0, 2)
        val b = UUID(0, 3)
        val book = ReplyBook()
        assertNull(book.target(me, 0, s))
        book.received(me, a, 0)
        assertEquals(a, book.target(me, 1, s))
        book.sent(me, a, 1_000)
        book.received(me, b, 2_000)
        assertEquals(a, book.target(me, 3_000, s))
        assertEquals(b, book.target(me, 1_000 + 121_000, s))
        assertEquals(b, book.target(me, 3_000, s.copy(replyToLastMessenger = true)))
        // 대화 상대가 다시 보내면 시간이 새로워진다.
        book.received(me, a, 100_000)
        assertEquals(a, book.target(me, 200_000, s))
    }

    @Test
    fun `링크 축약 - 앞 빈칸은 남기고 누르면 주소가 열린다`() {
        val out = ChatText.shortenLinks(Component.text("보세요 naver.com/abc 끝"), s.linkRegex, "<aqua>[링크]</aqua>")
        assertEquals("보세요 [링크] 끝", ChatText.plain(out))
        val clicks = mutableListOf<String>()
        fun walk(c: Component) { c.clickEvent()?.let { clicks += it.action().toString() + " " + it.toString() }; c.children().forEach(::walk) }
        walk(out)
        assertEquals(1, clicks.size)
        assertTrue(clicks[0].contains("https://naver.com/abc") && clicks[0].lowercase().contains("open_url"), clicks[0])
        assertEquals("1.5 배", ChatText.plain(ChatText.shortenLinks(Component.text("1.5 배"), s.linkRegex, "[링크]")))
    }

    @Test
    fun `사람의 글은 태그를 풀지 않는다 - 색 권한이면 색만`() {
        assertEquals("<click:run_command:'/op me'>눌러</click>", ChatText.plain(ChatText.body("<click:run_command:'/op me'>눌러</click>", colors = false)))
        assertEquals("<click:run_command:'/op me'>빨강", ChatText.plain(ChatText.body("<click:run_command:'/op me'><red>빨강", colors = true)))
        assertEquals("초록", ChatText.plain(ChatText.body("&a초록", colors = true)))
    }

    @Test
    fun `배포 설정은 코드의 기본값과 같다`() {
        val yaml = YamlConfiguration().apply { loadFromString(File("src/main/resources/settings/chat.yml").readText()) }
        val read = ChatSettings.from(yaml)
        assertEquals(s.linkRegex.pattern, read.linkRegex.pattern)
        assertEquals(s, read.copy(linkRegex = s.linkRegex))
    }
}
