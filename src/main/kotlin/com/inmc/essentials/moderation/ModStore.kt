package com.inmc.essentials.moderation

import com.inmc.essentials.Ess
import com.inmc.essentials.store.EssDb
import com.inmc.essentials.store.LogoutSpot
import kr.inmc.core.store.SqlDialect
import kr.inmc.core.store.SqlSource
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.UUID

/**
 * moderation 의 표(`ess_moderation_*`). 켤 때 전부 읽어 [ModState] 에 넣고, 바뀌는 순간 DB 쓰기 스레드로 넘긴다
 * (뮤트·감옥·경고·메모는 1회용 기록이라 묶지 않는다 — CLAUDE.md 5). 감옥 남은 시간만 30초마다 바뀐 사람을 쓴다(그것은 시계다).
 *
 * 사람을 가리키는 열은 모두 `uuid` 다 — 데이터 도구(교환·삭제·정리)가 이 이름으로 찾는다. 그래서 "준 사람" 은 `uuid` 로 끝나지 않는 이름
 * (`source`·`author`)에 **이름**으로 둔다.
 */
class ModStore(private val ess: Ess) {

    // --- 표 -------------------------------------------------------------------------------------

    fun schema(sql: SqlSource) {
        val sqlite = sql.dialect == SqlDialect.SQLITE
        val id = if (sqlite) "TEXT" else "VARCHAR(36)"
        val key = if (sqlite) "TEXT" else "VARCHAR(64)"
        val text = "TEXT"
        val big = if (sqlite) "INTEGER" else "BIGINT"
        val int = "INTEGER"
        val real = if (sqlite) "REAL" else "DOUBLE"
        sql.connection().createStatement().use { st ->
            st.execute("CREATE TABLE IF NOT EXISTS $MUTES (uuid $id NOT NULL PRIMARY KEY, until_at $big, reason $text, source $text, shadow $int NOT NULL, created $big NOT NULL)")
            st.execute(
                "CREATE TABLE IF NOT EXISTS $JAILED (uuid $id NOT NULL PRIMARY KEY, jail $key NOT NULL, cell $int, remaining $big NOT NULL, updated $big NOT NULL, " +
                    "reason $text, source $text, created $big NOT NULL, prior_world $key, prior_x $real, prior_y $real, prior_z $real, prior_yaw $real, prior_pitch $real)",
            )
            st.execute("CREATE TABLE IF NOT EXISTS $CUFFS (uuid $id NOT NULL PRIMARY KEY, source $text, created $big NOT NULL)")
            st.execute(
                "CREATE TABLE IF NOT EXISTS $WARNINGS (serial $big NOT NULL PRIMARY KEY, uuid $id NOT NULL, category $key NOT NULL, points $int NOT NULL, " +
                    "reason $text, source $text, created $big NOT NULL, expires $big)",
            )
            st.execute("CREATE TABLE IF NOT EXISTS $NOTES (serial $big NOT NULL PRIMARY KEY, uuid $id NOT NULL, note $text NOT NULL, author $text, created $big NOT NULL)")
            st.execute("CREATE TABLE IF NOT EXISTS $ALERTS (uuid $id NOT NULL PRIMARY KEY, reason $text, author $text, until_at $big NOT NULL)")
            st.execute("CREATE TABLE IF NOT EXISTS $META (uuid $id NOT NULL, meta_key $key NOT NULL, meta_value $text, PRIMARY KEY (uuid, meta_key))")
            st.execute("CREATE TABLE IF NOT EXISTS $LOCKIP (uuid $id NOT NULL, ip $key NOT NULL, PRIMARY KEY (uuid, ip))")
            st.execute(
                "CREATE TABLE IF NOT EXISTS $IPS (uuid $id NOT NULL, ip $key NOT NULL, first_seen $big NOT NULL, last_seen $big NOT NULL, times $int NOT NULL, " +
                    "PRIMARY KEY (uuid, ip))",
            )
            if (sqlite) {
                st.execute("CREATE INDEX IF NOT EXISTS ess_moderation_ips_ip ON $IPS (ip)")
                st.execute("CREATE INDEX IF NOT EXISTS ess_moderation_warnings_uuid ON $WARNINGS (uuid)")
                st.execute("CREATE INDEX IF NOT EXISTS ess_moderation_notes_uuid ON $NOTES (uuid)")
            }
            st.execute(
                "CREATE TABLE IF NOT EXISTS $PENDING (serial $big NOT NULL PRIMARY KEY, kind $key NOT NULL, payload $text NOT NULL, created $big NOT NULL, by_name $text)",
            )
            st.execute(
                "CREATE TABLE IF NOT EXISTS $RELEASE (uuid $id NOT NULL PRIMARY KEY, world $key NOT NULL, x $real NOT NULL, y $real NOT NULL, z $real NOT NULL, " +
                    "yaw $real NOT NULL, pitch $real NOT NULL)",
            )
        }
    }

