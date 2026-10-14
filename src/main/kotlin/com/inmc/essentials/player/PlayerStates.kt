package com.inmc.essentials.player

import com.inmc.essentials.Ess
import com.inmc.essentials.store.EssDb
import kr.inmc.core.store.SqlDialect
import kr.inmc.core.store.SqlSource
import org.bukkit.Bukkit
import java.sql.Types
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/**
 * 한 사람의 저장되는 상태. 시간제 무적·비행은 **남은 초**로(가이드 함정 14 — 끝나는 시각으로 두면 서버가 꺼져 있던 동안 다 닳는다).
 * 남은 초는 접속해 있는 동안만 준다. [UNTIL_RELOG] 는 "다시 접속할 때까지" — DB 에 쓰지 않는다.
 */
data class PlayerRecord(
    val god: Boolean = false,
    val fly: Boolean = false,
    val collision: Boolean = true,
    val tgodLeft: Long? = null,
    val tflyLeft: Long? = null,
) {
    val isDefault: Boolean get() = !god && !fly && collision && tgodLeft == null && tflyLeft == null

    /**
     * 읽기에 실패한 채 돈 이번 접속의 상태([this])를 나중에 읽은 저장값([loaded]) 위에 얹는다 — 이번 접속에 켠 것(기본값이 아닌 칸)이 이긴다.
     * 기본값에서 시작했으므로 이번 접속에 "끈" 것은 알 수 없다(저장값을 지우지 않는 쪽을 고른다).
     */
    fun over(loaded: PlayerRecord): PlayerRecord = PlayerRecord(
        god = god || loaded.god,
        fly = fly || loaded.fly,
        collision = collision && loaded.collision,
        tgodLeft = tgodLeft ?: loaded.tgodLeft,
        tflyLeft = tflyLeft ?: loaded.tflyLeft,
    )

    companion object {
        const val UNTIL_RELOG = -1L
    }
}

/**
 * player 기능의 사람별 상태 — 접속해 있는 동안만 메모리, 접속 직전(비동기 사건)에 DB 에서 읽고 바뀌는 순간 DB 쓰기 줄로 넘긴다.
 * 시간제의 남은 초는 1초마다 메모리에서 줄고, 나갈 때·끌 때·1분마다(공용 DB 면 5초마다) DB 에 적는다(크래시 때 잃는 것은 1분 이내).
 *
 * 표 `ess_player_state(uuid, god, fly, collision, tgod_left, tfly_left, updated_at)` — 덮어쓰기는 upsert(REPLACE INTO 금지).
 */
class PlayerStates(private val ess: Ess) {

    private val records = ConcurrentHashMap<UUID, PlayerRecord>()
    private val preloaded = ConcurrentHashMap<UUID, Pair<Long, PlayerRecord>>()

    /** 접속 직전 읽기에 실패한 사람(접속 때 [unloaded] 로 옮긴다). */
    private val failedPreload = ConcurrentHashMap<UUID, Long>()

    /**
     * 저장값을 아직 못 읽은 사람 — **DB 에 쓰지 않는다.** 기본값으로 돌다 나갈 때 쓰면 기본값 = 줄 지우기라 저장된 무적·비행·남은 시간이
     * 사라진다(2026-10-10 리뷰). 다시 읽기에 성공하면 이번 접속의 변경을 얹어 그때 적는다.
     */
    private val unloaded = ConcurrentHashMap.newKeySet<UUID>()

    /** 공용 DB 에서 접속 뒤 다시 읽기를 기다리는 사람 — 그 전에는 1초 틱의 주기 쓰기를 하지 않는다(옛 서버의 늦은 나감 쓰기를 덮지 않게). */
    private val awaitingRecheck = ConcurrentHashMap.newKeySet<UUID>()

    fun isAwaitingRecheck(id: UUID): Boolean = id in awaitingRecheck

    /** 검증기가 도는 동안 true — DB 에 쓰지 않는다(메인). */
    @Volatile
    var dryRun = false

    fun get(id: UUID): PlayerRecord = records[id] ?: PlayerRecord()

    /** 메모리만 바꾼다(검증기 되돌리기 · 1초 줄이기). */
    internal fun putMemory(id: UUID, record: PlayerRecord) {
        records[id] = record
    }

