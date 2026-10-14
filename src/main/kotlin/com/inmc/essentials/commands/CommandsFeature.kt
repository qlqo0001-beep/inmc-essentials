package com.inmc.essentials.commands

import com.destroystokyo.paper.event.brigadier.AsyncPlayerSendSuggestionsEvent
import com.inmc.essentials.Ess
import com.inmc.essentials.feature.Feature
import com.inmc.essentials.feature.Perm
import com.inmc.essentials.service.CmiData
import com.inmc.essentials.service.CmiImportable
import com.inmc.essentials.service.ImportReport
import com.inmc.essentials.util.Ph
import com.inmc.essentials.verify.Check
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.context.StringRange
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.suggestion.Suggestions
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import kr.inmc.core.store.SqlSource
import kr.inmc.core.util.AtomicFiles
import kr.inmc.core.util.Durations
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerLevelChangeEvent
import org.bukkit.event.server.ServerLoadEvent
import org.bukkit.permissions.PermissionAttachment
import org.bukkit.permissions.PermissionDefault
import java.time.LocalDateTime

/**
 * 명령어 체계 — 한/영 자판 변환 · 대기·쿨타임·비용 · 반복 막기 · 비슷한 명령·권한 안내 · 사용자 별칭(`/별칭`) · 이벤트 명령 · 일정(`/일정`).
 * CMI 항목: cmd-alias-editor · cfg-command-cost · cfg-warmups · cfg-command-cooldowns · sys-commandfilter · opt-partial-player-name ·
 * mod-eventcommands · cfg-scheduler. 운영 문서 `docs/commands.md`.
 *
 * 다른 기능과 만나는 곳은 `ess.gate`([CommandGateService]) 하나 — 이동 기능이 이동 성공 때 `commit` 을 부른다.
 */
class CommandsFeature(ess: Ess) : Feature(ess), CmiImportable {

    override val id = "commands"

    override val hasSettings = true

    override val permissions: List<Perm> = listOf(
        Perm(ALIAS_EDIT, PermissionDefault.OP, "/별칭 — 사용자 별칭 편집기"),
        Perm(ALIAS_NODE, PermissionDefault.OP, "permission: true 인 별칭은 inmcessentials.commands.alias.<이름> 이 있어야 쓴다"),
        Perm(SCHEDULE, PermissionDefault.OP, "/일정 — 일정 목록·실행·켜고 끄기"),
        Perm(SCHEDULE_EXCLUDE, PermissionDefault.FALSE, "일정의 [randomPlayer] 로 뽑히지 않음"),
        Perm(BYPASS_COOLDOWN, PermissionDefault.OP, "명령 쿨타임 무시(전부)"),
        Perm(BYPASS_WARMUP, PermissionDefault.OP, "명령 대기 무시(전부)"),
        Perm(BYPASS_COST, PermissionDefault.OP, "명령 비용 면제(전부)"),
        Perm(BYPASS_RULE, PermissionDefault.OP, "규칙 하나만 무시는 inmcessentials.commands.bypass.rule.<규칙 id>"),
        Perm(SPAM_BYPASS, PermissionDefault.OP, "같은 명령 반복 막기 무시"),
        Perm(SEE_PERMISSION, PermissionDefault.OP, "권한 없음 안내에 마우스를 올리면 권한 노드가 보임"),
    )

    override val messageDefaults: Map<String, String> = MESSAGES

    @Volatile
    var settings: CommandsSettings = CommandsSettings()
        internal set

    val cooldowns = CooldownStore(ess)

    internal lateinit var runner: CommandRunner
    internal lateinit var gate: CommandGateService
    internal lateinit var keyboard: KeyboardListener
    internal lateinit var aliases: AliasService
    internal lateinit var events: EventCommands
    internal lateinit var schedules: ScheduleService

    /** 이벤트 명령 파일 원본(가져오기가 고쳐 쓴다). */
    private var eventsRaw: YamlConfiguration = YamlConfiguration()

    private var serverLoaded = false

    override fun schema(sql: SqlSource) = cooldowns.schema(sql)

    override fun load(settings: YamlConfiguration) {
        this.settings = CommandsSettings.from(settings)
        if (::gate.isInitialized) {
            gate.invalidate()
            gate.resetSpam()
            keyboard.invalidate()
            loadData()
        }
    }

    override fun enable() {
        runner = CommandRunner(ess)
        gate = CommandGateService(ess, this)
        keyboard = KeyboardListener(ess, this)
        aliases = AliasService(ess, this)
        events = EventCommands(ess, this)
        schedules = ScheduleService(ess, this)
        ess.listen(keyboard)
        ess.listen(gate)
        ess.listen(aliases)
        ess.listen(events)
        ess.listen(object : Listener {
            @EventHandler
            fun onLoad(event: ServerLoadEvent) = onServerLoaded()
        })
        ess.gate = gate
        runner.interceptor = { sender, text -> aliases.intercept(sender, text) }
        ess.ticks.add("commands", 1) { now ->
            schedules.tick(now)
            events.tick(now)
            cooldowns.sweep(now)
        }
        registerCommands()
        loadData()
    }

