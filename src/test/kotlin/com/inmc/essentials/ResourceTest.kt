package com.inmc.essentials

import com.inmc.essentials.config.EssConfig
import com.inmc.essentials.config.Messages
import com.inmc.essentials.feature.Features
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.InputStreamReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 배포 파일과 코드가 어긋나지 않는지. 어긋나면 전부 오류 없이 조용히 깨진다(빈 메시지 · op 만 쓰는 권한 · 안 읽히는 설정). */
class ResourceTest {

    private fun yaml(path: String): YamlConfiguration =
        assertNotNull(javaClass.classLoader.getResourceAsStream(path), "배포 파일이 없습니다: $path")
            .use { YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8)) }

    private val code: String by lazy {
        File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
    }

    @Test
    fun `공통 메시지 파일과 기본값 표의 키가 정확히 같다`() {
        assertEquals(Messages.COMMON.keys, yaml("messages/common.yml").getKeys(false))
    }

    @Test
    fun `기능마다 메시지 파일과 기본값의 키가 정확히 같고 접두가 기능 이름이다`() {
        for (feature in TestEss.features) {
            val keys = feature.messageDefaults.keys
            assertEquals(keys, yaml("messages/${feature.id}.yml").getKeys(false), "messages/${feature.id}.yml 과 ${feature.javaClass.simpleName}.messageDefaults 가 다릅니다")
            val stray = keys.filterNot { it.startsWith("${feature.id}-") }
            assertTrue(stray.isEmpty(), "${feature.id} 의 키는 '${feature.id}-' 로 시작해야 합니다: $stray")
        }
    }

    @Test
    fun `메시지 키는 기능끼리·공통과 겹치지 않는다`() {
        val all = Messages.COMMON.keys.toList() + TestEss.features.flatMap { it.messageDefaults.keys }
        val dup = all.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue(dup.isEmpty(), "겹친 키: $dup")
    }

    @Test
    fun `코드가 부르는 메시지 키가 전부 있다`() {
        // 없는 키는 오류 없이 빈 줄이 된다.
        val used = Regex("""(?:send|tell)\([^,()]+, (?:if \([^)]*\) )?"([a-z0-9-]+)"(?: else "([a-z0-9-]+)")?""").findAll(code)
            .flatMap { listOf(it.groupValues[1], it.groupValues[2]) }.filter { it.isNotEmpty() }.toSet()
        assertTrue(used.isNotEmpty(), "키를 못 읽었다")
        val known = Messages.defaults(TestEss.features).keys
        assertEquals(emptySet(), used - known)
    }

    @Test
    fun `코드가 쓰는 권한은 전부 선언돼 있다`() {
        // 선언하지 않은 권한은 op 기본(지뢰 11). 동적으로 짓는 노드("…$n")는 여기서 못 본다 — 그것도 Feature.permissions 에 적을 것.
        val yml = File("src/main/resources/paper-plugin.yml").readText()
        val declared = TestEss.features.flatMap { f -> f.permissions.map { it.node } }.toSet() +
            Regex("""(?m)^  (inmcessentials\.[a-z0-9_.-]+):""").findAll(yml).map { it.groupValues[1] }
        val used = Regex(""""(inmcessentials\.[a-z0-9_.-]+)"""").findAll(code).map { it.groupValues[1] }.toSet()
        assertTrue(Ess.ADMIN in used, "권한을 못 읽었다: $used")
        assertEquals(emptySet(), used - declared, "선언 안 된 권한")
    }

    @Test
    fun `설정 파일은 파싱되고 hasSettings 와 짝이 맞는다`() {
        val dir = File("src/main/resources/settings")
        val files = dir.listFiles { f -> f.extension == "yml" }?.map { it.nameWithoutExtension }?.toSet().orEmpty()
        for (name in files) {
            val text = File(dir, "$name.yml").readText()
            YamlConfiguration().loadFromString(text) // 던지면 깨진 YAML
        }
        val wants = TestEss.features.filter { it.hasSettings }.map { it.id }.toSet()
        assertEquals(wants, files, "settings/<id>.yml 이 있는 것 = hasSettings 가 true 인 기능")
    }

    @Test
    fun `배포 설정은 코드의 기본값과 같고 기능 19개를 모두 적는다`() {
        val config = yaml("config.yml")
        assertEquals(Features.IDS.toSet(), config.getConfigurationSection("modules")!!.getKeys(false))
        assertEquals(EssConfig(), EssConfig.from(config))
    }

    @Test
    fun `필수 의존은 inmc-core 하나이고 나머지는 전부 OMIT`() {
        val yml = YamlConfiguration().apply { loadFromString(File("src/main/resources/paper-plugin.yml").readText()) }
        val server = yml.getConfigurationSection("dependencies.server")!!
        for (name in server.getKeys(false)) {
            val dep = server.getConfigurationSection(name)!!
            if (name == "inmc-core") {
                assertEquals("BEFORE", dep.getString("load"))
                assertTrue(dep.getBoolean("required"))
            } else {
                assertEquals("OMIT", dep.getString("load"), "$name 은 load: OMIT 이어야 합니다(가이드 함정 1)")
                assertTrue(!dep.getBoolean("required"), "$name 은 선택이어야 합니다")
            }
            assertTrue(dep.getBoolean("join-classpath"), "$name 은 join-classpath: true")
        }
        assertTrue(!yml.contains("commands"), "commands 절은 쓰지 않는다 — Brigadier")
    }
}
