package com.inmc.essentials.admininfo

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import kr.inmc.core.integration.PluginClasses
import kr.inmc.core.integration.TitleForgeNames
import kr.inmc.core.integration.Vanish
import kr.inmc.core.reward.RewardBundle
import org.bukkit.Bukkit
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import org.bukkit.configuration.file.YamlConfiguration
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 투표(CMI Votifier 모듈). NuVotifier·VotifierPlus 의 `com.vexsoftware.votifier.model.VotifierEvent` 를 **리플렉션으로** 받는다(규칙 14 —
 * 그 플러그인의 클래스로더에서 이름으로 찾아 `registerEvent` + [EventExecutor]).
 *
 * - 수는 `ess_admininfo_votes` 에 **더하기**로(여러 서버가 같은 DB 를 써도 잃지 않게) — 1회용 기록이라 받는 순간 쓴다.
 * - 보상: 투표마다 묶음 하나 + 누적 단계(10·100 …)마다 묶음(단계마다 한 번 — `ess_admininfo_vote_milestone`). 접속하지 않은 사람은 우편.
 *   단계는 **그 수를 새로 지나는 투표**에만 준다([VoteMath.milestonesToPay]) — 관리자가 올린 수·가져온 수 아래의 단계를 다음 투표에 몰아 주지 않는다.
 *   빈 묶음의 단계(편집기에서 막 만든 것)는 받은 것으로 적지 않는다.
 * - `votes.count: false` 면 수와 단계는 건너뛰고 기록·알림·투표마다 보상은 그대로(CMI CountVotes·PerformCommands 가 따로인 것과 같게).
 * - 아이피 하루 한도(`votes.max-per-ip-per-day`)·같은 사이트 대기(`votes.cooldown-seconds`)는 `ess_admininfo_vote_log` 로(켤 때 최근 하루치를 읽는다).
 */
internal class VoteService(private val ess: Ess, private val feature: AdminInfoFeature) {

    class Row(@Volatile var name: String, @Volatile var votes: Int, @Volatile var lastVote: Long?)

    private val rows = ConcurrentHashMap<UUID, Row>()
    private val milestonesGiven = ConcurrentHashMap<UUID, MutableSet<Int>>()
    private val log = CopyOnWriteArrayList<AdminStore.LogRow>()

    /** 투표마다. */
    @Volatile
    var perVote: RewardBundle = RewardBundle()
        private set

    /** 누적 단계 → 묶음. */
    val milestones = ConcurrentHashMap<Int, RewardBundle>()

    @Volatile
    var dryRun = false

    @Volatile
    var hookedPlugin: String? = null
        private set

    private var listener: Listener? = null

    private val settings get() = feature.settings

    fun load() {
        val since = System.currentTimeMillis() - 86_400_000L * 2
        data class All(val votes: List<AdminStore.VoteRow>, val given: Map<UUID, Set<Int>>, val log: List<AdminStore.LogRow>, val defs: Map<String, String>)
        val all = ess.db.call {
            All(AdminStore.readVotes(ess.db.sql), AdminStore.readMilestones(ess.db.sql), AdminStore.readLog(ess.db.sql, since), AdminStore.readRewards(ess.db.sql, KIND))
        }
        rows.clear()
        for (r in all.votes) rows[r.id] = Row(r.name, r.votes, r.lastVote)
        milestonesGiven.clear()
        for ((id, set) in all.given) milestonesGiven[id] = ConcurrentHashMap.newKeySet<Int>().apply { addAll(set) }
        log.clear()
        log.addAll(all.log)
        milestones.clear()
        for ((id, text) in all.defs) {
            val bundle = try {
                RewardBundle.load(YamlConfiguration().apply { loadFromString(text) })
            } catch (t: Throwable) {
                ess.logger.severe("투표 보상 '$id' 를 읽지 못했습니다(건너뜀): $t")
                continue
            }
            if (id == PER_VOTE) perVote = bundle
            else id.removePrefix(MILESTONE).toIntOrNull()?.takeIf { it > 0 }?.let { milestones[it] = bundle }
        }
    }