    override fun ready() {
        // 플러그인을 늦게 켜(PlugMan 등) 서버 준비 사건을 놓쳤으면 지금.
        if (!serverLoaded) onServerLoaded()
        for (player in Bukkit.getOnlinePlayers()) cooldowns.loadLater(player.uniqueId)
    }

    override fun disable() {
        if (::gate.isInitialized) gate.shutdown()
        if (::aliases.isInitialized) runCatching { aliases.unregisterAll() }
        if (ess.gate === (if (::gate.isInitialized) gate else null)) ess.gate = null
    }

    private fun onServerLoaded() {
        serverLoaded = true
        aliases.serverLoaded()
        commandsChanged()
    }

    /** 명령이 늘거나 줄었다 — 이름 맞추기·자판 색인을 다시. */
    fun commandsChanged() {
        if (::gate.isInitialized) gate.invalidate()
        if (::keyboard.isInitialized) keyboard.invalidate()
    }

    private fun loadData() {
        aliases.loadAsync()
        schedules.loadAsync()
        val file = ess.io.file("commands", "events.yml")
        ess.io.async({
            ess.io.copyDefault("commands/events.yml", file)
            val yaml = if (file.isFile) ess.io.load(file) else YamlConfiguration()
            val unknown = mutableListOf<String>()
            Triple(yaml, CommandsSettings.events(yaml) { unknown += it }, unknown)
        }) { (yaml, rules, unknown) ->
            for (u in unknown) ess.logger.warning("commands/events.yml: 모르는 사건 '$u' — 쓸 수 있는 것: ${EventCommands.ALL.joinToString(", ")}")
            eventsRaw = yaml
            events.rules = rules
        }
    }

    // --- 명령 ----------------------------------------------------------------------------------------

    private fun registerCommands() {
        ess.commands.add(
            Commands.literal("별칭").requires { it.sender.hasPermission(ALIAS_EDIT) }
                .executes { ctx -> openAliases(ctx); 1 }
                .then(Commands.literal("목록").executes { ctx -> listAliases(ctx); 1 })
                .build(),
            "사용자 별칭 편집기",
            listOf("aliaseditor"),
        )
        val names = SuggestionProvider<CommandSourceStack> { _, b ->
            val typed = b.remainingLowerCase
            schedules.schedules.map { it.name }.filter { it.lowercase().startsWith(typed) }.forEach { b.suggest(it) }
            b.buildFuture()
        }
        fun named(literal: String, run: (CommandContext<CommandSourceStack>, String) -> Unit) =
            Commands.literal(literal).then(Commands.argument("이름", StringArgumentType.greedyString()).suggests(names).executes { ctx ->
                run(ctx, StringArgumentType.getString(ctx, "이름").trim()); 1
            })
        ess.commands.add(
            Commands.literal("일정").requires { it.sender.hasPermission(SCHEDULE) }
                .executes { ctx -> listSchedules(ctx); 1 }
                .then(named("실행") { ctx, name ->
                    if (schedules.runNow(name)) ess.messages.send(ctx.source.sender, "commands-schedule-ran", Ph.of().value(name))
                    else ess.messages.send(ctx.source.sender, "commands-schedule-unknown", Ph.of().value(name))
                })
                .then(named("켜기") { ctx, name -> toggleSchedule(ctx, name, true) })
                .then(named("끄기") { ctx, name -> toggleSchedule(ctx, name, false) })
                .then(named("타이머") { ctx, name ->
                    if (schedules.resetTimer(name)) ess.messages.send(ctx.source.sender, "commands-schedule-timer-reset", Ph.of().value(name))
                    else ess.messages.send(ctx.source.sender, "commands-schedule-unknown", Ph.of().value(name))
                })
                .build(),
            "일정 — 목록·실행·켜고 끄기",
            listOf("schedules"),
        )
        registerFinders()
    }

    /** `/에센셜 명령찾기 <글>` · `/에센셜 권한찾기 <글>` — CMI `checkcommand`·`checkperm`. 관리 루트 아래라 권한은 `inmcessentials.admin`. */
    private fun registerFinders() {
        ess.commands.addAdmin(
            Commands.literal("명령찾기").then(Commands.argument("글", StringArgumentType.greedyString()).executes { ctx ->
                findCommands(ctx.source.sender, StringArgumentType.getString(ctx, "글").trim().lowercase()); 1
            }).build(),
        )
        ess.commands.addAdmin(
            Commands.literal("권한찾기").then(Commands.argument("글", StringArgumentType.greedyString()).executes { ctx ->
                findPermissions(ctx.source.sender, StringArgumentType.getString(ctx, "글").trim().lowercase()); 1
            }).build(),
        )
    }

