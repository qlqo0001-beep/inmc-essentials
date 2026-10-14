package com.inmc.essentials.commands

import com.inmc.essentials.Ess
import com.inmc.essentials.service.CommandGate
import com.inmc.essentials.util.Ph
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kr.inmc.core.util.Durations
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * 명령 관문 — 대기·쿨타임·비용(설정 `rules`, 명령 이름과 별칭으로) + 반복 명령 막기. [CommandGate] 를 구현해 `ess.gate` 에 들어간다.
 *
 * 순서(같은 사건 `PlayerCommandPreprocessEvent`): LOWEST 자판 변환(KeyboardListener) → LOW 반복 막기([onSpam]) · 던전 막기 →
 * NORMAL 쿨타임 → 비용(확인) → 대기([onCommand]) → HIGH 별칭 가로채기(AliasService).
 *
 * - **대기 뒤 실행은 `performCommand`** 라 이 사건이 다시 오지 않는다(연구 H: 명령 엿보기·던전 막기도 대기 **전** 한 번만 본다 — 막기를 대기 뒤로
 *   미루면 우회 구멍이 생기므로 그게 맞다). 그래서 대기가 끝나면 쿨타임 기록·별칭 가로채기를 여기서 직접 한다([dispatch]).
 * - `commit-on-success` 규칙은 실행 때 "기다리는 확정"([pending])만 남기고, 기능이 성공을 알리면([commit]) 쿨타임을 적고 돈을 뺀다.
 *   알림 없이 2분이 지나면 버린다(이동 실패 — 쿨타임도 돈도 없음). 같은 명령을 다시 치면 앞의 기다림은 버린다(실패한 시도가 다음 성공에
 *   얹혀 돈이 두 번 빠지지 않게). 메뉴 버튼처럼 이 사건 없이 실행된 명령은 확정을 기다리지 않는다(기록 없음).
 * - 별칭이 그 사람으로 돌리는 명령 줄은 실행기가 이 사건을 다시 쏜다(CommandRunner) — 대상 명령의 규칙도 걸린다. 반복 막기는 그 사건을 보지 않는다.
 */
class CommandGateService(private val ess: Ess, private val feature: CommandsFeature) : Listener, CommandGate {

    private val settings: CommandsSettings get() = feature.settings

    // --- 이름 맞추기 ------------------------------------------------------------------------------------

    /** 별칭 → 대표 이름(소문자). 명령이 새로 붙을 때([invalidate]) 비운다. */
    private val canonical = ConcurrentHashMap<String, String>()

    @Volatile
    private var ruleIndex: Map<String, List<CommandRule>>? = null

    fun invalidate() {
        canonical.clear()
        ruleIndex = null
    }

    /**
     * 우리 명령(메인·별칭)은 등록표로, 남의 것은 Bukkit 명령 표의 이름으로. 모르면 그대로. **알아본 이름만** 캐시한다 — 없는 명령을 매번 다르게
     * 쳐도(`/a1`, `/a2` …) 캐시가 늘지 않는다.
     */
    fun canonical(label: String): String {
        val lower = CommandsSettings.normalize(label)
        canonical[lower]?.let { return it }
        val found = ess.commands.all().firstOrNull { e -> e.node.literal.lowercase() == lower || e.aliases.any { it.lowercase() == lower } }
            ?.node?.literal?.lowercase()
            ?: runCatching { Bukkit.getCommandMap().getCommand(lower)?.name?.lowercase() }.getOrNull()
            ?: return lower
        canonical[lower] = found
        return found
    }

    private fun index(): Map<String, List<CommandRule>> = ruleIndex ?: HashMap<String, MutableList<CommandRule>>().also { out ->
        for (rule in settings.rules) for (name in rule.commands.map(::canonical).distinct()) out.getOrPut(name) { mutableListOf() } += rule
        ruleIndex = out
    }

    fun rulesFor(label: String, hasArgs: Boolean): List<CommandRule> =
        index()[canonical(label)].orEmpty().filter { it.args.accepts(hasArgs) }

    // --- 반복 막기(LOW) ---------------------------------------------------------------------------------

    private val spam = ConcurrentHashMap<UUID, SpamWindow>()

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onSpam(event: PlayerCommandPreprocessEvent) {
        val s = settings
        if (!s.spam || feature.runner.synthetic > 0) return
        val player = event.player
        if (player.hasPermission(CommandsFeature.SPAM_BYPASS)) return
        if (canonical(event.message) in s.spamWhitelist || CommandsSettings.normalize(event.message) in s.spamWhitelist) return
        val window = spam.computeIfAbsent(player.uniqueId) { SpamWindow(s.spamInterval * 1000, s.spamSimilarity, s.spamMaxRepeats) }
        if (window.check(event.message, System.currentTimeMillis())) {
            event.isCancelled = true
            ess.messages.send(player, "commands-spam-blocked", Ph.of().time(s.spamInterval.toString()))
        }
    }

