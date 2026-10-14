package com.inmc.essentials.kit

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CMI 킷 가져오기 — 예제 Kits.yml 은 내용까지 같을 때만 건너뛴다 · Group/CommandName 은 Group 이 먼저. */
class KitImportRulesTest {

    private fun yaml(text: String = KitRules.CMI_DEFAULT_KITS) = YamlConfiguration().apply { loadFromString(text) }

    @Test
    fun `CMI 가 깔아 준 그대로면 예제로 본다`() {
        assertTrue(KitRules.isCmiDefault(yaml()))
    }

    @Test
    fun `이름은 같아도 내용을 고쳤으면 운영자의 킷으로 가져온다`() {
        assertFalse(KitRules.isCmiDefault(yaml().apply { set("Newbie.Delay", 600) }), "지연")
        assertFalse(KitRules.isCmiDefault(yaml().apply { set("Newbie.Items.1.amount", 64) }), "아이템 개수")
        assertFalse(KitRules.isCmiDefault(yaml().apply { set("Ad3.Items.1.type", "DIAMOND") }), "아이템 종류")
        assertFalse(KitRules.isCmiDefault(yaml().apply { set("Advanced.Commands", listOf("say hi")) }), "명령")
        assertFalse(KitRules.isCmiDefault(yaml().apply { set("Newbie.Conditions", listOf("vip.kit")) }), "조건")
        assertFalse(KitRules.isCmiDefault(yaml().apply { set("Newbie.Items.1.meta.display-name", "§a돌") }), "아이템 이름")
        assertFalse(KitRules.isCmiDefault(yaml().apply { set("Daily.Delay", 86400) }), "킷이 더 있음")
        assertFalse(KitRules.isCmiDefault(yaml().apply { set("Ad1", null) }), "킷이 빠짐")
    }

    @Test
    fun `지문은 따옴표·순서와 무관하다`() {
        val a = yaml("K:\n  Delay: 5\n  Group: 'g'\n  Items:\n    '1':\n      type: STONE\n      amount: 2\n")
        val b = yaml("K:\n  Items:\n    '1':\n      amount: 2\n      type: STONE\n  Group: g\n  Delay: 5\n")
        assertEquals(KitRules.fingerprint(a.getConfigurationSection("K")!!), KitRules.fingerprint(b.getConfigurationSection("K")!!))
    }

    @Test
    fun `그룹 - Group 이 있으면 Group, 없으면 CommandName`() {
        assertEquals("resources", KitRules.importGroup("resources", "Ad"))
        assertEquals("Ad", KitRules.importGroup("", "Ad"))
        assertEquals("Ad", KitRules.importGroup(null, " Ad "))
        assertEquals("", KitRules.importGroup(null, null))
    }

    // --- 2026-10-14 검토: ItemMeta ↔ 섹션 지문 — 색 코드·인챈트 이름의 판 차이는 무시, 인챈트 종류·레벨은 비교 ---

    @Test
    fun `아이템 지문 - 색 코드를 떼고 인챈트는 지금 판 열쇠로 고른다`() {
        val fromSection = KitRules.metaText("§3Stone", listOf("§2Name §3{USERNAME}", "§x§1§2§3§4§5§6Hex"), mapOf("ARROW_DAMAGE" to 3, "THORNS" to 5))
        val fromMeta = KitRules.metaText("§r§3Stone", listOf("§fName {USERNAME}", "Hex"), mapOf("minecraft:power" to 3, "thorns" to 5))
        assertEquals(fromSection, fromMeta)
        assertEquals("Stone", KitRules.stripColor("§r§3Stone§r"))
        assertEquals("power", KitRules.enchantKey("ARROW_DAMAGE"))
        assertEquals("power", KitRules.enchantKey("minecraft:power"))
        assertEquals("sharpness", KitRules.enchantKey("DAMAGE_ALL"))
        assertEquals("mending", KitRules.enchantKey("MENDING"))
    }

    @Test
    fun `예제 알아보기 - 인챈트 종류·레벨을 고쳤으면 운영자의 킷, 이름만 새 판 이름으로 바뀐 것은 예제`() {
        assertFalse(KitRules.isCmiDefault(yaml().apply { set("Newbie.Items.1.meta.enchants.THORNS", 1) }), "인챈트 레벨")
        assertFalse(KitRules.isCmiDefault(yaml().apply { set("Newbie.Items.1.meta.enchants.MENDING", 1) }), "인챈트 추가")
        assertFalse(KitRules.isCmiDefault(yaml().apply { set("Newbie.Items.1.meta.enchants.THORNS", null) }), "인챈트 빠짐")
        val renamed = yaml().apply { set("Newbie.Items.1.meta.enchants.ARROW_DAMAGE", null); set("Newbie.Items.1.meta.enchants.power", 3) }
        assertTrue(KitRules.isCmiDefault(renamed), "ARROW_DAMAGE → power 는 같은 인챈트")
        assertTrue(KitRules.isCmiDefault(yaml().apply { set("Newbie.Items.1.meta.display-name", "§a§lStone") }), "색만 바꾼 이름은 같다고 본다")
        assertFalse(KitRules.isCmiDefault(yaml().apply { set("Newbie.Items.1.meta.lore", listOf("§2Name §3{USERNAME}")) }), "설명 줄이 빠짐")
    }

    @Test
    fun `예제 글 알아보기 - 실제 파일의 주석·ItemMeta 줄·줄 끝 공백은 무시한다`() {
        val real = "# Player should have appropriate permission\n\n" + KitRules.CMI_DEFAULT_KITS
            .replace("  Items:\n", "  Items:      \n").replace("      meta:\n", "      meta:\n        ==: ItemMeta\n        meta-type: UNSPECIFIC\n")
        assertTrue(KitRules.looksLikeCmiDefaultText(real))
        assertFalse(KitRules.looksLikeCmiDefaultText(real.replace("amount: 70", "amount: 64")))
        assertTrue(KitRules.cmiDefaultsAsItemMeta().contains("==: ItemMeta"))
    }
}
