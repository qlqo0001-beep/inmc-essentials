package com.inmc.essentials.item

import java.io.ByteArrayInputStream
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** items 기능의 순수 규칙 — 지급 꼬리 · 되풀이 만들기 · 비우기 옵션 · 염색 · 설명 고치기 · 머리 텍스처 · NBT · 검색 조건. */
class ItemsRulesTest {

    // --- 지급 꼬리 ------------------------------------------------------------------------------------

    private fun ok(text: String): GiveSpec = assertIs<GiveSpec.Parsed.Ok>(GiveSpec.parse(text)).spec

    @Test
    fun `지급 꼬리 - 참조 여럿과 개수 · 만들기 꼬리 · 옵션`() {
        val spec = ok("diamond_sword inmc:보석_검 n{<red>전설의 검</red>} l{첫 줄|둘째 줄} e{sharpness:5,unbreaking} unbreakable 10 -s -slot:3")
        assertEquals(listOf("diamond_sword", "inmc:보석_검"), spec.items)
        assertEquals(10, spec.amount)
        assertEquals("<red>전설의 검</red>", spec.name)
        assertEquals(listOf("첫 줄", "둘째 줄"), spec.lore)
        assertEquals(listOf("sharpness" to 5, "unbreaking" to 1), spec.enchants)
        assertTrue(spec.unbreakable && spec.silent)
        assertEquals(3, spec.slot)
    }

    @Test
    fun `지급 꼬리 - 손 · 남의 가방 · 오프라인 · 기간`() {
        val spec = ok("hand:Steve inv:Alex offline -d:7")
        assertEquals(listOf("hand:Steve", "inv:Alex"), spec.items)
        assertTrue(spec.offline)
        assertEquals(7, spec.days)
        assertNull(spec.amount)
    }

    @Test
    fun `지급 꼬리 - 잘못된 것은 까닭과 함께`() {
        assertEquals(GiveSpec.UNCLOSED, (GiveSpec.parse("stone n{안 닫힘") as GiveSpec.Parsed.Error).reason)
        assertEquals(GiveSpec.BAD_AMOUNT, (GiveSpec.parse("5 stone") as GiveSpec.Parsed.Error).reason)
        assertEquals(GiveSpec.BAD_AMOUNT, (GiveSpec.parse("stone 5 6") as GiveSpec.Parsed.Error).reason)
        assertEquals(GiveSpec.BAD_SLOT, (GiveSpec.parse("stone -slot:99") as GiveSpec.Parsed.Error).reason)
        assertEquals(GiveSpec.UNKNOWN_FLAG, (GiveSpec.parse("stone -x") as GiveSpec.Parsed.Error).reason)
        assertEquals(GiveSpec.NO_ITEM, (GiveSpec.parse("-s") as GiveSpec.Parsed.Error).reason)
        assertEquals(GiveSpec.BAD_ENCHANT, (GiveSpec.parse("stone e{sharpness:x}") as GiveSpec.Parsed.Error).reason)
    }

    @Test
    fun `괄호 안의 빈칸은 한 낱말 · 제안은 괄호 밖에서만`() {
        assertEquals(listOf("stone", "n{가 나 다}", "3"), GiveSpec.tokenize("stone  n{가 나 다} 3"))
        assertEquals(6, GiveSpec.lastTokenStart("stone dia"))
        assertEquals(-1, GiveSpec.lastTokenStart("stone n{가 나"))
    }

    @Test
    fun `대상 낱말`() {
        assertEquals(GiveTarget.Everyone, GiveTarget.parse("**"))
        assertEquals(GiveTarget.Online, GiveTarget.parse("전원"))
        assertEquals(GiveTarget.Named("홍길동"), GiveTarget.parse("홍길동"))
    }

    // --- 개수는 되풀이로 ---------------------------------------------------------------------------------

    @Test
    fun `개수는 묶음 최대치로 잘려도 되풀이해 다 만든다`() {
        // 한 번에 최대 3개만 만드는 가짜(칼은 1, 흙은 64 와 같은 상황).
        var calls = 0
        val made = ItemGive.repeatCreateCount(10) { left -> calls++; minOf(left, 3) }
        assertEquals(listOf(3, 3, 3, 1), made)
        assertEquals(4, calls)
        assertEquals(emptyList(), ItemGive.repeatCreateCount(5) { 0 })
    }

    // --- 비우기 ----------------------------------------------------------------------------------------

    @Test
    fun `비우기 옵션 - 범위 · 종류 · 아이템 개수`() {
        val spec = assertIs<ClearSpec.Parsed.Ok>(ClearSpec.parse(listOf("+핫바", "+weapons", "diamond:5", "inmc:보석:2", "-exact", "-s"))).spec
        assertEquals(setOf(ClearSpec.Scope.QUICKBAR), spec.slotScopes)
        assertEquals(setOf(ClearSpec.Scope.WEAPONS), spec.typeScopes)
        assertEquals(listOf("diamond" to 5, "inmc:보석" to 2), spec.items)
        assertTrue(spec.exact && spec.silent && !spec.isEverything)
        assertEquals((0..8).toList(), spec.slots(4))
        assertTrue(spec.typeMatches("NETHERITE_SWORD"))
        assertTrue(!spec.typeMatches("DIRT"))
        assertEquals("inmc:sword" to null, ClearSpec.item("inmc:sword"))
    }

