package com.inmc.essentials.commands

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration

/**
 * 사용자 별칭 하나(CMI CustomAlias). [name] 은 한 낱말(`밥`) 또는 여러 낱말(`스폰 설정` · `ca ?` — `?` 는 아무 낱말).
 *
 * - [commands]: 줄마다 명령(앞 `/` 없이). `$1` = 첫 인자, `$1-` = 첫 인자부터 끝까지, `$0` = 친 이름. `delay! N` · 특수 머리([CommandLines]).
 * - [permission]: 빈 값 = 누구나, `true` = `inmcessentials.commands.alias.<첫 낱말>`, 그 밖 = 그 노드.
 * - [tabs]: 인자 자리마다 제안(빈칸으로 여럿, `[player]`·`[world]`). 비면 첫 명령의 탭 완성을 빌린다. [tab] 이 false 면 제안 없음.
 * - [exact]: 인자가 **정확히 이름만**일 때만 쓴다(인자가 붙으면 원래 명령으로 — 같은 이름의 명령이 있을 때).
 */
data class AliasDef(
    val name: String,
    val commands: List<String>,
    val permission: String = "",
    val description: String = "",
    val tabs: List<String> = emptyList(),
    val tab: Boolean = true,
    val exact: Boolean = false,
    val enabled: Boolean = true,
    val usage: String = "",
) {
    val words: List<String> = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    val root: String get() = words.firstOrNull().orEmpty()

    val single: Boolean get() = words.size == 1 && words[0] != "?"

    /** 그 사람에게 요구하는 권한 노드(없으면 null). */
    fun node(): String? = when (permission.trim().lowercase()) {
        "", "false", "none" -> null
        "true" -> "${CommandsFeature.ALIAS_NODE}.${root.lowercase()}"
        else -> permission.trim()
    }

    /** 이름 낱말 수만큼 앞을 견준다. 맞으면 점수(글자로 맞은 낱말 수 × 2 + 와일드 1), 아니면 -1. */
    fun match(tokens: List<String>): Int {
        if (words.isEmpty() || tokens.size < words.size) return -1
        if (exact && tokens.size != words.size) return -1
        var score = 0
        for ((i, w) in words.withIndex()) {
            score += when {
                w == "?" -> 1
                w.equals(tokens[i], ignoreCase = true) -> 2
                else -> return -1
            }
        }
        return score
    }

    /**
     * 자기 자신을 부르나 — 명령 줄의 앞 낱말들이 이 별칭에 맞으면(CMI 도 "custom alias (x) performing (x) command" 를 거절한다).
     * `delay!` 뒤에 자기를 부르면 깊이 막기로는 안 잡히므로 켤 때 거른다. 원래 명령을 부르려면 접두 이름(`inmc-essentials:워프`)으로.
     */
    fun selfCalls(): Boolean = CommandLines.steps(commands).any { s ->
        s is CommandLines.Step.Line && s.parsed.action == CommandLines.Action.COMMAND &&
            match(s.parsed.body.split(' ').filter { it.isNotEmpty() }) >= 0
    }

    companion object {

        private val ARG = Regex("""\$(\d{1,2})(-?)(?!\d)""")

        /** 이름 규칙 — 점·콜론·슬래시는 안 된다(YAML 경로·네임스페이스). */
        fun nameProblem(name: String): String? = when {
            name.isBlank() -> "빈 이름"
            name.contains('.') || name.contains(':') || name.contains('/') -> "점·콜론·슬래시"
            name.length > 48 -> "너무 김"
            else -> null
        }

        /**
         * 인자 넣기(순수). [args] 는 이름 뒤의 낱말. 필요한 `$n` 이 없으면 null(사용법 안내).
         * [label] 은 `$0`. 줄을 나누지 않는다 — 실행은 [steps] 로(나눈 뒤에 넣는다).
         */
        fun expand(lines: List<String>, label: String, args: List<String>): List<String>? {
            val out = ArrayList<String>(lines.size)
            for (line in lines) out += expandLine(line, label, args) ?: return null
            return out
        }

        /** 한 줄에 인자 넣기. `$n` 이 모자라면 null. 넣는 값은 한 번만 바뀐다(값 속의 `$1` 은 다시 풀리지 않는다). */
        fun expandLine(line: String, label: String, args: List<String>): String? {
            var missing = false
            val text = ARG.replace(line) { m ->
                val n = m.groupValues[1].toInt()
                val rest = m.groupValues[2] == "-"
                when {
                    n == 0 -> label
                    rest -> if (args.size >= n) args.drop(n - 1).joinToString(" ") else ""
                    n <= args.size -> args[n - 1]
                    else -> {
                        missing = true
                        ""
                    }
                }
            }
            if (missing) return null
            return text.replace(Regex("\\s{2,}"), " ").trim()
        }

        /**
         * 별칭 실행 계획(순수) — **템플릿을 먼저 나누고(`;;`) 머리(`asConsole!`·`perm:…!`·`broadcast!` …)를 읽은 뒤** 명령 본문에만 인자를 넣는다.
         * 그래서 사람이 친 인자 속의 `;;`·`asConsole!` 은 그냥 글자다(새 줄·콘솔 실행이 되지 않는다). 겸해서 인자의 `;;` 는 `;` 로,
         * 명령이 아닌 줄(msg!·broadcast!·title! …)에 들어가는 인자의 `<` 는 글자로(MiniMessage 태그·클릭 끼워 넣기 막기).
         * [template] 은 인자를 넣기 **전**의 본문에 한 번(PlaceholderAPI — 친 사람의 `%…%` 는 풀리지 않는다). 인자가 모자라면 null.
         */
        fun steps(commands: List<String>, label: String, args: List<String>, template: (String) -> String = { it }): List<CommandLines.Step>? {
            val safe = args.map { it.replace(";;", ";") }
            val escaped by lazy { safe.map { it.replace("<", "\\<") } }
            val out = ArrayList<CommandLines.Step>()
            for (step in CommandLines.steps(commands)) {
                if (step !is CommandLines.Step.Line) {
                    out += step
                    continue
                }
                val p = step.parsed
                val values = if (p.action == CommandLines.Action.COMMAND) safe else escaped
                val body = expandLine(template(p.body), label, values) ?: return null
                out += CommandLines.Step.Line(p.copy(body = body))
            }
            return out
        }

        fun read(yaml: ConfigurationSection, problem: (String) -> Unit = {}): List<AliasDef> {
            val section = yaml.getConfigurationSection("aliases") ?: return emptyList()
            return section.getKeys(false).mapNotNull { key ->
                val s = section.getConfigurationSection(key) ?: return@mapNotNull null
                nameProblem(key)?.let {
                    problem("별칭 '$key' — $it")
                    return@mapNotNull null
                }
                val commands = s.getStringList("commands")
                if (commands.isEmpty()) {
                    problem("별칭 '$key' — 명령이 없습니다")
                    return@mapNotNull null
                }
                AliasDef(
                    name = key.trim(),
                    commands = commands,
                    permission = s.getString("permission", "")!!,
                    description = s.getString("description", "")!!,
                    tabs = s.getStringList("tab-completions"),
                    tab = s.getBoolean("tab", true),
                    exact = s.getBoolean("exact", false),
                    enabled = s.getBoolean("enabled", true),
                    usage = s.getString("usage", "")!!,
                )
            }
        }

        fun write(defs: List<AliasDef>, header: List<String> = emptyList()): YamlConfiguration {
            val yaml = YamlConfiguration()
            yaml.options().setHeader(header)
            val section = yaml.createSection("aliases")
            for (d in defs) {
                val s = section.createSection(d.name)
                s.set("enabled", d.enabled)
                s.set("commands", d.commands)
                s.set("permission", d.permission)
                s.set("description", d.description)
                s.set("usage", d.usage)
                s.set("tab", d.tab)
                s.set("tab-completions", d.tabs)
                s.set("exact", d.exact)
            }
            return yaml
        }
    }
}
