package com.inmc.essentials.afk

import com.inmc.essentials.Ess
import com.inmc.essentials.service.AfkState
import com.inmc.essentials.util.Ph
import kr.inmc.core.integration.Vanish
import kr.inmc.core.util.Durations
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.logging.Level

/**
 * 잠수 상태의 주인 — `ess.afk` 에 끼운다. 마지막 활동 시각은 어느 스레드에서나 적을 수 있고(채팅은 비동기), 잠수 들어가기·나오기는 메인에서만.
 */
class AfkService(private val ess: Ess, private val config: () -> AfkConfig) : AfkState {

    /** 잠수 하나. [since] = 잠수가 된 시각, [kickAt] = 추방(또는 반복 명령) 시각. */
    class Entry(val since: Long, val auto: Boolean, val reason: String?, var kickAt: Long?, var titleAt: Long = 0)

    private val lastActive = ConcurrentHashMap<UUID, Long>()
    private val entries = ConcurrentHashMap<UUID, Entry>()

    /** AFK 를 막으려고 꺼 둔 충돌 — 나올 때 되돌린다(그 사이 남이 바꿨으면 두고 · player 기능이 저장값으로 다시 건다). */
    private val collisionBefore = HashMap<UUID, Boolean>()

    /** 마지막으로 때리거나 맞은 시각 — 싸우다 `/잠수` 로 피하지 못하게. 메인. */
    private val lastCombat = HashMap<UUID, Long>()

    private var counter = 0

    /** 검증기가 도는 동안 true — 방송·명령·제목·추방 없이 상태만. */
    @Volatile
    var dryRun = false

    override fun isAfk(player: Player): Boolean = entries.containsKey(player.uniqueId)

    override fun lastActive(player: Player): Long = lastActive[player.uniqueId] ?: System.currentTimeMillis()

    fun entry(id: UUID): Entry? = entries[id]

    val afkCount: Int get() = entries.size

    /** 활동 — 시계를 되돌린다. 잠수였으면 메인에서 풀어 준다(비동기에서 불러도 된다). */
    fun activity(player: Player, now: Long = System.currentTimeMillis()) {
        lastActive[player.uniqueId] = now
        if (!entries.containsKey(player.uniqueId)) return
        if (Bukkit.isPrimaryThread()) leave(player) else player.scheduler.run(ess.plugin, { _ -> if (player.isOnline) leave(player) }, null)
    }

    /** 때렸거나 맞았다. */
    fun combat(id: UUID, now: Long = System.currentTimeMillis()) {
        lastCombat[id] = now
    }

    /** 스스로 `/잠수` 를 못 하는 남은 초(0 = 된다). */
    fun combatLeft(id: UUID, now: Long = System.currentTimeMillis()): Long {
        val block = config().combatBlockSeconds
        if (block <= 0) return 0
        val last = lastCombat[id] ?: return 0
        return ((last + block * 1000 - now + 999) / 1000).coerceAtLeast(0)
    }

    /** 지금 이 사람의 피해를 막나(잠수 + 설정 — 손 잠수는 정한 시간이 지나야). */
    fun protectsFromDamage(player: Player, now: Long = System.currentTimeMillis()): Boolean {
        val entry = entries[player.uniqueId] ?: return false
        return config().protectsFromDamage(entry.auto, now - entry.since, player.world.name)
    }

    fun joined(player: Player) {
        lastActive[player.uniqueId] = System.currentTimeMillis()
    }

    fun quit(player: Player) {
        lastActive.remove(player.uniqueId)
        entries.remove(player.uniqueId)
        lastCombat.remove(player.uniqueId)
        restoreCollision(player)
    }

    /** 잠수가 꺼 둔 충돌을 되돌린다 — 아직 우리가 둔 값(끔)일 때만(잠수 중 `/충돌` 로 바꿨으면 그대로). */
    private fun restoreCollision(player: Player) {
        val before = collisionBefore.remove(player.uniqueId) ?: return
        if (!player.isCollidable) player.isCollidable = before
    }

    // --- 들어가기 · 나오기 -------------------------------------------------------------------------

    /** 잠수가 된다. [by] = 남이 시켰으면 그 사람. [silent] 면 방송하지 않는다. 이미 잠수면 false. */
    fun enter(player: Player, auto: Boolean, reason: String?, silent: Boolean = false, now: Long = System.currentTimeMillis()): Boolean {
        if (entries.containsKey(player.uniqueId)) return false
        val c = config()
        val kick = c.kickAfter(Bukkit.getOnlinePlayers().size, kickOutIn(player), player.hasPermission(AfkFeature.KICK_BYPASS))
        val kickAt = kick?.let { now + (if (auto) 0 else c.autoAfkSeconds) * 1000 + it * 1000 }
        val entry = Entry(now, auto, reason?.takeIf { it.isNotBlank() }, kickAt)
        entries[player.uniqueId] = entry
        if (dryRun) return true
        if (c.preventPushing) {
            collisionBefore[player.uniqueId] = player.isCollidable
            player.isCollidable = false
        }
        showTitle(player, entry, now, fresh = true)
        ess.messages.send(player, if (entry.reason != null) "afk-enter-self-reason" else "afk-enter-self", Ph.of().reason(escape(entry.reason ?: "")))
        val quiet = Vanish.isVanished(player) && !c.commandsWhenVanished
        if (!silent && !quiet && c.broadcastEnter) {
            broadcast(player, if (entry.reason != null) "afk-broadcast-enter-reason" else "afk-broadcast-enter", Ph.of().player(player.name).reason(escape(entry.reason ?: "")))
        }
        if (!quiet) run(player, if (auto) c.autoEnterCommands else c.manualEnterCommands, entry, now, idleSeconds = (now - lastActive(player)) / 1000)
        return true
    }