    // --- 읽기(켤 때 — 기다린다) --------------------------------------------------------------------

    fun loadAll(state: ModState) {
        ess.db.call {
            val sql = ess.db.sql
            val c = sql.connection()
            var maxNo = 0L
            c.createStatement().use { st ->
                st.executeQuery("SELECT uuid, until_at, reason, source, shadow, created FROM $MUTES").use { rs ->
                    while (rs.next()) {
                        val id = uuid(rs, 1) ?: continue
                        state.mutes[id] = Mute(id, rs.longOrNull(2), rs.getString(3).orEmpty(), rs.getString(4).orEmpty(), rs.getInt(5) != 0, rs.getLong(6))
                    }
                }
                st.executeQuery(
                    "SELECT uuid, jail, cell, remaining, updated, reason, source, created, prior_world, prior_x, prior_y, prior_z, prior_yaw, prior_pitch FROM $JAILED",
                ).use { rs ->
                    while (rs.next()) {
                        val id = uuid(rs, 1) ?: continue
                        val world = rs.getString(9)
                        val prior = world?.let { LogoutSpot(it, rs.getDouble(10), rs.getDouble(11), rs.getDouble(12), rs.getFloat(13), rs.getFloat(14)) }
                        state.jailed[id] = Jailed(id, rs.getString(2), rs.intOrNull(3), rs.getLong(4), rs.getLong(5), rs.getString(6).orEmpty(),
                            rs.getString(7).orEmpty(), rs.getLong(8), prior)
                    }
                }
                st.executeQuery("SELECT uuid, source, created FROM $CUFFS").use { rs ->
                    while (rs.next()) {
                        val id = uuid(rs, 1) ?: continue
                        state.cuffs[id] = Cuff(id, rs.getString(2).orEmpty(), rs.getLong(3))
                    }
                }
                val warnings = HashMap<UUID, MutableList<Warning>>()
                st.executeQuery("SELECT serial, uuid, category, points, reason, source, created, expires FROM $WARNINGS ORDER BY created").use { rs ->
                    while (rs.next()) {
                        val id = uuid(rs, 2) ?: continue
                        val no = rs.getLong(1)
                        maxNo = maxOf(maxNo, no)
                        warnings.getOrPut(id) { ArrayList() } += Warning(no, id, rs.getString(3), rs.getInt(4), rs.getString(5).orEmpty(),
                            rs.getString(6).orEmpty(), rs.getLong(7), rs.longOrNull(8))
                    }
                }
                state.warnings.putAll(warnings)
                val notes = HashMap<UUID, MutableList<Note>>()
                st.executeQuery("SELECT serial, uuid, note, author, created FROM $NOTES ORDER BY created").use { rs ->
                    while (rs.next()) {
                        val id = uuid(rs, 2) ?: continue
                        val no = rs.getLong(1)
                        maxNo = maxOf(maxNo, no)
                        notes.getOrPut(id) { ArrayList() } += Note(no, id, rs.getString(3), rs.getString(4).orEmpty(), rs.getLong(5))
                    }
                }
                state.notes.putAll(notes)
                st.executeQuery("SELECT uuid, reason, author, until_at FROM $ALERTS").use { rs ->
                    while (rs.next()) {
                        val id = uuid(rs, 1) ?: continue
                        state.alerts[id] = Alert(id, rs.getString(2), rs.getString(3).orEmpty(), rs.getLong(4))
                    }
                }
                val meta = HashMap<UUID, LinkedHashMap<String, String>>()
                st.executeQuery("SELECT uuid, meta_key, meta_value FROM $META").use { rs ->
                    while (rs.next()) {
                        val id = uuid(rs, 1) ?: continue
                        meta.getOrPut(id) { LinkedHashMap() }[rs.getString(2)] = rs.getString(3).orEmpty()
                    }
                }
                state.meta.putAll(meta)
                val locks = HashMap<UUID, MutableSet<String>>()
                st.executeQuery("SELECT uuid, ip FROM $LOCKIP").use { rs ->
                    while (rs.next()) {
                        val id = uuid(rs, 1) ?: continue
                        locks.getOrPut(id) { LinkedHashSet() } += rs.getString(2)
                    }
                }
                state.lockIps.putAll(locks)
                val ips = HashMap<UUID, MutableList<IpSeen>>()
                st.executeQuery("SELECT uuid, ip, first_seen, last_seen, times FROM $IPS").use { rs ->
                    while (rs.next()) {
                        val id = uuid(rs, 1) ?: continue
                        ips.getOrPut(id) { ArrayList() } += IpSeen(id, rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getInt(5))
                    }
                }
                state.ips.putAll(ips)
                st.executeQuery("SELECT uuid, world, x, y, z, yaw, pitch FROM $RELEASE").use { rs ->
                    while (rs.next()) {
                        val id = uuid(rs, 1) ?: continue
                        state.pendingRelease[id] = LogoutSpot(rs.getString(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), rs.getFloat(6), rs.getFloat(7))
                    }
                }
            }
            if (maxNo >= state.serial.get()) state.serial.set(maxNo + 1)
        }
    }

