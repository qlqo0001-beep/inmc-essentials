package com.inmc.essentials.warp

import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 워프의 순수 규칙 — 파일 왕복(읽지 못한 칸 지키기) · 이름 · 개수 한도 · `/워프설정` 꼬리 · 화면 자리 · 그룹. */
class WarpRulesTest {

    private fun yaml(text: String) = YamlConfiguration().apply { loadFromString(text) }

    @Test
    fun `파일 왕복 - 모든 칸`() {
        val w = Warp(
            name = "광장", world = "spawn", x = 1.5, y = 64.0, z = -2.5, yaw = 90f, pitch = 10f, group = "마을", permission = true,
            showLocked = false, hidden = true, icon = "GRASS_BLOCK", iconItem = "AAAA", displayName = "<gold>광장", description = listOf("첫 줄", "둘째"),
            autoLore = false, slot = 5, randomYaw = true, extra = listOf("spawn;1;2;3;0;0"), creator = UUID(1, 2), creatorName = "Steve", created = 42L,
        )
        val file = WarpFile(listOf(w, Warp("b", "world", 0.0, 0.0, 0.0)))
        val back = WarpCodec.read(yaml(WarpCodec.write(file).saveToString()))
        assertEquals(file.warps, back.warps)
        assertTrue(back.rejected.isEmpty())
    }

    @Test
    fun `읽지 못한 칸은 버리지 않고 되써 넣는다 - 같은 이름 둘째도`() {
        val text = """
            warps:
            - { name: ok, world: world, x: 1, y: 2, z: 3 }
            - { name: broken, world: world, x: one, y: 2, z: 3 }
            - { name: OK, world: world, x: 9, y: 9, z: 9 }
        """.trimIndent()
        val file = WarpCodec.read(yaml(text))
        assertEquals(listOf("ok"), file.warps.map { it.name })
        assertEquals(1.0, file.warps.single().x)
        assertEquals(2, file.rejected.size)
        val again = WarpCodec.read(yaml(WarpCodec.write(file).saveToString()))
        assertEquals(2, again.rejected.size)
        assertEquals(file.warps, again.warps)
    }

    @Test
    fun `이름 길이와 글자`() {
        val s = WarpSettings()
        assertNull(WarpRules.nameProblem("광장", s))
        assertEquals("warps-name-length", WarpRules.nameProblem("a".repeat(17), s))
        assertEquals("warps-invalid-name", WarpRules.nameProblem("a.b", s))
        assertEquals("warps-name-length", WarpRules.nameProblem("ab", s.copy(minLength = 4)))
    }

    @Test
    fun `만들기 개수 - 숫자 권한 중 큰 것, 없으면 기본(-1 무제한)`() {
        val p = WarpRules.LIMIT_PREFIX
        assertNull(WarpRules.limit(emptyList(), WarpSettings()))
        assertEquals(0, WarpRules.limit(emptyList(), WarpSettings(defaultLimit = 0)))
        assertEquals(5, WarpRules.limit(listOf("${p}2", "${p}5", "${p}x"), WarpSettings(defaultLimit = 0)))
    }

    @Test
    fun `워프설정 꼬리 - CMI 순서와 한글 낱말`() {
        assertEquals(WarpRequest(name = "spawn"), WarpRules.parseSet(listOf("spawn")))
        assertEquals(
            WarpRequest(name = "spawn", permission = true, iconFromHand = true, slot = 13, autoLore = false),
            WarpRules.parseSet(listOf("spawn", "true", "hand", "13", "false")),
        )
        assertEquals(
            WarpRequest(name = "광장", group = "마을", iconHead = "Steve", location = "world;1;2;3;0;0", overwrite = true, icon = null),
            WarpRules.parseSet(listOf("광장", "그룹:마을", "머리:Steve", "world;1;2;3;0;0", "덮어쓰기")),
        )
        assertEquals(WarpRequest(name = "a", iconHead = "", icon = "diamond"), WarpRules.parseSet(listOf("a", "머리", "diamond")))
        assertEquals(WarpRequest(name = "a", permission = false, group = "g"), WarpRules.parseSet(listOf("a", "끔", "-g:g")))
    }

    @Test
    fun `화면 자리와 그룹`() {
        val warps = listOf(Warp("a", "w", 0.0, 0.0, 0.0, slot = 3), Warp("b", "w", 0.0, 0.0, 0.0), Warp("c", "w", 0.0, 0.0, 0.0, group = "마을"),
            Warp("d", "w", 0.0, 0.0, 0.0, group = "마을"), Warp("e", "w", 0.0, 0.0, 0.0, group = "Shop"))
        assertEquals(mapOf(2 to "a", 0 to "b", 1 to "c", 3 to "d", 4 to "e"), WarpRules.arrange(warps) { it.slot }.mapValues { it.value.name })
        assertEquals(listOf("Shop", "마을"), WarpRules.groups(warps + Warp("f", "w", 0.0, 0.0, 0.0, group = "shop")))
        assertEquals(listOf("c", "d"), warps.filter { WarpRules.inGroup(it, "마을") }.map { it.name })
        assertEquals(listOf("a", "b"), warps.filter { WarpRules.inGroup(it, "") }.map { it.name })
        assertEquals(5, warps.count { WarpRules.inGroup(it, null) })
    }

    @Test
    fun `위치 글자`() {
        assertEquals("world" to listOf(1.0, 2.0, 3.0, 90.0, 0.0), WarpCodec.splitSpot("world;1;2;3;90;0"))
        assertNull(WarpCodec.splitSpot("world;1;2"))
        assertNull(WarpCodec.splitSpot("world;a;2;3"))
    }

    @Test
    fun `설정 - 최소가 최대보다 크면 최대를 올린다`() {
        val s = WarpSettings.from(yaml("name-min-length: 20\nname-max-length: 4\ndefault-limit: -9"))
        assertEquals(20, s.minLength)
        assertEquals(20, s.maxLength)
        assertEquals(-1, s.defaultLimit)
    }
}