    /** 설정이 바뀌면 창을 새로(간격·횟수가 바뀌었을 수 있다). */
    fun resetSpam() = spam.clear()

    // --- 쿨타임 · 비용 · 대기(NORMAL) -----------------------------------------------------------------------

    /** 비용 확인을 기다리는 명령 — 같은 명령을 다시 치거나 누르면 실행. */
    private class Confirm(val message: String, val until: Long)

    private val confirms = ConcurrentHashMap<UUID, Confirm>()

    /** 성공 알림을 기다리는 `commit-on-success` 규칙. */
    private class Pending(val rule: CommandRule, val canonical: String, val until: Long)

    private val pending = ConcurrentHashMap<UUID, MutableList<Pending>>()

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val player = event.player
        val message = event.message
        val hasArgs = message.trim().contains(' ')
        val rules = rulesFor(message, hasArgs)
        if (rules.isEmpty()) return
        if (warmups.containsKey(player.uniqueId) && !player.hasPermission(CommandsFeature.BYPASS_WARMUP)) {
            // 대기 중 다른 명령 — CMI 처럼 새 것이 옛 것을 바꾼다(옛 것은 돈을 돌려주고 취소).
            cancelWarmup(player, null)
        }
        val now = System.currentTimeMillis()
        val has: (String) -> Boolean = { player.hasPermission(it) }

        // 1. 쿨타임
        if (!player.hasPermission(CommandsFeature.BYPASS_COOLDOWN)) {
            for (rule in rules) {
                if (player.hasPermission(bypassNode(rule))) continue
                val left = CooldownMath.remaining(feature.cooldowns.lastUsed(player.uniqueId, rule.id), now, rule.cooldownFor(has)) ?: continue
                event.isCancelled = true
                if (left == Long.MAX_VALUE) ess.messages.send(player, "commands-cooldown-once", Ph.of().value("/" + label(message)))
                else ess.messages.send(player, "commands-cooldown", Ph.of().value("/" + label(message)).time(Durations.formatShort(ceil(left / 1000.0).toLong())))
                return
            }
        }

        // 2. 비용 — 화폐마다 모아서 있는지 먼저
        val payers = if (player.hasPermission(CommandsFeature.BYPASS_COST)) emptyList() else rules.filter { it.cost > 0 && !player.hasPermission(bypassNode(it)) }
        for ((currency, amount) in sum(payers)) {
            if (!ess.economy.has(player, amount, currency.ifEmpty { null })) {
                event.isCancelled = true
                ess.messages.send(player, "commands-cost-not-enough", Ph.of().amount(ess.economy.format(amount, currency.ifEmpty { null })).value("/" + label(message)))
                return
            }
        }
        if (payers.any { it.costConfirm }) {
            val waiting = confirms[player.uniqueId]
            if (waiting == null || waiting.message != message || waiting.until < now) {
                event.isCancelled = true
                confirms[player.uniqueId] = Confirm(message, now + settings.confirmSeconds * 1000)
                val price = sum(payers).entries.joinToString(" + ") { (c, a) -> ess.economy.format(a, c.ifEmpty { null }) }
                val line = ess.messages.prefixed("commands-cost-confirm", Ph.of().amount(price).value(shown(message)).time(settings.confirmSeconds.toString()), player)
                    .clickEvent(ClickEvent.runCommand(message))
                    .hoverEvent(HoverEvent.showText(ess.messages.component("commands-cost-confirm-hover", Ph.of().value(shown(message)), player)))
                player.sendMessage(line)
                return
            }
            confirms.remove(player.uniqueId)
        }

        // 3. 대기
        val warmup = if (player.hasPermission(CommandsFeature.BYPASS_WARMUP)) 0L
        else rules.filter { !player.hasPermission(bypassNode(it)) }.maxOfOrNull { it.warmupFor(has) } ?: 0L
        if (warmup > 0) {
            event.isCancelled = true
            val charged = charge(player, payers.filter { !it.commitOnSuccess }, message) ?: return
            startWarmup(player, message, rules, warmup, charged)
            return
        }

