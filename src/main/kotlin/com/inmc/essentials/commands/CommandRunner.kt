package com.inmc.essentials.commands

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import kr.inmc.core.integration.Vanish
import kr.inmc.core.util.Text
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.attribute.Attribute
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import java.util.Random
import java.util.logging.Level

/**
 * 이벤트 명령·일정·별칭이 같이 쓰는 실행기. 줄마다 [CommandLines] 로 머리를 읽고 변수를 바꿔 실행한다. **메인 스레드에서.**
 *
 * 기본 실행자: 이벤트·일정은 콘솔(CMI 와 같다), 별칭은 친 사람([Context.defaultAsPlayer]). `asConsole!`·`asPlayer!` 로 바꾼다.
 * 변수: `[playerName]` `[playerDisplayName]` `[lvl]` `[exp]` `[hp]` `[maxHp]` `[hunger]` `[gameMode]` `[world]` `[x]` `[y]` `[z]`
 * (+ 죽인 쪽 `[source…]`), `[randomPlayer]`, `[allPlayers]`(`allPlayers!` 와 함께), `%rand/1-5%`, 그리고 PlaceholderAPI.
 */
class CommandRunner(private val ess: Ess) {

    data class Context(
        /** 대상 플레이어(이벤트의 주인공 · 별칭을 친 사람). 없으면 null(일정). */
        val player: Player? = null,
        /** 죽인 쪽 등 두 번째 사람. */
        val source: Player? = null,
        /** 별칭을 친 쪽(콘솔일 수 있다). */
        val sender: CommandSender? = player,
        /** 사건마다의 추가 변수(`value` 등) — 키는 대괄호 없이. */
        val values: Map<String, String> = emptyMap(),
        val translatePlaceholders: Boolean = true,
        val defaultAsPlayer: Boolean = false,
        /** 일정 이름 같은 것 — 로그에. */
        val origin: String = "",
        /**
         * 사람이 스스로 친 것(별칭)인가 — 그러면 그 사람으로 돌리는 명령 줄도 `PlayerCommandPreprocessEvent` 를 거친다(대기·쿨타임·비용·
         * 던전 막기·자판 변환·하위 명령 별칭이 똑같이 걸린다). 이벤트 명령·일정이 `asPlayer!` 로 시키는 것은 관리자가 시킨 것이라 거치지 않는다.
         */
        val playerInitiated: Boolean = false,
        /** `delay!` 를 건너 이어지는 별칭 → 별칭 사슬의 길이(무한 고리 막기 — 깊이 막기는 같은 틱 안의 겹침만 본다). */
        val chain: Int = 0,
    )

    private val random = Random()

    /** 별칭이 별칭을 부르는 깊이 — 무한 고리 막기(CMI `InfiniteLoopDetection`). 메인 스레드만이라 그냥 필드. */
    var depth: Int = 0
        private set

    /** 지금 도는 줄의 사슬 길이(메인 스레드만). 줄 실행 중에 새로 시작하는 실행은 이것 + 1. */
    private var activeChain = 0

    /** 우리가 쏜 전처리 사건 안인가 — 반복 막기가 별칭 한 번의 여러 줄을 도배로 보지 않게(메인 스레드만). */
    var synthetic: Int = 0
        private set

    /** 하위 명령 별칭·이름을 덮는 별칭 가로채기(AliasService) — `performCommand`·`dispatchCommand` 는 전처리 사건을 쏘지 않으므로 직접. */
    @Volatile
    var interceptor: ((CommandSender, String) -> Boolean)? = null

    fun run(lines: List<String>, ctx: Context) = runSteps(CommandLines.steps(lines), ctx)

    /** 이미 나누고 머리를 읽은 차례로 돌린다(별칭 — [AliasDef.steps]). */
    fun runSteps(steps: List<CommandLines.Step>, ctx: Context) {
        val chain = if (depth > 0) activeChain + 1 else ctx.chain
        if (chain > MAX_CHAIN) {
            ess.logger.warning("명령 실행이 delay! 를 건너 ${MAX_CHAIN}번 넘게 이어져 멈춥니다 — 별칭끼리 서로 부르는지 보세요 (${ctx.origin})")
            return
        }
        val values = withRandom(ctx.values, steps) { randomPlayer()?.name }
        runFrom(steps, 0, ctx.copy(values = values, chain = chain))
    }