    @Test
    fun `비우기 - 아무 조건 없으면 전부(확인 대상) · 모르는 옵션은 실패`() {
        val all = assertIs<ClearSpec.Parsed.Ok>(ClearSpec.parse(emptyList())).spec
        assertTrue(all.isEverything)
        assertEquals((0..40).toList(), all.slots(0))
        assertIs<ClearSpec.Parsed.Error>(ClearSpec.parse(listOf("+모자")))
        assertTrue(ClearSpec.kind(ClearSpec.Scope.ARMORS, "ELYTRA"))
        assertTrue(ClearSpec.kind(ClearSpec.Scope.TOOLS, "IRON_AXE") && ClearSpec.kind(ClearSpec.Scope.WEAPONS, "IRON_AXE"))
    }

    // --- 염색 -------------------------------------------------------------------------------------------

    @Test
    fun `염색 값 읽기`() {
        assertEquals(DyeColors.Spec.Static(0xFF0000), DyeColors.parse("255,0,0"))
        assertEquals(DyeColors.Spec.Static(0x00FF7F), DyeColors.parse("#00ff7f"))
        assertEquals(DyeColors.Spec.Static(0xB02E26), DyeColors.parse("빨간색"))
        assertEquals(DyeColors.Spec.Static(null), DyeColors.parse("지우기"))
        assertEquals(DyeColors.Spec.Dynamic(DyeColors.Mode.PALETTE, listOf(0xFF0000, 0x0000FF)), DyeColors.parse("팔레트:#ff0000,#0000ff"))
        assertNull(DyeColors.parse("팔레트:초록?"))
        assertNull(DyeColors.parse("300,0,0"))
        assertEquals("palette:#ff0000,#0000ff", (DyeColors.parse("palette:#ff0000,#0000ff") as DyeColors.Spec.Dynamic).serialize())
    }

    private fun ctx(now: Long = 0, health: Double = 1.0, hurtAgo: Long = 99_999, combat: Boolean = false, slot: Int = 0, food: Double = 1.0, air: Double = 1.0) =
        DyeColors.Context(now, 6000, "minecraft:desert", health, food, air, hurtAgo, combat, slot)

    @Test
    fun `움직이는 색 - 체력 · 피격 · 전투 · 산소 · 물결`() {
        val health = DyeColors.Spec.Dynamic(DyeColors.Mode.HEALTH, emptyList())
        assertEquals(0xC62828, DyeColors.color(health, ctx(health = 0.0)))
        assertEquals(0x2E7D32, DyeColors.color(health, ctx(health = 1.0)))
        val hit = DyeColors.Spec.Dynamic(DyeColors.Mode.HIT, listOf(0x111111, 0x222222))
        assertEquals(0x222222, DyeColors.color(hit, ctx(hurtAgo = 100)))
        assertEquals(0x111111, DyeColors.color(hit, ctx(hurtAgo = 5000)))
        val combat = DyeColors.Spec.Dynamic(DyeColors.Mode.COMBAT, emptyList())
        assertEquals(0xC62828, DyeColors.color(combat, ctx(combat = true)))
        val air = DyeColors.Spec.Dynamic(DyeColors.Mode.AIR, emptyList())
        assertEquals(0xE53935, DyeColors.color(air, ctx(air = 0.0)))
        val wave = DyeColors.Spec.Dynamic(DyeColors.Mode.WAVE, emptyList())
        assertTrue(DyeColors.color(wave, ctx(slot = 0)) != DyeColors.color(wave, ctx(slot = 3)), "물결은 머리와 발의 색이 달라야 한다")
        assertEquals(0xE3C770, DyeColors.biome("minecraft:desert"))
        assertEquals(0x167E86, DyeColors.biome("minecraft:warped_forest"))
    }

    @Test
    fun `색 섞기`() {
        assertEquals(0x000000, DyeColors.lerp(0x000000, 0xFFFFFF, 0.0))
        assertEquals(0xFFFFFF, DyeColors.lerp(0x000000, 0xFFFFFF, 1.0))
        assertEquals(0x7F7F7F, DyeColors.lerp(0x000000, 0xFFFFFF, 0.5))
        assertEquals(0x804020, DyeColors.scale(0xFF8040, 0.5025))
    }

    // --- 설명 · 이름 제한 -----------------------------------------------------------------------------------

