package com.inmc.essentials.moderation

import com.inmc.essentials.service.TeleportKind
import com.inmc.essentials.store.UserRecord
import com.inmc.essentials.util.Ph
import kr.inmc.core.integration.Vanish
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerKickEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 뮤트·채팅 잠금·추방·수갑·경고·메모·경보·유저 메타·IP 잠금·IP 기록·순찰의 동작. 상태는 [ModState](메모리) → 바뀌는 순간 [ModStore].
 * 메인 스레드 — [recordIp] 만 접속 전(비동기) 사건에서도 부른다.
 */
class Records(private val mod: Moderation) {

    private val ess get() = mod.ess
    private val state get() = mod.state
    private val store get() = mod.store
    private val settings get() = mod.settings

    // --- 뮤트 ------------------------------------------------------------------------------------

    /** [change] null = 영구. 이미 뮤트면 `+`·`-` 는 남은 시간에 더하고 뺀다. */
    fun mute(sender: CommandSender, user: UserRecord, change: ModRules.Change?, reason: String?, silent: Boolean, shadow: Boolean) {
        if (!mod.canTarget(sender, user.id, user.name, ModerationFeature.MUTE_BYPASS)) return
        val now = System.currentTimeMillis()
        val existing = state.activeMute(user.id, now)
        val until = if (change == null) null else ModRules.applyChange(existing?.until, existing != null, change, now)
        if (until != null && until <= now) return unmute(sender, user, silent)
        val seconds = until?.let { (it - now) / 1000 }
        if (change?.mode != ModRules.Mode.SUB && !mod.withinLimit(sender, seconds ?: Long.MAX_VALUE, ModerationFeature.MUTE_MAX, settings.muteMaxWithout)) return
        val adjusting = existing != null && change != null && change.mode != ModRules.Mode.SET
        val why = reason?.takeIf { it.isNotBlank() } ?: existing?.reason?.takeIf { adjusting } ?: settings.muteDefaultReason
        val mute = Mute(user.id, until, why, mod.who(sender), if (adjusting) existing.shadow || shadow else shadow, now)
        state.mutes[user.id] = mute
        store.putMute(mute)

        val time = mod.timeText(seconds)
        val ph = Ph.of().player(user.name).time(time).reason(why).raw("{관리자}", mod.who(sender))
        val online = Bukkit.getPlayer(user.id)
        when {
            adjusting -> ess.messages.send(sender, "moderation-mute-changed", ph)
            mute.shadow -> ess.messages.send(sender, "moderation-shadowmute-done", ph)
            else -> ess.messages.send(sender, "moderation-mute-done", ph)
        }
        if (online != null && !mute.shadow) ess.messages.send(online, "moderation-mute-target", ph)
        // 운영진에게(그림자 뮤트도 — 운영진은 알아야 한다). 조용히면 보낸 사람만 안다.
        if (!silent) mod.staff(null, "moderation-mute-broadcast", ph, online)
        if (!adjusting) mod.runCommands(settings.onMute, user.name, why, time)
    }

    fun unmute(sender: CommandSender, user: UserRecord, silent: Boolean) {
        val had = state.mutes.remove(user.id)
        if (had == null || !had.active(System.currentTimeMillis())) return ess.messages.send(sender, "moderation-unmute-not", Ph.of().player(user.name))
        store.deleteMute(user.id)
        val ph = Ph.of().player(user.name)
        ess.messages.send(sender, "moderation-unmute-done", ph)
        if (!silent && !had.shadow) Bukkit.getPlayer(user.id)?.let { ess.messages.send(it, "moderation-unmute-target") }
    }

    // --- 공개 채팅 잠금 ------------------------------------------------------------------------------

    fun lockChat(sender: CommandSender, seconds: Long?, reason: String?, silent: Boolean) {
        val now = System.currentTimeMillis()
        state.chatLock = ChatLock(seconds?.let { now + it * 1000 }, reason?.takeIf { it.isNotBlank() }, mod.who(sender))
        val ph = Ph.of().time(mod.timeText(seconds)).reason(reason?.takeIf { it.isNotBlank() } ?: "-")
        if (silent) mod.staff(sender, "moderation-mutechat-on", ph, null)
        else for (r in Bukkit.getOnlinePlayers() + Bukkit.getConsoleSender()) ess.messages.send(r, "moderation-mutechat-on", ph)
    }

