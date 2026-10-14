package com.inmc.essentials.admininfo

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.inmc.essentials.Ess
import org.bukkit.Bukkit
import org.bukkit.Statistic
import org.bukkit.entity.Player
import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 플레이 시간 — **원본은 바닐라 통계 `PLAY_ONE_MINUTE`(틱)**(사용자 결정 2026-10-10: CMI 값은 버린다). 여기는 그 사본과 하루별 기록만 든다.
 *
 * - [cache] 마지막으로 본 값(접속하지 않은 사람의 조회·순위용, `ess_admininfo_playtime`). 접속자는 늘 통계에서 바로 읽는다.
 *   바닐라 통계는 서버마다 따로라 이 사본은 공용 DB 를 써도 **로컬 DB** 에 둔다([local]).
 * - 표본([sample], `playtime.sample-seconds` 마다): 접속자마다 통계를 읽어 지난번과의 차이를 오늘 칸에 더한다(`ess_admininfo_playtime_day`,
 *   `playtime.flush-seconds` 마다 묶어 쓴다 — 돈·아이템이 아닌 누계라 묶어도 된다). 투명 + "플레이 시간 멈춤" 이면 통계를 되돌려 놓는다.
 *   그 차이(잠수 제외 옵션이면 잠수 중은 0)를 [onActive] 로 보상에 넘긴다.
 * - 편집: 접속자는 통계를 바로 고치고, 접속하지 않은 사람은 걸어 두었다가 다음 접속 때 적용한다(`ess_admininfo_playtime_edit`).
 *   적용한 줄은 **나갈 때** 지운다(통계 파일은 자동 저장·퇴장 때만 쓰인다). 그 전에 서버가 꺼졌으면 다음 접속 때 통계가 적용 전 값([applied] 의
 *   base)과 같으면 다시 적용하고, 다르면(이미 저장됨) 지우기만 한다.
 * - 켤 때 사본이 없는 사람은 월드의 통계 파일(`<world>/players/stats/<uuid>.json` 또는 옛 `<world>/stats/`)을 **비동기로** 읽어 채운다
 *   (CMI 의 "데이터를 훑는 중" 과 같은 일).
 */
