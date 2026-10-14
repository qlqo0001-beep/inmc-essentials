package com.inmc.essentials.teleport

import com.inmc.essentials.service.TeleportKind
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause

/** 소리 하나 — `키:크기:높낮이`. 키는 `entity.enderman.teleport`(마크 키) 또는 CMI 의 `entity_enderman_teleport`(상수 이름) 둘 다. */
data class SoundSpec(val key: String, val volume: Float = 1f, val pitch: Float = 1f) {

    companion object {

        /** 비었거나 이상하면 null(소리 없음). */
        fun parse(raw: String?): SoundSpec? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            val parts = text.split(':')
            // 끝의 숫자 둘(크기·높낮이)만 떼고 나머지는 키 — `minecraft:entity.x:1:1` 처럼 이름공간이 붙어도 된다.
            val numbers = parts.reversed().takeWhile { it.toFloatOrNull() != null }.reversed().take(2)
            val key = parts.dropLast(numbers.size).joinToString(":").ifEmpty { return null }
            val volume = numbers.getOrNull(0)?.toFloatOrNull() ?: 1f
            val pitch = numbers.getOrNull(1)?.toFloatOrNull() ?: 1f
            return SoundSpec(key, volume.coerceIn(0f, 10f), pitch.coerceIn(0.5f, 2f))
        }
    }
}

/** 파티클 하나 — `이름:개수`(예: `portal:40`). 데이터가 필요한 파티클(색 먼지 등)은 쓸 때 건너뛴다. */
data class ParticleSpec(val name: String, val count: Int = 30) {

    companion object {

        fun parse(raw: String?): ParticleSpec? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            val name = text.substringBefore(':').trim().ifEmpty { return null }
            val count = text.substringAfter(':', "").trim().toIntOrNull() ?: 30
            return ParticleSpec(name, count.coerceIn(1, 500))
        }
    }
}

/** 이동 종류 하나의 효과 — 출발 자리 파티클 · 도착 자리 파티클 · 도착 소리. */
data class KindEffect(val from: ParticleSpec? = null, val to: ParticleSpec? = null, val sound: SoundSpec? = null)

/**
 * 이동 금지 아이템 한 줄 — [ref] 는 core `ItemRef` 문자열(`diamond` · `minecraft:iron_ore` · `mmoitems:SWORD:X` · `inmc:ruby`),
 * [max] 는 들고 있어도 되는 최대 개수(그보다 많으면 막힌다 — CMI 와 같은 뜻, 기본 0).
 */
data class BlacklistRule(val ref: String, val max: Int = 0) {

    companion object {

        /** CMI 모양 `ironore:5` — 끝 조각이 숫자면 개수. `mmoitems:SWORD:X:2` 도 된다. 숫자가 아니면 전부 아이템. */
        fun parse(raw: String?): BlacklistRule? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            val parts = text.split(':')
            val last = parts.last().trim().toIntOrNull()
            return if (parts.size >= 2 && last != null) {
                BlacklistRule(parts.dropLast(1).joinToString(":").trim().ifEmpty { return null }, last.coerceAtLeast(0))
            } else {
                BlacklistRule(text, 0)
            }
        }
    }
}

/**
 * `settings/teleport.yml` 의 불변 스냅샷. 값이 이상하면 기본값(던지지 않는다). 배포 파일 = 이 기본값(`TeleportSettingsTest`).
 * CMI 의 같은 설정은 주석에 적었다(config.yml `Teleport.*` · Settings/ParticleEffects.yml `TeleportEffects` · `Sounds`).
 */