    /** 바꾸고 DB 에 적는다. */
    fun update(id: UUID, change: (PlayerRecord) -> PlayerRecord): PlayerRecord {
        val next = change(get(id))
        records[id] = next
        persist(id, next)
        return next
    }

    fun persist(id: UUID, record: PlayerRecord = get(id)) {
        if (dryRun || !ess.db.isOpen || id in unloaded) return
        ess.db.run("플레이어 상태") { write(ess.db.sql, id, record) }
    }

    fun isGod(id: UUID): Boolean = get(id).let { it.god || it.tgodLeft != null }

    /** 저장값을 읽어 메모리에 둔 사람(접속 중 · 읽기 실패·읽는 중이 아님). */
    fun isLoaded(id: UUID): Boolean = records.containsKey(id) && id !in unloaded

    fun online(): Map<UUID, PlayerRecord> = records

    // --- 읽기 · 접속 · 나감 ---------------------------------------------------------------------

    /** 접속 직전(비동기 사건) — 기다려도 된다. */
    fun preload(id: UUID) {
        try {
            val record = ess.db.call { read(ess.db.sql, id) }
            preloaded[id] = System.currentTimeMillis() to (record ?: PlayerRecord())
            failedPreload.remove(id)
        } catch (t: Throwable) {
            failedPreload[id] = System.currentTimeMillis()
            ess.logger.log(Level.WARNING, "플레이어 상태를 읽지 못했습니다($id) — 접속 뒤 다시 읽고, 읽기 전에는 저장하지 않습니다", t)
        }
    }

    /**
     * 접속 — 읽어 둔 것을 넣고 돌려준다. 읽기에 실패했던 사람은 기본값으로 돌리며 쓰지 않고, 쓰기 스레드에서 다시 읽어
     * 메인에서 [apply] 한다.
     */
    fun joined(id: UUID, apply: (UUID, PlayerRecord) -> Unit): PlayerRecord {
        val pre = preloaded.remove(id)
        failedPreload.remove(id)
        val record = pre?.second ?: PlayerRecord()
        records[id] = record
        if (sharedDb && (record.tgodLeft != null || record.tflyLeft != null)) awaitingRecheck += id
        if (pre == null && ess.db.isOpen) {
            // 읽기 실패 — 또는 접속 사건만 오고 읽기 사건이 없었다(다른 플러그인이 막았다 풀었거나). 어느 쪽이든 저장값을 모른다.
            unloaded += id
            reload(listOf(id), apply)
        }
        return record
    }

    /** 이미 접속해 있는 사람(켤 때·`/reload`) — 쓰기 스레드에서 읽고 메인에서 [apply]. 읽기 전에는 쓰지 않는다. */
    fun loadOnline(apply: (UUID, PlayerRecord) -> Unit) {
        val ids = Bukkit.getOnlinePlayers().map { it.uniqueId }
        if (ids.isEmpty() || !ess.db.isOpen) return
        unloaded += ids
        reload(ids, apply)
    }

    /** 쓰기 스레드에서 읽고, 메인에서 이번 접속의 상태를 얹어 넣는다(성공한 사람만 [unloaded] 에서 뺀다 — 실패하면 이번 접속 동안 쓰지 않는다). */
    private fun reload(ids: List<UUID>, apply: (UUID, PlayerRecord) -> Unit) {
        if (!ess.db.isOpen) return
        ess.db.run("플레이어 상태 읽기") {
            for (id in ids) {
                val loaded = try {
                    read(ess.db.sql, id) ?: PlayerRecord()
                } catch (t: Throwable) {
                    ess.logger.log(Level.WARNING, "플레이어 상태를 다시 읽지 못했습니다($id) — 이번 접속의 변경은 저장하지 않습니다", t)
                    continue
                }
                Bukkit.getScheduler().runTask(ess.plugin, Runnable {
                    if (id !in unloaded) return@Runnable
                    unloaded -= id
                    if (Bukkit.getPlayer(id) == null) return@Runnable
                    val session = records[id] ?: PlayerRecord()
                    val merged = session.over(loaded)
                    records[id] = merged
                    if (merged != loaded) persist(id, merged)
                    apply(id, merged)
                })
            }
        }
    }

