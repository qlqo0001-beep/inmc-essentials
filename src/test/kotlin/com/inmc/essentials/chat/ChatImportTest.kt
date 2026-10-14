package com.inmc.essentials.chat

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 검토 뒤 더한 채팅 규칙 — CMI 개인 설정 줄 읽기 · 이상한 설정값은 기본값 · CMI 와 같은 기본값(도배 꺼짐 · 무시는 공개 채팅도). */
class ChatImportTest {

    @Test
    fun `CMI 개인 설정은 PlayerOption 순서의 1과 0`() {
        // visibleHolograms … tagSound(5칸) · chatSpy=1 · cmdSpy=0 · signSpy=1 · acceptingPM=0 · acceptingTPA=1 · acceptingMoney=1 · chatbubble=1 · pmSound=0
        val parsed = ChatRules.cmiOptions("1:1:1:1:1:1:0:1:0:1:1:1:0")!!
        assertTrue(parsed.bad.isEmpty(), parsed.bad.toString())
        assertEquals(true, parsed.values["chatSpy"])
        assertEquals(false, parsed.values["cmdSpy"])
        assertEquals(true, parsed.values["signSpy"])
        assertEquals(false, parsed.values["acceptingPM"])
        assertEquals(false, parsed.values["pmSound"])
        assertNull(parsed.values["rideMe"]) // 짧은 줄 — 뒤 칸은 CMI 기본값
        assertNull(ChatRules.cmiOptions(null))
        assertNull(ChatRules.cmiOptions("  "))
    }

    @Test
    fun `CMI 개인 설정의 못 읽은 칸은 버리지 않고 알린다`() {
        assertEquals(1, ChatRules.cmiOptions("chatSpy")!!.bad.size)
        val odd = ChatRules.cmiOptions("1:x:1")!!
        assertEquals(listOf("visibleHolograms", "totemBossBar"), odd.values.keys.toList())
        assertEquals(1, odd.bad.size)
        val long = ChatRules.cmiOptions((0..ChatRules.CMI_OPTIONS.size).joinToString(":") { "1" })!!
        assertEquals(1, long.bad.size)
    }

    @Test
    fun `이상한 소리 값은 소리 없음이나 범위 안으로`() {
        assertNull(ChatSound.parse(null))
        assertNull(ChatSound.parse(""))
        assertNull(ChatSound.parse("1:2"))
        assertEquals(ChatSound("minecraft:entity.x", 10f, 0.5f), ChatSound.parse("minecraft:entity.x:99:0.1"))
        assertEquals(ChatSound("entity.x", 1f, 1f), ChatSound.parse("ENTITY.X"))
    }

    @Test
    fun `이상한 설정값은 기본값이나 범위 안으로 - 던지지 않는다`() {
        val yaml = YamlConfiguration().apply {
            loadFromString(
                """
                private:
                  reply-timeout-seconds: -5
                  max-length: 1
                  groups:
                    order: ['VIP', 'bad name!', 'staff']
                spam:
                  percentage: 500
                  interval-seconds: 0
                link:
                  regex: '(['
                ranged:
                  general: -9
                  dynamic:
                    limits: ['a-b', '5-100', '-1-3']
                title:
                  stay: 999999
                """.trimIndent(),
            )
        }
        val s = ChatSettings.from(yaml)
        val d = ChatSettings()
        assertEquals(0, s.replyTimeoutSeconds)
        assertEquals(16, s.maxLength)
        assertEquals(listOf("vip", "staff"), s.groups)
        assertEquals(100, s.spamPercentage)
        assertEquals(1, s.spamIntervalSeconds)
        assertEquals(d.linkRegex.pattern, s.linkRegex.pattern)
        assertEquals(-1, s.rangeGeneral)
        assertEquals(listOf(5 to 100), s.dynamicLimits)
        assertEquals(12000, s.titleStay)
    }

    @Test
    fun `CMI 와 같은 기본값 - 도배 막기 꺼짐 · 무시는 공개 채팅도 숨김`() {
        val d = ChatSettings()
        assertFalse(d.spamEnabled)
        assertTrue(d.ignorePublic)
        assertFalse(ChatSettings.from(YamlConfiguration().apply { loadFromString("ignore:\n  public-chat: false\n") }).ignorePublic)
    }
}
