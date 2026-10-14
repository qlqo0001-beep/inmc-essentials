package com.inmc.essentials.home

import com.inmc.essentials.Ess
import kr.inmc.core.store.SqlDialect
import kr.inmc.core.store.SqlSource
import java.sql.Types
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 홈 저장소 — `ess_homes` 전부를 메모리에 든다(켤 때 한 번 읽는다). 부활 사건·이름 제안·오프라인 사람의 홈 보기가 메인 스레드에서
 * DB 를 기다리지 않게. 쓰기는 바뀌는 순간 DB 쓰기 스레드로(`ess.db.run`).
 *
 * 공용 DB(mysql)를 여러 서버가 쓰면 다른 서버에서 고친 홈은 그 사람이 **이 서버에 들어오기 직전**(비동기 접속 사건)에 다시 읽는다([reload]).
 */
class HomeStore(private val ess: Ess) {

    /** 주인 → 그 사람의 홈(불변 목록 — 통째로 바꿔 끼운다). */
    private val byOwner = ConcurrentHashMap<UUID, List<Home>>()

    fun of(owner: UUID): List<Home> = byOwner[owner].orEmpty()

    fun get(owner: UUID, name: String): Home? {
        val key = Home.keyOf(name)
        return of(owner).firstOrNull { it.key == key }
    }

    fun all(): Sequence<Home> = byOwner.values.asSequence().flatten()

    fun owners(): Set<UUID> = byOwner.keys.toSet()

    /** 넣거나 바꾼다(메모리 + DB). */
    fun put(home: Home) {
        putMemory(home)
        ess.db.run("홈 저장") { write(ess.db.sql, home) }
    }

    /** 지운다(메모리 + DB). 없었으면 false. */
    fun remove(owner: UUID, name: String): Boolean {
        if (!removeMemory(owner, name)) return false
        val key = Home.keyOf(name)
        ess.db.run("홈 삭제") {
            ess.db.sql.connection().prepareStatement("DELETE FROM $TABLE WHERE owner = ? AND name_key = ?").use {
                it.setString(1, owner.toString())
                it.setString(2, key)
                it.executeUpdate()
            }
        }
        return true
    }

    /** 메모리만(검증기 — 끝나면 [removeMemory] 로 되돌린다). */
    internal fun putMemory(home: Home) {
        byOwner.compute(home.owner) { _, old -> old.orEmpty().filter { it.key != home.key } + home }
    }

    internal fun removeMemory(owner: UUID, name: String): Boolean {
        val key = Home.keyOf(name)
        var removed = false
        byOwner.computeIfPresent(owner) { _, old ->
            val left = old.filter { it.key != key }
            removed = left.size != old.size
            left.ifEmpty { null }
        }
        return removed
    }

    // --- 켜기 · 접속 --------------------------------------------------------------------------

    fun schema(source: SqlSource) {
        val sqlite = source.dialect == SqlDialect.SQLITE
        val text = if (sqlite) "TEXT" else "VARCHAR(64)"
        val long = if (sqlite) "TEXT" else "VARCHAR(192)"
        val real = if (sqlite) "REAL" else "DOUBLE"
        val int = if (sqlite) "INTEGER" else "INT"
        val big = if (sqlite) "INTEGER" else "BIGINT"
        source.connection().createStatement().use { st ->
            st.execute(
                "CREATE TABLE IF NOT EXISTS $TABLE (owner ${if (sqlite) "TEXT" else "VARCHAR(36)"} NOT NULL, name_key $text NOT NULL, name $text NOT NULL, " +
                    "world $long NOT NULL, x $real NOT NULL, y $real NOT NULL, z $real NOT NULL, yaw $real NOT NULL, pitch $real NOT NULL, " +
                    "icon $text, private_home $int NOT NULL, slot $int, bed $int NOT NULL, bed_block $long, created $big NOT NULL, " +
                    "PRIMARY KEY (owner, name_key))",
            )
        }
    }

    /** 전부 읽는다(기다린다 — 켤 때만). */
    fun loadAll() {
        val rows = ess.db.call { read(ess.db.sql, null) }
        byOwner.clear()
        for ((owner, homes) in rows.groupBy { it.owner }) byOwner[owner] = homes
        ess.logger.info("홈 ${rows.size}개(${byOwner.size}명)를 읽었습니다")
    }

    /** 한 사람을 DB 에서 다시 읽는다(기다린다 — 비동기 접속 사건에서만). */
    fun reload(owner: UUID) {
        val rows = ess.db.call { read(ess.db.sql, owner) }
        if (rows.isEmpty()) byOwner.remove(owner) else byOwner[owner] = rows
    }

    // --- 쓰기 스레드 -------------------------------------------------------------------------

