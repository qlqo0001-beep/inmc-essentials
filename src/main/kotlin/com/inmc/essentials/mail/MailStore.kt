package com.inmc.essentials.mail

import com.inmc.essentials.Ess
import kr.inmc.core.integration.Mail
import kr.inmc.core.store.SqlDialect
import kr.inmc.core.store.SqlSource
import java.sql.Types
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 우편 저장소 — `ess_mail` 전부를 메모리에 들고(켤 때 한 번 읽는다), 바뀌는 순간 DB 쓰기 스레드로 넘긴다(규칙 5: 아이템·돈이라 30초 묶음 금지).
 * core `Mail` 의 모든 메서드는 메인에서 불리므로 세기·목록은 메모리에서 답한다. 넣기는 다른 스레드에서도 올 수 있어 바꾸기는 [lock] 안에서.
 *
 * 공용 DB(mysql)를 여러 서버가 쓰면 다른 서버가 넣은 우편은 그 사람이 **이 서버에 들어오기 직전**(비동기 접속 사건)에 다시 읽는다([reload]).
 * 다시 읽기에 실패하면 그 사람을 "맞추지 못함"([isSynced] = false)으로 두고, 다음 다시 읽기가 될 때까지 받기를 거절한다 — 이 서버의 메모리는
 * 부팅 때 읽은 낡은 사본이라 다른 서버에서 이미 받은 우편이 남아 있을 수 있다(아이템·돈 복사).
 */
class MailStore(private val ess: Ess) {

    /** 주인 → 그 사람의 우편(오래된 것부터, 불변 목록 — 통째로 바꿔 끼운다). */
    private val byOwner = ConcurrentHashMap<UUID, List<MailEntry>>()

    /**
     * 주인 → 이미 받았거나 지운 열쇠 우편의 묘비(`MailRules.spentKey` → 그때). core 우편함이 서버가 죽은 뒤 같은 것을 다시 넘겨도 한 통이게
     * (`ess_mail_keys`). [KEY_DAYS] 일 뒤 지운다.
     */
    private val spent = ConcurrentHashMap<UUID, Map<String, Long>>()

    /** 주인 → 바뀐 횟수. [reload] 가 DB 를 읽는 사이에 메모리가 바뀌었으면 바꿔 끼우지 않고 다시 읽는다. */
    private val versions = ConcurrentHashMap<UUID, Long>()

    private val lock = Any()

    /** 공용 DB 에서 다시 읽지 못한 사람 — 받기를 거절한다(클래스 KDoc). */
    private val unsynced: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    fun isSynced(owner: UUID): Boolean = owner !in unsynced

    fun markUnsynced(owner: UUID) {
        unsynced += owner
    }

    /** 검증기 — 메모리 표시만 되돌린다. */
    internal fun clearUnsynced(owner: UUID) {
        unsynced -= owner
    }

    fun of(owner: UUID): List<MailEntry> = byOwner[owner].orEmpty()

    fun find(owner: UUID, id: String): MailEntry? = of(owner).firstOrNull { it.id == id }

    fun owners(): Set<UUID> = byOwner.keys.toSet()

    fun all(): Sequence<MailEntry> = byOwner.values.asSequence().flatten()

    fun spentKeys(owner: UUID): Set<String> = spent[owner]?.keys.orEmpty()

    /** 모든 사람의 묘비 열쇠 중 [origin] 의 것(열쇠만) — /우편전체 회수가 "마지막으로 보낸 묶음" 을 찾을 때. */
    fun spentKeysOf(origin: String): Sequence<String> {
        val prefix = MailRules.spentKey(origin, "")
        return spent.values.asSequence().flatMap { it.keys.asSequence() }.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
    }

    /** 잠금 안에서만. */
    private fun bump(owner: UUID) {
        versions.merge(owner, 1L, Long::plus)
    }

    /**
     * 넣기 판단([MailRules.admit]) + 넣기. [persist] = false 면 메모리만(검증기). 같은 판단과 넣기가 한 잠금 안에서 — 두 스레드가 같은 key 를
     * 동시에 넣어도 한 통. [force] 는 편지 한도를 넘어서도(콘솔·관리자 편지).
     */
    fun deposit(owner: UUID, parcel: Mail.Parcel, settings: MailSettings, rich: Boolean, persist: Boolean = true, force: Boolean = false): Pair<MailRules.Admit, MailEntry?> {
        synchronized(lock) {
            val existing = of(owner)
            val admit = MailRules.admit(existing, parcel, settings, spentKeys(owner), force)
            if (admit !is MailRules.Admit.Accept) return admit to null
            val entry = MailEntry(UUID.randomUUID().toString(), owner, parcel.copy(expiresAt = MailRules.expiryOf(parcel, settings)), null, rich)
            val dropIds = admit.dropOldest.toSet()
            val dropped = existing.filter { it.id in dropIds }
            byOwner[owner] = existing.filter { it.id !in dropIds } + entry
            bump(owner)
            if (persist) {
                val tombs = tombstone(owner, dropped)
                ess.db.run("우편 넣기") {
                    val source = ess.db.sql
                    if (dropped.isNotEmpty()) delete(source, dropped.map { it.id })
                    insert(source, entry)
                    writeSpent(source, owner, tombs)
                }
            }
            return admit to entry
        }
    }

