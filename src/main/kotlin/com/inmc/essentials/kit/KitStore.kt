package com.inmc.essentials.kit

import com.inmc.essentials.Ess
import kr.inmc.core.store.SqlDialect
import kr.inmc.core.store.SqlSource
import org.bukkit.Bukkit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 킷 정의 `ess_kits(id, data)` — 메모리에 전부(켤 때 한 번 읽음). 사용 기록 `ess_kit_usage(uuid, kit, last_used, uses)` — **접속한 사람의 것만**
 * 메모리에: 들어오기 직전(비동기 사건)에 그 사람 것을 읽고 나가면 내린다(받는 중이면 끝난 뒤). 공용 DB 여도 같은 길이라 서버끼리 맞는다.
 * 사용 기록은 **주기 전에** DB 에 쓴다(쓰기 스레드에서 끝난 뒤 메인에서 준다 — [KitService.claim]).
 */
class KitStore(private val ess: Ess) {

    private val kits = ConcurrentHashMap<String, Kit>()
    private val usage = ConcurrentHashMap<UUID, ConcurrentHashMap<String, KitUsage>>()

    fun all(): List<Kit> = kits.values.sortedWith(compareBy({ if (it.slot < 0) Int.MAX_VALUE else it.slot }, { it.id }))

    fun get(id: String): Kit? = kits[id.lowercase()]

    fun usage(player: UUID, kit: String): KitUsage? = usage[player]?.get(kit.lowercase())

    fun usages(player: UUID): Map<String, KitUsage> = usage[player].orEmpty()

    // --- 정의 ----------------------------------------------------------------------------------------

    fun save(kit: Kit) {
        kits[kit.id] = kit
        val text = kit.toYaml()
        ess.db.run("킷 저장") {
            ess.db.sql.connection().prepareStatement(ess.db.upsert(KITS, keys = listOf("id"), update = listOf("data"))).use { ps ->
                ps.setString(1, kit.id)
                ps.setString(2, text)
                ps.executeUpdate()
            }
        }
    }

    fun remove(id: String): Boolean {
        if (kits.remove(id) == null) return false
        ess.db.run("킷 지우기") {
            ess.db.sql.connection().prepareStatement("DELETE FROM $KITS WHERE id = ?").use { it.setString(1, id); it.executeUpdate() }
        }
        return true
    }

    /** 검증기용 — 메모리만. */
    internal fun putMemory(kit: Kit) { kits[kit.id] = kit }

    internal fun removeMemory(id: String) { kits.remove(id) }

    // --- 사용 기록 ------------------------------------------------------------------------------------

    /** 메모리에 먼저 적는다(같은 틱의 두 번 누르기를 막는다). 되돌릴 때 [restoreMemory]. 이전 값. */
    fun markMemory(player: UUID, kit: String, value: KitUsage?): KitUsage? {
        val map = usage.computeIfAbsent(player) { ConcurrentHashMap() }
        return if (value == null) map.remove(kit) else map.put(kit, value)
    }

    /** 되돌리기 — 그 사람의 기록이 이미 내려졌으면(나감) 아무것도 하지 않는다(다시 올리지 않게). */
    fun restoreMemory(player: UUID, kit: String, previous: KitUsage?) {
        if (!usage.containsKey(player)) return
        markMemory(player, kit, previous)
    }

    /** DB 쓰기 스레드에서. */
    fun writeUsage(player: UUID, kit: String, value: KitUsage) {
        ess.db.sql.connection().prepareStatement(ess.db.upsert(USAGE, keys = listOf("uuid", "kit"), update = listOf("last_used", "uses"))).use { ps ->
            ps.setString(1, player.toString())
            ps.setString(2, kit)
            ps.setLong(3, value.lastUsed)
            ps.setInt(4, value.uses)
            ps.executeUpdate()
        }
    }

