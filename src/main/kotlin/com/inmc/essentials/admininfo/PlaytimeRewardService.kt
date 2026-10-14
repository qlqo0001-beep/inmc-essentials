package com.inmc.essentials.admininfo

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import kr.inmc.core.reward.RewardBundle
import kr.inmc.core.util.Text
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 플레이 시간 보상(CMI PlayTimeRewards — `/플레이보상`). 정의는 `ess_admininfo_rewards`(kind = `playtime`), 사람마다 상태는 `ess_admininfo_preward`.
 *
 * - 반복 보상의 진행([RewardState.progress])·쌓인 수는 표본마다 늘고, 늘어난 **차이**를 묶어서 **더한다**(누계 — 30초 묶음 허용, 공용 DB 의
 *   다른 서버가 더한 것과 섞여도 잃지 않는다).
 * - **받기 기록은 주기 전에** 쓴다(1회용 기록 — 규칙 5): 메모리를 먼저 바꾸고(같은 틱의 두 번 누르기 막기), DB 쓰기 스레드에서 **조건부로**
 *   (반복: `pending >= n` 일 때만 빼기 · 한 번: `claimed = 0` 일 때만 1 로) 쓴 뒤 바뀐 줄이 있을 때만 메인에서 준다 — 공용 DB 에서 다른 서버가
 *   먼저 받았으면 주지 않는다. 그 사이 나갔으면 우편으로(규칙 9 — core 보상 서비스의 우편함 = 우편 기능).
 * - 설정 `playtime-rewards.enabled: false` 면 전부 멈춘다(진행도 안 늘고 받을 수도 없다).
 */
internal class PlaytimeRewardService(private val ess: Ess, private val feature: AdminInfoFeature) {

    private val defs = ConcurrentHashMap<String, PlaytimeReward>()
    private val states = ConcurrentHashMap<UUID, ConcurrentHashMap<String, RewardState>>()

    /** 메인 — 아직 안 쓴 (진행 차이, 쌓인 수 차이). */
    private val deltas = HashMap<Pair<UUID, String>, Delta>()
    private val lastInform = HashMap<UUID, Long>()

    private class Delta(var progress: Long = 0, var pending: Int = 0)

    @Volatile
    var dryRun = false

    /** 끄는 중 — 새로 받지 않는다(받을 것은 그대로 남아 다음에 받는다). 메인으로 넘길 스케줄러가 이미 닫혔을 수 있다. */
    @Volatile
    var stopping = false

    private val settings get() = feature.settings

    fun load() {
        val (raw, rows) = ess.db.call { AdminStore.readRewards(ess.db.sql, KIND) to AdminStore.readStates(ess.db.sql) }
        defs.clear()
        for ((id, text) in raw) {
            try {
                defs[id] = PlaytimeReward.fromYaml(id, text)
            } catch (t: Throwable) {
                ess.logger.severe("플레이 시간 보상 '$id' 를 읽지 못했습니다(건너뜀): $t")
            }
        }
        states.clear()
        for ((id, map) in rows) states[id] = ConcurrentHashMap(map)
    }

    /** 공용 DB — 접속 직전(비동기 사건). 이 서버의 안 쓴 차이는 퇴장 때 이미 넘겼다(같은 쓰기 스레드라 이 읽기보다 먼저 들어간다). */
    fun preload(id: UUID) {
        val rows = ess.db.call { AdminStore.readStates(ess.db.sql, id) }[id] ?: return
        states[id] = ConcurrentHashMap(rows)
    }

    // --- 정의 ---------------------------------------------------------------------------------------

    /** 반복 먼저, 그다음 시간 순. */
    fun all(): List<PlaytimeReward> = defs.values.sortedWith(compareBy<PlaytimeReward>({ !it.repeat }, { it.seconds }, { it.id }))

    fun get(id: String): PlaytimeReward? = defs[id]

    fun save(def: PlaytimeReward) {
        defs[def.id] = def
        if (dryRun) return
        val text = def.toYaml()
        ess.db.run("플레이 시간 보상 저장") { AdminStore.writeReward(ess.db.sql, KIND, def.id, text) }
    }

    fun remove(id: String): Boolean {
        defs.remove(id) ?: return false
        for (map in states.values) map.remove(id)
        deltas.keys.removeAll { it.second == id }
        ess.db.run("플레이 시간 보상 지우기") {
            AdminStore.writeReward(ess.db.sql, KIND, id, null)
            AdminStore.deleteStates(ess.db.sql, id)
        }
        return true
    }

    /** 검증기 — 메모리만. */
    fun putMemory(def: PlaytimeReward?, id: String) {
        if (def == null) defs.remove(id) else defs[id] = def
    }