    /** 바꾼다(받고 남은 것 · 읽음). DB 쓰기를 잠금 안에서 줄 세운다([reload] 의 순서 보장). */
    fun update(entry: MailEntry, persist: Boolean = true) {
        synchronized(lock) {
            val list = of(entry.owner)
            if (list.none { it.id == entry.id }) return
            byOwner[entry.owner] = list.map { if (it.id == entry.id) entry else it }
            bump(entry.owner)
            if (persist) ess.db.run("우편 고치기") { updateRow(ess.db.sql, entry) }
        }
    }

    /**
     * 지운다. 지운 것. 열쇠가 있는 우편은 묘비를 남긴다([tombstone] — 기본은 [persist] 와 같다. 검증기는 메모리 묘비만 남기고 [forgetSpent] 로 걷는다).
     * DB 쓰기를 잠금 안에서 줄 세운다([reload] 의 순서 보장).
     */
    fun remove(owner: UUID, ids: Collection<String>, persist: Boolean = true, tombstone: Boolean = persist): List<MailEntry> {
        if (ids.isEmpty()) return emptyList()
        val wanted = ids.toSet()
        synchronized(lock) {
            val list = of(owner)
            val removed = list.filter { it.id in wanted }
            if (removed.isEmpty()) return emptyList()
            val left = list.filter { it.id !in wanted }
            if (left.isEmpty()) byOwner.remove(owner) else byOwner[owner] = left
            bump(owner)
            val tombs = if (tombstone) tombstone(owner, removed) else emptyMap()
            if (persist) {
                val gone = removed.map { it.id }
                ess.db.run("우편 지우기") {
                    delete(ess.db.sql, gone)
                    writeSpent(ess.db.sql, owner, tombs)
                }
            }
            return removed
        }
    }

    /** 열쇠가 있는 것의 묘비를 메모리에 남기고 DB 에 쓸 것((출처, 열쇠) → 그때)을 돌려준다. 잠금 안에서만. */
    private fun tombstone(owner: UUID, removed: List<MailEntry>): Map<Pair<String, String>, Long> {
        val keyed = removed.filter { it.parcel.key != null }
        if (keyed.isEmpty()) return emptyMap()
        val now = System.currentTimeMillis()
        spent.compute(owner) { _, old -> old.orEmpty() + keyed.associate { MailRules.spentKey(it.parcel.origin, it.parcel.key!!) to now } }
        return keyed.associate { (it.parcel.origin to it.parcel.key!!) to now }
    }

    /** 검증기 — 메모리에만 남긴 묘비([origin] 의 것)를 걷는다. */
    internal fun forgetSpent(owner: UUID, origin: String) {
        val prefix = MailRules.spentKey(origin, "")
        synchronized(lock) {
            spent.computeIfPresent(owner) { _, old -> old.filterKeys { !it.startsWith(prefix) }.ifEmpty { null } }
        }
    }

    /** 만료된 편지를 지운다(보상과 받을 것이 남은 편지는 절대 아님). 지운 수. */
    fun purgeExpired(now: Long, persist: Boolean = true): Int {
        var count = 0
        for (owner in owners()) {
            val ids = of(owner).filter { MailRules.expired(it, now) }.map { it.id }
            count += remove(owner, ids, persist).size
        }
        return count
    }

    /** [KEY_DAYS] 일 지난 묘비를 지운다. */
    fun pruneSpent(now: Long) {
        val cutoff = now - KEY_DAYS * MailRules.DAY
        var pruned = false
        synchronized(lock) {
            for (owner in spent.keys.toList()) {
                spent.computeIfPresent(owner) { _, old ->
                    val kept = old.filterValues { it >= cutoff }
                    if (kept.size != old.size) pruned = true
                    kept.ifEmpty { null }
                }
            }
        }
        if (pruned) ess.db.run("우편 묘비 지우기") {
            ess.db.sql.connection().prepareStatement("DELETE FROM $KEYS WHERE at < ?").use { ps ->
                ps.setLong(1, cutoff)
                ps.executeUpdate()
            }
        }
    }

