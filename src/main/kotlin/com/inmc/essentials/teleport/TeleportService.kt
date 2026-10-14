package com.inmc.essentials.teleport

import com.inmc.essentials.Ess
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/**
 * 이동 기능의 상태 — 뒤로·사망 자리 · 요청 받기/막은 사람 · 나가 있는 동안 불린 자리 · 이동 뒤 무적 · 요청 장부 · 대기 중인 수락 이동.
 *
 * 사람마다의 자료는 **접속해 있는 동안만** 메모리에 둔다(접속 직전 비동기 사건에서 DB 로 읽고, 나가면 뺀다). 바뀌는 순간 DB 쓰기 줄로
 * 넘긴다(위치 기록은 자주 바뀌지 않고, 30초 묶음보다 단순하다 — 크래시에도 잃지 않는다).
 */
class TeleportService(private val ess: Ess, private val settings: () -> TeleportSettings) {

    val book = TpaBook()

    private val back = ConcurrentHashMap<UUID, Spot>()
    private val death = ConcurrentHashMap<UUID, Spot>()
    private val accepting = ConcurrentHashMap<UUID, Boolean>()
    private val blocks = ConcurrentHashMap<UUID, Set<UUID>>()
    private val pending = ConcurrentHashMap<UUID, Pair<Spot, String?>>()

    /** 접속 직전에 읽은 것(시각, 자료) — 접속 사건에서 꺼낸다. 접속이 막힌 사람 몫은 틱이 1분 뒤 지운다. */
    private val preloaded = ConcurrentHashMap<UUID, Pair<Long, TeleportRecord>>()

    /** 지금 `/뒤로` 로 옮기는 중 — 그 이동은 뒤로 기록에서 뺀다(메인). */
    private val suppressed = HashSet<UUID>()

    /** 이동 뒤 무적이 끝나는 시각(메인). */
    private val invulnerable = HashMap<UUID, Long>()

    /** 수락 뒤 옮겨지기를 기다리는 사람 → 카운트다운(메인). */
    private val warmups = HashMap<UUID, ScheduledTask>()

    /** 검증기가 도는 동안 true — 기록은 메모리에만(파일·DB 에 쓰지 않는다). 메인. */
    var dryRun = false

    // --- 뒤로 · 사망 -------------------------------------------------------------------------

    fun back(id: UUID): Spot? = back[id]

    fun death(id: UUID): Spot? = death[id]

    /** 검증기가 되돌릴 때 — 메모리만. */
    internal fun restore(id: UUID, backSpot: Spot?, deathSpot: Spot?) {
        if (backSpot == null) back.remove(id) else back[id] = backSpot
        if (deathSpot == null) death.remove(id) else death[id] = deathSpot
    }

    /** `PlayerTeleportEvent` 하나 — 기록할 이동이면 출발 자리를 적는다. 적었으면 true. */
    fun onTeleport(player: Player, from: Location, to: Location, cause: TeleportCause, now: Long = System.currentTimeMillis()): Boolean {
        val fromSpot = Spot.of(from, now) ?: return false
        val toSpot = Spot.of(to, now) ?: return false
        if (!shouldRecord(fromSpot, toSpot, cause, settings(), player.uniqueId in suppressed)) return false
        setSpot(player.uniqueId, TeleportData.BACK, fromSpot)
        return true
    }

    /** 죽은 자리를 적는다(제외 월드면 안 적는다). */
    fun onDeath(player: Player, at: Location, now: Long = System.currentTimeMillis()): Boolean {
        val spot = Spot.of(at, now) ?: return false
        if (settings().deathExcluded(spot.world)) return false
        setSpot(player.uniqueId, TeleportData.DEATH, spot)
        return true
    }

    private fun setSpot(id: UUID, kind: String, spot: Spot) {
        (if (kind == TeleportData.BACK) back else death)[id] = spot
        if (!dryRun) ess.db.run("이동 자리 기록") { TeleportData.writeSpot(ess.db.sql, id, kind, spot) }
    }

