package com.inmc.essentials.chat

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import io.papermc.paper.event.player.AsyncChatEvent
import kr.inmc.core.integration.PlayerSettings
import kr.inmc.core.integration.Vanish
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 채팅 길 — **렌더러(모양)는 건드리지 않는다**(계획 6절 · CLAUDE.md 10: 모양은 타이틀포지 `NameDisplayService.onChat` LOW 하나).
 *
 * - LOW(한 처리기 — 순서가 정해지게): ① 스태프 모드면 공개 채팅을 스태프 채널로 돌린다(취소) ② 같은 글 도배(취소) ③ `!` 로 시작하는 글만 외침으로 보고
 *   `!` 를 뗀다(외침 색 · 비용을 잡아 둔다). 다른 플러그인의 LOWEST 입력 대기(타이틀포지 TextInput · 채팅 입력 경로)·moderation 의 뮤트·채팅잠금(LOWEST
 *   취소)이 다 돈 뒤라 입력값을 바꾸지 않고, 뮤트된 사람의 글을 돌리지 않는다. (계약은 LOWEST 였으나 같은 LOWEST 의 입력 대기와 순서가 정해지지
 *   않아 입력값의 `!` 가 떨어졌다 — 검토 2026-10-10.)
 * - HIGH: `viewers()` 고치기(거리·외침 범위·전체 채팅 끄기·무시) · 링크 축약(디스코드 LOW 보다 뒤라 디스코드에는 원래 주소).
 * - MONITOR(취소된 것도): 외침 비용 — 나간 외침만 빼고, 취소된 외침은 잡아 둔 몫을 놓는다.
 * - 명령: LOWEST 에서 `/minecraft:msg|tell|w` 를 우리 귓속말로 · MONITOR 에서 명령 스파이. 표지판: MONITOR 에서 표지판 스파이(표지판 자리).
 */
class ChatListener(private val ess: Ess, private val feature: ChatFeature, private val service: ChatService) : Listener {

    private val settings: ChatSettings get() = feature.settings

    /**
     * 이번 채팅이 외침인가 → 낼 비용(0 = 무료). LOW 가 적고 HIGH 가 읽고 MONITOR 가 지운다. 사건 객체를 약하게 쥔다.
     */
    private val shouts: MutableMap<AsyncChatEvent, Double> = Collections.synchronizedMap(WeakHashMap())

    /**
     * 사람마다 "빼기로 잡아 둔" 외침 비용 — 잔액 확인(LOW, 채팅 스레드)과 빼기(MONITOR 뒤, 그 사람의 스레드)가 떨어져 있어, 한 틱에 여러 번 외쳐도
     * (잔액 - 잡아 둔 것)으로 판단한다. 빼거나 취소되면 놓는다.
     */
    private val reserved = ConcurrentHashMap<UUID, Double>()

