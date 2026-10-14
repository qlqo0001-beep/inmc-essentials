package com.inmc.essentials.inventory

import com.inmc.essentials.Ess
import kr.inmc.core.item.ItemCodec
import kr.inmc.core.store.SqlDialect
import kr.inmc.core.store.SqlSource
import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import java.sql.Statement
import java.util.UUID

/**
 * 한 사람의 가방 상태 — 가방 41칸(0~35 가방 · 36~39 갑옷 · 40 왼손) · 엔더 27칸 · 체력·경험치·배고픔·포만·효과.
 * 아이템은 Paper `serializeItemsAsBytes`(DataVersion 포함 — 판이 올라가도 DataFixer 가 올린다).
 */
class InvData(
    val items: Array<ItemStack?>,
    val ender: Array<ItemStack?>,
    val health: Double,
    val level: Int,
    val exp: Float,
    val food: Int,
    val saturation: Float,
    val effects: List<PotionEffect>,
) {
    fun isEmpty(): Boolean = items.all { it == null || it.type.isAir } && ender.all { it == null || it.type.isAir }

    companion object {
        const val INVENTORY_SIZE = 41
        const val ENDER_SIZE = 27

        fun capture(player: Player, blacklist: Set<Material> = emptySet()): InvData {
            fun keep(stack: ItemStack?): ItemStack? = stack?.takeIf { !it.type.isAir && it.type !in blacklist }?.clone()
            val inv = player.inventory
            return InvData(
                items = Array(INVENTORY_SIZE) { keep(inv.getItem(it)) },
                ender = Array(ENDER_SIZE) { keep(player.enderChest.getItem(it)) },
                health = player.health,
                level = player.level,
                exp = player.exp,
                food = player.foodLevel,
                saturation = player.saturation,
                effects = player.activePotionEffects.toList(),
            )
        }

        // 직렬화는 core [ItemCodec](2026-10-15 — inmc-worlds 의 모드별 몸이 같이 쓴다).
        fun encode(items: Array<ItemStack?>): ByteArray = ItemCodec.encode(items)

        fun decode(bytes: ByteArray?, size: Int): Array<ItemStack?> = ItemCodec.decode(bytes, size)

        fun encodeEffects(effects: List<PotionEffect>): String = ItemCodec.encodeEffects(effects)

        fun decodeEffects(text: String?): List<PotionEffect> = ItemCodec.decodeEffects(text)

        /** [data] 를 사람에게 되돌린다(설정이 고른 것만). */
        fun restore(player: Player, data: InvData, s: SavedSettings) {
            if (s.restoreItems) {
                for (i in 0 until INVENTORY_SIZE) player.inventory.setItem(i, data.items[i]?.clone())
                for (i in 0 until ENDER_SIZE) player.enderChest.setItem(i, data.ender[i]?.clone())
            }
            if (s.restoreHp) {
                val max = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
                player.health = data.health.coerceIn(0.5, max)
            }
            if (s.restoreXp) { player.level = data.level; player.exp = data.exp.coerceIn(0f, 0.9999f) }
            if (s.restoreFood) player.foodLevel = data.food.coerceIn(0, 20)
            if (s.restoreSaturation) player.saturation = data.saturation.coerceIn(0f, 20f)
            if (s.restorePotions) {
                player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
                data.effects.forEach { player.addPotionEffect(it) }
            }
        }
    }
}

/**
 * 나갈 때(또는 서버를 끌 때)의 모습 — 가방 41칸·엔더 27칸 바이트, 각각의 지문([InvHash]), 그때의 월드와 Multiverse-Inventories 그룹
 * (없으면 null — [MvInvGroups]), 시각.
 */
class Snapshot(
    val at: Long,
    val items: ByteArray?,
    val ender: ByteArray?,
    val itemsHash: String?,
    val enderHash: String?,
    val world: String?,
    val group: String?,
) {
    fun bytes(kind: PendingKind): ByteArray? = if (kind == PendingKind.INVENTORY) items else ender
    fun hash(kind: PendingKind): String? = if (kind == PendingKind.INVENTORY) itemsHash else enderHash
}

/** 오프라인 편집의 종류 — 가방(41칸)과 엔더(27칸)는 따로 기록하고 따로 적용한다. */
enum class PendingKind(val key: String, val size: Int) {
    INVENTORY("inv", InvData.INVENTORY_SIZE),
    ENDER("ender", InvData.ENDER_SIZE);

