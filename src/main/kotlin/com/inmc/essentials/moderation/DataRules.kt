package com.inmc.essentials.moderation

import org.bukkit.configuration.file.YamlConfiguration

/**
 * 데이터 도구(교환·삭제·열 초기화·정리)의 순수 규칙 — `DataToolsTest`. 어느 열이 "사람" 인가 · 예약 내용(YAML) · 기본값 글자 읽기.
 */
object DataRules {

    data class Column(val table: String, val column: String) {
        override fun toString(): String = "$table.$column"

        companion object {
            fun parse(text: String): Column? {
                val p = text.trim().split('.')
                if (p.size != 2 || p.any { !SAFE.matches(it) }) return null
                return Column(p[0].lowercase(), p[1].lowercase())
            }
        }
    }

    /** 표·열 이름으로 쓸 수 있는 글자(문장에 이름을 그대로 넣으므로 — 메타데이터에서 온 이름도 거른다). */
    val SAFE = Regex("""^[A-Za-z0-9_]{1,64}$""")

    /**
     * 사람(uuid)을 담는 열 — `uuid` · `owner` · `player` · `…_uuid`. "준 사람"·"보낸 사람" 같은 열은 일부러 이 모양을 피해 짓는다(moderation 의
     * `source`·`author`) — 그 사람을 지울 때 남에게 준 경고까지 지우지 않게. 다른 이름은 설정 `data-tools.extra-columns` 로 더한다.
     */
    fun isPlayerColumn(name: String): Boolean {
        val n = name.lowercase()
        return n == "uuid" || n == "owner" || n == "player" || n.endsWith("_uuid")
    }

    /** 교환에서 빼는 표 — 계정 자체의 기록(이름·접속·IP)은 사람을 따라가지 않는다. */
    val IDENTITY_TABLES = setOf("ess_users", ModStore.IPS, ModStore.LOCKIP, ModStore.PENDING)

    /** 어느 데이터 도구에서도 빼는 표(예약 자체). */
    val NEVER = setOf(ModStore.PENDING)

    /**
     * [tables](표 → 열 목록)에서 사람 열을 고른다. [extra] = 설정의 `표.열`(있는 것만), [exclude] = 뺄 표, [identity] 면 계정 표도 뺀다.
     */
    fun playerColumns(tables: Map<String, List<String>>, extra: List<String>, exclude: Set<String>, identity: Boolean): List<Column> {
        val out = LinkedHashSet<Column>()
        for ((table, columns) in tables) {
            val t = table.lowercase()
            if (!t.startsWith("ess_") || t in NEVER || t in exclude || (identity && t in IDENTITY_TABLES)) continue
            if (!SAFE.matches(t)) continue
            for (c in columns) if (isPlayerColumn(c) && SAFE.matches(c)) out += Column(t, c.lowercase())
        }
        for (text in extra) {
            val col = Column.parse(text) ?: continue
            if (col.table in NEVER || col.table in exclude || (identity && col.table in IDENTITY_TABLES)) continue
            if (tables.entries.any { (t, cs) -> t.equals(col.table, true) && cs.any { it.equals(col.column, true) } }) out += col
        }
        return out.toList()
    }

    /** 메타데이터의 COLUMN_DEF(`0` · `'abc'` · `NULL` · null) → 바인딩할 값. [Unknown] = 읽을 수 없는 식(함수 등). */
    sealed interface Default {
        data object Null : Default
        data class Value(val value: Any) : Default
        data object Unknown : Default
    }

    fun parseDefault(raw: String?, nullable: Boolean): Default {
        val text = raw?.trim()
        if (text == null || text.isEmpty() || text.equals("NULL", true)) return if (nullable) Default.Null else Default.Unknown
        val unquoted = text.removeSurrounding("(", ")")
        if (unquoted.length >= 2 && unquoted.startsWith("'") && unquoted.endsWith("'")) return Default.Value(unquoted.substring(1, unquoted.length - 1).replace("''", "'"))
        unquoted.toLongOrNull()?.let { return Default.Value(it) }
        unquoted.toDoubleOrNull()?.let { return Default.Value(it) }
        return Default.Unknown
    }