    // --- 명령 ------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onVanillaWhisper(event: PlayerCommandPreprocessEvent) {
        ChatRules.rewriteVanillaWhisper(event.message, ChatFeature.WHISPER)?.let { event.message = it }
    }

    /** 취소된 명령도 보인다(친 것 자체가 운영진이 알 일이다). */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onCommandSpy(event: PlayerCommandPreprocessEvent) {
        service.spyCommand(event.player, event.message)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSignSpy(event: SignChangeEvent) {
        service.spySign(event.player, event.block.location, event.lines().map { ChatText.plain(it) })
    }

    /** 나간 사람의 메모리 — 도배 기록·답장 상대(CMI 도 나가면 답장 상대를 잊는다). 스태프 모드는 재시작까지 남긴다. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        feature.spam.forget(event.player.uniqueId)
        feature.book.forget(event.player.uniqueId)
        reserved.remove(event.player.uniqueId)
    }

    // --- 공개 채팅 ---------------------------------------------------------------------------------

    /** ① 스태프 모드 ② 도배 ③ 외침 — 순서대로(클래스 KDoc). 앞에서 취소되면 뒤는 돌지 않는다. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onLow(event: AsyncChatEvent) {
        if (staffCapture(event, deliver = true)) return
        if (spamCheck(event)) return ess.messages.send(event.player, "chat-spam-blocked")
        shout(event, notify = true)
    }

    /**
     * 외침이면 `!` 를 떼고(외침 색) 표식을 남긴다 — 비용이 있으면 (잔액 - 잡아 둔 것)으로 보고 잡아 둔다. 돈이 모자라면 [notify] 일 때 알리고
     * 보통 글(`!` 그대로)로 둔다. 외침으로 쳤으면 true. 스태프 모드인 사람의 글은 [onLow] 가 먼저 가져갔다.
     */
    internal fun shout(event: AsyncChatEvent, notify: Boolean): Boolean {
        val player = event.player
        val s = settings
        val plain = ChatText.plain(event.message())
        if (!ChatRules.isShout(plain) || ChatRules.shoutRange(s) == ChatRules.NO_SHOUT || !player.hasPermission(ChatFeature.SHOUT)) return false
        var cost = 0.0
        if (s.shoutCost > 0.0 && ess.economy.isEnabled && !player.hasPermission(ChatFeature.SHOUT_FREE)) {
            // 그림자 뮤트여도 낸다 — 본인에게는 외친 것처럼 보이므로 돈이 그대로면 그림자 뮤트가 샌다.
            val ok = synchronized(reserved) {
                val held = reserved[player.uniqueId] ?: 0.0
                ess.economy.has(player, held + s.shoutCost).also { if (it) reserved[player.uniqueId] = held + s.shoutCost }
            }
            if (!ok) {
                if (notify) ess.messages.send(player, "chat-shout-cost-missing", Ph.of().amount(ess.economy.format(s.shoutCost)))
                return false
            }
            cost = s.shoutCost
        }
        var message = event.message().replaceText(TextReplacementConfig.builder().matchLiteral("!").once().replacement("").build())
        colorOf(s.shoutColor)?.let { message = message.colorIfAbsent(it) }
        event.message(message)
        shouts[event] = cost
        return true
    }

    private fun release(id: UUID, cost: Double) {
        synchronized(reserved) { reserved.computeIfPresent(id) { _, held -> (held - cost).takeIf { it > 1e-9 } } }
    }

    private fun inStaffMode(player: Player): Boolean {
        if (player.uniqueId !in feature.staffMode) return false
        if (player.hasPermission(ChatFeature.STAFF)) return true
        feature.staffMode.remove(player.uniqueId) // 권한을 잃었다
        return false
    }

    /**
     * 스태프 모드면 취소하고 스태프 채널로([deliver] = false 면 취소만 — 검증기). 그림자 뮤트면 보낸 사람에게만 보낸 것처럼(`ChatService.staff`).
     * 보통 뮤트·채팅잠금은 moderation 이 LOWEST 에서 이미 취소했다(ignoreCancelled).
     */
    internal fun staffCapture(event: AsyncChatEvent, deliver: Boolean): Boolean {
        val player = event.player
        if (!inStaffMode(player)) return false
        event.isCancelled = true
        if (deliver) service.staff(player, ChatText.plain(event.message()))
        return true
    }

    /** 도배면 취소하고 true(알림은 부른 쪽 — 검증기는 알리지 않는다). */
    internal fun spamCheck(event: AsyncChatEvent): Boolean {
        val s = settings
        if (!s.spamEnabled || event.player.hasPermission(ChatFeature.SPAM_BYPASS)) return false
        if (!feature.spam.check(event.player.uniqueId, "public", ChatText.plain(event.message()), System.currentTimeMillis(), s)) return false
        event.isCancelled = true
        return true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onHigh(event: AsyncChatEvent) {
        val s = settings
        val sender = event.player
        if (s.linkEnabled) event.message(ChatText.shortenLinks(event.message(), s.linkRegex, ess.messages.raw("chat-link-label")))
        val shout = shouts.containsKey(event)
        val range = when {
            sender.hasPermission(ChatFeature.RANGE_BYPASS) -> null
            shout -> ChatRules.shoutRange(s)
            else -> ChatRules.generalRange(s, Bukkit.getOnlinePlayers().size)
        }
        val rangeSpies = ArrayList<Player>()
        filterViewers(sender, event.viewers(), range, rangeSpies)
        if (rangeSpies.isNotEmpty()) service.spyRange(sender, rangeSpies, event.message())
        if (range != null && s.notifyNobody && nobodyNear(sender, range)) ess.messages.send(sender, "chat-range-nobody")
    }

    /**
     * 외침 비용 — 나간 외침만 그 사람의 스레드에서 뺀다. LOW 뒤에 취소된 외침(NORMAL·HIGH 의 다른 플러그인)은 잡아 둔 몫만 놓는다 — 그래서
     * ignoreCancelled 가 아니다. 그사이 다른 데서 돈을 써 모자라지면 빼지 못하고 로그를 남긴다.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onSent(event: AsyncChatEvent) {
        val cost = shouts.remove(event) ?: return
        if (cost <= 0.0) return
        val player = event.player
        val id = player.uniqueId
        if (event.isCancelled) return release(id, cost)
        val charge = Runnable {
            try {
                if (ess.economy.has(player, cost)) ess.economy.withdraw(player, cost)
                else ess.logger.warning("외침 비용 ${ess.economy.format(cost)} 을(를) ${player.name} 에게서 빼지 못했습니다 — 그사이 잔액이 모자라졌습니다")
            } finally {
                release(id, cost)
            }
        }
        val global = Runnable { Bukkit.getGlobalRegionScheduler().run(ess.plugin) { _ -> charge.run() } }
        if (player.scheduler.run(ess.plugin, { _ -> charge.run() }, global) == null) global.run()
    }

    /**
     * "근처에 들은 사람이 없다" 알림의 판단 — **보낸 사람이 볼 수 있는 다른 접속자가 거리 안에 하나도 없을 때만**. 받는 사람 목록(`viewers()`)이 아니라
     * 자리로만 본다: 목록은 무시·채팅 끄기·그림자 뮤트(moderation 이 본인만 남긴다)로도 줄어서, 그것으로 판단하면 보낸 사람이 그림자 뮤트·무시를
     * 알아채고, 투명인 운영진이 근처에 있으면 알림이 사라져 투명이 샌다.
     */
    private fun nobodyNear(sender: Player, range: Double): Boolean {
        val origin = sender.location
        return Bukkit.getOnlinePlayers().none { other ->
            other != sender && Vanish.canSee(sender, other) &&
                ChatRules.inRange(range, other.world == origin.world, if (other.world == origin.world) other.location.distanceSquared(origin) else Double.MAX_VALUE)
        }
    }

    /** 받는 사람이 빠지는 까닭. */
    enum class Drop { IGNORE, SILENCE, RANGE }

    /**
     * 받는 사람 거르기. 보낸 사람 자신·콘솔은 늘 남는다. 빠지는 까닭은 [dropOf]. 거리 밖으로 빠진 범위 스파이는 [rangeSpies] 에 담는다.
     */
    internal fun filterViewers(sender: Player, viewers: MutableSet<net.kyori.adventure.audience.Audience>, range: Double?, rangeSpies: MutableList<Player>) {
        val ignoreBypass = sender.hasPermission(ChatFeature.IGNORE_BYPASS)
        val silenceBypass = sender.hasPermission(ChatFeature.SILENCE_BYPASS)
        val origin = sender.location
        val iterator = viewers.iterator()
        while (iterator.hasNext()) {
            val viewer = iterator.next() as? Player ?: continue
            if (viewer.uniqueId == sender.uniqueId) continue
            val drop = dropOf(viewer, sender.uniqueId, ignoreBypass, silenceBypass, origin, range) ?: continue
            iterator.remove()
            if (drop == Drop.RANGE && service.isRangeSpy(viewer)) rangeSpies += viewer
        }
    }

    /**
     * [viewer] 가 [senderId] 의 공개 채팅을 못 받는 까닭(받으면 null): 보낸 사람을 무시함(`ignore.public-chat`) · 전체 채팅 끔 · 거리 밖([range] null 이면 늘 닿음).
     */
    internal fun dropOf(
        viewer: Player, senderId: UUID, ignoreBypass: Boolean, silenceBypass: Boolean, origin: Location, range: Double?,
        silenced: Boolean = isSilenced(viewer),
    ): Drop? = when {
        settings.ignorePublic && !ignoreBypass && feature.store.ignores(viewer.uniqueId, senderId) -> Drop.IGNORE
        !silenceBypass && silenced -> Drop.SILENCE
        !ChatRules.inRange(range, viewer.world == origin.world, if (viewer.world == origin.world) viewer.location.distanceSquared(origin) else Double.MAX_VALUE) -> Drop.RANGE
        else -> null
    }

    /** 전체 채팅을 껐나(core 개인 설정 `essentials.silence` — /채팅끄기 와 같은 값). */
    internal fun isSilenced(viewer: Player): Boolean = PlayerSettings.enabled(viewer.uniqueId, ChatFeature.SILENCE, false)

    private fun colorOf(raw: String): TextColor? {
        if (raw.isBlank()) return null
        return if (raw.startsWith("#")) TextColor.fromHexString(raw) else NamedTextColor.NAMES.value(raw.lowercase())
    }

    /** 검증기 — 외침 표식이 남았나. */
    internal fun isShout(event: AsyncChatEvent): Boolean = shouts.containsKey(event)

    internal fun forgetShout(event: AsyncChatEvent) {
        shouts.remove(event)?.let { if (it > 0.0) release(event.player.uniqueId, it) }
    }

    /** 검증기 — 그 사람이 잡아 둔 외침 비용. */
    internal fun reservedOf(id: UUID): Double = reserved[id] ?: 0.0

    companion object {
        /** 검증기가 만드는 채팅 사건(동기). 렌더러는 기본 — 쏘지 않고 우리 처리기에 직접 넘긴다. */
        fun probe(player: Player, text: String, viewers: Set<net.kyori.adventure.audience.Audience>): AsyncChatEvent {
            val message = Component.text(text)
            return AsyncChatEvent(
                false, player, viewers.toMutableSet(), io.papermc.paper.chat.ChatRenderer.defaultRenderer(),
                message, message, net.kyori.adventure.chat.SignedMessage.system(text, message),
            )
        }
    }
}
