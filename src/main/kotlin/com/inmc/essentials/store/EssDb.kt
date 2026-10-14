package com.inmc.essentials.store

import com.inmc.essentials.config.StorageConfig
import kr.inmc.core.store.SqlDialect
import kr.inmc.core.store.SqlSource
import kr.inmc.core.store.SqlWorker
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 이 플러그인의 DB — core [SqlWorker] 하나(쓰기 스레드 하나). 표 접두는 `ess_`.
 *
 * - [sql] = 기능이 쓰는 DB. `storage.type: mysql`(주소가 있으면)이면 공용 DB, 아니면 로컬 `data/essentials.db`.
 * - `call { }` 은 기다린다 — 켤 때·비동기 사건(`AsyncPlayerPreLoginEvent` 등)에서만. **메인에서 부르지 않는다.**
 *   `run("이름") { }` 은 넘기고 돌아간다(실패는 로그).
 * - **`REPLACE INTO` 금지**(가이드 함정 11 — UNIQUE 충돌로 남의 행을 통째로 지운다). 덮어쓰기는 [upsert] 로.
 */
class EssDb(private val logger: Logger) : SqlWorker(logger, "inmc-essentials-db") {

    @Volatile
    var isOpen: Boolean = false
        private set

    /** 기능이 쓰는 DB. 쓰기 스레드 안에서만 만진다. */
    val sql: SqlSource get() = source(network = true)

    val dialect: SqlDialect get() = sql.dialect

    /**
     * 연결을 열고 [schemas](이름 → 표 만들기)를 차례로 돌린다(기다린다). 한 기능의 표 만들기가 던져도 나머지는 돈다 — 로그에 그 이름.
     * 공용 DB 를 쓰면 표는 양쪽(로컬·공용)에 다 만든다(core [SqlWorker.open] 의 동작).
     */
    fun open(file: File, storage: StorageConfig, schemas: List<Pair<String, (SqlSource) -> Unit>>) {
        file.parentFile?.mkdirs()
        if (storage.type == "mysql" && !storage.mysql) logger.warning("storage.type 이 mysql 인데 주소가 비어 있습니다 — 로컬 SQLite 로 돕니다")
        open(file, if (storage.mysql) storage.url else "", storage.user, storage.password) { source ->
            for ((name, schema) in schemas) {
                try {
                    schema(source)
                } catch (t: Throwable) {
                    logger.log(Level.SEVERE, "표 만들기 실패 ($name · ${source.label})", t)
                }
            }
        }
        isOpen = true
    }

    /** [upsertSql] 을 지금 DB 의 말씨로. */
    fun upsert(table: String, keys: List<String>, update: List<String>, insertOnly: List<String> = emptyList()): String =
        upsertSql(dialect, table, keys, update, insertOnly)

    companion object {

        /**
         * 있으면 고치고 없으면 넣는 문장. 자리(`?`)는 [keys] → [insertOnly] → [update] 순서.
         * [insertOnly] 는 처음 넣을 때만 쓰고 고칠 때는 두는 열(첫 접속 시각 같은 것).
         *
         * SQLite: `INSERT … ON CONFLICT (키) DO UPDATE SET c = excluded.c` · MySQL: `INSERT … ON DUPLICATE KEY UPDATE c = VALUES(c)`.
         * [update] 가 비면 SQLite 는 `DO NOTHING`, MySQL 은 키 열을 제자리에 쓰는 것으로(아무것도 안 바뀐다).
         */
        fun upsertSql(dialect: SqlDialect, table: String, keys: List<String>, update: List<String>, insertOnly: List<String> = emptyList()): String {
            require(keys.isNotEmpty()) { "키 열이 없습니다" }
            val columns = keys + insertOnly + update
            val head = "INSERT INTO $table (${columns.joinToString(", ")}) VALUES (${columns.joinToString(", ") { "?" }})"
            return when (dialect) {
                SqlDialect.SQLITE ->
                    if (update.isEmpty()) "$head ON CONFLICT (${keys.joinToString(", ")}) DO NOTHING"
                    else "$head ON CONFLICT (${keys.joinToString(", ")}) DO UPDATE SET " + update.joinToString(", ") { "$it = excluded.$it" }
                SqlDialect.MYSQL ->
                    "$head ON DUPLICATE KEY UPDATE " + (update.ifEmpty { listOf(keys.first()) }).joinToString(", ") { "$it = VALUES($it)" }
            }
        }
    }
}
