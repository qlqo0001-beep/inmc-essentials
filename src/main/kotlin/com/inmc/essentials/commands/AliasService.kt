package com.inmc.essentials.commands

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import kr.inmc.core.integration.Vanish
import kr.inmc.core.util.AtomicFiles
import kr.inmc.core.util.Text
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.server.ServerCommandEvent
import java.io.File
import java.util.logging.Level

/**
 * 사용자 별칭(`commands/aliases.yml`) — CMI CustomAlias 와 같은 일.
 *
 * - 한 낱말 별칭은 **실행 중에** Bukkit 명령 표(`CommandMap`)에 올리고 접속자에게 `updateCommands()` — 탭 완성·`/` 목록에 보인다.
 *   이름이 이미 남의 명령이면 올리지 않고 아래 가로채기로 돈다(그 이름의 원래 명령을 덮는다 — `exact` 면 이름만 쳤을 때만).
 * - 여러 낱말 별칭(`스폰 설정`, `ca ?`)은 `PlayerCommandPreprocessEvent` HIGH 에서 가로챈다 — 대기·쿨타임·비용(NORMAL)이 먼저 본다.
 *   콘솔·명령 블록(`ServerCommandEvent`)과 실행기의 `performCommand`·`dispatchCommand`(일정·이벤트 명령·다른 별칭)도 가로챈다([intercept]).
 * - `/minecraft:reload`·Bukkit `/reload` 로 명령 트리가 새로 지어지면 `ServerLoadEvent` 에서 다시 올린다(CommandsFeature).
 * - 고리 막기: 자기를 부르는 별칭은 켜지 않고([AliasDef.selfCalls] — CMI 와 같다), 실행기 깊이([CommandRunner.MAX_DEPTH])·`delay!` 를 건넌 사슬
 *   ([CommandRunner.MAX_CHAIN])이 넘으면 멈춘다(CMI `InfiniteLoopDetection`).
 * - 인자는 템플릿을 나누고 머리를 읽은 **뒤에** 본문에만 넣는다([AliasDef.steps]) — `/귓 a ;;asConsole! op 나` 가 콘솔 명령이 되지 않는다.
 */
class AliasService(private val ess: Ess, private val feature: CommandsFeature) : Listener {

    @Volatile
    var defs: List<AliasDef> = emptyList()
        private set

    /** 명령 표에 올린 것(이름 → 명령). 메인 스레드만. */
    private val registered = LinkedHashMap<String, AliasCommand>()

    /** 가로채기로 도는 것(여러 낱말 + 이름이 남의 것이라 못 올린 한 낱말). */
    @Volatile
    private var intercepted: List<AliasDef> = emptyList()

    private var serverLoaded = false

    val file: File get() = ess.io.file("commands", "aliases.yml")

    /** 서버 준비가 끝났다(명령 표가 다 찼다) — 이제부터 올릴 수 있다. */
    fun serverLoaded() {
        serverLoaded = true
        apply(defs)
    }

    /** 새 목록으로 바꿔 끼우고 다시 올린다. 메인 스레드. */
    fun apply(next: List<AliasDef>) {
        defs = next
        if (!serverLoaded) {
            intercepted = next.filter { it.enabled && !it.selfCalls() }
            return
        }
        unregisterAll()
        val map = Bukkit.getCommandMap()
        val routed = mutableListOf<AliasDef>()
        for (def in next) {
            if (!def.enabled) continue
            if (def.selfCalls()) {
                ess.logger.warning("별칭 /${def.name} 은(는) 자기 자신을 불러 끝없이 돌므로 켜지 않습니다 — 원래 명령은 접두 이름(예: inmc-essentials:워프)으로 부르세요")
                continue
            }
            if (!def.single) {
                routed += def
                continue
            }
            val name = def.root.lowercase()
            if (map.getCommand(name) != null || com.inmc.essentials.command.CommandRegistry.reserved(name)) {
                routed += def
                if (!def.exact) ess.logger.info("별칭 /$name 은(는) 이미 있는 명령을 덮어 가로채기로 돕니다")
                continue
            }
            val command = AliasCommand(def)
            val ok = runCatching { map.register(FALLBACK, command) }.getOrElse {
                ess.logger.log(Level.WARNING, "별칭 /$name 을(를) 올리지 못했습니다", it)
                false
            }
            if (ok) {
                registered[name] = command
            } else {
                // 이름이 그새 차서 접두 이름(`inmcalias:이름`)으로만 들어갔다 — 그것은 빼고 가로채기로.
                removeFromMap(name, command)
                routed += def
            }
        }
        intercepted = routed
        feature.commandsChanged()
        for (player in Bukkit.getOnlinePlayers()) player.updateCommands()
    }