        // 4. 바로
        if (charge(player, payers.filter { !it.commitOnSuccess }, message) == null) {
            event.isCancelled = true
            return
        }
        settle(player, message, rules, payers, now)
    }

    /** 실행이 정해진 순간 — 성공 확정이 필요 없는 규칙은 쿨타임을 적고, 필요한 것은 기다리는 줄에. */
    private fun settle(player: Player, message: String, rules: List<CommandRule>, payers: List<CommandRule>, now: Long) {
        val bypassCooldown = player.hasPermission(CommandsFeature.BYPASS_COOLDOWN)
        val canon = canonical(message)
        // 같은 명령의 앞 시도가 아직 확정을 기다리면 버린다 — 그 시도는 실패했거나(이동 실패) 이번 것과 같은 성공 하나로 친다.
        // 그래서 확정 한 번(commit)은 언제나 한 시도 몫만 낸다.
        if (rules.any { it.commitOnSuccess }) pending[player.uniqueId]?.let { list -> synchronized(list) { list.removeAll { it.canonical == canon } } }
        for (rule in rules) {
            if (rule.commitOnSuccess) {
                val list = pending.computeIfAbsent(player.uniqueId) { mutableListOf() }
                synchronized(list) { list += Pending(rule, canon, now + PENDING_MS) }
                continue
            }
            if (!bypassCooldown && rule.tracksCooldown && !player.hasPermission(bypassNode(rule))) feature.cooldowns.record(player.uniqueId, rule.id, now)
        }
        for (rule in payers) if (!rule.commitOnSuccess && rule.costNotify) {
            ess.messages.send(player, "commands-cost-charged", Ph.of().amount(ess.economy.format(rule.cost, rule.currency.ifEmpty { null })).value("/" + label(message)))
        }
    }

    /** 돈을 뺀다 — 하나라도 실패하면 뺀 것을 돌려주고 null. 성공하면 뺀 목록. */
    private fun charge(player: Player, rules: List<CommandRule>, message: String): List<CommandRule>? {
        val done = mutableListOf<CommandRule>()
        for (rule in rules) {
            if (ess.economy.withdraw(player, rule.cost, rule.currency.ifEmpty { null })) {
                done += rule
            } else {
                refund(player, done)
                ess.messages.send(player, "commands-cost-not-enough", Ph.of().amount(ess.economy.format(rule.cost, rule.currency.ifEmpty { null })).value("/" + label(message)))
                return null
            }
        }
        return done
    }

    private fun refund(player: Player, rules: List<CommandRule>) {
        for (rule in rules) ess.economy.deposit(player, rule.cost, rule.currency.ifEmpty { null })
    }

    private fun sum(rules: List<CommandRule>): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        for (rule in rules) out.merge(rule.currency, rule.cost, Double::plus)
        return out
    }

    /**
     * 기능이 "성공했다" 고 알린다(이동 성공 등). 그 사람의 기다리는 확정 중 이 명령의 것을 처리한다 — [settle] 이 같은 명령의 앞 시도를
     * 버리므로 남은 것은 마지막 한 시도(규칙이 여럿이면 그 규칙들)뿐이다.
     */
    override fun commit(player: Player, command: String) {
        val list = pending[player.uniqueId] ?: return
        val now = System.currentTimeMillis()
        val canon = canonical(command)
        val mine = synchronized(list) {
            list.removeAll { it.until < now }
            list.filter { it.canonical == canon }.also { list.removeAll(it.toSet()) }
        }
        if (list.isEmpty()) pending.remove(player.uniqueId)
        for (p in mine) {
            val rule = p.rule
            val bypass = player.hasPermission(bypassNode(rule))
            if (rule.tracksCooldown && !bypass && !player.hasPermission(CommandsFeature.BYPASS_COOLDOWN)) feature.cooldowns.record(player.uniqueId, rule.id, now)
            if (rule.cost > 0 && !bypass && !player.hasPermission(CommandsFeature.BYPASS_COST)) {
                val currency = rule.currency.ifEmpty { null }
                if (ess.economy.withdraw(player, rule.cost, currency)) {
                    if (rule.costNotify) ess.messages.send(player, "commands-cost-charged", Ph.of().amount(ess.economy.format(rule.cost, currency)).value("/$command"))
                } else {
                    // 실행 전에 있는지 봤는데 그새 다 썼다 — 이미 성공한 것을 되돌릴 수는 없으니 알리기만.
                    ess.messages.send(player, "commands-cost-failed-after", Ph.of().amount(ess.economy.format(rule.cost, currency)).value("/$command"))
                }
            }
        }
    }

    // --- 대기 ---------------------------------------------------------------------------------------

    private class Warmup(
        val message: String,
        val rules: List<CommandRule>,
        val endsAt: Long,
        val seconds: Long,
        val origin: Location,
        val move: Double,
        val cancelOnDamage: Boolean,
        val charged: List<CommandRule>,
        val payers: List<CommandRule>,
    ) {
        var task: ScheduledTask? = null
        var bar: BossBar? = null
    }

    private val warmups = ConcurrentHashMap<UUID, Warmup>()

    fun warming(player: Player): Boolean = warmups.containsKey(player.uniqueId)

    private fun startWarmup(player: Player, message: String, rules: List<CommandRule>, seconds: Long, charged: List<CommandRule>) {
        // 움직임: 가장 엄한 규칙(같은 블록 0 < 거리 N < 자유 -1).
        val moves = rules.map { it.move }
        val move = if (moves.any { it >= 0 }) moves.filter { it >= 0 }.min() else -1.0
        val payers = rules.filter { it.cost > 0 && !player.hasPermission(bypassNode(it)) && !player.hasPermission(CommandsFeature.BYPASS_COST) }
        val w = Warmup(message, rules, System.currentTimeMillis() + seconds * 1000, seconds, player.location.clone(), move, rules.any { it.cancelOnDamage }, charged, payers)
        warmups[player.uniqueId] = w
        val s = settings
        if (WarmupDisplay.BOSSBAR in s.warmupDisplay) {
            val color = BossBar.Color.entries.firstOrNull { it.name.equals(s.warmupBossbarColor, true) } ?: BossBar.Color.BLUE
            w.bar = BossBar.bossBar(ess.messages.component("commands-warmup-bossbar", Ph.of().value("/" + label(message)).time(seconds.toString()), player), 1f, color, BossBar.Overlay.PROGRESS)
                .also { player.showBossBar(it) }
        }
        if (s.informOnNoMove && move >= 0) {
            ess.messages.send(player, if (move == 0.0) "commands-warmup-start-still" else "commands-warmup-start-range", Ph.of().value("/" + label(message)).time(seconds.toString()).amount(move.toInt().toString()))
        } else {
            ess.messages.send(player, "commands-warmup-start", Ph.of().value("/" + label(message)).time(seconds.toString()))
        }
        w.task = player.scheduler.runAtFixedRate(ess.plugin, { _ -> tickWarmup(player, w) }, null, 1L, 20L)
    }

    private fun tickWarmup(player: Player, w: Warmup) {
        if (warmups[player.uniqueId] !== w) {
            w.task?.cancel()
            return
        }
        val left = ceil((w.endsAt - System.currentTimeMillis()) / 1000.0).toLong()
        if (left <= 0) {
            finishWarmup(player, w)
            return
        }
        val s = settings
        val ph = Ph.of().value("/" + label(w.message)).time(left.toString())
        if (WarmupDisplay.ACTIONBAR in s.warmupDisplay) player.sendActionBar(ess.messages.component("commands-warmup-actionbar", ph, player))
        if (WarmupDisplay.TITLE in s.warmupDisplay) {
            player.showTitle(
                Title.title(
                    ess.messages.component("commands-warmup-title", ph, player),
                    ess.messages.component("commands-warmup-subtitle", ph, player),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(1200), Duration.ofMillis(200)),
                ),
            )
        }
        if (WarmupDisplay.CHAT in s.warmupDisplay) ess.messages.send(player, "commands-warmup-chat", ph)
        w.bar?.progress((left.toFloat() / w.seconds.coerceAtLeast(1)).coerceIn(0f, 1f))
        if (s.warmupSound.isNotBlank()) runCatching { player.playSound(Sound.sound(Key.key(s.warmupSound), Sound.Source.MASTER, 1f, 1f)) }
    }

    private fun finishWarmup(player: Player, w: Warmup) {
        cleanup(player, w)
        warmups.remove(player.uniqueId, w)
        if (!player.isOnline) {
            refund(player, w.charged)
            return
        }
        player.clearTitle()
        settle(player, w.message, w.rules, w.payers, System.currentTimeMillis())
        dispatch(player, w.message)
    }

    /** 대기를 끝낸 명령 실행 — 별칭이면 별칭으로, 아니면 그대로(`performCommand` 는 이 사건을 다시 쏘지 않는다). */
    fun dispatch(player: Player, message: String) {
        if (feature.aliases.intercept(player, message)) return
        player.performCommand(message.removePrefix("/"))
    }

    /** 대기 취소 — 뺀 돈을 돌려준다. [reasonKey] 가 null 이면 조용히. */
    fun cancelWarmup(player: Player, reasonKey: String?) {
        val w = warmups.remove(player.uniqueId) ?: return
        cleanup(player, w)
        refund(player, w.charged)
        if (reasonKey != null && player.isOnline) {
            player.clearTitle()
            ess.messages.send(player, reasonKey, Ph.of().value("/" + label(w.message)))
        }
    }

    private fun cleanup(player: Player, w: Warmup) {
        w.task?.cancel()
        w.bar?.let { player.hideBossBar(it) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val w = warmups[event.player.uniqueId] ?: return
        val to = event.to
        val from = event.from
        if (from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ && from.world == to.world) return
        if (w.move < 0) return
        val moved = to.world != w.origin.world ||
            (if (w.move == 0.0) to.blockX != w.origin.blockX || to.blockY != w.origin.blockY || to.blockZ != w.origin.blockZ
            else to.distanceSquared(w.origin) > w.move * w.move)
        if (moved) cancelWarmup(event.player, "commands-warmup-cancel-move")
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        val w = warmups[player.uniqueId] ?: return
        if (w.cancelOnDamage) cancelWarmup(player, "commands-warmup-cancel-damage")
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        if (warmups.containsKey(event.player.uniqueId)) cancelWarmup(event.player, "commands-warmup-cancel-teleport")
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) = cancelWarmup(event.entity, null)

    // --- 접속 ---------------------------------------------------------------------------------------

    /**
     * 접속 직전에 읽어 **대기칸**에만 둔다 — 접속 사건에서 옮긴다([onJoin]). 같은 계정이 접속 중에 다시 들어오면 Paper 는 새 접속의 이 사건을
     * 먼저 돌리고 옛 접속을 내보내는데, 옛 접속의 나감이 방금 읽은 기록을 지우면 '한 번만' 쿨타임이 풀린다. 거절된 접속의 대기칸은 시간이 지나면 버린다.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        if (event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED || !ess.db.isOpen) return
        runCatching { feature.cooldowns.stageBlocking(event.uniqueId) }
            .onFailure { ess.logger.warning("명령 쿨타임을 읽지 못했습니다 (${event.name}): $it") }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(event: PlayerJoinEvent) {
        val id = event.player.uniqueId
        // 대기칸이 없으면(DB 가 늦게 열림·읽기 실패) 넘겨서 읽는다 — 그동안은 기록 없음으로 본다.
        if (!feature.cooldowns.activate(id)) feature.cooldowns.loadLater(id)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val id = event.player.uniqueId
        cancelWarmup(event.player, null)
        confirms.remove(id)
        pending.remove(id)
        spam.remove(id)
        feature.cooldowns.forget(id)
    }

    /** 끌 때 — 대기 중인 사람의 돈을 돌려준다. */
    fun shutdown() {
        for (id in warmups.keys.toList()) Bukkit.getPlayer(id)?.let { cancelWarmup(it, null) }
    }

    /** 검증기용 — 비용 확인을 기다리는가. */
    internal fun awaitingConfirm(player: Player): Boolean = confirms.containsKey(player.uniqueId)

    internal fun clearConfirm(player: Player) {
        confirms.remove(player.uniqueId)
    }

    /** 검증기용 — 이 명령의 확정을 기다리는 수. */
    internal fun pendingCount(player: Player, command: String): Int {
        val canon = canonical(command)
        return pending[player.uniqueId]?.let { list -> synchronized(list) { list.count { it.canonical == canon } } } ?: 0
    }

    internal fun clearPending(player: Player) {
        pending.remove(player.uniqueId)
    }

    /** 검증기용 — 대기를 시험으로 시작하고 끝내기. */
    internal fun testWarmup(player: Player, message: String, rule: CommandRule) = startWarmup(player, message, listOf(rule), rule.warmup.coerceAtLeast(1), emptyList())

    private fun label(message: String): String = shown(message.trim().removePrefix("/").substringBefore(' '))

    /** 사람이 친 글을 메시지에 넣을 때 — MiniMessage 태그·PlaceholderAPI 로 읽히지 않게. */
    private fun shown(text: String): String = text.replace("<", "\\<").replace('%', '％')

    companion object {
        const val PENDING_MS = 120_000L

        /** 규칙 하나만 건너뛰는 권한 `inmcessentials.commands.bypass.rule.<id>`(동적 — 선언하지 않으므로 기본은 op, 지뢰 11 이 여기서는 뜻대로). */
        fun bypassNode(rule: CommandRule): String = "${CommandsFeature.BYPASS_RULE}.${rule.id.lowercase()}"
    }
}
