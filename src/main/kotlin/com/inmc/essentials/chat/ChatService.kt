package com.inmc.essentials.chat

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import kr.inmc.core.integration.PlayerSettings
import kr.inmc.core.integration.TitleForgeNames
import kr.inmc.core.integration.Vanish
import kr.inmc.core.util.Text
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import java.time.Duration
import java.util.UUID

/**
 * 귓속말·답장·도움요청·스태프 채널·공지·타이틀·스파이 알림. 명령(`ChatCommands`)과 리스너(`ChatListener`)가 부른다.
 * 귓속말은 메인 스레드(명령), 스파이·스태프 모드는 비동기 채팅 사건에서도 불린다 — `sendMessage` 와 메모리 표만 만진다.
 */
class ChatService(private val ess: Ess, private val feature: ChatFeature) {

    private val settings: ChatSettings get() = feature.settings
    private val store: ChatStore get() = feature.store

    // --- 이름 ----------------------------------------------------------------------------------

    fun idOf(sender: CommandSender): UUID = (sender as? Player)?.uniqueId ?: CONSOLE

    /** 방송·알림용 이름(TitleForge 닉네임). 콘솔은 설정의 이름. */
    fun nameOf(sender: CommandSender): String = when (sender) {
        is Player -> TitleForgeNames.displayName(sender.uniqueId, sender.name)
        is ConsoleCommandSender -> settings.consoleName
        else -> sender.name
    }

    /** 명령에 넣을 실명. */
    private fun realName(sender: CommandSender): String = (sender as? Player)?.name ?: settings.consoleName

    private fun ph(from: CommandSender, to: CommandSender?): Ph {
        val ph = Ph.of().player(nameOf(from)).raw("{실명}", realName(from)).raw("{name}", realName(from))
        if (to != null) ph.target(nameOf(to)).raw("{대상실명}", realName(to)).raw("{target-name}", realName(to))
        return ph
    }

    /** 틀 + 사람의 글을 [to] 에게. 틀이 비었으면 보내지 않는다. */
    private fun deliver(to: CommandSender, key: String, ph: Ph, body: Component) {
        ChatText.render(ess.messages.raw(key), ph, body, to as? Player)?.let(to::sendMessage)
    }

    fun playSound(player: Player, sound: ChatSound?) {
        val spec = sound ?: return
        runCatching { player.playSound(player.location, spec.key, spec.volume, spec.pitch) }
    }

    /** 사람이 친 글 → 조각(색 권한 · 링크 축약). */
    fun bodyOf(sender: CommandSender, text: String): Component {
        val raw = ChatText.body(text, sender.hasPermission(ChatFeature.COLOR))
        return if (settings.linkEnabled) ChatText.shortenLinks(raw, settings.linkRegex, ess.messages.raw("chat-link-label")) else raw
    }

    // --- 뮤트 ------------------------------------------------------------------------------------

    enum class Muted { NO, YES, SHADOW }

    fun muted(sender: CommandSender): Muted {
        val player = sender as? Player ?: return Muted.NO
        val state = ess.moderation
        return when {
            state.isShadowMuted(player.uniqueId) -> Muted.SHADOW
            state.isMuted(player.uniqueId) -> Muted.YES
            else -> Muted.NO
        }
    }

    // --- 귓속말 ----------------------------------------------------------------------------------

