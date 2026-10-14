package com.inmc.essentials.moderation

import com.inmc.essentials.moderation.DataRules.Column
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 데이터 도구의 순수 규칙 — 사람 열 고르기 · 기본값 읽기 · 예약 내용 왕복 · CMI 경고·메모 형식. */
class DataToolsTest {

    private val tables = mapOf(
        "ess_users" to listOf("uuid", "name", "last_join"),
        "ess_homes" to listOf("owner", "name_key", "x"),
        "ess_chat_ignores" to listOf("owner", "target", "at"),
        "ess_moderation_warnings" to listOf("serial", "uuid", "source", "reason"),
        "ess_moderation_ips" to listOf("uuid", "ip"),
        "ess_moderation_pending" to listOf("serial", "payload"),
        "ess_kits_usage" to listOf("uuid", "kit"),
        "ess_teleport_spots" to listOf("uuid", "kind", "world"),
        "other_table" to listOf("uuid"),
    )

    @Test
    fun `사람 열 - uuid owner player _uuid 만, 준 사람 열과 남의 표는 뺀다`() {
        val cols = DataRules.playerColumns(tables, emptyList(), emptySet(), identity = false).map { it.toString() }.toSet()
        assertEquals(
            setOf("ess_users.uuid", "ess_homes.owner", "ess_chat_ignores.owner", "ess_moderation_warnings.uuid", "ess_moderation_ips.uuid",
                "ess_kits_usage.uuid", "ess_teleport_spots.uuid"),
            cols,
        )
    }

    @Test
    fun `교환은 계정 표(이름·IP)를 빼고 설정의 열을 더한다`() {
        val cols = DataRules.playerColumns(tables, listOf("ess_chat_ignores.target", "ess_nothing.x", "bad"), setOf("ess_kits_usage"), identity = true)
            .map { it.toString() }.toSet()
        assertTrue("ess_users.uuid" !in cols)
        assertTrue("ess_moderation_ips.uuid" !in cols)
        assertTrue("ess_kits_usage.uuid" !in cols, "뺀 표")
        assertTrue("ess_chat_ignores.target" in cols, "설정으로 더한 열")
        assertTrue(cols.none { it.startsWith("ess_nothing") }, "없는 표는 무시")
    }

    @Test
    fun `표 열 이름 거르기`() {
        assertEquals(Column("ess_homes", "icon"), Column.parse("ESS_HOMES.icon"))
        assertNull(Column.parse("ess_homes.icon; DROP TABLE x"))
        assertNull(Column.parse("a.b.c"))
    }

    @Test
    fun `기본값 글 읽기`() {
        assertEquals(DataRules.Default.Value(0L), DataRules.parseDefault("0", nullable = false))
        assertEquals(DataRules.Default.Value("abc"), DataRules.parseDefault("'abc'", nullable = false))
        assertEquals(DataRules.Default.Value("it's"), DataRules.parseDefault("'it''s'", nullable = false))
        assertEquals(DataRules.Default.Value(1.5), DataRules.parseDefault("(1.5)", nullable = false))
        assertEquals(DataRules.Default.Null, DataRules.parseDefault(null, nullable = true))
        assertEquals(DataRules.Default.Unknown, DataRules.parseDefault(null, nullable = false))
        assertEquals(DataRules.Default.Unknown, DataRules.parseDefault("CURRENT_TIMESTAMP", nullable = true))
    }

    @Test
    fun `예약 내용 왕복`() {
        val ops = listOf(
            DataRules.Op(DataRules.Kind.SWITCH, columns = listOf(Column("ess_homes", "owner")), a = "a-uuid", b = "b-uuid"),
            DataRules.Op(DataRules.Kind.PURGE, columns = listOf(Column("ess_users", "uuid")), ids = listOf("x", "y"), cutoff = 123L,
                folders = listOf("C:/world/playerdata"), deleteFiles = true),
            DataRules.Op(DataRules.Kind.RESET, reset = Column("ess_player_state", "god"), value = 0L, where = Column("ess_player_state", "uuid"), whereId = "u"),
            DataRules.Op(DataRules.Kind.RESET, reset = Column("ess_homes", "icon"), value = null, world = "world"),
            DataRules.Op(DataRules.Kind.RESET, reset = Column("ess_x", "y"), value = "글자"),
        )
        for (op in ops) assertEquals(op, DataRules.decode(DataRules.encode(op)))
        assertNull(DataRules.decode("kind: NOPE"))
    }

