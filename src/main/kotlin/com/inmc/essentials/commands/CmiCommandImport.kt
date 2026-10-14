package com.inmc.essentials.commands

import com.inmc.essentials.service.ImportReport
import org.bukkit.configuration.ConfigurationSection

/**
 * CMI 의 별칭(`CustomAlias` 폴더의 yml)·이벤트 명령(`Settings/EventCommands.yml`)·일정(`Settings/Schedules.yml`)을 우리 모양으로(순수 — 시험이 돌린다).
 * 넣기(파일 쓰기·바꿔 끼우기)는 [CommandsFeature.importCmi].
 *
 * - CMI 명령(`cmi …`)을 부르는 것은 꺼 두고 가져온다 — CMI 를 빼면 돌지 않는다.
 * - [cmiRunning] 이면(가져오기는 CMI 를 빼기 **전에** 돈다) 켜져 있던 이벤트 명령·일정도 꺼 둔다 — 안 그러면 CMI 를 뺄 때까지 같은 명령이
 *   CMI 와 이 플러그인에서 두 번 돈다(킬 보상 돈·일정 아이템 두 번). 꺼 둔 이름은 [Result.heldBack] 에 — 보고서에 켜는 법과 함께.
 */
object CmiCommandImport {

    const val KIND_ALIAS = "별칭"
    const val KIND_EVENT = "이벤트 명령"
    const val KIND_SCHEDULE = "일정"

    /** 이벤트 하나 — [enabled] 는 넣을 때의 값. */
    data class EventImport(val id: String, val enabled: Boolean, val commands: List<String>, val source: List<String>, val cancel: Boolean?)

    class Result<T>(val items: T, val heldBack: List<String>)

    /** `cmi …` 명령 줄이 있나(머리 `asConsole!` 등을 뗀 뒤 본다). */
    fun callsCmi(lines: List<String>): Boolean = CommandLines.steps(lines).any { s ->
        s is CommandLines.Step.Line && s.parsed.action == CommandLines.Action.COMMAND &&
            s.parsed.body.trim().lowercase().let { it == "cmi" || it.startsWith("cmi ") }
    }

    fun aliases(yaml: ConfigurationSection, report: ImportReport): List<AliasDef> {
        val root = yaml.getConfigurationSection("CustomAlias") ?: yaml
        val out = mutableListOf<AliasDef>()
        for (key in root.getKeys(false)) {
            report.read(KIND_ALIAS)
            val s = root.getConfigurationSection(key)
            if (s == null) {
                report.unreadable(KIND_ALIAS, "'$key' — 섹션이 아닙니다: ${root.get(key)}")
                continue
            }
            val problem = AliasDef.nameProblem(key)
            if (problem != null) {
                report.unreadable(KIND_ALIAS, "'$key' — 이름을 쓸 수 없습니다($problem): ${s.getStringList("Cmds")}")
                continue
            }
            val cmds = s.getStringList("Cmds").map { it.trim().removePrefix("/") }.filter { it.isNotEmpty() }
            if (cmds.isEmpty()) {
                report.unreadable(KIND_ALIAS, "'$key' — Cmds 가 비었습니다")
                continue
            }
            val cmiEnabled = s.getBoolean("Enabled", true)
            if (!cmiEnabled) report.note("별칭 '$key' — CMI 에서 꺼져 있어(Enabled: false) 꺼 둔 채로 가져옵니다")
            val cmi = callsCmi(cmds)
            if (cmi) report.note("별칭 '$key' — CMI 명령(cmi …)을 부릅니다. CMI 를 빼면 안 돌므로 꺼 두고 가져옵니다(/별칭 에서 고친 뒤 켜세요): ${cmds.joinToString(" | ")}")
            if (s.getBoolean("AddTabs", false)) {
                report.note("별칭 '$key' — AddTabs 는 옮기지 않습니다: 탭 완성은 CustomTabs 가 있으면 그것, 없으면 첫 명령의 탭 완성을 빌립니다")
            }
            if (s.getBoolean("BaseDisabled", false)) {
                report.note("별칭 '$key' — BaseDisabled 는 옮기지 않습니다: 같은 이름의 명령이 있으면 이 별칭이 그것을 덮어 가로챕니다(exact 면 이름만 쳤을 때만)")
            }
            var def = AliasDef(
                name = key.trim(), commands = cmds, permission = if (s.getBoolean("Perm", false)) "true" else "",
                tabs = s.getStringList("CustomTabs"), tab = s.getBoolean("Tab", true), exact = s.getBoolean("ExactMatch", false),
                enabled = cmiEnabled && !cmi,
            )
            if (def.selfCalls()) {
                report.note("별칭 '$key' — 자기 자신을 부릅니다(끝없이 돎). 꺼 두고 가져옵니다 — 원래 명령은 접두 이름으로 부르게 고치세요")
                def = def.copy(enabled = false)
            }
            out += def
        }
        return out
    }

