package com.inmc.essentials.moderation

import com.inmc.essentials.moderation.DataRules.Column
import com.inmc.essentials.moderation.DataRules.Op
import com.inmc.essentials.moderation.ModStore.Companion.prepare
import com.google.gson.JsonParser
import kr.inmc.core.store.SqlDialect
import kr.inmc.core.store.SqlSource
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 데이터 도구의 DB·파일 쪽(쓰기 스레드·입출력 워커). 지우거나 바꾸는 일은 **바로 하지 않는다** — 확인하면 `ess_moderation_pending` 에 적고,
 * 다음 시작 때 [applyPending] 이 다른 기능이 표를 읽기 **전**(schema 단계)에 적용한다. 홈·우편·킷처럼 켤 때 표를 통째로 메모리에 드는 기능이
 * 있어서, 돌고 있는 서버에서 줄을 지우면 메모리의 값이 다음 쓰기에 되살아나거나 화면에 남는다.
 */
object DataTools {

    // --- 표 살피기(쓰기 스레드) ------------------------------------------------------------------------

    /** `ess_` 표 → 열 이름들. */
    fun tables(sql: SqlSource): Map<String, List<String>> {
        val c = sql.connection()
        val meta = c.metaData
        val catalog = if (sql.dialect == SqlDialect.MYSQL) c.catalog else null
        val names = ArrayList<String>()
        meta.getTables(catalog, null, "ess%", arrayOf("TABLE")).use { rs -> while (rs.next()) names += rs.getString("TABLE_NAME") }
        val out = LinkedHashMap<String, List<String>>()
        for (t in names.filter { it.lowercase().startsWith("ess_") }.sorted()) {
            val cols = ArrayList<String>()
            meta.getColumns(catalog, null, t, "%").use { rs -> while (rs.next()) cols += rs.getString("COLUMN_NAME") }
            out[t.lowercase()] = cols
        }
        return out
    }

    class ColumnInfo(val nullable: Boolean, val default: String?, val key: Boolean)

    fun columnInfo(sql: SqlSource, column: Column): ColumnInfo? {
        val c = sql.connection()
        val meta = c.metaData
        val catalog = if (sql.dialect == SqlDialect.MYSQL) c.catalog else null
        val table = tables(sql).keys.firstOrNull { it.equals(column.table, true) } ?: return null
        var info: ColumnInfo? = null
        val keys = HashSet<String>()
        meta.getPrimaryKeys(catalog, null, table).use { rs -> while (rs.next()) keys += rs.getString("COLUMN_NAME").lowercase() }
        meta.getColumns(catalog, null, table, "%").use { rs ->
            while (rs.next()) {
                if (!rs.getString("COLUMN_NAME").equals(column.column, true)) continue
                info = ColumnInfo(rs.getInt("NULLABLE") != java.sql.DatabaseMetaData.columnNoNulls, rs.getString("COLUMN_DEF"), column.column.lowercase() in keys)
            }
        }
        return info
    }

    /** [column] 에서 [ids] 중 하나인 줄 수. */
    fun count(sql: SqlSource, column: Column, ids: List<String>): Int {
        var total = 0
        for (chunk in ids.chunked(DataRules.CHUNK)) {
            if (chunk.isEmpty()) continue
            sql.connection().prepareStatement("SELECT COUNT(*) FROM ${column.table} WHERE ${column.column} IN (${chunk.joinToString(",") { "?" }})").use { ps ->
                chunk.forEachIndexed { i, v -> ps.setString(i + 1, v) }
                ps.executeQuery().use { rs -> if (rs.next()) total += rs.getInt(1) }
            }
        }
        return total
    }