    private fun findCommands(sender: org.bukkit.command.CommandSender, text: String) {
        val known = runCatching { Bukkit.getCommandMap().knownCommands }.getOrNull().orEmpty()
        val hits = known.entries.filter { (name, cmd) -> ':' !in name && (name.lowercase().contains(text) || cmd.description.lowercase().contains(text)) }
            .sortedBy { it.key }
        if (hits.isEmpty()) return ess.messages.send(sender, "commands-find-none", Ph.of().value(text))
        ess.messages.send(sender, "commands-find-command-header", Ph.of().value(text).count(hits.size))
        for ((name, cmd) in hits.take(FIND_LIMIT)) {
            val owner = (cmd as? org.bukkit.command.PluginIdentifiableCommand)?.plugin?.name ?: known.keys.firstOrNull { it.endsWith(":$name") }?.substringBefore(':') ?: "-"
            ess.messages.send(
                sender, "commands-find-command-line",
                Ph.of().value("/$name").raw("{설명}", cmd.description.ifBlank { "-" }).raw("{권한}", cmd.permission ?: "-").raw("{플러그인}", owner),
            )
        }
        if (hits.size > FIND_LIMIT) ess.messages.send(sender, "commands-find-more", Ph.of().count(hits.size - FIND_LIMIT))
    }

    private fun findPermissions(sender: org.bukkit.command.CommandSender, text: String) {
        val hits = Bukkit.getPluginManager().permissions.filter { it.name.lowercase().contains(text) || it.description.lowercase().contains(text) }.sortedBy { it.name }
        if (hits.isEmpty()) return ess.messages.send(sender, "commands-find-none", Ph.of().value(text))
        ess.messages.send(sender, "commands-find-perm-header", Ph.of().value(text).count(hits.size))
        for (perm in hits.take(FIND_LIMIT)) {
            val has = if (sender.hasPermission(perm)) "commands-on" else "commands-off"
            ess.messages.send(
                sender, "commands-find-perm-line",
                Ph.of().value(perm.name).raw("{설명}", perm.description.ifBlank { "-" }).raw("{기본}", perm.default.name.lowercase()).raw("{가짐}", ess.messages.raw(has)),
            )
        }
        if (hits.size > FIND_LIMIT) ess.messages.send(sender, "commands-find-more", Ph.of().count(hits.size - FIND_LIMIT))
    }

    private fun openAliases(ctx: CommandContext<CommandSourceStack>) {
        val player = ctx.source.executor as? Player ?: ctx.source.sender as? Player
        if (player == null) listAliases(ctx) else AliasMenu(ess, player, aliases).show()
    }

    private fun listAliases(ctx: CommandContext<CommandSourceStack>) {
        val sender = ctx.source.sender
        val defs = aliases.defs
        if (defs.isEmpty()) return ess.messages.send(sender, "commands-alias-list-empty")
        ess.messages.send(sender, "commands-alias-list-header", Ph.of().count(defs.size))
        for (d in defs.sortedBy { it.name.lowercase() }) {
            ess.messages.send(
                sender, "commands-alias-list-line",
                Ph.of().value("/" + d.name).raw("{명령}", d.commands.joinToString(" | ").replace('%', '％')).raw("{상태}", ess.messages.raw(if (d.enabled) "commands-on" else "commands-off")),
            )
        }
    }

    private fun listSchedules(ctx: CommandContext<CommandSourceStack>) {
        val sender = ctx.source.sender
        val list = schedules.schedules
        if (list.isEmpty()) return ess.messages.send(sender, "commands-schedule-empty")
        ess.messages.send(sender, "commands-schedule-header", Ph.of().count(list.size))
        for (s in list) {
            val state = ess.messages.raw(if (s.enabled) "commands-on" else "commands-off")
            val ph = Ph.of().value(s.name).raw("{상태}", state).count(s.commands.size)
            when {
                schedules.done(s.name) -> ess.messages.send(sender, "commands-schedule-line-done", ph)
                s.timed -> ess.messages.send(sender, "commands-schedule-line-timed", ph.time(s.frames.joinToString(", ") { frameText(it) }))
                else -> ess.messages.send(sender, "commands-schedule-line-interval", ph.time(Durations.formatShort(schedules.secondsLeft(s.name) ?: s.delay)).amount(Durations.formatShort(s.delay)))
            }
        }
    }

