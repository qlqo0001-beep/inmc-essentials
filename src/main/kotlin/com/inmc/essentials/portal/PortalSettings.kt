package com.inmc.essentials.portal

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration

/**
 * `settings/portals.yml` 의 불변 스냅샷(리로드 때 통째로 바뀐다). 값이 이상하면 기본값(던지지 않는다). **서버 없이 읽힌다.**
 * 괄호 안은 옮겨 온 CMI 설정 키(config.yml `Portals`).
 */
data class PortalSettings(
    /** 파티클을 몇 초마다 뿌리나(CheckParticleInterval 500ms — 우리 반복 작업은 1초 단위라 1초가 가장 짧다). */
    val particleSeconds: Int = 1,
    /** 밀어내는 세기(블록/틱 쯤). */
    val pushStrength: Double = 0.6,
    /** 한 포탈의 최대 크기(블록 수) — 실수로 월드 전체를 고르지 않게. */
    val maxVolume: Long = 200_000,
    /** 새 포탈의 기본값(Defaults.PerformCommands · Defaults.Commands). */
    val defaults: Portal = DEFAULT_TEMPLATE,
) {

    /** [defaults] 를 바탕으로 [name]·[box] 의 새 포탈. */
    fun create(name: String, box: PortalBox): Portal = defaults.copy(name = name, box = box)

    companion object {

        /** 이름·영역은 [create] 가 채운다. CMI 기본 명령(`cmi effect [playerName] blindness 2 1 -s`)은 효과 한 줄로. */
        val DEFAULT_TEMPLATE = Portal(
            name = "",
            box = PortalBox("world", 0, 0, 0, 0, 0, 0),
            effects = listOf("blindness:2:1"),
            cooldown = 2,
            sound = "entity.enderman.teleport",
        )

        fun from(y: YamlConfiguration): PortalSettings {
            val base = PortalSettings()
            val d = y.getConfigurationSection("defaults")
            val template = if (d == null) DEFAULT_TEMPLATE else readDefaults(d)
            return PortalSettings(
                particleSeconds = y.getInt("particle-seconds", base.particleSeconds).coerceIn(1, 60),
                pushStrength = y.getDouble("push-strength", base.pushStrength).takeIf { it.isFinite() }?.coerceIn(0.0, 3.0) ?: base.pushStrength,
                maxVolume = y.getLong("max-volume", base.maxVolume).takeIf { it > 0 } ?: base.maxVolume,
                defaults = template,
            )
        }

        private fun readDefaults(d: ConfigurationSection): Portal {
            val t = DEFAULT_TEMPLATE
            val p = d.getConfigurationSection("particles")
            return t.copy(
                commandsWithoutTarget = d.getBoolean("commands-without-target", t.commandsWithoutTarget),
                commands = d.getStringList("commands").filter { it.isNotBlank() },
                effects = if (d.isList("effects")) d.getStringList("effects").filter { PortalRules.parseEffect(it) != null } else t.effects,
                cooldown = d.getInt("cooldown-seconds", t.cooldown).coerceIn(0, 86_400),
                sound = (if (d.contains("sound")) d.getString("sound") else t.sound)?.trim()?.takeIf { it.isNotEmpty() },
                kickBack = d.getBoolean("kick-back", t.kickBack),
                informPermission = d.getBoolean("inform-permission", t.informPermission),
                particles = if (p == null) t.particles else t.particles.copy(
                    enabled = p.getBoolean("enabled", t.particles.enabled),
                    type = p.getString("type")?.trim()?.takeIf { it.isNotEmpty() } ?: t.particles.type,
                    amount = p.getInt("amount", t.particles.amount).coerceIn(1, 500),
                    range = p.getInt("range", t.particles.range).coerceIn(1, 128),
                ),
            )
        }
    }
}