    // --- 켜기 · 접속 --------------------------------------------------------------------------

    fun schema(source: SqlSource) {
        val sqlite = source.dialect == SqlDialect.SQLITE
        val id = if (sqlite) "TEXT" else "VARCHAR(36)"
        val short = if (sqlite) "TEXT" else "VARCHAR(64)"
        val key = if (sqlite) "TEXT" else "VARCHAR(191)"
        val big = if (sqlite) "INTEGER" else "BIGINT"
        val text = if (sqlite) "TEXT" else "MEDIUMTEXT"
        source.connection().createStatement().use { st ->
            st.execute(
                "CREATE TABLE IF NOT EXISTS $TABLE (id $id NOT NULL PRIMARY KEY, owner $id NOT NULL, kind $short NOT NULL, origin $short NOT NULL, " +
                    "source $text NOT NULL, sender_id $id, sender_name $short, body $text, payload $text, at $big NOT NULL, expires_at $big, " +
                    "mail_key $key, read_at $big, UNIQUE (owner, origin, mail_key))",
            )
            st.execute(
                "CREATE TABLE IF NOT EXISTS $KEYS (owner $id NOT NULL, origin $short NOT NULL, mail_key $key NOT NULL, at $big NOT NULL, " +
                    "PRIMARY KEY (owner, origin, mail_key))",
            )
        }
    }

    /** 전부 읽는다(기다린다 — 켤 때만). 못 읽은 줄은 로그로 남기고 DB 에는 그대로 둔다(지우지 않는다). */
    fun loadAll() {
        val (rows, keys) = ess.db.call { read(ess.db.sql, null) to readSpent(ess.db.sql, null) }
        synchronized(lock) {
            byOwner.clear()
            for ((owner, entries) in rows.groupBy { it.owner }) byOwner[owner] = entries.sortedBy { it.parcel.at }
            spent.clear()
            spent.putAll(keys)
        }
        ess.logger.info("우편 ${rows.size}통(${byOwner.size}명) · 받은 열쇠 묘비 ${keys.values.sumOf { it.size }}개를 읽었습니다")
    }

    /**
     * 한 사람을 DB 에서 다시 읽는다(기다린다 — 메인이 아닌 곳에서만: 비동기 접속 사건·비동기 작업). 읽는 사이에 이 서버에서 그 사람의 우편이
     * 바뀌었으면(넣기·받기·지우기) 바꿔 끼우지 않고 다시 읽는다 — 바꾼 쪽의 DB 쓰기는 잠금 안에서 줄을 섰으므로 다음 읽기는 그것을 본다.
     * 맞췄으면 true. 세 번 다 바뀌면 "맞추지 못함" 으로 두고 false(받기 거절 — [isSynced]). DB 가 던지면 그대로 던진다(부른 쪽이 [markUnsynced]).
     */
    fun reload(owner: UUID): Boolean {
        repeat(3) {
            val before = synchronized(lock) { versions[owner] ?: 0L }
            val (rows, keys) = ess.db.call { read(ess.db.sql, owner) to readSpent(ess.db.sql, owner) }
            synchronized(lock) {
                if ((versions[owner] ?: 0L) == before) {
                    if (rows.isEmpty()) byOwner.remove(owner) else byOwner[owner] = rows.sortedBy { it.parcel.at }
                    val mine = keys[owner]
                    if (mine.isNullOrEmpty()) spent.remove(owner) else spent[owner] = mine
                    unsynced -= owner
                    return true
                }
            }
        }
        unsynced += owner
        ess.logger.warning("공용 DB 에서 $owner 의 우편을 다시 읽는 동안 이 서버에서 계속 바뀌었습니다 — 다시 읽을 때까지 받기를 막습니다")
        return false
    }

    // --- 쓰기 스레드 -------------------------------------------------------------------------

