package com.inmc.essentials.moderation

import com.inmc.essentials.Ess
import com.inmc.essentials.store.UserRecord
import com.inmc.essentials.util.Ph
import kr.inmc.core.integration.Vanish
import kr.inmc.core.util.Durations
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.logging.Level

/**
 * moderation 의 공용 도구 — 명령·리스너·화면이 같이 쓴다. 메인 스레드(따로 적은 것 빼고).
 *
 * 알림 규칙(CLAUDE.md 8): 대상이 투명이면 그 사람을 이름으로 알리는 방송은 그를 볼 수 있는 사람(+ 본인)에게만. `-s`(조용히)면 방송 대신
 * [ModerationFeature.NOTIFY] 권한이 있는 운영진과 보낸 사람·콘솔에게만 `[조용히]` 를 붙여.
 */
class Moderation(val ess: Ess, val feature: ModerationFeature) {

    val state: ModState get() = feature.state
    val store: ModStore get() = feature.store
    val settings: ModSettings get() = feature.settings

    /** 보낸 사람 이름(콘솔이면 "콘솔"). */
    fun who(sender: CommandSender): String = if (sender is Player) sender.name else ess.messages.raw("moderation-console")

    fun timeText(seconds: Long?): String =
        if (seconds == null || seconds == Long.MAX_VALUE) ess.messages.raw("moderation-permanent") else Durations.formatShort(seconds.coerceAtLeast(1))

    fun dateText(millis: Long): String = DATE.format(Instant.ofEpochMilli(millis))

    /** [sender] 의 최대 시간(초) — 권한 `<prefix>unlimited` · `<prefix><초>`, 없으면 [fallback](-1 = 제한 없음). 콘솔은 제한 없음. */
    fun maxSeconds(sender: CommandSender, prefix: String, fallback: Long): Long {
        if (sender !is Player) return Long.MAX_VALUE
        if (sender.hasPermission(prefix + "unlimited")) return Long.MAX_VALUE
        val nodes = sender.effectivePermissions.asSequence().filter { it.value }.map { it.permission.lowercase() }.toList()
        return ModRules.maxSeconds(nodes, prefix, fallback)
    }

    /** 시간 제한을 넘으면 알리고 false. */
    fun withinLimit(sender: CommandSender, seconds: Long, prefix: String, fallback: Long): Boolean {
        val max = maxSeconds(sender, prefix, fallback)
        if (seconds <= max) return true
        ess.messages.send(sender, "moderation-time-too-long", Ph.of().time(timeText(max)))
        return false
    }

    /**
     * 그 사람이 보호받나(우회 권한). 접속 중이면 권한으로, 아니면 op 인지로(오프라인 권한은 LuckPerms 없이 알 수 없다 — op 만 지킨다).
     * 자기 자신은 따로 막는다.
     */
    fun isProtected(id: UUID, bypass: String): Boolean {
        Bukkit.getPlayer(id)?.let { return it.hasPermission(bypass) }
        return Bukkit.getOfflinePlayer(id).isOp
    }

    /** 대상을 막을 수 있나 — 자기 자신·보호 권한을 알리고 false. */
    fun canTarget(sender: CommandSender, id: UUID, name: String, bypass: String): Boolean {
        if (sender is Player && sender.uniqueId == id) {
            ess.messages.send(sender, "moderation-self")
            return false
        }
        if (isProtected(id, bypass)) {
            ess.messages.send(sender, "moderation-protected", Ph.of().player(name))
            return false
        }
        return true
    }