    companion object {
        fun of(key: String?): PendingKind? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 다음 접속 때 덮어쓸 편집 하나. [base] = 이 편집이 바탕으로 삼은 모습(처음 편집 때의 나갈 때 모습 — 편집을 거듭해도 그대로), [edited] = 고친 내용.
 * [group] 이 있으면 그 MV 그룹에 있을 때만 적용한다. [claimed] = 적용하려고 DB 에 표를 남겼다(크래시 뒤 두 번 적용 막기).
 */
class PendingEdit(
    val owner: UUID,
    val name: String,
    val kind: PendingKind,
    val base: ByteArray,
    val baseHash: String,
    var edited: ByteArray,
    val world: String?,
    val group: String?,
    var editor: UUID?,
    var editorName: String,
    var at: Long,
    var claimed: Boolean,
)

/** 저장 인벤 한 줄의 머리(목록용 — 아이템 바이트는 [InventoryStore.loadSaved] 에서). */
data class SavedHead(val id: Long, val owner: UUID, val ownerName: String, val created: Long, val savedBy: String, val reason: String, val itemCount: Int)

/**
 * `ess_inventory_snapshots`(나갈 때의 가방 — 사람마다 하나) · `ess_saved_inventories`(저장 인벤 — 사람마다 여럿) ·
 * `ess_inventory_pending`(오프라인 편집 — 사람·종류마다 하나, 다음 접속 때 적용하고 지운다).
 * **모든 메서드는 DB 쓰기 스레드에서**(`ess.db.run { … }` 안) 부른다 — 메인에서 부르지 않는다.
 */
class InventoryStore(private val ess: Ess) {

    fun schema(source: SqlSource) {
        val sqlite = source.dialect == SqlDialect.SQLITE
        val id = if (sqlite) "TEXT" else "VARCHAR(36)"
        val text = if (sqlite) "TEXT" else "VARCHAR(64)"
        val long = if (sqlite) "TEXT" else "TEXT"
        val blob = if (sqlite) "BLOB" else "LONGBLOB"
        val big = if (sqlite) "INTEGER" else "BIGINT"
        val int = if (sqlite) "INTEGER" else "INT"
        val real = if (sqlite) "REAL" else "DOUBLE"
        val auto = if (sqlite) "id INTEGER PRIMARY KEY AUTOINCREMENT" else "id BIGINT AUTO_INCREMENT PRIMARY KEY"
        source.connection().createStatement().use { st ->
            st.execute(
                "CREATE TABLE IF NOT EXISTS $SNAPSHOTS (uuid $id NOT NULL PRIMARY KEY, name $text NOT NULL, at $big NOT NULL, " +
                    "items $blob, ender $blob, level $int NOT NULL, exp $real NOT NULL)",
            )
            st.execute(
                "CREATE TABLE IF NOT EXISTS $SAVED ($auto, owner $id NOT NULL, owner_name $text NOT NULL, created $big NOT NULL, " +
                    "saved_by $text NOT NULL, reason $text NOT NULL, item_count $int NOT NULL, items $blob, ender $blob, hp $real NOT NULL, " +
                    "xp_level $int NOT NULL, xp_progress $real NOT NULL, food $int NOT NULL, saturation $real NOT NULL, effects $long)",
            )
            runCatching { st.execute("CREATE INDEX ${if (sqlite) "IF NOT EXISTS " else ""}ess_saved_inventories_owner ON $SAVED (owner, created)") }
            // 2026-10-10 오프라인 편집 — 나갈 때 모습에 지문·월드·MV 그룹을 더한다(이미 만든 표에는 열을 덧붙인다 — 있으면 실패를 무시).
            for (column in listOf("items_hash $text", "ender_hash $text", "world $text", "mv_group $long")) {
                runCatching { st.execute("ALTER TABLE $SNAPSHOTS ADD COLUMN $column") }
            }
            st.execute(
                "CREATE TABLE IF NOT EXISTS $PENDING (uuid $id NOT NULL, kind $text NOT NULL, name $text NOT NULL, base $blob, base_hash $text NOT NULL, " +
                    "edited $blob, world $text, mv_group $long, editor $id, editor_name $text NOT NULL, at $big NOT NULL, claimed $int NOT NULL, " +
                    "PRIMARY KEY (uuid, kind))",
            )
        }
    }

    // --- 나갈 때의 모습 ------------------------------------------------------------------------------

    fun writeSnapshot(s: Snapshot, id: UUID, name: String, level: Int, exp: Float) {
        val sql = ess.db.upsert(
            SNAPSHOTS, keys = listOf("uuid"),
            update = listOf("name", "at", "items", "ender", "level", "exp", "items_hash", "ender_hash", "world", "mv_group"),
        )
        ess.db.sql.connection().prepareStatement(sql).use { ps ->
            ps.setString(1, id.toString())
            ps.setString(2, name)
            ps.setLong(3, s.at)
            ps.setBytes(4, s.items)
            ps.setBytes(5, s.ender)
            ps.setInt(6, level)
            ps.setDouble(7, exp.toDouble())
            ps.setString(8, s.itemsHash)
            ps.setString(9, s.enderHash)
            ps.setString(10, s.world)
            ps.setString(11, s.group)
            ps.executeUpdate()
        }
    }

    /** 나갈 때 모습. 없으면 null. */
    fun readSnapshot(id: UUID): Snapshot? =
        ess.db.sql.connection().prepareStatement("SELECT at, items, ender, items_hash, ender_hash, world, mv_group FROM $SNAPSHOTS WHERE uuid = ?").use { ps ->
            ps.setString(1, id.toString())
            ps.executeQuery().use { rs ->
                if (rs.next()) Snapshot(rs.getLong(1), rs.getBytes(2), rs.getBytes(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)) else null
            }
        }

    // --- 오프라인 편집(다음 접속 때 덮어쓸 것) ------------------------------------------------------------

    fun upsertPending(p: PendingEdit) {
        val sql = ess.db.upsert(
            PENDING, keys = listOf("uuid", "kind"),
            update = listOf("name", "base", "base_hash", "edited", "world", "mv_group", "editor", "editor_name", "at", "claimed"),
        )
        ess.db.sql.connection().prepareStatement(sql).use { ps ->
            ps.setString(1, p.owner.toString())
            ps.setString(2, p.kind.key)
            ps.setString(3, p.name)
            ps.setBytes(4, p.base)
            ps.setString(5, p.baseHash)
            ps.setBytes(6, p.edited)
            ps.setString(7, p.world)
            ps.setString(8, p.group)
            ps.setString(9, p.editor?.toString())
            ps.setString(10, p.editorName)
            ps.setLong(11, p.at)
            ps.setInt(12, if (p.claimed) 1 else 0)
            ps.executeUpdate()
        }
    }

    /** '적용하려 함' 표 — 적용(메인) 전에 DB 에 남긴다. 크래시 뒤에 이 표가 있으면 적용됐는지 가방을 보고 가린다([OfflineEdits]). */
    fun markPendingClaimed(owner: UUID, kind: PendingKind, claimed: Boolean) {
        ess.db.sql.connection().prepareStatement("UPDATE $PENDING SET claimed = ? WHERE uuid = ? AND kind = ?").use { ps ->
            ps.setInt(1, if (claimed) 1 else 0)
            ps.setString(2, owner.toString())
            ps.setString(3, kind.key)
            ps.executeUpdate()
        }
    }

    fun deletePending(owner: UUID, kind: PendingKind) {
        ess.db.sql.connection().prepareStatement("DELETE FROM $PENDING WHERE uuid = ? AND kind = ?").use { ps ->
            ps.setString(1, owner.toString())
            ps.setString(2, kind.key)
            ps.executeUpdate()
        }
    }

    /** [owner] 가 null 이면 전부. */
    fun readPending(owner: UUID?): List<PendingEdit> =
        ess.db.sql.connection().prepareStatement(
            "SELECT uuid, kind, name, base, base_hash, edited, world, mv_group, editor, editor_name, at, claimed FROM $PENDING" +
                if (owner != null) " WHERE uuid = ?" else "",
        ).use { ps ->
            if (owner != null) ps.setString(1, owner.toString())
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val id = runCatching { UUID.fromString(rs.getString(1)) }.getOrNull() ?: continue
                        val kind = PendingKind.of(rs.getString(2)) ?: continue
                        add(PendingEdit(
                            id, rs.getString(3), kind, rs.getBytes(4) ?: ByteArray(0), rs.getString(5), rs.getBytes(6) ?: ByteArray(0),
                            rs.getString(7), rs.getString(8), rs.getString(9)?.let { runCatching { UUID.fromString(it) }.getOrNull() },
                            rs.getString(10), rs.getLong(11), rs.getInt(12) != 0,
                        ))
                    }
                }
            }
        }

    fun snapshotsSince(cutoff: Long): List<Pair<UUID, String>> =
        ess.db.sql.connection().prepareStatement("SELECT uuid, name FROM $SNAPSHOTS WHERE at >= ?").use { ps ->
            ps.setLong(1, cutoff)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) runCatching { UUID.fromString(rs.getString(1)) }.getOrNull()?.let { add(it to rs.getString(2)) } }
            }
        }

    // --- 저장 인벤 ------------------------------------------------------------------------------------

    /**
     * 넣고, [keep] 개를 넘는 오래된 것을 지운다. 새 id. 백업(불러오기 전)과 그 밖은 **따로** 센다 — 백업이 직접 저장·죽음 저장을
     * 밀어내지 않는다. [protect] 의 줄(지금 불러온 저장)은 이번 정리에서 지우지 않는다.
     */
    fun insertSaved(
        owner: UUID, ownerName: String, created: Long, savedBy: String, reason: String, itemCount: Int,
        items: ByteArray, ender: ByteArray, hp: Double, level: Int, exp: Float, food: Int, saturation: Float, effects: String, keep: Int,
        protect: Long = -1L,
    ): Long {
        val conn = ess.db.sql.connection()
        val id = conn.prepareStatement(
            "INSERT INTO $SAVED (owner, owner_name, created, saved_by, reason, item_count, items, ender, hp, xp_level, xp_progress, food, saturation, effects) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS,
        ).use { ps ->
            ps.setString(1, owner.toString())
            ps.setString(2, ownerName)
            ps.setLong(3, created)
            ps.setString(4, savedBy)
            ps.setString(5, reason)
            ps.setInt(6, itemCount)
            ps.setBytes(7, items)
            ps.setBytes(8, ender)
            ps.setDouble(9, hp)
            ps.setInt(10, level)
            ps.setDouble(11, exp.toDouble())
            ps.setInt(12, food)
            ps.setDouble(13, saturation.toDouble())
            ps.setString(14, effects)
            ps.executeUpdate()
            ps.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else -1L }
        }
        val backup = reason == REASON_BACKUP
        for (old in trimmed(listSaved(owner).filter { (it.reason == REASON_BACKUP) == backup }, keep, protect)) removeSaved(old.id)
        return id
    }

    /** 새것부터. */
    fun listSaved(owner: UUID): List<SavedHead> =
        ess.db.sql.connection().prepareStatement(
            "SELECT id, owner_name, created, saved_by, reason, item_count FROM $SAVED WHERE owner = ? ORDER BY created DESC, id DESC",
        ).use { ps ->
            ps.setString(1, owner.toString())
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(SavedHead(rs.getLong(1), owner, rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getInt(6))) }
            }
        }

    /** 저장 인벤 하나의 머리와 바이트(아이템은 메인에서 푼다). */
    class SavedRow(
        val head: SavedHead, val items: ByteArray?, val ender: ByteArray?, val hp: Double, val level: Int, val exp: Float,
        val food: Int, val saturation: Float, val effects: String?,
    )

    fun loadSaved(id: Long): SavedRow? =
        ess.db.sql.connection().prepareStatement(
            "SELECT owner, owner_name, created, saved_by, reason, item_count, items, ender, hp, xp_level, xp_progress, food, saturation, effects FROM $SAVED WHERE id = ?",
        ).use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return@use null
                val owner = runCatching { UUID.fromString(rs.getString(1)) }.getOrNull() ?: return@use null
                SavedRow(
                    SavedHead(id, owner, rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getInt(6)),
                    rs.getBytes(7), rs.getBytes(8), rs.getDouble(9), rs.getInt(10), rs.getDouble(11).toFloat(), rs.getInt(12),
                    rs.getDouble(13).toFloat(), rs.getString(14),
                )
            }
        }

    fun removeSaved(id: Long): Boolean =
        ess.db.sql.connection().prepareStatement("DELETE FROM $SAVED WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeUpdate() > 0
        }

    fun removeAllSaved(owner: UUID): Int =
        ess.db.sql.connection().prepareStatement("DELETE FROM $SAVED WHERE owner = ?").use { ps ->
            ps.setString(1, owner.toString())
            ps.executeUpdate()
        }

    /** 나갈 때의 모습을 저장 인벤으로 옮긴다(오프라인 `/인벤저장 **`). */
    fun saveFromSnapshot(owner: UUID, name: String, savedBy: String, keep: Int): Boolean {
        val conn = ess.db.sql.connection()
        val row = conn.prepareStatement("SELECT at, items, ender, level, exp FROM $SNAPSHOTS WHERE uuid = ?").use { ps ->
            ps.setString(1, owner.toString())
            ps.executeQuery().use { rs -> if (rs.next()) listOf(rs.getLong(1), rs.getBytes(2), rs.getBytes(3), rs.getInt(4), rs.getDouble(5)) else null }
        } ?: return false
        insertSaved(
            owner, name, System.currentTimeMillis(), savedBy, REASON_SNAPSHOT, -1,
            row[1] as ByteArray? ?: ByteArray(0), row[2] as ByteArray? ?: ByteArray(0), 20.0, row[3] as Int, (row[4] as Double).toFloat(), 20, 5f, "", keep,
        )
        return true
    }

    companion object {
        /** 새것부터 놓인 [heads] 중 지울 것 — [keep] 개를 넘는 오래된 것, [protect] 는 빼고(순수). */
        fun trimmed(heads: List<SavedHead>, keep: Int, protect: Long): List<SavedHead> =
            heads.drop(keep.coerceAtLeast(1)).filter { it.id != protect }

        const val SNAPSHOTS = "ess_inventory_snapshots"
        const val SAVED = "ess_saved_inventories"
        const val PENDING = "ess_inventory_pending"
        const val REASON_MANUAL = "manual"
        const val REASON_DEATH = "death"
        const val REASON_BACKUP = "backup"
        const val REASON_SNAPSHOT = "snapshot"
    }
}
