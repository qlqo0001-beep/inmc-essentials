package com.inmc.essentials.moderation

import com.inmc.essentials.store.LogoutSpot
import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 감옥 정의 파일 왕복 · 영역 · CMI Jails.yml 읽기. */
class JailCodecTest {

    private val jail = Jail(
        "감옥1", JailArea.of("world", 10, 60, 10, 0, 70, 0), LogoutSpot("world", 20.5, 64.0, 20.5, 90f, 0f),
        listOf(JailCell(1, LogoutSpot("world", 2.5, 61.0, 2.5, 0f, 0f)), JailCell(3, LogoutSpot("world", 8.5, 61.0, 8.5, 180f, 10f))),
    )

    private fun reread(yaml: YamlConfiguration): YamlConfiguration = YamlConfiguration().apply { loadFromString(yaml.saveToString()) }

    @Test
    fun `저장한 것이 그대로 읽힌다`() {
        val back = JailCodec.read(reread(JailCodec.write(listOf(jail))))
        assertEquals(listOf(jail), back.jails)
        assertTrue(back.problems.isEmpty())
    }

    @Test
    fun `영역은 양 끝을 포함하고 순서를 바로잡는다`() {
        val a = jail.area
        assertEquals(0, a.minX)
        assertEquals(10, a.maxX)
        assertTrue(a.contains("world", 10.9, 70.5, 0.0))
        assertFalse(a.contains("world", 11.0, 65.0, 5.0))
        assertFalse(a.contains("nether", 5.0, 65.0, 5.0))
        assertTrue(a.overlaps(JailArea.of("world", 10, 70, 10, 30, 80, 30)))
        assertFalse(a.overlaps(JailArea.of("world", 11, 60, 0, 30, 80, 30)))
        assertEquals(5.5, a.center().x)
    }

    @Test
    fun `못 읽은 감옥은 원문 그대로 다음 저장에 남는다`() {
        val yaml = reread(JailCodec.write(listOf(jail)))
        yaml.set("jails.깨진감옥.world", "world")
        yaml.set("jails.깨진감옥.min", "a,b,c")
        val read = JailCodec.read(yaml)
        assertEquals(listOf("감옥1"), read.jails.map { it.name })
        assertEquals(1, read.problems.size)
        val again = JailCodec.read(reread(JailCodec.write(read.jails, read.raw)))
        assertEquals("a,b,c", reread(JailCodec.write(read.jails, read.raw)).getString("jails.깨진감옥.min"))
        assertEquals(listOf("감옥1"), again.jails.map { it.name })
    }

    @Test
    fun `자리 글은 세미콜론과 콜론 둘 다`() {
        assertEquals(LogoutSpot("w", 1.0, 2.0, 3.0, 4f, 5f), JailCodec.spot("w;1;2;3;4;5"))
        assertEquals(LogoutSpot("w", 1.5, 2.0, 3.0, 0f, 0f), JailCodec.spot("w:1.5:2:3"))
        assertNull(JailCodec.spot("w;x;2;3"))
    }

    @Test
    fun `CMI Jails yml - Loc 영역 · safeLoc · 감방`() {
        val yaml = YamlConfiguration()
        yaml.set("jails.main.Loc", "world:0:60:0:10:70:10")
        yaml.set("jails.main.safeLoc", "world;20.5;64;20.5;90;0")
        yaml.set("jails.main.cells.1.location", "world;2.5;61;2.5;0;0")
        yaml.set("jails.bad.Loc", "world:1:2")
        val problems = ArrayList<String>()
        val jails = JailCodec.fromCmi(yaml) { problems += it }
        assertEquals(1, jails.size)
        val j = jails[0]
        assertEquals("main", j.name)
        assertEquals(JailArea("world", 0, 60, 0, 10, 70, 10), j.area)
        assertEquals(20.5, j.outside?.x)
        assertEquals(listOf(1), j.cells.map { it.id })
        assertEquals(1, problems.size, "못 읽은 감옥은 알린다")
    }

