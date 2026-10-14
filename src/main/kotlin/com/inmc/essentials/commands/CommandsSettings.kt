package com.inmc.essentials.commands

import org.bukkit.configuration.ConfigurationSection

/** 규칙의 인자 조건 — CMI 의 `cmd :10`(인자 없을 때만)·`cmd_`(인자 있을 때만)을 말로. */
enum class ArgMode {
    ANY, NONE, SOME;

    fun accepts(hasArgs: Boolean): Boolean = when (this) {
        ANY -> true
        NONE -> !hasArgs
        SOME -> hasArgs
    }

    companion object {
        fun parse(raw: String?): ArgMode = when (raw?.trim()?.lowercase()) {
            "none", "없음", "no" -> NONE
            "some", "있음", "yes" -> SOME
            else -> ANY
        }
    }
}

/**
 * 명령 하나(별칭 포함)에 거는 대기·쿨타임·비용. [id] 는 `settings/commands.yml rules.<id>` — 쿨타임 기록의 열쇠다(DB `rule`).
 *
 * - [cooldown] 초. **-1 = 평생 한 번**(CMI 와 같다), 0 = 없음. [groupCooldowns] 는 권한 노드 → 초(가진 것 중 가장 짧은 것).
 * - [warmup] 초. [move] = 대기 중 움직여도 되는 거리(블록) — -1 이면 어디로든, 0 이면 같은 블록 안.
 * - [cost] 는 [currency](빈 값 = 기본 화폐)로. [commitOnSuccess] 면 쿨타임 기록과 돈 빼기를 **명령이 성공했다고 알릴 때**(`ess.gate.commit`)
 *   한다 — 이동 명령이 이동에 실패하면 쿨타임도 돈도 없다. 아니면 실행 직전에(대기가 있으면 대기 시작 때 빼고 취소되면 돌려준다).
 */
data class CommandRule(
    val id: String,
    val commands: List<String>,
    val args: ArgMode = ArgMode.ANY,
    val cooldown: Long = 0,
    val groupCooldowns: Map<String, Long> = emptyMap(),
    val warmup: Long = 0,
    val groupWarmups: Map<String, Long> = emptyMap(),
    val move: Double = 0.0,
    val cancelOnDamage: Boolean = true,
    val cost: Double = 0.0,
    val currency: String = "",
    val costNotify: Boolean = true,
    val costConfirm: Boolean = false,
    val commitOnSuccess: Boolean = false,
) {
    val once: Boolean get() = cooldown < 0

    /** 쿨타임을 적어야 하나 — 기본이 0 이어도 그룹 쿨타임이 있으면 적는다. */
    val tracksCooldown: Boolean get() = cooldown != 0L || groupCooldowns.isNotEmpty()

    /** 그 사람의 쿨타임(초) — 맞는 권한 노드가 있으면 그중 가장 짧은 것(기본값을 덮는다), 없으면 기본. 평생 한 번(-1)은 덮지 않는다. */
    fun cooldownFor(has: (String) -> Boolean): Long =
        if (cooldown < 0) cooldown else groupCooldowns.filterKeys(has).values.minOrNull() ?: cooldown

    fun warmupFor(has: (String) -> Boolean): Long = groupWarmups.filterKeys(has).values.minOrNull() ?: warmup
}

/** 대기 중 보여 줄 곳. */
enum class WarmupDisplay { TITLE, ACTIONBAR, BOSSBAR, CHAT }

/** 이벤트 명령 하나(CMI `EventCommands.yml` 의 한 사건). */
data class EventRule(
    val enabled: Boolean,
    val commands: List<String>,
    val sourceCommands: List<String> = emptyList(),
    val cancel: Boolean = false,
)

/**
 * `settings/commands.yml` 의 불변 스냅샷(규칙 12 — 리로드는 통째 교체). 값이 이상하면 기본값으로(던지지 않는다).
 * 이벤트 명령·일정·별칭은 여기가 아니라 데이터 파일(`commands/events.yml` · `schedules.yml` · `aliases.yml`) — 편집기와 가져오기가 쓴다.
 */