    fun unlockChat(sender: CommandSender?, silent: Boolean) {
        val had = state.chatLock
        state.chatLock = null
        if (had == null) {
            sender?.let { ess.messages.send(it, "moderation-mutechat-not") }
            return
        }
        if (silent) mod.staff(sender, "moderation-mutechat-off", Ph.of(), null)
        else for (r in Bukkit.getOnlinePlayers() + Bukkit.getConsoleSender()) ess.messages.send(r, "moderation-mutechat-off")
    }

    // --- 추방 ------------------------------------------------------------------------------------

    fun kick(sender: CommandSender, targets: List<Player>, reason: String?, silent: Boolean, all: Boolean) {
        val why = reason?.takeIf { it.isNotBlank() } ?: settings.kickDefaultReason
        var count = 0
        for (target in targets) {
            if (target == sender) {
                if (!all) ess.messages.send(sender, "moderation-self")
                continue
            }
            if (target.hasPermission(ModerationFeature.KICK_BYPASS)) {
                if (!all) ess.messages.send(sender, "moderation-protected", Ph.of().player(target.name))
                continue
            }
            val ph = Ph.of().player(target.name).reason(why).raw("{관리자}", mod.who(sender))
            target.kick(ess.messages.component("moderation-kick-screen", ph), PlayerKickEvent.Cause.PLUGIN)
            count++
            if (!all) {
                mod.announce(sender, "moderation-kick-broadcast", ph, target, silent, settings.kickBroadcast)
                if (sender is Player) ess.messages.send(sender, "moderation-kick-done", ph)
            }
            mod.runCommands(settings.onKick, target.name, why)
        }
        if (all) ess.messages.send(sender, "moderation-kick-all-done", Ph.of().count(count))
    }

    // --- 수갑 ------------------------------------------------------------------------------------

    /** [value] null = 바꾸기. */
    fun cuff(sender: CommandSender, user: UserRecord, value: Boolean?, silent: Boolean) {
        val on = value ?: !state.isCuffed(user.id)
        val ph = Ph.of().player(user.name)
        val online = Bukkit.getPlayer(user.id)
        if (on) {
            if (!mod.canTarget(sender, user.id, user.name, ModerationFeature.CUFF_BYPASS)) return
            val cuff = Cuff(user.id, mod.who(sender), System.currentTimeMillis())
            state.cuffs[user.id] = cuff
            store.putCuff(cuff)
            // 말·보트를 탄 채면 그대로 달린다 — 내리게 한다(리스너가 다시 타는 것을 막는다).
            if (online != null && online.isInsideVehicle) online.leaveVehicle()
            ess.messages.send(sender, "moderation-cuff-done", ph)
            if (online != null && !silent) ess.messages.send(online, "moderation-cuff-target-on")
        } else {
            if (state.cuffs.remove(user.id) == null) return ess.messages.send(sender, "moderation-cuff-not", ph)
            store.deleteCuff(user.id)
            ess.messages.send(sender, "moderation-cuff-undone", ph)
            if (online != null && !silent) ess.messages.send(online, "moderation-cuff-target-off")
        }
    }

    // --- 경고 ------------------------------------------------------------------------------------

