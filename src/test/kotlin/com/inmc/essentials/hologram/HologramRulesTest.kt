package com.inmc.essentials.hologram

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 홀로그램 규칙 — 사람마다 글 판단 · 조건 · 쪽 셈 · 줄 가리키기 · 클릭 고르기 · 명령 접두 · 배경 · CMI 글 바꾸기 · 설정. 서버 없이. */
class HologramRulesTest {

    private val global = listOf("server_", "math_")

    @Test
    fun `사람마다 엔티티가 필요한 글만 가른다`() {
        assertTrue(HoloRules.isPersonal("안녕 {player}", global))
        assertTrue(HoloRules.isPersonal("레벨 %player_level%", global))
        assertFalse(HoloRules.isPersonal("접속 %server_online%명", global))
        assertFalse(HoloRules.isPersonal("<gold>그냥 글 100%</gold>", global))
        assertFalse(HoloRules.isPersonal("{홀로그램}", global))
        assertTrue(HoloRules.isDynamic("접속 %server_online%명"))
        assertFalse(HoloRules.isDynamic("<gold>고정</gold>"))
    }

    @Test
    fun `조건 - 숫자·글자·포함, 못 읽으면 거짓`() {
        val values = mapOf("%player_level%" to "12", "%player_world%" to "world_nether")
        val resolve = { s: String -> values[s] ?: s }
        assertTrue(HoloRules.condition("%player_level% >= 10", resolve))
        assertFalse(HoloRules.condition("%player_level% < 10", resolve))
        assertTrue(HoloRules.condition("%player_level% == 12.0", resolve))
        assertTrue(HoloRules.condition("%player_world% != world", resolve))
        assertTrue(HoloRules.condition("%player_world% contains nether", resolve))
        assertTrue(HoloRules.condition("%player_world% 포함 NETHER", resolve))
        assertFalse(HoloRules.condition("%player_world% 안포함 nether", resolve))
        assertFalse(HoloRules.condition("1 == 2", resolve))
        assertFalse(HoloRules.condition("이상한 조건", resolve))
        assertFalse(HoloRules.condition("abc > 3", resolve))
        assertTrue(HoloRules.condition("   ", resolve))
        assertTrue(HoloRules.conditionValid("%a% >= 3"))
        assertFalse(HoloRules.conditionValid("그냥 글"))
    }

    @Test
    fun `쪽 셈 - 기준 쪽 + 넘긴 만큼, 음수도 감싼다`() {
        assertEquals(0, HoloRules.pageOf(5, 3, 1))
        assertEquals(2, HoloRules.pageOf(1, 1, 3))
        assertEquals(2, HoloRules.pageOf(0, -1, 3))
        assertEquals(1, HoloRules.offsetFor(2, 0, 3))
        assertEquals(0, HoloRules.pageOf(2, HoloRules.offsetFor(2, 0, 3), 3))
    }

    @Test
    fun `줄 가리키기 - 3 과 2대3`() {
        assertEquals(0 to 2, HoloRules.lineRef("3"))
        assertEquals(1 to 2, HoloRules.lineRef("2:3"))
        assertEquals(4 to 0, HoloRules.lineRef("1", page = 4))
        assertNull(HoloRules.lineRef("0"))
        assertNull(HoloRules.lineRef("a"))
        assertNull(HoloRules.lineRef("1:2:3"))
    }

    @Test
    fun `클릭 고르기 - 정확히, 그다음 Shift 없는 쪽, 그다음 아무 클릭`() {
        val actions = HoloActions(mapOf(HoloClick.RIGHT to listOf("r"), HoloClick.ANY to listOf("a"), HoloClick.SHIFT_LEFT to listOf("sl")))
        assertEquals(listOf("r"), actions.resolve(HoloClick.RIGHT))
        assertEquals(listOf("r"), actions.resolve(HoloClick.SHIFT_RIGHT))
        assertEquals(listOf("sl"), actions.resolve(HoloClick.SHIFT_LEFT))
        assertEquals(listOf("a"), actions.resolve(HoloClick.LEFT))
        assertTrue(HoloActions.NONE.resolve(HoloClick.LEFT).isEmpty())
        assertEquals(HoloActions(mapOf(HoloClick.ANY to listOf("a"), HoloClick.SHIFT_LEFT to listOf("sl"))), actions.with(HoloClick.RIGHT, emptyList()))
        assertEquals(HoloClick.SHIFT_LEFT, HoloClick.parse("shift좌"))
        assertEquals(HoloClick.SHIFT_RIGHT, HoloClick.parse("SHIFT_RIGHT"))
        assertEquals(HoloClick.RIGHT, HoloClick.parse("우"))
        assertNull(HoloClick.parse("가운데"))
    }

