package com.inmc.essentials.moderation

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** moderation 의 순수 규칙 — 시간 · 플래그 · IP · 권한 최대 시간 · 경고 문턱 · 명령 허용 · 중복 계정. */
class ModRulesTest {

    // --- 시간 ------------------------------------------------------------------------------------

    @Test
    fun `시간은 단위를 섞어 읽고 맨 숫자는 초`() {
        assertEquals(30L, ModRules.parseSeconds("30s"))
        assertEquals(600L, ModRules.parseSeconds("10m"))
        assertEquals(93_600L, ModRules.parseSeconds("1d2h"))
        assertEquals(93_600L, ModRules.parseSeconds("1일 2시간"))
        assertEquals(604_800L, ModRules.parseSeconds("1w"))
        assertEquals(90L, ModRules.parseSeconds("90"))
        assertEquals(3_900L, ModRules.parseSeconds("1H5M"))
    }

    @Test
    fun `시간이 아닌 낱말은 null - 사유의 첫 낱말을 시간으로 먹지 않는다`() {
        assertNull(ModRules.parseSeconds("3번"))
        assertNull(ModRules.parseSeconds("욕설"))
        assertNull(ModRules.parseSeconds("10minutes"))
        assertNull(ModRules.parseSeconds("0"))
        assertNull(ModRules.parseSeconds(""))
    }

    @Test
    fun `더하기 빼기 정하기`() {
        assertEquals(ModRules.Change(ModRules.Mode.ADD, 30), ModRules.parseChange("+30s"))
        assertEquals(ModRules.Change(ModRules.Mode.SUB, 900), ModRules.parseChange("-15m"))
        assertEquals(ModRules.Change(ModRules.Mode.SET, 3600), ModRules.parseChange("1h"))
        assertNull(ModRules.parseChange("-s"))
        assertNull(ModRules.parseChange("+abc"))
    }

    @Test
    fun `기존 뮤트에 더하고 빼기 - 영구는 그대로`() {
        val now = 1_000_000L
        val add = ModRules.Change(ModRules.Mode.ADD, 30)
        val sub = ModRules.Change(ModRules.Mode.SUB, 30)
        assertEquals(now + 90_000, ModRules.applyChange(now + 60_000, true, add, now))
        assertEquals(now + 30_000, ModRules.applyChange(now + 60_000, true, sub, now))
        assertEquals(now + 30_000, ModRules.applyChange(null, false, add, now), "뮤트가 없으면 지금부터")
        assertNull(ModRules.applyChange(null, true, add, now), "영구 뮤트에 더해도 영구")
        assertTrue((ModRules.applyChange(now + 10_000, true, sub, now) ?: Long.MAX_VALUE) <= now, "빼서 0 이하면 풀림")
        assertEquals(now + 5_000, ModRules.applyChange(now + 999_999, true, ModRules.Change(ModRules.Mode.SET, 5), now))
    }

    @Test
    fun `최대 시간 권한 - unlimited 가 이기고 숫자 중 가장 큰 것, 없으면 설정값`() {
        val p = "inmcessentials.moderation.tempban.max."
        assertEquals(Long.MAX_VALUE, ModRules.maxSeconds(listOf(p + "60", p + "unlimited"), p, 60))
        assertEquals(3600L, ModRules.maxSeconds(listOf(p + "60", p + "3600", "other.node"), p, 60))
        assertEquals(86_400L, ModRules.maxSeconds(listOf(p + "1d"), p, 60))
        assertEquals(60L, ModRules.maxSeconds(emptyList(), p, 60))
        assertEquals(Long.MAX_VALUE, ModRules.maxSeconds(emptyList(), p, -1))
    }

    // --- 플래그 ----------------------------------------------------------------------------------

    @Test
    fun `조용히와 옵션을 떼고 나머지는 순서대로`() {
        val f = ModRules.flags(listOf("10m", "-s", "욕설", "반복", "-p:Steve", "-w:world"))
        assertTrue(f.silent)
        assertEquals(listOf("10m", "욕설", "반복"), f.rest)
        assertEquals(mapOf("p" to "Steve", "w" to "world"), f.options)
        assertNull(f.reason)
    }

    @Test
    fun `감옥의 r 사유는 그 뒤 낱말 전부`() {
        val f = ModRules.flags(listOf("5m", "감옥1", "r:테러", "했음", "-s"), reasonTag = true)
        assertEquals("테러 했음", f.reason)
        assertTrue(f.silent)
        assertEquals(listOf("5m", "감옥1"), f.rest)
        assertEquals(listOf("r:x"), ModRules.flags(listOf("r:x")).rest, "감옥이 아니면 r: 은 그냥 낱말")
    }

