package com.inmc.essentials.moderation

import com.inmc.essentials.store.LogoutSpot
import org.bukkit.Location
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration

/** 감옥의 네모난 영역(블록 좌표, 양 끝 포함). */
data class JailArea(val world: String, val minX: Int, val minY: Int, val minZ: Int, val maxX: Int, val maxY: Int, val maxZ: Int) {

    fun contains(world: String, x: Double, y: Double, z: Double): Boolean =
        world == this.world && x >= minX && x < maxX + 1 && y >= minY && y < maxY + 1 && z >= minZ && z < maxZ + 1

    fun contains(loc: Location): Boolean = loc.world?.name?.let { contains(it, loc.x, loc.y, loc.z) } == true

    fun contains(spot: LogoutSpot): Boolean = contains(spot.world, spot.x, spot.y, spot.z)

    fun overlaps(o: JailArea): Boolean =
        world == o.world && minX <= o.maxX && maxX >= o.minX && minY <= o.maxY && maxY >= o.minY && minZ <= o.maxZ && maxZ >= o.minZ

    /** 감방이 없을 때 보내는 자리 — 가로 가운데, 높이는 영역의 **가운데**(바닥 블록(minY) 안이 아니게 — 선 자리 둘레로 만든 감옥은 선 자리 높이). */
    fun center(): LogoutSpot = LogoutSpot(world, (minX + maxX + 1) / 2.0, ((minY + maxY) / 2).toDouble(), (minZ + maxZ + 1) / 2.0, 0f, 0f)

    fun describe(): String = "$world ($minX,$minY,$minZ) ~ ($maxX,$maxY,$maxZ)"

    companion object {
        fun of(world: String, x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int) =
            JailArea(world, minOf(x1, x2), minOf(y1, y2), minOf(z1, z2), maxOf(x1, x2), maxOf(y1, y2), maxOf(z1, z2))

        /** 선 자리 둘레 [radius] 블록(위아래 포함) — WorldEdit 선택이 없을 때. */
        fun around(world: String, x: Int, y: Int, z: Int, radius: Int) = of(world, x - radius, y - radius, z - radius, x + radius, y + radius, z + radius)
    }
}

data class JailCell(val id: Int, val spot: LogoutSpot)

/** 감옥 하나(CMI `Saves/Jails.yml` 의 jail). [outside] = 석방 위치(없으면 가두기 전 자리 → 월드 스폰). */
data class Jail(val name: String, val area: JailArea, val outside: LogoutSpot?, val cells: List<JailCell>) {
    fun cell(id: Int): JailCell? = cells.firstOrNull { it.id == id }
    fun nextCellId(): Int = (cells.maxOfOrNull { it.id } ?: 0) + 1
}

/**
 * `data/jails.yml` ↔ [Jail]. 순수(서버 없이 시험 — `JailCodecTest`). 못 읽은 감옥·감방은 [read] 의 문제 목록으로 — 그 원문은 [Result.raw](감옥)·
 * [Result.held](감방 — 못 읽은 것과 **영역 밖**인 것)로 들고 있다가 다음 저장에 되써 넣는다(지뢰 13 — 거부한 항목이 다음 저장에서 사라지지 않게.
 * 손으로 영역을 잘못 고쳐 감방이 밖으로 나가도 영역을 바로잡으면 돌아온다).
 */
/** 감방 원문 — 감옥 이름 → (감방 키 → 자리 글). 쓰지 않는 감방(못 읽음·영역 밖)을 파일에 남기기 위해. */
typealias HeldCells = Map<String, Map<String, String>>

object JailCodec {

    class Result(val jails: List<Jail>, val problems: List<String>, val raw: Map<String, Any?>, val held: HeldCells = emptyMap())