    @Test
    fun `CMI 경고 - JSON 때 → 준 사람·분류·사유`() {
        val list = assertNotNull(CmiModImport.parseWarnings("""{"1700000000000":["Zrips","Swear","bad words"],"1700000001000":["Admin","just reason"],"1700000002000":["Mod"]}"""))
        assertEquals(3, list.size)
        assertEquals(CmiModImport.CmiWarning(1_700_000_000_000, "Zrips", "Swear", "bad words"), list[0])
        assertEquals(CmiModImport.CmiWarning(1_700_000_001_000, "Admin", null, "just reason"), list[1])
        assertEquals(CmiModImport.CmiWarning(1_700_000_002_000, "Mod", null, null), list[2])
        assertNull(CmiModImport.parseWarnings("그냥 글"))
        assertNull(CmiModImport.parseWarnings("""{"not-a-time":["x"]}"""))
    }

    @Test
    fun `CMI 메모 - JSON · 쓴 사람 때 내용 · 그냥 글`() {
        assertEquals(listOf(CmiModImport.CmiNote(1_700_000_000_000, "Zrips", "watch him")), CmiModImport.parseNotes("""{"1700000000000":["Zrips","watch him"]}"""))
        assertEquals(listOf(CmiModImport.CmiNote(1_700_000_000_000, "Zrips", "a:b")), CmiModImport.parseNotes("Zrips:1700000000000:a:b"))
        assertEquals(listOf(CmiModImport.CmiNote(null, null, "그냥 메모")), CmiModImport.parseNotes("그냥 메모"))
        assertTrue(CmiModImport.parseNotes("  ").isEmpty())
    }

    @Test
    fun `CMI 때 - 초 단위면 밀리초로 올린다`() {
        assertEquals(1_700_000_000_000, CmiModImport.normalizeMillis(1_700_000_000))
        assertEquals(1_700_000_000_000, CmiModImport.normalizeMillis(1_700_000_000_000))
        assertEquals(0, CmiModImport.normalizeMillis(0))
    }

    @Test
    fun `기본 설정의 남이 거는 열은 교환·삭제를 따라가고 우편 보낸 사람은 안 따라간다`() {
        val withTeleport = tables + ("ess_teleport_blocks" to listOf("owner", "blocked")) + ("ess_mail" to listOf("id", "owner", "sender_id"))
        val cols = DataRules.playerColumns(withTeleport, ModSettings().dataExtraColumns, emptySet(), identity = false).map { it.toString() }.toSet()
        assertTrue("ess_chat_ignores.target" in cols)
        assertTrue("ess_teleport_blocks.blocked" in cols)
        assertTrue("ess_mail.owner" in cols)
        assertTrue("ess_mail.sender_id" !in cols, "남이 받은 우편까지 지우지 않게")
    }

    @Test
    fun `미접속 정리는 사람을 조각내고 마지막 조각만 남는 것이 없다`() {
        val op = DataRules.Op(DataRules.Kind.PURGE, columns = listOf(Column("ess_users", "uuid")), ids = (1..7).map { "u$it" }, cutoff = 5L, folders = listOf("x"))
        val slices = DataTools.purgeSlices(op, size = 3)
        assertEquals(listOf(listOf("u1", "u2", "u3"), listOf("u4", "u5", "u6"), listOf("u7")), slices.map { it.first.ids })
        assertEquals(listOf(4, 1, null), slices.map { it.second?.ids?.size })
        assertEquals(listOf("u4", "u5", "u6", "u7"), slices[0].second!!.ids, "남는 작업은 뒤의 사람 전부")
        for ((slice, rest) in slices) {
            assertEquals(op.copy(ids = emptyList()), slice.copy(ids = emptyList()), "조각은 기준·폴더·열을 그대로 든다")
            if (rest != null) assertEquals(op.copy(ids = emptyList()), rest.copy(ids = emptyList()))
        }
        // 남는 작업은 예약 줄에 되써지므로 왕복해야 한다.
        assertEquals(slices[0].second, DataRules.decode(DataRules.encode(slices[0].second!!)))
        // 사람이 없어도 조각 하나(예약 줄을 지우기 위해).
        assertEquals(listOf(op.copy(ids = emptyList()) to null), DataTools.purgeSlices(op.copy(ids = emptyList()), size = 3))
        assertTrue(DataTools.PURGE_SLICE <= DataRules.CHUNK * 2)
    }

    @Test
    fun `예약 종류마다 그 명령의 권한`() {
        assertEquals(DataRules.Kind.entries.map { it.name }.toSet(), DataCommands.KIND_PERMISSION.keys)
        assertEquals(ModerationFeature.PURGE, DataCommands.KIND_PERMISSION[DataRules.Kind.PURGE.name])
    }
}
