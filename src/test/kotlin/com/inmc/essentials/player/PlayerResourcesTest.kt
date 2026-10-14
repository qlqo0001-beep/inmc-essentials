package com.inmc.essentials.player

import com.inmc.essentials.TestEss
import com.inmc.essentials.config.Messages
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** player 기능의 배포 설정·메시지가 코드와 맞는지. */
class PlayerResourcesTest {

    private val feature = TestEss.features.first { it.id == "player" }

    @Test
    fun `배포 설정은 코드의 기본값과 같다`() {
        val yaml = YamlConfiguration().apply { loadFromString(File("src/main/resources/settings/player.yml").readText()) }
        assertEquals(PlayerConfig(), PlayerConfig.from(yaml))
    }

    @Test
    fun `이상한 값은 기본값으로 돈다`() {
        val yaml = YamlConfiguration().apply {
            set("maxhp.cap", "많이")
            set("near.default-distance", 5000)
            set("near.max-distance", 300)
            set("timed.warn-at", listOf("10", "x", -1, 5))
            set("hat.whitelist", listOf("Minecraft:GLASS"))
        }
        val c = PlayerConfig.from(yaml)
        assertEquals(1000.0, c.maxHpCap)
        assertEquals(300, c.nearDefault) // 최대보다 크면 최대로
        assertEquals(listOf(10L, 5L), c.timedWarnAt)
        assertEquals(setOf("glass"), c.hatWhitelist)
    }

    @Test
    fun `코드가 쓰는 player- 메시지 키가 전부 있다`() {
        val known = feature.messageDefaults.keys + Messages.COMMON.keys
        val code = File("src/main/kotlin/com/inmc/essentials/player").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val used = Regex(""""(player-[a-z0-9-]*[a-z0-9])"""").findAll(code).map { it.groupValues[1] }.toSet()
        assertTrue(used.size > 50, "키를 못 읽었다: ${used.size}")
        assertEquals(emptySet(), used - known)
        // 이름을 지어 부르는 키(시간제 무적·비행, 방향)
        val built = listOf("god", "fly").flatMap { kind ->
            listOf("not-set", "until-relog", "left", "set", "set-relog", "added", "taken", "ended-by-command", "got", "got-relog", "got-added", "got-taken", "ended", "warn", "expired")
                .map { "player-t$kind-$it" }
        } + (0..7).map { "player-dir-$it" }
        assertEquals(emptySet(), built.toSet() - known)
    }

    @Test
    fun `좌석 열쇠는 서버 기능과 맞춘 inmcess 콜론 seat 이다`() {
        assertEquals("inmcess:seat", Seats.SEAT_KEY.toString())
    }
}