    /**
     * [sender] → [target] 귓속말. 막히면 까닭을 [sender] 에게 알리고 false.
     * 순서: 자기 자신 → 길이 → 보통 뮤트(`deny-when-muted` 일 때만 막기) → 도배 → 그룹 권한 → 무시 → 받기 끔([whisperBlock]) → 그림자 뮤트면 보낸 척만.
     * 그룹·무시·받기 끔은 **그림자 뮤트여도 똑같이** 본다 — 결과가 달라지면 본인이 그림자 뮤트를 알아챈다.
     */
    fun whisper(sender: CommandSender, target: CommandSender, raw: String): Boolean {
        val s = settings
        if (sender is Player && target is Player && sender.uniqueId == target.uniqueId) {
            ess.messages.send(sender, "chat-msg-self")
            return false
        }
        val parsed = ChatRules.parseWhisper(raw, sender.hasPermission(ChatFeature.MSG_CLEAN), sender.hasPermission(ChatFeature.MSG_NOREPLY))
        if (parsed.text.isEmpty()) return false
        if (parsed.text.length > s.maxLength) {
            ess.messages.send(sender, "chat-msg-too-long", Ph.of().count(s.maxLength))
            return false
        }
        val muted = muted(sender)
        if (muted == Muted.YES && s.denyWhenMuted) {
            ess.messages.send(sender, "chat-msg-muted")
            return false
        }
        if (sender is Player && s.spamEnabled && s.spamPrivate && !sender.hasPermission(ChatFeature.SPAM_BYPASS) &&
            feature.spam.check(sender.uniqueId, "private", parsed.text, System.currentTimeMillis(), s)
        ) {
            ess.messages.send(sender, "chat-spam-blocked")
            return false
        }
        if (target is Player) {
            val block = whisperBlock((sender as? Player)?.uniqueId, sender::hasPermission, target, PlayerSettings.enabled(target, ChatFeature.ACCEPT_PM, true))
            if (block != null) {
                ess.messages.send(sender, block.first, Ph.of().target(nameOf(target)).value(block.second))
                return false
            }
        }

        val body = bodyOf(sender, parsed.text)
        val ph = ph(sender, target)
        deliver(sender, "chat-msg-to", ph, body)
        val now = System.currentTimeMillis()
        val from = idOf(sender)
        val to = idOf(target)
        feature.book.sent(from, to, now)
        // 잠수 안내는 그림자 뮤트여도 똑같이(안 오면 본인이 알아챈다).
        if (target is Player && ess.afk.isAfk(target)) ess.messages.send(sender, "chat-msg-target-afk", Ph.of().target(nameOf(target)))
        if (muted == Muted.SHADOW) {
            // 그림자 뮤트 — 본인에게만 보낸 것처럼. 스파이에게는 표시를 달아 보인다(운영진이 알아야 한다).
            spyWhisper(sender, target, body, shadow = true)
            return true
        }
        deliver(target, if (parsed.clean) "chat-msg-clean" else "chat-msg-from", ph, body)
        if (!parsed.noReply) feature.book.received(to, from, now)
        if (target is Player) {
            if (PlayerSettings.enabled(target, ChatFeature.PM_SOUND, true)) playSound(target, s.pmSound)
        }
        spyWhisper(sender, target, body, shadow = false)
        if (s.logPrivate) ess.logger.info("[귓속말] ${realName(sender)} -> ${realName(target)}: ${parsed.text}")
        return true
    }

    /**
     * 받는 사람 쪽 막기 — (메시지 키, 그룹 이름) 또는 null. 보내지 않고 판단만(검증기도 부른다). [senderId] 는 보낸 플레이어(콘솔이면 null),
     * [may] 는 보낸 쪽의 권한, [accepting] 은 받는 사람의 "귓속말 받기" 개인 설정.
     */
    internal fun whisperBlock(senderId: UUID?, may: (String) -> Boolean, target: Player, accepting: Boolean): Pair<String, String>? {
        val s = settings
        if (s.groupsEnabled && !may(ChatFeature.MSG_BYPASS)) {
            val group = ChatRules.groupOf(s.groups) { node -> target.isPermissionSet(node) && target.hasPermission(node) }
            if (group != null && !may(ChatFeature.MSG_GROUP_PREFIX + group)) return "chat-msg-group-denied" to group
        }
        if (senderId != null && store.ignores(target.uniqueId, senderId) && !may(ChatFeature.IGNORE_BYPASS)) return "chat-msg-ignored" to ""
        if (!accepting && !may(ChatFeature.MSG_BYPASS)) return "chat-msg-not-accepting" to ""
        return null
    }

    /** /답장 — 장부의 상대([ReplyBook.target]). 상대가 나갔거나 볼 수 없는 투명이면 "없음". */
    fun reply(sender: CommandSender, raw: String) {
        val targetId = feature.book.target(idOf(sender), System.currentTimeMillis(), settings)
            ?: return ess.messages.send(sender, "chat-reply-none")
        val target: CommandSender = if (targetId == CONSOLE) Bukkit.getConsoleSender()
        else Bukkit.getPlayer(targetId)?.takeIf { Vanish.canSee(sender, it) }
            ?: return ess.messages.send(sender, "chat-reply-gone")
        whisper(sender, target, raw)
    }

    private fun spyWhisper(sender: CommandSender, target: CommandSender, body: Component, shadow: Boolean) {
        val ph = ph(sender, target)
        val key = if (shadow) "chat-spy-msg-shadow" else "chat-spy-msg"
        for (spy in Bukkit.getOnlinePlayers()) {
            if (spy == sender || spy == target) continue
            if (!spy.hasPermission(ChatFeature.SPY) || !store.spying(spy.uniqueId, ChatStore.Spy.CHAT)) continue
            if (sender is Player && !Vanish.canSee(spy, sender)) continue
            if (target is Player && !Vanish.canSee(spy, target)) continue
            deliver(spy, key, ph, body)
        }
    }

    // --- 도움요청 · 스태프 ---------------------------------------------------------------------------