data class CommandsSettings(
    val keyboard: Boolean = true,
    val keyboardSubcommands: Boolean = true,
    val keyboardTab: Boolean = true,
    val unknownHint: Boolean = true,
    val similarThreshold: Int = 75,
    val permissionHint: Boolean = true,
    val permissionShowNode: Boolean = false,
    val spam: Boolean = false,
    val spamSimilarity: Int = 80,
    val spamInterval: Long = 5,
    val spamMaxRepeats: Int = 2,
    val spamWhitelist: Set<String> = setOf("msg", "tell", "w", "귓", "r", "답장", "login", "register", "l"),
    val warmupDisplay: Set<WarmupDisplay> = setOf(WarmupDisplay.ACTIONBAR, WarmupDisplay.TITLE),
    val warmupBossbarColor: String = "blue",
    val warmupSound: String = "block.note_block.hat",
    val informOnNoMove: Boolean = true,
    val confirmSeconds: Long = 15,
    val combatSeconds: Long = 15,
    val rules: List<CommandRule> = emptyList(),
) {

    /** 이름(소문자, 앞의 `/`·`ns:` 없이) → 그 이름을 적은 규칙들. */
    val byCommand: Map<String, List<CommandRule>> by lazy {
        val out = HashMap<String, MutableList<CommandRule>>()
        for (rule in rules) for (name in rule.commands) out.getOrPut(name) { mutableListOf() } += rule
        out
    }

    companion object {

        fun from(yaml: ConfigurationSection): CommandsSettings {
            val d = CommandsSettings()
            val rules = yaml.getConfigurationSection("rules")?.let { section ->
                section.getKeys(false).mapNotNull { id -> section.getConfigurationSection(id)?.let { rule(id, it) } }
            }.orEmpty()
            return CommandsSettings(
                keyboard = yaml.getBoolean("keyboard.enabled", d.keyboard),
                keyboardSubcommands = yaml.getBoolean("keyboard.subcommands", d.keyboardSubcommands),
                keyboardTab = yaml.getBoolean("keyboard.tab-complete", d.keyboardTab),
                unknownHint = yaml.getBoolean("keyboard.unknown-hint", d.unknownHint),
                similarThreshold = yaml.getInt("similar.threshold", d.similarThreshold).coerceIn(0, 100),
                permissionHint = yaml.getBoolean("permission-hint.enabled", d.permissionHint),
                permissionShowNode = yaml.getBoolean("permission-hint.show-node", d.permissionShowNode),
                spam = yaml.getBoolean("spam.enabled", d.spam),
                spamSimilarity = yaml.getInt("spam.similarity", d.spamSimilarity).coerceIn(1, 100),
                spamInterval = yaml.getLong("spam.interval", d.spamInterval).coerceAtLeast(1),
                spamMaxRepeats = yaml.getInt("spam.max-repeats", d.spamMaxRepeats).coerceAtLeast(1),
                spamWhitelist = if (yaml.isList("spam.whitelist")) yaml.getStringList("spam.whitelist").map(::normalize).toSet() else d.spamWhitelist,
                warmupDisplay = if (yaml.isList("warmup.display")) {
                    yaml.getStringList("warmup.display").mapNotNull { raw -> WarmupDisplay.entries.firstOrNull { it.name.equals(raw.trim(), true) } }.toSet()
                } else {
                    d.warmupDisplay
                },
                warmupBossbarColor = yaml.getString("warmup.bossbar-color", d.warmupBossbarColor)!!.trim().lowercase(),
                warmupSound = yaml.getString("warmup.sound", d.warmupSound)!!.trim(),
                informOnNoMove = yaml.getBoolean("warmup.inform-on-no-move", d.informOnNoMove),
                confirmSeconds = yaml.getLong("cost.confirm-seconds", d.confirmSeconds).coerceIn(3, 300),
                combatSeconds = yaml.getLong("combat-seconds", d.combatSeconds).coerceAtLeast(1),
                rules = rules,
            )
        }

        private fun rule(id: String, s: ConfigurationSection): CommandRule? {
            if (!s.getBoolean("enabled", true)) return null
            val commands = (if (s.isList("commands")) s.getStringList("commands") else listOfNotNull(s.getString("commands")))
                .map(::normalize).filter { it.isNotEmpty() }.distinct()
            if (commands.isEmpty()) return null
            return CommandRule(
                id = id,
                commands = commands,
                args = ArgMode.parse(s.getString("args")),
                cooldown = s.getLong("cooldown", 0).coerceAtLeast(-1),
                groupCooldowns = groups(s.getConfigurationSection("group-cooldowns")),
                warmup = s.getLong("warmup", 0).coerceAtLeast(0),
                groupWarmups = groups(s.getConfigurationSection("group-warmups")),
                move = parseMove(s.getString("move")),
                cancelOnDamage = s.getBoolean("cancel-on-damage", true),
                cost = s.getDouble("cost", 0.0).takeIf { it.isFinite() && it > 0 } ?: 0.0,
                currency = s.getString("currency", "")!!.trim(),
                costNotify = s.getBoolean("cost-notify", true),
                costConfirm = s.getBoolean("cost-confirm", false),
                commitOnSuccess = s.getBoolean("commit-on-success", false),
            )
        }

        /** `any`·`true` = 어디로든(-1), `block`·`false` = 같은 블록(0), 숫자 = 그 거리. */
        fun parseMove(raw: String?): Double {
            val text = raw?.trim()?.lowercase() ?: return 0.0
            return when (text) {
                "any", "true", "자유" -> -1.0
                "", "block", "false", "블록" -> 0.0
                else -> text.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: 0.0
            }
        }

        /** 그룹 표 — 키는 권한 노드. YAML 키의 점은 경로가 되므로(지뢰 1) 잎을 깊게 읽는다. */
        private fun groups(section: ConfigurationSection?): Map<String, Long> {
            section ?: return emptyMap()
            val out = LinkedHashMap<String, Long>()
            for (key in section.getKeys(true)) {
                if (section.isConfigurationSection(key)) continue
                val value = section.getLong(key, -2)
                if (value >= 0) out[key] = value
            }
            return out
        }

        /** 명령 이름 정규화 — 앞의 `/`, `ns:` 를 떼고 소문자. */
        fun normalize(raw: String): String = raw.trim().removePrefix("/").substringBefore(' ').substringAfterLast(':').lowercase()

        /** 이벤트 명령 파일(`commands/events.yml`) — 사건 이름 → 규칙. 모르는 사건 이름은 [unknown] 으로 알린다. */
        fun events(yaml: ConfigurationSection, unknown: (String) -> Unit = {}): Map<String, EventRule> {
            val out = HashMap<String, EventRule>()
            for (key in yaml.getKeys(false)) {
                val s = yaml.getConfigurationSection(key) ?: continue
                val id = EventCommands.ALL.firstOrNull { it.equals(key, ignoreCase = true) }
                if (id == null) {
                    unknown(key)
                    continue
                }
                out[id] = EventRule(
                    enabled = s.getBoolean("Enabled", s.getBoolean("enabled", false)),
                    commands = s.getStringList(if (s.isList("Commands")) "Commands" else "commands"),
                    sourceCommands = s.getStringList(if (s.isList("SourceCommands")) "SourceCommands" else "source-commands"),
                    cancel = s.getBoolean("CancelEvent", s.getBoolean("cancel-event", false)),
                )
            }
            return out
        }
    }
}
