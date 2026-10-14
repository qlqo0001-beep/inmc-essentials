package com.inmc.essentials.admininfo

import com.inmc.essentials.store.EssDb
import kr.inmc.core.store.SqlDialect
import kr.inmc.core.store.SqlSource
import java.util.UUID

/**
 * admininfo 의 표 아홉. **전부 DB 쓰기 스레드에서만** 부른다(`ess.db.run` · 켤 때·접속 직전의 `ess.db.call` 안).
 *
 * - `ess_admininfo_vanish` 사람마다 투명 상태와 바꾼 옵션 + 못 보는 사람에게 보일 "마지막으로 본 때"(`seen`·`seen_join` — 투명을 켠 때).
 * - `ess_admininfo_playtime` 마지막으로 본 플레이 시간(틱) — 접속하지 않은 사람의 조회·순위용 사본. 원본은 바닐라 통계라 **서버마다 다르다** —
 *   공용 DB 를 써도 이 표만은 **로컬 DB** 에 쓴다(부르는 쪽이 `ess.db.source(network = false)` 를 넘긴다).
 * - `ess_admininfo_playtime_day` 하루별 플레이 시간(틱) — `/상세플레이시간`. 더하기로만 쓴다(여러 서버가 같은 DB 를 써도 안 겹친다).
 * - `ess_admininfo_playtime_edit` 접속하지 않은 사람에게 걸어 둔 편집 — 다음 접속 때 적용하고(`base` = 적용 전 통계를 적어 둔다) **나갈 때** 지운다
 *   (바닐라 통계 파일은 자동 저장·퇴장 때만 쓰인다 — 그 사이 꺼져도 편집을 잃지 않게).
 * - `ess_admininfo_rewards` 보상 정의(플레이 시간 보상 · 투표 보상) — YAML 글.
 * - `ess_admininfo_preward` 사람마다 플레이 시간 보상 상태 — 진행·쌓인 수는 **더하기**로, 받기는 **조건부 빼기**(`pending >= n`)·
 *   **조건부 표시**(`claimed = 0`)로 주기 **전에** 쓴다(공용 DB 의 두 서버가 같은 것을 두 번 주지 않게).
 * - `ess_admininfo_votes` 사람마다 투표 수 · 마지막 투표. 투표는 더하기로 쓴다.
 * - `ess_admininfo_vote_log` 투표 기록(아이피 하루 한도·같은 사이트 대기·최근 기록) — `votes.log-days` 지나면 지운다.
 * - `ess_admininfo_vote_milestone` 누적 보상을 받은 단계(같은 단계를 두 번 받지 않게 — 1회용 기록).
 */
internal object AdminStore {

    const val VANISH = "ess_admininfo_vanish"
    const val PLAYTIME = "ess_admininfo_playtime"
    const val DAYS = "ess_admininfo_playtime_day"
    const val EDITS = "ess_admininfo_playtime_edit"
    const val REWARDS = "ess_admininfo_rewards"
    const val PREWARD = "ess_admininfo_preward"
    const val VOTES = "ess_admininfo_votes"
    const val VOTE_LOG = "ess_admininfo_vote_log"
    const val MILESTONES = "ess_admininfo_vote_milestone"

