package com.inmc.essentials.decor

import com.inmc.essentials.service.ImportReport
import net.kyori.adventure.text.Component
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** decor 기능의 순수 규칙 — 설정 · 표지판 글 · 동적 표지판 장면 · 자세 계산 · 템플릿·동적 표지판 저장 왕복 · CMI 옮기기. */
class DecorLogicTest {

    private fun shipped(): YamlConfiguration = YamlConfiguration.loadConfiguration(File("src/main/resources/settings/decor.yml"))

    @Test
    fun `배포 설정은 코드의 기본값과 같고 이상한 값은 기본값`() {
        assertEquals(DecorSettings(), DecorSettings.from(shipped()))
        val y = YamlConfiguration()
        y.loadFromString("armorstand:\n  rotate-step: 500\n  min-scale: 4\n  max-scale: 2\nsign-edit:\n  blacklist: []\ndynamic-sign:\n  marquee-width: 1\n")
        val s = DecorSettings.from(y)
        assertEquals(DecorSettings().armorStand.rotateStep, s.armorStand.rotateStep)
        assertEquals(4.0, s.armorStand.minScale)
        assertEquals(4.0, s.armorStand.maxScale) // 최대가 최소보다 작으면 최소로
        assertTrue(s.sign.blacklist.isEmpty())
        assertEquals(DecorSettings().dynamic.marqueeWidth, s.dynamic.marqueeWidth)
    }

    @Test
    fun `금지 목록은 첫 줄을 대소문자 없이 본다`() {
        val rule = DecorSettings.Sign()
        assertTrue(rule.blocked("[shop]"))
        assertTrue(rule.blocked("  [ChestShop] "))
        assertTrue(!rule.blocked("[Shop] 사과"))
        assertTrue(!rule.blocked(""))
    }

    @Test
    fun `표지판 글 - 색 권한은 색만, 서식 권한은 클릭까지`() {
        val raw = "&a초록 <click:run_command:'/op me'>눌러</click>"
        val plain = DecorText.parse(raw, DecorText.Level.PLAIN)
        assertEquals(raw, DecorText.plain(plain))
        val color = DecorText.parse(raw, DecorText.Level.COLOR)
        fun clicks(c: Component): Boolean = c.clickEvent() != null || c.children().any { clicks(it) }
        assertTrue(!clicks(color), "색 권한에 클릭 사건")
        assertTrue(DecorText.plain(color).startsWith("초록"))
        assertTrue(clicks(DecorText.parse(raw, DecorText.Level.FORMAT)))
        assertTrue(DecorText.hasMarkup("&a") && DecorText.hasMarkup("<red>") && !DecorText.hasMarkup("[Shop]"))
    }

    @Test
    fun `표지판편집 줄 나누기`() {
        assertEquals(listOf(0 to "가"), DecorText.signLines(1, "가"))
        assertEquals(listOf(2 to "가", 3 to "나"), DecorText.signLines(3, "가\\n나\\n다"))
        assertEquals(listOf(1 to ""), DecorText.signLines(2, ""))
    }

    @Test
    fun `동적 표지판 장면 - 굴리기와 흐르기`() {
        assertEquals(listOf("a", "b", "", ""), SignFrames.window(listOf("a", "b"), 7))
        assertEquals(listOf("a", "b", "c", "d"), SignFrames.window(listOf("a", "b", "c", "d", "e"), 0))
        assertEquals(listOf("e", "a", "b", "c"), SignFrames.window(listOf("a", "b", "c", "d", "e"), 4))
        assertEquals("짧은글", SignFrames.marquee("짧은글", 15, 3))
        assertEquals("abcd", SignFrames.marquee("abcdefgh", 4, 0))
        assertEquals("h   ", SignFrames.marquee("abcdefgh", 4, 7))
        assertEquals("abcd", SignFrames.marquee("abcdefgh", 4, 11)) // 한 바퀴(8+3)
        assertTrue(SignFrames.isMarquee("{스크롤}<gold>안녕") && !SignFrames.isMarquee("<gold>안녕"))
        assertEquals("<gold>안녕", SignFrames.stripMarker("{스크롤}<gold>안녕"))
        assertEquals("<gold><bold>", SignFrames.leadingTags("<gold><bold>안녕 <red>x"))
        assertEquals("", SignFrames.leadingTags("안녕"))
    }

    @Test
    fun `자세 계산`() {
        assertEquals(-170.0, Pose.wrap(190.0))
        assertEquals(180.0, Pose.wrap(-180.0))
        assertEquals(0.0, Pose.wrap(720.0))
        assertEquals(5.0, Pose.delta(5.0, right = false, shift = false))
        assertEquals(-50.0, Pose.delta(5.0, right = true, shift = true))
        assertEquals(3.0, Pose.scale(9.0, 0.5, 3.0))
        assertEquals(0.55, Pose.scale(0.551, 0.5, 3.0))
        assertTrue(Pose.withinMove(1.0, 1.0, 1.0, 2.0) && !Pose.withinMove(2.0, 0.1, 0.0, 2.0))
    }

