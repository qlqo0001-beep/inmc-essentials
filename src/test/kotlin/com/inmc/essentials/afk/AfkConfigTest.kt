package com.inmc.essentials.afk

import com.inmc.essentials.TestEss
import com.inmc.essentials.config.Messages
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 잠수 설정·추방 시간·몹 생성 판정 — 서버 없이. */
class AfkConfigTest {

    @Test
    fun `배포 설정은 코드의 기본값과 같다`() {
        val yaml = YamlConfiguration().apply { loadFromString(File("src/main/resources/settings/afk.yml").readText()) }
        assertEquals(AfkConfig(), AfkConfig.from(yaml))
    }

    @Test
    fun `활동 종류를 끄고 이상한 값은 기본값`() {
        val yaml = YamlConfiguration().apply {
            set("leave-on.move", false)
            set("leave-on.look-around", true)
            set("check-interval", 0)
            set("auto-afk-seconds", "오래")
            set("auto-kick.by-player-count", listOf("10-600", "엉터리", "30-x", "5-100"))
        }
        val c = AfkConfig.from(yaml)
        assertTrue(!c.counts(AfkConfig.Activity.MOVE))
        assertTrue(c.counts(AfkConfig.Activity.LOOK_AROUND))
        assertTrue(c.counts(AfkConfig.Activity.COMMAND)) // 안 적은 것은 기본값
        assertEquals(1, c.checkInterval)
        assertEquals(300, c.autoAfkSeconds)
        assertEquals(listOf(10 to 600L, 5 to 100L), c.kickByPlayerCount)
    }

    @Test
    fun `추방 시간 - 꺼짐 단계 권한 면제`() {
        val off = AfkConfig()
        assertNull(off.kickAfter(100, 60, false))
        val c = AfkConfig(kickSeconds = 900, kickByPlayerCount = listOf(10 to 600L, 20 to 500L, 30 to 400L))
        assertEquals(900, c.kickAfter(9, null, false))
        assertEquals(600, c.kickAfter(10, null, false))
        assertEquals(500, c.kickAfter(29, null, false))
        assertEquals(400, c.kickAfter(1000, null, false))
        assertEquals(1800, c.kickAfter(1000, 1800, false))
        assertNull(c.kickAfter(1000, 1800, true))
    }

    @Test
    fun `단계 읽기`() {
        assertEquals(10 to 600L, AfkConfig.parseTier(" 10-600 "))
        assertNull(AfkConfig.parseTier("10"))
        assertNull(AfkConfig.parseTier("-5-10"))
    }

    @Test
    fun `몹 생성은 근처가 모두 잠수일 때만 막는다`() {
        assertTrue(AfkListener.shouldBlockSpawn(listOf(true)))
        assertTrue(!AfkListener.shouldBlockSpawn(listOf(true, false)))
        assertTrue(!AfkListener.shouldBlockSpawn(emptyList()))
    }

    @Test
    fun `코드가 쓰는 afk- 메시지 키가 전부 있다`() {
        val feature = TestEss.features.first { it.id == "afk" }
        val known = feature.messageDefaults.keys + Messages.COMMON.keys
        val code = File("src/main/kotlin/com/inmc/essentials/afk").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val used = Regex(""""(afk-[a-z0-9-]*[a-z0-9])"""").findAll(code).map { it.groupValues[1] }.toSet()
        assertTrue(used.size > 10, "키를 못 읽었다")
        assertEquals(emptySet(), used - known)
    }
}