    fun preload(id: UUID) {
        val (row, given) = ess.db.call { AdminStore.readVotes(ess.db.sql, id).firstOrNull() to AdminStore.readMilestones(ess.db.sql, id)[id] }
        if (row != null) rows[id] = Row(row.name, row.votes, row.lastVote)
        if (given != null) milestonesGiven[id] = ConcurrentHashMap.newKeySet<Int>().apply { addAll(given) }
    }

    // --- 읽기 ---------------------------------------------------------------------------------------

    fun votes(id: UUID): Int = rows[id]?.votes ?: 0

    fun lastVote(id: UUID): Long? = rows[id]?.lastVote

    /** 오늘(지난 24시간) 투표 수 — 그 사람. */
    fun today(id: UUID, now: Long): Int = log.count { it.id == id && now - it.at < DAY }

    fun top(): List<Triple<UUID, String, Int>> =
        rows.entries.filter { it.value.votes > 0 && it.value.name.lowercase() !in settings.voteExclude }
            .map { Triple(it.key, it.value.name, it.value.votes) }
            .sortedWith(compareByDescending<Triple<UUID, String, Int>> { it.third }.thenBy { it.second.lowercase() })

    // --- 보상 정의 ---------------------------------------------------------------------------------------

    fun savePerVote(bundle: RewardBundle) {
        perVote = bundle
        write(PER_VOTE, bundle)
    }

    fun saveMilestone(votes: Int, bundle: RewardBundle?) {
        if (bundle == null) milestones.remove(votes) else milestones[votes] = bundle
        write(MILESTONE + votes, bundle)
    }

    private fun write(id: String, bundle: RewardBundle?) {
        if (dryRun) return
        val text = bundle?.let { b -> YamlConfiguration().also { b.save(it) }.saveToString() }
        ess.db.run("투표 보상 저장") { AdminStore.writeReward(ess.db.sql, KIND, id, text) }
    }

    // --- 투표 받기 ---------------------------------------------------------------------------------------

    /** Votifier 사건을 건다(켜진 뒤 — 그 플러그인이 있으면). 건 플러그인 이름. */
    fun hook(): String? {
        for (name in VOTIFIER_PLUGINS) {
            val cls = PluginClasses.find(name, EVENT_CLASS) ?: continue
            if (!Event::class.java.isAssignableFrom(cls)) continue
            @Suppress("UNCHECKED_CAST")
            val type = cls as Class<out Event>
            val l = object : Listener {}
            val getVote = runCatching { cls.getMethod("getVote") }.getOrNull() ?: continue
            val executor = EventExecutor { _, event ->
                if (!type.isInstance(event)) return@EventExecutor
                try {
                    val vote = getVote.invoke(event) ?: return@EventExecutor
                    fun str(method: String): String = runCatching { vote.javaClass.getMethod(method).invoke(vote) as? String }.getOrNull().orEmpty()
                    val user = str("getUsername").trim()
                    val service = str("getServiceName").trim()
                    val address = str("getAddress").trim()
                    if (Bukkit.isPrimaryThread()) received(user, service, address)
                    else Bukkit.getGlobalRegionScheduler().run(ess.plugin) { _ -> received(user, service, address) }
                } catch (t: Throwable) {
                    ess.logger.warning("투표 사건을 읽지 못했습니다: $t")
                }
            }
            Bukkit.getPluginManager().registerEvent(type, l, EventPriority.MONITOR, executor, ess.plugin, true)
            listener = l
            hookedPlugin = name
            ess.logger.info("투표: $name 의 투표 사건을 받습니다")
            return name
        }
        return null
    }

    fun unhook() {
        listener?.let { HandlerList.unregisterAll(it) }
        listener = null
        hookedPlugin = null
    }

    enum class Outcome { COUNTED, UNKNOWN_PLAYER, IP_LIMIT, COOLDOWN, NOT_COUNTED }