data class TeleportSettings(
    // --- 안전한 자리 (CMI SafeLocationDownThenUp) ---
    val safeEnabled: Boolean = true,
    val safeDownThenUp: Boolean = false,
    val safeVertical: Int = 16,
    val safeHorizontal: Int = 2,
    // --- 이동 뒤 무적 (CMI Invulnerability · InvulnerabilityPerWorld · DisableInvulnerability) ---
    val invulnerableSeconds: Int = 5,
    val invulnerablePerWorld: Map<String, Int> = emptyMap(),
    val invulnerableNoAttack: Boolean = true,
    val invulnerableEndOnBlock: Boolean = true,
    val invulnerableEndOnInteract: Boolean = true,
    // --- 이동 (CMI SwitchPlaces) ---
    val switchPlaces: Boolean = true,
    // --- 나가 있는 동안 불린 자리 — 이 초가 지나면 버린다(0 = 끝없음) · /월드전원소환 이 고르는 나간 사람(최근 N일 안에 나간 사람만, 0 = 전부) ---
    val pendingExpireSeconds: Long = 7 * 24 * 3600L,
    val tpAllWorldOfflineDays: Int = 30,
    // --- 이동 요청 (CMI Tpa · TpaHere · CurrentLoc · DenyConfirm · Sounds.TpaRequest · Player.Options.Defaults.acceptingTPA) ---
    val tpaExpireSeconds: Int = 60,
    val tpaWarmupSeconds: Int = 3,
    val tpaCancelOnMove: Boolean = true,
    val tpaDenyBlockSeconds: Int = 120,
    val tpaMaxDistance: Int = 0,
    val tpaHereMaxDistance: Int = 0,
    val tpaCurrentLocation: Boolean = true,
    val tpaHereCurrentLocation: Boolean = true,
    val tpaButtons: Boolean = true,
    val tpaRequestSound: SoundSpec? = SoundSpec("block.anvil.land", 0.5f, 2f),
    val tpaDefaultAccepting: Boolean = true,
    /** 이 월드에 있거나 이 월드로 가는 이동 요청은 막는다(던전 — 판·입장료 없이 들어가지 않게). */
    val tpaExcludedWorlds: Set<String> = setOf("inmc_dungeon"),
    // --- 뒤로 (CMI Teleport.Back) ---
    val backCauses: Set<TeleportCause> = setOf(TeleportCause.COMMAND, TeleportCause.PLUGIN, TeleportCause.UNKNOWN),
    val backMinDistance: Double = 5.0,
    val backExcludedWorlds: Set<String> = setOf("inmc_dungeon"),
    val backExpireSeconds: Long = 0,
    val backRecordBackMoves: Boolean = false,
    // --- 사망 지점 (CMI Teleport.DBack) ---
    val deathExcludedWorlds: Set<String> = setOf("inmc_dungeon"),
    val deathExpireSeconds: Long = 0,
    // --- 이동 금지 아이템 (CMI Teleport.BlackListedItems) ---
    val blacklistEnabled: Boolean = false,
    /** CMI `EnabledFor.tp` — `/이동`·`/좌표이동` 으로 하는 자기 이동(설정의 `TP` 칸). OTHER 종류는 다른 기능의 시스템 이동이라 보지 않는다. */
    val blacklistTp: Boolean = true,
    val blacklistKinds: Set<TeleportKind> = setOf(
        TeleportKind.TPA, TeleportKind.HOME, TeleportKind.WARP, TeleportKind.SPAWN, TeleportKind.BACK, TeleportKind.DEATH_BACK,
    ),
    val blacklist: List<BlacklistRule> = listOf(
        BlacklistRule("diamond"), BlacklistRule("diamond_block"), BlacklistRule("diamond_ore"), BlacklistRule("iron_ore", 5),
    ),
    // --- 효과 (CMI TeleportEffects · Sounds.TeleportHome/Warp/Spawn/Fail) ---
    val effects: Map<TeleportKind, KindEffect> = DEFAULT_EFFECTS,
    /** 이 기능의 명령마다 효과(CMI TeleportEffects 의 Tp·TpHere·TpAll·TpaAll·TpPos·TpoPos) — 있으면 종류의 효과 대신 쓴다. */
    val commandEffects: Map<String, KindEffect> = emptyMap(),
    val failSound: SoundSpec? = SoundSpec("entity.villager.no", 2f, 1f),
) {

    /**
     * 이 종류의 이동에 금지 아이템을 보나. 관리자 이동·감옥·OTHER(다른 기능의 시스템 이동 — 토템 구조·앉기·접속 복귀)는 설정과 무관하게
     * 보지 않는다. 사람이 고른 `/이동`·`/좌표이동` 은 [blacklistAppliesToTp].
     */
    fun blacklistApplies(kind: TeleportKind): Boolean =
        blacklistEnabled && blacklist.isNotEmpty() && kind !in NEVER_BLACKLISTED && kind in blacklistKinds

    /** `/이동`·`/좌표이동` 으로 하는 자기 이동에 금지 아이템을 보나(설정의 `TP` 칸). */
    fun blacklistAppliesToTp(): Boolean = blacklistEnabled && blacklist.isNotEmpty() && blacklistTp

    /** 이 이동의 효과 — 명령 열쇠([command])의 효과가 있으면 그것, 없으면 종류의 효과. */
    fun effectFor(kind: TeleportKind, command: String?): KindEffect? = command?.let { commandEffects[it] } ?: effects[kind]

    fun tpaExcluded(world: String): Boolean = world.lowercase() in tpaExcludedWorlds

    /** 나가 있는 동안 불린 자리가 너무 오래됐나. */
    fun pendingExpired(spot: Spot, now: Long): Boolean = spot.expired(pendingExpireSeconds, now)

    /** [world] 로 옮긴 뒤 무적 초(월드별이 있으면 그것). 0 = 없음. 60 이 최대(CMI 와 같다). */
    fun invulnerabilityFor(world: String): Int =
        (invulnerablePerWorld[world.lowercase()] ?: invulnerableSeconds).coerceIn(0, 60)

    fun backExcluded(world: String): Boolean = world.lowercase() in backExcludedWorlds

    fun deathExcluded(world: String): Boolean = world.lowercase() in deathExcludedWorlds

    companion object {

        /** 금지 아이템을 보지 않는 종류 — 설정에 적어도 무시된다(로드할 때 경고). */
        val NEVER_BLACKLISTED: Set<TeleportKind> = setOf(TeleportKind.ADMIN, TeleportKind.JAIL, TeleportKind.OTHER)

        /** `blacklisted-items.kinds` 의 `/이동`·`/좌표이동` 칸(CMI EnabledFor.tp). */
        const val TP_KIND = "TP"

        /** 명령마다 효과 열쇠(CMI TeleportEffects 이름). */
        const val FX_TP = "TP"
        const val FX_TPHERE = "TPHERE"
        const val FX_TPALL = "TPALL"
        const val FX_TPAALL = "TPAALL"
        const val FX_TPPOS = "TPPOS"
        const val FX_TPOPOS = "TPOPOS"
        val COMMAND_EFFECT_KEYS: Set<String> = setOf(FX_TP, FX_TPHERE, FX_TPALL, FX_TPAALL, FX_TPPOS, FX_TPOPOS)

        val DEFAULT_EFFECTS: Map<TeleportKind, KindEffect> = mapOf(
            TeleportKind.HOME to KindEffect(sound = SoundSpec("block.beacon.activate", 2f, 1f)),
            TeleportKind.WARP to KindEffect(sound = SoundSpec("entity.enderman.teleport", 0.5f, 1f)),
            TeleportKind.SPAWN to KindEffect(sound = SoundSpec("entity.enderman.teleport", 0.5f, 1f)),
        )

        fun from(yaml: YamlConfiguration): TeleportSettings {
            val d = TeleportSettings()
            fun int(path: String, def: Int, min: Int = 0, max: Int = Int.MAX_VALUE): Int =
                (if (yaml.isInt(path) || yaml.isLong(path)) yaml.getInt(path) else yaml.getString(path)?.trim()?.toIntOrNull() ?: def).coerceIn(min, max)
            fun long(path: String, def: Long): Long =
                (if (yaml.isInt(path) || yaml.isLong(path)) yaml.getLong(path) else yaml.getString(path)?.trim()?.toLongOrNull() ?: def).coerceAtLeast(0)
            fun bool(path: String, def: Boolean): Boolean = if (yaml.isBoolean(path)) yaml.getBoolean(path) else def
            fun worlds(path: String, def: Set<String>): Set<String> =
                if (yaml.isList(path)) yaml.getStringList(path).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet() else def
            fun sound(path: String, def: SoundSpec?): SoundSpec? = if (yaml.isString(path)) SoundSpec.parse(yaml.getString(path)) else def

            return TeleportSettings(
                safeEnabled = bool("safe.enabled", d.safeEnabled),
                safeDownThenUp = bool("safe.down-then-up", d.safeDownThenUp),
                safeVertical = int("safe.vertical", d.safeVertical, 0, 64),
                safeHorizontal = int("safe.horizontal", d.safeHorizontal, 0, 8),
                invulnerableSeconds = int("invulnerability.seconds", d.invulnerableSeconds, 0, 60),
                invulnerablePerWorld = perWorld(yaml, "invulnerability.per-world"),
                invulnerableNoAttack = bool("invulnerability.no-attack", d.invulnerableNoAttack),
                invulnerableEndOnBlock = bool("invulnerability.end-on-block", d.invulnerableEndOnBlock),
                invulnerableEndOnInteract = bool("invulnerability.end-on-interact", d.invulnerableEndOnInteract),
                switchPlaces = bool("tp.switch-places", d.switchPlaces),
                pendingExpireSeconds = long("tp.pending-expire-seconds", d.pendingExpireSeconds),
                tpAllWorldOfflineDays = int("tp.tpallworld-offline-days", d.tpAllWorldOfflineDays, 0, 36500),
                tpaExpireSeconds = int("tpa.expire-seconds", d.tpaExpireSeconds, 5, 3600),
                tpaWarmupSeconds = int("tpa.warmup-seconds", d.tpaWarmupSeconds, 0, 60),
                tpaCancelOnMove = bool("tpa.cancel-on-move", d.tpaCancelOnMove),
                tpaDenyBlockSeconds = int("tpa.deny-block-seconds", d.tpaDenyBlockSeconds, 0, 86400),
                tpaMaxDistance = int("tpa.max-distance", d.tpaMaxDistance),
                tpaHereMaxDistance = int("tpa.here-max-distance", d.tpaHereMaxDistance),
                tpaCurrentLocation = bool("tpa.current-location", d.tpaCurrentLocation),
                tpaHereCurrentLocation = bool("tpa.here-current-location", d.tpaHereCurrentLocation),
                tpaButtons = bool("tpa.buttons", d.tpaButtons),
                tpaRequestSound = sound("tpa.request-sound", d.tpaRequestSound),
                tpaDefaultAccepting = bool("tpa.default-accepting", d.tpaDefaultAccepting),
                tpaExcludedWorlds = worlds("tpa.excluded-worlds", d.tpaExcludedWorlds),
                backCauses = if (yaml.isList("back.causes")) {
                    yaml.getStringList("back.causes").mapNotNull { name -> runCatching { TeleportCause.valueOf(name.trim().uppercase()) }.getOrNull() }.toSet()
                } else {
                    d.backCauses
                },
                backMinDistance = (if (yaml.isDouble("back.min-distance") || yaml.isInt("back.min-distance")) yaml.getDouble("back.min-distance") else d.backMinDistance).coerceIn(0.0, 10_000.0),
                backExcludedWorlds = worlds("back.excluded-worlds", d.backExcludedWorlds),
                backExpireSeconds = long("back.expire-seconds", d.backExpireSeconds),
                backRecordBackMoves = bool("back.record-back-moves", d.backRecordBackMoves),
                deathExcludedWorlds = worlds("death-back.excluded-worlds", d.deathExcludedWorlds),
                deathExpireSeconds = long("death-back.expire-seconds", d.deathExpireSeconds),
                blacklistEnabled = bool("blacklisted-items.enabled", d.blacklistEnabled),
                blacklistTp = if (yaml.isList("blacklisted-items.kinds")) {
                    yaml.getStringList("blacklisted-items.kinds").any { it.trim().equals(TP_KIND, ignoreCase = true) }
                } else {
                    d.blacklistTp
                },
                blacklistKinds = if (yaml.isList("blacklisted-items.kinds")) {
                    yaml.getStringList("blacklisted-items.kinds").mapNotNull { kind(it) }.toSet()
                } else {
                    d.blacklistKinds
                },
                blacklist = if (yaml.isList("blacklisted-items.items")) rules(yaml.getList("blacklisted-items.items").orEmpty()) else d.blacklist,
                effects = if (yaml.isConfigurationSection("effects")) effects(yaml.getConfigurationSection("effects")!!) else d.effects,
                commandEffects = if (yaml.isConfigurationSection("effects")) commandEffects(yaml.getConfigurationSection("effects")!!) else d.commandEffects,
                failSound = sound("fail-sound", d.failSound),
            )
        }

        fun kind(name: String?): TeleportKind? = runCatching { TeleportKind.valueOf(name.orEmpty().trim().uppercase().replace('-', '_')) }.getOrNull()

        /** `{월드: 초}` 또는 CMI 의 목록 꼴 `[월드-초]`(`월드:초` 도). 못 읽는 줄은 버린다([problems] 가 알린다). */
        private fun perWorld(yaml: YamlConfiguration, path: String): Map<String, Int> {
            if (yaml.isList(path)) return yaml.getStringList(path).mapNotNull { perWorldEntry(it) }.toMap()
            val section = yaml.getConfigurationSection(path) ?: return emptyMap()
            return section.getKeys(false).mapNotNull { world ->
                val seconds = section.getString(world)?.trim()?.toIntOrNull() ?: return@mapNotNull null
                world.lowercase() to seconds.coerceIn(0, 60)
            }.toMap()
        }

        /** CMI `world_nether-3` · `world_nether:3` — 마지막 `-`/`:` 뒤가 초. 못 읽으면 null. */
        fun perWorldEntry(raw: String?): Pair<String, Int>? {
            val text = raw?.trim().orEmpty()
            val cut = maxOf(text.lastIndexOf('-'), text.lastIndexOf(':'))
            if (cut <= 0) return null
            val seconds = text.substring(cut + 1).trim().toIntOrNull() ?: return null
            val world = text.substring(0, cut).trim().ifEmpty { return null }
            return world.lowercase() to seconds.coerceIn(0, 60)
        }

        /** 읽지 못하고 버린 값(순수) — 로드할 때 한 줄씩 경고로 남긴다(조용히 버리지 않는다). */
        fun problems(yaml: YamlConfiguration): List<String> {
            val out = ArrayList<String>()
            if (yaml.isList("back.causes")) {
                val names = yaml.getStringList("back.causes")
                val bad = names.filter { name -> runCatching { TeleportCause.valueOf(name.trim().uppercase()) }.isFailure }
                for (name in bad) out += "back.causes 의 '$name' 은(는) 없는 이동 원인입니다 — 건너뜁니다"
                if (names.isNotEmpty() && bad.size == names.size) out += "back.causes 에 읽을 수 있는 원인이 없어 /뒤로 에 아무것도 기록되지 않습니다"
            }
            if (yaml.isList("blacklisted-items.kinds")) {
                for (name in yaml.getStringList("blacklisted-items.kinds")) {
                    if (name.trim().equals(TP_KIND, ignoreCase = true)) continue
                    val k = kind(name)
                    if (k == null) {
                        out += "blacklisted-items.kinds 의 '$name' 은(는) 없는 종류입니다 — 건너뜁니다"
                    } else if (k in NEVER_BLACKLISTED) {
                        out += "blacklisted-items.kinds 의 '$name' 은(는) 금지 아이템을 보지 않는 종류입니다(관리자·감옥·다른 기능의 시스템 이동) — /이동·/좌표이동 은 TP 로 적으세요"
                    }
                }
            }
            val perWorldPath = "invulnerability.per-world"
            if (yaml.isList(perWorldPath)) {
                for (entry in yaml.getStringList(perWorldPath)) if (perWorldEntry(entry) == null) out += "$perWorldPath 의 '$entry' 을(를) 읽지 못했습니다(월드-초) — 건너뜁니다"
            } else {
                yaml.getConfigurationSection(perWorldPath)?.let { section ->
                    for (world in section.getKeys(false)) {
                        if (section.getString(world)?.trim()?.toIntOrNull() == null) out += "$perWorldPath.$world 의 값이 숫자가 아닙니다 — 건너뜁니다"
                    }
                }
            }
            if (yaml.isList("blacklisted-items.items")) {
                for (entry in yaml.getList("blacklisted-items.items").orEmpty()) {
                    if (rules(listOf(entry)).isEmpty()) out += "blacklisted-items.items 의 '$entry' 을(를) 읽지 못했습니다 — 건너뜁니다"
                }
            }
            yaml.getConfigurationSection("effects")?.let { section ->
                for (key in section.getKeys(false)) {
                    if (kind(key) == null && key.trim().uppercase() !in COMMAND_EFFECT_KEYS) out += "effects.$key 은(는) 없는 종류·명령입니다 — 건너뜁니다"
                }
            }
            return out
        }

        private fun commandEffects(section: ConfigurationSection): Map<String, KindEffect> {
            val out = LinkedHashMap<String, KindEffect>()
            for (key in section.getKeys(false)) {
                val name = key.trim().uppercase()
                if (name !in COMMAND_EFFECT_KEYS) continue
                val sub = section.getConfigurationSection(key) ?: continue
                val effect = KindEffect(ParticleSpec.parse(sub.getString("from")), ParticleSpec.parse(sub.getString("to")), SoundSpec.parse(sub.getString("sound")))
                if (effect != KindEffect()) out[name] = effect
            }
            return out
        }

        /** 줄마다 문자열(`iron_ore:5`) 또는 `{item: …, max: n}`. 못 읽는 줄은 버린다(로그는 load 가 남긴다). */
        fun rules(list: List<*>): List<BlacklistRule> = list.mapNotNull { entry ->
            when (entry) {
                is String -> BlacklistRule.parse(entry)
                is Map<*, *> -> {
                    val ref = entry["item"]?.toString()?.trim().orEmpty()
                    val max = entry["max"]?.toString()?.trim()?.toIntOrNull() ?: 0
                    if (ref.isEmpty()) null else BlacklistRule(ref, max.coerceAtLeast(0))
                }
                is ConfigurationSection -> {
                    val ref = entry.getString("item")?.trim().orEmpty()
                    if (ref.isEmpty()) null else BlacklistRule(ref, entry.getInt("max").coerceAtLeast(0))
                }
                else -> null
            }
        }

        private fun effects(section: ConfigurationSection): Map<TeleportKind, KindEffect> {
            val out = LinkedHashMap<TeleportKind, KindEffect>()
            for (key in section.getKeys(false)) {
                val kind = kind(key) ?: continue
                val sub = section.getConfigurationSection(key) ?: continue
                val effect = KindEffect(ParticleSpec.parse(sub.getString("from")), ParticleSpec.parse(sub.getString("to")), SoundSpec.parse(sub.getString("sound")))
                if (effect != KindEffect()) out[kind] = effect
            }
            return out
        }
    }
}
