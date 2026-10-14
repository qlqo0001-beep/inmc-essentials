package com.inmc.essentials.chat

import com.inmc.essentials.Ess
import com.inmc.essentials.store.EssDb
import kr.inmc.core.store.SqlDialect
import kr.inmc.core.store.SqlSource
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * chat 의 저장소 — 무시 목록(`ess_chat_ignores`)과 스파이 켜짐(`ess_chat_spies`). 둘 다 작아서 켤 때 전부 메모리로 읽고, 바뀌는 순간
 * DB 쓰기 스레드로 넘긴다. 읽기는 비동기 채팅 사건에서도 하므로 동시성 안전한 표에서.
 *
 * 무시 대상 [ALL] 은 "모두 무시"(CMI `/ignore all`).
 */
class ChatStore(private val ess: Ess) {

    enum class Spy(val column: String) { COMMAND("cmd_spy"), SIGN("sign_spy"), CHAT("chat_spy") }

    /** 주인 → 무시하는 사람(불변 집합 — 통째로 바꿔 끼운다). */
    private val ignores = ConcurrentHashMap<UUID, Set<String>>()

    /** 사람 → 켠 스파이. */
    private val spies = ConcurrentHashMap<UUID, Set<Spy>>()

    // --- 무시 ---------------------------------------------------------------------------------

    fun ignoring(owner: UUID): Set<String> = ignores[owner].orEmpty()

    /** [owner] 가 [sender] 를 무시하나(모두 무시 포함). */
    fun ignores(owner: UUID, sender: UUID): Boolean {
        val set = ignores[owner] ?: return false
        return ALL in set || sender.toString() in set
    }

    /** 넣었으면 true(이미 있으면 false). */
    fun addIgnore(owner: UUID, target: String): Boolean {
        if (!addIgnoreMemory(owner, target)) return false
        ess.db.run("무시 추가") { writeIgnore(ess.db.sql, owner, target) }
        return true
    }

    fun removeIgnore(owner: UUID, target: String): Boolean {
        if (!removeIgnoreMemory(owner, target)) return false
        ess.db.run("무시 해제") {
            ess.db.sql.connection().prepareStatement("DELETE FROM $IGNORES WHERE owner = ? AND target = ?").use {
                it.setString(1, owner.toString())
                it.setString(2, target)
                it.executeUpdate()
            }
        }
        return true
    }

    internal fun addIgnoreMemory(owner: UUID, target: String): Boolean {
        var added = false
        ignores.compute(owner) { _, old ->
            val set = old.orEmpty()
            added = target !in set
            set + target
        }
        return added
    }

    internal fun removeIgnoreMemory(owner: UUID, target: String): Boolean {
        var removed = false
        ignores.computeIfPresent(owner) { _, old ->
            removed = target in old
            (old - target).ifEmpty { null }
        }
        return removed
    }

    // --- 스파이 -------------------------------------------------------------------------------

    fun spying(id: UUID, kind: Spy): Boolean = spies[id]?.contains(kind) == true

    fun spies(id: UUID): Set<Spy> = spies[id].orEmpty()

    fun setSpy(id: UUID, kind: Spy, on: Boolean) {
        val now = setSpyMemory(id, kind, on)
        ess.db.run("스파이 저장") { writeSpies(ess.db.sql, id, now) }
    }

    internal fun setSpyMemory(id: UUID, kind: Spy, on: Boolean): Set<Spy> =
        spies.compute(id) { _, old -> (if (on) old.orEmpty() + kind else old.orEmpty() - kind).ifEmpty { null } }.orEmpty()

    // --- 켜기 · DB ----------------------------------------------------------------------------

    fun schema(source: SqlSource) {
        val sqlite = source.dialect == SqlDialect.SQLITE
        val id = if (sqlite) "TEXT" else "VARCHAR(36)"
        val big = if (sqlite) "INTEGER" else "BIGINT"
        val int = if (sqlite) "INTEGER" else "INT"
        source.connection().createStatement().use { st ->
            st.execute("CREATE TABLE IF NOT EXISTS $IGNORES (owner $id NOT NULL, target $id NOT NULL, at $big NOT NULL, PRIMARY KEY (owner, target))")
            st.execute(
                "CREATE TABLE IF NOT EXISTS $SPIES (uuid $id NOT NULL PRIMARY KEY, cmd_spy $int NOT NULL, sign_spy $int NOT NULL, chat_spy $int NOT NULL)",
            )
        }
    }

    /** 전부 읽는다(기다린다 — 켤 때만). */
    fun loadAll() {
        val (ignoreRows, spyRows) = ess.db.call {
            val source = ess.db.sql
            val readIgnores = source.connection().prepareStatement("SELECT owner, target FROM $IGNORES").use { ps ->
                ps.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
                }
            }
            val readSpies = source.connection().prepareStatement("SELECT uuid, cmd_spy, sign_spy, chat_spy FROM $SPIES").use { ps ->
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val set = HashSet<Spy>()
                            if (rs.getInt(2) != 0) set += Spy.COMMAND
                            if (rs.getInt(3) != 0) set += Spy.SIGN
                            if (rs.getInt(4) != 0) set += Spy.CHAT
                            add(rs.getString(1) to set.toSet())
                        }
                    }
                }
            }
            readIgnores to readSpies
        }
        ignores.clear()
        for ((owner, rows) in ignoreRows.groupBy { it.first }) {
            val id = runCatching { UUID.fromString(owner) }.getOrNull() ?: continue
            ignores[id] = rows.map { it.second }.toSet()
        }
        spies.clear()
        for ((raw, set) in spyRows) {
            val id = runCatching { UUID.fromString(raw) }.getOrNull() ?: continue
            if (set.isNotEmpty()) spies[id] = set
        }
        ess.logger.info("무시 목록 ${ignores.size}명 · 스파이 ${spies.size}명을 읽었습니다")
    }

    private fun writeIgnore(source: SqlSource, owner: UUID, target: String) {
        val sql = EssDb.upsertSql(source.dialect, IGNORES, keys = listOf("owner", "target"), update = emptyList(), insertOnly = listOf("at"))
        source.connection().prepareStatement(sql).use {
            it.setString(1, owner.toString())
            it.setString(2, target)
            it.setLong(3, System.currentTimeMillis())
            it.executeUpdate()
        }
    }

    private fun writeSpies(source: SqlSource, id: UUID, set: Set<Spy>) {
        if (set.isEmpty()) {
            source.connection().prepareStatement("DELETE FROM $SPIES WHERE uuid = ?").use {
                it.setString(1, id.toString())
                it.executeUpdate()
            }
            return
        }
        val sql = EssDb.upsertSql(source.dialect, SPIES, keys = listOf("uuid"), update = listOf("cmd_spy", "sign_spy", "chat_spy"))
        source.connection().prepareStatement(sql).use {
            it.setString(1, id.toString())
            it.setInt(2, if (Spy.COMMAND in set) 1 else 0)
            it.setInt(3, if (Spy.SIGN in set) 1 else 0)
            it.setInt(4, if (Spy.CHAT in set) 1 else 0)
            it.executeUpdate()
        }
    }

    companion object {
        const val IGNORES = "ess_chat_ignores"
        const val SPIES = "ess_chat_spies"

        /** "모두 무시" 의 대상 칸. */
        const val ALL = "*"
    }
}