    @Test
    fun `켜기 끄기 낱말`() {
        assertEquals(true, ModRules.bool("true"))
        assertEquals(false, ModRules.bool("끄기"))
        assertNull(ModRules.bool("maybe"))
    }

    // --- IP --------------------------------------------------------------------------------------

    @Test
    fun `IP 알아보기`() {
        assertTrue(ModRules.isIp("127.0.0.1"))
        assertTrue(ModRules.isIp("203.0.113.9"))
        assertTrue(ModRules.isIp("2001:db8::1"))
        assertFalse(ModRules.isIp("256.1.1.1"))
        assertFalse(ModRules.isIp("Steve"))
        assertFalse(ModRules.isIp("1.2.3"))
    }

    @Test
    fun `CMI 의 IP 칸 - 밑줄 주소와 횟수`() {
        val problems = ArrayList<String>()
        val ips = ModRules.cmiIps("""{"127_0_0_1":3,"10_0_0_5":1}""") { problems += it }
        assertEquals(mapOf("127.0.0.1" to 3, "10.0.0.5" to 1), ips)
        assertTrue(problems.isEmpty())
        assertEquals(mapOf("1.2.3.4" to 1), ModRules.cmiIps("1_2_3_4") { problems += it })
        ModRules.cmiIps("이상한값") { problems += it }
        assertEquals(1, problems.size, "못 읽은 것은 알린다")
    }

    // --- 경고 문턱 -------------------------------------------------------------------------------------

    @Test
    fun `점수가 오를 때 새로 넘은 문턱만`() {
        val t = listOf(3, 5, 10)
        assertEquals(listOf(3), ModRules.crossed(t, 2, 3))
        assertEquals(listOf(3, 5), ModRules.crossed(t, 0, 7))
        assertEquals(emptyList(), ModRules.crossed(t, 3, 4), "이미 넘은 문턱은 다시 안 돈다")
        assertEquals(listOf(10), ModRules.crossed(t, 9, 60))
    }

    // --- 명령 허용 ----------------------------------------------------------------------------------

    @Test
    fun `허용 목록 - 첫 낱말 · 이름공간 무시 · 여러 낱말 항목`() {
        val allowed = listOf("귓", "msg", "에센셜 도움")
        assertTrue(ModRules.commandAllowed("귓 Steve 안녕", allowed))
        assertTrue(ModRules.commandAllowed("/minecraft:msg Steve hi", allowed))
        assertTrue(ModRules.commandAllowed("MSG Steve", allowed))
        assertTrue(ModRules.commandAllowed("에센셜 도움 1", allowed))
        assertFalse(ModRules.commandAllowed("에센셜 리로드", allowed))
        assertFalse(ModRules.commandAllowed("spawn", allowed))
        assertFalse(ModRules.commandAllowed("msgx", allowed))
    }

    // --- 중복 계정 ---------------------------------------------------------------------------------

    @Test
    fun `온라인 서버 - 같은 이름의 v3 오프라인 uuid 만 중복`() {
        val online = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5") // v4
        val offline = ModRules.offlineUuid("Notch")
        val other = UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6") // 이름을 넘겨받은 다른 사람(v4)
        assertEquals(listOf(offline), ModRules.duplicates(listOf(online to "Notch", offline to "Notch"), onlineMode = true))
        assertEquals(emptyList(), ModRules.duplicates(listOf(online to "Notch", other to "notch"), onlineMode = true), "둘 다 진짜 계정이면 건드리지 않는다")
        assertEquals(emptyList(), ModRules.duplicates(listOf(offline to "Notch"), onlineMode = true), "혼자면 중복이 아니다")
    }

    @Test
    fun `오프라인 서버 - 같은 이름의 v4 가 중복`() {
        val online = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")
        val offline = ModRules.offlineUuid("Notch")
        assertEquals(listOf(online), ModRules.duplicates(listOf(online to "Notch", offline to "Notch"), onlineMode = false))
    }

    @Test
    fun `이름 규칙`() {
        assertTrue(ModRules.validName("감옥1"))
        assertTrue(ModRules.validName("main_jail-2"))
        assertFalse(ModRules.validName("a.b"))
        assertFalse(ModRules.validName(""))
        assertFalse(ModRules.validName("x".repeat(33)))
    }
}
