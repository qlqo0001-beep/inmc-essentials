package com.inmc.essentials.teleport

import com.inmc.essentials.store.EssDb
import kr.inmc.core.store.SqlDialect
import kr.inmc.core.store.SqlSource
import org.bukkit.Bukkit
import org.bukkit.Location
import java.sql.Types
import java.util.UUID

/** 기억한 자리 하나(뒤로·사망·불려 갈 자리). [at] = 기록한 시각(밀리초). 월드가 지금 없으면 [toLocation] 은 null. */
data class Spot(val world: String, val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float, val at: Long) {

    fun toLocation(): Location? = Bukkit.getWorld(world)?.let { Location(it, x, y, z, yaw, pitch) }

    /** 같은 월드에서 [other] 까지의 거리(다른 월드면 무한). */
    fun distance(other: Spot): Double =
        if (!world.equals(other.world, ignoreCase = true)) Double.POSITIVE_INFINITY
        else kotlin.math.sqrt((x - other.x) * (x - other.x) + (y - other.y) * (y - other.y) + (z - other.z) * (z - other.z))

    /** [expireSeconds] 가 지났나(0 = 끝없음). */
    fun expired(expireSeconds: Long, now: Long): Boolean = expireSeconds > 0 && now - at > expireSeconds * 1000L

    companion object {
        fun of(location: Location, at: Long): Spot? =
            location.world?.let { Spot(it.name, location.x, location.y, location.z, location.yaw, location.pitch, at) }
    }
}

/** 한 사람의 저장된 이동 자료(접속 직전에 DB 에서 읽은 것). */
data class TeleportRecord(
    val back: Spot? = null,
    val death: Spot? = null,
    val accepting: Boolean? = null,
    val blocked: Set<UUID> = emptySet(),
    val pending: Spot? = null,
    val pendingBy: String? = null,
)

/**
 * 이동 기능의 표 넷. 쓰기는 전부 `ess.db.run`(쓰기 스레드 하나 — 순서가 지켜진다), 읽기는 접속 직전 비동기 사건과 켤 때만(`ess.db.call`).
 *
 * - `ess_teleport_spots(uuid, kind, …)` — kind = `back` | `death`. 덮어쓰기는 upsert(REPLACE INTO 금지).
 * - `ess_teleport_tpa(uuid, accepting)` — 요청 받기 켬/끔.
 * - `ess_teleport_blocks(owner, blocked)` — 그 사람의 요청을 받지 않는다(`/요청차단 <이름>`).
 * - `ess_teleport_pending(uuid, …)` — 나가 있는 동안 `/소환` 된 사람이 다음 접속 때 옮겨질 자리.
 */
object TeleportData {

    const val BACK = "back"
    const val DEATH = "death"

    fun schema(source: SqlSource) {
        val sqlite = source.dialect == SqlDialect.SQLITE
        val id = if (sqlite) "TEXT" else "VARCHAR(36)"
        val text = if (sqlite) "TEXT" else "VARCHAR(128)"
        val real = if (sqlite) "REAL" else "DOUBLE"
        val big = if (sqlite) "INTEGER" else "BIGINT"
        source.connection().createStatement().use { st ->
            st.execute(
                "CREATE TABLE IF NOT EXISTS ess_teleport_spots (uuid $id NOT NULL, kind ${if (sqlite) "TEXT" else "VARCHAR(8)"} NOT NULL, " +
                    "world $text NOT NULL, x $real NOT NULL, y $real NOT NULL, z $real NOT NULL, yaw $real NOT NULL, pitch $real NOT NULL, saved_at $big NOT NULL, " +
                    "PRIMARY KEY (uuid, kind))",
            )
            st.execute("CREATE TABLE IF NOT EXISTS ess_teleport_tpa (uuid $id PRIMARY KEY, accepting INTEGER NOT NULL)")
            st.execute("CREATE TABLE IF NOT EXISTS ess_teleport_blocks (owner $id NOT NULL, blocked $id NOT NULL, PRIMARY KEY (owner, blocked))")
            st.execute(
                "CREATE TABLE IF NOT EXISTS ess_teleport_pending (uuid $id PRIMARY KEY, world $text NOT NULL, x $real NOT NULL, y $real NOT NULL, " +
                    "z $real NOT NULL, yaw $real NOT NULL, pitch $real NOT NULL, saved_at $big NOT NULL, by_name ${if (sqlite) "TEXT" else "VARCHAR(64)"})",
            )
        }
    }