    fun read(yaml: YamlConfiguration): Result {
        val root = yaml.getConfigurationSection("jails") ?: return Result(emptyList(), emptyList(), emptyMap())
        val jails = ArrayList<Jail>()
        val problems = ArrayList<String>()
        val raw = LinkedHashMap<String, Any?>()
        val held = LinkedHashMap<String, Map<String, String>>()
        for (name in root.getKeys(false)) {
            val sec = root.getConfigurationSection(name)
            val heldHere = LinkedHashMap<String, String>()
            val jail = sec?.let { parse(name, it, problems, heldHere) }
            if (jail == null) {
                problems += "감옥 '$name' 을(를) 읽지 못해 그대로 둡니다"
                raw[name] = root.get(name).let { if (it is ConfigurationSection) plain(it) else it }
            } else {
                jails += jail
                if (heldHere.isNotEmpty()) held[name] = heldHere
            }
        }
        return Result(jails, problems, raw, held)
    }

    /** 섹션 → 중첩 지도(다시 쓸 때 섹션 객체를 그대로 넣지 않게). */
    private fun plain(sec: ConfigurationSection): Map<String, Any?> =
        sec.getKeys(false).associateWith { key -> sec.get(key).let { if (it is ConfigurationSection) plain(it) else it } }

    private fun parse(name: String, sec: ConfigurationSection, problems: MutableList<String>, held: MutableMap<String, String>): Jail? {
        val world = sec.getString("world") ?: return null
        val min = ints(sec.getString("min")) ?: return null
        val max = ints(sec.getString("max")) ?: return null
        val area = JailArea.of(world, min[0], min[1], min[2], max[0], max[1], max[2])
        val outside = sec.getString("outside")?.let { spot(it) ?: null.also { _ -> problems += "감옥 '$name' 의 석방 위치를 읽지 못했습니다: $it" } }
        val cells = sec.getConfigurationSection("cells")?.let { cs ->
            cs.getKeys(false).mapNotNull { key ->
                val id = key.toIntOrNull()
                val s = cs.getString(key)?.let { spot(it) }
                when {
                    id == null || s == null -> {
                        problems += "감옥 '$name' 의 감방 '$key' 을(를) 읽지 못해 쓰지 않습니다(파일에는 남겨 둡니다): ${cs.getString(key)}"
                        cs.getString(key)?.let { held[key] = it }
                        null
                    }
                    !area.contains(s) -> {
                        // 영역 밖 감방은 들어갈 수 없는 자리다(이동 리스너가 영역 밖으로 가는 이동을 막는다) — 쓰지 않되 파일에는 남긴다(영역을 바로잡으면 돌아온다).
                        problems += "감옥 '$name' 의 감방 '$key' 이(가) 영역 밖이라 쓰지 않습니다(파일에는 남겨 둡니다 — 영역을 고치거나 감방을 지우세요): ${cs.getString(key)}"
                        held[key] = cs.getString(key)!!
                        null
                    }
                    else -> JailCell(id, s)
                }
            }
        }.orEmpty()
        return Jail(name, area, outside, cells.sortedBy { it.id })
    }

    /** [held] 의 감방은 그 감옥이 [jails] 에 있고 같은 번호의 감방이 없을 때만 되써 넣는다(감옥을 지웠으면 같이 사라진다). */
    fun write(jails: Collection<Jail>, raw: Map<String, Any?> = emptyMap(), held: HeldCells = emptyMap()): YamlConfiguration {
        val yaml = YamlConfiguration()
        yaml.options().setHeader(listOf(
            "inmc-essentials 감옥 — /감옥편집 으로 고친다(손으로 고쳤으면 /에센셜 리로드 — 다시 읽는다. 감방은 영역 안에 있어야 한다).",
            "min/max = 영역의 두 끝(x,y,z) · outside = 석방 위치(월드;x;y;z;yaw;pitch) · cells.<번호> = 감방 자리",
        ))
        for (jail in jails) {
            val p = "jails.${jail.name}"
            yaml.set("$p.world", jail.area.world)
            yaml.set("$p.min", "${jail.area.minX},${jail.area.minY},${jail.area.minZ}")
            yaml.set("$p.max", "${jail.area.maxX},${jail.area.maxY},${jail.area.maxZ}")
            yaml.set("$p.outside", jail.outside?.let { spotText(it) })
            for (cell in jail.cells) yaml.set("$p.cells.${cell.id}", spotText(cell.spot))
            for ((key, text) in held[jail.name].orEmpty()) if (!yaml.contains("$p.cells.$key")) yaml.set("$p.cells.$key", text)
        }
        for ((name, value) in raw) if (jails.none { it.name == name }) yaml.set("jails.$name", value)
        return yaml
    }

