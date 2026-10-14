package com.inmc.essentials

import com.inmc.essentials.command.CommandRegistry
import com.inmc.essentials.command.HelpCommand
import com.inmc.essentials.command.HelpCommand.Line
import com.inmc.essentials.command.HelpCommand.Row
import com.inmc.essentials.server.PapiLine
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `/도움말` 의 줄 짓기·찾기와 `/플레이스홀더 목록` 의 값 칸(2026-10-15). */
class HelpCommandTest {

    @Test
    fun `기능 순서로 묶고 묶음마다 머리줄 하나 · 모르는 기능은 기타로 맨 뒤`() {
        val rows = HelpCommand.rows(
            listOf(
                Row("homes", "홈설정", listOf("sethome"), "선 자리를 홈으로"),
                Row(null, "무언가", emptyList(), "기능 밖"),
                Row("teleport", "이동", listOf("tp"), "순간이동"),
                Row("homes", "홈", listOf("home"), "홈으로"),
            ),
        )
        assertEquals(
            listOf(
                Line.Group("순간이동"), Line.Command("이동", listOf("tp"), "순간이동"),
                Line.Group("홈"), Line.Command("홈", listOf("home"), "홈으로"), Line.Command("홈설정", listOf("sethome"), "선 자리를 홈으로"),
                Line.Group("기타"), Line.Command("무언가", emptyList(), "기능 밖"),
            ),
            rows,
        )
    }

    @Test
    fun `모든 기능에 묶음 이름이 있다`() {
        assertEquals(emptyList(), com.inmc.essentials.feature.Features.IDS.filter { it !in HelpCommand.GROUPS })
    }

    @Test
    fun `쪽 수는 최소 1`() {
        assertEquals(1, HelpCommand.pageCount(0, 12))
        assertEquals(1, HelpCommand.pageCount(12, 12))
        assertEquals(2, HelpCommand.pageCount(13, 12))
    }

    @Test
    fun `이름이나 별칭으로 찾는다 · 대소문자 무시 · 이름이 별칭보다 먼저`() {
        fun entry(name: String, vararg aliases: String) =
            CommandRegistry.Entry(LiteralArgumentBuilder.literal<CommandSourceStack>(name).build(), "", aliases.toList())
        val entries = listOf(entry("홈", "home"), entry("홈목록", "homes"), entry("home2", "홈"))
        assertEquals("홈", HelpCommand.find(entries, "홈")?.node?.literal)
        assertEquals("홈목록", HelpCommand.find(entries, "HOMES")?.node?.literal)
        assertNull(HelpCommand.find(entries, "없음"))
    }

    @Test
    fun `입력창에 넣는 글은 첫 인자 자리 앞까지`() {
        assertEquals("/홈 ", HelpCommand.suggestion("/홈"))
        assertEquals("/홈설정 ", HelpCommand.suggestion("/홈설정 <내용>"))
        assertEquals("/우편 보내기 ", HelpCommand.suggestion("/우편 보내기 <플레이어> <내용>"))
    }

    @Test
    fun `자리표시 값 칸 — 매개변수 자리 · 안 풀림 · 빈 값 · 줄바꿈 · 긴 값`() {
        assertTrue(PapiLine.isTemplate("%inmceco_balance_<화폐>%"))
        assertFalse(PapiLine.isTemplate("%inmceco_balance%"))
        assertNull(PapiLine.shown("%x_y%", "%x_y%"), "PAPI 가 모르면 그대로 돌려준다 — 값 없음")
        assertNull(PapiLine.shown("%x_y%", "  "))
        assertEquals("12,000", PapiLine.shown("%x%", "12,000"))
        assertEquals("가 ⏎ 나", PapiLine.shown("%x%", "가\n나"))
        assertEquals(PapiLine.MAX + 1, PapiLine.shown("%x%", "a".repeat(200))!!.length)
    }
}
