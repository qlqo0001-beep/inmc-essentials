package com.inmc.essentials.admininfo

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration

/** `settings/admininfo.yml` 의 불변 스냅샷(규칙 12). 값이 이상하면 기본값 — 기본값 = 배포 파일의 값. */
data class AdminSettings(
    // --- 투명 -----------------------------------------------------------------------------
    val vanishDefaults: Map<VanishOption, Boolean> = VanishOption.entries.associateWith { it.cmiDefault },
    val vanishResyncSeconds: Int = 5,
    val vanishStaffNotice: Boolean = true,
    // --- 플레이 시간 --------------------------------------------------------------------------
    val sampleSeconds: Int = 5,
    val flushSeconds: Int = 60,
    val topExclude: Set<String> = emptySet(),
    val topOfflineDays: Int = 0,
    val pageSize: Int = 10,
    val scanStatsOnStart: Boolean = true,
    val keepDays: Int = 400,
    // --- 플레이 시간 보상 ------------------------------------------------------------------------
    val rewardsEnabled: Boolean = true,
    val rewardsExcludeAfk: Boolean = false,
    val rewardsInformMinutes: Int = 15,
    val rewardsOneTimeShown: Int = 2,
    val rewardsRequirePermission: Boolean = false,
    // --- 투표 ---------------------------------------------------------------------------------
    val countVotes: Boolean = true,
    val maxVotesPerIpPerDay: Int = 0,
    val voteCooldownSeconds: Long = 0,
    val voteRewards: Boolean = true,
    val voteMilestones: Boolean = true,
    val voteBroadcast: Boolean = true,
    val voteExclude: Set<String> = emptySet(),
    val voteSiteHours: Int = 24,
    val voteLogDays: Int = 30,
    // --- 목록·최근 접속 ---------------------------------------------------------------------------
    val listGroups: Boolean = true,
    val listHighestFirst: Boolean = true,
    val lastOnlineTimers: List<Long> = listOf(3600L, 86400L, 604800L, 2592000L),
) {

    fun vanishDefault(option: VanishOption): Boolean = vanishDefaults[option] ?: option.cmiDefault

    companion object {

        fun from(y: YamlConfiguration): AdminSettings {
            val d = AdminSettings()
            val defaults = y.getConfigurationSection("vanish.defaults")
            fun int(path: String, def: Int, min: Int, max: Int): Int = y.getInt(path, def).let { if (it in min..max) it else def }
            fun long(path: String, def: Long, min: Long, max: Long): Long = y.getLong(path, def).let { if (it in min..max) it else def }
            fun names(path: String): Set<String> = y.getStringList(path).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            return AdminSettings(
                vanishDefaults = VanishOption.entries.associateWith { opt -> bool(defaults, opt.key, opt.cmiDefault) },
                vanishResyncSeconds = int("vanish.resync-seconds", d.vanishResyncSeconds, 1, 300),
                vanishStaffNotice = y.getBoolean("vanish.staff-notice", d.vanishStaffNotice),
                sampleSeconds = int("playtime.sample-seconds", d.sampleSeconds, 1, 60),
                flushSeconds = int("playtime.flush-seconds", d.flushSeconds, 10, 3600),
                topExclude = names("playtime.top-exclude"),
                topOfflineDays = int("playtime.top-offline-days", d.topOfflineDays, 0, 100_000),
                pageSize = int("playtime.page-size", d.pageSize, 1, 50),
                scanStatsOnStart = y.getBoolean("playtime.scan-stats-on-start", d.scanStatsOnStart),
                keepDays = int("playtime.keep-days", d.keepDays, 0, 100_000),
                rewardsEnabled = y.getBoolean("playtime-rewards.enabled", d.rewardsEnabled),
                rewardsExcludeAfk = y.getBoolean("playtime-rewards.exclude-afk", d.rewardsExcludeAfk),
                rewardsInformMinutes = int("playtime-rewards.inform-minutes", d.rewardsInformMinutes, 0, 100_000),
                rewardsOneTimeShown = int("playtime-rewards.one-time-shown", d.rewardsOneTimeShown, 0, 100),
                rewardsRequirePermission = y.getBoolean("playtime-rewards.require-permission", d.rewardsRequirePermission),
                countVotes = y.getBoolean("votes.count", d.countVotes),
                maxVotesPerIpPerDay = int("votes.max-per-ip-per-day", d.maxVotesPerIpPerDay, 0, 100_000),
                voteCooldownSeconds = long("votes.cooldown-seconds", d.voteCooldownSeconds, 0, 100_000_000),
                voteRewards = y.getBoolean("votes.rewards", d.voteRewards),
                voteMilestones = y.getBoolean("votes.milestones", d.voteMilestones),
                voteBroadcast = y.getBoolean("votes.broadcast", d.voteBroadcast),
                voteExclude = names("votes.top-exclude"),
                voteSiteHours = int("votes.site-hours", d.voteSiteHours, 0, 10_000),
                voteLogDays = int("votes.log-days", d.voteLogDays, 1, 10_000),
                listGroups = y.getBoolean("list.group-by-luckperms", d.listGroups),
                listHighestFirst = y.getBoolean("list.highest-weight-first", d.listHighestFirst),
                lastOnlineTimers = y.getLongList("lastonline.timers").filter { it > 0 }.take(8).ifEmpty { d.lastOnlineTimers },
            )
        }

        private fun bool(section: ConfigurationSection?, key: String, def: Boolean): Boolean =
            if (section != null && section.isBoolean(key)) section.getBoolean(key) else def
    }
}