    private fun read(source: SqlSource, only: UUID?): List<Home> =
        source.connection().prepareStatement(
            "SELECT owner, name, world, x, y, z, yaw, pitch, icon, private_home, slot, bed, bed_block, created FROM $TABLE" +
                if (only != null) " WHERE owner = ?" else "",
        ).use { ps ->
            if (only != null) ps.setString(1, only.toString())
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val owner = runCatching { UUID.fromString(rs.getString(1)) }.getOrNull() ?: continue
                        val slot = rs.getInt(11).takeIf { !rs.wasNull() }
                        add(
                            Home(
                                owner = owner, name = rs.getString(2), world = rs.getString(3),
                                x = rs.getDouble(4), y = rs.getDouble(5), z = rs.getDouble(6), yaw = rs.getFloat(7), pitch = rs.getFloat(8),
                                icon = rs.getString(9), private = rs.getInt(10) != 0, slot = slot, bed = rs.getInt(12) != 0,
                                bedBlock = rs.getString(13), created = rs.getLong(14),
                            ),
                        )
                    }
                }
            }
        }

    /** 쓴다. [insertOnly] 면 같은 열쇠의 줄이 이미 있을 때 아무것도 안 한다(가져오기 — 다른 서버가 쓴 줄을 덮지 않게). */
    private fun write(source: SqlSource, h: Home, insertOnly: Boolean = false) {
        val rest = listOf("name", "world", "x", "y", "z", "yaw", "pitch", "icon", "private_home", "slot", "bed", "bed_block")
        val sql = com.inmc.essentials.store.EssDb.upsertSql(
            source.dialect, TABLE, keys = listOf("owner", "name_key"),
            insertOnly = if (insertOnly) listOf("created") + rest else listOf("created"),
            update = if (insertOnly) emptyList() else rest,
        )
        // ? 순서: 열쇠 → 처음만 → 고칠 열. 두 경우 모두 owner, name_key, created, name … bed_block 차례다.
        source.connection().prepareStatement(sql).use { ps ->
            ps.setString(1, h.owner.toString())
            ps.setString(2, h.key)
            ps.setLong(3, h.created)
            ps.setString(4, h.name)
            ps.setString(5, h.world)
            ps.setDouble(6, h.x)
            ps.setDouble(7, h.y)
            ps.setDouble(8, h.z)
            ps.setDouble(9, h.yaw.toDouble())
            ps.setDouble(10, h.pitch.toDouble())
            ps.setString(11, h.icon)
            ps.setInt(12, if (h.private) 1 else 0)
            if (h.slot != null) ps.setInt(13, h.slot) else ps.setNull(13, Types.INTEGER)
            ps.setInt(14, if (h.bed) 1 else 0)
            ps.setString(15, h.bedBlock)
            ps.executeUpdate()
        }
    }

    /**
     * CMI 가져오기 — 이미 있는 홈은 건드리지 않는다. DB 도 **넣기만**(공용 DB 에서 이 서버의 메모리가 늦어도 다른 서버가 쓴 줄을 덮지 않게):
     * 이미 줄이 있었으면 메모리의 **우리가 넣은 그 사본만** DB 의 줄로 바꾸고 로그를 남긴다 — 그 사람의 목록을 통째로 되읽어 끼우지 않는다
     * (읽는 사이 메인이 넣은 다른 홈이 사라진다. 목록은 불변이라 원자적 compute 로 바꾸면 어느 스레드에서든 안전하다).
     */
    fun insertIfAbsent(home: Home): Boolean {
        if (get(home.owner, home.name) != null) return false
        putMemory(home)
        ess.db.run("홈 가져오기") {
            // 줄 수로는 못 가른다(MySQL Connector/J 는 기본이 '찾은 줄 수') — 먼저 묻고, 넣기는 그래도 넣기만으로.
            val exists = ess.db.sql.connection().prepareStatement("SELECT 1 FROM $TABLE WHERE owner = ? AND name_key = ?").use { ps ->
                ps.setString(1, home.owner.toString())
                ps.setString(2, home.key)
                ps.executeQuery().use { it.next() }
            }
            if (!exists) {
                write(ess.db.sql, home, insertOnly = true)
                return@run
            }
            ess.logger.warning("가져온 홈 '${home.name}'(${home.owner}) 은(는) DB 에 이미 있어 넣지 않았습니다 — 다른 서버가 쓴 것을 둡니다")
            val fromDb = read(ess.db.sql, home.owner).firstOrNull { it.key == home.key }
            // 그 사람의 목록에서 우리가 넣은 그 객체만 DB 의 것으로(없어졌으면 뺀다) — 그 사이 메인이 같은 이름으로 다시 만들었으면(다른 객체) 그것을 둔다.
            byOwner.computeIfPresent(home.owner) { _, old -> old.mapNotNull { if (it === home) fromDb else it }.ifEmpty { null } }
        }
        return true
    }

    companion object {
        const val TABLE = "ess_homes"
    }
}