    private fun frameText(f: TimeFrame): String = buildString {
        f.month?.let { append("${it}월 ") }
        f.day?.let { append("${it}일 ") }
        f.weekday?.let { append(WEEK[it.value - 1]).append("요일 ") }
        f.firstWeekday?.let { append("첫 ").append(WEEK[it.value - 1]).append("요일 ") }
        f.lastWeekday?.let { append("마지막 ").append(WEEK[it.value - 1]).append("요일 ") }
        append(f.hour?.let { "%02d".format(it) } ?: "매시")
        append(":%02d:%02d".format(f.minute, f.second))
    }

    private fun toggleSchedule(ctx: CommandContext<CommandSourceStack>, name: String, on: Boolean) {
        val sender = ctx.source.sender
        if (!schedules.setEnabled(name, on)) return ess.messages.send(sender, "commands-schedule-unknown", Ph.of().value(name))
        ess.messages.send(sender, if (on) "commands-schedule-enabled" else "commands-schedule-disabled", Ph.of().value(name))
    }

    // --- CMI 가져오기 ------------------------------------------------------------------------------------

    override fun importCmi(data: CmiData, report: ImportReport) {
        if (!::aliases.isInitialized) return report.skipped(CmiCommandImport.KIND_ALIAS, "commands 기능이 켜지지 않았습니다")
        val cmiRunning = Bukkit.getPluginManager().isPluginEnabled("CMI")
        data.customAliases?.let { importAliases(it, report) }
        val heldEvents = data.eventCommands?.let { importEvents(it, cmiRunning, report) }.orEmpty()
        val heldSchedules = data.schedules?.let { importSchedules(it, cmiRunning, report) }.orEmpty()
        if (heldEvents.isNotEmpty() || heldSchedules.isNotEmpty()) {
            report.note(
                "CMI 가 아직 켜져 있어 가져온 이벤트 명령·일정을 꺼 두었습니다(같은 명령이 CMI 와 두 번 돌지 않게). CMI 를 뺀 뒤 켜세요 — " +
                    (if (heldSchedules.isNotEmpty()) "일정: ${heldSchedules.joinToString(", ") { "/일정 켜기 $it" }}" else "") +
                    (if (heldSchedules.isNotEmpty() && heldEvents.isNotEmpty()) " · " else "") +
                    (if (heldEvents.isNotEmpty()) "이벤트: commands/events.yml 의 ${heldEvents.joinToString(", ")} 에 Enabled: true 뒤 /에센셜 리로드" else ""),
            )
        }
    }

    private fun importAliases(yaml: YamlConfiguration, report: ImportReport) {
        val found = CmiCommandImport.aliases(yaml, report)
        val added = aliases.merge(found)
        report.imported(CmiCommandImport.KIND_ALIAS, added.size)
        for (d in found - added.toSet()) report.skipped(CmiCommandImport.KIND_ALIAS, "'${d.name}' — 같은 이름의 별칭이 이미 있습니다")
    }

    /** 반환: CMI 가 켜져 있어 꺼 둔 사건. */
    private fun importEvents(yaml: YamlConfiguration, cmiRunning: Boolean, report: ImportReport): List<String> {
        val result = CmiCommandImport.events(yaml, { id -> events.rules[id]?.enabled == true }, cmiRunning, report)
        if (result.items.isEmpty()) return emptyList()
        for (e in result.items) {
            eventsRaw.set("${e.id}.Enabled", e.enabled)
            eventsRaw.set("${e.id}.Commands", e.commands)
            if (e.source.isNotEmpty()) eventsRaw.set("${e.id}.SourceCommands", e.source)
            e.cancel?.let { eventsRaw.set("${e.id}.CancelEvent", it) }
        }
        events.rules = CommandsSettings.events(eventsRaw)
        val text = eventsRaw.saveToString()
        val file = ess.io.file("commands", "events.yml")
        ess.io.asyncRun { AtomicFiles.write(file, text) }
        return result.heldBack
    }

    /** 반환: CMI 가 켜져 있어 꺼 둔 일정(넣은 것만). */
    private fun importSchedules(yaml: YamlConfiguration, cmiRunning: Boolean, report: ImportReport): List<String> {
        val result = CmiCommandImport.schedules(yaml, cmiRunning, report)
        val added = schedules.merge(result.items)
        report.imported(CmiCommandImport.KIND_SCHEDULE, added.size)
        for (name in result.items.keys - added.toSet()) report.skipped(CmiCommandImport.KIND_SCHEDULE, "$name — 같은 이름의 일정이 이미 있습니다")
        return result.heldBack.filter { it in added }
    }

    // --- 검증기 ----------------------------------------------------------------------------------------