    /**
     * 공지. [target] 이 접속 중인 투명이면 그를 볼 수 있는 사람에게만. [silent] 면 운영진([ModerationFeature.NOTIFY])·보낸 사람·콘솔에게만
     * `[조용히]` 를 붙여. [public] 이 false 면(설정에서 방송을 껐다) 조용히와 같은 길로.
     */
    fun announce(sender: CommandSender, key: String, ph: Ph, target: Player?, silent: Boolean, public: Boolean = true) {
        val quiet = silent || !public
        val tag = if (quiet) ess.messages.raw("moderation-silent-tag") else ""
        val body = ess.messages.raw(key)
        if (body.isEmpty()) return
        for (p in Bukkit.getOnlinePlayers()) {
            if (target != null && p != target && !Vanish.canSee(p, target)) continue
            if (quiet && p != sender && !p.hasPermission(ModerationFeature.NOTIFY)) continue
            ess.messages.sendRaw(p, tag + body, ph)
        }
        ess.messages.sendRaw(Bukkit.getConsoleSender(), tag + body, ph)
        if (sender !is Player && sender != Bukkit.getConsoleSender()) ess.messages.sendRaw(sender, tag + body, ph)
    }

    /** 운영진([ModerationFeature.NOTIFY])·보낸 사람·콘솔에게만(꼬리표 없이). [target] 이 투명이면 그를 볼 수 있는 운영진만. */
    fun staff(sender: CommandSender?, key: String, ph: Ph, target: Player?) {
        for (p in Bukkit.getOnlinePlayers()) {
            if (p != sender && !p.hasPermission(ModerationFeature.NOTIFY)) continue
            if (target != null && p != target && !Vanish.canSee(p, target)) continue
            ess.messages.send(p, key, ph)
        }
        ess.messages.send(Bukkit.getConsoleSender(), key, ph)
    }

    /** 설정의 명령 목록을 콘솔로. `{player}`·`[playerName]` = 대상, `{reason}`·`{time}`. */
    fun runCommands(lines: List<String>, target: String, reason: String = "", time: String = "") {
        for (raw in lines) {
            val line = raw.trim().removePrefix("/")
            if (line.isEmpty()) continue
            val cmd = line.replace("{player}", target).replace("[playerName]", target).replace("{플레이어}", target)
                .replace("{reason}", reason).replace("{사유}", reason).replace("{time}", time).replace("{시간}", time)
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)
            } catch (t: Throwable) {
                ess.logger.log(Level.WARNING, "명령 실행 실패: /$cmd", t)
            }
        }
    }

    /** 온 적 있는 사람 — 없으면 알리고 null. 기록이 없어도 접속 중이면 그 사람. */
    fun user(sender: CommandSender, input: String): UserRecord? {
        ess.args.offline(sender, input)?.let { return it }
        // 기록 전에 uuid 를 쳤을 수도 있다(데이터 도구·가져오기 뒤).
        runCatching { UUID.fromString(input.trim()) }.getOrNull()?.let { id ->
            return ess.users.get(id) ?: UserRecord(id, Bukkit.getOfflinePlayer(id).name ?: input, 0L, 0L)
        }
        ess.messages.send(sender, "user-not-found", Ph.of().value(input))
        return null
    }

    fun online(sender: CommandSender, input: String): Player? =
        ess.args.online(sender, input) ?: null.also { ess.messages.send(sender, "player-not-found", Ph.of().value(input)) }

    /** DB 쓰기 스레드에서 [work], 결과를 메인에서 [then]. 실패는 보낸 사람에게. */
    fun <T> query(sender: CommandSender, what: String, work: () -> T, then: (T) -> Unit) {
        ess.db.run(what) {
            val result = try {
                Result.success(work())
            } catch (t: Throwable) {
                ess.logger.log(Level.WARNING, "$what 실패", t)
                Result.failure(t)
            }
            Bukkit.getGlobalRegionScheduler().run(ess.plugin) {
                result.onSuccess(then).onFailure { ess.messages.send(sender, "moderation-data-failed", Ph.of().value(it.message ?: it.javaClass.simpleName)) }
            }
        }
    }

    fun usage(sender: CommandSender, text: String) = ess.messages.send(sender, "moderation-usage", Ph.of().value(text))

    companion object {
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }
}
