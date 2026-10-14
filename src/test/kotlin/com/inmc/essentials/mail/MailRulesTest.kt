package com.inmc.essentials.mail

import kr.inmc.core.integration.Mail
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 우편의 순수 규칙 — 겹침·한도(보상은 거절, 편지는 설정대로) · 만료(보상은 없음) · 받기 순서 · 저장 모양 · 책 쪽 · 설정 읽기. */
class MailRulesTest {

    private val s = MailSettings()
    private val owner = UUID(0, 1)

    private fun reward(key: String? = null, at: Long = 0L, origin: String = "inmc-numbergame") =
        Mail.Parcel(kind = Mail.Kind.REWARD, origin = origin, source = "시즌 보상", commands = listOf("give {player} diamond"), at = at, key = key)

    private fun letter(at: Long = 0L, text: String = "안녕", items: List<ByteArray> = emptyList()) =
        Mail.Parcel(kind = Mail.Kind.LETTER, origin = "inmc-essentials", source = "철수님의 편지", senderName = "철수", text = text, at = at, items = items)

    private fun entry(parcel: Mail.Parcel, id: String = UUID.randomUUID().toString(), read: Long? = null) =
        MailEntry(id, owner, parcel.copy(expiresAt = MailRules.expiryOf(parcel, s)), read)

    @Test
    fun `같은 origin 과 key 는 한 통 - key 가 없으면 매번 넣는다`() {
        val existing = listOf(entry(reward("a#1")))
        assertEquals(MailRules.Admit.Duplicate, MailRules.admit(existing, reward("a#1"), s))
        assertTrue(MailRules.admit(existing, reward("a#1", origin = "inmc-fishing"), s) is MailRules.Admit.Accept)
        assertTrue(MailRules.admit(existing, reward(null), s) is MailRules.Admit.Accept)
        assertTrue(MailRules.admit(listOf(entry(reward(null))), reward(null), s) is MailRules.Admit.Accept)
    }

    @Test
    fun `보상 한도를 넘으면 거절하고 편지는 보상 한도와 따로 센다`() {
        val small = s.copy(rewardLimit = 2, letterLimit = 2)
        val two = listOf(entry(reward()), entry(reward()))
        assertEquals(MailRules.Admit.RewardLimit, MailRules.admit(two, reward(), small))
        assertTrue(MailRules.admit(two, letter(), small) is MailRules.Admit.Accept)
        assertTrue(MailRules.admit(two, reward(), small.copy(rewardLimit = 0)) is MailRules.Admit.Accept)
    }

    @Test
    fun `편지 한도 - 거절이 기본, oldest 면 받을 것이 없는 가장 오래된 편지를 지운다`() {
        val small = s.copy(letterLimit = 2)
        val withItem = entry(letter(at = 1, items = listOf(byteArrayOf(1))), id = "item")
        val plain = entry(letter(at = 2), id = "plain")
        val newer = entry(letter(at = 3), id = "newer")
        assertEquals(MailRules.Admit.LetterLimit, MailRules.admit(listOf(plain, newer), letter(at = 4), small))
        val dropping = small.copy(letterDropOldest = true)
        assertEquals(MailRules.Admit.Accept(listOf("plain")), MailRules.admit(listOf(withItem, plain), letter(at = 4), dropping))
        // 이미 한도를 넘어 있으면(설정을 줄였다) 넘친 만큼 + 1 을 지운다.
        assertEquals(MailRules.Admit.Accept(listOf("plain", "newer")), MailRules.admit(listOf(withItem, plain, newer), letter(at = 4), dropping))
        // 지울 수 있는 편지가 모자라면 거절(아이템이 든 편지는 한도 때문에 버리지 않는다).
        assertEquals(MailRules.Admit.LetterLimit, MailRules.admit(listOf(withItem, entry(letter(at = 5, items = listOf(byteArrayOf(2))))), letter(), dropping))
    }

