package com.inmc.essentials.chat

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 검토(2026-10-10) 뒤 더한 규칙 — CMI 모두 무시 · 공지 거리 옵션 오타 · 귓속말 그룹은 실제로 준 것만. */
class ChatReviewTest {

    @Test
    fun `CMI 모두 무시 표시 uuid 는 우리 모두 무시로 - 다른 uuid 는 그대로`() {
        assertEquals(ChatStore.ALL, ChatRules.cmiIgnoreTarget(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")))
        val someone = UUID(1, 2)
        assertEquals(someone.toString(), ChatRules.cmiIgnoreTarget(someone))
    }

    @Test
    fun `공지 거리 옵션을 못 읽으면 서버 전체 공지가 되지 않게 알린다`() {
        val bad = ChatRules.parseBroadcast("-c:world;100;64;100 -r:5O 곧 시작")
        assertNull(bad.range)
        assertEquals("-r:5O", bad.badRange)
        assertEquals("-r:5O", ChatRules.broadcastRangeProblem(bad))
        assertEquals("-r:0", ChatRules.broadcastRangeProblem(ChatRules.parseBroadcast("안녕 -r:0")))
        assertEquals("-거리:abc", ChatRules.broadcastRangeProblem(ChatRules.parseBroadcast("안녕 -거리:abc")))
        // 중심만 주고 거리를 빠뜨림
        assertEquals("-c:world;1;2;3", ChatRules.broadcastRangeProblem(ChatRules.parseBroadcast("곧 시작 -c:world;1;2;3")))
        // 바른 것
        assertNull(ChatRules.broadcastRangeProblem(ChatRules.parseBroadcast("곧 시작 -c:world;1;2;3 -r:50")))
        assertNull(ChatRules.broadcastRangeProblem(ChatRules.parseBroadcast("곧 시작 -r:50")))
        assertNull(ChatRules.broadcastRangeProblem(ChatRules.parseBroadcast("곧 시작 -w:world")))
        // 뒤에 바른 거리가 오면 그것
        assertNull(ChatRules.broadcastRangeProblem(ChatRules.parseBroadcast("곧 -r:x -r:20")))
    }

    @Test
    fun `귓속말 그룹은 실제로 준 group 노드만 앞에서부터`() {
        val order = listOf("admin", "vip")
        // OP 처럼 모든 노드에 hasPermission 이 참이어도 isPermissionSet 이 아니면 그룹이 아니다
        assertNull(ChatRules.groupOf(order) { false })
        assertEquals("vip", ChatRules.groupOf(order) { it == "group.vip" })
        assertEquals("admin", ChatRules.groupOf(order) { it == "group.admin" || it == "group.vip" })
    }
}