    fun helpop(sender: CommandSender, raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        if (text.length > settings.maxLength) return ess.messages.send(sender, "chat-msg-too-long", Ph.of().count(settings.maxLength))
        val muted = muted(sender)
        if (muted == Muted.YES && settings.denyWhenMuted) return ess.messages.send(sender, "chat-helpop-muted")
        val body = bodyOf(sender, text)
        val ph = ph(sender, null)
        val staff = helpopStaff(sender, Bukkit.getOnlinePlayers())
        if (muted != Muted.SHADOW) {
            for (member in staff) {
                deliver(member, "chat-helpop-message", ph, body)
                playSound(member, settings.pmSound)
            }
            ess.logger.info("[도움요청] ${realName(sender)}: $text")
        }
        // 볼 수 있는 운영진이 없으면 "지금 없음" — 투명인 운영진에게는 그래도 간다(있다는 것을 알리지 않는다).
        val visible = staff.count { sender !is Player || Vanish.canSee(sender, it) }
        when {
            visible == 0 -> ess.messages.send(sender, "chat-helpop-offline")
            settings.helpopFeedback -> ess.messages.send(sender, "chat-helpop-feedback")
            else -> deliver(sender, "chat-helpop-message", ph, body)
        }
    }

    /** 도움요청을 받을 운영진 — `…helpop.receive` 권한자 중 보낸 사람을 볼 수 있는 사람(투명 규칙). 보낸 사람 자신은 빼고. */
    internal fun helpopStaff(sender: CommandSender, online: Collection<Player>): List<Player> = online.filter {
        it != sender && it.hasPermission(ChatFeature.HELPOP_RECEIVE) && (sender !is Player || Vanish.canSee(it, sender))
    }

    /**
     * 스태프 채널로. 받는 사람은 권한이 있는 사람 중 보낸 사람을 볼 수 있는 사람(투명 규칙) + 콘솔.
     * 그림자 뮤트면 보낸 사람에게만 보낸 것처럼(보통 뮤트는 막지 않는다 — 스태프끼리의 채널이다. 공개 채팅을 돌린 것은 moderation 이 먼저 막는다).
     */
    fun staff(sender: CommandSender, raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val body = bodyOf(sender, text)
        val ph = ph(sender, null)
        if (muted(sender) == Muted.SHADOW) return deliver(sender, "chat-staff-message", ph, body)
        for (member in Bukkit.getOnlinePlayers()) {
            if (!member.hasPermission(ChatFeature.STAFF)) continue
            if (sender is Player && !Vanish.canSee(member, sender)) continue
            deliver(member, "chat-staff-message", ph, body)
        }
        if (sender !is ConsoleCommandSender) deliver(Bukkit.getConsoleSender(), "chat-staff-message", ph, body)
    }

    // --- 공지 · 타이틀 -----------------------------------------------------------------------------

    /** /공지 — 받은 사람 수. 옵션이 이상하면 알리고 -1. */
    fun broadcast(sender: CommandSender, raw: String): Int {
        val parsed = ChatRules.parseBroadcast(raw)
        if (parsed.text.isEmpty()) {
            ess.messages.send(sender, "chat-broadcast-empty")
            return -1
        }
        ChatRules.broadcastRangeProblem(parsed)?.let { bad ->
            ess.messages.send(sender, "chat-broadcast-range-invalid", Ph.of().value(bad))
            return -1
        }
        val unknown = parsed.worlds.filter { name -> Bukkit.getWorlds().none { it.name.equals(name, ignoreCase = true) } }
        if (unknown.isNotEmpty()) {
            ess.messages.send(sender, "chat-broadcast-world-unknown", Ph.of().world(unknown.joinToString(", ")))
            return -1
        }
        val center: Location? = when {
            parsed.center != null -> centerOf(parsed.center) ?: run {
                ess.messages.send(sender, "chat-broadcast-center-invalid", Ph.of().value(parsed.center.joinToString(";")))
                return -1
            }
            parsed.range != null -> (sender as? Player)?.location ?: run {
                ess.messages.send(sender, "chat-broadcast-center-invalid", Ph.of().value("-c"))
                return -1
            }
            else -> null
        }
        val worlds = parsed.worlds.map { it.lowercase() }.toSet()
        val receivers = broadcastReceivers(Bukkit.getOnlinePlayers(), worlds, center, parsed.range)
        // 공지 글은 관리자가 쓰는 것이라 MiniMessage·PAPI 를 그대로(받는 사람 기준) 푼다.
        val template = if (parsed.clean) "{메시지}" else ess.messages.raw("chat-broadcast-format")
        for (p in receivers) p.sendMessage(Text.render(template.replace("{메시지}", parsed.text).replace("{message}", parsed.text), null, p))
        if (worlds.isEmpty() && center == null) {
            Bukkit.getConsoleSender().sendMessage(Text.render(template.replace("{메시지}", parsed.text).replace("{message}", parsed.text)))
        }
        return receivers.size
    }