    /** 지운다(메모리 + DB + 접속 중에 읽어 둔 것). 나가 있는 사람도 된다. 메모리에 있었으면 true. */
    fun clear(id: UUID, kind: String): Boolean {
        val had = (if (kind == TeleportData.BACK) back else death).remove(id) != null
        // 접속 직전에 읽어 둔 자료가 있으면 그것도 고친다 — 접속 사건이 옛 자리를 되살리지 않게.
        preloaded.computeIfPresent(id) { _, (t, r) -> t to if (kind == TeleportData.BACK) r.copy(back = null) else r.copy(death = null) }
        if (!dryRun) ess.db.run("이동 자리 지우기") { TeleportData.deleteSpot(ess.db.sql, id, kind) }
        return had
    }

    /** CMI 가져오기 — 비어 있을 때만 넣는다(DB 의 `onlyIfAbsent` 와 같은 뜻). 접속 중이면 메모리에도. 메인. */
    fun importSpot(id: UUID, kind: String, spot: Spot) {
        ess.db.run("CMI 이동 자리") { TeleportData.writeSpot(ess.db.sql, id, kind, spot, onlyIfAbsent = true) }
        if (Bukkit.getPlayer(id) != null) (if (kind == TeleportData.BACK) back else death).putIfAbsent(id, spot)
        preloaded.computeIfPresent(id) { _, (t, r) ->
            t to when {
                kind == TeleportData.BACK && r.back == null -> r.copy(back = spot)
                kind == TeleportData.DEATH && r.death == null -> r.copy(death = spot)
                else -> r
            }
        }
    }

    /** CMI 가져오기 — 요청 받기(비어 있을 때만). 접속 중이면 메모리에도. 메인. */
    fun importAccepting(id: UUID, value: Boolean) {
        ess.db.run("CMI 요청 받기") { TeleportData.writeAccepting(ess.db.sql, id, value, onlyIfAbsent = true) }
        if (Bukkit.getPlayer(id) != null) accepting.putIfAbsent(id, value)
        preloaded.computeIfPresent(id) { _, (t, r) -> t to if (r.accepting == null) r.copy(accepting = value) else r }
    }

    fun suppress(id: UUID) {
        suppressed += id
    }

    fun unsuppress(id: UUID) {
        suppressed -= id
    }

    // --- 요청 받기 · 막은 사람 ---------------------------------------------------------------------

    fun accepting(id: UUID): Boolean = accepting[id] ?: settings().tpaDefaultAccepting

    /**
     * [target] 이 [sender] 의 이동·요청을 받지 않나 — 받기를 껐거나(요청차단) [sender] 를 막았다. [bypass] 면 늘 false.
     * [sender] 가 null 이면 받기만 본다(`/이동`·`/소환` 은 한 사람 막기를 보지 않는다 — 막기는 요청만).
     */
    fun refuses(target: UUID, sender: UUID?, bypass: Boolean): Boolean =
        !bypass && (!accepting(target) || (sender != null && blocked(target, sender)))

    /** 검증기 — 지금 저장된 값(없으면 null)과 되돌리기. 메모리만. */
    internal fun acceptingRaw(id: UUID): Boolean? = accepting[id]

    internal fun restoreAccepting(id: UUID, value: Boolean?) {
        if (value == null) accepting.remove(id) else accepting[id] = value
    }

    internal fun blocksRaw(id: UUID): Set<UUID>? = blocks[id]

    internal fun restoreBlocks(id: UUID, value: Set<UUID>?) {
        if (value == null) blocks.remove(id) else blocks[id] = value
    }

    fun setAccepting(id: UUID, value: Boolean) {
        accepting[id] = value
        if (!dryRun) ess.db.run("요청 받기") { TeleportData.writeAccepting(ess.db.sql, id, value) }
    }

    /** [owner] 가 [other] 의 요청을 막았나. */
    fun blocked(owner: UUID, other: UUID): Boolean = blocks[owner]?.contains(other) == true

