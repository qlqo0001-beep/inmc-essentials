package com.inmc.essentials.decor

import com.inmc.essentials.service.ImportReport
import org.bukkit.configuration.file.YamlConfiguration

/**
 * CMI → decor(순수 — YAML 만, `CmiImportTest`).
 *
 * - 설정: config `Signs.editBlackList` + `Optimizations.SignEdit.BlackList`(합침) · `Signs.RequireSneaking` · `Signs.DialogEditor` ·
 *   `Optimizations.ArmorStands.MinScale`·`MaxScale`·`Templates.SpecificPermission`(테섭 config.yml 164·194·747행).
 *   `CheckBlockPlace` 는 옮기지 않는다 — 우리는 명령으로 거치대를 열 때 늘 보호 구역을 본다(`.armorstand.any` 만 건너뜀). 보고서에 적는다.
 * - CMI `Settings/Modules.yml` 에서 꺼 둔 모듈(paintingEditor · armorstand · signEdit)은 우리 쪽도 끈다.
 * - 동적 표지판: `Saves/DynamicSigns.yml` — CMI 9.8.9.8 jar(`SignManager`)의 열쇠 `<id>.Loc`·`.Lines`·`.Interval`·`.Personal`·`.Range`.
 *   테섭에는 이 파일이 없어 실제 값으로 확인하지 못했다 — 위치는 `월드;x;y;z`·`월드:x:y:z`(·yaw·pitch) 둘 다 읽고, 못 읽으면 원문과 함께 `unreadable`.
 */
object CmiDecorImport {

    const val SETTINGS = "표지판·거치대 설정"
    const val DSIGN = "동적 표지판"
    const val SHIFT_EDIT = "웅크려 표지판 편집(개인)"

    /**
     * CMI `users.Options`(CMI 9.8.9.8 `PlayerOption` 순서대로 `1`/`0` 을 `:` 로 이은 것 — chat 기능의 `ChatRules.CMI_OPTIONS` 와 같은 순서)의
     * 2번째 칸 `shiftSignEdit`. 칸이 없으면(옛 판) true(CMI 기본), `1`/`0` 이 아니거나 `:` 목록이 아니면 null(못 읽음 — 보고서에).
     */
    fun shiftSignEdit(raw: String): Boolean? {
        val text = raw.trim()
        if (':' !in text) return null
        return when (text.split(':').getOrNull(1)?.trim()) {
            null -> true
            "1" -> true
            "0" -> false
            else -> null
        }
    }

