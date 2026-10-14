package com.inmc.essentials.afk

import org.bukkit.configuration.file.YamlConfiguration

/**
 * `settings/afk.yml` 의 불변 스냅샷(CMI `Settings/Afk.yml` 을 옮긴 것 — 괄호 안이 CMI 키). 값이 이상하면 기본값(던지지 않는다).
 *
 * 활동 종류([Activity])마다 켜고 끈다(`leave-on.*`). **끈 종류는 활동이 아니다** — 자리 비움 시계도 되돌리지 않고 잠수도 풀지 않는다
 * (CMI 는 DisableOn* 이 "풀기"만 정했는데, 시계는 되돌리고 풀지 않으면 자동 잠수가 다시 걸리지 않아 뜻이 엇갈렸다).
 */
data class AfkConfig(
    /** 이 초 동안 활동이 없으면 잠수(AutoAfkIn, 0 = 자동 잠수 없음). 권한 `inmcessentials.afk.auto` 가 있는 사람만. */
    val autoAfkSeconds: Long = 300,
    /** 자동 잠수·추방을 보는 주기(CheckInterval). */
    val checkInterval: Int = 10,
    /** 자동 잠수가 걸리지 않는 월드(DisabledWorlds). */
    val disabledWorlds: Set<String> = emptySet(),
    val title: Boolean = true,
    val subtitle: Boolean = true,
    val broadcastEnter: Boolean = true,
    val broadcastLeave: Boolean = true,
    /** 들어갈 때·나올 때 콘솔이 칠 명령(AutoAfkCmds · ManualAfkCmds · LeaveCmds.*). `player:` 로 시작하면 그 사람이 친다. `{player}` `{reason}` `{time}`. */
    val autoEnterCommands: List<String> = emptyList(),
    val manualEnterCommands: List<String> = emptyList(),
    val leaveManualCommands: List<String> = emptyList(),
    val leaveAutoCommands: List<String> = emptyList(),
    /** 투명인 사람도 위 명령을 돌린다(CMI vanish 의 afkcommands). 끄면 방송·명령 없이 상태만 바뀐다. */
    val commandsWhenVanished: Boolean = false,
    // --- 보호 ---
    val preventDamage: Boolean = true,
    /**
     * 손으로(`/잠수`) 잠수한 사람은 이 초가 지나야 피해 보호를 받는다(자동 잠수는 이미 그만큼 쉬었으니 바로). 싸우다 `/잠수` 로 무적이 되지 않게
     * (CMI 에 없는 것 — 이 서버는 CMI 잠수를 꺼 두었으므로 새로 생기는 무적을 막는다).
     */
    val manualDamageDelay: Long = 30,
    /** 피해 보호를 하지 않는 월드(던전 — 쓰러짐·전멸 판정을 막지 않게). */
    val damageDisabledWorlds: Set<String> = setOf("inmc_dungeon"),
    /** 때리거나 맞은 뒤 이 초 동안은 스스로 `/잠수` 를 못 한다(0 = 막지 않음). */
    val combatBlockSeconds: Long = 15,
    val preventMobDamage: Boolean = true,
    val preventHook: Boolean = true,
    val preventPushing: Boolean = false,
    val disableItemPickup: Boolean = false,
    val disableExpPickup: Boolean = false,
    val disableShulkerTargeting: Boolean = true,
    val mobSpawningEnabled: Boolean = false,
    val mobSpawningNatural: Boolean = false,
    val mobSpawningSpawners: Boolean = true,
    val mobSpawningVillageDefence: Boolean = true,
    val mobSpawningRadius: Double = 64.0,
    // --- 우회 막기 ---
    val preventJumping: Boolean = true,
    val smartInteractCheck: Boolean = true,
    val antiAfkMachines: Boolean = true,
    // --- 활동으로 볼 것 ---
    val leaveOn: Set<Activity> = setOf(
        Activity.INTERACT, Activity.INVENTORY_CLICK, Activity.ITEM_DROP, Activity.COMMAND, Activity.PUBLIC_CHAT, Activity.PRIVATE_CHAT, Activity.MOVE,
    ),
    // --- 자동 추방 ---
    /** 잠수가 된 뒤 이 초가 더 지나면 추방(AutoKickTimes.Default, -1 = 끔). 손으로 잠수한 사람은 자동 잠수 시간을 더 기다린다(CMI 와 같다). */
    val kickSeconds: Long = -1,
    val kickFromPlayerCount: Int = 0,
    /** (인원, 초) — 잠수가 될 때의 인원으로 그 이하 중 가장 큰 인원의 것(ByPlayerCount). */
    val kickByPlayerCount: List<Pair<Int, Long>> = listOf(10 to 600L, 20 to 500L, 30 to 400L, 40 to 350L, 50 to 300L),
    val kickRepeat: Boolean = false,
    val kickRepeatInterval: Long = 300,
    /** 비우면 기본 추방(메시지 `afk-kick-reason`). */
    val kickCommands: List<String> = emptyList(),
    // --- 귓속말 ---
    val mayNotRespond: Boolean = true,
    val whisperCommands: Set<String> = setOf("msg", "tell", "w", "m", "whisper", "귓", "귓말", "귓속말", "pm", "dm"),
    val replyCommands: Set<String> = setOf("r", "reply", "답장"),
) {

    enum class Activity(val key: String) {
        INTERACT("interact"),
        INVENTORY_CLICK("inventory-click"),
        ITEM_DROP("item-drop"),
        COMMAND("command"),
        PUBLIC_CHAT("public-chat"),
        PRIVATE_CHAT("private-chat"),
        MOVE("move"),
        LOOK_AROUND("look-around"),
        FISHING("fishing"),
    }

    fun counts(activity: Activity): Boolean = activity in leaveOn

    fun autoDisabledIn(world: String): Boolean = world.lowercase() in disabledWorlds

    /** 잠수한 지 [afkMillis] 된 사람을 피해에서 지키나 — 자동 잠수는 바로, 손 잠수는 [manualDamageDelay] 뒤. */
    fun protectsFromDamage(auto: Boolean, afkMillis: Long, world: String): Boolean =
        preventDamage && world.lowercase() !in damageDisabledWorlds && (auto || afkMillis >= manualDamageDelay * 1000)

    /**
     * 추방까지의 초 — null 이면 추방하지 않는다. [permSeconds] = 권한 `inmcessentials.afk.kickoutin.<초>` 중 가장 큰 것(있으면 그것이 이긴다),
     * [bypass] = `inmcessentials.afk.kickbypass`. 기본이 -1(끔)이면 아무도 추방하지 않는다.
     */
    fun kickAfter(online: Int, permSeconds: Long?, bypass: Boolean): Long? {
        if (bypass || kickSeconds < 0) return null
        if (permSeconds != null) return permSeconds
        val tier = kickByPlayerCount.filter { it.first <= online }.maxByOrNull { it.first }
        return tier?.second ?: kickSeconds
    }

    companion object {

        fun parseTier(raw: String): Pair<Int, Long>? {
            val parts = raw.trim().split('-')
            if (parts.size != 2) return null
            val count = parts[0].trim().toIntOrNull() ?: return null
            val seconds = parts[1].trim().toLongOrNull() ?: return null
            return if (count >= 0 && seconds >= 0) count to seconds else null
        }

        fun from(yaml: YamlConfiguration): AfkConfig {
            val d = AfkConfig()
            fun bool(path: String, def: Boolean): Boolean = if (yaml.isBoolean(path)) yaml.getBoolean(path) else def
            fun long(path: String, def: Long, min: Long, max: Long): Long =
                (if (yaml.isInt(path) || yaml.isLong(path)) yaml.getLong(path) else yaml.getString(path)?.trim()?.toLongOrNull() ?: def).coerceIn(min, max)
            fun list(path: String, def: List<String>): List<String> = if (yaml.isList(path)) yaml.getStringList(path).map { it.trim() }.filter { it.isNotEmpty() } else def
            fun names(path: String, def: Set<String>): Set<String> = if (yaml.isList(path)) list(path, emptyList()).map { it.lowercase().removePrefix("/") }.toSet() else def

            val leaveOn = if (yaml.isConfigurationSection("leave-on")) {
                Activity.entries.filter { bool("leave-on.${it.key}", it in d.leaveOn) }.toSet()
            } else d.leaveOn
            return AfkConfig(
                autoAfkSeconds = long("auto-afk-seconds", d.autoAfkSeconds, 0, 86_400),
                checkInterval = long("check-interval", d.checkInterval.toLong(), 1, 300).toInt(),
                disabledWorlds = names("disabled-worlds", d.disabledWorlds),
                title = bool("title", d.title),
                subtitle = bool("subtitle", d.subtitle),
                broadcastEnter = bool("broadcast.enter", d.broadcastEnter),
                broadcastLeave = bool("broadcast.leave", d.broadcastLeave),
                autoEnterCommands = list("commands.auto-enter", d.autoEnterCommands),
                manualEnterCommands = list("commands.manual-enter", d.manualEnterCommands),
                leaveManualCommands = list("commands.leave-manual", d.leaveManualCommands),
                leaveAutoCommands = list("commands.leave-auto", d.leaveAutoCommands),
                commandsWhenVanished = bool("commands.when-vanished", d.commandsWhenVanished),
                preventDamage = bool("protect.damage", d.preventDamage),
                manualDamageDelay = long("protect.damage-manual-delay", d.manualDamageDelay, 0, 86_400),
                damageDisabledWorlds = names("protect.damage-disabled-worlds", d.damageDisabledWorlds),
                combatBlockSeconds = long("manual.combat-block-seconds", d.combatBlockSeconds, 0, 3600),
                preventMobDamage = bool("protect.mob-damage", d.preventMobDamage),
                preventHook = bool("protect.hook", d.preventHook),
                preventPushing = bool("protect.pushing", d.preventPushing),
                disableItemPickup = bool("protect.item-pickup", d.disableItemPickup),
                disableExpPickup = bool("protect.exp-pickup", d.disableExpPickup),
                disableShulkerTargeting = bool("protect.shulker-targeting", d.disableShulkerTargeting),
                mobSpawningEnabled = bool("protect.mob-spawning.enabled", d.mobSpawningEnabled),
                mobSpawningNatural = bool("protect.mob-spawning.natural", d.mobSpawningNatural),
                mobSpawningSpawners = bool("protect.mob-spawning.spawners", d.mobSpawningSpawners),
                mobSpawningVillageDefence = bool("protect.mob-spawning.village-defence", d.mobSpawningVillageDefence),
                mobSpawningRadius = long("protect.mob-spawning.radius", d.mobSpawningRadius.toLong(), 8, 256).toDouble(),
                preventJumping = bool("anti-abuse.prevent-jumping", d.preventJumping),
                smartInteractCheck = bool("anti-abuse.smart-interact-check", d.smartInteractCheck),
                antiAfkMachines = bool("anti-abuse.anti-afk-machines", d.antiAfkMachines),
                leaveOn = leaveOn,
                kickSeconds = long("auto-kick.seconds", d.kickSeconds, -1, 604_800),
                kickFromPlayerCount = long("auto-kick.from-player-count", d.kickFromPlayerCount.toLong(), 0, 100_000).toInt(),
                kickByPlayerCount = if (yaml.isList("auto-kick.by-player-count")) list("auto-kick.by-player-count", emptyList()).mapNotNull(::parseTier) else d.kickByPlayerCount,
                kickRepeat = bool("auto-kick.repeat", d.kickRepeat),
                kickRepeatInterval = long("auto-kick.repeat-interval", d.kickRepeatInterval, 10, 86_400),
                kickCommands = list("auto-kick.commands", d.kickCommands),
                mayNotRespond = bool("may-not-respond.enabled", d.mayNotRespond),
                whisperCommands = names("may-not-respond.whisper-commands", d.whisperCommands),
                replyCommands = names("may-not-respond.reply-commands", d.replyCommands),
            )
        }
    }
}