    private fun read(source: SqlSource, only: UUID?): List<MailEntry> =
        source.connection().prepareStatement(
            "SELECT id, owner, kind, origin, source, sender_id, sender_name, body, payload, at, expires_at, mail_key, read_at FROM $TABLE" +
                if (only != null) " WHERE owner = ?" else "",
        ).use { ps ->
            if (only != null) ps.setString(1, only.toString())
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val id = rs.getString(1)
                        val owner = runCatching { UUID.fromString(rs.getString(2)) }.getOrNull()
                        val kind = runCatching { Mail.Kind.valueOf(rs.getString(3)) }.getOrNull()
                        if (owner == null || kind == null) {
                            ess.logger.warning("우편 한 줄을 읽지 못했습니다(그대로 둡니다): id=$id owner=${rs.getString(2)} kind=${rs.getString(3)}")
                            continue
                        }
                        val payload = MailRules.decode(rs.getString(9))
                        if (payload.bad.isNotEmpty()) ess.logger.warning("우편 $id 의 일부를 읽지 못했습니다: ${payload.bad}")
                        val expires = rs.getLong(11).takeIf { !rs.wasNull() }
                        val read = rs.getLong(13).takeIf { !rs.wasNull() }
                        val parcel = Mail.Parcel(
                            kind = kind, origin = rs.getString(4), source = rs.getString(5),
                            senderId = rs.getString(6)?.let { runCatching { UUID.fromString(it) }.getOrNull() }, senderName = rs.getString(7),
                            text = rs.getString(8), items = payload.items, money = payload.money, moneyBy = payload.moneyBy, commands = payload.commands,
                            at = rs.getLong(10), expiresAt = expires, key = rs.getString(12),
                        )
                        add(MailEntry(id, owner, parcel, read, payload.rich))
                    }
                }
            }
        }

    private fun insert(source: SqlSource, e: MailEntry) {
        val p = e.parcel
        source.connection().prepareStatement(
            "INSERT INTO $TABLE (id, owner, kind, origin, source, sender_id, sender_name, body, payload, at, expires_at, mail_key, read_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { ps ->
            ps.setString(1, e.id)
            ps.setString(2, e.owner.toString())
            ps.setString(3, p.kind.name)
            ps.setString(4, p.origin.take(64))
            ps.setString(5, p.source)
            ps.setString(6, p.senderId?.toString())
            ps.setString(7, p.senderName?.take(64))
            ps.setString(8, p.text)
            ps.setString(9, MailRules.encode(p, e.rich))
            ps.setLong(10, p.at)
            if (p.expiresAt != null) ps.setLong(11, p.expiresAt!!) else ps.setNull(11, Types.BIGINT)
            ps.setString(12, p.key?.take(191))
            if (e.readAt != null) ps.setLong(13, e.readAt) else ps.setNull(13, Types.BIGINT)
            ps.executeUpdate()
        }
    }

    private fun updateRow(source: SqlSource, e: MailEntry) {
        source.connection().prepareStatement("UPDATE $TABLE SET payload = ?, read_at = ?, expires_at = ? WHERE id = ?").use { ps ->
            ps.setString(1, MailRules.encode(e.parcel, e.rich))
            if (e.readAt != null) ps.setLong(2, e.readAt) else ps.setNull(2, Types.BIGINT)
            if (e.parcel.expiresAt != null) ps.setLong(3, e.parcel.expiresAt!!) else ps.setNull(3, Types.BIGINT)
            ps.setString(4, e.id)
            ps.executeUpdate()
        }
    }

    private fun delete(source: SqlSource, ids: Collection<String>) {
        source.connection().prepareStatement("DELETE FROM $TABLE WHERE id = ?").use { ps ->
            for (id in ids) {
                ps.setString(1, id)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    private fun readSpent(source: SqlSource, only: UUID?): Map<UUID, Map<String, Long>> =
        source.connection().prepareStatement(
            "SELECT owner, origin, mail_key, at FROM $KEYS" + if (only != null) " WHERE owner = ?" else "",
        ).use { ps ->
            if (only != null) ps.setString(1, only.toString())
            ps.executeQuery().use { rs ->
                val out = HashMap<UUID, HashMap<String, Long>>()
                while (rs.next()) {
                    val owner = runCatching { UUID.fromString(rs.getString(1)) }.getOrNull() ?: continue
                    out.getOrPut(owner) { HashMap() }[MailRules.spentKey(rs.getString(2), rs.getString(3))] = rs.getLong(4)
                }
                out
            }
        }

    private fun writeSpent(source: SqlSource, owner: UUID, tombs: Map<Pair<String, String>, Long>) {
        if (tombs.isEmpty()) return
        source.connection().prepareStatement(ess.db.upsert(KEYS, listOf("owner", "origin", "mail_key"), listOf("at"))).use { ps ->
            for ((pair, at) in tombs) {
                ps.setString(1, owner.toString())
                ps.setString(2, pair.first.take(64))
                ps.setString(3, pair.second.take(191))
                ps.setLong(4, at)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    companion object {
        const val TABLE = "ess_mail"
        const val KEYS = "ess_mail_keys"

        /** 묘비를 쥐는 일수. */
        const val KEY_DAYS = 30L
    }
}