    fun schema(source: SqlSource) {
        val sqlite = source.dialect == SqlDialect.SQLITE
        val id = if (sqlite) "TEXT" else "VARCHAR(36)"
        val name = if (sqlite) "TEXT" else "VARCHAR(64)"
        val big = if (sqlite) "INTEGER" else "BIGINT"
        val int = if (sqlite) "INTEGER" else "INT"
        val text = if (sqlite) "TEXT" else "MEDIUMTEXT"
        source.connection().createStatement().use { st ->
            st.execute("CREATE TABLE IF NOT EXISTS $VANISH (uuid $id PRIMARY KEY, vanished $int NOT NULL, options $text, updated $big NOT NULL)")
            st.execute("CREATE TABLE IF NOT EXISTS $PLAYTIME (uuid $id PRIMARY KEY, name $name NOT NULL, ticks $big NOT NULL, updated $big NOT NULL)")
            st.execute("CREATE TABLE IF NOT EXISTS $DAYS (uuid $id NOT NULL, day $int NOT NULL, ticks $big NOT NULL, PRIMARY KEY (uuid, day))")
            st.execute("CREATE TABLE IF NOT EXISTS $EDITS (uuid $id PRIMARY KEY, is_set $int NOT NULL, ticks $big NOT NULL, at $big NOT NULL)")
            st.execute("CREATE TABLE IF NOT EXISTS $REWARDS (kind $name NOT NULL, id $name NOT NULL, data $text NOT NULL, PRIMARY KEY (kind, id))")
            st.execute(
                "CREATE TABLE IF NOT EXISTS $PREWARD (uuid $id NOT NULL, reward $name NOT NULL, progress $big NOT NULL, pending $int NOT NULL, " +
                    "claimed $int NOT NULL, updated $big NOT NULL, PRIMARY KEY (uuid, reward))",
            )
            st.execute("CREATE TABLE IF NOT EXISTS $VOTES (uuid $id PRIMARY KEY, name $name NOT NULL, votes $int NOT NULL, last_vote $big)")
            st.execute(
                "CREATE TABLE IF NOT EXISTS $VOTE_LOG (" +
                    (if (sqlite) "id INTEGER PRIMARY KEY AUTOINCREMENT" else "id BIGINT AUTO_INCREMENT PRIMARY KEY") +
                    ", at $big NOT NULL, uuid $id, name $name NOT NULL, service ${if (sqlite) "TEXT" else "VARCHAR(128)"}, address $name" +
                    (if (sqlite) ")" else ", INDEX ess_admininfo_vote_log_at (at))"),
            )
            if (sqlite) st.execute("CREATE INDEX IF NOT EXISTS ess_admininfo_vote_log_at ON $VOTE_LOG (at)")
            st.execute("CREATE TABLE IF NOT EXISTS $MILESTONES (uuid $id NOT NULL, milestone $int NOT NULL, at $big NOT NULL, PRIMARY KEY (uuid, milestone))")
        }
        // 나중에 더한 열(먼저 만든 표에도) — 없을 때만.
        addColumn(source, VANISH, "seen", big)
        addColumn(source, VANISH, "seen_join", big)
        addColumn(source, EDITS, "base", big)
    }

    private fun addColumn(source: SqlSource, table: String, column: String, type: String) {
        val has = source.connection().createStatement().use { st ->
            st.executeQuery("SELECT * FROM $table WHERE 1 = 0").use { rs -> (1..rs.metaData.columnCount).any { rs.metaData.getColumnName(it).equals(column, true) } }
        }
        if (!has) source.connection().createStatement().use { it.execute("ALTER TABLE $table ADD COLUMN $column $type") }
    }

    // --- 투명 -------------------------------------------------------------------------------------

    /** [seen]·[seenJoin] = 못 보는 사람에게 보일 마지막 나감·마지막 접속(투명이 아니면 null). */
    class VanishRow(val id: UUID, val vanished: Boolean, val options: Map<VanishOption, Boolean>, val seen: Long? = null, val seenJoin: Long? = null)

