package com.inmc.essentials.store

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * `ess_users` 의 메모리 색인(순수 — 서버 없이 시험한다). uuid → 기록, 소문자 이름 → uuid.
 *
 * 이름 규칙: 같은 이름을 둘이 가지면(이름을 넘겨받은 사람) **마지막으로 들어온 사람**이 쥔다 — 마지막 접속이 같으면 나중에 넣은 쪽.
 * 이름을 바꾼 사람의 옛 이름은 놓는다(다른 사람이 쥐고 있으면 그대로 둔다).
 */
internal class UserIndex {

    private val byId = ConcurrentHashMap<UUID, UserRecord>()
    private val byName = ConcurrentHashMap<String, UUID>()

    operator fun get(id: UUID): UserRecord? = byId[id]

    fun byName(name: String): UserRecord? = byName[name.lowercase()]?.let { byId[it] }

    fun all(): Collection<UserRecord> = byId.values

    fun count(): Int = byId.size

    fun clear() {
        byId.clear()
        byName.clear()
    }

    fun put(record: UserRecord) {
        val old = byId.put(record.id, record)
        if (old != null && !old.name.equals(record.name, ignoreCase = true)) byName.remove(old.name.lowercase(), record.id)
        val key = record.name.lowercase()
        val holder = byName[key]?.let { byId[it] }
        if (holder == null || holder.id == record.id || holder.lastJoin <= record.lastJoin) byName[key] = record.id
    }

    fun remove(id: UUID): UserRecord? {
        val old = byId.remove(id) ?: return null
        byName.remove(old.name.lowercase(), id)
        return old
    }
}