    // --- 상태 ---------------------------------------------------------------------------------------

    fun state(id: UUID, reward: String): RewardState = states.computeIfAbsent(id) { ConcurrentHashMap() }.computeIfAbsent(reward) { RewardState() }

    fun stateOrNull(id: UUID, reward: String): RewardState? = states[id]?.get(reward)

    /** 검증기·실패 되돌리기 — 메모리 상태를 [saved] 로(안 쓴 차이도 버린다). */
    fun restoreState(id: UUID, reward: String, saved: RewardState?) {
        if (saved == null) states[id]?.remove(reward) else states.computeIfAbsent(id) { ConcurrentHashMap() }[reward] = saved
        deltas.remove(id to reward)
    }

    fun allowed(player: Player, def: PlaytimeReward): Boolean =
        !(settings.rewardsRequirePermission || def.permission) || player.hasPermission(def.permissionNode)

    /** 한 번 보상에 닿았나. */
    fun reached(id: UUID, def: PlaytimeReward): Boolean = (feature.playtime.ticks(id) ?: 0L) >= def.periodTicks

    /** 지금 받을 수 있는 개수(반복 = 쌓인 것, 한 번 = 닿았고 안 받았으면 1). 기능 전체가 꺼졌으면 0. */
    fun claimable(player: Player, def: PlaytimeReward): Int {
        if (!settings.rewardsEnabled || !def.enabled || !allowed(player, def)) return 0
        val s = stateOrNull(player.uniqueId, def.id)
        if (!def.repeat) return if ((s?.claimed ?: 0) == 0 && reached(player.uniqueId, def)) 1 else 0
        val pending = (s?.pending ?: 0).coerceAtLeast(0)
        // 공용 DB 에서 두 서버가 더한 것이 겹쳐도 "안 쌓기" 는 하나까지만.
        return if (def.stack) pending.coerceAtMost(PlaytimeReward.MAX_PENDING) else pending.coerceAtMost(1)
    }

    // --- 표본마다 ---------------------------------------------------------------------------------------

    /** [PlaytimeService.sampleOne] 이 부른다 — 늘어난(잠수 제외 옵션이면 활동 중인) 틱. */
    fun onActive(player: Player, delta: Long) {
        if (!settings.rewardsEnabled) return
        for (def in defs.values) advance(player, def, delta)
    }

    /** 보상 하나에 [delta] 틱을 넘긴다(반복은 진행, 한 번은 닿았는지). 저절로 받기면 받는다. */
    fun advance(player: Player, def: PlaytimeReward, delta: Long) {
        if (!def.enabled || !allowed(player, def)) return
        val id = player.uniqueId
        if (def.repeat) {
            val s = state(id, def.id)
            val (progress, pending) = PlaytimeReward.advance(s.progress, s.pending, delta, def.periodTicks, def.stack)
            if (!dryRun) {
                val d = deltas.getOrPut(id to def.id) { Delta() }
                d.progress += progress - s.progress
                d.pending += pending - s.pending
            }
            s.progress = progress
            s.pending = pending
            if (def.autoClaim && s.pending > 0) claim(player, def)
        } else if (def.autoClaim && claimable(player, def) > 0) {
            claim(player, def)
        }
    }

