package com.inmc.essentials.server

import com.inmc.essentials.Ess
import com.inmc.essentials.feature.Features
import kr.inmc.core.store.SqlDialect
import kr.inmc.core.store.SqlSource
import org.bukkit.Bukkit
import java.sql.Connection

/**
 * `/에센셜 데이터 이전 <mysql|sqlite> 확인 [덮어쓰기]` — 지금 쓰는 DB 의 `ess_` 표를 다른 쪽으로 전부 베낀다(CMI `migratedatabase`).
 *
 * - **DB 쓰기 스레드에서** 돈다 — 그동안 다른 DB 쓰기는 줄을 선다(그래서 베끼는 중에 바뀌는 줄이 없다). 접속자가 있으면 거절한다(부른 사람 빼고).
 *   시작하는 순간부터 재시작까지 접속을 막는다(ServerFeature 의 이전 잠금 — 베끼는 중·끝난 뒤 재시작 전의 기록이 옛 DB 에만 남지 않게).
 *   멈추거나 실패하면 [run] 의 `finished(false)` 로 잠금을 푼다.
 * - 받는 쪽 표는 모든 기능의 `schema` 로 먼저 만든다(꺼진 기능 것도). 받는 쪽에 이미 줄이 있으면 **멈추고 목록만** 알린다(가이드 6 —
 *   임의로 지우지 않는다). `덮어쓰기` 를 붙이면 그 표를 비우고 베낀다(같은 트랜잭션).
 * - 끝나면 `config.yml storage.type` 을 바꾸고 재시작하라고 알린다 — 이 명령은 설정 파일을 고치지 않는다.
 */
class DbMigration(private val ess: Ess) {

    fun interface Progress {
        fun say(key: String, values: Map<String, String>)
    }

    /** 진행은 [progress] 로(부르는 쪽이 메인으로 넘긴다). 끝나면 [finished] — 다 베꼈으면 true, 멈췄거나 실패하면 false(DB 쓰기 스레드에서). */
    fun run(toMysql: Boolean, overwrite: Boolean, progress: Progress, finished: (Boolean) -> Unit) {
        val storage = ess.config.storage
        ess.db.run("데이터 이전") {
            val source = ess.db.sql
            var created: SqlSource? = null
            try {
                val target = if (toMysql) {
                    SqlSource("migrate", storage.url, storage.user, storage.password).also { created = it }
                } else {
                    ess.db.local
                }
                runCatching { if (toMysql) Class.forName("com.mysql.cj.jdbc.Driver") else Class.forName("org.sqlite.JDBC") }
                // 받는 쪽 표 — 모든 기능의 schema(꺼진 것도). 기능을 새로 만들어 schema 만 부른다(생성자는 서비스를 만지지 않는다 — 규칙 2).
                ess.users.schema(target)
                for (entry in Features.ALL) {
                    runCatching { Features.create(entry, ess).schema(target) }
                        .onFailure { progress.say("server-migrate-schema-failed", mapOf("{값}" to entry.id, "{사유}" to (it.message ?: it.javaClass.simpleName))) }
                }
                val tables = tables(source.connection())
                val busy = tables.filter { count(target.connection(), it) > 0 }
                if (busy.isNotEmpty() && !overwrite) {
                    progress.say("server-migrate-target-not-empty", mapOf("{값}" to busy.joinToString(", ")))
                    finished(false)
                    return@run
                }
                progress.say("server-migrate-started", mapOf("{값}" to source.label, "{대상}" to target.label, "{개수}" to tables.size.toString()))
                var total = 0L
                for (table in tables) {
                    val copied = copy(source.connection(), target, table, overwrite)
                    total += copied
                    progress.say("server-migrate-table", mapOf("{값}" to table, "{개수}" to copied.toString()))
                }
                progress.say("server-migrate-done", mapOf("{개수}" to total.toString(), "{값}" to if (toMysql) "mysql" else "sqlite"))
                finished(true)
            } catch (t: Throwable) {
                ess.logger.log(java.util.logging.Level.SEVERE, "데이터 이전 실패", t)
                progress.say("server-migrate-failed", mapOf("{사유}" to (t.message ?: t.javaClass.simpleName)))
                finished(false)
            } finally {
                created?.close()
            }
        }
    }

    private fun tables(c: Connection): List<String> {
        val out = mutableListOf<String>()
        c.metaData.getTables(c.catalog, null, "%", arrayOf("TABLE")).use { rs ->
            while (rs.next()) rs.getString("TABLE_NAME")?.takeIf { it.lowercase().startsWith("ess_") }?.let { out += it }
        }
        return out.distinct().sorted()
    }

    private fun count(c: Connection, table: String): Long =
        runCatching { c.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM $table").use { if (it.next()) it.getLong(1) else 0L } } }.getOrDefault(0L)

    private fun columns(c: Connection, table: String): List<String> =
        c.createStatement().use { st -> st.executeQuery("SELECT * FROM $table WHERE 1 = 0").use { rs -> (1..rs.metaData.columnCount).map { rs.metaData.getColumnName(it) } } }

    /** 한 표를 한 트랜잭션으로. 받는 쪽에 없는 열은 뺀다(기능 판이 다를 때). */
    private fun copy(from: Connection, target: SqlSource, table: String, overwrite: Boolean): Long {
        val targetColumns = runCatching { columns(target.connection(), table).map { it.lowercase() }.toSet() }.getOrElse { return 0L }
        val sourceColumns = columns(from, table).filter { it.lowercase() in targetColumns }
        if (sourceColumns.isEmpty()) return 0L
        return target.transaction { to ->
            if (overwrite) to.createStatement().use { it.executeUpdate("DELETE FROM $table") }
            val insert = "INSERT INTO $table (${sourceColumns.joinToString(", ")}) VALUES (${sourceColumns.joinToString(", ") { "?" }})"
            var n = 0L
            to.prepareStatement(insert).use { ps ->
                from.createStatement().use { st ->
                    st.executeQuery("SELECT ${sourceColumns.joinToString(", ")} FROM $table").use { rs ->
                        while (rs.next()) {
                            for (i in sourceColumns.indices) ps.setObject(i + 1, rs.getObject(i + 1))
                            ps.addBatch()
                            n++
                            if (n % 500 == 0L) ps.executeBatch()
                        }
                    }
                }
                ps.executeBatch()
            }
            n
        }
    }

    companion object {
        /** 접속자가 있으면 안 된다(부른 사람 빼고). */
        fun othersOnline(except: java.util.UUID?): Int = Bukkit.getOnlinePlayers().count { it.uniqueId != except }

        fun dialectName(d: SqlDialect): String = if (d == SqlDialect.MYSQL) "mysql" else "sqlite"
    }
}
