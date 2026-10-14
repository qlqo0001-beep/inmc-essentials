package com.inmc.essentials

import com.inmc.essentials.command.Args
import com.inmc.essentials.command.CommandRegistry
import com.inmc.essentials.store.EssDb
import kr.inmc.core.store.SqlDialect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 서버 없이 도는 뼈대의 순수 규칙 — 덮어쓰기 문장 · 이름 고르기 순서 · 쓰지 않는 명령 이름. */
class PureTest {

    // --- 덮어쓰기 문장 (REPLACE INTO 금지 — 가이드 함정 11) -------------------------------------------

    @Test
    fun `SQLite 는 ON CONFLICT DO UPDATE, 자리는 키 - 처음만 - 고칠 것 순서`() {
        assertEquals(
            "INSERT INTO ess_users (uuid, first_join, name, last_join) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (uuid) DO UPDATE SET name = excluded.name, last_join = excluded.last_join",
            EssDb.upsertSql(SqlDialect.SQLITE, "ess_users", listOf("uuid"), listOf("name", "last_join"), listOf("first_join")),
        )
    }

    @Test
    fun `MySQL 은 ON DUPLICATE KEY UPDATE`() {
        assertEquals(
            "INSERT INTO ess_homes (owner, name, x) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE x = VALUES(x)",
            EssDb.upsertSql(SqlDialect.MYSQL, "ess_homes", listOf("owner", "name"), listOf("x")),
        )
    }

    @Test
    fun `고칠 열이 없으면 넣기만 한다`() {
        assertEquals(
            "INSERT INTO t (a) VALUES (?) ON CONFLICT (a) DO NOTHING",
            EssDb.upsertSql(SqlDialect.SQLITE, "t", listOf("a"), emptyList()),
        )
        assertEquals("INSERT INTO t (a) VALUES (?) ON DUPLICATE KEY UPDATE a = VALUES(a)", EssDb.upsertSql(SqlDialect.MYSQL, "t", listOf("a"), emptyList()))
    }

    @Test
    fun `어느 말씨에도 REPLACE 가 없다`() {
        for (dialect in SqlDialect.entries) assertFalse(EssDb.upsertSql(dialect, "t", listOf("a"), listOf("b")).contains("REPLACE", ignoreCase = true))
    }

    // --- 이름 고르기 순서 ---------------------------------------------------------------------------

    private fun pick(input: String, vararg names: String): String? = Args.pick(input, names.toList()) { it }

    @Test
    fun `정확한 이름이 대소문자 무시보다 먼저`() {
        assertEquals("steve", pick("steve", "Steve", "steve"))
        assertEquals("Steve", pick("STEVE", "Steve", "Alex"))
    }

    @Test
    fun `대소문자 무시가 앞부분보다 먼저`() {
        // "alex" 는 Alex 와 정확히(대소문자 무시) 같다 — Alexander 의 앞부분이기도 하지만 정확한 쪽.
        assertEquals("Alex", pick("alex", "Alexander", "Alex"))
    }

    @Test
    fun `앞부분이 하나만 맞으면 그 사람, 둘이면 없음`() {
        assertEquals("Notch", pick("no", "Notch", "jeb_"))
        assertNull(pick("st", "Steve", "Stella"))
    }

    @Test
    fun `앞부분이 둘이면 들어 있음으로 넘어가지 않는다`() {
        // "ab" 는 abc·abd 의 앞부분(둘) — xab 가 "들어 있음" 으로 하나지만 고르지 않는다.
        assertNull(pick("ab", "abc", "abd", "xab"))
    }

    @Test
    fun `앞부분이 없으면 들어 있는 것이 하나일 때만`() {
        assertEquals("xX_Pro_Xx", pick("pro", "xX_Pro_Xx", "Steve"))
        assertNull(pick("e", "Steve", "Alex"))
        assertNull(pick("zzz", "Steve"))
    }

    @Test
    fun `전원과 꼬리 쪼개기`() {
        assertTrue(Args.isAll("ALL"))
        assertTrue(Args.isAll("*"))
        assertTrue(Args.isAll("전원"))
        assertFalse(Args.isAll("alle"))
        assertEquals(listOf("홍길동", "10"), Args.split("  홍길동   10 "))
    }

    // --- 쓰지 않는 명령 이름 -----------------------------------------------------------------------

    @Test
    fun `바닐라와 Paper 명령 이름은 막고 귓속말과 목록만 연다`() {
        for (name in listOf("tp", "give", "kill", "gamemode", "ban", "kick", "reload", "plugins", "tps", "spawnpoint", "me")) {
            assertTrue(CommandRegistry.reserved(name), "$name 은 막혀야 합니다")
        }
        for (name in listOf("msg", "tell", "w", "list", "spawn", "home", "warp", "tpa", "홈", "귓")) {
            assertFalse(CommandRegistry.reserved(name), "$name 은 쓸 수 있어야 합니다")
        }
    }
}