    /**
     * CMI 가져오기가 DB 에 넣은 값([patch] — 우리 쪽이 기본값인 칸만 바꾸는 함수)을 메모리에도 — 가져오기와 접속이 엇갈려(읽어 둔 뒤·접속한 뒤) 나갈 때 빈 칸으로 덮어쓰지 않게.
     * 접속해 있는 사람의 기록이 바뀌었으면 그 기록을 돌려준다(메인).
     */
    internal fun adoptImported(id: UUID, patch: (PlayerRecord) -> PlayerRecord): PlayerRecord? {
        preloaded.computeIfPresent(id) { _, (at, r) -> at to patch(r) }
        val now = records[id] ?: return null
        val next = patch(now)
        if (next == now) return null
        records[id] = next
        return next
    }

    /** 나감 — "다시 접속할 때까지" 인 것은 지우고 적은 뒤 메모리에서 뺀다. 저장값을 못 읽은 채였으면 쓰지 않는다. */
    fun quit(id: UUID) {
        awaitingRecheck -= id
        val record = records.remove(id) ?: return
        if (unloaded.remove(id)) {
            if (!record.isDefault) ess.logger.warning("플레이어 상태를 끝내 읽지 못해 이번 접속의 변경을 저장하지 않았습니다($id: $record)")
            return
        }
        val kept = record.copy(
            tgodLeft = record.tgodLeft?.takeIf { it > 0 },
            tflyLeft = record.tflyLeft?.takeIf { it > 0 },
        )
        persist(id, kept)
    }

    /**
     * 여러 서버가 같은 DB 를 쓰나(MySQL·MariaDB — `storage` 가 SQLite 가 아니면). 프록시는 새 서버에 먼저 붙고 옛 서버를 나중에 끊으므로, 새 서버의
     * 접속 직전 읽기가 옛 서버의 나감 쓰기보다 먼저 일 수 있다 — 그래서 공용 DB 면 시간제 값을 자주 적고([PlayerFeature] 1초 틱) 접속 뒤 한 번 더 읽는다.
     */
    val sharedDb: Boolean get() = ess.db.isOpen && ess.db.dialect != SqlDialect.SQLITE

    /**
     * 접속 몇 초 뒤(공용 DB) — 옛 서버의 늦은 나감 쓰기를 읽어 시간제 남은 초를 작은 쪽으로 맞춘다([TimedMath.reconcile]). 쓰기 스레드에서 읽고 메인에서 넣는다.
     * 저장값을 못 읽은 사람([unloaded])은 그쪽 다시 읽기가 맡는다.
     */
    fun recheckTimed(id: UUID) {
        if (!sharedDb || id in unloaded || !records.containsKey(id)) {
            awaitingRecheck -= id
            return
        }
        ess.db.run("플레이어 상태 다시 읽기") {
            val stored = try {
                read(ess.db.sql, id)
            } catch (t: Throwable) {
                ess.logger.log(Level.WARNING, "플레이어 상태를 다시 읽지 못했습니다($id) — 시간제 남은 초를 맞추지 못했습니다", t)
                null
            }
            Bukkit.getScheduler().runTask(ess.plugin, Runnable {
                awaitingRecheck -= id
                stored ?: return@Runnable
                val now = records[id] ?: return@Runnable
                if (id in unloaded) return@Runnable
                val next = now.copy(tgodLeft = TimedMath.reconcile(now.tgodLeft, stored.tgodLeft), tflyLeft = TimedMath.reconcile(now.tflyLeft, stored.tflyLeft))
                if (next == now) return@Runnable
                records[id] = next
                persist(id, next)
            })
        }
    }

    /** 1초 — 접속하지 못한 사람의 읽어 둔 자료를 버린다. */
    fun sweep(now: Long) {
        preloaded.entries.removeIf { now - it.value.first > 60_000L && Bukkit.getPlayer(it.key) == null }
        failedPreload.entries.removeIf { now - it.value > 60_000L && Bukkit.getPlayer(it.key) == null }
    }

    /** 끌 때 — 접속자 전부를 적는다(DB 워커는 이 뒤에 닫힌다). */
    fun flushAll() {
        for ((id, record) in records) persist(id, record.copy(tgodLeft = record.tgodLeft?.takeIf { it > 0 }, tflyLeft = record.tflyLeft?.takeIf { it > 0 }))
    }

