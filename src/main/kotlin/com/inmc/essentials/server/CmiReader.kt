package com.inmc.essentials.server

import com.inmc.essentials.service.CmiData
import com.inmc.essentials.service.CmiLocation
import com.inmc.essentials.service.CmiUser
import com.inmc.essentials.service.CmiWarp
import com.inmc.essentials.service.ImportReport
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipFile

/**
 * `plugins/CMI` 를 읽어 [CmiData] 를 만든다 — **I/O 스레드에서**(DB·파일). CMI 폴더는 고치지 않는다: SQLite 는 사본을 만들어 읽기 전용으로 연다
 * (CMI 가 켜져 있어도 잠금·WAL 을 건드리지 않게). MySQL 이면 `DataBaseInfo.yml` 의 접속 정보와 표 접두(`CMI_`)로.
 *
 * 못 읽은 것은 [ImportReport.unreadable] 로 원문과 함께 남긴다 — 홈·우편이 소리 없이 사라지는 것이 가장 나쁜 결과다.
 * YAML 저장 파일은 비었거나 CMI jar 안의 기본 파일과 같으면(빈칸 차이 무시) null 로 넘긴다.
 */
class CmiReader(private val folder: File, private val tempDir: File, private val log: (String) -> Unit) {

    /** 주인 기능이 없는 열 — 보고서에 숫자로. */
    class Extras {
        var balances = 0
        val nicknames = mutableListOf<String>()
        var bans = 0
        var playtimeRows = 0
        var playtimeRewardRows = 0
        var inventoryRows = 0
        val cooldownRules = mutableListOf<String>()
        val warmupRules = mutableListOf<String>()
        val costRules = mutableListOf<String>()

        // server 기능의 몫(config.yml · CustomText) — CmiData 에는 칸이 없어 여기로(ServerFeature.importCmi 가 읽는다).
        var maintenance: Boolean? = null
        var maintenanceAutoKick: Boolean? = null
        var maintenanceBossBar: Boolean? = null
        /** config `Motd:` — 접속 때 보일 CustomText 이름. */
        var joinText: String? = null
        var bookAuthor: String? = null
        var bookAddDate: Boolean? = null
        /** `CustomText` 폴더의 txt — 이름 → 원문(CMI jar 기본과 같은 것은 뺌). */
        val customTexts = LinkedHashMap<String, String>()
    }

    val extras = Extras()

    fun read(report: ImportReport): CmiData {
        val users = runCatching { users(report) }.getOrElse { t ->
            report.unreadable("유저", "유저 DB 를 열지 못했습니다: ${t.javaClass.simpleName}: ${t.message}")
            emptyList()
        }
        val config = yaml(File(folder, "config.yml"))
        val spawn = config?.let { loc(it.getString("Spawn.Main.Location"), "스폰", report) }
        val firstSpawn = config?.takeIf { it.getBoolean("Spawn.FirstSpawn.Use", true) }?.let { loc(it.getString("Spawn.FirstSpawn.Location"), "첫 스폰", report) }
        config?.let { commandRules(it, report) }
        config?.let { serverConfig(it) }
        customTexts(report)
        return CmiData(
            folder = folder,
            users = users,
            warps = warps(report),
            spawn = spawn,
            firstSpawn = firstSpawn,
            kits = saved("Kits/Kits.yml", report),
            holograms = saved("Saves/Holograms.yml", report),
            portals = saved("Saves/Portals.yml", report),
            jails = saved("Saves/Jails.yml", report),
            schedules = saved("Settings/Schedules.yml", report),
            playtimeRewards = saved("Settings/PlayTimeRewards.yml", report),
            customAliases = aliases(report),
            eventCommands = saved("Settings/EventCommands.yml", report),
        )
    }

    // --- YAML ---------------------------------------------------------------------------------------

    private fun yaml(file: File): YamlConfiguration? =
        if (file.isFile && file.length() > 0) YamlConfiguration.loadConfiguration(file) else null

    private val jar: File? by lazy {
        folder.parentFile?.listFiles { f -> f.isFile && f.name.startsWith("CMI-") && f.name.endsWith(".jar") }?.maxByOrNull { it.lastModified() }
    }