    override fun checks(): List<Check> = listOf(
        Check("ess.gate 가 이 기능이다") { _ -> if (ess.gate === gate) null else "ess.gate 가 commands 기능이 아닙니다(${ess.gate})" },
        // 자판 검사는 사건을 우리 리스너에만 준다 — callEvent 로 쏘면 명령 로그(server)·명령 엿보기(chat)·반복 막기 창에 가짜 명령이 남는다(규칙 13).
        Check("자판 변환 — 영타로 친 한글 명령(/별칭 → qufcld)") { p ->
            if (!settings.keyboard) return@Check null
            val event = PlayerCommandPreprocessEvent(p, "/" + Keyboard.keys("별칭") + " 목록")
            keyboard.onCommand(event)
            if (event.message == "/별칭 목록") null else "'/${Keyboard.keys("별칭")} 목록' 이 '${event.message}' 가 됐습니다(권한·명령 트리가 없으면 안 바뀝니다)"
        },
        Check("자판 변환 — 한글 자판으로 친 영어 명령(자모 그대로)") { p ->
            if (!settings.keyboard) return@Check null
            val typed = jamoOf("aliaseditor")
            val event = PlayerCommandPreprocessEvent(p, "/$typed")
            keyboard.onCommand(event)
            if (event.message == "/aliaseditor" || event.message == "/별칭") null else "'/$typed' 이 '${event.message}' 가 됐습니다"
        },
        Check("별칭 — 친 인자로 줄·머리를 끼워 넣을 수 없다") { _ ->
            val steps = AliasDef.steps(listOf("귓속말 \$1-"), "귓", listOf("a", ";;asConsole!", "op", "x"))
            val line = steps?.singleOrNull() as? CommandLines.Step.Line
            when {
                line == null -> "실행 줄이 하나가 아닙니다: $steps"
                line.parsed.asConsole || line.parsed.action != CommandLines.Action.COMMAND -> "인자가 머리로 읽혔습니다: ${line.parsed}"
                else -> null
            }
        },
        Check("쿨타임 — 기록이 있으면 막는다(메모리만)") { p -> withRule(p, CommandRule(PROBE_RULE, listOf(PROBE_COMMAND), cooldown = 60)) {
            cooldowns.putMemory(p.uniqueId, PROBE_RULE, System.currentTimeMillis())
            try {
                val event = PlayerCommandPreprocessEvent(p, "/$PROBE_COMMAND")
                gate.onCommand(event)
                if (event.isCancelled) null else "쿨타임 중인데 막지 않았습니다"
            } finally {
                cooldowns.removeMemory(p.uniqueId, PROBE_RULE)
            }
        } },
        Check("대기 — 명령을 멈추고 카운트다운(메모리만)") { p -> withRule(p, CommandRule(PROBE_RULE, listOf(PROBE_COMMAND), warmup = 5, move = -1.0, cancelOnDamage = false)) {
            try {
                val event = PlayerCommandPreprocessEvent(p, "/$PROBE_COMMAND")
                gate.onCommand(event)
                when {
                    !event.isCancelled -> "대기 규칙인데 명령을 바로 보냈습니다"
                    !gate.warming(p) -> "대기를 시작하지 않았습니다"
                    else -> null
                }
            } finally {
                gate.cancelWarmup(p, null)
            }
        } },
        Check("반복 막기 — 같은 명령 세 번째는 막는다") { p ->
            val before = settings
            val attachment: PermissionAttachment = p.addAttachment(ess.plugin, SPAM_BYPASS, false)
            try {
                settings = before.copy(spam = true, spamMaxRepeats = 2, spamInterval = 30, spamWhitelist = emptySet())
                gate.resetSpam()
                val results = (1..3).map { PlayerCommandPreprocessEvent(p, "/$PROBE_COMMAND").also { e -> gate.onSpam(e) }.isCancelled }
                if (results == listOf(false, false, true)) null else "막힘 결과가 $results 입니다(기대 [false, false, true])"
            } finally {
                settings = before
                gate.resetSpam()
                p.removeAttachment(attachment)
            }
        },
        Check("별칭 — 켜진 한 낱말 별칭이 명령 표에 올라 있다(덮는 것은 가로채기)") { _ ->
            val map = Bukkit.getCommandMap()
            val registered = aliases.registeredNames()
            val routed = aliases.interceptedNames()
            val missing = aliases.defs.filter { it.enabled && it.single && !it.selfCalls() }.map { it.root.lowercase() }
                .filter { it !in routed && (it !in registered || map.getCommand(it) == null) }
            if (missing.isEmpty()) null else "명령 표에 없는 별칭: ${missing.joinToString(", ")}(서버 준비 전이면 /에센셜 검증 을 다시)"
        },
        Check("탭 완성 — 영타 루트의 인자 제안(/qufcld → /별칭 의 하위 명령, 취소 풀기)") { p ->
            if (!settings.keyboard || !settings.keyboardTab) return@Check null
            val buffer = "/" + Keyboard.keys("별칭") + " "
            // Paper 가 보내는 모양 그대로 — 제안이 비어 있으면 취소된 채 온다. 우리 리스너에만 준다(규칙 13).
            val event = AsyncPlayerSendSuggestionsEvent(p, Suggestions(StringRange.at(buffer.length), emptyList()), buffer)
            event.isCancelled = true
            keyboard.onSuggestions(event)
            when {
                event.suggestions.isEmpty -> "'$buffer' 의 제안이 비어 있습니다(명령 표에 /별칭 이 없거나 변환이 안 됐습니다)"
                event.isCancelled -> "제안을 채웠는데 취소를 풀지 않았습니다(손님에게 가지 않습니다)"
                else -> null
            }
        },
        Check("없는 명령 안내 — 영타로 친 한글 명령의 후보(혹시 /별칭?)") { p ->
            if (!settings.unknownHint) return@Check null
            val typed = Keyboard.keys("별칭")
            val candidates = keyboard.hintCandidates(p, typed)
            if ("별칭" in candidates) null else "'/$typed' 의 후보에 /별칭 이 없습니다: $candidates"
        },
        Check("별칭 — 하위 명령 별칭을 가로챈다(메모리만)") { p ->
            val def = AliasDef("$PROBE_COMMAND ?", listOf("msg! [playerName] <gray>(검증) 별칭이 돌았습니다</gray>"))
            aliases.withIntercept(def) {
                val event = PlayerCommandPreprocessEvent(p, "/$PROBE_COMMAND 아무거나")
                aliases.onCommand(event)
                if (event.isCancelled) null else "별칭 '$PROBE_COMMAND ?' 이 '/$PROBE_COMMAND 아무거나' 를 가로채지 않았습니다"
            }
        },
        Check("이벤트 명령 — 레벨 바뀜 사건(메모리만)") { p ->
            val before = events.rules
            try {
                events.lastFired = null
                events.rules = before + (EventCommands.LEVEL_CHANGE to EventRule(true, listOf("msg! [playerName] <gray>(검증) 이벤트 명령이 돌았습니다</gray>")))
                // 우리 리스너에만 — 다른 플러그인의 레벨 보상이 가짜 사건에 반응하지 않게.
                events.onLevel(PlayerLevelChangeEvent(p, p.level, p.level))
                if (events.lastFired == EventCommands.LEVEL_CHANGE) null else "playerLevelChange 사건을 받지 못했습니다"
            } finally {
                events.rules = before
            }
        },
        Check("비용 — 돈이 들면 확인을 묻고 실행을 멈춘다(돈은 빼지 않음)") { p ->
            if (!ess.economy.isEnabled) return@Check null
            withRule(p, CommandRule(PROBE_RULE, listOf(PROBE_COMMAND), cost = 1.0, costConfirm = true)) {
                try {
                    val event = PlayerCommandPreprocessEvent(p, "/$PROBE_COMMAND")
                    gate.onCommand(event)
                    val enough = ess.economy.has(p, 1.0, null)
                    when {
                        !event.isCancelled -> "비용 규칙인데 확인 없이 명령을 보냈습니다"
                        enough && !gate.awaitingConfirm(p) -> "돈이 있는데 확인을 묻지 않았습니다"
                        !enough && gate.awaitingConfirm(p) -> "돈이 모자란데 확인을 물었습니다"
                        else -> null
                    }
                } finally {
                    gate.clearConfirm(p)
                }
            }
        },
        Check("성공해야 쿨타임 — 확정(ess.gate.commit) 때 적고, 다시 치면 앞 시도는 버린다(메모리만)") { p ->
            withRule(p, CommandRule(PROBE_RULE, listOf(PROBE_COMMAND), cooldown = 60, commitOnSuccess = true)) {
                cooldowns.memoryOnly = true
                try {
                    repeat(2) { gate.onCommand(PlayerCommandPreprocessEvent(p, "/$PROBE_COMMAND")) }
                    val waiting = gate.pendingCount(p, PROBE_COMMAND)
                    val before = cooldowns.lastUsed(p.uniqueId, PROBE_RULE)
                    gate.commit(p, PROBE_COMMAND)
                    when {
                        waiting != 1 -> "같은 명령 두 번에 확정 대기가 ${waiting}개입니다(1개여야 실패한 시도 몫이 얹히지 않습니다)"
                        before != null -> "확정 전에 쿨타임을 적었습니다"
                        cooldowns.lastUsed(p.uniqueId, PROBE_RULE) == null -> "확정했는데 쿨타임을 적지 않았습니다"
                        else -> null
                    }
                } finally {
                    cooldowns.memoryOnly = false
                    cooldowns.removeMemory(p.uniqueId, PROBE_RULE)
                    gate.clearPending(p)
                }
            }
        },
        Check("일정 — 시각 맞추기") { _ ->
            val now = LocalDateTime.now().withNano(0)
            val frame = TimeFrame(hour = now.hour, minute = now.minute, second = now.second)
            if (frame.matches(now) && !frame.copy(second = (now.second + 1) % 60).matches(now)) null else "TimeFrame 이 지금 시각을 맞추지 못했습니다"
        },
    )