    /**
     * 받기. 반복은 쌓인 것 전부, 한 번은 하나. 메모리를 먼저 바꾸고 → 조건부 기록 → 바뀌었으면 메인에서 준다. 받을 것이 없으면 0.
     */
    fun claim(player: Player, def: PlaytimeReward): Int {
        val n = claimable(player, def)
        if (n <= 0 || stopping) return 0
        val id = player.uniqueId
        val s = state(id, def.id)
        val previous = s.copy()
        if (def.repeat) s.pending = 0 else s.claimed = 1
        if (dryRun) return n
        // 이 사람 몫의 안 쓴 차이를 같이 넘긴다 — 조건부 빼기가 이 서버가 쌓은 것까지 보고 판단하게.
        val taken = deltas.remove(id to def.id)
        val progress = taken?.let { AdminStore.ProgressDelta(id, def.id, it.progress, it.pending) }
        val bundle = def.bundle.copyOf()
        val name = Text.plain(def.name)
        val repeat = def.repeat
        val all = !def.stack
        ess.db.run("플레이 시간 보상 받기 기록") {
            val won = runCatching {
                if (progress != null) AdminStore.addProgress(ess.db.sql, listOf(progress))
                if (repeat) AdminStore.claimRepeat(ess.db.sql, id, def.id, n, all) else AdminStore.claimOnce(ess.db.sql, id, def.id)
            }.onFailure { ess.logger.severe("플레이 시간 보상 기록을 쓰지 못해 주지 않습니다(${def.id} · $id): $it") }
            val wrote = won.isSuccess
            val ok = won.getOrDefault(false)
            val fresh = if (wrote && !ok) runCatching { AdminStore.readStates(ess.db.sql, id)[id]?.get(def.id) }.getOrNull() else null
            try {
                Bukkit.getGlobalRegionScheduler().run(ess.plugin) { _ ->
                    when {
                        !wrote -> {
                            // 아무것도 못 썼다 — 되돌리고 차이도 다시 걸어 둔다.
                            restoreState(id, def.id, previous)
                            if (taken != null) deltas.getOrPut(id to def.id) { Delta() }.also { it.progress += taken.progress; it.pending += taken.pending }
                        }
                        !ok -> {
                            // 다른 서버가 먼저 받았다 — DB 값으로 맞추고 주지 않는다.
                            ess.logger.info("플레이 시간 보상: 다른 서버에서 이미 받아 주지 않습니다(${def.id} · $id)")
                            if (fresh != null) states.computeIfAbsent(id) { ConcurrentHashMap() }[def.id] = fresh
                        }
                        else -> deliver(id, bundle, name, n)
                    }
                }
            } catch (t: Throwable) {
                // 기록은 썼는데 줄 수 없게 됐다(서버가 꺼지는 중) — 관리자가 보충할 수 있게 남긴다.
                if (ok) ess.logger.severe("플레이 시간 보상을 기록했지만 주지 못했습니다(서버 종료 중): $id · ${def.id} x$n — 직접 보충하세요")
            }
        }
        return n
    }

    /** 준다 — 접속 중이면 가방(넘치면 core 가 우편/바닥), 아니면 우편. */
    private fun deliver(id: UUID, bundle: RewardBundle, name: String, times: Int) {
        val source = "플레이 시간 보상 · $name"
        val online = Bukkit.getPlayer(id)
        repeat(times) {
            val payout = ess.rewards.resolve(bundle)
            if (payout.isEmpty()) return@repeat
            if (online != null) ess.rewards.give(online, payout, "플레이 시간 보상", source) else ess.rewards.mail(id, payout, source)
        }
        if (online != null) ess.messages.send(online, "admininfo-prewards-claimed", Ph.of().subject(name).count(times))
    }

    /** 반복 보상 진행 차이를 쓴다(주기·퇴장). [only] 면 그 사람만. */
    fun flush(only: UUID? = null) {
        if (dryRun) return
        val keys = deltas.keys.filter { only == null || it.first == only }
        if (keys.isEmpty()) return
        val rows = keys.mapNotNull { key -> deltas.remove(key)?.let { AdminStore.ProgressDelta(key.first, key.second, it.progress, it.pending) } }
        ess.db.run("플레이 시간 보상 진행 저장") { AdminStore.addProgress(ess.db.sql, rows) }
    }

    /**
     * CMI 가져오기 — 이미 받은 한 번 보상으로 적는다(메모리 + DB). 이미 받은 것으로 적힌 것은 건너뛴다. 적은 수.
     */
    fun markClaimed(rows: List<Pair<UUID, String>>): Int {
        val fresh = rows.distinct().filter { (id, reward) -> (stateOrNull(id, reward)?.claimed ?: 0) == 0 }
        for ((id, reward) in fresh) state(id, reward).claimed = 1
        if (fresh.isNotEmpty()) ess.db.run("플레이 시간 보상 받은 기록 가져오기") { AdminStore.markClaimed(ess.db.sql, fresh) }
        return fresh.size
    }

    /** 안 받은(손으로 받는) 보상 알림 — `playtime-rewards.inform-minutes` 마다. */
    fun inform(now: Long) {
        val minutes = settings.rewardsInformMinutes
        if (!settings.rewardsEnabled || minutes <= 0) return
        for (player in Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission(AdminInfoFeature.PREWARDS_NOTIFY) || !player.hasPermission(AdminInfoFeature.PREWARDS)) continue
            val last = lastInform[player.uniqueId] ?: 0L
            if (now - last < minutes * 60_000L) continue
            if (defs.values.none { !it.autoClaim && claimable(player, it) > 0 }) continue
            lastInform[player.uniqueId] = now
            player.sendActionBar(ess.messages.component("admininfo-prewards-inform"))
        }
    }

    fun forget(id: UUID) {
        lastInform.remove(id)
    }

    companion object {
        const val KIND = "playtime"
    }
}