    @Test
    fun `명령 접두와 쪽 넘기기 동작`() {
        assertEquals(true to "spawn", HoloRules.parseCommand("player:/spawn"))
        assertEquals(false to "say {player}", HoloRules.parseCommand("say {player}"))
        assertEquals(true to "warp a", HoloRules.parseCommand("asPlayer! warp a"))
        assertNull(HoloRules.parseCommand("console:"))
        assertEquals(1, HoloRules.pageStep("[nextPage]"))
        assertEquals(-1, HoloRules.pageStep("[이전쪽]"))
        assertNull(HoloRules.pageStep("say next"))
    }

    @Test
    fun `배경·너비·아이콘 입력`() {
        assertNull(HoloRules.background("default"))
        assertEquals(0, HoloRules.background("none"))
        assertEquals(0x40112233, HoloRules.background("#112233"))
        assertEquals(0x80112233.toInt(), HoloRules.background("#80112233"))
        assertNull(HoloRules.background("#zz"))
        assertEquals(2.5, HoloRules.clickWidth(10, 1.0, 2.5))
        assertEquals(0.4, HoloRules.clickWidth(0, 1.0, 0.0))
        assertEquals(8.0, HoloRules.clickWidth(500, 1.0, 0.0))
        assertEquals("minecraft:diamond", HoloRules.iconSpec("icon:minecraft:diamond"))
        assertEquals("손", HoloRules.iconSpec("아이콘:손"))
        assertNull(HoloRules.iconSpec("그냥 글"))
    }

    @Test
    fun `CMI 글 - 색·이름 토큰·아이콘·CText 명령`() {
        assertEquals(HoloLine("<#FF0000>빨강 <white>{player}"), HoloRules.fromCmiLine("{#FF0000}빨강 {#white}[playerName]").first)
        assertEquals(HoloLine(icon = "minecraft:diamond"), HoloRules.fromCmiLine("ICON:DIAMOND").first)
        assertEquals(HoloLine(icon = "mmoitems:SWORD:X"), HoloRules.fromCmiLine("SICON:mmoitems:SWORD:X").first)
        val (line, notes) = HoloRules.fromCmiLine("앞 <T>여기를 누르세요</T><H>힌트</H><C>warp spawn [playerName]</C> 뒤")
        assertEquals("앞 여기를 누르세요 뒤", line.text)
        assertEquals(listOf("warp spawn {player}"), line.actions.resolve(HoloClick.RIGHT))
        assertTrue(notes.any { "<H>" in it })
        assertEquals(listOf("[nextPage]"), HoloRules.fromCmiLine("<T>다음</T><C>[nextpage]</C>").first.actions.resolve(HoloClick.LEFT))
    }

    @Test
    fun `CMI 쪽 나누기`() {
        assertEquals(listOf(listOf("a", "b"), listOf("c")), HoloRules.splitPages(listOf("a", "b", "!nextpage!", "c")))
        assertEquals(listOf(listOf("a")), HoloRules.splitPages(listOf("a", "!NEXTPAGE!")))
        assertEquals(listOf(emptyList<String>()), HoloRules.splitPages(emptyList()))
    }

    @Test
    fun `설정 - 배포 파일은 기본값과 같고 이상한 값은 기본값으로`() {
        val shipped = YamlConfiguration().apply { loadFromString(java.io.File("src/main/resources/settings/holograms.yml").readText()) }
        assertEquals(HologramSettings(), HologramSettings.from(shipped))
        val weird = YamlConfiguration().apply {
            set("view-range", 0)
            set("line-height", -1)
            set("global-placeholders", listOf(" Server_ ", ""))
        }
        val s = HologramSettings.from(weird)
        assertEquals(1, s.viewRange)
        assertEquals(0.28, s.lineHeight)
        assertEquals(listOf("server_"), s.globalPlaceholders)
    }
}
