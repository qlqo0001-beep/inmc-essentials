package com.inmc.essentials.hologram

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CMI `Saves/Holograms.yml` 판독 — 표본은 CMI 9.8.9.8 jar 의 deserialize 열쇠 목록(javap)으로 짰다:
 * `CMIHologram.deserialize` · `CMIHologramSettings` · `CMIHologramTextSettings` · `CMIHologramPageSettings` · `CMIHologramInteraction` ·
 * `CMIHologramIconSettings`. 테섭 파일은 비어 있다.
 */
class HologramCmiShapeTest {

    private fun yaml(text: String) = YamlConfiguration().apply { loadFromString(text.trimIndent()) }

    @Test
    fun `새 모양 - Settings Range 섹션, 틱 단위 주기, Change 는 자동 넘김, 누르기 켜짐, 정렬, 위치는 CMI 의 아래 기준`() {
        val read = HologramCodec.fromCmi(yaml(
            """
            Welcome_board:
              Loc: 'world;10.5;72.0;-4.5;0.0;0.0'
              Enabled: true
              ArmorStand: false
              Lines:
              - '{#FFAA00}어서 오세요 [playerName]'
              - 'ICON:DIAMOND'
              - '!nextpage!'
              - '<T>스폰으로</T><C>asPlayer! spawn</C>'
              Settings:
                Bill: VERTICAL
                Perm: true
                Scale: '1.5;1.5'
                Range:
                  Visual: 20
                  Update: 6
                Interval: 50
                LOS: true
              Text:
                Shadow: false
                See: true
                BColor: '{#112233}'
                BAlpha: 128
                Align: LEFT
              Page:
                Change: 200
                Auto: true
              Interaction:
                Enabled: true
                Commands:
                - 'cmi warp spawn [playerName]'
                BasePrefix: '{#white}'
                Particle:
                  ShowClick: true
                  Mystery2: 1
              Board:
                Something: 1
              Mystery: 7
            Broken:
              Loc: 'nowhere'
            """,
        ))
        val h = read.holograms.single()
        assertEquals("Welcome_board", h.name)
        assertEquals(10.5 to -4.5, h.x to h.z)
        // CMI Loc 은 아래 — 가운데(Loc + 0.25 + 첫 쪽 2줄 × 0.25 / 2)를 맞춘다: 우리 높이 (글 0.28 + 아이콘 0.55) × 크기 1.5 의 절반 위가 첫 줄의 윗자리.
        assertEquals(72.0 + 0.25 + 0.25 + (0.28 + 0.55) * 1.5 / 2, h.y, 1e-9)
        assertEquals(2, h.pages.size)
        assertEquals(HoloLine("<#FFAA00>어서 오세요 {player}"), h.pages[0].lines[0])
        assertEquals(HoloLine(icon = "minecraft:diamond"), h.pages[0].lines[1])
        assertEquals("스폰으로", h.pages[1].lines[0].text)
        assertEquals(listOf("asPlayer! spawn"), h.pages[1].lines[0].actions.resolve(HoloClick.LEFT))
        assertEquals(listOf("cmi warp spawn {player}"), h.pages[0].actions.resolve(HoloClick.RIGHT))
        assertEquals(20, h.viewRange)
        assertEquals(6, h.updateRange)
        assertEquals(3, h.updateSeconds) // 50틱 = 2.5초 → 3초
        assertEquals(10, h.pageSeconds) // 200틱
        assertEquals(HologramCodec.HOLO_SEE_PREFIX + "welcome_board", h.permission)
        assertEquals("VERTICAL", h.billboard)
        assertEquals(1.5, h.scale)
        assertEquals("#80112233", h.background)
        assertTrue(!h.shadow && h.seeThrough && h.clickPages)
        assertEquals("LEFT", h.alignment)
        assertTrue(read.unreadable.any { "Broken" in it }, "위치를 못 읽은 홀로그램을 보고해야 한다: ${read.unreadable}")
        // 모르는 열쇠 — 맨 위와 섹션 속까지
        assertTrue(read.unreadable.any { "Mystery=7" in it }, read.unreadable.toString())
        assertTrue(read.unreadable.any { "Interaction.Particle.Mystery2=1" in it }, read.unreadable.toString())
        // 알지만 옮기지 않는 열쇠 — 값과 함께
        for (path in listOf("Settings.LOS=true", "Interaction.BasePrefix", "Interaction.Particle.ShowClick=true", "Board=")) {
            assertTrue(read.notes.any { path in it }, "$path 를 알려야 한다: ${read.notes}")
        }
    }

