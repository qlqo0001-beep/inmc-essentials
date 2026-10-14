package com.inmc.essentials.player

import org.bukkit.configuration.file.YamlConfiguration

/**
 * `settings/player.yml` 의 불변 스냅샷(리로드 때 통째로 바뀐다). 값이 이상하면 기본값(던지지 않는다).
 * 기본값은 CMI 설정의 기본값을 옮긴 것이다(괄호 안은 CMI 키).
 */
data class PlayerConfig(
    // --- 최대 체력 (Optimizations.MaxHp) ---
    val maxHpCap: Double = 1000.0,
    // --- 회복·배고픔 (heal.RemoveNegative · hunger.overide) ---
    val healRemoveNegative: Boolean = true,
    val healNegative: List<String> = listOf(
        "blindness", "nausea", "instant_damage", "hunger", "poison", "slowness", "mining_fatigue", "weakness", "wither", "darkness",
    ),
    val feedAllowOverMax: Boolean = false,
    // --- 효과 (PotionEffects.DeductWhileOffline) ---
    val effectDeductWhileOffline: Boolean = false,
    // --- 무적 ---
    val godKeepsHunger: Boolean = true,
    // --- 비행 (WorldLimits.ReenabledFlyAfterDeath) ---
    val flyRestoreAfterDeath: Boolean = false,
    val flyKeepOnGameModeChange: Boolean = true,
    // --- 시간제 비행·무적 (timedCommands) ---
    val timedWarnAt: List<Long> = listOf(60, 30, 10, 5, 3, 2, 1),
    val tflyLandingProtection: Boolean = true,
    // --- 모자 (Optimizations.Hat) ---
    val hatBlockWithLore: Boolean = false,
    val hatBlockNoneHat: Boolean = true,
    val hatBlockNoneHatEnchanted: Boolean = true,
    val hatWhitelist: Set<String> = emptySet(),
    val hatInventory: Boolean = true,
    // --- 앉기 (Animations) ---
    val sitChairs: Boolean = true,
    val sitStairs: Boolean = true,
    val sitSlabs: Boolean = true,
    val sitCarpets: Boolean = false,
    val sitStandOnDamage: Boolean = true,
    val sitDoubleClick: Boolean = false,
    val sitDoubleClickMillis: Long = 200,
    val sitChairRange: Double = 4.0,
    val sitHeight: Double = 0.0,
    // --- 타기 (rideAnimation) ---
    val rideEjectOnSuffocation: Boolean = true,
    val rideBlacklist: Set<String> = setOf("ender_dragon", "wither"),
    val rideMaxDistance: Double = 6.0,
    // --- 근처 (Optimizations.Commands.Near) ---
    val nearDefault: Int = 200,
    val nearMax: Int = 1000,
    val nearApproximate: Boolean = true,
    val nearHideInvisible: Boolean = false,
    val nearObfuscateInvisible: Boolean = false,
    val nearPointTo: Boolean = true,
    val nearCount: Int = 10,
    // --- 자살 ---
    val suicideBroadcast: Boolean = true,
    // --- 토템 (Totem · Player.Options.Defaults.totemBossBar) ---
    val totemFromInventory: Boolean = false,
    val totemRequirePermission: Boolean = false,
    val totemCooldown: Long = 0,
    val totemWarmup: Long = 0,
    val totemProtectFromVoid: Boolean = true,
    val totemRegeneration: Int = 45,
    val totemFireResistance: Int = 40,
    val totemAbsorption: Int = 5,
    val totemVanillaWorlds: Set<String> = emptySet(),
    val totemDisabledWorlds: Set<String> = emptySet(),
    /** 허공 구하기를 하지 않는 월드 — 던전은 허공 추락을 "쓰러짐" 으로 다루고, 다른 월드로 옮기면 판에서 빠진다. */
    val totemVoidSkipWorlds: Set<String> = setOf("inmc_dungeon"),
    val totemBossBar: Boolean = true,
    // --- 체력 보스바 (BossBar.HpBarEnabled · HpBarBlackList) ---
    val hpBarEnabled: Boolean = true,
    val hpBarBlacklist: Set<String> = setOf("ender_dragon"),
    val hpBarSeconds: Int = 5,
    val hpBarPlayers: Boolean = true,
    /** 커스텀 몹(inmc-monster `mob_id` 가 있는 것 — 보스바를 스스로 띄운다)은 빼기. */
    val hpBarSkipCustomMobs: Boolean = true,
) {

    fun totemVanilla(world: String): Boolean = world.lowercase() in totemVanillaWorlds

    fun totemDisabled(world: String): Boolean = world.lowercase() in totemDisabledWorlds

    fun totemVoidSkip(world: String): Boolean = world.lowercase() in totemVoidSkipWorlds

    companion object {

        fun from(yaml: YamlConfiguration): PlayerConfig {
            val d = PlayerConfig()
            fun bool(path: String, def: Boolean): Boolean = if (yaml.isBoolean(path)) yaml.getBoolean(path) else def
            fun long(path: String, def: Long, min: Long = 0, max: Long = Long.MAX_VALUE): Long =
                (if (yaml.isInt(path) || yaml.isLong(path)) yaml.getLong(path) else yaml.getString(path)?.trim()?.toLongOrNull() ?: def).coerceIn(min, max)
            fun int(path: String, def: Int, min: Int = 0, max: Int = Int.MAX_VALUE): Int = long(path, def.toLong(), min.toLong(), max.toLong()).toInt()
            fun double(path: String, def: Double, min: Double, max: Double): Double =
                (if (yaml.isDouble(path) || yaml.isInt(path) || yaml.isLong(path)) yaml.getDouble(path) else yaml.getString(path)?.trim()?.toDoubleOrNull() ?: def)
                    .let { if (it.isFinite()) it else def }.coerceIn(min, max)
            fun names(path: String, def: Set<String>): Set<String> =
                if (yaml.isList(path)) yaml.getStringList(path).map { it.trim().lowercase().removePrefix("minecraft:") }.filter { it.isNotEmpty() }.toSet() else def

            return PlayerConfig(
                maxHpCap = double("maxhp.cap", d.maxHpCap, 1.0, 1_000_000.0),
                healRemoveNegative = bool("heal.remove-negative", d.healRemoveNegative),
                healNegative = if (yaml.isList("heal.negative-effects")) yaml.getStringList("heal.negative-effects").map(EffectNames::key).filter { it.isNotEmpty() } else d.healNegative,
                feedAllowOverMax = bool("feed.allow-over-max", d.feedAllowOverMax),
                effectDeductWhileOffline = bool("effect.deduct-while-offline", d.effectDeductWhileOffline),
                godKeepsHunger = bool("god.keep-hunger", d.godKeepsHunger),
                flyRestoreAfterDeath = bool("fly.restore-after-death", d.flyRestoreAfterDeath),
                flyKeepOnGameModeChange = bool("fly.keep-on-gamemode-change", d.flyKeepOnGameModeChange),
                timedWarnAt = if (yaml.isList("timed.warn-at")) {
                    yaml.getList("timed.warn-at").orEmpty().mapNotNull { (it as? Number)?.toLong() ?: it?.toString()?.trim()?.toLongOrNull() }.filter { it > 0 }.distinct().sortedDescending()
                } else d.timedWarnAt,
                tflyLandingProtection = bool("timed.tfly-landing-protection", d.tflyLandingProtection),
                hatBlockWithLore = bool("hat.block.with-lore", d.hatBlockWithLore),
                hatBlockNoneHat = bool("hat.block.non-hat-items", d.hatBlockNoneHat),
                hatBlockNoneHatEnchanted = bool("hat.block.non-hat-enchanted", d.hatBlockNoneHatEnchanted),
                hatWhitelist = names("hat.whitelist", d.hatWhitelist),
                hatInventory = bool("hat.inventory-slot", d.hatInventory),
                sitChairs = bool("sit.chairs", d.sitChairs),
                sitStairs = bool("sit.stairs", d.sitStairs),
                sitSlabs = bool("sit.slabs", d.sitSlabs),
                sitCarpets = bool("sit.carpets", d.sitCarpets),
                sitStandOnDamage = bool("sit.stand-on-damage", d.sitStandOnDamage),
                sitDoubleClick = bool("sit.double-click", d.sitDoubleClick),
                sitDoubleClickMillis = long("sit.double-click-millis", d.sitDoubleClickMillis, 50, 2000),
                sitChairRange = double("sit.chair-range", d.sitChairRange, 1.0, 16.0),
                sitHeight = double("sit.height-offset", d.sitHeight, -2.0, 2.0),
                rideEjectOnSuffocation = bool("ride.eject-on-suffocation", d.rideEjectOnSuffocation),
                rideBlacklist = names("ride.blacklist", d.rideBlacklist),
                rideMaxDistance = double("ride.max-distance", d.rideMaxDistance, 1.0, 64.0),
                nearDefault = int("near.default-distance", d.nearDefault, 1, 100_000),
                nearMax = int("near.max-distance", d.nearMax, 1, 100_000),
                nearApproximate = bool("near.approximate", d.nearApproximate),
                nearHideInvisible = bool("near.invisibility.hide", d.nearHideInvisible),
                nearObfuscateInvisible = bool("near.invisibility.obfuscate", d.nearObfuscateInvisible),
                nearPointTo = bool("near.point-to", d.nearPointTo),
                nearCount = int("near.count", d.nearCount, 1, 100),
                suicideBroadcast = bool("suicide.broadcast", d.suicideBroadcast),
                totemFromInventory = bool("totem.from-inventory", d.totemFromInventory),
                totemRequirePermission = bool("totem.require-permission", d.totemRequirePermission),
                totemCooldown = long("totem.cooldown-seconds", d.totemCooldown, 0, 86_400),
                totemWarmup = long("totem.warmup-seconds", d.totemWarmup, 0, 3600),
                totemProtectFromVoid = bool("totem.protect-from-void", d.totemProtectFromVoid),
                totemRegeneration = int("totem.effects.regeneration", d.totemRegeneration, 0, 3600),
                totemFireResistance = int("totem.effects.fire-resistance", d.totemFireResistance, 0, 3600),
                totemAbsorption = int("totem.effects.absorption", d.totemAbsorption, 0, 3600),
                totemVanillaWorlds = names("totem.vanilla-worlds", d.totemVanillaWorlds),
                totemDisabledWorlds = names("totem.disabled-worlds", d.totemDisabledWorlds),
                totemVoidSkipWorlds = names("totem.protect-from-void-skip-worlds", d.totemVoidSkipWorlds),
                totemBossBar = bool("totem.boss-bar", d.totemBossBar),
                hpBarEnabled = bool("hp-bar.enabled", d.hpBarEnabled),
                hpBarBlacklist = names("hp-bar.blacklist", d.hpBarBlacklist),
                hpBarSeconds = int("hp-bar.seconds", d.hpBarSeconds, 1, 60),
                hpBarPlayers = bool("hp-bar.players", d.hpBarPlayers),
                hpBarSkipCustomMobs = bool("hp-bar.skip-custom-mobs", d.hpBarSkipCustomMobs),
            ).let { if (it.nearDefault > it.nearMax) it.copy(nearDefault = it.nearMax) else it }
        }
    }
}