    fun spotText(s: LogoutSpot): String = listOf(s.world, fmt(s.x), fmt(s.y), fmt(s.z), fmt(s.yaw.toDouble()), fmt(s.pitch.toDouble())).joinToString(";")

    /** `월드;x;y;z(;yaw;pitch)` — `:` 로 나눈 것도 받는다(CMI). */
    fun spot(text: String): LogoutSpot? {
        val p = text.split(';', ':').map { it.trim() }
        if (p.size < 4 || p[0].isEmpty()) return null
        val x = p[1].toDoubleOrNull() ?: return null
        val y = p[2].toDoubleOrNull() ?: return null
        val z = p[3].toDoubleOrNull() ?: return null
        return LogoutSpot(p[0], x, y, z, p.getOrNull(4)?.toFloatOrNull() ?: 0f, p.getOrNull(5)?.toFloatOrNull() ?: 0f)
    }

    private fun ints(text: String?): IntArray? {
        val p = text?.split(',', ';', ':')?.map { it.trim().toDoubleOrNull()?.toInt() } ?: return null
        if (p.size != 3 || p.any { it == null }) return null
        return IntArray(3) { p[it]!! }
    }

    private fun fmt(v: Double): String = if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else "%.3f".format(java.util.Locale.ROOT, v)

    /**
     * CMI `Saves/Jails.yml`(jar 의 키: `jails.<이름>.Loc` · `safeLoc` · `cells.<번호>.location`). 영역 `Loc` 은 `월드:x1:y1:z1:x2:y2:z2`
     * 또는 `;` 로 나눈 것을 받는다. 못 읽은 것은 [problem].
     */
    fun fromCmi(yaml: YamlConfiguration, problem: (String) -> Unit): List<Jail> {
        val root = yaml.getConfigurationSection("jails") ?: yaml
        val out = ArrayList<Jail>()
        for (name in root.getKeys(false)) {
            val sec = root.getConfigurationSection(name) ?: continue
            val locText = sec.getString("Loc") ?: sec.getString("loc")
            val parts = locText?.split(':', ';')?.map { it.trim() }
            val nums = parts?.drop(1)?.map { it.toDoubleOrNull()?.toInt() }
            if (parts == null || parts.size < 7 || nums == null || nums.take(6).any { it == null }) {
                problem("감옥 '$name' — 영역(Loc)을 읽지 못했습니다: $locText")
                continue
            }
            val area = JailArea.of(parts[0], nums[0]!!, nums[1]!!, nums[2]!!, nums[3]!!, nums[4]!!, nums[5]!!)
            val outside = sec.getString("safeLoc")?.let { spot(it) ?: null.also { _ -> problem("감옥 '$name' — 석방 위치(safeLoc)를 읽지 못했습니다: $it") } }
            val cells = sec.getConfigurationSection("cells")?.let { cs ->
                cs.getKeys(false).mapNotNull { key ->
                    val text = cs.getString("$key.location") ?: cs.getString(key)
                    val id = key.toIntOrNull()
                    val s = text?.let { spot(it) }
                    when {
                        id == null || s == null -> {
                            problem("감옥 '$name' — 감방 '$key' 을(를) 읽지 못했습니다: $text")
                            null
                        }
                        !area.contains(s) -> {
                            problem("감옥 '$name' — 감방 '$key' 이(가) 영역(Loc) 밖이라 뺍니다: $text")
                            null
                        }
                        else -> JailCell(id, s)
                    }
                }
            }.orEmpty()
            out += Jail(name, area, outside, cells.sortedBy { it.id })
        }
        return out
    }
}