    /** 막기를 뒤집는다. 이제 막았으면 true. */
    fun toggleBlock(owner: UUID, other: UUID): Boolean {
        val now = !blocked(owner, other)
        blocks.compute(owner) { _, old -> (old.orEmpty() - other).let { if (now) it + other else it } }
        if (!dryRun) ess.db.run("요청 막기") { TeleportData.writeBlock(ess.db.sql, owner, other, now) }
        return now
    }

    // --- 나가 있는 동안 불린 자리 -------------------------------------------------------------------

    /**
     * 나가 있는 사람이 다음 접속 때 옮겨질 자리를 적는다 — DB 에만(접속할 때 읽는다). 메모리에는 **지금 접속 중인 사람**(읽는 사이에 들어온 사람)과
     * 접속 직전에 읽어 둔 자료에만 넣는다 — 나간 수천 명을 메모리에 들고 있지 않게.
     */
    fun addPending(id: UUID, spot: Spot, by: String?) {
        if (Bukkit.getPlayer(id) != null) pending[id] = spot to by
        preloaded.computeIfPresent(id) { _, (t, r) -> t to r.copy(pending = spot, pendingBy = by) }
        ess.db.run("불려 갈 자리") { TeleportData.writePending(ess.db.sql, id, spot, by) }
    }

    /** 한 사람의 불려 갈 자리를 지운다(`/소환 취소`). 메모리·읽어 둔 것·DB. */
    fun cancelPending(id: UUID) {
        pending.remove(id)
        preloaded.computeIfPresent(id) { _, (t, r) -> t to r.copy(pending = null, pendingBy = null) }
        ess.db.run("불려 갈 자리 지우기") { TeleportData.deletePending(ess.db.sql, id) }
    }

    /** 불려 갈 자리 전부를 지운다(`/월드전원소환 취소`) — 지운 줄 수를 메인에서 [done] 으로. */
    fun cancelAllPending(done: (Int) -> Unit) {
        pending.clear()
        preloaded.replaceAll { _, (t, r) -> t to r.copy(pending = null, pendingBy = null) }
        ess.db.run("불려 갈 자리 전부 지우기") {
            val count = TeleportData.deleteAllPending(ess.db.sql)
            Bukkit.getScheduler().runTask(ess.plugin, Runnable { done(count) })
        }
    }

    fun takePending(id: UUID): Pair<Spot, String?>? {
        val found = pending.remove(id)
        ess.db.run("불려 갈 자리 지우기") { TeleportData.deletePending(ess.db.sql, id) }
        return found
    }

    // --- 이동 뒤 무적 ------------------------------------------------------------------------------

    fun grantInvulnerability(id: UUID, seconds: Int, now: Long = System.currentTimeMillis()) {
        if (seconds <= 0) return
        invulnerable[id] = now + seconds * 1000L
    }

    fun isInvulnerable(id: UUID, now: Long = System.currentTimeMillis()): Boolean {
        val until = invulnerable[id] ?: return false
        if (until > now) return true
        invulnerable.remove(id)
        return false
    }

    fun endInvulnerability(id: UUID) {
        invulnerable.remove(id)
    }

    /** 검증기 — 지금 값(되돌릴 때 쓴다). */
    internal fun invulnerableUntil(id: UUID): Long? = invulnerable[id]

    internal fun setInvulnerableUntil(id: UUID, until: Long?) {
        if (until == null) invulnerable.remove(id) else invulnerable[id] = until
    }

    // --- 수락 뒤 대기 ------------------------------------------------------------------------------

    fun startWarmup(id: UUID, task: ScheduledTask) {
        warmups.put(id, task)?.cancel()
    }

    fun warmupDone(id: UUID, task: ScheduledTask) {
        if (warmups[id] === task) warmups.remove(id)
    }

    fun cancelWarmup(id: UUID): Boolean {
        val task = warmups.remove(id) ?: return false
        task.cancel()
        return true
    }

    fun hasWarmup(id: UUID): Boolean = id in warmups