    // --- 쓰기 -----------------------------------------------------------------------------------

    fun putMute(m: Mute) = write("뮤트") { sql ->
        sql.prepare(EssDb.upsertSql(sql.dialect, MUTES, listOf("uuid"), listOf("until_at", "reason", "source", "shadow", "created"))) { ps ->
            ps.setString(1, m.id.toString())
            ps.setLongOrNull(2, m.until)
            ps.setString(3, m.reason)
            ps.setString(4, m.source)
            ps.setInt(5, if (m.shadow) 1 else 0)
            ps.setLong(6, m.created)
        }
    }

    fun deleteMute(id: UUID) = delete("뮤트 해제", MUTES, id)

    fun putJailed(j: Jailed) = write("감옥") { sql ->
        sql.prepare(
            EssDb.upsertSql(sql.dialect, JAILED, listOf("uuid"),
                listOf("jail", "cell", "remaining", "updated", "reason", "source", "created", "prior_world", "prior_x", "prior_y", "prior_z", "prior_yaw", "prior_pitch")),
        ) { ps ->
            ps.setString(1, j.id.toString())
            ps.setString(2, j.jail)
            if (j.cell != null) ps.setInt(3, j.cell!!) else ps.setNull(3, Types.INTEGER)
            ps.setLong(4, j.remainingMs)
            ps.setLong(5, j.updated)
            ps.setString(6, j.reason)
            ps.setString(7, j.source)
            ps.setLong(8, j.created)
            val p = j.prior
            ps.setString(9, p?.world)
            if (p != null) {
                ps.setDouble(10, p.x); ps.setDouble(11, p.y); ps.setDouble(12, p.z); ps.setFloat(13, p.yaw); ps.setFloat(14, p.pitch)
            } else for (i in 10..14) ps.setNull(i, Types.DOUBLE)
        }
    }

    /** 남은 시간만(30초 묶음·나갈 때). */
    fun putJailTime(id: UUID, remaining: Long, updated: Long) = write("감옥 시간") { sql ->
        sql.prepare("UPDATE $JAILED SET remaining = ?, updated = ? WHERE uuid = ?") { ps ->
            ps.setLong(1, remaining); ps.setLong(2, updated); ps.setString(3, id.toString())
        }
    }

    fun deleteJailed(id: UUID) = delete("석방", JAILED, id)