    @Test
    fun `새 모양 - Page Auto 는 자동 넘김과 무관(Change 만), Interaction Enabled false 면 명령을 붙이지 않고 알린다`() {
        val read = HologramCodec.fromCmi(yaml(
            """
            quiet:
              Loc: 'world;0;64;0'
              Lines: ['a', '!nextpage!', 'b']
              Page:
                Change: 100
                Auto: false
              Interaction:
                Enabled: false
                Commands: ['say hi']
            """,
        ))
        val h = read.holograms.single()
        assertEquals(5, h.pageSeconds) // Change 100틱 — Auto false(버튼 줄 없음)는 자동 넘김을 끄지 않는다
        assertTrue(h.pages.all { it.actions.isEmpty })
        assertTrue(!h.clickPages)
        assertTrue(read.notes.any { "say hi" in it }, read.notes.toString())
    }

    @Test
    fun `옛 모양 - 맨 위 Interval·PageInterval 은 초, ND 아래 Bill·ScaleW·AutoPage·Shadow`() {
        val read = HologramCodec.fromCmi(yaml(
            """
            old:
              Loc: 'spawn:0:80:0:0:0'
              Lines: ['한 줄', '!nextpage!', '둘째 쪽']
              RangeExtra: 30
              Range: 8
              Interval: 2.0
              PageInterval: 4.0
              RequiresPermission: true
              Interactable: true
              Commands: ['[nextpage]', 'delay! 5 say x']
              Filler: 3
              ND:
                Bill: FIXED
                Yaw: 45.0
                ScaleW: 2.0
                ScaleH: 1.0
                AutoPage: true
                Shadow: false
                BColor: '{#000000}'
                BAlpha: 0
                SL: 15
                Weird: true
            """,
        ))
        val h = read.holograms.single()
        assertEquals("spawn", h.world)
        assertEquals(30, h.viewRange)
        assertEquals(8, h.updateRange)
        assertEquals(2, h.updateSeconds)
        assertEquals(4, h.pageSeconds)
        assertEquals(HologramCodec.HOLO_SEE_PREFIX + "old", h.permission)
        assertEquals("FIXED", h.billboard)
        assertEquals(45f, h.yaw)
        assertEquals(2.0, h.scale)
        assertTrue(!h.shadow)
        assertEquals("none", h.background) // BAlpha 0
        assertEquals(listOf("[nextPage]", "delay! 5 say x"), h.pages[0].actions.resolve(HoloClick.ANY))
        assertTrue(read.notes.any { "delay!" in it }, read.notes.toString())
        assertTrue(read.notes.any { "ND.SL=15" in it }, read.notes.toString())
        assertTrue(read.notes.any { "Filler=3" in it }, read.notes.toString())
        assertTrue(read.notes.any { "가로" in it }, "가로·세로 크기가 다르면 알린다: ${read.notes}")
        assertTrue(read.unreadable.any { "ND.Weird=true" in it }, read.unreadable.toString())
    }

    @Test
    fun `옛 모양 - 섹션이 없으면 옛 열쇠, 섹션이 있으면 맨 위 옛 열쇠는 모르는 것으로`() {
        val read = HologramCodec.fromCmi(yaml(
            """
            mixed:
              Loc: 'world;0;64;0'
              Lines: ['x']
              Settings:
                Perm: false
              RequiresPermission: true
            """,
        ))
        val h = read.holograms.single()
        assertNull(h.permission)
        assertTrue(read.unreadable.any { "RequiresPermission=true" in it }, read.unreadable.toString())
    }

