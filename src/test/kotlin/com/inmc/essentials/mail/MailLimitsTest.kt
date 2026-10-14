package com.inmc.essentials.mail

import kr.inmc.core.integration.Mail
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 검토 뒤 더한 우편 규칙 — 첨부가 남은 편지의 만료 · 받은 열쇠 묘비 · 관리자 편지의 한도 넘기 · 쿨타임 · 사람마다 한도 · CMI 열쇠 · 편지 글 태그. */
class MailLimitsTest {

    private val s = MailSettings()
    private val owner = UUID(0, 1)

    private fun letter(at: Long = 0L, items: List<ByteArray> = emptyList(), sender: UUID? = null, key: String? = null) =
        Mail.Parcel(kind = Mail.Kind.LETTER, origin = "inmc-essentials", source = "편지", senderId = sender, senderName = "철수", text = "안녕", at = at, items = items, key = key)

    private fun reward(key: String?) = Mail.Parcel(kind = Mail.Kind.REWARD, origin = "inmc-numbergame", source = "시즌", commands = listOf("x"), key = key)

    @Test
    fun `받을 것이 남은 편지는 만료되지 않고 다 받으면 시계가 다시 돈다`() {
        val withItem = MailEntry("a", owner, letter(items = listOf(byteArrayOf(1))).copy(expiresAt = 5))
        val withMoney = MailEntry("b", owner, letter().copy(money = 3.0, expiresAt = 5))
        val plain = MailEntry("c", owner, letter().copy(expiresAt = 5))
        assertFalse(MailRules.expired(withItem, 100))
        assertFalse(MailRules.expired(withMoney, 100))
        assertTrue(MailRules.expired(plain, 100))
        val now = 1_000L * MailRules.DAY
        assertEquals(now + 30 * MailRules.DAY, MailRules.expiryAfterClaim(5, now, s))
        assertEquals(now + 90 * MailRules.DAY, MailRules.expiryAfterClaim(now + 90 * MailRules.DAY, now, s))
        assertNull(MailRules.expiryAfterClaim(null, now, s))
        assertEquals(5L, MailRules.expiryAfterClaim(5, now, s.copy(letterDays = 0)))
    }

    @Test
    fun `받아서 지운 열쇠 우편은 묘비로 겹침 - 열쇠 없는 것과 다른 출처는 그대로`() {
        val spent = setOf(MailRules.spentKey("inmc-numbergame", "season-1"))
        assertEquals(MailRules.Admit.Duplicate, MailRules.admit(emptyList(), reward("season-1"), s, spent))
        assertTrue(MailRules.admit(emptyList(), reward("season-2"), s, spent) is MailRules.Admit.Accept)
        assertTrue(MailRules.admit(emptyList(), reward(null), s, spent) is MailRules.Admit.Accept)
        assertTrue(MailRules.admit(emptyList(), reward("season-1").copy(origin = "inmc-fishing"), s, spent) is MailRules.Admit.Accept)
        assertNotEquals(MailRules.spentKey("a", "bc"), MailRules.spentKey("ab", "c"))
    }

    @Test
    fun `관리자 편지는 편지함이 꽉 차도 들어가고 플레이어 편지는 막힌다`() {
        val small = s.copy(letterLimit = 1)
        val full = listOf(MailEntry("x", owner, letter()))
        assertEquals(MailRules.Admit.LetterLimit, MailRules.admit(full, letter(at = 2), small))
        assertTrue(MailRules.admit(full, letter(at = 2), small, force = true) is MailRules.Admit.Accept)
        // 보상 한도는 force 와 상관없다(보상은 플레이어가 보내는 것이 아니다).
        assertEquals(MailRules.Admit.RewardLimit, MailRules.admit(listOf(MailEntry("r", owner, reward(null))), reward(null), s.copy(rewardLimit = 1), force = true))
    }

    @Test
    fun `쿨타임은 남은 초를 올림하고 사람마다 안 읽은 편지를 센다`() {
        assertEquals(0L, MailRules.cooldownLeft(null, 10_000, 10))
        assertEquals(0L, MailRules.cooldownLeft(0, 10_000, 0))
        assertEquals(10L, MailRules.cooldownLeft(10_000, 10_000, 10))
        assertEquals(1L, MailRules.cooldownLeft(10_000, 19_001, 10))
        assertEquals(0L, MailRules.cooldownLeft(10_000, 20_000, 10))
        val griefer = UUID(9, 9)
        val box = listOf(
            MailEntry("1", owner, letter(sender = griefer)),
            MailEntry("2", owner, letter(sender = griefer), readAt = 5),
            MailEntry("3", owner, letter(sender = griefer)),
            MailEntry("4", owner, letter(sender = UUID(1, 1))),
            MailEntry("5", owner, reward(null)),
        )
        assertEquals(2, MailRules.unreadFrom(box, griefer))
        assertEquals(0, MailRules.unreadFrom(box, UUID(7, 7)))
    }

    @Test
    fun `CMI 열쇠는 시각이 없어도 다시 가져와도 같다`() {
        assertEquals("cmi:123:${"안녕".hashCode()}", MailRules.cmiKey("철수", 123, "안녕", 0))
        val a = MailRules.cmiKey("철수", null, "안녕", 2)
        assertEquals(a, MailRules.cmiKey("철수", null, "안녕", 2))
        assertNotEquals(a, MailRules.cmiKey("철수", null, "안녕", 3))
        assertNotEquals(a, MailRules.cmiKey("영희", null, "안녕", 2))
    }

    @Test
    fun `편지 글은 색만 살리고 클릭 태그는 글자 그대로`() {
        val rich = MailText.body("<click:run_command:'/op me'><red>빨강", rich = true)
        assertEquals("<click:run_command:'/op me'>빨강", plain(rich))
        assertTrue(clicks(rich).isEmpty())
        assertEquals("초록", plain(MailText.body("&a초록", rich = true)))
        assertEquals("<red>그대로", plain(MailText.body("<red>그대로", rich = false)))
    }

    private fun plain(c: Component): String = PlainTextComponentSerializer.plainText().serialize(c)

    private fun clicks(c: Component): List<Any> = listOfNotNull(c.clickEvent()) + c.children().flatMap { clicks(it) }
}
