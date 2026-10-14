package com.inmc.essentials

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The optional plugins must be reachable by reflection only.
 *
 * This is the invariant the whole integration design rests on: none of MMOItems, MythicLib,
 * BetterModel, ModelEngine, MythicMobs, MagicSpells or ItemsAdder is a compile dependency, so a
 * server without them still loads every class we ship. One accidental import - an IDE
 * auto-completing a type in a hook - would turn that into a `NoClassDefFoundError` on the first
 * spawn, on exactly the servers least equipped to diagnose it.
 *
 * The check scans compiled bytecode rather than source, because an import is not the only way a
 * reference gets in. Class references appear in the constant pool in `a/b/C` form; the string
 * literals the hooks pass to `Class.forName` use dots, so they do not match and the test does
 * not have to special-case them.
 */
class ReflectionPurityTest {

    /** Packages that must never appear as a compiled class reference. */
    private val reflectionOnly = listOf(
        // 이 플러그인이 기대는 선택 플러그인(paper-plugin.yml) — 전부 리플렉션으로만.
        "com/sk89q/worldedit",
        "com/fastasyncworldedit",
        "com/sk89q/worldguard",
        "net/luckperms",
        "com/vexsoftware/votifier",
        "com/bencodez/votifierplus",
        "net/coreprotect",
        "kr/inmc/titleforge",
        "org/mvplugins/multiverse",
        "com/onarandombox",
        // 가이드 7절의 공통 목록.
        "net/Indyuce/mmoitems",
        "io/lumine/mythic/lib",
        "io/lumine/mythic/bukkit",
        "kr/toxicity/model",
        "com/ticxo/modelengine",
        "com/nisovin/magicspells",
        "dev/lone/itemsadder",
        "com/nexomc/nexo",
        "io/th0rgal/oraxen",
        "com/willfp/ecoitems",
        "me/angeschossen/lands",
    )

    /**
     * Packages we deliberately compile against.
     *
     * Kept here as the test's own control: if these stopped being found, the scan would be
     * looking in the wrong place and every assertion above would pass for the wrong reason.
     */
    private val compiledAgainst = listOf(
        "me/clip/placeholderapi",
        "kr/inmc/core",
    )

    private fun classFiles(): List<File> {
        val root = File("build/classes/kotlin/main")
        assertTrue(root.isDirectory, "compiled classes not found at ${root.absolutePath}")
        return root.walkTopDown().filter { it.isFile && it.extension == "class" }.toList()
    }

    private fun referencing(pkg: String): List<String> {
        val needle = pkg.toByteArray(Charsets.US_ASCII)
        return classFiles()
            .filter { it.readBytes().containsSequence(needle) }
            .map { it.path.substringAfter("main${File.separator}") }
    }

    @Test
    fun `no compiled class references an optional plugin directly`() {
        val offenders = reflectionOnly.associateWith { referencing(it) }.filterValues { it.isNotEmpty() }
        assertTrue(
            offenders.isEmpty(),
            buildString {
                appendLine("optional plugin types leaked into compiled code:")
                offenders.forEach { (pkg, files) ->
                    appendLine("  " + pkg.replace('/', '.') + " <- " + files.joinToString(", "))
                }
                append("these must be reached through PluginClasses/reflection instead")
            },
        )
    }

    @Test
    fun `the scan actually finds references when they exist`() {
        // Without this, a scan pointed at the wrong directory would report a clean bill of
        // health for a jar riddled with hard references.
        val found = compiledAgainst.filter { referencing(it).isNotEmpty() }
        assertTrue(
            found.isNotEmpty(),
            "the reference scan found nothing at all, so it is not testing what it claims to",
        )
    }

    @Test
    fun `the plugin main class is where the descriptor says it is`() {
        val descriptor = File("src/main/resources/paper-plugin.yml").readText(Charsets.UTF_8)
        val main = descriptor.lineSequence()
            .first { it.startsWith("main:") }
            .substringAfter("main:")
            .trim()
        assertTrue(main.isNotEmpty(), "paper-plugin.yml declares no main class")

        val expected = File("build/classes/kotlin/main/" + main.replace('.', '/') + ".class")
        assertTrue(
            expected.isFile,
            "paper-plugin.yml points at $main but no such class was compiled",
        )
    }
}

/** Byte-level substring search, so class files can be scanned without a bytecode library. */
private fun ByteArray.containsSequence(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return false
    val first = needle[0]
    outer@ for (start in 0..(size - needle.size)) {
        if (this[start] != first) continue
        for (offset in 1 until needle.size) {
            if (this[start + offset] != needle[offset]) continue@outer
        }
        return true
    }
    return false
}
