package com.inmc.essentials.teleport

import org.bukkit.Location
import java.util.UUID

/**
 * 이동 요청 장부(순수 · 메인 스레드 전용 — 명령·틱·사건이 모두 메인이다). 저장하지 않는다(재시작하면 요청은 사라진다 — CMI 와 같다).
 *
 * - 한 사람이 같은 상대에게 둘 이상 걸어 두지 않는다 — 새 요청이 옛것을 바꾼다(이동요청 ↔ 소환요청 도).
 * - 거절하면 그 사람의 요청을 [denyBlockMillis] 동안 막는다(CMI `Tpa.Block`).
 */
class TpaBook {

    /**
     * 요청 하나. [here] = 소환요청(받는 사람이 요청한 사람에게 간다), 아니면 이동요청(요청한 사람이 받는 사람에게 간다).
     * [label] = 요청한 사람이 친 명령 이름(이동이 성공하면 이 이름으로 쿨타임이 기록된다). [snapshot] = 요청한 때 도착 자리(현재 위치 설정이 꺼졌을 때).
     */
    data class Request(
        val from: UUID,
        val fromName: String,
        val to: UUID,
        val toName: String,
        val here: Boolean,
        val label: String,
        val at: Long,
        val expires: Long,
        val snapshot: Location? = null,
        val all: Boolean = false,
    )

    private val requests = ArrayList<Request>()
    private val denyBlocks = HashMap<Pair<UUID, UUID>, Long>()

    /** 넣는다. 같은 (보낸 사람, 받는 사람) 의 옛 요청이 있으면 그것을 돌려주고 바꾼다. */
    fun add(request: Request): Request? {
        val old = requests.firstOrNull { it.from == request.from && it.to == request.to }
        if (old != null) requests.remove(old)
        requests += request
        return old
    }

    /** [to] 가 받은 요청(최근 것부터). */
    fun incoming(to: UUID): List<Request> = requests.filter { it.to == to }.asReversed()

    /** [from] 이 보낸 요청(최근 것부터). */
    fun outgoing(from: UUID): List<Request> = requests.filter { it.from == from }.asReversed()

    /** [to] 가 받은 요청 하나를 꺼낸다 — [from] 이 null 이면 가장 최근 것. 없으면 null. */
    fun take(to: UUID, from: UUID? = null): Request? {
        val found = incoming(to).firstOrNull { from == null || it.from == from } ?: return null
        requests.remove(found)
        return found
    }

    /** [from] 이 보낸 요청을 거둔다 — [to] 가 null 이면 전부. 거둔 것. */
    fun cancel(from: UUID, to: UUID? = null): List<Request> {
        val gone = requests.filter { it.from == from && (to == null || it.to == to) }
        requests.removeAll(gone.toSet())
        return gone
    }

    /** [now] 에 끝난 요청을 빼서 돌려준다. 끝난 거절 막기도 지운다. */
    fun expire(now: Long): List<Request> {
        val gone = requests.filter { it.expires <= now }
        requests.removeAll(gone.toSet())
        denyBlocks.values.removeIf { it <= now }
        return gone
    }

    /** [id] 가 보냈거나 받은 요청 전부를 뺀다(나갈 때). */
    fun removeAll(id: UUID): List<Request> {
        val gone = requests.filter { it.from == id || it.to == id }
        requests.removeAll(gone.toSet())
        return gone
    }

    /** [to] 가 [from] 의 요청을 거절했다 — [until] 까지 [from] → [to] 요청을 막는다. */
    fun blockAfterDeny(from: UUID, to: UUID, until: Long) {
        denyBlocks[from to to] = until
    }

    /** [from] → [to] 요청이 거절 때문에 막혀 있으면 남은 밀리초, 아니면 0. */
    fun denyBlockLeft(from: UUID, to: UUID, now: Long): Long = ((denyBlocks[from to to] ?: 0L) - now).coerceAtLeast(0L)

    fun size(): Int = requests.size

    fun clear() {
        requests.clear()
        denyBlocks.clear()
    }
}