    /**
     * DB 쓰기 스레드에서 — 더 새것만 남기며 합친다(CMI 가져오기: 접속하지 않은 사람의 기록은 메모리에 없으니 DB 에서 견준다).
     * 마지막 받은 때는 더 늦은 것, 횟수는 더 큰 것.
     */
    fun mergeUsage(player: UUID, kit: String, value: KitUsage) {
        val head = "INSERT INTO $USAGE (uuid, kit, last_used, uses) VALUES (?, ?, ?, ?) "
        val sql = when (ess.db.dialect) {
            SqlDialect.SQLITE -> head + "ON CONFLICT (uuid, kit) DO UPDATE SET last_used = MAX(last_used, excluded.last_used), uses = MAX(uses, excluded.uses)"
            SqlDialect.MYSQL -> head + "ON DUPLICATE KEY UPDATE last_used = GREATEST(last_used, VALUES(last_used)), uses = GREATEST(uses, VALUES(uses))"
        }
        ess.db.sql.connection().prepareStatement(sql).use { ps ->
            ps.setString(1, player.toString())
            ps.setString(2, kit)
            ps.setLong(3, value.lastUsed)
            ps.setInt(4, value.uses)
            ps.executeUpdate()
        }
    }

    /**
     * 기록 지우기 — [player] 가 null 이면 모든 사람, [kit] 이 null 이면 모든 킷. 메모리(접속자) + DB(쓰기 스레드). DB 에서 바뀐 줄 수를
     * 메인에서 [done] 으로(접속하지 않은 사람의 기록은 DB 에만 있다).
     */
    fun reset(player: UUID?, kit: String?, cooldownOnly: Boolean, usesOnly: Boolean, done: (Int) -> Unit = {}) {
        val targets = if (player != null) listOfNotNull(usage[player]?.let { player to it }) else usage.entries.map { it.key to it.value }
        for ((_, map) in targets) {
            for (key in map.keys.toList()) {
                if (kit != null && key != kit) continue
                if (key == FIRST_JOIN_MARK) continue
                val old = map[key] ?: continue
                when {
                    cooldownOnly -> map[key] = old.copy(lastUsed = 0L)
                    usesOnly -> map[key] = old.copy(uses = 0)
                    else -> map.remove(key)
                }
            }
        }
        ess.db.run("킷 기록 초기화") {
            val where = buildList {
                if (player != null) add("uuid = ?")
                if (kit != null) add("kit = ?")
                add("kit <> ?")
            }.joinToString(" AND ")
            val sql = when {
                cooldownOnly -> "UPDATE $USAGE SET last_used = 0 WHERE $where"
                usesOnly -> "UPDATE $USAGE SET uses = 0 WHERE $where"
                else -> "DELETE FROM $USAGE WHERE $where"
            }
            val n = ess.db.sql.connection().prepareStatement(sql).use { ps ->
                var i = 1
                if (player != null) ps.setString(i++, player.toString())
                if (kit != null) ps.setString(i++, kit)
                ps.setString(i, FIRST_JOIN_MARK)
                ps.executeUpdate()
            }
            Bukkit.getScheduler().runTask(ess.plugin, Runnable { done(n) })
        }
    }

