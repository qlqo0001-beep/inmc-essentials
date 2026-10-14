package com.inmc.essentials.store

import com.inmc.essentials.Ess
import kr.inmc.core.store.SqlDialect
import kr.inmc.core.store.SqlSource
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.sql.Types
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 나간 자리. 월드가 지금 없으면 [toLocation] 은 null. */
data class LogoutSpot(val world: String, val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float) {

    fun toLocation(): Location? = Bukkit.getWorld(world)?.let { Location(it, x, y, z, yaw, pitch) }

    companion object {
        fun of(location: Location): LogoutSpot? =
            location.world?.let { LogoutSpot(it.name, location.x, location.y, location.z, location.yaw, location.pitch) }
    }
}

/** 이 서버에 온 사람 한 명(`ess_users` 한 줄). 시각은 밀리초. 불변 — 바꿀 때는 [UserService.save] 에 copy 를 넘긴다. */
data class UserRecord(
    val id: UUID,
    val name: String,
    val firstJoin: Long,
    val lastJoin: Long,
    val lastQuit: Long? = null,
    val lastIp: String? = null,
    val logout: LogoutSpot? = null,
)

/**
 * 온 적 있는 사람 전부(`ess_users`) — 이름 ↔ uuid, 처음·마지막 접속, 마지막 IP, 나간 자리. **전부 메모리에 들고 있다**(켤 때 한 번 읽는다) —
 * 오프라인 이름 찾기(`/seen`·`/인벤`·밴 …)가 메인 스레드에서 DB 를 기다리지 않게. 쓰기는 바뀌는 순간 DB 쓰기 스레드로.
 *
 * - 이름 → uuid 는 소문자로, 같은 이름이 둘이면(이름을 넘겨받은 사람) **마지막으로 들어온 사람**([UserIndex]).
 * - 공용 DB(mysql)를 여러 서버가 같이 쓰면 다른 서버가 쓴 줄은 이 서버를 다시 켤 때 보인다 — 단 **들어오는 사람 자신의 줄**은 접속 직전
 *   (비동기)에 DB 에서 한 번 더 찾는다. 다른 서버에서 먼저 온 사람을 여기서 첫 접속으로 치지 않게.
 * - 이름 바뀜 기록은 두지 않는다(사용자 결정 — 이름 기록 없음).
 */
class UserService(private val ess: Ess) : Listener {

    private val index = UserIndex()

    /** 이번 접속에서 처음 만든 기록(첫 접속) — 나갈 때 지운다. */
    private val firstJoins = ConcurrentHashMap.newKeySet<UUID>()

    /** 접속 직전 사건에서 본 IP — 접속 사건에서 기록에 넣는다. */
    private val pendingIp = ConcurrentHashMap<UUID, String>()

    fun get(id: UUID): UserRecord? = index[id]

    /** 이름으로(대소문자 무시, 정확히). 부분 이름·닉네임은 `ess.args` 가 한다. */
    fun byName(name: String): UserRecord? = index.byName(name)

    fun all(): Collection<UserRecord> = index.all()

    fun count(): Int = index.count()

    /** 이번 접속이 이 서버의 첫 접속인가(기록을 이번에 만들었나). `PlayerJoinEvent` 의 LOWEST 뒤부터 맞다. */
    fun isFirstJoin(player: Player): Boolean = player.uniqueId in firstJoins

    /** 기록을 바꾼다(메모리 + DB 쓰기 줄). 가져오기·데이터 교환 같은 관리 도구용. */
    fun save(record: UserRecord) {
        index.put(record)
        ess.db.run("유저 기록") { write(ess.db.sql, record) }
    }

    /** 기록을 지운다(메모리 + DB). 다른 기능의 그 사람 데이터는 각 기능이 지운다. */
    fun remove(id: UUID) {
        index.remove(id) ?: return
        ess.db.run("유저 삭제") {
            ess.db.sql.connection().prepareStatement("DELETE FROM ess_users WHERE uuid = ?").use {
                it.setString(1, id.toString())
                it.executeUpdate()
            }
        }
    }

    // --- 켜기 ---------------------------------------------------------------------------------

    /** 표 — 쓰기 스레드에서(EssDb.open 이 부른다). */
    fun schema(source: SqlSource) {
        val sqlite = source.dialect == SqlDialect.SQLITE
        val text = if (sqlite) "TEXT" else "VARCHAR(64)"
        val big = if (sqlite) "INTEGER" else "BIGINT"
        val real = if (sqlite) "REAL" else "DOUBLE"
        source.connection().createStatement().use { st ->
            st.execute(
                "CREATE TABLE IF NOT EXISTS ess_users (uuid ${if (sqlite) "TEXT" else "VARCHAR(36)"} PRIMARY KEY, name $text NOT NULL, " +
                    "name_lower $text NOT NULL, first_join $big NOT NULL, last_join $big NOT NULL, last_quit $big, last_ip $text, " +
                    "logout_world ${if (sqlite) "TEXT" else "VARCHAR(128)"}, logout_x $real, logout_y $real, logout_z $real, logout_yaw $real, logout_pitch $real" +
                    (if (sqlite) ")" else ", INDEX ess_users_name (name_lower))"),
            )
            if (sqlite) st.execute("CREATE INDEX IF NOT EXISTS ess_users_name ON ess_users (name_lower)")
        }
    }

