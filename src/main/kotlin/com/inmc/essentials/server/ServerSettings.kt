package com.inmc.essentials.server

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration

/** `settings/server.yml` 의 불변 스냅샷(규칙 12). 값이 이상하면 기본값. */
data class ServerSettings(
    // --- 서버 목록(MOTD) ---
    val motdEnabled: Boolean = true,
    val motdLines: List<String> = emptyList(),
    val motdHover: List<String> = emptyList(),
    /** 서버 목록에 보일 최대 인원(0 = 실제 값). */
    val motdMaxPlayers: Int = 0,
    /** 접속자 수에서 투명인 사람을 뺀다(CLAUDE.md 8 — 보는 사람을 모르면 숨김). */
    val motdHideVanished: Boolean = true,
    // --- 점검 ---
    val maintenanceMessage: String = "<red>서버 점검 중입니다.</red>\n<gray>잠시 뒤에 다시 들어와 주세요.</gray>",
    val maintenanceMotd: List<String> = listOf("<red>■ 서버 점검 중</red>", "<gray>잠시 뒤에 다시 와 주세요</gray>"),
    val maintenanceVersionText: String = "점검 중",
    val maintenanceAutoKick: Boolean = true,
    val maintenanceBossBar: Boolean = true,
    val maintenanceBossBarColor: String = "red",
    /** 우회 권한이 아니어도 들어올 수 있는 이름·uuid(LuckPerms 가 없는 서버를 위해). */
    val maintenanceAllow: Set<String> = emptySet(),
    // --- 가득 참 ---
    val fullBypass: Boolean = true,
    // --- 상태 ---
    val statusBusiestChunks: Int = 3,
    val tpsSpikeBelow: Double = 18.0,
    // --- 텍스트 · 책 ---
    val joinText: String = "",
    val textLinesPerPage: Int = 10,
    val bookAuthor: String = "Server",
    val bookAddDate: Boolean = false,
    val bookMaxPages: Int = 50,
    // --- 로그 ---
    val logCommands: Boolean = false,
    val logChat: Boolean = false,
    val logKeepDays: Int = 30,
    val logHiddenCommands: Set<String> = setOf("login", "l", "register", "reg", "changepassword", "로그인"),
) {
    companion object {

        fun from(y: ConfigurationSection): ServerSettings {
            val d = ServerSettings()
            fun lines(path: String, def: List<String>): List<String> = when {
                y.isList(path) -> y.getStringList(path)
                y.isString(path) -> y.getString(path)!!.split("\n")
                else -> def
            }
            return ServerSettings(
                motdEnabled = y.getBoolean("motd.enabled", d.motdEnabled),
                motdLines = lines("motd.lines", d.motdLines).take(2),
                motdHover = lines("motd.hover", d.motdHover),
                motdMaxPlayers = y.getInt("motd.max-players", d.motdMaxPlayers).coerceAtLeast(0),
                motdHideVanished = y.getBoolean("motd.hide-vanished", d.motdHideVanished),
                maintenanceMessage = lines("maintenance.kick-message", listOf(d.maintenanceMessage)).joinToString("\n"),
                maintenanceMotd = lines("maintenance.motd", d.maintenanceMotd).take(2),
                maintenanceVersionText = y.getString("maintenance.version-text", d.maintenanceVersionText)!!,
                maintenanceAutoKick = y.getBoolean("maintenance.auto-kick", d.maintenanceAutoKick),
                maintenanceBossBar = y.getBoolean("maintenance.bossbar", d.maintenanceBossBar),
                maintenanceBossBarColor = y.getString("maintenance.bossbar-color", d.maintenanceBossBarColor)!!.trim().lowercase(),
                maintenanceAllow = y.getStringList("maintenance.allow").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet(),
                fullBypass = y.getBoolean("full-server.bypass-enabled", d.fullBypass),
                statusBusiestChunks = y.getInt("status.busiest-chunks", d.statusBusiestChunks).coerceIn(0, 10),
                tpsSpikeBelow = y.getDouble("status.spike-below", d.tpsSpikeBelow).coerceIn(1.0, 20.0),
                joinText = y.getString("texts.join-text", d.joinText)!!.trim(),
                textLinesPerPage = y.getInt("texts.lines-per-page", d.textLinesPerPage).coerceIn(3, 50),
                bookAuthor = y.getString("books.default-author", d.bookAuthor)!!,
                bookAddDate = y.getBoolean("books.add-date", d.bookAddDate),
                bookMaxPages = y.getInt("books.max-pages", d.bookMaxPages).coerceIn(1, 100),
                logCommands = y.getBoolean("logs.commands", d.logCommands),
                logChat = y.getBoolean("logs.chat", d.logChat),
                logKeepDays = y.getInt("logs.keep-days", d.logKeepDays).coerceAtLeast(0),
                logHiddenCommands = if (y.isList("logs.hidden-commands")) y.getStringList("logs.hidden-commands").map { it.trim().lowercase().removePrefix("/") }.toSet() else d.logHiddenCommands,
            )
        }
    }
}

/**
 * 명령으로 바꾸고 재시작 뒤에도 남는 것(`plugins/inmc-essentials/server/state.yml`) — 점검 켬·문구, `/서버소개` 로 바꾼 서버 목록 글, `/최대인원`.
 * 불변 — 바꿀 때는 copy 를 [ServerFeature.state] 에 넣고 파일은 비동기로 쓴다.
 */
data class ServerState(
    val maintenance: Boolean = false,
    val maintenanceReason: String = "",
    /** `/서버소개` 로 바꾼 줄(비면 설정의 motd.lines). */
    val motd: List<String> = emptyList(),
    /** `/최대인원` 으로 바꾼 값(null = server.properties 그대로). */
    val maxPlayers: Int? = null,
) {
    fun toYaml(): String = YamlConfiguration().apply {
        options().setHeader(listOf("inmc-essentials 서버 상태 — 명령(/점검 · /서버소개 · /최대인원)이 씁니다. 손으로 고치면 /에센셜 리로드."))
        set("maintenance.enabled", maintenance)
        set("maintenance.reason", maintenanceReason)
        set("motd", motd)
        set("max-players", maxPlayers ?: 0)
    }.saveToString()

    companion object {
        fun from(y: ConfigurationSection): ServerState = ServerState(
            maintenance = y.getBoolean("maintenance.enabled", false),
            maintenanceReason = y.getString("maintenance.reason", "")!!,
            motd = y.getStringList("motd").take(2),
            maxPlayers = y.getInt("max-players", 0).takeIf { it > 0 },
        )
    }
}