    /** 나가 있는 동안 석방 — 다음 접속 때 보낼 자리. */
    fun putRelease(id: UUID, spot: LogoutSpot) = write("석방 예약") { sql ->
        sql.prepare(EssDb.upsertSql(sql.dialect, RELEASE, listOf("uuid"), listOf("world", "x", "y", "z", "yaw", "pitch"))) { ps ->
            ps.setString(1, id.toString()); ps.setString(2, spot.world)
            ps.setDouble(3, spot.x); ps.setDouble(4, spot.y); ps.setDouble(5, spot.z); ps.setFloat(6, spot.yaw); ps.setFloat(7, spot.pitch)
        }
    }

    fun deleteRelease(id: UUID) = delete("석방 예약 지우기", RELEASE, id)

    fun putCuff(c: Cuff) = write("수갑") { sql ->
        sql.prepare(EssDb.upsertSql(sql.dialect, CUFFS, listOf("uuid"), listOf("source", "created"))) { ps ->
            ps.setString(1, c.id.toString()); ps.setString(2, c.source); ps.setLong(3, c.created)
        }
    }

    fun deleteCuff(id: UUID) = delete("수갑 풀기", CUFFS, id)

    fun insertWarning(w: Warning) = write("경고") { sql ->
        sql.prepare(EssDb.upsertSql(sql.dialect, WARNINGS, listOf("serial"), emptyList(), listOf("uuid", "category", "points", "reason", "source", "created", "expires"))) { ps ->
            ps.setLong(1, w.no); ps.setString(2, w.id.toString()); ps.setString(3, w.category); ps.setInt(4, w.points)
            ps.setString(5, w.reason); ps.setString(6, w.source); ps.setLong(7, w.created); ps.setLongOrNull(8, w.expires)
        }
    }

    fun deleteWarning(no: Long) = write("경고 지우기") { sql ->
        sql.prepare("DELETE FROM $WARNINGS WHERE serial = ?") { it.setLong(1, no) }
    }

    fun deleteWarningsOf(id: UUID) = delete("경고 비우기", WARNINGS, id)

    fun deleteAllWarnings() = write("경고 전부 비우기") { sql -> sql.prepare("DELETE FROM $WARNINGS") {} }

    fun deleteExpiredWarnings(now: Long) = write("만료 경고") { sql ->
        sql.prepare("DELETE FROM $WARNINGS WHERE expires IS NOT NULL AND expires <= ?") { it.setLong(1, now) }
    }

    fun insertNote(n: Note) = write("메모") { sql ->
        sql.prepare(EssDb.upsertSql(sql.dialect, NOTES, listOf("serial"), emptyList(), listOf("uuid", "note", "author", "created"))) { ps ->
            ps.setLong(1, n.no); ps.setString(2, n.id.toString()); ps.setString(3, n.text); ps.setString(4, n.author); ps.setLong(5, n.created)
        }
    }

    fun deleteNote(no: Long) = write("메모 지우기") { sql -> sql.prepare("DELETE FROM $NOTES WHERE serial = ?") { it.setLong(1, no) } }

    fun deleteNotesOf(id: UUID) = delete("메모 비우기", NOTES, id)

    fun deleteExpiredNotes(before: Long) = write("만료 메모") { sql ->
        sql.prepare("DELETE FROM $NOTES WHERE created <= ?") { it.setLong(1, before) }
    }

    fun putAlert(a: Alert) = write("경보") { sql ->
        sql.prepare(EssDb.upsertSql(sql.dialect, ALERTS, listOf("uuid"), listOf("reason", "author", "until_at"))) { ps ->
            ps.setString(1, a.id.toString()); ps.setString(2, a.reason); ps.setString(3, a.author); ps.setLong(4, a.until)
        }
    }

    fun deleteAlert(id: UUID) = delete("경보 지우기", ALERTS, id)

    fun putMeta(id: UUID, key: String, value: String) = write("유저메타") { sql ->
        sql.prepare(EssDb.upsertSql(sql.dialect, META, listOf("uuid", "meta_key"), listOf("meta_value"))) { ps ->
            ps.setString(1, id.toString()); ps.setString(2, key); ps.setString(3, value)
        }
    }