    fun unregisterAll() {
        for ((name, command) in registered) removeFromMap(name, command)
        registered.clear()
    }

    /** 검증기용 — 명령 표에 올린 이름(소문자). 메인 스레드. */
    internal fun registeredNames(): Set<String> = registered.keys.toSet()

    /** 검증기용 — 가로채기로 도는 별칭 이름. */
    internal fun interceptedNames(): Set<String> = intercepted.map { it.name.lowercase() }.toSet()

    private fun removeFromMap(name: String, command: Command) {
        val map = Bukkit.getCommandMap()
        runCatching {
            command.unregister(map)
            val known = map.knownCommands
            if (known[name] === command) known.remove(name)
            if (known["$FALLBACK:$name"] === command) known.remove("$FALLBACK:$name")
            known.remove("$FALLBACK:$name")
        }
    }

    // --- 가로채기 -------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        if (intercept(event.player, event.message)) event.isCancelled = true
    }

    /** 콘솔·명령 블록이 친 것(CMI 도 서버 전처리를 본다). RCON 은 따로 사건이라 보지 않는다. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onServerCommand(event: ServerCommandEvent) {
        if (intercept(event.sender, event.command)) event.isCancelled = true
    }

    /** [message] 가 가로채는 별칭이면 돌리고 true. 권한이 없으면 false(원래 명령으로). */
    fun intercept(sender: CommandSender, message: String): Boolean {
        val tokens = message.removePrefix("/").split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        val def = intercepted.mapNotNull { d -> d.match(tokens).takeIf { it >= 0 }?.let { d to it } }.maxByOrNull { it.second }?.first ?: return false
        val node = def.node()
        if (node != null && !sender.hasPermission(node)) return false
        run(sender, def, tokens.first(), tokens.drop(def.words.size))
        return true
    }

    /**
     * 별칭 하나 실행 — 템플릿을 나누고 머리를 읽은 뒤([AliasDef.steps]) 본문에 PAPI 를 풀고(친 사람 기준) 그 다음에 인자를 넣는다:
     * 사람이 친 `%…%`·`;;`·`asConsole!` 은 글자 그대로다.
     */
    fun run(sender: CommandSender, def: AliasDef, label: String, args: List<String>) {
        val player = sender as? Player
        val steps = AliasDef.steps(def.commands, label, args) { body -> Text.substituteOnly(body, null, player) }
        if (steps == null) {
            if (def.usage.isNotBlank()) sender.sendMessage(Text.render(def.usage, null, player))
            else ess.messages.send(sender, "commands-alias-usage", Ph.of().value("/" + def.name))
            return
        }
        feature.runner.runSteps(
            steps,
            CommandRunner.Context(
                player = player, sender = sender, translatePlaceholders = false, defaultAsPlayer = true, origin = "별칭 /${def.name}",
                playerInitiated = player != null,
            ),
        )
    }

    /** 검증기용 — [def] 를 잠깐 가로채기 목록에 넣고 [block] 을 돌린 뒤 되돌린다(명령 표·파일은 그대로). */
    internal fun <T> withIntercept(def: AliasDef, block: () -> T): T {
        val before = intercepted
        intercepted = before + def
        try {
            return block()
        } finally {
            intercepted = before
        }
    }

    // --- 저장 ---------------------------------------------------------------------------------------

    /** 파일을 비동기로 읽어 바꿔 끼운다(켤 때·리로드 때). */
    fun loadAsync() {
        ess.io.async({
            ess.io.copyDefault("commands/aliases.yml", file)
            val problems = mutableListOf<String>()
            val list = if (file.isFile) AliasDef.read(ess.io.load(file)) { problems += it } else emptyList()
            list to problems
        }) { (list, problems) ->
            for (p in problems) ess.logger.warning("commands/aliases.yml: $p")
            apply(list)
        }
    }

    /** 편집기가 바꾼 목록 — 바로 반영하고 파일은 비동기로(원자 쓰기). */
    fun save(next: List<AliasDef>) {
        apply(next)
        val text = AliasDef.write(next, HEADER).saveToString()
        val target = file
        ess.io.asyncRun { AtomicFiles.write(target, text) }
    }

    /** 가져오기 — 같은 이름은 건너뛴다. 넣은 수. */
    fun merge(add: List<AliasDef>): List<AliasDef> {
        val names = defs.map { it.name.lowercase() }.toHashSet()
        val fresh = add.filter { names.add(it.name.lowercase()) }
        if (fresh.isNotEmpty()) save(defs + fresh)
        return fresh
    }

    // --- 명령 -----------------------------------------------------------------------------------------

    /** 명령 표에 올리는 별칭. 권한은 Bukkit 이 보고(목록에서도 숨는다), 실행은 [run]. */
    private inner class AliasCommand(val def: AliasDef) : Command(def.root.lowercase(), def.description.ifBlank { "별칭" }, "/" + def.name, emptyList()) {

        init {
            def.node()?.let { permission = it }
        }

        override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean {
            if (!testPermissionSilent(sender)) {
                ess.messages.send(sender, "no-permission")
                return true
            }
            val current = defs.firstOrNull { it.single && it.root.equals(def.root, true) && it.enabled } ?: def
            if (current.exact && args.isNotEmpty()) {
                ess.messages.send(sender, "commands-alias-usage", Ph.of().value("/" + current.name))
                return true
            }
            run(sender, current, commandLabel, args.toList())
            return true
        }

        override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>): List<String> {
            if (!def.tab || args.isEmpty()) return emptyList()
            val typed = args.last()
            if (def.tabs.isNotEmpty()) {
                val choices = def.tabs.getOrNull(args.size - 1)?.split(' ')?.filter { it.isNotEmpty() } ?: return emptyList()
                return choices.flatMap { c ->
                    when (c.lowercase()) {
                        "[player]", "[players]", "[playername]" -> Vanish.visibleNames(sender)
                        "[world]", "[worlds]" -> Bukkit.getWorlds().map { it.name }
                        else -> listOf(c)
                    }
                }.filter { it.startsWith(typed, ignoreCase = true) }.distinct()
            }
            // 첫 명령의 탭 완성을 빌린다 — `warp $1` 이면 `warp <친 것>` 의 제안.
            val first = def.commands.firstOrNull { CommandLines.delay(it) == null } ?: return emptyList()
            val body = CommandLines.parse(first).takeIf { it.action == CommandLines.Action.COMMAND }?.body ?: return emptyList()
            val cut = body.indexOf('$')
            if (cut <= 0) return emptyList()
            val line = body.substring(0, cut).trimEnd() + " " + args.joinToString(" ")
            return runCatching { Bukkit.getCommandMap().tabComplete(sender, line) }.getOrNull().orEmpty()
        }
    }

    companion object {
        const val FALLBACK = "inmcalias"

        val HEADER = listOf(
            "inmc-essentials 사용자 별칭 — /별칭 편집기가 이 파일을 다시 씁니다(주석은 남지 않습니다). 손으로 고치면 /에센셜 리로드.",
            "commands: 줄마다 명령(앞 / 없이). \$1 = 첫 인자 · \$1- = 첫 인자부터 끝까지 · \$0 = 친 이름 · delay! 3 = 3초 뒤 나머지",
            "  특수 머리: asConsole! · asPlayer! · fromConsole! · perm:<노드>! · moneycost:<금액>! · expcost:<경험치>! · allPlayers!",
            "            msg! <이름> <글> · broadcast! · actionbar! · title! <제목>\\n<부제> · subtitle! · kickall!   (docs/commands.md)",
            "permission: '' = 누구나 · true = inmcessentials.commands.alias.<이름> · 그 밖 = 그 권한 노드",
            "tab-completions: 인자 자리마다 한 줄(빈칸으로 여럿, [player]·[world]). 비우면 첫 명령의 탭 완성을 빌림",
            "exact: true 면 이름만 쳤을 때만(인자가 붙으면 같은 이름의 원래 명령으로)",
            "이름에 빈칸을 넣으면 하위 명령 별칭(예: '스폰 설정'), ? 는 아무 낱말(예: 'ca ?').",
        )

        fun emptyFile(): YamlConfiguration = AliasDef.write(emptyList(), HEADER)
    }
}