    fun settings(cmi: YamlConfiguration, out: YamlConfiguration, report: ImportReport, modules: YamlConfiguration? = null) {
        report.read(SETTINGS)
        val blacklist = LinkedHashSet<String>()
        blacklist += cmi.getStringList("Signs.editBlackList")
        blacklist += cmi.getStringList("Optimizations.SignEdit.BlackList")
        if (blacklist.isNotEmpty()) out.set("sign-edit.blacklist", blacklist.map { it.trim() }.filter { it.isNotEmpty() })
        if (cmi.contains("Signs.RequireSneaking")) {
            val sneak = cmi.getBoolean("Signs.RequireSneaking")
            out.set("sign-edit.require-sneaking", sneak)
            if (!sneak) report.note("CMI Signs.RequireSneaking=false 를 옮겼습니다 — 맨손 우클릭으로 표지판 편집 화면이 열립니다(바닐라와 같음). 밀랍칠한 표지판은 늘 웅크려야 하고, 클릭 명령이 든 표지판은 명령이 먼저입니다")
        }
        if (cmi.contains("Signs.DialogEditor")) out.set("sign-edit.dialog", cmi.getBoolean("Signs.DialogEditor"))
        cmi.getConfigurationSection("Optimizations.ArmorStands")?.let { a ->
            if (a.contains("CheckBlockPlace")) {
                report.note("CMI Optimizations.ArmorStands.CheckBlockPlace=${a.getBoolean("CheckBlockPlace")} 는 옮기지 않았습니다 — 명령으로 거치대를 열 때 늘 보호 구역을 확인합니다(.armorstand.any 만 건너뜀)")
            }
            if (a.contains("MinScale")) out.set("armorstand.min-scale", a.getDouble("MinScale"))
            if (a.contains("MaxScale")) out.set("armorstand.max-scale", a.getDouble("MaxScale"))
            if (a.contains("Templates.SpecificPermission")) out.set("armorstand.template-permission", a.getBoolean("Templates.SpecificPermission"))
        }
        if (modules != null) {
            fun off(key: String) = modules.contains(key) && !modules.getBoolean(key, true)
            if (off("paintingEditor")) {
                out.set("painting.enabled", false)
                report.note("CMI Modules.yml paintingEditor=false — 그림 바꾸기(painting.enabled)를 껐습니다")
            }
            if (off("armorstand")) {
                out.set("armorstand.shift-click", false)
                report.note("CMI Modules.yml armorstand=false — 웅크려 우클릭 거치대 편집기(armorstand.shift-click)를 껐습니다(/갑옷거치대 는 권한대로)")
            }
            if (off("signEdit")) {
                out.set("sign-edit.enabled", false)
                report.note("CMI Modules.yml signEdit=false — 웅크려 우클릭 표지판 편집(sign-edit.enabled)을 껐습니다")
            }
        }
        report.imported(SETTINGS)
    }

    /** CMI 동적 표지판들 → 우리 것(번호는 받는 쪽이 새로 준다). [existing] 과 같은 자리는 건너뛴다. */
    fun dynamicSigns(y: YamlConfiguration, existing: Collection<DynamicSigns.DynSign>, report: ImportReport): List<DynamicSigns.DynSign> {
        val out = ArrayList<DynamicSigns.DynSign>()
        for (key in y.getKeys(false)) {
            val s = y.getConfigurationSection(key)
            report.read(DSIGN)
            if (s == null) {
                report.unreadable(DSIGN, "$key: ${y.get(key)}")
                continue
            }
            val loc = parseLocation(s.getString("Loc") ?: s.getString("loc") ?: s.getString("Location"))
            if (loc == null) {
                report.unreadable(DSIGN, "$key.Loc: ${s.get("Loc")}")
                continue
            }
            val lines = (s.getStringList("Lines").ifEmpty { s.getStringList("lines") }).ifEmpty { listOf("") }
            val (world, x, yy, z) = loc
            if (existing.any { it.world == world && it.x == x && it.y == yy && it.z == z } || out.any { it.world == world && it.x == x && it.y == yy && it.z == z }) {
                report.skipped(DSIGN, "$key — 그 자리($world $x $yy $z)에 이미 동적 표지판이 있음")
                continue
            }
            out += DynamicSigns.DynSign(0, world, x, yy, z, lines, s.getInt("Interval", 5).coerceIn(1, 3600), s.getInt("Range", 16).coerceIn(1, 256),
                s.getBoolean("Personal", false))
            report.imported(DSIGN)
        }
        return out
    }

    data class Loc(val world: String, val x: Int, val y: Int, val z: Int)

    /** `월드;x;y;z[;yaw;pitch]` 또는 `월드:x:y:z[:yaw:pitch]`. */
    fun parseLocation(raw: String?): Loc? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parts = text.split(';').takeIf { it.size >= 4 } ?: text.split(':').takeIf { it.size >= 4 } ?: return null
        val x = parts[1].trim().toDoubleOrNull() ?: return null
        val y = parts[2].trim().toDoubleOrNull() ?: return null
        val z = parts[3].trim().toDoubleOrNull() ?: return null
        val world = parts[0].trim().takeIf { it.isNotEmpty() } ?: return null
        return Loc(world, Math.floor(x).toInt(), Math.floor(y).toInt(), Math.floor(z).toInt())
    }
}
