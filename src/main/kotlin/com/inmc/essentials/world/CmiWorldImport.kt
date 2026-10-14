package com.inmc.essentials.world

import com.inmc.essentials.service.ImportReport
import org.bukkit.configuration.file.YamlConfiguration

/**
 * CMI `config.yml` → `settings/world.yml`(순수 — YAML 둘만 다룬다, `CmiImportTest`). 키 이름은 CMI 9.8.9.8 의 config.yml 그대로
 * (테섭 `plugins/CMI/config.yml` 1344행 WorldLimits · 500행 Sleeping · 1006행 NetherPortal · 410행 PreventEntityBoatEnter · 1724행 Time).
 *
 * 목록 줄 `월드:값` 중 서버에 없는 월드(CMI 기본값의 `testWorld`·`worldName`)는 건너뛰고 보고서에 남긴다. 알아보지 못한 줄은 `unreadable`.
 *
 * [modules] 는 CMI `Settings/Modules.yml` — CMI 에서 꺼 둔 모듈(worldLimits · worldLimitsElytra · nightSpeedup · portalCreation)은 config 값과
 * 상관없이 우리 쪽도 끈다(옮긴 뒤 갑자기 켜지지 않게). 끈 것은 보고서에 적는다.
 */
object CmiWorldImport {

    private const val KIND = WorldFeature.KIND

    fun apply(cmi: YamlConfiguration, out: YamlConfiguration, worlds: Set<String>, report: ImportReport, modules: YamlConfiguration? = null) {
        cmi.getConfigurationSection("WorldLimits")?.let { wl ->
            report.read(KIND)
            out.set("limits.enabled", wl.getBoolean("Enabled", false))
            val gamemode = LinkedHashMap<String, String>()
            for (line in wl.getStringList("Gamemode")) {
                val parsed = pair(line)
                if (parsed == null) {
                    report.unreadable(KIND, "WorldLimits.Gamemode: $line")
                    continue
                }
                val (world, value) = parsed
                val mode = value.lowercase()
                if (mode !in WorldSettings.GAMEMODES) {
                    report.unreadable(KIND, "WorldLimits.Gamemode: $line")
                    continue
                }
                if (world.lowercase() !in worlds) report.skipped(KIND, "WorldLimits.Gamemode '$line' — 서버에 없는 월드")
                else gamemode[world] = mode
            }
            out.set("limits.gamemode", null)
            for ((w, m) in gamemode) out.set("limits.gamemode.$w", m)
            out.set("limits.no-fly", falseWorlds(wl.getStringList("Fly"), "WorldLimits.Fly", worlds, report))
            out.set("limits.no-god", falseWorlds(wl.getStringList("GodMode"), "WorldLimits.GodMode", worlds, report))
            out.set("limits.no-elytra", falseWorlds(wl.getStringList("ElytraFlight"), "WorldLimits.ElytraFlight", worlds, report))
            if (wl.contains("ReenabledFlyAfterDeath")) {
                // 죽은 뒤 비행 되돌리기는 player 기능이 늘 한다(죽기 전에 날았으면 · /비행 상태면). 비행 금지 월드에서는 부활 뒤 이 제한이 다시 끈다.
                val on = wl.getBoolean("ReenabledFlyAfterDeath")
                report.note("CMI WorldLimits.ReenabledFlyAfterDeath=$on — 옮길 설정이 없습니다: 죽은 뒤 비행 되돌리기는 player 기능이 늘 합니다" +
                    if (on) "(같은 동작)" else "(CMI 는 꺼져 있었습니다 — 죽은 뒤에도 /비행 상태면 다시 납니다)")
            }
            out.set("limits.fly-above-roof", wl.getBoolean("FlyAboveRoof", true))
            val roof = LinkedHashMap<String, Int>()
            for (line in wl.getStringList("FlyAboveRoofLimitations")) {
                val world = line.substringBeforeLast('-')
                val height = line.substringAfterLast('-').toIntOrNull()
                if (world.isEmpty() || height == null) {
                    report.unreadable(KIND, "WorldLimits.FlyAboveRoofLimitations: $line")
                    continue
                }
                roof[world] = height
            }
            if (roof.isNotEmpty()) {
                out.set("limits.roof", null)
                for ((w, h) in roof) out.set("limits.roof.$w", h)
            }
            out.set("limits.spawn-reasons", null)
            wl.getConfigurationSection("SpawnReasons")?.let { sr ->
                for (world in sr.getKeys(false)) {
                    val reasons = sr.getStringList(world).map { it.trim().uppercase() }.filter { it.isNotEmpty() && it != "NONE" }
                    if (reasons.isEmpty()) continue
                    if (world.lowercase() !in worlds) report.skipped(KIND, "WorldLimits.SpawnReasons.$world — 서버에 없는 월드")
                    else out.set("limits.spawn-reasons.$world", reasons)
                }
            }
            report.imported(KIND)
        }

        cmi.getConfigurationSection("Sleeping.Speedup")?.let { sp ->
            report.read(KIND)
            out.set("sleep.enabled", sp.getBoolean("Enabled", true))
            out.set("sleep.worlds", sp.getStringList("Worlds").filter { it.isNotBlank() })
            out.set("sleep.only-night", sp.getBoolean("OnlyDurringNight", true))
            out.set("sleep.night-starts-at", sp.getLong("NightStartsAt", 12542))
            val info = sp.getString("InfoType", "title")!!.lowercase()
            out.set("sleep.info", if (info in setOf("none", "title", "bossbar", "actionbar")) info else "title".also { report.unreadable(KIND, "Sleeping.Speedup.InfoType: $info") })
            out.set("sleep.exclude-afk", sp.getBoolean("ExcludeAfk", true))
            out.set("sleep.base-speed", sp.getInt("BaseSpeed", 100))
            out.set("sleep.min-speed", sp.getInt("MinSpeed", 5))
            out.set("sleep.min-sleeping", sp.getString("MinBeforeSpeeding", "50%"))
            out.set("sleep.inform", sp.getBoolean("Inform", true))
            out.set("sleep.inform-delay", sp.getInt("InformDelay", 30))
            report.imported(KIND)
        }

        cmi.getConfigurationSection("NetherPortal")?.let { np ->
            report.read(KIND)
            out.set("nether-portal.prevent-creation", np.getBoolean("PreventCreation", false))
            out.set("nether-portal.max-height", np.getInt("MaxHeight", 23))
            out.set("nether-portal.max-width", np.getInt("MaxWidth", 23))
            report.imported(KIND)
        }

        cmi.getConfigurationSection("Optimizations.PreventEntityBoatEnter")?.let { b ->
            report.read(KIND)
            out.set("entity-vehicle.boats.monsters", b.getBoolean("Monsters", false))
            out.set("entity-vehicle.boats.animals", b.getBoolean("Animals", false))
            out.set("entity-vehicle.boats.villagers", b.getBoolean("Villagers", false))
            report.imported(KIND)
        }

        cmi.getConfigurationSection("Time")?.let { t ->
            report.read(KIND)
            for ((cmiKey, ours) in listOf("Day" to "day", "Night" to "night", "Morning" to "morning", "Dusk" to "dusk")) {
                val text = t.getString(cmiKey) ?: continue
                if (TimeText.parse(text) == null) report.unreadable(KIND, "Time.$cmiKey: $text") else out.set("time.presets.$ours", text)
            }
            t.getConfigurationSection("AutoTime")?.let { a ->
                out.set("time.auto-real.interval", a.getInt("Interval", 60))
                out.set("time.auto-real.smooth", a.getBoolean("Smooth", true))
                out.set("time.smooth-speed", a.getInt("SmoothSpeed", 100))
                val list = a.getStringList("Worlds").filter { it.isNotBlank() }
                val (known, unknown) = list.partition { it.lowercase() in worlds }
                unknown.forEach { report.skipped(KIND, "Time.AutoTime.Worlds '$it' — 서버에 없는 월드") }
                out.set("time.auto-real.worlds", known)
            }
            if (t.isConfigurationSection("TimeSpeed")) report.note("CMI Time.TimeSpeed(낮·밤 길이)는 world 기능에 없습니다 — 옮기지 않았습니다")
            report.imported(KIND)
        }

        modules?.let { m -> applyModules(m, out, report) }
    }

