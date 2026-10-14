package com.inmc.essentials.item

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 2026-10-10 검토 반영 — /i 자기 지급 꼬리 · 검색 지우기 조건 · 비우기 제외(NBT 경로) · 색 글 알아보기 · 비우기 종류. */
class ItemsFixesTest {

    @Test
    fun `i 자기 지급 - 첫 낱말을 아이템으로 되돌려 붙이면 개수·만들기 꼬리가 읽힌다`() {
        // 꼬리만으로는 아이템이 없어 읽기 오류 — 그래서 대상 자리의 첫 낱말을 붙여 다시 읽는다.
        assertIs<GiveSpec.Parsed.Error>(GiveSpec.parse("64"))
        val amount = assertIs<GiveSpec.Parsed.Ok>(GiveSpec.parse(GiveSpec.selfText("diamond", "64"))).spec
        assertEquals(listOf("diamond"), amount.items)
        assertEquals(64, amount.amount)
        val named = assertIs<GiveSpec.Parsed.Ok>(GiveSpec.parse(GiveSpec.selfText("diamond_sword", "n{<red>칼</red>} e{sharpness:5}"))).spec
        assertEquals(listOf("diamond_sword"), named.items)
        assertEquals("<red>칼</red>", named.name)
        assertEquals(listOf("sharpness" to 5), named.enchants)
        assertEquals("diamond", GiveSpec.selfText(" diamond ", ""))
    }

    @Test
    fun `검색 지우기 - 아이템을 고르는 조건이 없으면 거절(모드·최소는 사람만 고른다)`() {
        fun criteria(text: String) = assertIs<SearchCriteria.Parsed.Ok>(SearchCriteria.parse(text)).criteria
        assertFalse(criteria("모드:creative").selectsItems)
        assertFalse(criteria("최소:1000 모드:survival").selectsItems)
        assertTrue(criteria("diamond 최소:1000").selectsItems)
        assertTrue(criteria("hand").selectsItems)
        assertTrue(criteria("이름:보물").selectsItems)
        assertTrue(criteria("인챈트:sharpness:5").selectsItems)
        val c = criteria("diamond 모드:creative")
        assertTrue(c.allowsMode("creative"))
        assertFalse(c.allowsMode("SURVIVAL"))
    }

    @Test
    fun `비우기 제외 - NBT 경로와 PDC 열쇠를 가른다`() {
        val s = ClearSettings.of(true, listOf("NBT:ItemJoin Name", "nbt:ItemJoin.Name", "PDC:inmcess:modified_name", "MyPlugin:Tag", "garbage", " "))
        assertEquals(listOf("ItemJoin Name", "ItemJoin.Name"), s.excludeNbt)
        assertEquals(listOf("inmcess:modified_name", "myplugin:tag"), s.excludeKeys)
    }

    @Test
    fun `비우기 제외 - custom_data 안 경로(그대로의 열쇠 또는 점으로 한 겹씩)`() {
        val data = mapOf("ItemJoin Name" to "x", "ItemJoin" to mapOf("Name" to "y"), "a" to mapOf("b" to mapOf("c" to 1)))
        assertTrue(ClearSpec.hasPath(data, "ItemJoin Name"))
        assertTrue(ClearSpec.hasPath(data, "ItemJoin.Name"))
        assertTrue(ClearSpec.hasPath(data, "a.b.c"))
        assertFalse(ClearSpec.hasPath(data, "a.b.d"))
        assertFalse(ClearSpec.hasPath(data, "nothing"))
    }

    @Test
    fun `비우기 종류 - 재질 이름으로 무기·도구·갑옷`() {
        assertTrue(ClearSpec.kind(ClearSpec.Scope.WEAPONS, "diamond_sword"))
        assertTrue(ClearSpec.kind(ClearSpec.Scope.WEAPONS, "MACE"))
        assertTrue(ClearSpec.kind(ClearSpec.Scope.TOOLS, "IRON_PICKAXE"))
        assertTrue(ClearSpec.kind(ClearSpec.Scope.ARMORS, "ELYTRA"))
        assertFalse(ClearSpec.kind(ClearSpec.Scope.ARMORS, "DIRT"))
        assertFalse(ClearSpec.kind(ClearSpec.Scope.TOOLS, "BOW"))
    }

    @Test
    fun `모루 색 - 색·꾸밈이 있는 글만 색 이름으로`() {
        assertTrue(ItemGive.hasFormatting("&c빨강"))
        assertTrue(ItemGive.hasFormatting("&#ff00ff보라"))
        assertTrue(ItemGive.hasFormatting("<red>빨강</red>"))
        assertTrue(ItemGive.hasFormatting("<!i>곧은 글"))
        assertFalse(ItemGive.hasFormatting("평범한 이름"))
        assertFalse(ItemGive.hasFormatting("A & B"))
        assertFalse(ItemGive.hasFormatting("1 < 2"))
    }

    @Test
    fun `이름 규칙 - 모든 재질(*) 규칙 읽기 · 잘못된 정규식은 버림`() {
        val rule = assertNotNull(RenameRule.parse("*:([A-z]+ (?i)\\w*spawner)"))
        assertNull(rule.material)
        assertTrue(rule.regex.matches("Zombie Spawner"))
        assertFalse(rule.regex.matches("보물"))
        assertNull(RenameRule.parse("*:(unclosed"))
        assertNull(RenameRule.parse("no-colon"))
    }
}