    fun warn(sender: CommandSender, user: UserRecord, category: WarnCategory, reason: String?, silent: Boolean) {
        if (!mod.canTarget(sender, user.id, user.name, ModerationFeature.WARN_BYPASS)) return
        val now = System.currentTimeMillis()
        val before = state.points(user.id, now)
        val why = reason?.takeIf { it.isNotBlank() } ?: category.reason
        val warning = Warning(state.serial.incrementAndGet(), user.id, category.name, category.points, why, mod.who(sender), now,
            category.lifeSeconds.takeIf { it > 0 }?.let { now + it * 1000 })
        state.warnings.compute(user.id) { _, list -> list.orEmpty() + warning }
        store.insertWarning(warning)
        val after = before + category.points
        val online = Bukkit.getPlayer(user.id)
        val ph = Ph.of().player(user.name).reason(why).value(after.toString()).raw("{분류}", category.name).raw("{점수}", category.points.toString())
            .raw("{관리자}", mod.who(sender))
        mod.announce(sender, "moderation-warn-broadcast", ph, online, silent, settings.warnBroadcast)
        online?.let { ess.messages.send(it, "moderation-warn-target", ph) }
        if (sender is Player) ess.messages.send(sender, "moderation-warn-done", ph)
        for (threshold in ModRules.crossed(settings.warnPerform.keys, before, after)) {
            val lines = settings.warnPerform[threshold].orEmpty()
            mod.runCommands(lines, user.name, why)
            mod.staff(null, "moderation-warn-threshold", Ph.of().player(user.name).value(threshold.toString()).count(lines.size), online)
        }
    }

    fun removeWarning(user: UserRecord, warning: Warning) {
        state.warnings.computeIfPresent(user.id) { _, list -> list.filterNot { it.no == warning.no }.ifEmpty { null } }
        store.deleteWarning(warning.no)
    }

    fun clearWarnings(id: UUID) {
        state.warnings.remove(id)
        store.deleteWarningsOf(id)
    }

    fun clearAllWarnings(): Int {
        val n = state.warnings.values.sumOf { it.size }
        state.warnings.clear()
        store.deleteAllWarnings()
        return n
    }

    // --- 메모 ------------------------------------------------------------------------------------

    fun addNote(sender: CommandSender, user: UserRecord, text: String) {
        val note = Note(state.serial.incrementAndGet(), user.id, text, mod.who(sender), System.currentTimeMillis())
        state.notes.compute(user.id) { _, list -> list.orEmpty() + note }
        store.insertNote(note)
        ess.messages.send(sender, "moderation-note-added", Ph.of().player(user.name))
    }

    fun removeNote(user: UserRecord, note: Note) {
        state.notes.computeIfPresent(user.id) { _, list -> list.filterNot { it.no == note.no }.ifEmpty { null } }
        store.deleteNote(note.no)
    }

    fun clearNotes(id: UUID) {
        state.notes.remove(id)
        store.deleteNotesOf(id)
    }

    // --- 경보 ------------------------------------------------------------------------------------

    fun addAlert(sender: CommandSender, user: UserRecord, reason: String?) {
        val minutes = settings.alertMinutes
        val alert = Alert(user.id, reason?.takeIf { it.isNotBlank() }, mod.who(sender), System.currentTimeMillis() + minutes * 60_000)
        state.alerts[user.id] = alert
        store.putAlert(alert)
        val ph = Ph.of().player(user.name).time(mod.timeText(minutes * 60))
        ess.messages.send(sender, "moderation-alert-added", ph)
        if (Bukkit.getPlayer(user.id) != null) ess.messages.send(sender, "moderation-alert-online", ph)
    }

    fun removeAlert(sender: CommandSender, user: UserRecord) {
        if (state.alerts.remove(user.id) == null) return ess.messages.send(sender, "moderation-alert-not-found", Ph.of().player(user.name))
        store.deleteAlert(user.id)
        ess.messages.send(sender, "moderation-alert-removed", Ph.of().player(user.name))
    }

    fun extendAlert(sender: CommandSender, user: UserRecord) {
        val now = System.currentTimeMillis()
        val old = state.activeAlert(user.id, now) ?: return ess.messages.send(sender, "moderation-alert-not-found", Ph.of().player(user.name))
        val alert = old.copy(until = old.until + 86_400_000L)
        state.alerts[user.id] = alert
        store.putAlert(alert)
        ess.messages.send(sender, "moderation-alert-extended", Ph.of().player(user.name).time(mod.timeText((alert.until - now) / 1000)))
    }