    // --- 읽기 · 접속 · 나감 -------------------------------------------------------------------------

    /** 접속 직전(비동기 사건) — DB 에서 그 사람 몫을 읽어 둔다. 기다린다(비동기 사건이라 된다). */
    fun preload(id: UUID) {
        try {
            val record = ess.db.call { TeleportData.read(ess.db.sql, id) }
            preloaded[id] = System.currentTimeMillis() to record
        } catch (t: Throwable) {
            ess.logger.log(Level.WARNING, "이동 자료를 읽지 못했습니다($id) — 이번 접속은 빈 기록으로 돕니다", t)
        }
    }

    /** 접속 — 읽어 둔 것을 넣는다. 불려 갈 자리가 있으면 돌려준다. */
    fun joined(player: Player): Pair<Spot, String?>? {
        // 읽어 두지 못했어도(읽기 실패) 이미 메모리에 든 불려 갈 자리는 돌려준다.
        preloaded.remove(player.uniqueId)?.second?.let { apply(player.uniqueId, it) }
        return pending[player.uniqueId]
    }

    /** 이미 접속해 있는 사람(`/reload` 뒤 · 켤 때) — 쓰기 스레드에서 읽고 메모리에 넣는다(메모리가 이긴다). 불려 갈 자리는 메인에서 [onPending]. */
    fun loadOnline(onPending: (Player, Pair<Spot, String?>) -> Unit) {
        val ids = Bukkit.getOnlinePlayers().map { it.uniqueId }
        if (ids.isEmpty()) return
        ess.db.run("이동 자료 읽기") {
            for (id in ids) {
                val record = TeleportData.read(ess.db.sql, id)
                apply(id, record)
                val found = pending[id] ?: continue
                Bukkit.getScheduler().runTask(ess.plugin, Runnable { Bukkit.getPlayer(id)?.let { onPending(it, found) } })
            }
        }
    }

    private fun apply(id: UUID, record: TeleportRecord) {
        record.back?.let { back.putIfAbsent(id, it) }
        record.death?.let { death.putIfAbsent(id, it) }
        record.accepting?.let { accepting.putIfAbsent(id, it) }
        if (record.blocked.isNotEmpty()) blocks.putIfAbsent(id, record.blocked)
        record.pending?.let { pending.putIfAbsent(id, it to record.pendingBy) }
    }

    /** 나감 — 메모리에서 뺀다(DB 에는 이미 있다). 요청은 장부에서 뺀 것을 돌려준다(상대에게 알리기). */
    fun quit(id: UUID): List<TpaBook.Request> {
        back.remove(id)
        death.remove(id)
        accepting.remove(id)
        blocks.remove(id)
        pending.remove(id)
        suppressed.remove(id)
        invulnerable.remove(id)
        cancelWarmup(id)
        return book.removeAll(id)
    }

    /** 1초마다(메인) — 끝난 요청을 돌려준다. 접속하지 못한 사람의 읽어 둔 자료를 버린다. */
    fun tick(now: Long): List<TpaBook.Request> {
        preloaded.entries.removeIf { now - it.value.first > 60_000L && Bukkit.getPlayer(it.key) == null }
        invulnerable.entries.removeIf { it.value <= now }
        return book.expire(now)
    }

    fun shutdown() {
        for (task in warmups.values) task.cancel()
        warmups.clear()
        book.clear()
    }

    companion object {

        /**
         * 뒤로 기록 규칙(순수) — 출발 자리를 적을지. `/뒤로` 자신의 이동([suppressed]) · 설정에 없는 원인 · 제외 월드에서 떠남 ·
         * 같은 월드 안 가까운 이동(CMI `MinDistance`)은 뺀다.
         */
        fun shouldRecord(from: Spot, to: Spot, cause: TeleportCause, settings: TeleportSettings, suppressed: Boolean): Boolean {
            if (suppressed) return false
            if (cause !in settings.backCauses) return false
            if (settings.backExcluded(from.world)) return false
            return from.distance(to) >= settings.backMinDistance
        }
    }
}