    /** 잠수에서 나온다. [silent] 면 방송하지 않는다. 잠수가 아니었으면 false. */
    fun leave(player: Player, silent: Boolean = false, now: Long = System.currentTimeMillis()): Boolean {
        val entry = entries.remove(player.uniqueId) ?: return false
        lastActive[player.uniqueId] = now
        if (dryRun) return true
        val c = config()
        restoreCollision(player)
        // 우리가 제목을 띄웠을 때만 지운다(설정 title: false 면 남의 제목을 지울 까닭이 없다). 띄운 뒤 다른 플러그인이 제목을 바꿨는지는 서버가 알 수 없다 —
        // 그때는 그 제목도 지워진다(남은 한계 · docs/afk.md).
        if (entry.titleAt > 0) player.clearTitle()
        val away = Durations.formatShort((now - entry.since) / 1000)
        ess.messages.send(player, "afk-leave-self", Ph.of().time(away))
        val quiet = Vanish.isVanished(player) && !c.commandsWhenVanished
        if (!silent && !quiet && c.broadcastLeave) broadcast(player, "afk-broadcast-leave", Ph.of().player(player.name).time(away))
        if (!quiet) run(player, if (entry.auto) c.leaveAutoCommands else c.leaveManualCommands, entry, now, idleSeconds = (now - entry.since) / 1000)
        return true
    }

    /** 이 사람을 볼 수 있는 사람(+ 본인 빼고)에게만 — 투명인 사람의 잠수를 새지 않게(CLAUDE.md 8). 콘솔에도. */
    private fun broadcast(player: Player, key: String, ph: Ph) {
        for (viewer in Bukkit.getOnlinePlayers()) {
            if (viewer == player || !Vanish.canSee(viewer, player)) continue
            ess.messages.send(viewer, key, ph)
        }
        ess.messages.send(Bukkit.getConsoleSender(), key, ph)
    }

    private fun showTitle(player: Player, entry: Entry, now: Long, fresh: Boolean) {
        val c = config()
        if (!c.title) return
        val sub = if (c.subtitle) randomSubtitle() else ""
        val times = Title.Times.times(if (fresh) Duration.ofMillis(500) else Duration.ZERO, Duration.ofMinutes(11), Duration.ofMillis(500))
        player.showTitle(Title.title(ess.messages.component("afk-title"), MiniMessage.miniMessage().deserialize(sub), times))
        entry.titleAt = now
    }

    private fun randomSubtitle(): String {
        val lines = SUBTITLE_KEYS.map { ess.messages.raw(it) }.filter { it.isNotBlank() }
        return if (lines.isEmpty()) "" else lines[ThreadLocalRandom.current().nextInt(lines.size)]
    }

    /**
     * 명령 실행 — `player:` 로 시작하면 그 사람이, 아니면 콘솔이. `{player}` `{reason}` `{time}`(잠수가 된 뒤 지난 시간) `{idle}`(마지막 활동 뒤 쉰 시간,
     * [idleSeconds]). 추방 명령의 `{time}` 은 기본 추방 메시지처럼 **쉰 시간**이다(CMI AutoKickCmds 의 `[time]` — 2026-10-10 리뷰).
     */
    private fun run(player: Player, commands: List<String>, entry: Entry, now: Long, idleSeconds: Long, kick: Boolean = false) {
        val since = Durations.formatShort((now - entry.since) / 1000)
        val idle = Durations.formatShort(idleSeconds.coerceAtLeast(0))
        val time = if (kick) idle else since
        for (raw in commands) {
            val reason = commandSafe(entry.reason ?: "")
            val text = raw.replace("{player}", player.name).replace("{플레이어}", player.name)
                .replace("{reason}", reason).replace("{사유}", reason)
                .replace("{time}", time).replace("{시간}", time)
                .replace("{idle}", idle).replace("{쉰시간}", idle)
                .trim().removePrefix("/")
            try {
                if (text.startsWith("player:")) player.performCommand(text.removePrefix("player:").trim().removePrefix("/"))
                else Bukkit.dispatchCommand(Bukkit.getConsoleSender(), text)
            } catch (t: Throwable) {
                ess.logger.log(Level.WARNING, "잠수 명령 실패: $text", t)
            }
        }
    }