    private fun runFrom(steps: List<CommandLines.Step>, start: Int, ctx: Context) {
        val saved = activeChain
        activeChain = ctx.chain
        try {
            var i = start
            while (i < steps.size) {
                val step = steps[i]
                if (step is CommandLines.Step.Wait) {
                    val ticks = (step.seconds * 20).toLong().coerceAtLeast(1)
                    val next = i + 1
                    if (next < steps.size) Bukkit.getGlobalRegionScheduler().runDelayed(ess.plugin, { runFrom(steps, next, ctx) }, ticks)
                    return
                }
                val parsed = (step as CommandLines.Step.Line).parsed
                try {
                    runLine(parsed, ctx)
                } catch (t: Throwable) {
                    ess.logger.log(Level.WARNING, "명령 줄 실행 실패 (${ctx.origin}): ${parsed.body}", t)
                }
                i++
            }
        } finally {
            activeChain = saved
        }
    }

    private fun runLine(parsed: CommandLines.Parsed, ctx: Context) {
        val player = ctx.player?.takeIf { it.isOnline }
        if (parsed.fromConsole && ctx.sender !is ConsoleCommandSender && ctx.sender != null) return
        parsed.permission?.let { node -> if (player == null || !player.hasPermission(node)) return }
        parsed.moneyCost?.let { cost ->
            if (player == null) return
            if (!ess.economy.has(player, cost) || !ess.economy.withdraw(player, cost)) {
                ess.messages.send(player, "commands-runner-no-money", Ph.of().amount(ess.economy.format(cost)))
                return
            }
        }
        parsed.expCost?.let { cost ->
            if (player == null) return
            if (player.totalExperience < cost) {
                ess.messages.send(player, "commands-runner-no-exp", Ph.of().count(cost))
                return
            }
            player.giveExp(-cost)
        }
        if (parsed.allPlayers) {
            for (each in Vanish.visibleOnline(null)) {
                execute(parsed, ctx.copy(player = each, values = ctx.values + ("allPlayers" to each.name)), player)
            }
            return
        }
        execute(parsed, ctx, player)
    }

    private fun execute(parsed: CommandLines.Parsed, ctx: Context, player: Player?) {
        val subject = ctx.player?.takeIf { it.isOnline }
        val text = fill(parsed.body, ctx, subject)
        when (parsed.action) {
            CommandLines.Action.COMMAND -> {
                val asPlayer = when {
                    parsed.asConsole -> false
                    parsed.asPlayer -> true
                    else -> ctx.defaultAsPlayer
                }
                val actor: CommandSender? = if (asPlayer) (player ?: ctx.sender) else Bukkit.getConsoleSender()
                if (actor == null) return
                depth++
                try {
                    if (depth > MAX_DEPTH) {
                        ess.logger.warning("명령 실행이 ${MAX_DEPTH}번 넘게 겹쳐 멈춥니다 — 별칭이 자기를 부르는지 보세요 (${ctx.origin}): $text")
                        return
                    }
                    dispatch(actor, text, ctx.playerInitiated)
                } finally {
                    depth--
                }
            }
            CommandLines.Action.MSG -> {
                val name = text.substringBefore(' ')
                val body = text.substringAfter(' ', "")
                val target = Bukkit.getPlayerExact(name) ?: return
                target.sendMessage(Text.render(body, null, target))
            }
            CommandLines.Action.BROADCAST -> audience(subject).forEach { it.sendMessage(Text.render(text, null, it)) }
            CommandLines.Action.ACTIONBAR -> audience(subject).forEach { it.sendActionBar(Text.render(text, null, it)) }
            CommandLines.Action.TITLE -> {
                val main = text.substringBefore("\\n")
                val sub = if (text.contains("\\n")) text.substringAfter("\\n") else ""
                audience(subject).forEach { it.showTitle(Title.title(Text.render(main, null, it), Text.render(sub, null, it))) }
            }
            CommandLines.Action.SUBTITLE -> audience(subject).forEach { it.showTitle(Title.title(Component.empty(), Text.render(text, null, it))) }
            CommandLines.Action.KICKALL -> {
                val reason = Text.render(text)
                for (p in Bukkit.getOnlinePlayers().toList()) p.kick(reason)
            }
        }
    }

