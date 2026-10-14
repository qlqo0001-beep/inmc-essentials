package com.inmc.essentials.moderation

import org.bukkit.configuration.file.YamlConfiguration
import java.io.InputStreamReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** 배포 설정(`settings/moderation.yml`)이 코드의 기본값과 같다 — 둘이 어긋나면 지운 키가 엉뚱한 값으로 돈다. */
class ModSettingsTest {

    private fun shipped(): YamlConfiguration =
        assertNotNull(javaClass.classLoader.getResourceAsStream("settings/moderation.yml"))
            .use { YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8)) }

    @Test
    fun `배포 파일 = 코드 기본값`() {
        assertEquals(ModSettings(), ModSettings.from(shipped()))
    }

    @Test
    fun `빈 파일도 기본값`() {
        assertEquals(ModSettings(), ModSettings.from(YamlConfiguration()))
    }

    @Test
    fun `분류는 이름과 별칭으로 찾고 이상한 값은 기본값`() {
        val s = ModSettings()
        assertEquals("욕설", s.category("SWEAR")?.name)
        assertEquals("기본", s.category("default")?.name)
        val yaml = YamlConfiguration()
        yaml.set("jail.chat-range", -7)
        yaml.set("jail.default-time", "열")
        yaml.set("warnings.perform.abc", listOf("x"))
        yaml.set("warnings.perform.4", listOf("/추방 {player}"))
        val read = ModSettings.from(yaml)
        assertEquals(-1, read.jailChatRange)
        assertEquals(300L, read.jailDefaultSeconds)
        assertEquals(mapOf(4 to listOf("추방 {player}")), read.warnPerform)
    }
}
