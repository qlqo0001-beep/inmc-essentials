package com.inmc.essentials.hologram

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** holograms.yml 왕복 · 못 읽은 칸 되써 넣기 · CMI Saves/Holograms.yml 판독(합성 YAML — 테섭 파일은 비어 있다). */
class HologramCodecTest {

    private fun reload(yaml: YamlConfiguration) = YamlConfiguration().apply { loadFromString(yaml.saveToString()) }

    @Test
    fun `저장하고 다시 읽으면 같다 - 쪽·아이콘·줄과 쪽의 동작·설정`() {
        val h = Hologram(
            name = "안내", world = "world", x = 1.5, y = 70.25, z = -3.0,
            pages = listOf(
                HoloPage(
                    listOf(
                        HoloLine("<gold>환영합니다 {player}"),
                        HoloLine("눌러 보세요", actions = HoloActions(mapOf(HoloClick.RIGHT to listOf("player:spawn"), HoloClick.SHIFT_LEFT to listOf("say a", "say b")))),
                        HoloLine(icon = "minecraft:diamond"),
                        HoloLine(icon = "snapshot", material = "PAPER", snapshot = "AAEC"),
                    ),
                    HoloActions(mapOf(HoloClick.ANY to listOf("[nextPage]"))),
                ),
                HoloPage(listOf(HoloLine("2쪽"))),
            ),
            enabled = false, viewRange = 24, updateSeconds = 5, updateRange = 6, pageSeconds = 0, permission = "vip.see", condition = "%player_level% >= 10",
            billboard = "FIXED", yaw = 90f, scale = 1.5, background = "#80000000", shadow = false, seeThrough = true, clickPages = false, clickWidth = 2.0,
        )
        val plain = Hologram("b", "w", 0.0, 64.0, 0.0)
        val back = HologramCodec.read(reload(HologramCodec.write(HologramFile(listOf(h, plain)))))
        assertEquals(setOf(h, plain), back.holograms.toSet())
        assertTrue(back.rejected.isEmpty())
    }

    @Test
    fun `못 읽은 칸은 지우지 않고 그대로 되써 넣는다`() {
        val yaml = YamlConfiguration().apply {
            set("holograms.좋음.location", "w;0;64;0")
            set("holograms.나쁨.location", "w;x;y")
            set("holograms.나쁨.pages", listOf(mapOf("lines" to listOf("남겨 둘 것"))))
        }
        val read = HologramCodec.read(yaml)
        assertEquals(listOf("좋음"), read.holograms.map { it.name })
        assertEquals(setOf("나쁨"), read.rejected.keys)
        val again = reload(HologramCodec.write(read))
        assertEquals("w;x;y", again.getString("holograms.나쁨.location"))
        assertEquals(1, again.getMapList("holograms.나쁨.pages").size)
    }

    @Test
    fun `못 읽은 칸은 까닭과 함께 - 모르는 클릭 종류 · 목록이 아닌 actions · 위치`() {
        val yaml = YamlConfiguration().apply {
            loadFromString(
                """
                holograms:
                  good:
                    location: 'world;0;64;0'
                    alignment: LEFT
                    line-width: 200
                    text-opacity: 100
                    pages:
                    - lines: ['a']
                  typo:
                    location: 'world;0;64;0'
                    pages:
                    - lines:
                      - text: 'x'
                        actions:
                          rigth: ['say hi']
                  listy:
                    location: 'world;0;64;0'
                    pages:
                    - lines: ['a']
                      actions:
                      - 42.5
                  noloc:
                    loc: 'world;0;64;0'
                """.trimIndent(),
            )
        }
        val read = HologramCodec.read(yaml)
        assertEquals(listOf("good"), read.holograms.map { it.name })
        val good = read.holograms.single()
        assertEquals(Triple("LEFT", 200, 100), Triple(good.alignment, good.lineWidth, good.textOpacity))
        assertEquals(setOf("typo", "listy", "noloc"), read.rejected.keys)
        assertTrue(read.reasons["typo"]!!.contains("rigth"), read.reasons.toString())
        assertTrue(read.reasons["listy"]!!.contains("actions"), read.reasons.toString())
        assertTrue(read.reasons["noloc"]!!.contains("location"), read.reasons.toString())
        // 되써 넣을 때 원문 그대로 — 고친 정의도 다시 읽힌다
        val again = HologramCodec.read(reload(HologramCodec.write(read)))
        assertEquals(read.rejected.keys, again.rejected.keys)
        assertEquals(good, again.holograms.single())
        assertEquals(listOf("say hi"), (again.rejected["typo"] as Map<*, *>).let { t ->
            (((t["pages"] as List<*>)[0] as Map<*, *>)["lines"] as List<*>).let { l -> ((l[0] as Map<*, *>)["actions"] as Map<*, *>)["rigth"] }
        })
    }
}
