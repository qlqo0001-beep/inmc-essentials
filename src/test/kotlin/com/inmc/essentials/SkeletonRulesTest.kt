package com.inmc.essentials

import com.inmc.essentials.command.Args
import com.inmc.essentials.command.CommandRegistry
import com.inmc.essentials.store.UserIndex
import com.inmc.essentials.store.UserRecord
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 여러 작업자가 기대는 뼈대 규칙 — 사람 인자 읽기 · 이름 색인(넘겨받은 이름·바꾼 이름) · 명령 이름 거르기와 실패한 기능의 명령 거두기. */
class SkeletonRulesTest {

    // --- 사람 인자: 빈칸까지 한 낱말 ------------------------------------------------------------------

    @Test
    fun `사람 인자는 한글 닉네임과 기호도 빈칸까지 읽고 빈칸은 남긴다`() {
        val reader = StringReader("홍길동 10")
        assertEquals("홍길동", Args.TOKEN.parse(reader))
        assertEquals(' ', reader.peek())
        assertEquals("Steve_1", Args.TOKEN.parse(StringReader("Steve_1")))
        assertEquals("*", Args.TOKEN.parse(StringReader("* 아이템")))
        assertEquals("전원", Args.TOKEN.parse(StringReader("전원")))
        assertEquals("", Args.TOKEN.parse(StringReader("")))
    }

    // --- 이름 색인 ------------------------------------------------------------------------------------

    private val a = UUID(0, 1)
    private val b = UUID(0, 2)

    private fun record(id: UUID, name: String, lastJoin: Long) = UserRecord(id, name, firstJoin = 1L, lastJoin = lastJoin)

    @Test
    fun `같은 이름은 마지막으로 들어온 사람이 쥔다 - 넣는 순서와 무관`() {
        val index = UserIndex()
        index.put(record(a, "Steve", 100))
        index.put(record(b, "steve", 200))
        assertEquals(b, index.byName("STEVE")?.id)

        val reversed = UserIndex()
        reversed.put(record(b, "steve", 200))
        reversed.put(record(a, "Steve", 100))
        assertEquals(b, reversed.byName("steve")?.id)
    }

    @Test
    fun `이름을 바꾸면 옛 이름을 놓는다 - 남이 쥔 옛 이름은 그대로`() {
        val index = UserIndex()
        index.put(record(a, "Old", 100))
        index.put(record(a, "New", 150))
        assertNull(index.byName("old"))
        assertEquals(a, index.byName("new")?.id)

        // b 가 a 의 옛 이름을 넘겨받은 뒤 a 가 다시 이름을 바꿔도 b 의 것은 그대로.
        index.put(record(b, "New", 300))
        index.put(record(a, "Newer", 400))
        assertEquals(b, index.byName("New")?.id)
        assertEquals(a, index.byName("newer")?.id)
    }

    @Test
    fun `지우면 이름도 풀린다`() {
        val index = UserIndex()
        index.put(record(a, "Alex", 100))
        index.remove(a)
        assertNull(index.byName("alex"))
        assertEquals(0, index.count())
    }

    // --- 명령 이름 거르기 ------------------------------------------------------------------------------

    /** 로거만 있는 가짜 Ess(공유 TestEss 를 더럽히지 않게 따로). */
    private fun essWithLogger(): Ess {
        val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        val ess = unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, Ess::class.java) as Ess
        Ess::class.java.getDeclaredField("logger").apply { isAccessible = true }.set(ess, Logger.getLogger("essentials-test"))
        return ess
    }

    private fun node(name: String) = LiteralArgumentBuilder.literal<CommandSourceStack>(name).build()

    @Test
    fun `바닐라 메인 이름은 명령째, 바닐라 별칭은 그 별칭만 뺀다`() {
        val registry = CommandRegistry(essWithLogger())
        registry.add(node("tp"), "막힘")
        registry.add(node("이동"), "이동", listOf("tp", "tpto", "이동"))
        assertEquals(listOf("이동"), registry.all().map { it.node.literal })
        assertEquals(listOf("tpto"), registry.all().single().aliases)
    }

    @Test
    fun `다른 기능이 쓴 이름과 별칭은 뺀다 - 관리 명령 이름도`() {
        val registry = CommandRegistry(essWithLogger())
        registry.add(node("홈"), "홈", listOf("home"))
        registry.add(node("home"), "겹침")
        registry.add(node("집"), "집", listOf("home", "house", "ess"))
        registry.add(node("에센셜"), "관리와 겹침")
        assertEquals(listOf("홈", "집"), registry.all().map { it.node.literal })
        assertEquals(listOf("house"), registry.all().last().aliases)
    }

    @Test
    fun `켜다 실패한 기능의 명령을 거두면 이름이 풀린다`() {
        val ess = essWithLogger()
        val registry = CommandRegistry(ess)
        ess.owner = "teleport"
        registry.add(node("이동"), "이동", listOf("tpto"))
        ess.owner = "homes"
        registry.add(node("홈"), "홈")
        ess.owner = null
        assertEquals(1, registry.removeOwner("teleport"))
        assertEquals(listOf("홈"), registry.all().map { it.node.literal })
        registry.add(node("tpto"), "풀린 이름")
        assertEquals(listOf("홈", "tpto"), registry.all().map { it.node.literal })
    }
}