    /** 공지를 받을 사람 — [worlds](소문자, 비면 모두) 안이고, [center]·[range] 가 있으면 그 거리 안. */
    fun broadcastReceivers(online: Collection<Player>, worlds: Set<String>, center: Location?, range: Double?): List<Player> =
        online.filter { p ->
            (worlds.isEmpty() || p.world.name.lowercase() in worlds) &&
                (center == null || range == null || (p.world == center.world && p.location.distanceSquared(center) <= range * range))
        }

    private fun centerOf(parts: List<String>): Location? {
        if (parts.size < 4) return null
        val world = Bukkit.getWorlds().firstOrNull { it.name.equals(parts[0].trim(), ignoreCase = true) } ?: return null
        val x = parts[1].trim().toDoubleOrNull() ?: return null
        val y = parts[2].trim().toDoubleOrNull() ?: return null
        val z = parts[3].trim().toDoubleOrNull() ?: return null
        return Location(world, x, y, z)
    }

    /** /타이틀 — 제목·부제목은 MiniMessage·PAPI(받는 사람 기준). */
    fun title(targets: List<Player>, raw: String): Boolean {
        val parsed = ChatRules.parseTitle(raw, settings)
        if (parsed.title.isEmpty() && parsed.subtitle.isEmpty()) return false
        val times = Title.Times.times(ticks(parsed.fadeIn), ticks(parsed.stay), ticks(parsed.fadeOut))
        for (p in targets) p.showTitle(Title.title(Text.renderFlat(parsed.title, null, p), Text.renderFlat(parsed.subtitle, null, p), times))
        return true
    }

    private fun ticks(n: Int): Duration = Duration.ofMillis(n * 50L)

    // --- 스파이 알림 ---------------------------------------------------------------------------------

    /** 명령 스파이 — 친 사람 본인과, 그 사람을 볼 수 없는 스파이(투명)에게는 안 보낸다. */
    fun spyCommand(actor: Player, command: String) {
        if (actor.hasPermission(ChatFeature.SPY_EXEMPT)) return
        val ph = Ph.of().player(nameOf(actor)).raw("{실명}", actor.name)
        val body = Component.text(command)
        for (spy in Bukkit.getOnlinePlayers()) {
            if (spy == actor || !spy.hasPermission(ChatFeature.SPY) || !store.spying(spy.uniqueId, ChatStore.Spy.COMMAND)) continue
            if (!Vanish.canSee(spy, actor)) continue
            if (!ChatRules.spyShows(command, settings, spy.hasPermission(ChatFeature.SPY_ALL), spy.hasPermission(ChatFeature.SPY_BYPASS))) continue
            deliver(spy, "chat-spy-command", ph, body)
        }
    }

    /** 표지판 스파이 — [where] 는 **표지판 자리**(쓴 사람의 발이 아니라). */
    fun spySign(actor: Player, where: Location, lines: List<String>) {
        if (actor.hasPermission(ChatFeature.SPY_EXEMPT)) return
        val text = lines.map { it.trim() }.filter { it.isNotEmpty() }
        if (text.isEmpty()) return
        val ph = Ph.of().player(nameOf(actor)).raw("{실명}", actor.name)
            .world(where.world?.name ?: actor.world.name).raw("{좌표}", coords(where))
        val body = Component.text(text.joinToString(" | "))
        for (spy in Bukkit.getOnlinePlayers()) {
            if (spy == actor || !spy.hasPermission(ChatFeature.SPY) || !store.spying(spy.uniqueId, ChatStore.Spy.SIGN)) continue
            if (!Vanish.canSee(spy, actor)) continue
            deliver(spy, "chat-spy-sign", ph, body)
        }
    }

    /** 알림에 쓰는 블록 좌표 `x y z`. */
    internal fun coords(where: Location): String = "${where.blockX} ${where.blockY} ${where.blockZ}"

    /** 거리 밖이라 빠진 사람 중 범위 스파이에게 — 채팅 스파이를 켠 `…range.spy` 권한자. */
    fun spyRange(actor: Player, spies: Collection<Player>, message: Component) {
        val ph = Ph.of().player(nameOf(actor)).raw("{실명}", actor.name)
        for (spy in spies) deliver(spy, "chat-spy-range", ph, message)
    }

    fun isRangeSpy(viewer: Player): Boolean =
        viewer.hasPermission(ChatFeature.RANGE_SPY) && store.spying(viewer.uniqueId, ChatStore.Spy.CHAT)

    companion object {
        /** 콘솔의 장부 id(답장 상대). */
        val CONSOLE: UUID = UUID(0L, 0L)
    }
}