    /** 경보가 걸린 사람이 들어왔을 때 — 볼 수 있는 운영진([ModerationFeature.ALERT_INFORM])에게. */
    fun informAlert(player: Player) {
        val now = System.currentTimeMillis()
        val alert = state.activeAlert(player.uniqueId, now) ?: return
        val notes = state.activeNotes(player.uniqueId).size
        val ph = Ph.of().player(player.name).reason(alert.reason ?: "-").count(notes).raw("{명령}", "/메모 ${player.name} 목록")
        val receivers = Bukkit.getOnlinePlayers().filter { it != player && it.hasPermission(ModerationFeature.ALERT_INFORM) && Vanish.canSee(it, player) } +
            Bukkit.getConsoleSender()
        for (r in receivers) {
            ess.messages.send(r, "moderation-alert-inform", ph)
            if (settings.notesOnAlert && notes > 0) ess.messages.send(r, "moderation-alert-notes", ph)
        }
    }

    // --- 유저 메타 ---------------------------------------------------------------------------------

    fun setMeta(sender: CommandSender, user: UserRecord, key: String, value: String, silent: Boolean) {
        val old = state.meta[user.id]?.get(key)
        state.meta.compute(user.id) { _, map -> LinkedHashMap(map.orEmpty()).apply { put(key, value) } }
        store.putMeta(user.id, key, value)
        if (silent) return
        val ph = Ph.of().player(user.name).value(value).raw("{키}", key)
        if (old != null) ess.messages.send(sender, "moderation-usermeta-changed", ph.reason(old))
        else ess.messages.send(sender, "moderation-usermeta-added", ph)
    }

    /** 숫자 값에 [delta] 를 더한다(없으면 0 에서). 숫자가 아니면 알리고 false. */
    fun incrementMeta(sender: CommandSender, user: UserRecord, key: String, delta: Double, silent: Boolean): Boolean {
        val old = state.meta[user.id]?.get(key)
        val base = old?.let { it.toDoubleOrNull() ?: return false.also { _ ->
            ess.messages.send(sender, "moderation-usermeta-not-number", Ph.of().value(it).raw("{키}", key))
        } } ?: 0.0
        val sum = base + delta
        val text = if (sum == Math.floor(sum) && !sum.isInfinite() && Math.abs(sum) < 1e15) sum.toLong().toString() else sum.toString()
        setMeta(sender, user, key, text, silent)
        return true
    }

    fun removeMeta(sender: CommandSender, user: UserRecord, key: String, silent: Boolean) {
        val ph = Ph.of().player(user.name).raw("{키}", key)
        val had = state.meta[user.id]?.containsKey(key) == true
        if (!had) return ess.messages.send(sender, "moderation-usermeta-not-found", ph)
        state.meta.computeIfPresent(user.id) { _, map -> (map - key).ifEmpty { null } }
        store.deleteMeta(user.id, key)
        if (!silent) ess.messages.send(sender, "moderation-usermeta-removed", ph)
    }

    fun clearMeta(sender: CommandSender, user: UserRecord, silent: Boolean) {
        state.meta.remove(user.id)
        store.deleteMeta(user.id, null)
        if (!silent) ess.messages.send(sender, "moderation-usermeta-cleared", Ph.of().player(user.name))
    }

    // --- IP 잠금·기록 --------------------------------------------------------------------------------

    fun addLockIp(sender: CommandSender, user: UserRecord, ip: String) {
        state.lockIps.compute(user.id) { _, set -> LinkedHashSet(set.orEmpty()).apply { add(ip) } }
        store.putLockIp(user.id, ip)
        ess.messages.send(sender, "moderation-lockip-added", Ph.of().player(user.name).raw("{아이피}", ip))
    }

    fun removeLockIp(sender: CommandSender, user: UserRecord, ip: String) {
        val ph = Ph.of().player(user.name).raw("{아이피}", ip)
        if (state.lockIps[user.id]?.contains(ip) != true) return ess.messages.send(sender, "moderation-lockip-not-found", ph)
        state.lockIps.computeIfPresent(user.id) { _, set -> (set - ip).ifEmpty { null } }
        store.deleteLockIp(user.id, ip)
        ess.messages.send(sender, "moderation-lockip-removed", ph)
    }