    /**
     * 킷 id 바꾸기 — 새 id 로 정의를 옮기고 옛 정의를 지우고, 사용 기록(쿨타임·횟수·1회용)도 새 id 로 옮긴다(메모리 + DB 한 번에).
     * 새 id 에 남아 있던 옛 기록(지운 킷의 것)은 먼저 지운다 — 기본 열쇠(uuid, kit) 충돌 없이. 새 킷.
     */
    fun rename(kit: Kit, newId: String): Kit {
        val moved = kit.copyOf(newId)
        val oldId = kit.id
        kits.remove(oldId)
        kits[newId] = moved
        for (map in usage.values) {
            map.remove(newId)
            map.remove(oldId)?.let { map[newId] = it }
        }
        val text = moved.toYaml()
        ess.db.run("킷 id 바꾸기") {
            val conn = ess.db.sql.connection()
            conn.prepareStatement(ess.db.upsert(KITS, keys = listOf("id"), update = listOf("data"))).use { ps ->
                ps.setString(1, newId)
                ps.setString(2, text)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM $USAGE WHERE kit = ?").use { it.setString(1, newId); it.executeUpdate() }
            conn.prepareStatement("UPDATE $USAGE SET kit = ? WHERE kit = ?").use { it.setString(1, newId); it.setString(2, oldId); it.executeUpdate() }
            conn.prepareStatement("DELETE FROM $KITS WHERE id = ?").use { it.setString(1, oldId); it.executeUpdate() }
        }
        return moved
    }

    // --- 켜기 · 접속 --------------------------------------------------------------------------------

    fun schema(source: SqlSource) {
        val sqlite = source.dialect == SqlDialect.SQLITE
        val id = if (sqlite) "TEXT" else "VARCHAR(64)"
        val uuid = if (sqlite) "TEXT" else "VARCHAR(36)"
        val text = if (sqlite) "TEXT" else "MEDIUMTEXT"
        val big = if (sqlite) "INTEGER" else "BIGINT"
        val int = if (sqlite) "INTEGER" else "INT"
        source.connection().createStatement().use { st ->
            st.execute("CREATE TABLE IF NOT EXISTS $KITS (id $id NOT NULL PRIMARY KEY, data $text NOT NULL)")
            st.execute("CREATE TABLE IF NOT EXISTS $USAGE (uuid $uuid NOT NULL, kit $id NOT NULL, last_used $big NOT NULL, uses $int NOT NULL, PRIMARY KEY (uuid, kit))")
        }
    }

    /**
     * 킷 정의 전부와 **지금 접속한 사람의** 사용 기록을 읽는다(기다린다 — 켤 때만). 못 읽는 킷은 로그로 남기고 건너뛴다.
     * 사용 기록은 사람마다 들어올 때(비동기 접속 사건) 읽고 나갈 때 내린다 — 서버가 오래 돌아도 지금 있는 사람 것만 메모리에.
     */
    fun loadAll(online: Collection<UUID>) {
        val (defs, rows) = ess.db.call {
            val defs = ess.db.sql.connection().prepareStatement("SELECT id, data FROM $KITS").use { ps ->
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) } }
            }
            defs to online.flatMap { readUsage(it) }
        }
        kits.clear()
        for ((id, data) in defs) {
            runCatching { Kit.fromYaml(id, data) }
                .onSuccess { kits[id] = it }
                .onFailure { ess.logger.severe("킷 '$id' 을(를) 읽지 못했습니다(DB ess_kits) — 건너뜁니다: $it") }
        }
        usage.clear()
        for ((player, kit, value) in rows) usage.computeIfAbsent(player) { ConcurrentHashMap() }[kit] = value
        ess.logger.info("킷 ${kits.size}개 · 사용 기록 ${rows.size}줄을 읽었습니다")
    }

    /** 한 사람을 DB 에서 읽는다(기다린다 — 비동기 접속 사건에서만). 쓰기 스레드가 줄을 서므로 그 사람의 기다리던 쓰기 뒤에 읽는다. */
    fun reload(player: UUID) {
        val rows = ess.db.call { readUsage(player) }
        val map = ConcurrentHashMap<String, KitUsage>()
        for ((_, kit, value) in rows) map[kit] = value
        usage[player] = map
    }

    /** 나간 사람의 기록을 메모리에서 내린다(DB 에는 남는다). */
    fun unload(player: UUID) {
        usage.remove(player)
    }

    /** 그 사람의 기록이 메모리에 올라 있나 — 아니면 킷을 주지 않는다(1회용을 다시 받지 않게). */
    fun isLoaded(player: UUID): Boolean = usage.containsKey(player)

    /** 메인에서 — 쓰기 스레드로 읽어 올린다(접속 사건에서 읽지 못했을 때). 이미 메모리에 쓴 것(같은 틱의 받기)은 덮지 않는다. */
    fun loadLater(player: UUID) {
        ess.db.run("킷 기록 다시 읽기") {
            val rows = readUsage(player)
            val map = ConcurrentHashMap<String, KitUsage>()
            for ((_, kit, value) in rows) map[kit] = value
            Bukkit.getScheduler().runTask(ess.plugin, Runnable {
                if (Bukkit.getPlayer(player) == null) return@Runnable
                usage.merge(player, map) { mine, read -> read.putAll(mine); read }
            })
        }
    }

    /** 메모리에 사용 기록이 올라 있는 사람 수(검증·로그용). */
    fun loadedPlayers(): Int = usage.size

    private fun readUsage(only: UUID): List<Triple<UUID, String, KitUsage>> =
        ess.db.sql.connection().prepareStatement("SELECT uuid, kit, last_used, uses FROM $USAGE WHERE uuid = ?").use { ps ->
            ps.setString(1, only.toString())
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val id = runCatching { UUID.fromString(rs.getString(1)) }.getOrNull() ?: continue
                        add(Triple(id, rs.getString(2), KitUsage(rs.getLong(3), rs.getInt(4))))
                    }
                }
            }
        }

    companion object {
        const val KITS = "ess_kits"
        const val USAGE = "ess_kit_usage"

        /** 첫 접속 킷을 이미 받았다는 표(킷 이름 자리에). */
        const val FIRST_JOIN_MARK = "*first-join"
    }
}
