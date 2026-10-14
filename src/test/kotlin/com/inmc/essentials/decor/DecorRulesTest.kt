package com.inmc.essentials.decor

import net.kyori.adventure.text.minimessage.MiniMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 2026-10-10 리뷰 뒤의 decor 규칙 — 바닐라 편집 화면 판정 · 템플릿 권한 노드 · 동적 표지판 클릭 벗기기 · CMI shiftSignEdit. */
class DecorRulesTest {

    @Test
    fun `바닐라 편집 화면 - 권한·개인 설정·웅크림·금지 목록·동적 표지판`() {
        assertTrue(SignEdit.vanillaOpenAllowed(permitted = true, toggle = true, requireSneaking = true, sneaking = true, blacklisted = false, dynamicLocked = false))
        assertFalse(SignEdit.vanillaOpenAllowed(permitted = true, toggle = true, requireSneaking = true, sneaking = false, blacklisted = false, dynamicLocked = false))
        assertTrue(SignEdit.vanillaOpenAllowed(permitted = true, toggle = true, requireSneaking = false, sneaking = false, blacklisted = false, dynamicLocked = false))
        assertFalse(SignEdit.vanillaOpenAllowed(permitted = true, toggle = false, requireSneaking = false, sneaking = true, blacklisted = false, dynamicLocked = false))
        assertFalse(SignEdit.vanillaOpenAllowed(permitted = true, toggle = true, requireSneaking = false, sneaking = true, blacklisted = true, dynamicLocked = false))
        assertFalse(SignEdit.vanillaOpenAllowed(permitted = true, toggle = true, requireSneaking = false, sneaking = true, blacklisted = false, dynamicLocked = true))
        // .sign.edit 가 없으면 나머지가 다 맞아도 막는다(개인 설정은 권한 없는 사람에게 기본 켬을 돌려준다)
        assertFalse(SignEdit.vanillaOpenAllowed(permitted = false, toggle = true, requireSneaking = true, sneaking = true, blacklisted = false, dynamicLocked = false))
        assertFalse(SignEdit.vanillaOpenAllowed(permitted = false, toggle = true, requireSneaking = false, sneaking = false, blacklisted = false, dynamicLocked = false))
    }

    @Test
    fun `템플릿 권한 노드는 선언된 템플릿 노드와 다른 가지`() {
        val declared = setOf(DecorFeature.ARMORSTAND_TEMPLATE_SAVE, DecorFeature.ARMORSTAND_TEMPLATE_GIVE, DecorFeature.ARMORSTAND_TEMPLATE_FREE)
        for (name in listOf("save", "give", "free", "use", "vip")) assertFalse(ArmorStands.templateNode(name) in declared, name)
        assertEquals("inmcessentials.decor.armorstand.template.use.vip", ArmorStands.templateNode("vip"))
    }

    @Test
    fun `동적 표지판 - 원문에 없던 클릭은 벗기고 글·색은 남긴다`() {
        val injected = MiniMessage.miniMessage().deserialize("<gold>1위 <click:run_command:'/op me'>이름</click> <hover:show_text:'a'>h</hover>")
        val stripped = DecorText.withoutInjectedClicks("<gold>1위 %top_name%", injected)
        assertFalse(DecorText.hasClick(stripped))
        assertEquals(DecorText.plain(injected), DecorText.plain(stripped))
        assertTrue(DecorText.hasClick(DecorText.withoutInjectedClicks("<click:run_command:'/spawn'>스폰</click>", injected)))
    }

    @Test
    fun `CMI users Options 의 shiftSignEdit 칸`() {
        assertEquals(false, CmiDecorImport.shiftSignEdit("1:0:1:1"))
        assertEquals(true, CmiDecorImport.shiftSignEdit("1:1:0"))
        assertNull(CmiDecorImport.shiftSignEdit("1:")) // 빈 칸 — 1/0 이 아니다
        assertNull(CmiDecorImport.shiftSignEdit("1:x:0"))
        assertNull(CmiDecorImport.shiftSignEdit("shiftSignEdit=false"))
    }
}