    fun deleteMeta(id: UUID, key: String?) = write("유저메타 지우기") { sql ->
        if (key == null) sql.prepare("DELETE FROM $META WHERE uuid = ?") { it.setString(1, id.toString()) }
        else sql.prepare("DELETE FROM $META WHERE uuid = ? AND meta_key = ?") { it.setString(1, id.toString()); it.setString(2, key) }
    }

    fun putLockIp(id: UUID, ip: String) = write("아이피잠금") { sql ->
        sql.prepare(EssDb.upsertSql(sql.dialect, LOCKIP, listOf("uuid", "ip"), emptyList())) { ps -> ps.setString(1, id.toString()); ps.setString(2, ip) }
    }

    fun deleteLockIp(id: UUID, ip: String?) = write("아이피잠금 지우기") { sql ->
        if (ip == null) sql.prepare("DELETE FROM $LOCKIP WHERE uuid = ?") { it.setString(1, id.toString()) }
        else sql.prepare("DELETE FROM $LOCKIP WHERE uuid = ? AND ip = ?") { it.setString(1, id.toString()); it.setString(2, ip) }
    }

    fun putIp(seen: IpSeen) = write("IP 기록") { sql ->
        sql.prepare(EssDb.upsertSql(sql.dialect, IPS, listOf("uuid", "ip"), listOf("last_seen", "times"), listOf("first_seen"))) { ps ->
            ps.setString(1, seen.id.toString()); ps.setString(2, seen.ip); ps.setLong(3, seen.firstSeen); ps.setLong(4, seen.lastSeen); ps.setInt(5, seen.times)
        }
    }

    /** 데이터 도구 예약(다음 시작 때 [DataTools.applyPending] 이 돌린다). */
    fun insertPending(no: Long, kind: String, payload: String, by: String) = write("데이터 도구 예약") { sql ->
        sql.prepare("INSERT INTO $PENDING (serial, kind, payload, created, by_name) VALUES (?, ?, ?, ?, ?)") { ps ->
            ps.setLong(1, no); ps.setString(2, kind); ps.setString(3, payload); ps.setLong(4, System.currentTimeMillis()); ps.setString(5, by)
        }
    }

    fun deletePending(no: Long) = write("데이터 도구 예약 취소") { sql -> sql.prepare("DELETE FROM $PENDING WHERE serial = ?") { it.setLong(1, no) } }

    // --- 도우미 ---------------------------------------------------------------------------------

    private fun write(what: String, block: (SqlSource) -> Unit) = ess.db.run(what) { block(ess.db.sql) }

    private fun delete(what: String, table: String, id: UUID) = write(what) { sql ->
        sql.prepare("DELETE FROM $table WHERE uuid = ?") { it.setString(1, id.toString()) }
    }

    companion object {
        const val MUTES = "ess_moderation_mutes"
        const val JAILED = "ess_moderation_jailed"
        const val CUFFS = "ess_moderation_cuffs"
        const val WARNINGS = "ess_moderation_warnings"
        const val NOTES = "ess_moderation_notes"
        const val ALERTS = "ess_moderation_alerts"
        const val META = "ess_moderation_usermeta"
        const val LOCKIP = "ess_moderation_lockip"
        const val IPS = "ess_moderation_ips"
        const val PENDING = "ess_moderation_pending"
        const val RELEASE = "ess_moderation_release"

        fun SqlSource.prepare(sql: String, bind: (PreparedStatement) -> Unit) {
            connection().prepareStatement(sql).use { ps ->
                bind(ps)
                ps.executeUpdate()
            }
        }

        private fun PreparedStatement.setLongOrNull(i: Int, v: Long?) = if (v != null) setLong(i, v) else setNull(i, Types.BIGINT)

        private fun ResultSet.longOrNull(i: Int): Long? = getLong(i).takeIf { !wasNull() }

        private fun ResultSet.intOrNull(i: Int): Int? = getInt(i).takeIf { !wasNull() }

        private fun uuid(rs: ResultSet, i: Int): UUID? = runCatching { UUID.fromString(rs.getString(i)) }.getOrNull()
    }
}