    /** [alreadyOn] = 이 플러그인에서 이미 켜진 사건(건너뜀). */
    fun events(yaml: ConfigurationSection, alreadyOn: (String) -> Boolean, cmiRunning: Boolean, report: ImportReport): Result<List<EventImport>> {
        val out = mutableListOf<EventImport>()
        val held = mutableListOf<String>()
        for (key in yaml.getKeys(false)) {
            val s = yaml.getConfigurationSection(key) ?: continue
            report.read(KIND_EVENT)
            val id = EventCommands.ALL.firstOrNull { it.equals(key, true) }
            if (id == null) {
                report.unreadable(KIND_EVENT, "'$key' — 이 플러그인에 없는 사건입니다: ${s.getStringList("Commands")}")
                continue
            }
            if (!s.getBoolean("Enabled", false)) {
                report.skipped(KIND_EVENT, "$key — CMI 에서 꺼져 있습니다")
                continue
            }
            if (alreadyOn(id)) {
                report.skipped(KIND_EVENT, "$key — 이미 켜진 이벤트 명령이 있습니다")
                continue
            }
            val commands = s.getStringList("Commands")
            val source = s.getStringList("SourceCommands")
            val cmi = callsCmi(commands + source)
            if (cmi) report.note("이벤트 $key — CMI 명령(cmi …)을 부릅니다. 꺼 두고 가져옵니다(commands/events.yml 에서 고친 뒤 켜세요)")
            else if (cmiRunning) held += id
            out += EventImport(id, enabled = !cmi && !cmiRunning, commands, source, if (s.contains("CancelEvent")) s.getBoolean("CancelEvent") else null)
            report.imported(KIND_EVENT)
        }
        return Result(out, held)
    }

    /** 이름 → 넣을 값(CMI 키 그대로 — [Schedule.read] 가 읽는다). 같은 이름 건너뛰기는 넣는 쪽([ScheduleService.merge]). */
    fun schedules(yaml: ConfigurationSection, cmiRunning: Boolean, report: ImportReport): Result<LinkedHashMap<String, Map<String, Any?>>> {
        val found = LinkedHashMap<String, Map<String, Any?>>()
        val held = mutableListOf<String>()
        for (name in yaml.getKeys(false)) {
            val s = yaml.getConfigurationSection(name) ?: continue
            report.read(KIND_SCHEDULE)
            val problems = mutableListOf<String>()
            val parsed = Schedule.read(name, s) { problems += it }
            for (p in problems) report.unreadable(KIND_SCHEDULE, p)
            if (parsed == null) continue
            val values = deep(s).toMutableMap()
            if (callsCmi(parsed.commands + parsed.minPlayersCommands)) {
                values["Enabled"] = false
                report.note("일정 $name — CMI 명령(cmi …)을 부릅니다. 꺼 두고 가져옵니다(/일정 켜기 $name 전에 명령을 고치세요)")
            } else if (cmiRunning && parsed.enabled) {
                values["Enabled"] = false
                held += name
            }
            found[name] = values
        }
        return Result(found, held)
    }

    /** 섹션 → 평범한 값(목록 속 지도는 그대로). */
    private fun deep(s: ConfigurationSection): Map<String, Any?> =
        s.getValues(false).mapValues { (_, v) -> if (v is ConfigurationSection) deep(v) else v }
}