    @Test
    fun `Interaction 에 Enabled 가 없으면 꺼짐(CMI 기본) - 명령도 CText 명령도 붙이지 않고 알린다`() {
        // CMIHologramInteraction 은 interactable=false 가 기본이고 serialize 는 켜졌을 때만 Enabled 를 쓴다 — 실제 파일의 모양.
        val read = HologramCodec.fromCmi(yaml(
            """
            shop:
              Loc: 'world;0;64;0'
              Lines: ['<T>사기</T><C>asConsole! give [playerName] diamond 1</C>', '!nextpage!', 'b']
              Settings:
                Perm: false
              Interaction:
                Commands: ['asConsole! give [playerName] diamond 1']
            """,
        ))
        val h = read.holograms.single()
        assertTrue(h.pages.all { pg -> pg.actions.isEmpty && pg.lines.all { it.actions.isEmpty } }, h.pages.toString())
        assertTrue(!h.clickPages)
        assertTrue(read.notes.count { "누르기가 꺼져" in it } == 2, read.notes.toString())
    }

    @Test
    fun `Page Auto true 는 쪽 넘김 버튼 - 누르면 넘기기를 켜고 알린다, 자동 넘김은 켜지 않는다`() {
        val read = HologramCodec.fromCmi(yaml(
            """
            pages:
              Loc: 'world;0;64;0'
              Lines: ['a', '!nextpage!', 'b']
              Page:
                Auto: true
            """,
        ))
        val h = read.holograms.single()
        assertTrue(h.clickPages)
        assertNull(h.pageSeconds)
        assertTrue(read.notes.any { "버튼" in it }, read.notes.toString())
    }

    @Test
    fun `배경 - 없는 쪽은 CMI 기본(검정 · 125)으로 채운다`() {
        fun bg(text: String): String = HologramCodec.fromCmi(yaml(
            """
            b:
              Loc: 'world;0;64;0'
              Lines: ['x']
              Settings:
                Perm: false
            $text
            """,
        )).holograms.single().background
        assertEquals("#7D000000", bg(""))
        assertEquals("#7DFF0000", bg("  Text:\n                BColor: '{#ff0000}'"))
        assertEquals("#DC000000", bg("  Text:\n                BAlpha: 220"))
        assertEquals("#7D55FF55", bg("  Text:\n                BColor: '{#green}'"))
    }

    @Test
    fun `정렬·감기 너비·글자 투명도를 옮긴다(새 모양 Text · 옛 모양 ND)`() {
        val read = HologramCodec.fromCmi(yaml(
            """
            a:
              Loc: 'world;0;64;0'
              Lines: ['x']
              Settings:
                Perm: false
              Text:
                Align: RIGHT
                Line: 200
                TAlpha: 128
            b:
              Loc: 'world;0;64;0'
              Lines: ['x']
              ND:
                Align: LEFT
                LineW: 150
                TAlpha: 64
            """,
        ))
        val a = read.holograms.first { it.name == "a" }
        val b = read.holograms.first { it.name == "b" }
        assertEquals(Triple("RIGHT", 200, 128), Triple(a.alignment, a.lineWidth, a.textOpacity))
        assertEquals(Triple("LEFT", 150, 64), Triple(b.alignment, b.lineWidth, b.textOpacity))
        assertTrue(read.notes.none { "Align" in it || "TAlpha" in it || "Line" in it }, read.notes.toString())
    }

    @Test
    fun `CMI 만의 자리(변수 · cmi PAPI)와 표시 이름 바꿈을 알린다`() {
        val read = HologramCodec.fromCmi(yaml(
            """
            c:
              Loc: 'world;0;64;0'
              Lines: ['잔고 %cmi_user_balance%', '[playerDisplayName] 님 [world]', '[VIP] 그대로', '_']
              Settings:
                Perm: false
              Interaction:
                Enabled: true
                Commands: ['/cmi warp [world] [playerName]']
            """,
        ))
        val h = read.holograms.single()
        assertEquals("", h.pages[0].lines[3].text) // CMI 의 빈 줄 '_'
        assertTrue(read.notes.any { "%cmi_user_balance%" in it }, read.notes.toString())
        assertTrue(read.notes.any { "[world]" in it && "줄" in it }, read.notes.toString())
        assertTrue(read.notes.any { "[world]" in it && "클릭 명령" in it }, read.notes.toString())
        assertTrue(read.notes.any { "표시 이름" in it }, read.notes.toString())
        assertTrue(read.notes.none { "[VIP]" in it }, read.notes.toString())
        assertEquals(listOf("/cmi warp [world] {player}"), h.pages[0].actions.resolve(HoloClick.ANY))
    }
}