    /** 전부 읽는다(기다린다 — 켤 때만). */
    fun loadAll() {
        val rows = ess.db.call { read(ess.db.sql) }
        index.clear()
        for (record in rows.sortedBy { it.lastJoin }) index.put(record)
        ess.logger.info("유저 기록 ${rows.size}명을 읽었습니다")
    }

    /** `/reload` 뒤 — 이미 접속한 사람의 기록이 없으면 만든다(접속 사건이 다시 오지 않는다). */
    fun loadOnline() {
        for (player in Bukkit.getOnlinePlayers()) if (index[player.uniqueId] == null) joined(player, player.address?.address?.hostAddress)
    }

    /**
     * 끌 때(`onDisable`) — 접속 중인 사람 모두를 나간 것으로 적는다. 서버를 끌 때 Paper 는 플러그인을 먼저 끄고 접속자를 나중에 내보내서
     * [onQuit] 이 오지 않는다. `/reload` 면 다시 켜진 뒤에도 접속 중이지만 마지막 나감이 그 시각으로 남는다(접속 중인지는 Bukkit 으로 본다).
     */
    fun quitAll() {
        for (player in Bukkit.getOnlinePlayers()) quit(player)
    }

    // --- 사건 ---------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        if (event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED) return
        event.address.hostAddress?.let { pendingIp[event.uniqueId] = it }
        // 공용 DB 면 다른 서버에서 먼저 온 사람일 수 있다 — 메모리에 없을 때만 그 줄 하나를 읽어 둔다(비동기 사건이라 기다려도 된다).
        if (ess.db.network != null && index[event.uniqueId] == null) {
            try {
                ess.db.call { read(ess.db.sql, event.uniqueId) }.firstOrNull()?.let { found -> if (index[found.id] == null) index.put(found) }
            } catch (t: Throwable) {
                ess.logger.warning("공용 DB 에서 ${event.name} 의 기록을 읽지 못했습니다 — 이 서버 기준으로 처리합니다: ${t.message}")
            }
        }
    }

    /** LOWEST — 다른 기능의 접속 처리(NORMAL)가 기록과 [isFirstJoin] 을 볼 수 있게. */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        joined(player, pendingIp.remove(player.uniqueId) ?: player.address?.address?.hostAddress)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        quit(event.player)
    }

    private fun quit(player: Player) {
        firstJoins.remove(player.uniqueId)
        pendingIp.remove(player.uniqueId)
        val old = index[player.uniqueId] ?: return
        save(old.copy(lastQuit = System.currentTimeMillis(), logout = LogoutSpot.of(player.location) ?: old.logout))
    }

    private fun joined(player: Player, ip: String?) {
        val now = System.currentTimeMillis()
        val old = index[player.uniqueId]
        val record = if (old == null) {
            firstJoins += player.uniqueId
            UserRecord(player.uniqueId, player.name, firstJoin = now, lastJoin = now, lastIp = ip)
        } else {
            old.copy(name = player.name, lastJoin = now, lastIp = ip ?: old.lastIp)
        }
        save(record)
    }

    // --- 쓰기 스레드 ----------------------------------------------------------------------------

    /** 전부, 또는 [only] 한 사람. */
    private fun read(source: SqlSource, only: UUID? = null): List<UserRecord> =
        source.connection().prepareStatement(
            "SELECT uuid, name, first_join, last_join, last_quit, last_ip, logout_world, logout_x, logout_y, logout_z, logout_yaw, logout_pitch FROM ess_users" +
                if (only != null) " WHERE uuid = ?" else "",
        ).use { ps ->
            if (only != null) ps.setString(1, only.toString())
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val id = runCatching { UUID.fromString(rs.getString(1)) }.getOrNull() ?: continue
                        val name = rs.getString(2)
                        val firstJoin = rs.getLong(3)
                        val lastJoin = rs.getLong(4)
                        val lastQuit = rs.getLong(5).takeIf { !rs.wasNull() }
                        val ip = rs.getString(6)
                        val world = rs.getString(7)
                        val logout = world?.let { LogoutSpot(it, rs.getDouble(8), rs.getDouble(9), rs.getDouble(10), rs.getFloat(11), rs.getFloat(12)) }
                        add(UserRecord(id, name, firstJoin, lastJoin, lastQuit, ip, logout))
                    }
                }
            }
        }

    private fun write(source: SqlSource, r: UserRecord) {
        val sql = EssDb.upsertSql(
            source.dialect, "ess_users", keys = listOf("uuid"), insertOnly = listOf("first_join"),
            update = listOf("name", "name_lower", "last_join", "last_quit", "last_ip", "logout_world", "logout_x", "logout_y", "logout_z", "logout_yaw", "logout_pitch"),
        )
        source.connection().prepareStatement(sql).use { ps ->
            ps.setString(1, r.id.toString())
            ps.setLong(2, r.firstJoin)
            ps.setString(3, r.name)
            ps.setString(4, r.name.lowercase())
            ps.setLong(5, r.lastJoin)
            if (r.lastQuit != null) ps.setLong(6, r.lastQuit) else ps.setNull(6, Types.BIGINT)
            ps.setString(7, r.lastIp)
            val spot = r.logout
            ps.setString(8, spot?.world)
            if (spot != null) {
                ps.setDouble(9, spot.x)
                ps.setDouble(10, spot.y)
                ps.setDouble(11, spot.z)
                ps.setFloat(12, spot.yaw)
                ps.setFloat(13, spot.pitch)
            } else {
                for (i in 9..13) ps.setNull(i, Types.DOUBLE)
            }
            ps.executeUpdate()
        }
    }
}
