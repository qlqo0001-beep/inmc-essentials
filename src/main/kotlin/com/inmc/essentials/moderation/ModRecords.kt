package com.inmc.essentials.moderation

import com.inmc.essentials.service.ModerationState
import com.inmc.essentials.store.LogoutSpot
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 뮤트 한 건. [until] null = 영구. 시각은 밀리초. */
data class Mute(val id: UUID, val until: Long?, val reason: String, val source: String, val shadow: Boolean, val created: Long) {
    fun active(now: Long): Boolean = until == null || until > now
    fun remainingSeconds(now: Long): Long? = until?.let { ((it - now) / 1000).coerceAtLeast(0) }
}

/**
 * 갇힌 사람 한 명. 남은 시간은 **절대 시각이 아니라 남은 양**으로 둔다(가이드 함정 14 — 서버가 꺼져 있던 동안 풀리지 않게).
 * [updated] = [remainingMs] 를 마지막으로 맞춘 시각 — 오프라인에도 시간을 깎는 설정이면 들어올 때 그만큼 뺀다.
 * [prior] = 가두기 전 자리(감옥에 석방 위치가 없을 때 돌려보낼 곳).
 */
class Jailed(
    val id: UUID,
    @Volatile var jail: String,
    @Volatile var cell: Int?,
    @Volatile var remainingMs: Long,
    @Volatile var updated: Long,
    val reason: String,
    val source: String,
    val created: Long,
    val prior: LogoutSpot?,
) {
    @Volatile
    var dirty: Boolean = false

    fun remainingSeconds(): Long = (remainingMs / 1000).coerceAtLeast(0)

    /** 오프라인 사람의 지금 남은 시간(오프라인에도 깎는 설정이면 지난 만큼 뺀다). */
    fun estimate(now: Long, countOffline: Boolean, online: Boolean): Long =
        if (online || !countOffline) remainingMs else (remainingMs - (now - updated)).coerceAtLeast(0)
}

data class Cuff(val id: UUID, val source: String, val created: Long)

/** 경고 한 건. [expires] null = 만료 없음. */
data class Warning(
    val no: Long,
    val id: UUID,
    val category: String,
    val points: Int,
    val reason: String,
    val source: String,
    val created: Long,
    val expires: Long?,
) {
    fun active(now: Long): Boolean = expires == null || expires > now
}

data class Note(val no: Long, val id: UUID, val text: String, val author: String, val created: Long)

data class Alert(val id: UUID, val reason: String?, val author: String, val until: Long)

/** IP 기록 한 줄(이 uuid 가 이 IP 로 몇 번 들어왔나). */
data class IpSeen(val id: UUID, val ip: String, val firstSeen: Long, val lastSeen: Long, val times: Int)

/** 공개 채팅 잠금. [until] null = 풀 때까지. */
data class ChatLock(val until: Long?, val reason: String?, val source: String) {
    fun active(now: Long): Boolean = until == null || until > now
}

/**
 * 메모리에 든 제재 상태 전부 — `ess.moderation` 이 이것이다. **어느 스레드에서나** 읽는다(비동기 채팅) — 전부 동시성 안전한 표,
 * 목록 값은 바꿀 때 통째로 갈아 끼운다(읽는 쪽이 반쯤 바뀐 목록을 보지 않게).
 */
class ModState : ModerationState {

    val mutes = ConcurrentHashMap<UUID, Mute>()
    val jailed = ConcurrentHashMap<UUID, Jailed>()
    val cuffs = ConcurrentHashMap<UUID, Cuff>()
    val warnings = ConcurrentHashMap<UUID, List<Warning>>()
    val notes = ConcurrentHashMap<UUID, List<Note>>()
    val alerts = ConcurrentHashMap<UUID, Alert>()
    val meta = ConcurrentHashMap<UUID, Map<String, String>>()
    val lockIps = ConcurrentHashMap<UUID, Set<String>>()
    val ips = ConcurrentHashMap<UUID, List<IpSeen>>()

    /** 나가 있는 동안 석방된 사람 → 다음 접속 때 보낼 자리(감옥 안에 갇힌 채 로그아웃했을 수 있다). `ess_moderation_release`. */
    val pendingRelease = ConcurrentHashMap<UUID, LogoutSpot>()

    @Volatile
    var chatLock: ChatLock? = null

    /** 경고·메모 번호 — 켤 때 가장 큰 번호와 지금 시각 중 큰 것에서 시작한다(여러 서버가 같은 DB 를 써도 거의 겹치지 않게). */
    val serial = AtomicLong(System.currentTimeMillis())

    override fun isMuted(id: UUID): Boolean = mutes[id]?.active(System.currentTimeMillis()) == true

    override fun isShadowMuted(id: UUID): Boolean = mutes[id]?.let { it.shadow && it.active(System.currentTimeMillis()) } == true

    override fun isJailed(id: UUID): Boolean = jailed.containsKey(id)

    override fun isCuffed(id: UUID): Boolean = cuffs.containsKey(id)

    fun activeMute(id: UUID, now: Long): Mute? = mutes[id]?.takeIf { it.active(now) }

    fun activeWarnings(id: UUID, now: Long): List<Warning> = warnings[id].orEmpty().filter { it.active(now) }

    fun points(id: UUID, now: Long): Int = activeWarnings(id, now).sumOf { it.points }

    fun activeNotes(id: UUID): List<Note> = notes[id].orEmpty()

    fun activeAlert(id: UUID, now: Long): Alert? = alerts[id]?.takeIf { it.until > now }

    fun lockChat(now: Long): ChatLock? = chatLock?.takeIf { it.active(now) }

    /** 이 IP 를 쓴 적 있는 uuid 들. */
    fun usersOf(ip: String): Set<UUID> = ips.values.asSequence().flatten().filter { it.ip == ip }.map { it.id }.toSet()
}