    @Test
    fun `거치대 모양 저장 왕복`() {
        val t = StandTemplate(head = StandTemplate.Angles(10.0, -20.5, 30.0), small = true, arms = true, glowing = true, locked = true, scale = 1.5)
        val y = YamlConfiguration()
        t.write(y.createSection("a"))
        val back = YamlConfiguration().apply { loadFromString(y.saveToString()) }
        assertEquals(t, StandTemplate.read(back.getConfigurationSection("a")!!))
        assertTrue(StandTemplate.NAME.matches("의자_1") && !StandTemplate.NAME.matches("a.b") && !StandTemplate.NAME.matches("Big"))
    }

    @Test
    fun `동적 표지판 저장 왕복`() {
        val signs = listOf(
            DynamicSigns.DynSign(1, "world", 10, 64, -3, listOf("<gold>%server_online%", "{스크롤}길고 긴 글"), 5, 16, false),
            DynamicSigns.DynSign(7, "spawn", 0, 70, 0, listOf(""), 2, 8, true),
        )
        val back = DynamicSigns.read(YamlConfiguration().apply { loadFromString(DynamicSigns.write(signs).saveToString()) })
        assertEquals(signs.associateBy { it.id }, back)
    }

    @Test
    fun `CMI 표지판·거치대 설정과 동적 표지판을 옮긴다`() {
        val cmi = YamlConfiguration()
        cmi.loadFromString(
            """
            Optimizations:
              ArmorStands:
                CheckBlockPlace: false
                MinScale: 0.25
                MaxScale: 5.0
                Templates:
                  SpecificPermission: true
              SignEdit:
                BlackList:
                - '[Private]'
                - '[Lift]'
            Signs:
              editBlackList:
              - '[Shop]'
              RequireSneaking: false
              DialogEditor: true
            """.trimIndent(),
        )
        val out = shipped()
        val report = ImportReport()
        CmiDecorImport.settings(cmi, out, report)
        val s = DecorSettings.from(out)
        assertEquals(listOf("[Shop]", "[Private]", "[Lift]"), s.sign.blacklist)
        assertTrue(!s.sign.requireSneaking && s.sign.dialog)
        assertTrue(s.armorStand.templatePermission)
        // CheckBlockPlace 는 옮기지 않는다(명령으로 열 때 늘 보호 구역 확인) — 보고서에 남는다.
        assertTrue(report.details().any { "CheckBlockPlace" in it })
        assertTrue(!out.contains("armorstand.check-build"))
        assertEquals(0.25, s.armorStand.minScale)
        assertEquals(5.0, s.armorStand.maxScale)

        val ds = YamlConfiguration()
        ds.loadFromString(
            """
            '1':
              Loc: world;10.5;64.0;-3.2;0;0
              Lines: ['&6Hello', '%player_name%']
              Interval: 3
              Personal: true
              Range: 20
            '2':
              Loc: 'spawn:1:2:3'
              Lines: []
            '3':
              Loc: nowhere
            '4':
              Loc: world;10;64;-4
            """.trimIndent(),
        )
        val existing = listOf(DynamicSigns.DynSign(9, "world", 99, 64, 99, listOf("x"), 5, 16, false))
        val imported = CmiDecorImport.dynamicSigns(ds, existing, report)
        assertEquals(2, imported.size)
        assertEquals(DynamicSigns.DynSign(0, "world", 10, 64, -4, listOf("&6Hello", "%player_name%"), 3, 20, true), imported[0])
        assertEquals(DynamicSigns.DynSign(0, "spawn", 1, 2, 3, listOf(""), 5, 16, false), imported[1])
        assertTrue(report.details().any { "nowhere" in it && "못 읽음" in it })
        assertTrue(report.details().any { "이미 동적 표지판" in it })
        assertNull(CmiDecorImport.parseLocation("world;x;1;2"))
    }

    @Test
    fun `CMI 에서 꺼 둔 decor 모듈은 꺼진다`() {
        val modules = YamlConfiguration()
        modules.loadFromString("paintingEditor: false\narmorstand: false\nsignEdit: false\ndynamicSigns: true\n")
        val out = shipped()
        val report = ImportReport()
        CmiDecorImport.settings(YamlConfiguration(), out, report, modules)
        val s = DecorSettings.from(out)
        assertTrue(!s.painting.enabled && !s.armorStand.shiftClick && !s.sign.enabled)
        assertEquals(3, report.details().count { "Modules.yml" in it })
    }

    @Test
    fun `장비 잠금 - 잠그기는 켜고 끄기 권한, 풀기는 any 만`() {
        assertTrue(ArmorStands.lockToggleAllowed(currentlyLocked = false, canToggle = true, any = false))
        assertTrue(!ArmorStands.lockToggleAllowed(currentlyLocked = true, canToggle = true, any = false))
        assertTrue(ArmorStands.lockToggleAllowed(currentlyLocked = true, canToggle = false, any = true))
        assertTrue(!ArmorStands.lockToggleAllowed(currentlyLocked = false, canToggle = false, any = false))
    }

    @Test
    fun `클릭 사건 찾기 - 자식까지, 색 권한 글에는 없다`() {
        val nested = Component.text("a").append(Component.text("b").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/say x")))
        assertTrue(DecorText.hasClick(nested))
        assertTrue(!DecorText.hasClick(DecorText.parse("<click:run_command:'/op me'>x</click>", DecorText.Level.COLOR)))
        assertTrue(DecorText.hasClick(DecorText.parse("<click:run_command:'/spawn'>x</click>", DecorText.Level.FORMAT)))
    }
}
