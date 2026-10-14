package com.inmc.essentials.admininfo

import com.google.gson.JsonParser
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 관리 정보(투명 옵션 · 플레이 시간 · 보상 · 목록 · 통계)의 서버 없이 되는 계산. */
class AdminInfoLogicTest {

    // --- 설정 -------------------------------------------------------------------------------------

    @Test
    fun `배포 설정은 코드의 기본값과 같다`() {
        val yaml = YamlConfiguration().apply { loadFromString(File("src/main/resources/settings/admininfo.yml").readText()) }
        assertEquals(AdminSettings(), AdminSettings.from(yaml))
    }

    @Test
    fun `이상한 설정값은 기본값으로`() {
        val yaml = YamlConfiguration().apply {
            set("playtime.sample-seconds", 0)
            set("vanish.defaults.night-vision", "응")
            set("vanish.defaults.player-damage", true)
            set("playtime.top-exclude", listOf(" Steve ", ""))
            set("lastonline.timers", listOf(-5, 60))
        }
        val s = AdminSettings.from(yaml)
        assertEquals(5, s.sampleSeconds)
        assertTrue(s.vanishDefault(VanishOption.NIGHT_VISION))
        assertTrue(s.vanishDefault(VanishOption.PLAYER_DAMAGE))
        assertEquals(setOf("steve"), s.topExclude)
        assertEquals(listOf(60L), s.lastOnlineTimers)
    }

    // --- 투명 옵션 ---------------------------------------------------------------------------------

    @Test
    fun `투명 옵션은 이름으로 저장되고 모르는 조각은 건너뛴다`() {
        val map = mapOf(VanishOption.NIGHT_VISION to false, VanishOption.ITEM_PICKUP to true)
        val text = VanishOption.encode(map)
        assertEquals(map, VanishOption.decode(text))
        assertEquals(mapOf(VanishOption.BOSSBAR to true), VanishOption.decode("bossbar=1;없는옵션=1;깨짐;item-pickup=아마"))
        assertEquals(emptyMap(), VanishOption.decode(null))
    }

    @Test
    fun `투명 옵션은 CMI 이름·설정 이름·한글 이름 모두 받는다`() {
        assertEquals(VanishOption.HOOK_PLAYERS, VanishOption.parse("hookPlayers"))
        assertEquals(VanishOption.HOOK_PLAYERS, VanishOption.parse("hook-entities"))
        assertEquals(VanishOption.NIGHT_VISION, VanishOption.parse("야간투시"))
        assertNull(VanishOption.parse("noisyChest")) // inventory 기능의 설정 — 여기 없다
        assertEquals(true, VanishOption.parseState("켜기"))
        assertEquals(false, VanishOption.parseState("off"))
        assertNull(VanishOption.parseState("-s"))
    }

    @Test
    fun `CMI 기본값을 그대로 가진다`() {
        val on = VanishOption.entries.filter { it.cmiDefault }.toSet()
        assertEquals(setOf(VanishOption.NIGHT_VISION, VanishOption.BOSSBAR, VanishOption.STOP_PLAYTIME, VanishOption.SLEEP_IGNORE), on)
        assertEquals(VanishOption.entries.size, VanishOption.entries.map { it.key }.toSet().size)
    }

    // --- 플레이 시간 편집 ---------------------------------------------------------------------------

    @Test
    fun `접속하지 않은 사람에게 건 편집은 이어 붙는다`() {
        val set100 = PlaytimeMath.PendingEdit(true, 100)
        val add50 = PlaytimeMath.PendingEdit(false, 50)
        val take30 = PlaytimeMath.PendingEdit.of(PlaytimeMath.Op.TAKE, 30)
        assertEquals(PlaytimeMath.PendingEdit(true, 150), PlaytimeMath.compose(set100, add50))
        assertEquals(PlaytimeMath.PendingEdit(false, 20), PlaytimeMath.compose(add50, take30))
        assertEquals(set100, PlaytimeMath.compose(add50, set100))
        assertEquals(PlaytimeMath.PendingEdit(true, 0), PlaytimeMath.compose(PlaytimeMath.PendingEdit(true, 10), take30))
        assertEquals(0L, take30.applyTo(10))
        assertEquals(Int.MAX_VALUE.toLong(), PlaytimeMath.PendingEdit(false, Long.MAX_VALUE / 2).applyTo(5))
    }

    @Test
    fun `시간 양 읽기 - 단위와 그냥 숫자는 분`() {
        assertEquals(5400L, PlaytimeMath.parseAmountSeconds("90"))
        assertEquals(5400L, PlaytimeMath.parseAmountSeconds("1h 30m"))
        assertEquals(93600L, PlaytimeMath.parseAmountSeconds("1일 2시간"))
        assertEquals(45L, PlaytimeMath.parseAmountSeconds("45s"))
        assertNull(PlaytimeMath.parseAmountSeconds("1h 뭐"))
        assertNull(PlaytimeMath.parseAmountSeconds("-5"))
        assertNull(PlaytimeMath.parseAmountSeconds(""))
        assertEquals(PlaytimeMath.Op.TAKE, PlaytimeMath.Op.parse("빼기"))
        assertEquals(PlaytimeMath.Op.SET, PlaytimeMath.Op.parse("set"))
    }