    fun clearLockIp(sender: CommandSender, user: UserRecord) {
        state.lockIps.remove(user.id)
        store.deleteLockIp(user.id, null)
        ess.messages.send(sender, "moderation-lockip-cleared", Ph.of().player(user.name))
    }

    /** 어느 스레드에서나(접속 전 사건). */
    fun recordIp(id: UUID, ip: String) {
        val now = System.currentTimeMillis()
        var seen: IpSeen? = null
        state.ips.compute(id) { _, list ->
            val old = list.orEmpty().firstOrNull { it.ip == ip }
            val next = old?.copy(lastSeen = now, times = old.times + 1) ?: IpSeen(id, ip, now, now, 1)
            seen = next
            list.orEmpty().filterNot { it.ip == ip } + next
        }
        seen?.let { store.putIp(it) }
    }

    // --- 순찰 ------------------------------------------------------------------------------------

    /** 순찰한 시각(이 서버에서 — 재시작하면 처음부터). */
    private val patrolled = ConcurrentHashMap<UUID, Long>()

    fun patrol(admin: Player, label: String) {
        val candidates = Vanish.visibleOnline(admin).filter { it != admin && !it.hasPermission(ModerationFeature.PATROL_EXEMPT) }
            .sortedBy { it.name.lowercase() }
        if (candidates.isEmpty()) return ess.messages.send(admin, "moderation-patrol-none")
        val target = candidates.minBy { patrolled[it.uniqueId] ?: Long.MIN_VALUE }
        val last = patrolled[target.uniqueId]
        val now = System.currentTimeMillis()
        ess.teleporter.teleport(admin, target.location, TeleportKind.ADMIN).thenAccept { ok ->
            Bukkit.getGlobalRegionScheduler().run(ess.plugin) { _ ->
                if (ok != true) return@run ess.messages.send(admin, "moderation-patrol-failed", Ph.of().player(target.name))
                patrolled[target.uniqueId] = now
                ess.gate?.commit(admin, label)
                ess.messages.send(admin, "moderation-patrol-now", Ph.of().player(target.name).count(candidates.size)
                    .raw("{번호}", (candidates.indexOf(target) + 1).toString()))
                if (last == null) ess.messages.send(admin, "moderation-patrol-never")
                else ess.messages.send(admin, "moderation-patrol-last", Ph.of().time(mod.timeText((now - last) / 1000)))
                val notes = state.activeNotes(target.uniqueId).size
                if (notes > 0) ess.messages.send(admin, "moderation-patrol-notes", Ph.of().count(notes).raw("{명령}", "/메모 ${target.name} 목록"))
            }
        }
    }

    // --- 만료 ------------------------------------------------------------------------------------

    /** 10초마다 — 끝난 뮤트(본인에게 알림)·채팅 잠금·경보·경고·메모를 치운다. */
    fun expire(now: Long) {
        for (mute in state.mutes.values.toList()) {
            if (mute.active(now)) continue
            state.mutes.remove(mute.id, mute)
            store.deleteMute(mute.id)
            if (!mute.shadow) Bukkit.getPlayer(mute.id)?.let { ess.messages.send(it, "moderation-mute-expired") }
        }
        state.chatLock?.let { if (!it.active(now)) unlockChat(null, silent = false) }
        for (alert in state.alerts.values.toList()) if (alert.until <= now) {
            state.alerts.remove(alert.id, alert)
            store.deleteAlert(alert.id)
        }
        var expiredWarnings = false
        for ((id, list) in state.warnings.entries.toList()) {
            if (list.all { it.active(now) }) continue
            state.warnings.computeIfPresent(id) { _, l -> l.filter { it.active(now) }.ifEmpty { null } }
            expiredWarnings = true
        }
        if (expiredWarnings) store.deleteExpiredWarnings(now)
        val days = settings.notesExpireDays
        if (days > 0) {
            val before = now - days * 86_400_000L
            var expired = false
            for ((id, list) in state.notes.entries.toList()) {
                if (list.none { it.created <= before }) continue
                state.notes.computeIfPresent(id) { _, l -> l.filter { it.created > before }.ifEmpty { null } }
                expired = true
            }
            if (expired) store.deleteExpiredNotes(before)
        }
    }
}