    /**
     * 명령 한 줄 보내기. 사람이 스스로 친 별칭의 줄([Context.playerInitiated])은 `PlayerCommandPreprocessEvent` 를 쏘고 취소되지 않았을 때만
     * (바뀐 글로) 실행한다 — 대상 명령의 대기·쿨타임·비용·던전 막기가 별칭으로 빠지지 않게. 대기가 걸리면 관문이 사건을 취소하고 대기 뒤에
     * 직접 실행한다(그때는 이 사건을 다시 쏘지 않는다). 그 밖(관리자가 시킨 것·콘솔)은 하위 명령 별칭 가로채기만 보고 바로 보낸다.
     */
    private fun dispatch(actor: CommandSender, text: String, playerInitiated: Boolean) {
        if (actor is Player && playerInitiated) {
            val event = PlayerCommandPreprocessEvent(actor, "/$text")
            synthetic++
            try {
                Bukkit.getPluginManager().callEvent(event)
            } finally {
                synthetic--
            }
            if (event.isCancelled) return
            actor.performCommand(event.message.removePrefix("/"))
            return
        }
        if (interceptor?.invoke(actor, text) == true) return
        if (actor is Player) actor.performCommand(text) else Bukkit.dispatchCommand(actor, text)
    }

    /**
     * 모두에게 보내는 것의 받는 사람. 주인공이 투명이면 그 사람을 볼 수 있는 사람(+ 본인)에게만 — 방송에 이름이 들어갈 수 있다(CLAUDE.md 8).
     */
    private fun audience(subject: Player?): List<Player> {
        val all = Bukkit.getOnlinePlayers().toList()
        if (subject == null || !Vanish.isVanished(subject)) return all
        return all.filter { it == subject || Vanish.canSee(it, subject) }
    }

    /** 변수 → `%rand%` → PlaceholderAPI(주인공 기준). 줄바꿈 같은 제어 문자는 빈칸으로(명령 모양이 바뀌지 않게). */
    fun fill(raw: String, ctx: Context, subject: Player?): String {
        val values = LinkedHashMap<String, String>()
        subject?.let { values.putAll(vars("", it)) }
        ctx.source?.takeIf { it.isOnline }?.let { values.putAll(vars("source", it)) }
        // 한 번 돌릴 때 한 사람(runSteps 가 ctx 에 넣는다) — 여기는 그게 없을 때만(고를 사람이 없었으면 다시 해 봐도 없다).
        if (raw.contains("[randomPlayer]", ignoreCase = true) && ctx.values.keys.none { it.equals("randomPlayer", true) }) {
            randomPlayer()?.let { values["randomPlayer"] = it.name }
        }
        values.putAll(ctx.values)
        var text = CommandLines.variables(raw, values).replace("{player}", subject?.name ?: ctx.sender?.name ?: "")
        text = CommandLines.rand(text, random)
        return if (ctx.translatePlaceholders) Text.substituteOnly(text, null, subject) else text
    }

    /** 일정의 `[randomPlayer]` — 투명이 아니고 제외 권한이 없는 접속자 중 하나(CMI `cmi.scheduler.exclude`). */
    private fun randomPlayer(): Player? {
        val pool = Vanish.visibleOnline(null).filter { !it.hasPermission(CommandsFeature.SCHEDULE_EXCLUDE) }
        return if (pool.isEmpty()) null else pool[random.nextInt(pool.size)]
    }

    private fun vars(prefix: String, p: Player): Map<String, String> {
        fun k(name: String) = if (prefix.isEmpty()) name else prefix + name.replaceFirstChar { it.uppercase() }
        val name = if (prefix.isEmpty()) "playerName" else "sourceName"
        val display = if (prefix.isEmpty()) "playerDisplayName" else "sourceDisplayName"
        return mapOf(
            name to p.name,
            display to Text.plain(p.displayName()),
            k("lvl") to p.level.toString(),
            k("exp") to p.totalExperience.toString(),
            k("hp") to "%.1f".format(p.health),
            k("maxHp") to "%.1f".format(p.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0),
            k("hunger") to p.foodLevel.toString(),
            k("gameMode") to p.gameMode.name.lowercase(),
            k("world") to p.world.name,
            k("x") to p.location.blockX.toString(),
            k("y") to p.location.blockY.toString(),
            k("z") to p.location.blockZ.toString(),
        )
    }

    companion object {
        const val MAX_DEPTH = 8
        const val MAX_CHAIN = 16

        /**
         * `[randomPlayer]` 을 한 번만 고른다(순수) — 어느 줄이 쓰고 [values] 에 아직 없으면 [pick] 을 **한 번** 불러 넣는다. 그래서 같은 실행의
         * 모든 줄(+ `delay!` 뒤 줄)이 같은 사람을 본다 — 다이아는 A 가 받고 "받았다" 글은 B 가 받는 일이 없다.
         */
        fun withRandom(values: Map<String, String>, steps: List<CommandLines.Step>, pick: () -> String?): Map<String, String> {
            if (values.keys.any { it.equals("randomPlayer", true) } || !CommandLines.mentionsRandom(steps)) return values
            val name = pick() ?: return values
            return values + ("randomPlayer" to name)
        }
    }
}