internal class PlaytimeService(
    private val ess: Ess,
    private val feature: AdminInfoFeature,
    private val onActive: (Player, Long) -> Unit,
) {

    class Cached(@Volatile var name: String, @Volatile var ticks: Long, @Volatile var updated: Long)

    val cache = ConcurrentHashMap<UUID, Cached>()

    /** 메인 — 접속자마다 지난번에 읽은 통계. */
    private val last = HashMap<UUID, Long>()

    /** 메인 — 아직 안 쓴 하루별 증가분. */
    private val pendingDays = HashMap<UUID, HashMap<Long, Long>>()

    /** 접속하지 않은 사람에게 걸어 둔 편집. */
    private val edits = ConcurrentHashMap<UUID, PlaytimeMath.PendingEdit>()

    /** 적용했지만 통계가 아직 저장됐는지 모르는 편집 — (적용 전 통계, 적용 뒤 통계). 나갈 때 줄을 지운다. */
    private val applied = ConcurrentHashMap<UUID, Pair<Long, Long>>()

    /** 이번 접속에서 편집 줄을 적용·확인한 사람 — 나갈 때 줄을 지운다. */
    private val clearAtQuit = ConcurrentHashMap.newKeySet<UUID>()

    /** 사본을 두는 DB — 늘 로컬(쓰기 스레드에서만). */
    private val local get() = ess.db.source(network = false)

    /** 통계 파일을 훑는 중. */
    @Volatile
    var scanning = false
        private set

    @Volatile
    var dryRun = false

    private val settings get() = feature.settings

    fun load() {
        val (rows, pending) = ess.db.call { AdminStore.readPlaytime(local) to AdminStore.readEdits(ess.db.sql) }
        cache.clear()
        for (r in rows) cache[r.id] = Cached(r.name, r.ticks, r.updated)
        edits.clear()
        applied.clear()
        for ((id, row) in pending) put(id, row)
    }

    private fun put(id: UUID, row: AdminStore.EditRow?) {
        edits.remove(id)
        applied.remove(id)
        if (row == null) return
        if (row.base == null) edits[id] = row.edit else applied[id] = row.base to row.edit.ticks
    }

    /** 공용 DB — 접속 직전 그 사람의 걸어 둔 편집만 다시(다른 서버에서 걸었을 수 있다). */
    fun preload(id: UUID) {
        put(id, ess.db.call { AdminStore.readEdits(ess.db.sql, id) }[id])
    }

    // --- 읽기 -------------------------------------------------------------------------------------

    fun statOf(player: Player): Long = player.getStatistic(Statistic.PLAY_ONE_MINUTE).toLong()

    /** 그 사람의 플레이 시간(틱). 접속자는 통계, 아니면 사본(걸어 둔 편집 반영), 모르면 null. */
    fun ticks(id: UUID): Long? {
        Bukkit.getPlayer(id)?.let { return statOf(it) }
        val base = cache[id]?.ticks ?: return edits[id]?.takeIf { it.set }?.ticks
        return edits[id]?.applyTo(base) ?: base
    }

    fun pendingEdit(id: UUID): PlaytimeMath.PendingEdit? = edits[id]

    /** 순위 — (uuid, 이름, 틱). 제외 규칙(`playtime.top-exclude` · `top-offline-days`)을 거친다. */
    fun top(now: Long): List<Triple<UUID, String, Long>> {
        val online = Bukkit.getOnlinePlayers().associateBy { it.uniqueId }
        val ids = HashSet<UUID>(cache.keys).apply { addAll(online.keys) }
        return ids.mapNotNull { id ->
            val p = online[id]
            val name = p?.name ?: cache[id]?.name ?: ess.users.get(id)?.name ?: return@mapNotNull null
            val user = ess.users.get(id)
            val lastSeen = if (p != null) now else (user?.lastQuit ?: user?.lastJoin)
            if (PlaytimeMath.excluded(name, lastSeen, now, settings.topExclude, settings.topOfflineDays)) return@mapNotNull null
            val t = ticks(id) ?: return@mapNotNull null
            if (t <= 0) null else Triple(id, name, t)
        }.sortedWith(compareByDescending<Triple<UUID, String, Long>> { it.third }.thenBy { it.second.lowercase() })
    }

    // --- 표본 ---------------------------------------------------------------------------------------

    /** 접속 — 걸어 둔 편집을 적용하고 기준값을 잡는다. 줄은 나갈 때 지운다([quit]). */
    fun join(player: Player) {
        val id = player.uniqueId
        applied.remove(id)?.let { (base, next) ->
            // 지난번에 적용했는데 나가기 전에 꺼졌다 — 통계가 적용 전 그대로면 저장되지 않은 것이니 다시.
            val now = statOf(player)
            if (now == base && base != next) {
                player.setStatistic(Statistic.PLAY_ONE_MINUTE, next.toInt())
                ess.logger.info("플레이 시간 편집 다시 적용(지난번 저장 전에 꺼짐): ${player.name} ${now / 20}초 → ${next / 20}초")
            }
            clearAtQuit += id
        }
        edits.remove(id)?.let { edit ->
            val now = statOf(player)
            val next = edit.applyTo(now)
            player.setStatistic(Statistic.PLAY_ONE_MINUTE, next.toInt())
            ess.logger.info("플레이 시간 편집 적용: ${player.name} ${now / 20}초 → ${next / 20}초")
            clearAtQuit += id
            if (!dryRun) ess.db.run("플레이 시간 편집 적용 표시") { AdminStore.writeEdit(ess.db.sql, id, PlaytimeMath.PendingEdit(true, next), base = now) }
        }
        val stat = statOf(player)
        last[id] = stat
        val c = cache.computeIfAbsent(id) { Cached(player.name, stat, System.currentTimeMillis()) }
        c.name = player.name
        c.ticks = stat
    }

    /** 지금 통계를 기준으로 다시 잡는다(통계 편집 뒤 — 편집한 양이 "논 시간" 으로 세어지지 않게). */
    fun resync(player: Player) {
        val stat = statOf(player)
        last[player.uniqueId] = stat
        cache[player.uniqueId]?.ticks = stat
    }

    /** 접속자 모두. */
    fun sample() {
        val day = LocalDate.now().toEpochDay()
        for (player in Bukkit.getOnlinePlayers()) sampleOne(player, day)
    }

    /** 한 사람 — 늘어난 틱을 돌려준다(멈췄거나 줄었으면 0). */
    fun sampleOne(player: Player, day: Long = LocalDate.now().toEpochDay()): Long {
        val id = player.uniqueId
        val stat = statOf(player)
        val before = last[id]
        if (before == null || stat < before) {
            last[id] = stat
            cache[id]?.ticks = stat
            return 0
        }
        val delta = stat - before
        if (delta == 0L) return 0
        if (feature.vanish.stopsPlaytime(player)) {
            // 투명 중 멈춤 — 통계를 되돌린다(업적 등 통계를 읽는 다른 곳도 같이 멈춘다).
            player.setStatistic(Statistic.PLAY_ONE_MINUTE, before.toInt())
            return 0
        }
        last[id] = stat
        val c = cache.computeIfAbsent(id) { Cached(player.name, stat, System.currentTimeMillis()) }
        c.ticks = stat
        c.updated = System.currentTimeMillis()
        pendingDays.getOrPut(id) { HashMap() }.merge(day, delta, Long::plus)
        val active = if (settings.rewardsExcludeAfk && ess.afk.isAfk(player)) 0L else delta
        if (active > 0 && !dryRun) onActive(player, active)
        return delta
    }

    /** 퇴장 — 마지막 표본과 그 사람 몫 쓰기. 적용한 편집 줄은 이제 지운다(통계는 이 퇴장 사건 바로 뒤에 저장된다). */
    fun quit(player: Player) {
        val id = player.uniqueId
        sampleOne(player)
        last.remove(id)
        flush(only = id)
        if (clearAtQuit.remove(id) && !dryRun && !edits.containsKey(id)) ess.db.run("플레이 시간 편집 지우기") { AdminStore.writeEdit(ess.db.sql, id, null) }
    }

    /** 사본과 하루별 증가분을 쓴다. [only] 면 그 사람만. */
    fun flush(only: UUID? = null) {
        if (dryRun) return
        val ids = if (only != null) listOf(only) else Bukkit.getOnlinePlayers().map { it.uniqueId } + pendingDays.keys
        val rows = ids.distinct().mapNotNull { id -> cache[id]?.let { AdminStore.PlaytimeRow(id, it.name, it.ticks, it.updated) } }
        val days = HashMap<UUID, Map<Long, Long>>()
        for (id in ids) pendingDays.remove(id)?.let { days[id] = it }
        if (rows.isEmpty() && days.isEmpty()) return
        ess.db.run("플레이 시간 저장") {
            AdminStore.writePlaytime(local, rows)
            AdminStore.addDays(ess.db.sql, days)
        }
    }

    /** 오래된 하루 기록 지우기(`playtime.keep-days`). */
    fun prune() {
        val keep = settings.keepDays
        if (keep <= 0 || dryRun) return
        val before = LocalDate.now().toEpochDay() - keep
        ess.db.run("하루 기록 정리") { AdminStore.pruneDays(ess.db.sql, before) }
    }

    /** 하루별 기록 — DB 를 읽고(쓰기 스레드) 아직 안 쓴 것을 더해 메인에서 [then]. */
    fun days(id: UUID, then: (Map<Long, Long>) -> Unit) {
        ess.db.run("하루 기록 읽기") {
            val stored = AdminStore.readDays(ess.db.sql, id)
            Bukkit.getGlobalRegionScheduler().run(ess.plugin) { _ ->
                val merged = HashMap(stored)
                pendingDays[id]?.forEach { (d, t) -> merged.merge(d, t, Long::plus) }
                then(merged)
            }
        }
    }

    // --- 편집 ---------------------------------------------------------------------------------------

    /**
     * [id] 의 플레이 시간을 바꾼다. 접속 중이면 통계를 바로(결과 = (전, 후)), 아니면 걸어 둔다(사본의 보이는 값도 바로 바꾼다).
     * 사본이 없는 사람(한 번도 안 본 사람)의 더하기·빼기도 걸어 둔다 — 다음 접속 때 실제 통계에 적용된다.
     */
    fun edit(id: UUID, name: String, op: PlaytimeMath.Op, amountTicks: Long): Pair<Long?, Long?> {
        val change = PlaytimeMath.PendingEdit.of(op, amountTicks)
        val online = Bukkit.getPlayer(id)
        if (online != null) {
            sampleOne(online)
            val before = statOf(online)
            val after = change.applyTo(before)
            online.setStatistic(Statistic.PLAY_ONE_MINUTE, after.toInt())
            resync(online)
            return before to after
        }
        val before = ticks(id)
        val composed = PlaytimeMath.compose(edits[id], change)
        edits[id] = composed
        if (!dryRun) ess.db.run("플레이 시간 편집 걸기") { AdminStore.writeEdit(ess.db.sql, id, composed) }
        cache[id]?.name = name
        return before to ticks(id)
    }

    /** 검증기 — 메모리만 되돌리기. */
    fun memory(id: UUID): Triple<Long?, Map<Long, Long>?, Cached?> = Triple(last[id], pendingDays[id]?.let { HashMap(it) }, cache[id]?.let { Cached(it.name, it.ticks, it.updated) })

    fun restoreMemory(id: UUID, saved: Triple<Long?, Map<Long, Long>?, Cached?>) {
        if (saved.first == null) last.remove(id) else last[id] = saved.first!!
        if (saved.second == null) pendingDays.remove(id) else pendingDays[id] = HashMap(saved.second!!)
        if (saved.third == null) cache.remove(id) else cache[id] = saved.third!!
    }

    fun setLastMemory(id: UUID, value: Long) {
        last[id] = value
    }

    fun pendingToday(id: UUID): Long = pendingDays[id]?.get(LocalDate.now().toEpochDay()) ?: 0L

    // --- 통계 파일 훑기 ---------------------------------------------------------------------------------

    /** 사본이 없는 온 적 있는 사람을 통계 파일에서 채운다(비동기 읽기 → 메인에서 넣기 → DB). */
    fun scanMissing() {
        if (scanning) return
        val folder = statsFolder() ?: return
        val missing = ess.users.all().filter { !cache.containsKey(it.id) && Bukkit.getPlayer(it.id) == null }.map { it.id to it.name }
        if (missing.isEmpty()) return
        scanning = true
        ess.io.async({
            missing.mapNotNull { (id, name) -> readPlayTime(File(folder, "$id.json"))?.let { Triple(id, name, it) } }
        }) { found ->
            scanning = false
            val now = System.currentTimeMillis()
            val rows = found.filter { (id) -> !cache.containsKey(id) }.map { (id, name, t) ->
                cache[id] = Cached(name, t, now)
                AdminStore.PlaytimeRow(id, name, t, now)
            }
            if (rows.isNotEmpty()) {
                ess.logger.info("플레이 시간: 통계 파일에서 ${rows.size}명을 읽었습니다")
                ess.db.run("플레이 시간 훑기 저장") { AdminStore.writePlaytime(local, rows) }
            }
        }
    }

    companion object {

        /** 기본 월드의 통계 폴더(26.x `players/stats`, 옛 `stats`). 메인에서 — 월드 목록을 본다. */
        fun statsFolder(): File? {
            val world = Bukkit.getWorlds().firstOrNull() ?: return null
            // 26.x 는 차원 폴더가 `world/dimensions/...` 로 갈라졌다 — 레벨 뿌리(서버 폴더/월드 이름)도 본다.
            val roots = listOf(File(Bukkit.getWorldContainer(), world.name), world.worldFolder).distinct()
            return roots.flatMap { listOf(File(it, "players/stats"), File(it, "stats")) }.firstOrNull { it.isDirectory }
        }

        /** 통계 파일 전체(없거나 깨졌으면 null). 아무 스레드. */
        fun readStats(file: File): JsonObject? =
            if (!file.isFile) null else runCatching { JsonParser.parseString(file.readText()).asJsonObject.getAsJsonObject("stats") }.getOrNull()

        fun readPlayTime(file: File): Long? =
            readStats(file)?.getAsJsonObject("minecraft:custom")?.get("minecraft:play_time")?.asLong
    }
}