    /** 투표 하나(메인). */
    fun received(username: String, service: String, address: String, now: Long = System.currentTimeMillis()): Outcome {
        if (username.isEmpty()) return Outcome.UNKNOWN_PLAYER
        val online = Bukkit.getPlayerExact(username)
        val record = online?.let { ess.users.get(it.uniqueId) } ?: ess.users.byName(username)
        val id = online?.uniqueId ?: record?.id
        if (id == null) {
            ess.logger.info("투표: 이 서버에 온 적 없는 이름이라 세지 않습니다 — $username ($service)")
            return Outcome.UNKNOWN_PLAYER
        }
        val name = online?.name ?: record?.name ?: username
        log.removeIf { now - it.at > DAY * 2 }
        if (settings.maxVotesPerIpPerDay > 0 && address.isNotEmpty() && log.count { it.address == address && now - it.at < DAY } >= settings.maxVotesPerIpPerDay) {
            ess.logger.info("투표: 아이피 하루 한도 — $name ($service · $address)")
            return Outcome.IP_LIMIT
        }
        val cd = settings.voteCooldownSeconds
        if (cd > 0 && log.any { it.id == id && it.service.equals(service, true) && now - it.at < cd * 1000 }) {
            ess.logger.info("투표: 같은 사이트 대기 중 — $name ($service)")
            return Outcome.COOLDOWN
        }
        val entry = AdminStore.LogRow(now, id, name, service, address)
        log += entry
        if (!dryRun) ess.db.run("투표 기록") { AdminStore.writeLog(ess.db.sql, entry) }
        if (!settings.countVotes) {
            // 세지 않아도 알림·투표마다 보상은 준다(단계는 수가 있어야 — 건너뜀).
            if (!dryRun) reward(id, name, service, votes(id), emptyList())
            return Outcome.NOT_COUNTED
        }

        val row = rows.computeIfAbsent(id) { Row(name, 0, null) }
        row.name = name
        val before = row.votes
        row.votes += 1
        row.lastVote = now
        val total = row.votes
        if (dryRun) return Outcome.COUNTED

        // 수를 먼저 쓰고(1회용 기록) 메인에서 보상. 단계는 빈 묶음이 아닌 것만 받은 것으로 적는다.
        val filled = milestones.filterValues { !it.isEmpty() }.keys
        val reached = if (settings.voteMilestones) VoteMath.milestonesToPay(filled, milestonesGiven[id] ?: emptySet(), before, total) else emptyList()
        milestonesGiven.computeIfAbsent(id) { ConcurrentHashMap.newKeySet() }.addAll(reached)
        ess.db.run("투표 수") {
            AdminStore.addVote(ess.db.sql, id, name, now)
            AdminStore.writeMilestones(ess.db.sql, reached.map { id to it })
            handOver(id, name, service, total, reached)
        }
        return Outcome.COUNTED
    }

    /** DB 쓰기 스레드 → 메인에서 [reward]. 서버가 꺼지는 중이라 넘기지 못하면 관리자가 보충할 수 있게 남긴다. */
    private fun handOver(id: UUID, name: String, service: String, total: Int, reached: List<Int>) {
        try {
            Bukkit.getGlobalRegionScheduler().run(ess.plugin) { _ -> reward(id, name, service, total, reached) }
        } catch (t: Throwable) {
            ess.logger.severe(
                "투표를 기록했지만 보상을 주지 못했습니다(서버 종료 중): $name($id) · 사이트 $service · 누적 $total" +
                    (if (reached.isEmpty()) "" else " · 누적 단계 $reached") + " — 투표마다 보상" + (if (reached.isEmpty()) "" else "과 단계 보상") + "을 직접 보충하세요",
            )
        }
    }

    private fun reward(id: UUID, name: String, service: String, total: Int, reached: List<Int>) {
        val player = Bukkit.getPlayer(id)
        if (settings.voteBroadcast) {
            val shown = player?.let { VanishService.displayName(it) } ?: name
            val ph = Ph.of().player(shown).count(total).raw("{사이트}", escape(service.ifEmpty { "?" }))
            Bukkit.getConsoleSender().let { ess.messages.send(it, "admininfo-votes-broadcast", ph) }
            for (other in Bukkit.getOnlinePlayers()) if (player == null || Vanish.canSee(other, player)) ess.messages.send(other, "admininfo-votes-broadcast", ph)
        }
        if (settings.voteRewards) give(id, player, perVote, "투표 보상", service)
        for (m in reached) milestones[m]?.let { give(id, player, it, "투표 ${m}회 보상", service) }
        if (player != null) ess.messages.send(player, "admininfo-votes-thanks", Ph.of().count(total))
    }

