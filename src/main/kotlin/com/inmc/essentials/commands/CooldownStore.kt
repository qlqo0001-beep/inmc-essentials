package com.inmc.essentials.commands

import com.inmc.essentials.Ess
import kr.inmc.core.store.SqlDialect
import kr.inmc.core.store.SqlSource
import org.bukkit.Bukkit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 명령 쿨타임 기록(`ess_commands_cooldowns` — uuid · 규칙 id · 마지막으로 쓴 시각). 접속 중인 사람 것만 메모리에 들고, 쓰기는 **바뀌는 순간**
 * DB 쓰기 스레드로(평생 한 번(-1) 기록은 1회용 기록이다 — 규칙 5). 읽기는 접속 직전 비동기 사건에서 기다려서(`ess.db.call` 허용 자리)
 * **대기칸**([stageBlocking])에 두고, 접속 사건에서 옮긴다([activate]) — 같은 계정의 겹친 접속에서 옛 접속의 나감([forget])이 새 기록을 지우지 않게.
 */
class CooldownStore(private val ess: Ess) {

    private val byPlayer = ConcurrentHashMap<UUID, ConcurrentHashMap<String, Long>>()

    private class Staged(val rows: Map<String, Long>, val at: Long)

    /** 접속 직전에 읽어 둔 것 — 접속 사건이 옮긴다. 거절된 접속의 것은 [sweep] 이 버린다. */
    private val staged = ConcurrentHashMap<UUID, Staged>()

    /** 검증기용 — 켜면 [record] 가 메모리에만 적는다(DB 에 쓰지 않는다). 메인 스레드만. */
    @Volatile
    internal var memoryOnly = false

    fun schema(source: SqlSource) {
        val sqlite = source.dialect == SqlDialect.SQLITE
        source.connection().createStatement().use {
            it.execute(
                "CREATE TABLE IF NOT EXISTS $TABLE (uuid ${if (sqlite) "TEXT" else "VARCHAR(36)"} NOT NULL, " +
                    "rule ${if (sqlite) "TEXT" else "VARCHAR(64)"} NOT NULL, used_at ${if (sqlite) "INTEGER" else "BIGINT"} NOT NULL, " +
                    "PRIMARY KEY (uuid, rule))",
            )
        }
    }

    fun lastUsed(id: UUID, rule: String): Long? = byPlayer[id]?.get(rule)

    fun record(id: UUID, rule: String, at: Long) {
        byPlayer.computeIfAbsent(id) { ConcurrentHashMap() }[rule] = at
        if (memoryOnly) return
        ess.db.run("명령 쿨타임") {
            ess.db.sql.connection().prepareStatement(ess.db.upsert(TABLE, listOf("uuid", "rule"), listOf("used_at"))).use {
                it.setString(1, id.toString())
                it.setString(2, rule)
                it.setLong(3, at)
                it.executeUpdate()
            }
        }
    }

    /** 관리자가 지운다(`/일정` 이 아니라 검증기·초기화용). */
    fun clear(id: UUID, rule: String) {
        byPlayer[id]?.remove(rule)
        ess.db.run("명령 쿨타임 지우기") {
            ess.db.sql.connection().prepareStatement("DELETE FROM $TABLE WHERE uuid = ? AND rule = ?").use {
                it.setString(1, id.toString())
                it.setString(2, rule)
                it.executeUpdate()
            }
        }
    }

    /** 검증기용 — 메모리에만(DB 에 쓰지 않는다). */
    internal fun putMemory(id: UUID, rule: String, at: Long) {
        byPlayer.computeIfAbsent(id) { ConcurrentHashMap() }[rule] = at
    }

    internal fun removeMemory(id: UUID, rule: String) {
        byPlayer[id]?.remove(rule)
    }

    /** 비동기(접속 직전)에서 — 기다려 읽고 대기칸에. */
    fun stageBlocking(id: UUID) {
        val rows = ess.db.call { read(ess.db.sql, id) }
        staged[id] = Staged(rows, System.currentTimeMillis())
    }

    /** 접속 사건에서 — 대기칸을 옮긴다. 대기칸이 없으면 false(부르는 쪽이 [loadLater]). */
    fun activate(id: UUID): Boolean {
        val s = staged.remove(id) ?: return false
        val now = ConcurrentHashMap(s.rows)
        // 그새 메모리에 적힌 것(대기칸을 읽은 뒤 옛 접속이 쓴 것 등)이 있으면 늦은 쪽이 이긴다.
        byPlayer.put(id, now)?.let { old -> for ((rule, at) in old) now.merge(rule, at) { a, b -> maxOf(a, b) } }
        return true
    }

    /** 오래된 대기칸(접속이 거절된 사람) 버리기 — 1초 틱에서. */
    fun sweep(now: Long) {
        if (staged.isEmpty()) return
        staged.entries.removeIf { now - it.value.at > STAGE_MS }
    }

    /** 메인에서(리로드 뒤 이미 접속한 사람) — 넘기고 돌아온다. */
    fun loadLater(id: UUID) {
        ess.db.run("명령 쿨타임 읽기") {
            val rows = read(ess.db.sql, id)
            Bukkit.getGlobalRegionScheduler().run(ess.plugin) {
                // 그새 기록한 것이 이긴다(메모리가 이긴다 — 가이드 함정 28).
                val now = byPlayer.computeIfAbsent(id) { ConcurrentHashMap() }
                for ((rule, at) in rows) now.merge(rule, at) { a, b -> maxOf(a, b) }
            }
        }
    }

    /**
     * 나감 — 메모리에서 뺀다. 같은 사람의 새 접속이 대기칸에 있으면(겹친 접속) 옛 접속의 메모리를 거기에 합친다: 대기칸을 읽은 뒤에 이 접속이
     * 적은 기록이 DB 쓰기를 아직 기다리고 있을 수 있다.
     */
    fun forget(id: UUID) {
        val old = byPlayer.remove(id) ?: return
        staged.computeIfPresent(id) { _, s ->
            val merged = HashMap(s.rows)
            for ((rule, at) in old) merged.merge(rule, at) { a, b -> maxOf(a, b) }
            Staged(merged, s.at)
        }
    }

    private fun read(source: SqlSource, id: UUID): Map<String, Long> {
        val out = HashMap<String, Long>()
        source.connection().prepareStatement("SELECT rule, used_at FROM $TABLE WHERE uuid = ?").use { st ->
            st.setString(1, id.toString())
            st.executeQuery().use { rs -> while (rs.next()) out[rs.getString(1)] = rs.getLong(2) }
        }
        return out
    }

    companion object {
        const val TABLE = "ess_commands_cooldowns"

        /** 접속 직전에 읽은 것을 접속까지 기다리는 시간 — 넘으면 거절된 접속으로 보고 버린다. */
        const val STAGE_MS = 120_000L
    }
}