    /** 저장 파일 — 비었거나 jar 기본과 같으면 null(보고서에 "기본값이라 건너뜀"). */
    private fun saved(path: String, report: ImportReport): YamlConfiguration? {
        val file = File(folder, path)
        val kind = "파일 $path"
        if (!file.isFile || file.length() == 0L) return null
        val text = file.readText(Charsets.UTF_8)
        if (text.isBlank()) return null
        val default = jarText(path)
        if (default != null && squash(default) == squash(text)) {
            report.skipped(kind, "CMI jar 의 기본 파일과 같습니다(예제뿐)")
            return null
        }
        return try {
            YamlConfiguration().apply { loadFromString(text) }.takeIf { it.getKeys(false).isNotEmpty() }
        } catch (e: Exception) {
            report.unreadable(kind, "YAML 을 읽지 못했습니다: ${e.message?.take(200)}")
            null
        }
    }

    /** CMI jar 안의 기본 파일 글(없으면 null). */
    private fun jarText(path: String): String? =
        runCatching { jar?.let { j -> ZipFile(j).use { z -> z.getEntry(path)?.let { e -> z.getInputStream(e).readBytes().toString(Charsets.UTF_8) } } } }.getOrNull()

    private fun serverConfig(config: YamlConfiguration) {
        if (config.contains("Optimizations.Maintenance")) extras.maintenance = config.getBoolean("Optimizations.Maintenance")
        if (config.contains("Optimizations.MaintenanceAutoKick")) extras.maintenanceAutoKick = config.getBoolean("Optimizations.MaintenanceAutoKick")
        if (config.contains("Optimizations.MaintenanceBossBar")) extras.maintenanceBossBar = config.getBoolean("Optimizations.MaintenanceBossBar")
        extras.joinText = config.getString("Motd")?.trim()?.takeIf { it.isNotEmpty() }
        extras.bookAuthor = config.getString("Books.DefaultAuthor")
        if (config.contains("Books.AddDate")) extras.bookAddDate = config.getBoolean("Books.AddDate")
    }

