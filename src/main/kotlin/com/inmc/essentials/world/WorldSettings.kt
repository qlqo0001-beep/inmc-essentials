package com.inmc.essentials.world

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration

/**
 * `settings/world.yml` 의 불변 스냅샷(리로드 때 통째로 바뀐다). 값이 이상하면 기본값(던지지 않는다).
 * 기본값은 CMI 설정의 기본값을 옮긴 것이다(괄호 안은 CMI 키). **서버 없이 읽힌다** — `WorldSettingsTest`.
 *
 * 월드 이름은 전부 소문자로 비교한다(월드 폴더 이름은 대소문자를 가리지만 같은 이름이 둘 있는 서버는 없다 — 설정을 손으로 칠 때 틀리기 쉽다).
 */
data class WorldSettings(
    val limits: Limits = Limits(),
    val sleep: Sleep = Sleep(),
    val portal: Portal = Portal(),
    val vehicles: Vehicles = Vehicles(),
    val time: Time = Time(),
    val weatherDefaultSeconds: Long = 0,
    val findBiome: FindBiome = FindBiome(),
    val fixChunk: FixChunk = FixChunk(),
    val lightFix: LightFix = LightFix(),
    val killAll: KillAll = KillAll(),
) {

    /** 월드별 제한(WorldLimits). 목록에 없는 월드는 아무것도 하지 않는다. */
    data class Limits(
        val enabled: Boolean = false,
        val checkEverySeconds: Int = 5,
        /** 월드 → 게임모드 이름(survival·creative·adventure·spectator). */
        val gamemode: Map<String, String> = emptyMap(),
        val noFly: Set<String> = emptySet(),
        val noGod: Set<String> = emptySet(),
        val noElytra: Set<String> = emptySet(),
        val elytraUnequip: Boolean = true,
        /** false 면 [roof] 높이 위로는 권한이 있는 사람만 난다(FlyAboveRoof). */
        val flyAboveRoof: Boolean = true,
        val roof: Map<String, Int> = mapOf("world" to 320, "world_nether" to 128, "world_the_end" to 256),
        /** 월드 → 막을 스폰 사유(대문자 enum 이름). */
        val spawnReasons: Map<String, Set<String>> = emptyMap(),
    ) {
        fun gamemodeOf(world: String): String? = gamemode[world.lowercase()]
        fun flyBlocked(world: String): Boolean = world.lowercase() in noFly
        fun godBlocked(world: String): Boolean = world.lowercase() in noGod
        fun elytraBlocked(world: String): Boolean = world.lowercase() in noElytra
        fun roofOf(world: String): Int? = if (flyAboveRoof) null else roof[world.lowercase()]
        fun spawnBlocked(world: String, reason: String): Boolean = spawnReasons[world.lowercase()]?.contains(reason.uppercase()) == true
    }

    /** 잠자기 밤 가속(Sleeping.Speedup). */
    data class Sleep(
        val enabled: Boolean = true,
        /** 비우면 전부(정상 월드만 — 네더·엔드에는 잘 수 없다). */
        val worlds: Set<String> = emptySet(),
        val onlyNight: Boolean = true,
        val nightStartsAt: Long = 12542,
        val info: Info = Info.TITLE,
        val excludeAfk: Boolean = true,
        val baseSpeed: Int = 100,
        val minSpeed: Int = 5,
        /** 이만큼 자야 빨라진다 — 사람 수 또는 비율. */
        val minSleeping: Threshold = Threshold(50, percent = true),
        /** 이만큼 자면 바로 아침(0 = 끔). 바닐라 playersSleepingPercentage 와 별개 — 둘 중 먼저 닿는 쪽. */
        val skipAt: Threshold = Threshold(0, percent = true),
        val clearWeather: Boolean = true,
        val inform: Boolean = true,
        val informDelaySeconds: Int = 30,
    ) {
        enum class Info { NONE, TITLE, ACTIONBAR, BOSSBAR }

        fun applies(world: String): Boolean = enabled && (worlds.isEmpty() || world.lowercase() in worlds)
    }

    /** 사람 수(`3`) 또는 비율(`50%`). */
    data class Threshold(val value: Int, val percent: Boolean) {
        /** [eligible] 명 중 몇 명이 자야 하나. 0 이면 끔(늘 0 을 돌려준다). 비율은 올림 — 3명의 50% 는 2명. */
        fun required(eligible: Int): Int = when {
            value <= 0 -> 0
            percent -> ((eligible * value + 99) / 100).coerceAtLeast(1)
            else -> value
        }

        override fun toString(): String = if (percent) "$value%" else "$value"

        companion object {
            fun parse(raw: String?, fallback: Threshold): Threshold {
                val text = raw?.trim()?.replace(" ", "") ?: return fallback
                if (text.endsWith("%")) return text.dropLast(1).toIntOrNull()?.takeIf { it in 0..100 }?.let { Threshold(it, true) } ?: fallback
                return text.toIntOrNull()?.takeIf { it >= 0 }?.let { Threshold(it, false) } ?: fallback
            }
        }
    }

    /** 네더 포탈 만들기(NetherPortal). 월드별로 덮어쓸 수 있다. */
    data class Portal(
        val base: PortalRule = PortalRule(),
        val perWorld: Map<String, PortalRule> = emptyMap(),
        val showSize: Boolean = true,
    ) {
        fun of(world: String): PortalRule = perWorld[world.lowercase()] ?: base
    }

    data class PortalRule(val preventCreation: Boolean = false, val maxWidth: Int = 23, val maxHeight: Int = 23)

    /** 몹이 탈것에 타는 것 막기(Optimizations.PreventEntityBoatEnter — 수레는 우리가 더함). */
    data class Vehicles(val boats: Riders = Riders(), val minecarts: Riders = Riders())

    data class Riders(val monsters: Boolean = false, val animals: Boolean = false, val villagers: Boolean = false) {
        val any: Boolean get() = monsters || animals || villagers
    }

    /** 시간(Time). 프리셋은 틱. */
    data class Time(
        val presets: Map<String, Long> = mapOf("day" to 6000, "night" to 18000, "morning" to 0, "dusk" to 12000),
        val smoothSpeed: Int = 100,
        val autoRealWorlds: Set<String> = emptySet(),
        val autoRealIntervalSeconds: Int = 60,
        val autoRealSmooth: Boolean = true,
        val timezone: String = "",
    )

    data class FindBiome(
        val radius: Int = 6400,
        val maxRadius: Int = 20000,
        val step: Int = 32,
        val maxConcurrent: Int = 2,
        val progressEverySeconds: Int = 3,
        val maxSeconds: Int = 120,
        val deepDarkWarning: Boolean = true,
    )

    data class FixChunk(val speed: Int = 200, val autoSpeed: Boolean = true, val messages: Boolean = true, val progressEverySeconds: Int = 10)

    data class LightFix(val batch: Int = 16, val progressEverySeconds: Int = 3)

    data class KillAll(val radius: Int = 64, val maxRadius: Int = 512)

    companion object {

        val GAMEMODES = setOf("survival", "creative", "adventure", "spectator")

        fun from(y: YamlConfiguration): WorldSettings {
            val d = WorldSettings()
            return WorldSettings(
                limits = limits(y.getConfigurationSection("limits"), d.limits),
                sleep = sleep(y.getConfigurationSection("sleep"), d.sleep),
                portal = portal(y.getConfigurationSection("nether-portal"), d.portal),
                vehicles = Vehicles(
                    boats = riders(y.getConfigurationSection("entity-vehicle.boats")),
                    minecarts = riders(y.getConfigurationSection("entity-vehicle.minecarts")),
                ),
                time = time(y.getConfigurationSection("time"), d.time),
                weatherDefaultSeconds = y.getLong("weather.default-duration", 0).coerceAtLeast(0),
                findBiome = y.getConfigurationSection("find-biome")?.let { s ->
                    val f = d.findBiome
                    val max = s.getInt("max-radius", f.maxRadius).takeIf { it > 0 } ?: f.maxRadius
                    FindBiome(
                        radius = s.getInt("radius", f.radius).takeIf { it > 0 }?.coerceAtMost(max) ?: f.radius.coerceAtMost(max),
                        maxRadius = max,
                        step = s.getInt("step", f.step).takeIf { it in 4..256 } ?: f.step,
                        maxConcurrent = s.getInt("max-concurrent", f.maxConcurrent).takeIf { it in 1..16 } ?: f.maxConcurrent,
                        progressEverySeconds = s.getInt("progress-every", f.progressEverySeconds).takeIf { it >= 1 } ?: f.progressEverySeconds,
                        maxSeconds = s.getInt("max-seconds", f.maxSeconds).takeIf { it >= 5 } ?: f.maxSeconds,
                        deepDarkWarning = s.getBoolean("deep-dark-warning", f.deepDarkWarning),
                    )
                } ?: d.findBiome,
                fixChunk = y.getConfigurationSection("fix-chunk")?.let { s ->
                    val f = d.fixChunk
                    FixChunk(
                        speed = s.getInt("speed", f.speed).takeIf { it in 1..100_000 } ?: f.speed,
                        autoSpeed = s.getBoolean("auto-speed", f.autoSpeed),
                        messages = s.getBoolean("messages", f.messages),
                        progressEverySeconds = s.getInt("progress-every", f.progressEverySeconds).takeIf { it >= 1 } ?: f.progressEverySeconds,
                    )
                } ?: d.fixChunk,
                lightFix = y.getConfigurationSection("light-fix")?.let { s ->
                    LightFix(
                        batch = s.getInt("batch", d.lightFix.batch).takeIf { it in 1..1024 } ?: d.lightFix.batch,
                        progressEverySeconds = s.getInt("progress-every", d.lightFix.progressEverySeconds).takeIf { it >= 1 } ?: d.lightFix.progressEverySeconds,
                    )
                } ?: d.lightFix,
                killAll = y.getConfigurationSection("kill-all")?.let { s ->
                    val max = s.getInt("max-radius", d.killAll.maxRadius).takeIf { it > 0 } ?: d.killAll.maxRadius
                    KillAll(radius = (s.getInt("radius", d.killAll.radius).takeIf { it > 0 } ?: d.killAll.radius).coerceAtMost(max), maxRadius = max)
                } ?: d.killAll,
            )
        }

        private fun lowerSet(s: ConfigurationSection, key: String): Set<String> =
            s.getStringList(key).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

        private fun limits(s: ConfigurationSection?, d: Limits): Limits {
            s ?: return d
            val gamemode = LinkedHashMap<String, String>()
            s.getConfigurationSection("gamemode")?.let { g ->
                for (world in g.getKeys(false)) {
                    val mode = g.getString(world)?.trim()?.lowercase() ?: continue
                    if (mode in GAMEMODES) gamemode[world.lowercase()] = mode
                }
            }
            val roof = LinkedHashMap<String, Int>()
            val roofSection = s.getConfigurationSection("roof")
            if (roofSection == null) roof.putAll(d.roof)
            else for (world in roofSection.getKeys(false)) roofSection.getInt(world, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }?.let { roof[world.lowercase()] = it }
            val reasons = LinkedHashMap<String, Set<String>>()
            s.getConfigurationSection("spawn-reasons")?.let { r ->
                for (world in r.getKeys(false)) {
                    val set = r.getStringList(world).map { it.trim().uppercase() }.filter { it.isNotEmpty() && it != "NONE" }.toSet()
                    if (set.isNotEmpty()) reasons[world.lowercase()] = set
                }
            }
            return Limits(
                enabled = s.getBoolean("enabled", d.enabled),
                checkEverySeconds = s.getInt("check-every", d.checkEverySeconds).takeIf { it in 1..3600 } ?: d.checkEverySeconds,
                gamemode = gamemode,
                noFly = lowerSet(s, "no-fly"),
                noGod = lowerSet(s, "no-god"),
                noElytra = lowerSet(s, "no-elytra"),
                elytraUnequip = s.getBoolean("elytra-unequip", d.elytraUnequip),
                flyAboveRoof = s.getBoolean("fly-above-roof", d.flyAboveRoof),
                roof = roof,
                spawnReasons = reasons,
            )
        }

        private fun sleep(s: ConfigurationSection?, d: Sleep): Sleep {
            s ?: return d
            val info = s.getString("info")?.trim()?.uppercase()?.let { name -> Sleep.Info.entries.firstOrNull { it.name == name } } ?: d.info
            val base = s.getInt("base-speed", d.baseSpeed).takeIf { it in 1..10_000 } ?: d.baseSpeed
            return Sleep(
                enabled = s.getBoolean("enabled", d.enabled),
                worlds = lowerSet(s, "worlds"),
                onlyNight = s.getBoolean("only-night", d.onlyNight),
                nightStartsAt = s.getLong("night-starts-at", d.nightStartsAt).takeIf { it in 0..23999 } ?: d.nightStartsAt,
                info = info,
                excludeAfk = s.getBoolean("exclude-afk", d.excludeAfk),
                baseSpeed = base,
                minSpeed = (s.getInt("min-speed", d.minSpeed).takeIf { it in 1..10_000 } ?: d.minSpeed).coerceAtMost(base),
                minSleeping = Threshold.parse(s.getString("min-sleeping"), d.minSleeping),
                skipAt = Threshold.parse(s.getString("skip-at"), d.skipAt),
                clearWeather = s.getBoolean("clear-weather", d.clearWeather),
                inform = s.getBoolean("inform", d.inform),
                informDelaySeconds = s.getInt("inform-delay", d.informDelaySeconds).takeIf { it >= 0 } ?: d.informDelaySeconds,
            )
        }

        private fun portalRule(s: ConfigurationSection, d: PortalRule): PortalRule = PortalRule(
            preventCreation = s.getBoolean("prevent-creation", d.preventCreation),
            maxWidth = s.getInt("max-width", d.maxWidth).takeIf { it in 2..23 } ?: d.maxWidth,
            maxHeight = s.getInt("max-height", d.maxHeight).takeIf { it in 3..23 } ?: d.maxHeight,
        )

        private fun portal(s: ConfigurationSection?, d: Portal): Portal {
            s ?: return d
            val base = portalRule(s, d.base)
            val per = LinkedHashMap<String, PortalRule>()
            s.getConfigurationSection("worlds")?.let { w ->
                for (world in w.getKeys(false)) w.getConfigurationSection(world)?.let { per[world.lowercase()] = portalRule(it, base) }
            }
            return Portal(base, per, s.getBoolean("show-size", d.showSize))
        }

        private fun riders(s: ConfigurationSection?): Riders {
            s ?: return Riders()
            return Riders(s.getBoolean("monsters", false), s.getBoolean("animals", false), s.getBoolean("villagers", false))
        }

        private fun time(s: ConfigurationSection?, d: Time): Time {
            s ?: return d
            val presets = LinkedHashMap(d.presets)
            s.getConfigurationSection("presets")?.let { p ->
                for (key in p.getKeys(false)) TimeText.parse(p.getString(key) ?: continue)?.let { presets[key.lowercase()] = it.ticks }
            }
            val auto = s.getConfigurationSection("auto-real")
            return Time(
                presets = presets,
                smoothSpeed = s.getInt("smooth-speed", d.smoothSpeed).takeIf { it in 2..10_000 } ?: d.smoothSpeed,
                autoRealWorlds = auto?.let { lowerSet(it, "worlds") } ?: d.autoRealWorlds,
                autoRealIntervalSeconds = auto?.getInt("interval", d.autoRealIntervalSeconds)?.takeIf { it in 1..3600 } ?: d.autoRealIntervalSeconds,
                autoRealSmooth = auto?.getBoolean("smooth", d.autoRealSmooth) ?: d.autoRealSmooth,
                timezone = auto?.getString("timezone", d.timezone)?.trim() ?: d.timezone,
            )
        }
    }
}
