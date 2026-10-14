package com.inmc.essentials.moderation

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration

/** 경고 분류 하나(CMI `Warnings.Categories`). [name] 이 저장되는 이름, [aliases] 는 명령에서 같이 받는 말(영어 등). */
data class WarnCategory(val name: String, val aliases: List<String>, val points: Int, val lifeSeconds: Long, val reason: String) {
    fun matches(text: String): Boolean = name.equals(text, true) || aliases.any { it.equals(text, true) }
}

/**
 * `settings/moderation.yml` 의 불변 스냅샷. 값이 이상하면 기본값(던지지 않는다). 시간은 초.
 * 명령 목록의 `{player}`·`[playerName]` 은 대상 이름, `{reason}`·`{time}` 은 사유·시간으로 바뀐다(콘솔이 실행).
 */
data class ModSettings(
    // --- 밴 ---------------------------------------------------------------------------------------
    val banOverrideLogin: Boolean = true,
    val banDefaultReason: String = "서버 규칙 위반",
    val banBroadcast: Boolean = true,
    val tempbanMaxWithout: Long = 60,
    val onBan: List<String> = emptyList(),
    val onUnban: List<String> = emptyList(),
    // --- 추방 -------------------------------------------------------------------------------------
    val kickDefaultReason: String = "관리자에 의해 추방되었습니다",
    val kickBroadcast: Boolean = true,
    val onKick: List<String> = emptyList(),
    // --- 뮤트 -------------------------------------------------------------------------------------
    val muteDefaultReason: String = "채팅 규칙 위반",
    val muteMaxWithout: Long = -1,
    val muteBlockedCommands: List<String> = listOf("me", "say", "teammsg", "tm"),
    val onMute: List<String> = emptyList(),
    // --- 감옥 -------------------------------------------------------------------------------------
    val jailDefaultSeconds: Long = 300,
    val jailMaxWithout: Long = -1,
    val jailDefaultReason: String = "규칙 위반",
    val jailChatRange: Int = 20,
    val jailCountOffline: Boolean = false,
    val jailNoAfk: Boolean = false,
    val jailPreventDamage: Boolean = true,
    val jailPreventHunger: Boolean = true,
    val jailWhitelist: List<String> = listOf("귓", "msg", "tell", "w", "m", "whisper", "귓말", "귓속말", "답장", "r", "reply"),
    val jailBossbar: Boolean = true,
    val jailTitle: Boolean = true,
    val jailRadius: Int = 5,
    val onJail: List<String> = emptyList(),
    val onUnjail: List<String> = emptyList(),
    // --- 수갑 -------------------------------------------------------------------------------------
    val cuffMute: Boolean = true,
    val cuffAllowed: List<String> = listOf("귓", "msg", "tell", "w", "m", "답장", "r", "reply"),
    // --- 경고 -------------------------------------------------------------------------------------
    val warnDefault: WarnCategory = WarnCategory("기본", listOf("default"), 1, 86_400, "서버 규칙 위반"),
    val warnCategories: List<WarnCategory> = listOf(
        WarnCategory("욕설", listOf("swear"), 3, 86_400, "욕설"),
        WarnCategory("테러", listOf("grief"), 10, 86_400, "테러(그리핑)"),
        WarnCategory("버그", listOf("bug"), 30, 86_400, "버그 악용"),
        WarnCategory("핵", listOf("cheat"), 50, 86_400, "불법 프로그램"),
    ),
    val warnPerform: Map<Int, List<String>> = mapOf(
        3 to listOf("뮤트 {player} 10m 경고 점수 3점 누적"),
        5 to listOf("추방 {player} 경고 점수 5점 누적"),
        10 to listOf("기간밴 {player} 5m 경고 점수 10점 누적"),
    ),
    val warnBroadcast: Boolean = true,
    // --- 경보·메모 ----------------------------------------------------------------------------------
    val alertMinutes: Long = 1440,
    val notesExpireDays: Int = 30,
    val notesOnAlert: Boolean = true,
    // --- IP ---------------------------------------------------------------------------------------
    val ipRecord: Boolean = true,
    val ipRecordDelaySeconds: Int = 0,
    val accountExcluded: List<String> = emptyList(),
    // --- 데이터 도구 --------------------------------------------------------------------------------
    /** 남이 그 사람을 가리키는 열(무시 목록의 대상 · 요청 차단의 대상) — 이름이 `uuid`·`owner` 꼴이 아니라 따로 적는다. 우편의 `sender_id` 는 넣지 않는다(지우면 남이 받은 우편까지 지운다). */
    val dataExtraColumns: List<String> = listOf("ess_chat_ignores.target", "ess_teleport_blocks.blocked"),
    val dataExcludeTables: List<String> = emptyList(),
    val purgeDays: Int = 90,
    val purgeDeleteFiles: Boolean = false,
    val purgeFolders: List<String> = listOf("playerdata", "stats", "advancements"),
) {

    /** 이름·별칭으로 분류(기본 분류 포함). */
    fun category(text: String): WarnCategory? = (listOf(warnDefault) + warnCategories).firstOrNull { it.matches(text) }

    companion object {

        fun from(yaml: YamlConfiguration): ModSettings {
            val d = ModSettings()
            fun list(path: String, def: List<String>): List<String> =
                if (yaml.isList(path)) yaml.getStringList(path).map { it.trim() }.filter { it.isNotEmpty() } else def
            fun long(path: String, def: Long, min: Long): Long = if (yaml.contains(path)) yaml.getLong(path, def).coerceAtLeast(min) else def
            fun text(path: String, def: String): String = yaml.getString(path)?.takeIf { it.isNotBlank() } ?: def

            fun category(name: String, section: ConfigurationSection?, def: WarnCategory?): WarnCategory = WarnCategory(
                name = name,
                aliases = if (section?.isList("aliases") == true) section.getStringList("aliases").map { it.trim() }.filter { it.isNotEmpty() } else def?.aliases.orEmpty(),
                points = (section?.getInt("points", def?.points ?: 1) ?: def?.points ?: 1).coerceAtLeast(0),
                lifeSeconds = (section?.getLong("life-time", def?.lifeSeconds ?: 86_400) ?: def?.lifeSeconds ?: 86_400).coerceAtLeast(0),
                reason = section?.getString("reason")?.takeIf { it.isNotBlank() } ?: def?.reason ?: d.warnDefault.reason,
            )

            val categories = yaml.getConfigurationSection("warnings.categories")?.let { sec ->
                sec.getKeys(false).filter { ModRules.validName(it) }.map { key -> category(key, sec.getConfigurationSection(key), null) }
            } ?: d.warnCategories
            val perform = yaml.getConfigurationSection("warnings.perform")?.let { sec ->
                sec.getKeys(false).mapNotNull { key ->
                    val points = key.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                    points to sec.getStringList(key).map { it.trim().removePrefix("/") }.filter { it.isNotEmpty() }
                }.toMap()
            } ?: d.warnPerform

            return ModSettings(
                banOverrideLogin = yaml.getBoolean("ban.override-login-message", d.banOverrideLogin),
                banDefaultReason = text("ban.default-reason", d.banDefaultReason),
                banBroadcast = yaml.getBoolean("ban.broadcast", d.banBroadcast),
                tempbanMaxWithout = if (yaml.contains("ban.tempban-max-without-permission")) yaml.getLong("ban.tempban-max-without-permission").coerceAtLeast(-1) else d.tempbanMaxWithout,
                onBan = list("ban.commands.on-ban", d.onBan),
                onUnban = list("ban.commands.on-unban", d.onUnban),
                kickDefaultReason = text("kick.default-reason", d.kickDefaultReason),
                kickBroadcast = yaml.getBoolean("kick.broadcast", d.kickBroadcast),
                onKick = list("kick.commands", d.onKick),
                muteDefaultReason = text("mute.default-reason", d.muteDefaultReason),
                muteMaxWithout = if (yaml.contains("mute.max-without-permission")) yaml.getLong("mute.max-without-permission").coerceAtLeast(-1) else d.muteMaxWithout,
                muteBlockedCommands = list("mute.blocked-commands", d.muteBlockedCommands),
                onMute = list("mute.commands", d.onMute),
                jailDefaultSeconds = long("jail.default-time", d.jailDefaultSeconds, 1),
                jailMaxWithout = if (yaml.contains("jail.max-without-permission")) yaml.getLong("jail.max-without-permission").coerceAtLeast(-1) else d.jailMaxWithout,
                jailDefaultReason = text("jail.default-reason", d.jailDefaultReason),
                jailChatRange = yaml.getInt("jail.chat-range", d.jailChatRange).coerceAtLeast(-1),
                jailCountOffline = yaml.getBoolean("jail.count-while-offline", d.jailCountOffline),
                jailNoAfk = yaml.getBoolean("jail.no-afk", d.jailNoAfk),
                jailPreventDamage = yaml.getBoolean("jail.prevent-damage", d.jailPreventDamage),
                jailPreventHunger = yaml.getBoolean("jail.prevent-hunger", d.jailPreventHunger),
                jailWhitelist = list("jail.whitelisted-commands", d.jailWhitelist),
                jailBossbar = yaml.getBoolean("jail.bossbar", d.jailBossbar),
                jailTitle = yaml.getBoolean("jail.title", d.jailTitle),
                jailRadius = yaml.getInt("jail.stand-radius", d.jailRadius).coerceIn(1, 64),
                onJail = list("jail.commands.on-jail", d.onJail),
                onUnjail = list("jail.commands.on-unjail", d.onUnjail),
                cuffMute = yaml.getBoolean("cuff.mute", d.cuffMute),
                cuffAllowed = list("cuff.allowed-commands", d.cuffAllowed),
                warnDefault = category(d.warnDefault.name, yaml.getConfigurationSection("warnings.default"), d.warnDefault),
                warnCategories = categories,
                warnPerform = perform,
                warnBroadcast = yaml.getBoolean("warnings.broadcast", d.warnBroadcast),
                alertMinutes = long("alert.timer-minutes", d.alertMinutes, 1),
                notesExpireDays = yaml.getInt("notes.expire-days", d.notesExpireDays).coerceAtLeast(0),
                notesOnAlert = yaml.getBoolean("notes.show-on-alert", d.notesOnAlert),
                ipRecord = yaml.getBoolean("ip.record", d.ipRecord),
                ipRecordDelaySeconds = yaml.getInt("ip.record-delay-seconds", d.ipRecordDelaySeconds).coerceIn(0, 600),
                accountExcluded = list("ip.checkaccount-excluded", d.accountExcluded),
                dataExtraColumns = list("data-tools.extra-columns", d.dataExtraColumns),
                dataExcludeTables = list("data-tools.exclude-tables", d.dataExcludeTables).map { it.lowercase() },
                purgeDays = yaml.getInt("purge.offline-days", d.purgeDays).coerceAtLeast(1),
                purgeDeleteFiles = yaml.getBoolean("purge.delete-files", d.purgeDeleteFiles),
                purgeFolders = list("purge.folders", d.purgeFolders),
            )
        }
    }
}