    /** `CustomText` 폴더의 txt 중 jar 기본과 다른 것. */
    private fun customTexts(report: ImportReport) {
        val dir = File(folder, "CustomText")
        for (f in dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }.orEmpty().sortedBy { it.name }) {
            val name = f.name.removeSuffix(".txt")
            report.read("커스텀 텍스트")
            val text = runCatching { f.readText(Charsets.UTF_8) }.getOrElse {
                report.unreadable("커스텀 텍스트", "CustomText/${f.name} — 읽지 못했습니다: ${it.message}")
                continue
            }
            val default = jarText("CustomText/${f.name}")
            if (default != null && squash(default) == squash(text)) {
                report.skipped("커스텀 텍스트", "$name — CMI jar 의 기본 파일과 같습니다(예제뿐)")
                continue
            }
            extras.customTexts[name] = text
        }
    }

    private fun squash(text: String): String = text.replace("\r", "").lines().map { it.trimEnd() }.filter { it.isNotEmpty() }.joinToString("\n")

    /** `CustomAlias` 폴더의 yml 을 하나로(`CustomAlias.<이름>`). */
    private fun aliases(report: ImportReport): YamlConfiguration? {
        val dir = File(folder, "CustomAlias")
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".yml") }.orEmpty()
        val merged = YamlConfiguration()
        for (f in files) {
            val y = runCatching { YamlConfiguration().apply { loadFromString(f.readText(Charsets.UTF_8)) } }.getOrElse {
                report.unreadable("별칭", "CustomAlias/${f.name} — YAML 을 읽지 못했습니다: ${it.message?.take(200)}")
                continue
            }
            val section = y.getConfigurationSection("CustomAlias") ?: y
            for (key in section.getKeys(false)) merged.set("CustomAlias.$key", section.get(key))
        }
        return merged.takeIf { it.getConfigurationSection("CustomAlias")?.getKeys(false)?.isNotEmpty() == true }
    }

    private fun warps(report: ImportReport): List<CmiWarp> {
        val y = saved("Saves/Warps.yml", report) ?: return emptyList()
        val out = mutableListOf<CmiWarp>()
        for (name in y.getKeys(false)) {
            report.read("워프")
            val s = y.getConfigurationSection(name)
            if (s == null) {
                report.unreadable("워프", "$name — 섹션이 아닙니다: ${y.get(name)}")
                continue
            }
            val raw = s.getValues(true).filterValues { it !is org.bukkit.configuration.ConfigurationSection }
            val locText = listOf("primaryLocation", "Location", "Loc", "location").firstNotNullOfOrNull { s.getString(it) }
            val location = try {
                CmiFormats.location(locText, CmiFormats.Order.YAW_PITCH)
            } catch (e: CmiFormats.Bad) {
                report.unreadable("워프", "$name — ${e.message}")
                continue
            }
            if (location == null) {
                report.unreadable("워프", "$name — 위치가 없습니다: $raw")
                continue
            }
            out += CmiWarp(
                name = name,
                location = location,
                group = s.getString("group")?.takeIf { it.isNotBlank() && it != "N/A" },
                permission = s.getBoolean("reqPermission", s.getBoolean("permission", false)),
                icon = s.getString("placeItem")?.takeIf { it.isNotBlank() },
                raw = raw,
            )
        }
        return out
    }

    private fun loc(raw: String?, what: String, report: ImportReport): CmiLocation? =
        try {
            CmiFormats.location(raw, CmiFormats.Order.YAW_PITCH)
        } catch (e: CmiFormats.Bad) {
            report.unreadable(what, e.message ?: raw.orEmpty())
            null
        }

    /** config 의 쿨타임·대기와 CommandCost.yml — 명령 이름이 CMI 것(`cmi heal`)이라 옮기지 않고 목록만 남긴다. */
    private fun commandRules(config: YamlConfiguration, report: ImportReport) {
        if (config.getBoolean("Cooldowns.Enabled", false)) extras.cooldownRules += config.getStringList("Cooldowns.List")
        if (config.getBoolean("WarmUps.Enabled", false)) extras.warmupRules += config.getStringList("WarmUps.List")
        yaml(File(folder, "Settings/CommandCost.yml"))?.takeIf { it.getBoolean("Enabled", false) }?.let { extras.costRules += it.getStringList("Commands") }
    }

    // --- 유저 DB ------------------------------------------------------------------------------------

    private fun users(report: ImportReport): List<CmiUser> {
        val info = yaml(File(folder, "Settings/DataBaseInfo.yml"))
        val method = info?.getString("storage.method", "sqlite")?.trim()?.lowercase() ?: "sqlite"
        return if (method == "mysql") {
            val prefix = info?.getString("mysql.tablePrefix", "CMI_").orEmpty()
            val host = info?.getString("mysql.hostname", "localhost:3306")
            val db = info?.getString("mysql.database", "minecraft")
            val url = "jdbc:mysql://$host/$db?useSSL=${info?.getBoolean("mysql.useSSL", false)}&autoReconnect=true&characterEncoding=utf8"
            runCatching { Class.forName("com.mysql.cj.jdbc.Driver") }
            log("CMI MySQL 을 읽습니다 — $host/$db (표 접두 $prefix)")
            DriverManager.getConnection(url, info?.getString("mysql.username", "root"), info?.getString("mysql.password", "")).use { c -> readUsers(c, prefix, report) }
        } else {
            val source = File(folder, "cmi.sqlite.db")
            if (!source.isFile) {
                report.note("cmi.sqlite.db 가 없습니다 — 유저 자료 없음")
                return emptyList()
            }
            tempDir.mkdirs()
            val copy = File(tempDir, "cmi-copy-${System.currentTimeMillis()}.db")
            Files.copy(source.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING)
            // WAL 모드로 돌던 DB 는 최근 쓰기가 -wal 에 있다 — 같이 베껴야 사본에 보인다.
            for (suffix in listOf("-wal", "-shm")) {
                val side = File(folder, "cmi.sqlite.db$suffix")
                if (side.isFile) Files.copy(side.toPath(), File(copy.path + suffix).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            runCatching { Class.forName("org.sqlite.JDBC") }
            try {
                val props = Properties().apply { setProperty("open_mode", "1") } // SQLITE_OPEN_READONLY
                DriverManager.getConnection("jdbc:sqlite:" + copy.absolutePath, props).use { c -> readUsers(c, "", report) }
            } finally {
                for (suffix in listOf("", "-wal", "-shm")) File(copy.path + suffix).delete()
            }
        }
    }

    private fun readUsers(c: Connection, prefix: String, report: ImportReport): List<CmiUser> {
        extras.playtimeRows = count(c, "${prefix}playtime")
        extras.playtimeRewardRows = count(c, "${prefix}playtimereward")
        extras.inventoryRows = count(c, "${prefix}inventories")
        val daily = playtimeDaily(c, prefix)
        val rewards = playtimeRewards(c, prefix, report)
        val out = mutableListOf<CmiUser>()
        c.createStatement().use { st ->
            st.executeQuery("SELECT * FROM ${prefix}users").use { rs ->
                val columns = (1..rs.metaData.columnCount).map { rs.metaData.getColumnName(it) }
                while (rs.next()) {
                    report.read("유저")
                    user(rs, columns, daily, rewards, report)?.let { out += it }
                }
            }
        }
        return out
    }

    /**
     * playtimereward 표(`player_id` · `repeatable` · `onetime`) → player_id → (받은 한 번 보상 `;` 목록, 반복 보상 기록). 주인(admininfo)이 유저 extra
     * `playtimeRewardOnetime`(줄이 있으면 빈 값이라도 넣는다 — "기록이 있는데 받은 것이 없음" 과 "기록 자체가 없음" 을 가르게) · `playtimeRewardRepeat` 로 읽는다.
     */
    private fun playtimeRewards(c: Connection, prefix: String, report: ImportReport): Map<Int, Pair<String, String>> {
        if (extras.playtimeRewardRows == 0) return emptyMap()
        val out = HashMap<Int, Pair<String, String>>()
        try {
            c.createStatement().use { st ->
                st.executeQuery("SELECT * FROM ${prefix}playtimereward").use { rs ->
                    val columns = (1..rs.metaData.columnCount).map { rs.metaData.getColumnName(it) }
                    while (rs.next()) {
                        val id = rs.getInt("player_id")
                        val onetime = if ("onetime" in columns) rs.getString("onetime").orEmpty() else ""
                        val repeat = if ("repeatable" in columns) rs.getString("repeatable").orEmpty() else ""
                        out[id] = onetime to repeat
                    }
                }
            }
        } catch (e: Exception) {
            report.unreadable("플레이 보상 기록", "CMI playtimereward 표를 읽지 못했습니다: ${e.javaClass.simpleName}: ${e.message} — 받은 한 번 보상 기록 없이 옮깁니다(이미 시간을 넘은 사람은 받은 것으로)")
        }
        return out
    }

    private fun count(c: Connection, table: String): Int =
        runCatching { c.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM $table").use { if (it.next()) it.getInt(1) else 0 } } }.getOrDefault(0)

    /** playtime 표(하루·시간대별 밀리초) → player_id → "날짜=합" 목록. 주인(admininfo)이 쓸 수 있게 extra 로 넘긴다. */
    private fun playtimeDaily(c: Connection, prefix: String): Map<Int, String> {
        if (extras.playtimeRows == 0) return emptyMap()
        val out = HashMap<Int, MutableList<String>>()
        runCatching {
            c.createStatement().use { st ->
                st.executeQuery("SELECT * FROM ${prefix}playtime").use { rs ->
                    while (rs.next()) {
                        val id = rs.getInt("player_id")
                        val date = rs.getInt("date")
                        val total = (0..23).sumOf { h -> runCatching { rs.getLong("h$h") }.getOrDefault(0L) }
                        out.getOrPut(id) { mutableListOf() } += "$date=$total"
                    }
                }
            }
        }
        return out.mapValues { it.value.joinToString(",") }
    }

    private fun user(rs: ResultSet, columns: List<String>, daily: Map<Int, String>, rewards: Map<Int, Pair<String, String>>, report: ImportReport): CmiUser? {
        fun str(name: String): String? = if (name in columns) rs.getString(name)?.takeIf { it.isNotBlank() } else null
        fun long(name: String): Long? = if (name in columns) rs.getLong(name).takeIf { !rs.wasNull() } else null
        val rawId = str("player_uuid")
        val id = runCatching { UUID.fromString(rawId) }.getOrNull()
        val name = str("username") ?: "?"
        if (id == null) {
            report.unreadable("유저", "$name — uuid 가 아닙니다: $rawId")
            return null
        }
        val who = "$name($id)"
        fun <T> safe(what: String, raw: String?, block: () -> T?): T? =
            try {
                block()
            } catch (e: CmiFormats.Bad) {
                report.unreadable(what, "$who — ${e.message} — 원문: ${raw?.take(200)}")
                null
            } catch (e: Exception) {
                report.unreadable(what, "$who — ${e.javaClass.simpleName}: ${e.message} — 원문: ${raw?.take(200)}")
                null
            }
        val homes = safe("홈", str("Homes")) { CmiFormats.homes(str("Homes")) { report.unreadable("홈", "$who — $it") } }.orEmpty()
        val mail = safe("우편", str("Mail")) { CmiFormats.mail(str("Mail")) { report.unreadable("우편", "$who — $it") } }.orEmpty()
        val kits = safe("킷", str("Kits")) { CmiFormats.kits(str("Kits")) { report.unreadable("킷", "$who — $it") } }.orEmpty()
        val now = System.currentTimeMillis()
        str("nickname")?.let { extras.nicknames += "$name → $it" }
        if ((long("Balance") ?: 0L) != 0L || (str("Balance")?.toDoubleOrNull() ?: 0.0) != 0.0) extras.balances++
        if (long("BannedUntil") != null || str("BanReason") != null) extras.bans++
        val known = setOf(
            "id", "player_uuid", "username", "nickname", "LogOutLocation", "DeathLocation", "TeleportLocation", "Homes", "LastLoginTime",
            "LastLogoffTime", "TotalPlayTime", "tFly", "tGod", "Ignores", "Mail", "UserMeta", "Votifier", "Jail", "JailedUntil", "JailReason",
            "Warnings", "Notes", "Muted", "Kits",
        )
        val extra = LinkedHashMap<String, String>()
        for (col in columns) {
            if (col in known) continue
            val v = runCatching { rs.getString(col) }.getOrNull()?.takeIf { it.isNotBlank() && it != "0" } ?: continue
            extra[col] = v
        }
        val rowId = runCatching { rs.getInt("id") }.getOrNull()
        rowId?.let { daily[it] }?.let { extra["playtimeDaily"] = it }
        rowId?.let { rewards[it] }?.let { (onetime, repeat) ->
            extra[EXTRA_PLAYTIME_REWARD_ONETIME] = onetime // 빈 값도 — 줄이 있다는 뜻
            if (repeat.isNotBlank()) extra[EXTRA_PLAYTIME_REWARD_REPEAT] = repeat
        }
        return CmiUser(
            id = id,
            name = name,
            nickname = str("nickname"),
            lastLogin = long("LastLoginTime")?.takeIf { it > 0 },
            lastLogoff = long("LastLogoffTime")?.takeIf { it > 0 },
            totalPlayTime = long("TotalPlayTime")?.takeIf { it > 0 },
            logout = safe("위치", str("LogOutLocation")) { CmiFormats.location(str("LogOutLocation"), CmiFormats.Order.PITCH_YAW) },
            back = safe("위치", str("TeleportLocation")) { CmiFormats.location(str("TeleportLocation"), CmiFormats.Order.PITCH_YAW) },
            death = safe("위치", str("DeathLocation")) { CmiFormats.location(str("DeathLocation"), CmiFormats.Order.PITCH_YAW) },
            homes = homes,
            mail = mail,
            ignores = CmiFormats.ignores(str("Ignores")) { report.unreadable("무시", "$who — $it") },
            mute = safe("뮤트", str("Muted")) { CmiFormats.mute(str("Muted")) },
            jail = safe("감옥", str("Jail")) { CmiFormats.jail(str("Jail"), long("JailedUntil"), str("JailReason")) },
            warnings = CmiFormats.list(str("Warnings")),
            notes = CmiFormats.list(str("Notes")),
            kits = kits,
            userMeta = safe("유저메타", str("UserMeta")) { CmiFormats.meta(str("UserMeta")) }.orEmpty(),
            tfly = CmiFormats.remainingSeconds(long("tFly"), now),
            tgod = CmiFormats.remainingSeconds(long("tGod"), now),
            votes = long("Votifier")?.toInt()?.takeIf { it > 0 },
            extra = extra,
        )
    }
    companion object {
        /** 유저 extra 열쇠 — admininfo 가 읽는다(`AdminInfoFeature.CMI_ONETIME_KEY` 와 같은 글자). 받은 한 번(PayFor) 보상 이름 `;` 목록(빈 값 = 줄은 있는데 받은 것 없음). */
        const val EXTRA_PLAYTIME_REWARD_ONETIME = "playtimeRewardOnetime"

        /** 반복 보상 기록 원문(CMI `repeatable` 열) — 주인이 아직 쓰지 않는다. 보고서용. */
        const val EXTRA_PLAYTIME_REWARD_REPEAT = "playtimeRewardRepeat"
    }
}