    @Test
    fun `설명 고치기`() {
        val lines = listOf("a", "b")
        assertEquals(listOf("a", "X"), LoreEdit.parse("2 X")!!.apply(lines, { it }, ""))
        assertEquals(listOf("a", "b", "", "Z"), LoreEdit.parse("4 Z")!!.apply(lines, { it }, ""))
        assertEquals(listOf("b"), LoreEdit.parse("1 지우기")!!.apply(lines, { it }, ""))
        assertNull(LoreEdit.parse("5 remove")!!.apply(lines, { it }, ""))
        assertEquals(listOf("N", "a", "b"), LoreEdit.parse("1 끼우기 N")!!.apply(lines, { it }, ""))
        assertEquals(listOf("x", "y"), LoreEdit.parse("* x|y")!!.apply(lines, { it }, ""))
        assertEquals(listOf("a", "b", "새 줄"), LoreEdit.parse("새 줄")!!.apply(lines, { it }, ""))
        assertEquals(emptyList(), LoreEdit.parse("지우기")!!.apply(lines, { it }, ""))
    }

    // --- 머리 -------------------------------------------------------------------------------------------

    @Test
    fun `머리 - 이름과 텍스처를 가른다`() {
        assertNull(Heads.textureValue("Notch"))
        val hash = "beea845cc0b58ff763decffe11cd1c845c5d09c3b04fe80b0663da5c7c699eb3"
        val value = assertNotNull(Heads.textureValue(hash))
        assertTrue(String(Base64.getDecoder().decode(value)).contains(Heads.TEXTURE_URL + hash))
        assertEquals(value, Heads.textureValue("http://textures.minecraft.net/texture/$hash"))
        assertEquals("eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUv", Heads.textureValue("eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUv"))
    }

    // --- NBT · 플레이어 파일 ---------------------------------------------------------------------------

    @Test
    fun `NBT 를 쓰고 읽으면 같다`() {
        val root = linkedMapOf<String, Any>(
            "id" to "minecraft:diamond", "count" to 3, "b" to 1.toByte(), "s" to 2.toShort(), "l" to 4L, "f" to 1.5f, "d" to 2.5,
            "name" to "한글", "list" to Nbt.NbtList(8, listOf("a", "b")), "ints" to intArrayOf(1, 2), "longs" to longArrayOf(3L),
            "sub" to linkedMapOf<String, Any>("x" to 1),
        )
        val again = Nbt.readCompressed(ByteArrayInputStream(Nbt.writeCompressed(root)))
        assertEquals("한글", again["name"])
        assertEquals(3, again["count"])
        assertEquals(1.toByte(), again["b"])
        assertEquals(Nbt.NbtList(8, listOf("a", "b")), again["list"])
        assertContentEquals(intArrayOf(1, 2), again["ints"] as IntArray)
        assertEquals(mapOf("x" to 1), again["sub"])
    }

    @Test
    fun `플레이어 파일에서 가방·엔더·장비를 뽑고 칸 번호를 떼고 판 번호를 붙인다`() {
        val item = { id: String, slot: Int? -> linkedMapOf<String, Any>("id" to id, "count" to 1).also { if (slot != null) it["Slot"] = slot.toByte() } }
        val root = linkedMapOf<String, Any>(
            "DataVersion" to 4903, "playerGameType" to 0,
            "Inventory" to Nbt.NbtList(10, listOf(item("minecraft:stone", 9), item("minecraft:iron_helmet", 103))),
            "EnderItems" to Nbt.NbtList(10, listOf(item("minecraft:dirt", 0))),
            "equipment" to linkedMapOf<String, Any>("offhand" to item("minecraft:shield", null)),
        )
        val data = PlayerDataFile.read(root)
        assertEquals(4903, data.dataVersion)
        assertEquals(listOf(PlayerDataFile.WHERE_INVENTORY, PlayerDataFile.WHERE_EQUIPMENT, PlayerDataFile.WHERE_ENDER, PlayerDataFile.WHERE_EQUIPMENT), data.items.map { it.where })
        val first = Nbt.readCompressed(ByteArrayInputStream(data.items[0].bytes))
        assertEquals(null, first["Slot"])
        assertEquals(4903, first["DataVersion"])
        assertEquals("minecraft:stone", first["id"])
    }

    // --- 검색 조건 --------------------------------------------------------------------------------------

    @Test
    fun `검색 조건`() {
        val c = assertIs<SearchCriteria.Parsed.Ok>(SearchCriteria.parse("diamond inmc:보석 이름:전설_검 인챈트:sharpness:5 최소:10 모드:survival")).criteria
        assertEquals(listOf("diamond", "inmc:보석"), c.refs)
        assertEquals("전설 검", c.name)
        assertEquals(listOf("sharpness" to 5), c.enchants)
        assertEquals(10, c.minCount)
        assertTrue(c.allowsMode("survival") && !c.allowsMode("CREATIVE"))
        assertIs<SearchCriteria.Parsed.Error>(SearchCriteria.parse("모드:하늘"))
        assertIs<SearchCriteria.Parsed.Error>(SearchCriteria.parse(""))
    }
}