    companion object {

        fun schema(source: SqlSource) {
            val sqlite = source.dialect == SqlDialect.SQLITE
            val id = if (sqlite) "TEXT" else "VARCHAR(36)"
            val big = if (sqlite) "INTEGER" else "BIGINT"
            source.connection().createStatement().use { st ->
                st.execute(
                    "CREATE TABLE IF NOT EXISTS ess_player_state (uuid $id PRIMARY KEY, god INTEGER NOT NULL DEFAULT 0, fly INTEGER NOT NULL DEFAULT 0, " +
                        "collision INTEGER NOT NULL DEFAULT 1, tgod_left $big, tfly_left $big, updated_at $big NOT NULL)",
                )
            }
        }

        fun read(source: SqlSource, id: UUID): PlayerRecord? =
            source.connection().prepareStatement("SELECT god, fly, collision, tgod_left, tfly_left FROM ess_player_state WHERE uuid = ?").use { ps ->
                ps.setString(1, id.toString())
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    val tgod = rs.getLong(4).takeUnless { rs.wasNull() }?.takeIf { it > 0 }
                    val tfly = rs.getLong(5).takeUnless { rs.wasNull() }?.takeIf { it > 0 }
                    PlayerRecord(rs.getInt(1) != 0, rs.getInt(2) != 0, rs.getInt(3) != 0, tgod, tfly)
                }
            }

        fun write(source: SqlSource, id: UUID, record: PlayerRecord) {
            if (record.isDefault) {
                source.connection().prepareStatement("DELETE FROM ess_player_state WHERE uuid = ?").use { ps ->
                    ps.setString(1, id.toString())
                    ps.executeUpdate()
                }
                return
            }
            val sql = EssDb.upsertSql(source.dialect, "ess_player_state", listOf("uuid"), listOf("god", "fly", "collision", "tgod_left", "tfly_left", "updated_at"))
            source.connection().prepareStatement(sql).use { ps ->
                ps.setString(1, id.toString())
                ps.setInt(2, if (record.god) 1 else 0)
                ps.setInt(3, if (record.fly) 1 else 0)
                ps.setInt(4, if (record.collision) 1 else 0)
                record.tgodLeft?.takeIf { it > 0 }?.let { ps.setLong(5, it) } ?: ps.setNull(5, Types.BIGINT)
                record.tflyLeft?.takeIf { it > 0 }?.let { ps.setLong(6, it) } ?: ps.setNull(6, Types.BIGINT)
                ps.setLong(7, System.currentTimeMillis())
                ps.executeUpdate()
            }
        }

        /**
         * CMI 가져오기 — 시간제 비행·무적의 남은 초를 **비어 있을 때만** 넣는다(이미 우리 쪽에 남은 시간이 있으면 그대로). 두 번 돌려도 겹치지 않는다.
         * 넣었으면 true.
         */
        fun importTimed(source: SqlSource, id: UUID, column: String, seconds: Long): Boolean {
            require(column == "tgod_left" || column == "tfly_left")
            ensureRow(source, id)
            return source.connection().prepareStatement("UPDATE ess_player_state SET $column = ? WHERE uuid = ? AND $column IS NULL").use { ps ->
                ps.setLong(1, seconds)
                ps.setString(2, id.toString())
                ps.executeUpdate() > 0
            }
        }

        /**
         * CMI 가져오기 — `/비행` 켜 둠(`fly` → 1) · 밀기 끔(`collision` → 0)을 **우리 쪽이 기본값일 때만** 넣는다. 넣었으면 true.
         */
        fun importFlag(source: SqlSource, id: UUID, column: String): Boolean {
            val (to, from) = when (column) {
                "fly" -> 1 to 0
                "collision" -> 0 to 1
                else -> throw IllegalArgumentException(column)
            }
            ensureRow(source, id)
            return source.connection().prepareStatement("UPDATE ess_player_state SET $column = ? WHERE uuid = ? AND $column = ?").use { ps ->
                ps.setInt(1, to)
                ps.setString(2, id.toString())
                ps.setInt(3, from)
                ps.executeUpdate() > 0
            }
        }

        /** 줄이 없으면 기본값으로 만든다(있으면 그대로). */
        private fun ensureRow(source: SqlSource, id: UUID) {
            val insert = EssDb.upsertSql(source.dialect, "ess_player_state", listOf("uuid"), emptyList(), listOf("updated_at"))
            source.connection().prepareStatement(insert).use { ps ->
                ps.setString(1, id.toString())
                ps.setLong(2, System.currentTimeMillis())
                ps.executeUpdate()
            }
        }
    }
}