    @Test
    fun `영역 밖 감방은 빼고 알린다 - 우리 파일과 CMI 둘 다`() {
        val yaml = reread(JailCodec.write(listOf(jail)))
        yaml.set("jails.감옥1.cells.9", "world;50;64;50;0;0")
        val read = JailCodec.read(yaml)
        assertEquals(listOf(1, 3), read.jails.single().cells.map { it.id })
        assertEquals(1, read.problems.size)
        assertTrue(read.problems[0].contains("영역 밖"))
        assertEquals(mapOf("감옥1" to mapOf("9" to "world;50;64;50;0;0")), read.held, "쓰지 않는 감방의 원문을 들고 있다")

        val cmi = YamlConfiguration()
        cmi.set("jails.main.Loc", "world:0:60:0:10:70:10")
        cmi.set("jails.main.cells.1.location", "world;2.5;61;2.5;0;0")
        cmi.set("jails.main.cells.2.location", "world;200;61;2.5;0;0")
        val problems = ArrayList<String>()
        val jails = JailCodec.fromCmi(cmi) { problems += it }
        assertEquals(listOf(1), jails.single().cells.map { it.id })
        assertEquals(1, problems.size)
    }

    @Test
    fun `쓰지 않는 감방(영역 밖·못 읽음)은 다음 저장에 그대로 남고 같은 번호의 새 감방이 이긴다`() {
        val yaml = reread(JailCodec.write(listOf(jail)))
        yaml.set("jails.감옥1.cells.9", "world;50;64;50;0;0")
        yaml.set("jails.감옥1.cells.x", "깨진 글")
        yaml.set("jails.감옥1.cells.7", "nowhere")
        val read = JailCodec.read(yaml)
        assertEquals(listOf(1, 3), read.jails.single().cells.map { it.id })
        assertEquals(setOf("9", "x", "7"), read.held["감옥1"]!!.keys)

        // 그대로 저장 → 파일에 남는다(손으로 고친 실수가 영구 손실이 되지 않게).
        val saved = reread(JailCodec.write(read.jails, read.raw, read.held))
        assertEquals("world;50;64;50;0;0", saved.getString("jails.감옥1.cells.9"))
        assertEquals("깨진 글", saved.getString("jails.감옥1.cells.x"))
        assertEquals("nowhere", saved.getString("jails.감옥1.cells.7"))
        assertEquals(listOf(1, 3), JailCodec.read(saved).jails.single().cells.map { it.id })

        // 영역을 넓혀 되읽으면 9번은 돌아온다.
        saved.set("jails.감옥1.max", "60,70,60")
        val widened = JailCodec.read(saved)
        assertEquals(listOf(1, 3, 9), widened.jails.single().cells.map { it.id })
        assertEquals(setOf("x", "7"), widened.held["감옥1"]!!.keys)

        // 메모리의 같은 번호 감방(관리자가 새로 만든 9번)이 이긴다.
        val replaced = read.jails.single().let { it.copy(cells = it.cells + JailCell(9, LogoutSpot("world", 5.5, 61.0, 5.5, 0f, 0f))) }
        assertEquals("world;5.500;61;5.500;0;0", reread(JailCodec.write(listOf(replaced), read.raw, read.held)).getString("jails.감옥1.cells.9"))

        // 감옥을 지웠으면 그 감옥의 보류 감방도 같이 사라진다.
        assertFalse(reread(JailCodec.write(emptyList(), read.raw, read.held)).contains("jails.감옥1"))
    }

    @Test
    fun `감방이 없을 때의 자리는 영역 가운데 높이`() {
        val a = JailArea.of("world", 0, 60, 0, 10, 70, 10)
        assertEquals(65.0, a.center().y, "바닥(minY) 블록 안이 아니라 가운데")
        assertTrue(a.contains(a.center()))
        val r = JailArea.around("world", 100, 64, 100, 5)
        assertEquals(64.0, r.center().y, "선 자리 둘레 감옥은 선 자리 높이")
    }
}