    /** 한 사람의 자료(쓰기 스레드에서 — `ess.db.call` 안). */
    fun read(source: SqlSource, id: UUID): TeleportRecord {
        val c = source.connection()
        var back: Spot? = null
        var death: Spot? = null
        c.prepareStatement("SELECT kind, world, x, y, z, yaw, pitch, saved_at FROM ess_teleport_spots WHERE uuid = ?").use { ps ->
            ps.setString(1, id.toString())
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val spot = Spot(rs.getString(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), rs.getFloat(6), rs.getFloat(7), rs.getLong(8))
                    when (rs.getString(1)) {
                        BACK -> back = spot
                        DEATH -> death = spot
                    }
                }
            }
        }
        val accepting = c.prepareStatement("SELECT accepting FROM ess_teleport_tpa WHERE uuid = ?").use { ps ->
            ps.setString(1, id.toString())
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) != 0 else null }
        }
        val blocked = c.prepareStatement("SELECT blocked FROM ess_teleport_blocks WHERE owner = ?").use { ps ->
            ps.setString(1, id.toString())
            ps.executeQuery().use { rs -> buildSet { while (rs.next()) runCatching { UUID.fromString(rs.getString(1)) }.getOrNull()?.let { add(it) } } }
        }
        var pending: Spot? = null
        var pendingBy: String? = null
        c.prepareStatement("SELECT world, x, y, z, yaw, pitch, saved_at, by_name FROM ess_teleport_pending WHERE uuid = ?").use { ps ->
            ps.setString(1, id.toString())
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    pending = Spot(rs.getString(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getFloat(5), rs.getFloat(6), rs.getLong(7))
                    pendingBy = rs.getString(8)
                }
            }
        }
        return TeleportRecord(back, death, accepting, blocked, pending, pendingBy)
    }

    fun writeSpot(source: SqlSource, id: UUID, kind: String, spot: Spot, onlyIfAbsent: Boolean = false) {
        val update = if (onlyIfAbsent) emptyList() else listOf("world", "x", "y", "z", "yaw", "pitch", "saved_at")
        val insertOnly = if (onlyIfAbsent) listOf("world", "x", "y", "z", "yaw", "pitch", "saved_at") else emptyList()
        val sql = EssDb.upsertSql(source.dialect, "ess_teleport_spots", listOf("uuid", "kind"), update, insertOnly)
        source.connection().prepareStatement(sql).use { ps ->
            ps.setString(1, id.toString())
            ps.setString(2, kind)
            bindSpot(ps, 3, spot)
            ps.executeUpdate()
        }
    }

    fun deleteSpot(source: SqlSource, id: UUID, kind: String) {
        source.connection().prepareStatement("DELETE FROM ess_teleport_spots WHERE uuid = ? AND kind = ?").use { ps ->
            ps.setString(1, id.toString())
            ps.setString(2, kind)
            ps.executeUpdate()
        }
    }

    fun writeAccepting(source: SqlSource, id: UUID, accepting: Boolean, onlyIfAbsent: Boolean = false) {
        val sql = if (onlyIfAbsent) EssDb.upsertSql(source.dialect, "ess_teleport_tpa", listOf("uuid"), emptyList(), listOf("accepting"))
        else EssDb.upsertSql(source.dialect, "ess_teleport_tpa", listOf("uuid"), listOf("accepting"))
        source.connection().prepareStatement(sql).use { ps ->
            ps.setString(1, id.toString())
            ps.setInt(2, if (accepting) 1 else 0)
            ps.executeUpdate()
        }
    }

    fun writeBlock(source: SqlSource, owner: UUID, blocked: UUID, add: Boolean) {
        val sql = if (add) EssDb.upsertSql(source.dialect, "ess_teleport_blocks", listOf("owner", "blocked"), emptyList())
        else "DELETE FROM ess_teleport_blocks WHERE owner = ? AND blocked = ?"
        source.connection().prepareStatement(sql).use { ps ->
            ps.setString(1, owner.toString())
            ps.setString(2, blocked.toString())
            ps.executeUpdate()
        }
    }

    fun writePending(source: SqlSource, id: UUID, spot: Spot, by: String?) {
        val sql = EssDb.upsertSql(source.dialect, "ess_teleport_pending", listOf("uuid"), listOf("world", "x", "y", "z", "yaw", "pitch", "saved_at", "by_name"))
        source.connection().prepareStatement(sql).use { ps ->
            ps.setString(1, id.toString())
            bindSpot(ps, 2, spot)
            if (by != null) ps.setString(9, by) else ps.setNull(9, Types.VARCHAR)
            ps.executeUpdate()
        }
    }

    fun deletePending(source: SqlSource, id: UUID) {
        source.connection().prepareStatement("DELETE FROM ess_teleport_pending WHERE uuid = ?").use { ps ->
            ps.setString(1, id.toString())
            ps.executeUpdate()
        }
    }

    /** 불려 갈 자리 전부를 지운다 — 지운 줄 수. */
    fun deleteAllPending(source: SqlSource): Int =
        source.connection().createStatement().use { it.executeUpdate("DELETE FROM ess_teleport_pending") }

    /** world, x, y, z, yaw, pitch, at — [start] 부터 7 자리. */
    private fun bindSpot(ps: java.sql.PreparedStatement, start: Int, spot: Spot) {
        ps.setString(start, spot.world)
        ps.setDouble(start + 1, spot.x)
        ps.setDouble(start + 2, spot.y)
        ps.setDouble(start + 3, spot.z)
        ps.setFloat(start + 4, spot.yaw)
        ps.setFloat(start + 5, spot.pitch)
        ps.setLong(start + 6, spot.at)
    }
}