    @Test
    fun `만료 - 편지는 설정 일수, 보상은 언제나 없음`() {
        assertEquals(30 * MailRules.DAY + 10, MailRules.expiryOf(letter(at = 10), s))
        assertEquals(99L, MailRules.expiryOf(letter().copy(expiresAt = 99), s))
        assertNull(MailRules.expiryOf(letter(), s.copy(letterDays = 0)))
        assertNull(MailRules.expiryOf(reward().copy(expiresAt = 5), s))
        val old = MailEntry("x", owner, letter().copy(expiresAt = 5))
        assertTrue(MailRules.expired(old, 5))
        assertFalse(MailRules.expired(old, 4))
        assertFalse(MailRules.expired(MailEntry("y", owner, reward().copy(expiresAt = 5)), 100))
    }

    @Test
    fun `받기 - 가방이 모자라면 아이템만 남기고 돈과 명령은 미룬다`() {
        val parcel = reward().copy(items = listOf(byteArrayOf(1), byteArrayOf(2)), money = 10.0, moneyBy = mapOf("gem" to 3.0))
        val paid = mutableListOf<Pair<Double, String?>>()
        val tight = MailRules.claim(parcel, give = { if (it[0].toInt() == 2) listOf(it) else emptyList() }, pay = { a, c -> paid += a to c; true })
        assertFalse(tight.done)
        assertTrue(tight.partial)
        assertEquals(1, tight.rest.items.size)
        assertEquals(10.0, tight.rest.money)
        assertTrue(tight.commands.isEmpty())
        assertTrue(paid.isEmpty())

        val all = MailRules.claim(parcel, give = { emptyList() }, pay = { a, c -> paid += a to c; true })
        assertTrue(all.done)
        assertEquals(listOf(10.0 to null, 3.0 to "gem"), paid)
        assertEquals(parcel.commands, all.commands)
        assertFalse(MailRules.hasContent(all.rest))
    }

    @Test
    fun `받기 - 돈이 막히면 돈만 남기고 명령은 미루며 되살리지 못한 아이템은 센다`() {
        val parcel = reward().copy(items = listOf(byteArrayOf(9)), money = 10.0, moneyBy = mapOf("gem" to 3.0))
        val held = MailRules.claim(parcel, give = { null }, pay = { _, c -> c == null })
        assertFalse(held.done)
        assertTrue(held.moneyHeld)
        assertEquals(1, held.broken)
        assertEquals(0.0, held.rest.money)
        assertEquals(mapOf("gem" to 3.0), held.rest.moneyBy)
        assertTrue(held.rest.items.isEmpty())
        assertTrue(held.commands.isEmpty())
    }

    @Test
    fun `저장 모양은 되읽힌다 - 화폐 id 의 점도`() {
        val parcel = reward().copy(items = listOf(byteArrayOf(1, 2, 3)), money = 1.5, moneyBy = mapOf("shop.gem" to 2.0), commands = listOf("a {player}", "b"))
        val back = MailRules.decode(MailRules.encode(parcel, rich = true))
        assertEquals(1, back.items.size)
        assertTrue(back.items[0].contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(1.5, back.money)
        assertEquals(mapOf("shop.gem" to 2.0), back.moneyBy)
        assertEquals(listOf("a {player}", "b"), back.commands)
        assertTrue(back.rich)
        assertTrue(back.bad.isEmpty())
        assertFalse(MailRules.decode(MailRules.encode(letter(), rich = false)).rich)
        assertTrue(MailRules.decode("money-by: [\"oops\"]\n").bad.isNotEmpty())
        assertEquals(0, MailRules.decode(null).items.size)
    }

    @Test
    fun `책 쪽 나누기는 줄을 지키고 길면 자른다`() {
        assertEquals(listOf(""), MailRules.pages(""))
        assertEquals(listOf("가\n나"), MailRules.pages("가\n나", 10))
        assertEquals(listOf("가나다라마", "바사"), MailRules.pages("가나다라마바사", 5))
        assertEquals(listOf("가나다", "라마바"), MailRules.pages("가나다\n라마바", 5))
    }

    @Test
    fun `배포 설정은 코드의 기본값과 같다`() {
        val yaml = YamlConfiguration().apply { loadFromString(File("src/main/resources/settings/mail.yml").readText()) }
        assertEquals(MailSettings(), MailSettings.from(yaml))
    }
}