    private fun give(id: UUID, player: org.bukkit.entity.Player?, bundle: RewardBundle, source: String, service: String) {
        if (bundle.isEmpty()) return
        val payout = ess.rewards.resolve(bundle)
        if (payout.isEmpty()) return
        // 명령 속 사이트 이름(CMI [serviceName]) — core 는 {player} 만 바꾸므로 여기서.
        for (i in payout.commands.indices) payout.commands[i] = payout.commands[i].replace("{service}", service).replace("[serviceName]", service)
        if (player != null) ess.rewards.give(player, payout, source, source) else ess.rewards.mail(id, payout, source)
    }

    // --- 편집 ---------------------------------------------------------------------------------------

    /** 관리자 편집 — 결과 수. 단계 기록은 0 으로 비울 때만 같이 비운다(내렸다 다시 올려 받는 일을 막는다). */
    fun set(id: UUID, name: String, votes: Int): Int {
        val v = votes.coerceIn(0, Int.MAX_VALUE)
        val row = rows.computeIfAbsent(id) { Row(name, 0, null) }
        row.name = name
        row.votes = v
        if (dryRun) return v
        val last = row.lastVote
        val clear = v == 0
        if (clear) milestonesGiven.remove(id)
        ess.db.run("투표 편집") {
            AdminStore.setVotes(ess.db.sql, id, name, v, last)
            if (clear) AdminStore.clearMilestones(ess.db.sql, id)
        }
        return v
    }

    /** 가져오기 — 이미 표가 있으면(0 보다 크면) 건드리지 않는다. 넣었으면 true. */
    fun importCount(id: UUID, name: String, votes: Int): Boolean {
        if ((rows[id]?.votes ?: 0) > 0) return false
        rows[id] = Row(name, votes, rows[id]?.lastVote)
        ess.db.run("투표 가져오기") { AdminStore.setVotes(ess.db.sql, id, name, votes, null) }
        return true
    }

    /**
     * 가져온 수 아래의 누적 단계를 받은 것으로 적는다(CMI 가 이미 줬다 — 나중에 수를 내렸다 다시 올려도 두 번 받지 않게). 적은 단계 수.
     */
    fun markMilestonesUpTo(id: UUID, count: Int): Int {
        val given = milestonesGiven.computeIfAbsent(id) { ConcurrentHashMap.newKeySet() }
        val fresh = milestones.keys.filter { it <= count && it !in given }
        if (fresh.isEmpty()) return 0
        given.addAll(fresh)
        ess.db.run("투표 단계 가져오기") { AdminStore.writeMilestones(ess.db.sql, fresh.map { id to it }) }
        return fresh.size
    }

    fun prune() {
        if (dryRun) return
        val before = System.currentTimeMillis() - settings.voteLogDays * DAY
        ess.db.run("투표 기록 정리") { AdminStore.pruneLog(ess.db.sql, before) }
    }

    /** 검증기 — 메모리 되돌리기. 기록은 통째로 베껴 둔다(받기가 오래된 줄을 지우므로 크기로 되돌릴 수 없다). */
    class Snapshot(val row: Row?, val log: List<AdminStore.LogRow>, val given: Set<Int>?)

    fun snapshot(id: UUID): Snapshot = Snapshot(rows[id]?.let { Row(it.name, it.votes, it.lastVote) }, log.toList(), milestonesGiven[id]?.toSet())

    fun restore(id: UUID, saved: Snapshot) {
        if (saved.row == null) rows.remove(id) else rows[id] = saved.row
        log.clear()
        log.addAll(saved.log)
        if (saved.given == null) milestonesGiven.remove(id) else milestonesGiven[id] = ConcurrentHashMap.newKeySet<Int>().apply { addAll(saved.given) }
    }

    companion object {
        const val KIND = "vote"
        const val PER_VOTE = "per-vote"
        const val MILESTONE = "milestone-"
        const val DAY = 86_400_000L
        const val EVENT_CLASS = "com.vexsoftware.votifier.model.VotifierEvent"
        val VOTIFIER_PLUGINS = listOf("VotifierPlus", "Votifier", "NuVotifier")

        fun escape(text: String): String = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(text)

        /** 표시 이름(접속자) — 순위·편집 안내. */
        fun shownName(id: UUID, fallback: String): String = escape(TitleForgeNames.displayName(id, fallback))
    }
}