    /** 검증용 규칙 하나를 잠깐 끼운다 — 우회 권한은 잠깐 끈다(관리자도 막히는지 봐야 하니까). */
    private fun withRule(p: Player, rule: CommandRule, block: () -> String?): String? {
        val before = settings
        val attachment = p.addAttachment(ess.plugin)
        for (node in listOf(BYPASS_COOLDOWN, BYPASS_WARMUP, BYPASS_COST, CommandGateService.bypassNode(rule))) attachment.setPermission(node, false)
        try {
            settings = before.copy(rules = before.rules + rule)
            gate.invalidate()
            return block()
        } finally {
            settings = before
            gate.invalidate()
            p.removeAttachment(attachment)
        }
    }

    /** 영어 → 한글 자판 낱자모(합치지 않은 꼴 — `네ㅁ주` 처럼). */
    private fun jamoOf(english: String): String {
        val rev = "rRseEfaqQtTdwWczxvgkoiOjpuPhynbml".zip("ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎㅏㅐㅑㅒㅓㅔㅕㅖㅗㅛㅜㅠㅡㅣ").toMap()
        return english.map { rev[it] ?: it }.joinToString("")
    }

    companion object {
        const val ALIAS_EDIT = "inmcessentials.commands.aliaseditor"
        const val ALIAS_NODE = "inmcessentials.commands.alias"
        const val SCHEDULE = "inmcessentials.commands.schedule"
        const val SCHEDULE_EXCLUDE = "inmcessentials.commands.schedule.exclude"
        const val BYPASS_COOLDOWN = "inmcessentials.commands.bypass.cooldown"
        const val BYPASS_WARMUP = "inmcessentials.commands.bypass.warmup"
        const val BYPASS_COST = "inmcessentials.commands.bypass.cost"
        const val BYPASS_RULE = "inmcessentials.commands.bypass.rule"
        const val SPAM_BYPASS = "inmcessentials.commands.spam.bypass"
        const val SEE_PERMISSION = "inmcessentials.commands.seepermission"

        private const val FIND_LIMIT = 30
        private const val PROBE_RULE = "essverify"
        private const val PROBE_COMMAND = "essverifycmd"

        private val WEEK = listOf("월", "화", "수", "목", "금", "토", "일")

        val MESSAGES: Map<String, String> = linkedMapOf(
            "commands-on" to "<green>켬</green>",
            "commands-off" to "<red>끔</red>",
            // --- 실행기 ---
            "commands-runner-no-money" to "<red>돈이 모자랍니다 — {금액} 이(가) 필요합니다.</red>",
            "commands-runner-no-exp" to "<red>경험치가 모자랍니다 — {개수} 이(가) 필요합니다.</red>",
            // --- 반복 막기 ---
            "commands-spam-blocked" to "<red>같은 명령을 너무 자주 쳤습니다.</red> <gray>{시간}초 뒤에 다시 해 주세요.</gray>",
            // --- 쿨타임 · 비용 · 대기 ---
            "commands-cooldown" to "<red>{값} 은(는) {시간} 뒤에 다시 쓸 수 있습니다.</red>",
            "commands-cooldown-once" to "<red>{값} 은(는) 한 번만 쓸 수 있습니다.</red>",
            "commands-cost-not-enough" to "<red>{값} 에는 {금액} 이(가) 필요합니다.</red>",
            "commands-cost-confirm" to "<yellow>{값} 은(는) {금액} 이(가) 듭니다.</yellow> <green><u>[여기를 누르거나 같은 명령을 다시 치면 실행]</u></green> <gray>({시간}초 안에)</gray>",
            "commands-cost-confirm-hover" to "<gray>누르면 {값} 을(를) 실행하고 돈을 냅니다</gray>",
            "commands-cost-charged" to "<gray>{값} — {금액} 을(를) 냈습니다.</gray>",
            "commands-cost-failed-after" to "<yellow>{값} 의 비용 {금액} 을(를) 내지 못했습니다(그사이 돈이 줄었습니다).</yellow>",
            "commands-warmup-start" to "<yellow>{시간}초 뒤에 {값} 을(를) 실행합니다.</yellow>",
            "commands-warmup-start-still" to "<yellow>{시간}초 뒤에 {값} 을(를) 실행합니다.</yellow> <gray>움직이면 취소됩니다.</gray>",
            "commands-warmup-start-range" to "<yellow>{시간}초 뒤에 {값} 을(를) 실행합니다.</yellow> <gray>{금액}칸 넘게 움직이면 취소됩니다.</gray>",
            "commands-warmup-actionbar" to "<yellow>{값}</yellow> <white>{시간}</white><gray>초…</gray>",
            "commands-warmup-title" to "<yellow>{시간}</yellow>",
            "commands-warmup-subtitle" to "<gray>{값} 실행까지</gray>",
            "commands-warmup-chat" to "<gray>{값} — {시간}초…</gray>",
            "commands-warmup-bossbar" to "<yellow>{값}</yellow> <gray>실행 대기</gray>",
            "commands-warmup-cancel-move" to "<red>움직여서 {값} 을(를) 취소했습니다.</red>",
            "commands-warmup-cancel-damage" to "<red>공격받아 {값} 을(를) 취소했습니다.</red>",
            "commands-warmup-cancel-teleport" to "<red>순간이동해서 {값} 을(를) 취소했습니다.</red>",
            // --- 안내 ---
            "commands-hint-no-permission" to "<red>{값} 을(를) 쓸 권한이 없습니다.</red>",
            "commands-hint-permission-node" to "<gray>필요한 권한:</gray> <white>{값}</white>",
            "commands-hint-did-you-mean" to "<yellow>혹시</yellow>",
            "commands-hint-candidate" to "<green><u>{값}</u></green>",
            "commands-hint-candidate-hover" to "<gray>누르면 입력창에 넣습니다:</gray> <white>{값}</white>",
            // --- 별칭 ---
            "commands-alias-usage" to "<red>사용법이 맞지 않습니다:</red> <white>{값}</white>",
            "commands-alias-bad-name" to "<red>'{값}' 은(는) 별칭 이름으로 쓸 수 없습니다 — {사유}.</red>",
            "commands-alias-no-commands" to "<red>명령을 한 줄 이상 적어 주세요.</red>",
            "commands-alias-exists" to "<red>'{값}' 별칭이 이미 있습니다.</red>",
            "commands-alias-saved" to "<green>{값} 별칭을 저장했습니다.</green>",
            "commands-alias-self-call" to "<red>{값} 별칭이 자기 자신을 부릅니다 — 끝없이 돌므로 저장하지 않았습니다.</red> <gray>원래 명령은 접두 이름(예: inmc-essentials:워프)으로 부르세요.</gray>",
            "commands-alias-list-header" to "<gold>별칭 {개수}개</gold>",
            "commands-alias-list-line" to "{상태} <white>{값}</white> <gray>→ {명령}</gray>",
            "commands-alias-list-empty" to "<gray>별칭이 없습니다.</gray> <gray>/별칭 으로 만드세요.</gray>",
            // --- 일정 ---
            "commands-schedule-header" to "<gold>일정 {개수}개</gold> <gray>— /일정 실행·켜기·끄기·타이머 <이름></gray>",
            "commands-schedule-line-interval" to "{상태} <white>{값}</white> <gray>— {금액}마다 · 다음까지 {시간} · 명령 {개수}줄</gray>",
            "commands-schedule-line-timed" to "{상태} <white>{값}</white> <gray>— {시간} · 명령 {개수}줄</gray>",
            "commands-schedule-line-done" to "{상태} <white>{값}</white> <gray>— 이번 실행에서 이미 돌았습니다(Repeat: false)</gray>",
            "commands-schedule-empty" to "<gray>일정이 없습니다.</gray> <gray>(plugins/inmc-essentials/commands/schedules.yml)</gray>",
            "commands-schedule-unknown" to "<red>'{값}' 일정이 없습니다.</red>",
            "commands-schedule-ran" to "<green>일정 {값} 을(를) 지금 돌렸습니다.</green>",
            "commands-schedule-enabled" to "<green>일정 {값} 을(를) 켰습니다.</green>",
            "commands-schedule-disabled" to "<yellow>일정 {값} 을(를) 껐습니다.</yellow>",
            "commands-schedule-timer-reset" to "<green>일정 {값} 의 간격을 지금부터 다시 셉니다.</green>",
            // --- 명령·권한 찾기 ---
            "commands-find-none" to "<gray>'{값}' 이(가) 든 것이 없습니다.</gray>",
            "commands-find-command-header" to "<gold>'{값}' 명령 {개수}개</gold>",
            "commands-find-command-line" to "<white>{값}</white> <gray>— {설명} · 권한 {권한} · {플러그인}</gray>",
            "commands-find-perm-header" to "<gold>'{값}' 권한 {개수}개</gold>",
            "commands-find-perm-line" to "{가짐} <white>{값}</white> <gray>— {설명} · 기본 {기본}</gray>",
            "commands-find-more" to "<gray>…그리고 {개수}개 더 — 더 좁혀 찾으세요</gray>",
        )
    }
}