    fun countWhere(sql: SqlSource, table: String, where: Column?, whereId: String?, world: String?): Int {
        val (clause, params) = whereClause(where, whereId, world)
        sql.connection().prepareStatement("SELECT COUNT(*) FROM $table$clause").use { ps ->
            params.forEachIndexed { i, v -> ps.setString(i + 1, v) }
            ps.executeQuery().use { rs -> return if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    private fun whereClause(where: Column?, whereId: String?, world: String?): Pair<String, List<String>> {
        val parts = ArrayList<String>()
        val params = ArrayList<String>()
        if (where != null && whereId != null) {
            parts += "${where.column} = ?"; params += whereId
        }
        if (world != null) {
            parts += "world = ?"; params += world
        }
        return (if (parts.isEmpty()) "" else " WHERE " + parts.joinToString(" AND ")) to params
    }

    /** 표에 `world` 열이 있나(열 초기화의 -w:). */
    fun hasWorld(tables: Map<String, List<String>>, table: String): Boolean = tables[table]?.any { it.equals("world", true) } == true

    // --- 예약 적용(다음 시작 — schema 단계, 쓰기 스레드) ------------------------------------------------------

    /**
     * 예약된 작업을 차례로 적용하고 지운다. 적용과 예약 줄 지우기는 **한 트랜잭션** — 커밋과 지우기 사이에 서버가 죽어 다음 시작에 또 적용되지
     * 않게(교환은 두 번 하면 원래대로 돌아간다). 실패하면 되돌리고 따로 지운다(매번 같은 실패로 켜기가 막히지 않게, 내용은 로그에).
     * 바닐라 파일 옮기기는 [files] 로 넘긴다(입출력 워커에서 — 켜기의 DB 대기 시간 한도 안에 들지 않게). 적용한 수.
     *
     * **시간 한도**([deadline], 밀리초 시각): 이 단계는 켜기의 DB 대기 한도(core `SqlWorker.call` 30초) 안에서 돈다. 미접속 정리([DataRules.Kind.PURGE])는
     * 사람을 [PURGE_SLICE] 명씩 **조각마다 한 트랜잭션**으로 지우고 같은 트랜잭션에서 예약 줄의 내용을 남은 사람으로 바꾼다 — 한도가 지나면 거기서
     * 멈추고 나머지는 다음 시작에 이어서 한다(정리는 기준 시각으로 다시 거르므로 몇 번에 나눠 해도 같은 결과). 조각 사이·예약 사이에서만 멈추므로
     * 교환·삭제·초기화 같은 작은 예약은 쪼개지 않는다.
     */
    fun applyPending(sql: SqlSource, logger: Logger, files: (Op, List<String>) -> Unit, deadline: Long = Long.MAX_VALUE): Int {
        val rows = ArrayList<Triple<Long, String, String>>()
        sql.connection().createStatement().use { st ->
            st.executeQuery("SELECT serial, payload, by_name FROM ${ModStore.PENDING} ORDER BY serial").use { rs ->
                while (rs.next()) rows += Triple(rs.getLong(1), rs.getString(2), rs.getString(3).orEmpty())
            }
        }
        var applied = 0
        for ((index, row) in rows.withIndex()) {
            val (serial, payload, by) = row
            if (System.currentTimeMillis() > deadline) {
                logger.warning("데이터 도구 예약 — 켜기의 시간 한도에 닿아 남은 ${rows.size - index}건은 다음 시작에 적용합니다")
                break
            }
            val op = DataRules.decode(payload)
            var removed = false
            try {
                if (op == null) {
                    logger.severe("데이터 도구 예약 $serial 을(를) 읽지 못해 버립니다: ${payload.take(200)}")
                } else if (op.kind == DataRules.Kind.PURGE) {
                    val purged = ArrayList<String>()
                    var rowsDone = 0
                    var finished = false
                    for ((slice, rest) in purgeSlices(op)) {
                        val r = sql.transaction { c ->
                            val r = apply(c, slice)
                            if (rest == null) c.prepareStatement("DELETE FROM ${ModStore.PENDING} WHERE serial = ?").use { ps -> ps.setLong(1, serial); ps.executeUpdate() }
                            else c.prepareStatement("UPDATE ${ModStore.PENDING} SET payload = ? WHERE serial = ?").use { ps ->
                                ps.setString(1, DataRules.encode(rest)); ps.setLong(2, serial); ps.executeUpdate()
                            }
                            r
                        }
                        purged += r.purged
                        rowsDone += r.rows
                        if (rest == null) {
                            finished = true
                            break
                        }
                        if (System.currentTimeMillis() > deadline) {
                            logger.warning("데이터 도구 예약 $serial (${by}) — ${op.describe()}: 켜기의 시간 한도에 닿아 ${purged.size}명(줄 ${rowsDone}개)까지 하고 남은 ${rest.ids.size}명은 다음 시작에 이어서 합니다")
                            break
                        }
                    }
                    removed = true
                    applied++
                    if (finished) logger.warning("데이터 도구 예약 $serial 적용(${by}) — ${op.describe()}: ${Applied(rowsDone, purged)}")
                    if (op.folders.isNotEmpty() && purged.isNotEmpty()) files(op, purged)
                } else {
                    val result = sql.transaction { c ->
                        val r = apply(c, op)
                        c.prepareStatement("DELETE FROM ${ModStore.PENDING} WHERE serial = ?").use { ps -> ps.setLong(1, serial); ps.executeUpdate() }
                        r
                    }
                    removed = true
                    applied++
                    logger.warning("데이터 도구 예약 $serial 적용(${by}) — ${op.describe()}: $result")
                }
            } catch (t: Throwable) {
                logger.log(Level.SEVERE, "데이터 도구 예약 $serial 적용 실패 — ${op?.describe()}. 되돌렸습니다(트랜잭션).", t)
            } finally {
                if (!removed) runCatching { sql.prepare("DELETE FROM ${ModStore.PENDING} WHERE serial = ?") { it.setLong(1, serial) } }
                    .onFailure { logger.log(Level.SEVERE, "데이터 도구 예약 $serial 을(를) 지우지 못했습니다 — 다음 시작에 또 적용될 수 있습니다", it) }
            }
        }
        return applied
    }

    /** 미접속 정리를 한 번에 지울 사람 수(조각 하나 = 트랜잭션 하나). */
    const val PURGE_SLICE = 500

    /**
     * 미접속 정리를 [size] 명씩 조각낸다 — (이 조각의 작업, 그 뒤에 남는 작업 또는 끝이면 null). 순수(`DataToolsTest`).
     * 사람이 없으면 조각 하나(남는 것 없음) — 예약 줄을 지우기 위해.
     */
    fun purgeSlices(op: Op, size: Int = PURGE_SLICE): List<Pair<Op, Op?>> {
        if (op.ids.isEmpty()) return listOf(op to null)
        val chunks = op.ids.chunked(size)
        return chunks.mapIndexed { i, chunk ->
            val rest = chunks.drop(i + 1).flatten()
            op.copy(ids = chunk) to (if (rest.isEmpty()) null else op.copy(ids = rest))
        }
    }

    class Applied(val rows: Int, val purged: List<String>) {
        override fun toString(): String = "줄 ${rows}개" + if (purged.isNotEmpty()) " · 사람 ${purged.size}명" else ""
    }

    private fun apply(c: Connection, op: Op): Applied {
        var rows = 0
        val existing = existingColumns(c)
        val columns = op.columns.filter { col -> existing[col.table]?.contains(col.column) == true }
        when (op.kind) {
            DataRules.Kind.SWITCH -> {
                val a = op.a ?: return Applied(0, emptyList())
                val b = op.b ?: return Applied(0, emptyList())
                val tmp = UUID.randomUUID().toString()
                for (col in columns) {
                    rows += update(c, "UPDATE ${col.table} SET ${col.column} = ? WHERE ${col.column} = ?", tmp, a)
                    rows += update(c, "UPDATE ${col.table} SET ${col.column} = ? WHERE ${col.column} = ?", a, b)
                    update(c, "UPDATE ${col.table} SET ${col.column} = ? WHERE ${col.column} = ?", b, tmp)
                }
            }
            DataRules.Kind.REMOVE -> for (col in columns) rows += delete(c, col, op.ids)
            DataRules.Kind.PURGE -> {
                // 예약 뒤에 들어온 사람은 빼고 — 지금도 기준보다 오래된 사람만.
                val cutoff = op.cutoff ?: return Applied(0, emptyList())
                val still = ArrayList<String>()
                for (chunk in op.ids.chunked(DataRules.CHUNK)) {
                    c.prepareStatement("SELECT uuid FROM ess_users WHERE last_join < ? AND uuid IN (${chunk.joinToString(",") { "?" }})").use { ps ->
                        ps.setLong(1, cutoff)
                        chunk.forEachIndexed { i, v -> ps.setString(i + 2, v) }
                        ps.executeQuery().use { rs -> while (rs.next()) still += rs.getString(1) }
                    }
                }
                for (col in columns) rows += delete(c, col, still)
                return Applied(rows, still)
            }
            DataRules.Kind.RESET -> {
                val target = op.reset ?: return Applied(0, emptyList())
                if (existing[target.table]?.contains(target.column) != true) return Applied(0, emptyList())
                val (clause, params) = whereClause(op.where, op.whereId, op.world)
                c.prepareStatement("UPDATE ${target.table} SET ${target.column} = ?$clause").use { ps ->
                    when (val v = op.value) {
                        null -> ps.setNull(1, java.sql.Types.NULL)
                        is Long -> ps.setLong(1, v)
                        is Double -> ps.setDouble(1, v)
                        else -> ps.setString(1, v.toString())
                    }
                    params.forEachIndexed { i, v -> ps.setString(i + 2, v) }
                    rows += ps.executeUpdate()
                }
            }
        }
        return Applied(rows, emptyList())
    }

    private fun existingColumns(c: Connection): Map<String, Set<String>> {
        val meta = c.metaData
        val catalog = runCatching { c.catalog }.getOrNull()
        val out = HashMap<String, MutableSet<String>>()
        meta.getColumns(catalog, null, "ess%", "%").use { rs ->
            while (rs.next()) out.getOrPut(rs.getString("TABLE_NAME").lowercase()) { HashSet() } += rs.getString("COLUMN_NAME").lowercase()
        }
        return out
    }

    private fun update(c: Connection, sql: String, a: String, b: String): Int = c.prepareStatement(sql).use { ps ->
        ps.setString(1, a); ps.setString(2, b); ps.executeUpdate()
    }

    private fun delete(c: Connection, col: Column, ids: List<String>): Int {
        var n = 0
        for (chunk in ids.chunked(DataRules.CHUNK)) {
            if (chunk.isEmpty()) continue
            c.prepareStatement("DELETE FROM ${col.table} WHERE ${col.column} IN (${chunk.joinToString(",") { "?" }})").use { ps ->
                chunk.forEachIndexed { i, v -> ps.setString(i + 1, v) }
                n += ps.executeUpdate()
            }
        }
        return n
    }

    // --- 바닐라 파일(입출력 워커) ----------------------------------------------------------------------

    /** 그 사람들의 파일(`<uuid>.dat`·`.dat_old`·`.json`) — 폴더마다. */
    fun vanillaFiles(folders: List<String>, ids: Collection<String>): List<File> {
        val out = ArrayList<File>()
        for (path in folders) {
            val dir = File(path)
            if (!dir.isDirectory) continue
            for (id in ids) for (ext in listOf(".dat", ".dat_old", ".json")) File(dir, id + ext).takeIf { it.isFile }?.let { out += it }
        }
        return out
    }

    /** 옮기거나(폴더 옆 `<폴더>_backup`) 지운다. 옮긴·지운 수. */
    fun purgeFiles(folders: List<String>, ids: Collection<String>, delete: Boolean, logger: Logger): Int {
        var n = 0
        for (file in vanillaFiles(folders, ids)) {
            try {
                if (delete) Files.deleteIfExists(file.toPath())
                else {
                    val backup = File(file.parentFile.parentFile, file.parentFile.name + "_backup").apply { mkdirs() }
                    Files.move(file.toPath(), File(backup, file.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                n++
            } catch (t: Throwable) {
                logger.warning("파일 정리 실패: ${file.path} — ${t.message}")
            }
        }
        return n
    }

    // --- 옛 유저 가져오기(입출력 워커) ------------------------------------------------------------------

    /** playerdata 의 uuid 들(파일 수정 시각과 함께) · usercache.json 의 이름. */
    class OldUsers(val seen: Map<UUID, Long>, val names: Map<UUID, String>, val problems: List<String>)

    fun readOldUsers(playerdata: File, usercache: File): OldUsers {
        val seen = LinkedHashMap<UUID, Long>()
        val problems = ArrayList<String>()
        playerdata.listFiles { f -> f.isFile && f.name.endsWith(".dat") }?.forEach { f ->
            val id = runCatching { UUID.fromString(f.name.removeSuffix(".dat")) }.getOrNull()
            if (id == null) problems += "uuid 가 아닌 파일: ${f.name}" else seen[id] = f.lastModified()
        }
        val names = HashMap<UUID, String>()
        if (usercache.isFile) {
            try {
                val root = JsonParser.parseString(usercache.readText(Charsets.UTF_8))
                if (root.isJsonArray) for (e in root.asJsonArray) {
                    if (!e.isJsonObject) continue
                    val o = e.asJsonObject
                    val id = runCatching { UUID.fromString(o.get("uuid")?.asString) }.getOrNull() ?: continue
                    val name = o.get("name")?.asString?.takeIf { it.isNotBlank() } ?: continue
                    names[id] = name
                }
            } catch (t: Throwable) {
                problems += "usercache.json 을 읽지 못했습니다: ${t.message}"
            }
        } else problems += "usercache.json 이 없습니다: ${usercache.path}"
        return OldUsers(seen, names, problems)
    }
}