    @Test
    fun `상세 플레이 시간 구간 합`() {
        val today = LocalDate.of(2026, 10, 10) // 토요일
        val t = today.toEpochDay()
        val days = mapOf(t to 100L, t - 1 to 10L, t - 5 to 1L, t - 9 to 1000L, LocalDate.of(2026, 1, 1).toEpochDay() to 5L, t - 400 to 7L)
        val sums = PlaytimeMath.sums(days, today)
        assertEquals(100L, sums[PlaytimeMath.Range.TODAY])
        assertEquals(10L, sums[PlaytimeMath.Range.YESTERDAY])
        assertEquals(111L, sums[PlaytimeMath.Range.THIS_WEEK]) // 월요일(10-05)부터
        assertEquals(111L, sums[PlaytimeMath.Range.WEEK])
        assertEquals(1111L, sums[PlaytimeMath.Range.THIS_MONTH])
        assertEquals(1111L, sums[PlaytimeMath.Range.MONTH])
        assertEquals(1116L, sums[PlaytimeMath.Range.THIS_YEAR])
        assertEquals(1116L, sums[PlaytimeMath.Range.YEAR])
        assertEquals(1123L, sums[PlaytimeMath.Range.ALL])
    }

    @Test
    fun `순위 제외 - 이름과 오래 안 들어온 사람`() {
        val now = 100L * 86_400_000L
        assertTrue(PlaytimeMath.excluded("Steve", now, now, setOf("steve"), 0))
        assertTrue(PlaytimeMath.excluded("Alex", now - 31L * 86_400_000L, now, emptySet(), 30))
        assertTrue(!PlaytimeMath.excluded("Alex", now - 29L * 86_400_000L, now, emptySet(), 30))
        assertTrue(!PlaytimeMath.excluded("Alex", null, now, emptySet(), 30))
    }

    // --- 플레이 시간 보상 -----------------------------------------------------------------------------

    @Test
    fun `반복 보상 진행 - 쌓기와 안 쌓기`() {
        assertEquals(5L to 2, PlaytimeReward.advance(0, 0, 45, 20, stack = true))
        assertEquals(5L to 1, PlaytimeReward.advance(0, 0, 45, 20, stack = false))
        assertEquals(5L to 1, PlaytimeReward.advance(0, 1, 45, 20, stack = false))
        assertEquals(19L to 3, PlaytimeReward.advance(18, 3, 1, 20, stack = true))
        assertEquals(0L to 0, PlaytimeReward.advance(0, 0, 0, 20, stack = true))
        assertEquals(PlaytimeReward.MAX_PENDING, PlaytimeReward.advance(0, PlaytimeReward.MAX_PENDING, 100, 1, stack = true).second)
    }

    @Test
    fun `보상 정의는 YAML 로 오간다`() {
        val def = PlaytimeReward("daily", name = "<gold>매일</gold>", description = mutableListOf("한 줄"), repeat = true, seconds = 86400, autoClaim = true, stack = false, permission = true)
        val back = PlaytimeReward.fromYaml("daily", def.toYaml())
        assertEquals(def.name, back.name)
        assertEquals(def.description, back.description)
        assertEquals(listOf(true, true, false, true, true), listOf(back.repeat, back.autoClaim, back.stack, back.permission, back.enabled))
        assertEquals(86400L * 20, back.periodTicks)
        assertEquals("inmcessentials.admininfo.prewards.reward.daily", back.permissionNode)
        assertTrue(PlaytimeReward.validId("1hour") && !PlaytimeReward.validId("Bad Id") && !PlaytimeReward.validId(""))
    }

    // --- 목록 · 통계 ----------------------------------------------------------------------------------

    @Test
    fun `목록 그룹 - 무게 순서와 그룹 안 이름 순서`() {
        val players = listOf("zed", "amy", "Bob", "kim")
        val group = mapOf("zed" to "VIP", "amy" to "default", "Bob" to "admin", "kim" to "vip")
        val weight = mapOf("vip" to ("후원자" to 50), "default" to ("기본" to 0), "admin" to ("관리자" to 100))
        val high = ListGrouping.group(players, { group.getValue(it) }, { weight.getValue(it) }, true) { it }
        assertEquals(listOf("admin", "vip", "default"), high.map { it.key })
        assertEquals(listOf("kim", "zed"), high[1].players)
        assertEquals("후원자", high[1].display)
        val low = ListGrouping.group(players, { group.getValue(it) }, { weight.getValue(it) }, false) { it }
        assertEquals(listOf("default", "vip", "admin"), low.map { it.key })
    }

    @Test
    fun `통계 파일 요약 - 분류 합과 놓은 블록 가르기`() {
        val json = JsonParser.parseString(
            """{"stats":{"minecraft:custom":{"minecraft:play_time":72000,"minecraft:deaths":3,"minecraft:walk_one_cm":250000,"minecraft:damage_dealt":125},
              "minecraft:used":{"minecraft:stone":10,"minecraft:diamond_sword":4},"minecraft:mined":{"minecraft:stone":7,"minecraft:dirt":2},
              "minecraft:killed":{"minecraft:zombie":5}}}""",
        ).asJsonObject.getAsJsonObject("stats")
        val source = StatsSummary.FromJson(json) { it == "minecraft:stone" }
        val lines = StatsSummary.lines(source) { "${it / 20}s" }.toMap()
        assertEquals("3600s", lines["플레이 시간"])
        assertEquals("3", lines["죽음"])
        assertEquals("2.50km", lines["걷기"])
        assertEquals("12.5", lines["준 피해"])
        assertEquals("10", lines["놓은 블록"])
        assertEquals("4", lines["쓴 아이템"])
        assertEquals("9", lines["캔 블록"])
        assertEquals("5", lines["처치한 몹"])
        assertEquals("2.50km", lines["이동 거리 합계"])
        assertTrue("달리기" !in lines) // 0 인 이동 거리는 뺀다
        assertEquals("57cm", StatsSummary.distance(57))
        assertEquals("12m", StatsSummary.distance(1234))
    }
}
