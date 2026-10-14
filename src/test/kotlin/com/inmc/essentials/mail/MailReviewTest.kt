package com.inmc.essentials.mail

import kr.inmc.core.integration.Mail
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 검토(2026-10-10) 뒤 더한 우편 규칙 — 편지 고르기(숫자뿐인 id) · 마지막 우편전체 묶음 · 접속 알림 세기 · 경고 하루 한 번. */
class MailReviewTest {

    private val owner = UUID(0, 7)

    private fun letter(id: String, at: Long = 0L, read: Long? = null) =
        MailEntry(id, owner, Mail.Parcel(kind = Mail.Kind.LETTER, origin = MailFeature.LETTER_ORIGIN, source = "편지", text = "x", at = at), read)

    @Test
    fun `숫자뿐인 id 도 샵으로 고르고 8자 이상은 id 를 먼저 본다`() {
        val numeric = letter("40213378-aaaa-bbbb-cccc-000000000000", at = 1)
        val other = letter("abcdef12-aaaa-bbbb-cccc-000000000000", at = 2)
        val letters = listOf(other, numeric) // 새것부터
        val all = listOf(numeric, other)
        assertEquals(numeric, MailRules.pick(letters, all, "#40213378"))
        assertEquals(numeric, MailRules.pick(letters, all, "40213378"))
        assertEquals(other, MailRules.pick(letters, all, "1"))
        assertEquals(numeric, MailRules.pick(letters, all, "2"))
        assertEquals(other, MailRules.pick(letters, all, "#ABCDEF12"))
        assertNull(MailRules.pick(letters, all, "3"))
        assertNull(MailRules.pick(letters, all, "#ab")) // 너무 짧다
    }

    @Test
    fun `보상 우편은 id 앞부분으로 골라지지 않는다`() {
        val reward = MailEntry("deadbeef-aaaa-bbbb-cccc-000000000000", owner, Mail.Parcel(kind = Mail.Kind.REWARD, origin = "inmc-fishing", source = "보상", commands = listOf("x")))
        val letter = letter("cafe0000-aaaa-bbbb-cccc-000000000000", at = 1)
        val all = listOf(reward, letter)
        assertNull(MailRules.pick(listOf(letter), all, "#deadbeef"))
        assertNull(MailRules.pick(listOf(letter), all, "deadbeef"))
        assertNull(MailRules.pick(listOf(letter), all, "dead"))
        assertEquals(letter, MailRules.pick(listOf(letter), all, "#cafe0000"))
        assertEquals(letter, MailRules.pick(listOf(letter), all, "1"))
    }

    @Test
    fun `마지막 우편전체 묶음은 시각이 가장 큰 열쇠`() {
        val p = MailCommands.BATCH_PREFIX
        assertEquals("${p}300", MailRules.latestBatch(sequenceOf("${p}100", "cmi:1:2", "${p}300", "${p}200"), p))
        assertNull(MailRules.latestBatch(sequenceOf("cmi:1:2"), p))
        assertEquals(300L, MailRules.batchTime("${p}300", p))
        assertNull(MailRules.batchTime("${p}x", p))
    }

    @Test
    fun `접속 알림은 안 읽은 편지와 받을 것이 남은 우편을 센다`() {
        val reward = MailEntry("r", owner, Mail.Parcel(kind = Mail.Kind.REWARD, origin = "inmc-fishing", source = "보상", commands = listOf("x")))
        assertEquals(2 to 1, MailRules.joinCounts(listOf(letter("a"), letter("b"), letter("c", read = 5L), reward)))
        assertEquals(0 to 0, MailRules.joinCounts(emptyList()))
    }

    @Test
    fun `같은 경고는 하루에 한 번`() {
        val w = DailyWarnings(windowMillis = 1000L)
        assertTrue(w.shouldWarn("a", 0L))
        assertFalse(w.shouldWarn("a", 999L))
        assertTrue(w.shouldWarn("b", 999L))
        assertTrue(w.shouldWarn("a", 1000L))
        val small = DailyWarnings(windowMillis = 10L, maxKeys = 2)
        assertTrue(small.shouldWarn("x", 0L))
        assertTrue(small.shouldWarn("y", 0L))
        assertTrue(small.shouldWarn("z", 20L)) // 넘치면 오래된 것을 걷는다
        assertTrue(small.shouldWarn("x", 21L))
    }
}