    /** CMI 에서 꺼 둔 모듈 → 우리 쪽도 끈다. */
    private fun applyModules(m: YamlConfiguration, out: YamlConfiguration, report: ImportReport) {
        fun off(key: String) = m.contains(key) && !m.getBoolean(key, true)
        if (off("worldLimits")) {
            out.set("limits.enabled", false)
            report.note("CMI Modules.yml worldLimits=false — 월드 제한(limits.enabled)을 껐습니다")
        }
        if (off("worldLimitsElytra")) {
            out.set("limits.no-elytra", emptyList<String>())
            report.note("CMI Modules.yml worldLimitsElytra=false — 겉날개 금지 월드(limits.no-elytra)를 비웠습니다")
        }
        if (off("nightSpeedup")) {
            out.set("sleep.enabled", false)
            report.note("CMI Modules.yml nightSpeedup=false — 잠자기 밤 가속(sleep.enabled)을 껐습니다")
        }
        if (off("portalCreation")) {
            out.set("nether-portal.prevent-creation", false)
            out.set("nether-portal.max-height", 23)
            out.set("nether-portal.max-width", 23)
            out.set("nether-portal.worlds", null)
            report.note("CMI Modules.yml portalCreation=false — 네더 포탈 제한을 바닐라 그대로(막지 않음 · 23×23)로 두었습니다")
        }
    }

    /** `월드:값` → (월드, 값). */
    private fun pair(line: String): Pair<String, String>? {
        val world = line.substringBeforeLast(':', "").trim()
        val value = line.substringAfterLast(':', "").trim()
        return if (world.isEmpty() || value.isEmpty()) null else world to value
    }

    /** `월드:False` 줄의 월드들(서버에 있는 것만). */
    private fun falseWorlds(lines: List<String>, path: String, worlds: Set<String>, report: ImportReport): List<String> {
        val out = ArrayList<String>()
        for (line in lines) {
            val parsed = pair(line)
            if (parsed == null) {
                report.unreadable(KIND, "$path: $line")
                continue
            }
            val (world, value) = parsed
            when (value.lowercase()) {
                "false" -> if (world.lowercase() in worlds) out += world else report.skipped(KIND, "$path '$line' — 서버에 없는 월드")
                "true" -> Unit
                else -> report.unreadable(KIND, "$path: $line")
            }
        }
        return out
    }
}