    /** 권한 `inmcessentials.afk.kickoutin.<초>` 중 가장 큰 것(직접 받은 것만 — op 가 모든 값을 갖지 않게). */
    private fun kickOutIn(player: Player): Long? =
        player.effectivePermissions.asSequence()
            .filter { it.value && it.permission.startsWith(AfkFeature.KICK_OUT_IN_PREFIX) }
            .mapNotNull { it.permission.removePrefix(AfkFeature.KICK_OUT_IN_PREFIX).toLongOrNull() }
            .maxOrNull()

    // --- 1초 ----------------------------------------------------------------------------------

    fun tick(now: Long) {
        val c = config()
        if (++counter < c.checkInterval) return
        counter = 0
        // 사본으로 돈다 — 들어가기 명령·추방이 접속자 목록을 바꾸면 살아 있는 목록의 반복이 깨진다(ConcurrentModificationException).
        val online = Bukkit.getOnlinePlayers().toList()
        val kicks = ArrayList<Pair<Player, Entry>>()
        for (player in online) {
            if (!player.isOnline) continue
            if (check(player, now, online.size, c)) entries[player.uniqueId]?.let { kicks += player to it }
        }
        for ((player, entry) in kicks) if (player.isOnline) kick(player, entry, now)
    }

    /**
     * 한 사람을 본다(주기마다 — 검증기도 이것을 부른다): 잠수가 아니면 쉰 시간이 넘었을 때 자동 잠수로, 잠수면 제목을 다시 띄우고 추방 시각을 본다.
     * 추방할 때면 true(다음 추방 시각은 이미 고쳤다 — 실제 추방은 부르는 쪽).
     */
    internal fun check(player: Player, now: Long, onlineCount: Int, c: AfkConfig = config()): Boolean {
        val id = player.uniqueId
        val entry = entries[id]
        if (entry == null) {
            if (c.autoAfkSeconds <= 0 || c.autoDisabledIn(player.world.name) || !player.hasPermission(AfkFeature.AUTO)) return false
            val last = lastActive.getOrPut(id) { now }
            if (now - last >= c.autoAfkSeconds * 1000) enter(player, auto = true, reason = null, now = now)
            return false
        }
        if (c.title && !dryRun && now - entry.titleAt > TITLE_REFRESH_MS) showTitle(player, entry, now, fresh = false)
        val kickAt = entry.kickAt ?: return false
        if (now < kickAt) return false
        if (onlineCount < c.kickFromPlayerCount) {
            entry.kickAt = now + c.checkInterval * 1000L
            return false
        }
        entry.kickAt = if (c.kickRepeat) now + c.kickRepeatInterval * 1000 else null
        return true
    }

    private fun kick(player: Player, entry: Entry, now: Long) {
        if (dryRun) return
        val c = config()
        val idle = (now - lastActive(player)) / 1000
        if (c.kickCommands.isEmpty()) {
            player.kick(ess.messages.component("afk-kick-reason", Ph.of().time(Durations.formatShort(idle))))
            return
        }
        run(player, c.kickCommands, entry, now, idleSeconds = idle, kick = true)
    }

    /** 끌 때 — 제목·충돌을 되돌린다(방송·명령 없이). */
    fun clear() {
        for (id in entries.keys) Bukkit.getPlayer(id)?.let { p ->
            p.clearTitle()
            restoreCollision(p)
        }
        entries.clear()
        lastCombat.clear()
    }

    /** 검증기 — 상태를 메모리에서 되돌린다. */
    internal fun restore(id: UUID, entry: Entry?, last: Long?) {
        if (entry == null) entries.remove(id) else entries[id] = entry
        if (last == null) lastActive.remove(id) else lastActive[id] = last
    }

    internal fun rawLast(id: UUID): Long? = lastActive[id]

    internal fun rawCombat(id: UUID): Long? = lastCombat[id]

    internal fun restoreCombat(id: UUID, at: Long?) {
        if (at == null) lastCombat.remove(id) else lastCombat[id] = at
    }

    companion object {
        val SUBTITLE_KEYS = listOf("afk-subtitle-1", "afk-subtitle-2", "afk-subtitle-3", "afk-subtitle-4", "afk-subtitle-5")

        /** 제목을 다시 보내는 간격(보이는 시간 11분보다 짧게). */
        const val TITLE_REFRESH_MS = 10 * 60_000L

        /** 사람이 친 사유를 MiniMessage 태그로 읽지 않게. */
        fun escape(text: String): String = MiniMessage.miniMessage().escapeTags(text)

        /**
         * 명령 속 `{reason}` 에 넣을 사유 — **사람이 친 글**이라 명령을 조작하지 못하게 거른다: 태그·색 코드·이스케이프·명령 구분에 쓰이는
         * 글자(`< > { } [ ] § & \ / | ; "`)와 줄바꿈을 지우고, 낱말 앞의 `-`(다른 명령의 깃발 `-s` 등)를 떼고, 빈칸을 하나로.
         */
        fun commandSafe(text: String): String =
            text.replace(Regex("""[<>{}\[\]§&\\/|;"\p{Cntrl}]"""), "")
                .split(Regex("\\s+")).map { it.trimStart('-') }.filter { it.isNotEmpty() }.joinToString(" ")
    }
}