    fun readVanish(sql: SqlSource, only: UUID? = null): List<VanishRow> =
        sql.connection().prepareStatement("SELECT uuid, vanished, options, seen, seen_join FROM $VANISH" + if (only != null) " WHERE uuid = ?" else "").use { ps ->
            if (only != null) ps.setString(1, only.toString())
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val id = runCatching { UUID.fromString(rs.getString(1)) }.getOrNull() ?: continue
                        val vanished = rs.getInt(2) != 0
                        val options = VanishOption.decode(rs.getString(3))
                        val seen = rs.getLong(4).takeIf { !rs.wasNull() }
                        val seenJoin = rs.getLong(5).takeIf { !rs.wasNull() }
                        add(VanishRow(id, vanished, options, seen, seenJoin))
                    }
                }
            }
        }

    fun writeVanish(sql: SqlSource, row: VanishRow) {
        sql.connection().prepareStatement(EssDb.upsertSql(sql.dialect, VANISH, listOf("uuid"), listOf("vanished", "options", "seen", "seen_join", "updated"))).use { ps ->
            ps.setString(1, row.id.toString())
            ps.setInt(2, if (row.vanished) 1 else 0)
            ps.setString(3, VanishOption.encode(row.options))
            if (row.seen != null) ps.setLong(4, row.seen) else ps.setNull(4, java.sql.Types.BIGINT)
            if (row.seenJoin != null) ps.setLong(5, row.seenJoin) else ps.setNull(5, java.sql.Types.BIGINT)
            ps.setLong(6, System.currentTimeMillis())
            ps.executeUpdate()
        }
    }

    // --- 플레이 시간 -------------------------------------------------------------------------------

    class PlaytimeRow(val id: UUID, val name: String, val ticks: Long, val updated: Long)

    fun readPlaytime(sql: SqlSource): List<PlaytimeRow> =
        sql.connection().prepareStatement("SELECT uuid, name, ticks, updated FROM $PLAYTIME").use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val id = runCatching { UUID.fromString(rs.getString(1)) }.getOrNull() ?: continue
                        add(PlaytimeRow(id, rs.getString(2), rs.getLong(3), rs.getLong(4)))
                    }
                }
            }
        }

    fun writePlaytime(sql: SqlSource, rows: Collection<PlaytimeRow>) {
        if (rows.isEmpty()) return
        sql.connection().prepareStatement(EssDb.upsertSql(sql.dialect, PLAYTIME, listOf("uuid"), listOf("name", "ticks", "updated"))).use { ps ->
            for (r in rows) {
                ps.setString(1, r.id.toString())
                ps.setString(2, r.name)
                ps.setLong(3, r.ticks)
                ps.setLong(4, r.updated)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    /** 하루별 틱을 **더한다**(덮어쓰지 않는다 — 다른 서버가 같은 날에 더한 것과 섞이지 않게). */
    fun addDays(sql: SqlSource, deltas: Map<UUID, Map<Long, Long>>) {
        if (deltas.isEmpty()) return
        val head = "INSERT INTO $DAYS (uuid, day, ticks) VALUES (?, ?, ?)"
        val q = if (sql.dialect == SqlDialect.SQLITE) "$head ON CONFLICT (uuid, day) DO UPDATE SET ticks = ticks + excluded.ticks"
        else "$head ON DUPLICATE KEY UPDATE ticks = ticks + VALUES(ticks)"
        sql.connection().prepareStatement(q).use { ps ->
            for ((id, days) in deltas) for ((day, ticks) in days) {
                if (ticks == 0L) continue
                ps.setString(1, id.toString())
                ps.setLong(2, day)
                ps.setLong(3, ticks)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    fun readDays(sql: SqlSource, id: UUID): Map<Long, Long> =
        sql.connection().prepareStatement("SELECT day, ticks FROM $DAYS WHERE uuid = ?").use { ps ->
            ps.setString(1, id.toString())
            ps.executeQuery().use { rs -> buildMap { while (rs.next()) put(rs.getLong(1), rs.getLong(2)) } }
        }

    fun pruneDays(sql: SqlSource, beforeDay: Long) {
        sql.connection().prepareStatement("DELETE FROM $DAYS WHERE day < ?").use { it.setLong(1, beforeDay); it.executeUpdate() }
    }

    /** 걸어 둔 편집. [base] 가 있으면 이미 적용했다(적용 전 통계 = base, 적용 뒤 = [edit] 의 설정값) — 나갈 때 지워질 줄. */
    class EditRow(val edit: PlaytimeMath.PendingEdit, val base: Long?)

    fun readEdits(sql: SqlSource, only: UUID? = null): Map<UUID, EditRow> =
        sql.connection().prepareStatement("SELECT uuid, is_set, ticks, base FROM $EDITS" + if (only != null) " WHERE uuid = ?" else "").use { ps ->
            if (only != null) ps.setString(1, only.toString())
            ps.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        val id = runCatching { UUID.fromString(rs.getString(1)) }.getOrNull() ?: continue
                        val edit = PlaytimeMath.PendingEdit(rs.getInt(2) != 0, rs.getLong(3))
                        val base = rs.getLong(4).takeIf { !rs.wasNull() }
                        put(id, EditRow(edit, base))
                    }
                }
            }
        }

    fun writeEdit(sql: SqlSource, id: UUID, edit: PlaytimeMath.PendingEdit?, base: Long? = null) {
        if (edit == null) {
            sql.connection().prepareStatement("DELETE FROM $EDITS WHERE uuid = ?").use { it.setString(1, id.toString()); it.executeUpdate() }
            return
        }
        sql.connection().prepareStatement(EssDb.upsertSql(sql.dialect, EDITS, listOf("uuid"), listOf("is_set", "ticks", "base", "at"))).use { ps ->
            ps.setString(1, id.toString())
            ps.setInt(2, if (edit.set) 1 else 0)
            ps.setLong(3, edit.ticks)
            if (base != null) ps.setLong(4, base) else ps.setNull(4, java.sql.Types.BIGINT)
            ps.setLong(5, System.currentTimeMillis())
            ps.executeUpdate()
        }
    }

    // --- 보상 정의 ---------------------------------------------------------------------------------

    fun readRewards(sql: SqlSource, kind: String): Map<String, String> =
        sql.connection().prepareStatement("SELECT id, data FROM $REWARDS WHERE kind = ?").use { ps ->
            ps.setString(1, kind)
            ps.executeQuery().use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) } }
        }

    fun writeReward(sql: SqlSource, kind: String, id: String, data: String?) {
        if (data == null) {
            sql.connection().prepareStatement("DELETE FROM $REWARDS WHERE kind = ? AND id = ?").use {
                it.setString(1, kind); it.setString(2, id); it.executeUpdate()
            }
            return
        }
        sql.connection().prepareStatement(EssDb.upsertSql(sql.dialect, REWARDS, listOf("kind", "id"), listOf("data"))).use { ps ->
            ps.setString(1, kind)
            ps.setString(2, id)
            ps.setString(3, data)
            ps.executeUpdate()
        }
    }

    // --- 플레이 시간 보상 상태 --------------------------------------------------------------------------

    fun readStates(sql: SqlSource, only: UUID? = null): Map<UUID, Map<String, RewardState>> {
        val out = HashMap<UUID, HashMap<String, RewardState>>()
        sql.connection().prepareStatement("SELECT uuid, reward, progress, pending, claimed FROM $PREWARD" + if (only != null) " WHERE uuid = ?" else "").use { ps ->
            if (only != null) ps.setString(1, only.toString())
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val id = runCatching { UUID.fromString(rs.getString(1)) }.getOrNull() ?: continue
                    out.getOrPut(id) { HashMap() }[rs.getString(2)] = RewardState(rs.getLong(3), rs.getInt(4), rs.getInt(5))
                }
            }
        }
        return out
    }

    class ProgressDelta(val id: UUID, val reward: String, val progress: Long, val pending: Int)

    /** 반복 보상 진행 — 진행·쌓인 수를 **더한다**(공용 DB 의 다른 서버가 더한 것과 섞여도 잃지 않게). */
    fun addProgress(sql: SqlSource, rows: List<ProgressDelta>) {
        val real = rows.filter { it.progress != 0L || it.pending != 0 }
        if (real.isEmpty()) return
        val head = "INSERT INTO $PREWARD (uuid, reward, progress, pending, claimed, updated) VALUES (?, ?, ?, ?, 0, ?)"
        val q = if (sql.dialect == SqlDialect.SQLITE) {
            "$head ON CONFLICT (uuid, reward) DO UPDATE SET progress = progress + excluded.progress, pending = pending + excluded.pending, updated = excluded.updated"
        } else {
            "$head ON DUPLICATE KEY UPDATE progress = progress + VALUES(progress), pending = pending + VALUES(pending), updated = VALUES(updated)"
        }
        sql.connection().prepareStatement(q).use { ps ->
            val now = System.currentTimeMillis()
            for (r in real) {
                ps.setString(1, r.id.toString())
                ps.setString(2, r.reward)
                ps.setLong(3, r.progress)
                ps.setInt(4, r.pending)
                ps.setLong(5, now)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    /**
     * 반복 보상 받기 — DB 의 쌓인 수가 [n] 이상일 때만 [n] 을 뺀다([all] 이면 0 으로). 뺐으면 true(이 서버가 준다), 아니면 false
     * (다른 서버가 먼저 받았다 — 주지 않는다).
     */
    fun claimRepeat(sql: SqlSource, id: UUID, reward: String, n: Int, all: Boolean): Boolean =
        sql.connection().prepareStatement(
            "UPDATE $PREWARD SET pending = ${if (all) "0" else "pending - ?"}, updated = ? WHERE uuid = ? AND reward = ? AND pending >= ?",
        ).use { ps ->
            var i = 1
            if (!all) ps.setInt(i++, n)
            ps.setLong(i++, System.currentTimeMillis())
            ps.setString(i++, id.toString())
            ps.setString(i++, reward)
            ps.setInt(i, n)
            ps.executeUpdate() == 1
        }

    /** 한 번 보상 받기 — 줄이 없으면 만들고, `claimed = 0` 인 줄만 1 로. 바꿨으면 true(이 서버가 준다). */
    fun claimOnce(sql: SqlSource, id: UUID, reward: String): Boolean {
        sql.connection().prepareStatement(EssDb.upsertSql(sql.dialect, PREWARD, listOf("uuid", "reward"), emptyList(), listOf("progress", "pending", "claimed", "updated"))).use { ps ->
            ps.setString(1, id.toString())
            ps.setString(2, reward)
            ps.setLong(3, 0)
            ps.setInt(4, 0)
            ps.setInt(5, 0)
            ps.setLong(6, System.currentTimeMillis())
            ps.executeUpdate()
        }
        return sql.connection().prepareStatement("UPDATE $PREWARD SET claimed = 1, updated = ? WHERE uuid = ? AND reward = ? AND claimed = 0").use { ps ->
            ps.setLong(1, System.currentTimeMillis())
            ps.setString(2, id.toString())
            ps.setString(3, reward)
            ps.executeUpdate() == 1
        }
    }

    /** 가져오기 — 이미 받은 한 번 보상으로 적는다(줄이 있으면 claimed 만 1 로). */
    fun markClaimed(sql: SqlSource, rows: List<Pair<UUID, String>>) {
        if (rows.isEmpty()) return
        sql.connection().prepareStatement(EssDb.upsertSql(sql.dialect, PREWARD, listOf("uuid", "reward"), listOf("claimed"), listOf("progress", "pending", "updated"))).use { ps ->
            val now = System.currentTimeMillis()
            for ((id, reward) in rows) {
                ps.setString(1, id.toString())
                ps.setString(2, reward)
                ps.setLong(3, 0)
                ps.setInt(4, 0)
                ps.setLong(5, now)
                ps.setInt(6, 1)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    fun deleteStates(sql: SqlSource, reward: String) {
        sql.connection().prepareStatement("DELETE FROM $PREWARD WHERE reward = ?").use { it.setString(1, reward); it.executeUpdate() }
    }

    // --- 투표 ---------------------------------------------------------------------------------------

    class VoteRow(val id: UUID, val name: String, val votes: Int, val lastVote: Long?)

    fun readVotes(sql: SqlSource, only: UUID? = null): List<VoteRow> =
        sql.connection().prepareStatement("SELECT uuid, name, votes, last_vote FROM $VOTES" + if (only != null) " WHERE uuid = ?" else "").use { ps ->
            if (only != null) ps.setString(1, only.toString())
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val id = runCatching { UUID.fromString(rs.getString(1)) }.getOrNull() ?: continue
                        add(VoteRow(id, rs.getString(2), rs.getInt(3), rs.getLong(4).takeIf { !rs.wasNull() }))
                    }
                }
            }
        }

    /** 투표 하나 — 수를 **더한다**(여러 서버가 같은 DB 를 써도 잃지 않게). */
    fun addVote(sql: SqlSource, id: UUID, name: String, at: Long) {
        val head = "INSERT INTO $VOTES (uuid, name, votes, last_vote) VALUES (?, ?, 1, ?)"
        val q = if (sql.dialect == SqlDialect.SQLITE) "$head ON CONFLICT (uuid) DO UPDATE SET name = excluded.name, votes = votes + 1, last_vote = excluded.last_vote"
        else "$head ON DUPLICATE KEY UPDATE name = VALUES(name), votes = votes + 1, last_vote = VALUES(last_vote)"
        sql.connection().prepareStatement(q).use { ps ->
            ps.setString(1, id.toString())
            ps.setString(2, name)
            ps.setLong(3, at)
            ps.executeUpdate()
        }
    }

    /** 관리자 편집·가져오기 — 수를 정한다. */
    fun setVotes(sql: SqlSource, id: UUID, name: String, votes: Int, lastVote: Long?) {
        sql.connection().prepareStatement(EssDb.upsertSql(sql.dialect, VOTES, listOf("uuid"), listOf("name", "votes", "last_vote"))).use { ps ->
            ps.setString(1, id.toString())
            ps.setString(2, name)
            ps.setInt(3, votes)
            if (lastVote != null) ps.setLong(4, lastVote) else ps.setNull(4, java.sql.Types.BIGINT)
            ps.executeUpdate()
        }
    }

    class LogRow(val at: Long, val id: UUID?, val name: String, val service: String, val address: String)

    fun readLog(sql: SqlSource, since: Long): List<LogRow> =
        sql.connection().prepareStatement("SELECT at, uuid, name, service, address FROM $VOTE_LOG WHERE at >= ? ORDER BY at").use { ps ->
            ps.setLong(1, since)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(LogRow(rs.getLong(1), rs.getString(2)?.let { runCatching { UUID.fromString(it) }.getOrNull() }, rs.getString(3), rs.getString(4).orEmpty(), rs.getString(5).orEmpty()))
                    }
                }
            }
        }

    fun writeLog(sql: SqlSource, row: LogRow) {
        sql.connection().prepareStatement("INSERT INTO $VOTE_LOG (at, uuid, name, service, address) VALUES (?, ?, ?, ?, ?)").use { ps ->
            ps.setLong(1, row.at)
            ps.setString(2, row.id?.toString())
            ps.setString(3, row.name)
            ps.setString(4, row.service.take(128))
            ps.setString(5, row.address.take(64))
            ps.executeUpdate()
        }
    }

    fun pruneLog(sql: SqlSource, before: Long) {
        sql.connection().prepareStatement("DELETE FROM $VOTE_LOG WHERE at < ?").use { it.setLong(1, before); it.executeUpdate() }
    }

    fun readMilestones(sql: SqlSource, only: UUID? = null): Map<UUID, Set<Int>> {
        val out = HashMap<UUID, MutableSet<Int>>()
        sql.connection().prepareStatement("SELECT uuid, milestone FROM $MILESTONES" + if (only != null) " WHERE uuid = ?" else "").use { ps ->
            if (only != null) ps.setString(1, only.toString())
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val id = runCatching { UUID.fromString(rs.getString(1)) }.getOrNull() ?: continue
                    out.getOrPut(id) { HashSet() } += rs.getInt(2)
                }
            }
        }
        return out
    }

    fun writeMilestone(sql: SqlSource, id: UUID, milestone: Int) = writeMilestones(sql, listOf(id to milestone))

    fun writeMilestones(sql: SqlSource, rows: List<Pair<UUID, Int>>) {
        if (rows.isEmpty()) return
        sql.connection().prepareStatement(EssDb.upsertSql(sql.dialect, MILESTONES, listOf("uuid", "milestone"), emptyList(), listOf("at"))).use { ps ->
            val now = System.currentTimeMillis()
            for ((id, milestone) in rows) {
                ps.setString(1, id.toString())
                ps.setInt(2, milestone)
                ps.setLong(3, now)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    fun clearMilestones(sql: SqlSource, id: UUID) {
        sql.connection().prepareStatement("DELETE FROM $MILESTONES WHERE uuid = ?").use { it.setString(1, id.toString()); it.executeUpdate() }
    }
}