    // --- 예약 내용 ---------------------------------------------------------------------------------

    enum class Kind(val label: String) { SWITCH("데이터 교환"), REMOVE("유저 삭제"), RESET("열 초기화"), PURGE("미접속 정리") }

    /**
     * 예약 하나. 미리 보기에서 정한 표·열을 그대로 들고 간다 — 확인한 것과 다른 것이 적용되지 않게.
     * [a]/[b] = 교환 · [ids] = 삭제·정리 대상 · [reset] = 초기화할 열, [value] 는 그 기본값(null = 비움), [where] 는 사람 열과 uuid, [world] ·
     * [cutoff] = 정리 때 다시 확인할 마지막 접속 기준 · [folders] = 옮기거나 지울 바닐라 폴더 · [deleteFiles].
     */
    data class Op(
        val kind: Kind,
        val columns: List<Column> = emptyList(),
        val a: String? = null,
        val b: String? = null,
        val ids: List<String> = emptyList(),
        val reset: Column? = null,
        val value: Any? = null,
        val where: Column? = null,
        val whereId: String? = null,
        val world: String? = null,
        val cutoff: Long? = null,
        val folders: List<String> = emptyList(),
        val deleteFiles: Boolean = false,
    ) {
        fun describe(): String = when (kind) {
            Kind.SWITCH -> "${kind.label} $a ↔ $b"
            Kind.REMOVE -> "${kind.label} ${ids.size}명"
            Kind.RESET -> "${kind.label} $reset" + (whereId?.let { " (사람 $it)" } ?: "") + (world?.let { " (월드 $it)" } ?: "")
            Kind.PURGE -> "${kind.label} ${ids.size}명" + if (folders.isNotEmpty()) " + 바닐라 파일" else ""
        }
    }

    fun encode(op: Op): String = YamlConfiguration().apply {
        set("kind", op.kind.name)
        set("columns", op.columns.map { it.toString() })
        set("a", op.a)
        set("b", op.b)
        set("ids", op.ids)
        set("reset", op.reset?.toString())
        set("value-type", when (op.value) { null -> "null"; is Long -> "long"; is Double -> "double"; else -> "string" })
        set("value", op.value?.toString())
        set("where", op.where?.toString())
        set("where-id", op.whereId)
        set("world", op.world)
        set("cutoff", op.cutoff)
        set("folders", op.folders)
        set("delete-files", op.deleteFiles)
    }.saveToString()

    fun decode(text: String): Op? {
        val y = YamlConfiguration()
        runCatching { y.loadFromString(text) }.getOrElse { return null }
        val kind = runCatching { Kind.valueOf(y.getString("kind").orEmpty()) }.getOrNull() ?: return null
        val raw = y.getString("value")
        val value: Any? = when (y.getString("value-type")) {
            "long" -> raw?.toLongOrNull()
            "double" -> raw?.toDoubleOrNull()
            "string" -> raw
            else -> null
        }
        return Op(
            kind = kind,
            columns = y.getStringList("columns").mapNotNull { Column.parse(it) },
            a = y.getString("a"),
            b = y.getString("b"),
            ids = y.getStringList("ids"),
            reset = y.getString("reset")?.let { Column.parse(it) },
            value = value,
            where = y.getString("where")?.let { Column.parse(it) },
            whereId = y.getString("where-id"),
            world = y.getString("world"),
            cutoff = if (y.contains("cutoff")) y.getLong("cutoff") else null,
            folders = y.getStringList("folders"),
            deleteFiles = y.getBoolean("delete-files"),
        )
    }

    /** IN (?, ?, …) 을 이만큼씩 나눈다(SQLite 자리 수 한도 아래로). */
    const val CHUNK = 400
}
