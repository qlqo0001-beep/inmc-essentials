package com.inmc.essentials.spawn

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration

/**
 * 부활 위치 후보. 파일에는 [key] 로 적는다(순서값을 저장하지 않는다 — 지뢰 5). inmc-menu 의 옛 파일(bed·anchor·server·world)도 그대로 읽힌다.
 * 침대 홈·기본 홈·워프는 CMI `ReSpawn.PriorityOrder` 의 bedLocation·homeLocation·warp! 에 해당한다(카탈로그 cfg1:respawn-handling).
 */
enum class Source(val key: String, val label: String, val description: String) {
    BED("bed", "침대", "그 사람이 잔 침대가 남아 있으면(바닐라)"),
    ANCHOR("anchor", "리스폰 앵커", "충전된 앵커가 남아 있으면"),
    SERVER("server", "서버 스폰", "/스폰 설정 으로 정한 곳"),
    BED_HOME("bed-home", "침대 홈", "침대를 눌러 만든 홈(/홈)"),
    HOME("home", "기본 홈", "기본 이름의 홈 — 없으면 첫 홈"),
    WARP("warp", "부활 워프", "관리 화면에서 고른 워프(권한 없이)"),
    WORLD("world", "월드 스폰", "죽은 월드의 스폰(네더·엔드면 기본 월드)"),
    ;

    companion object {
        /** 이름 읽기 — 키·enum 이름 둘 다, 대소문자·`-`/`_` 무시. */
        fun parse(raw: String): Source? {
            val k = raw.trim().lowercase().replace('_', '-')
            return entries.firstOrNull { it.key == k || it.name.lowercase().replace('_', '-') == k }
        }
    }
}

/** 부활 순서의 한 칸 — 끈 것은 건너뛴다. */
data class Step(val source: Source, val enabled: Boolean = true)

/** 스폰 자리 — 월드는 이름으로(쓸 때 찾는다 — 월드가 늦게 열려도). */
data class SpawnPoint(val world: String, val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float) {

    fun location(): Location? = Bukkit.getWorld(world)?.let { Location(it, x, y, z, yaw, pitch) }

    fun describe(): String = "$world ${x.toInt()}, ${y.toInt()}, ${z.toInt()}"

    companion object {
        fun of(location: Location): SpawnPoint =
            SpawnPoint(location.world.name, location.x, location.y, location.z, location.yaw, location.pitch)

        /**
         * CMI 위치 글자 — config 는 `world;x;y;z;yaw;pitch`, DB 는 `world:x:y:z:yaw:pitch`. yaw·pitch 는 없어도 된다.
         * 못 읽으면 null(순수 — `SpawnConfigTest`).
         */
        fun parseCmi(raw: String?): SpawnPoint? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            val parts = text.split(if (';' in text) ';' else ':')
            if (parts.size < 4 || parts[0].isBlank()) return null
            val x = parts[1].trim().toDoubleOrNull() ?: return null
            val y = parts[2].trim().toDoubleOrNull() ?: return null
            val z = parts[3].trim().toDoubleOrNull() ?: return null
            val yaw = parts.getOrNull(4)?.trim()?.toFloatOrNull() ?: 0f
            val pitch = parts.getOrNull(5)?.trim()?.toFloatOrNull() ?: 0f
            return SpawnPoint(parts[0].trim(), x, y, z, yaw, pitch)
        }

        internal fun read(y: YamlConfiguration, path: String): SpawnPoint? =
            y.getString("$path.world")?.takeIf { it.isNotBlank() }?.let {
                SpawnPoint(it, y.getDouble("$path.x"), y.getDouble("$path.y"), y.getDouble("$path.z"),
                    y.getDouble("$path.yaw").toFloat(), y.getDouble("$path.pitch").toFloat())
            }

        internal fun write(y: YamlConfiguration, path: String, p: SpawnPoint?) {
            if (p == null) return
            y.set("$path.world", p.world)
            y.set("$path.x", p.x)
            y.set("$path.y", p.y)
            y.set("$path.z", p.z)
            y.set("$path.yaw", p.yaw.toDouble())
            y.set("$path.pitch", p.pitch.toDouble())
        }
    }
}

/** CMI 부활 순서를 읽은 것 — [unknown] 은 옮기지 못한 칸(보고). */
data class CmiOrder(val steps: List<Step>, val warp: String?, val unknown: List<String>)

/** 접속할 때 어디로 — [SpawnConfig.joinTarget] 의 답. */
enum class JoinTarget { NONE, FIRST, SERVER }

/**
 * `spawn.yml`(데이터 폴더 바로 아래 — inmc-menu 와 같은 형식에 키를 더했다) — 서버 스폰·첫 스폰·접속 때 보내기·부활 순서.
 * 게임 안 `/스폰 관리` 에서 고치면 통째로 다시 쓴다. 대기·쿨타임은 여기 없다 — commands 기능이 명령 이름(`스폰`)으로 건다.
 */
data class SpawnConfig(
    val point: SpawnPoint? = null,
    /** 처음 들어온 사람이 설 곳(CMI setfirstspawn). 없으면 [point]. */
    val firstPoint: SpawnPoint? = null,
    /** 처음 들어온 사람을 첫 스폰으로. */
    val firstJoin: Boolean = true,
    /** 들어올 때마다 서버 스폰으로(CMI SpawnOnJoin). */
    val everyJoin: Boolean = false,
    val respawn: List<Step> = DEFAULT_ORDER,
    /** 부활 순서의 워프 칸이 쓸 워프 이름. */
    val respawnWarp: String = "",
    /** `/스폰`·부활 때 서버 스폰 둘레로 흩는 반경(블록, CMI Spawn.Main.Rng). 0 이면 그 자리. */
    val radius: Int = 0,
    /** CMI config 의 스폰을 한 번 가져왔나 — 두 번 덮지 않게. */
    val cmiImported: Boolean = false,
) {

    /** 순서대로 처음 되는 것. 다 안 되면 null(바닐라 그대로). [available] 은 앞 칸부터 차례로만 불린다(뒤 칸의 홈 찾기를 아끼게). */
    fun choose(available: (Source) -> Boolean): Source? =
        respawn.firstOrNull { it.enabled && (it.source == Source.WORLD || available(it.source)) }?.source

    /** 시험·검증기용 — 칸마다 되는지를 값으로. */
    fun choose(bed: Boolean, anchor: Boolean, hasServerSpawn: Boolean, bedHome: Boolean = false, home: Boolean = false, warp: Boolean = false): Source? =
        choose { source ->
            when (source) {
                Source.BED -> bed
                Source.ANCHOR -> anchor
                Source.SERVER -> hasServerSpawn
                Source.BED_HOME -> bedHome
                Source.HOME -> home
                Source.WARP -> warp
                Source.WORLD -> true
            }
        }

    /**
     * 접속 자리(순수). [isNew] = 처음 들어온 사람(`AsyncPlayerSpawnLocationEvent.isNewPlayer`), [fromWorld] = 나갈 때 있던 월드,
     * [ignoredWorlds] = 매 접속 보내기에서 뺄 월드(CMI IgnoredWorlds). [modes] = 모드가 있다(모드 스폰이 서버 스폰을 대신한다 — 2026-10-15).
     */
    fun joinTarget(isNew: Boolean, fromWorld: String?, ignoredWorlds: Set<String>, modes: Boolean = false): JoinTarget = when {
        isNew && firstJoin && (firstPoint != null || point != null || modes) -> JoinTarget.FIRST
        everyJoin && (point != null || modes) && (fromWorld == null || ignoredWorlds.none { it.equals(fromWorld, ignoreCase = true) }) -> JoinTarget.SERVER
        else -> JoinTarget.NONE
    }

    /** [index] 칸을 [delta] 만큼 옮긴다(끝에서는 그대로). */
    fun move(index: Int, delta: Int): SpawnConfig {
        val target = index + delta
        if (index !in respawn.indices || target !in respawn.indices) return this
        val list = respawn.toMutableList()
        list.add(target, list.removeAt(index))
        return copy(respawn = list)
    }

    fun toggle(index: Int): SpawnConfig =
        copy(respawn = respawn.mapIndexed { i, step -> if (i == index) step.copy(enabled = !step.enabled) else step })

    fun toYaml(): YamlConfiguration = YamlConfiguration().also { y ->
        y.options().setHeader(HEADER.lines())
        SpawnPoint.write(y, "spawn", point)
        SpawnPoint.write(y, "first-spawn", firstPoint)
        y.set("first-join", firstJoin)
        y.set("every-join", everyJoin)
        y.set("respawn-order", respawn.map { (if (it.enabled) "" else "-") + it.source.key })
        y.set("respawn-warp", respawnWarp)
        y.set("radius", radius)
        y.set("cmi-imported", cmiImported)
    }

    companion object {

        val DEFAULT_ORDER: List<Step> = listOf(
            Step(Source.BED), Step(Source.ANCHOR), Step(Source.SERVER),
            Step(Source.BED_HOME, false), Step(Source.HOME, false), Step(Source.WARP, false), Step(Source.WORLD),
        )

        const val MAX_RADIUS = 1000

        val HEADER = """
            inmc-essentials 스폰. 게임 안 /스폰 관리 에서 고치는 것이 편합니다(고치면 이 파일을 다시 씁니다).
            손으로 고쳤으면 바로 /에센셜 리로드 — 바뀐 파일을 다시 읽습니다. 리로드 전에 게임 안에서 고치면 손으로 고친 것이 덮입니다.
            respawn-order: 죽었을 때 위에서부터 처음 되는 곳으로. 앞에 - 를 붙이면 끈 것. 다 안 되면 바닐라 그대로.
              bed(침대) · anchor(리스폰 앵커) · server(서버 스폰) · bed-home(침대 홈) · home(기본 홈) · warp(respawn-warp 의 워프)
              · world(죽은 월드의 스폰 — 네더·엔드면 기본 월드)
            /스폰 의 대기·쿨타임·비용은 commands 기능 설정에서 명령 이름(스폰)으로 겁니다.
            CMI 같은 다른 플러그인의 스폰·부활 처리는 꺼 두세요 — 둘이 같은 사건을 고칩니다.
        """.trimIndent()

        fun from(y: YamlConfiguration): SpawnConfig = SpawnConfig(
            point = SpawnPoint.read(y, "spawn"),
            firstPoint = SpawnPoint.read(y, "first-spawn"),
            firstJoin = y.getBoolean("first-join", true),
            everyJoin = y.getBoolean("every-join", false),
            respawn = parseOrder(y.getStringList("respawn-order")),
            respawnWarp = y.getString("respawn-warp", "")!!.trim(),
            radius = y.getInt("radius", 0).coerceIn(0, MAX_RADIUS),
            cmiImported = y.getBoolean("cmi-imported", false),
        )

        /**
         * CMI `ReSpawn.Global.PriorityOrder` → 우리 순서(순수). anchor·bedLocation(바닐라 침대)·spawn·homeLocation·worldSpawn·`warp!이름`.
         * [spawnRespawn] = CMI `Spawn.Main.RespawnLocation` — 거짓이면 CMI 는 spawn 칸을 건너뛰므로 끈 채로 둔다. 빈 목록이면 기본 순서.
         */
        fun fromCmiOrder(raw: List<String>, spawnRespawn: Boolean): CmiOrder {
            val steps = ArrayList<Step>()
            var warp: String? = null
            val unknown = ArrayList<String>()
            for (entry in raw) {
                val text = entry.trim()
                val lower = text.lowercase()
                val step = when {
                    lower == "anchor" -> Step(Source.ANCHOR)
                    lower == "bedlocation" -> Step(Source.BED)
                    lower == "spawn" -> Step(Source.SERVER, spawnRespawn)
                    lower == "homelocation" -> Step(Source.HOME)
                    lower == "worldspawn" -> Step(Source.WORLD)
                    lower.startsWith("warp!") -> Step(Source.WARP).also { if (warp == null) warp = text.substringAfter('!').trim() }
                    else -> null.also { unknown += text }
                }
                if (step != null && steps.none { it.source == step.source }) steps += step
            }
            if (steps.isEmpty()) return CmiOrder(DEFAULT_ORDER, null, unknown)
            return CmiOrder(steps + Source.entries.filter { s -> steps.none { it.source == s } }.map { Step(it, enabled = false) }, warp, unknown)
        }

        /** 모르는 이름은 버리고, 빠진 것은 끈 채로 끝에 — 칸이 늘 다 있다(옛 inmc-menu 파일은 새 칸 셋이 꺼진 채 붙는다). */
        fun parseOrder(raw: List<String>): List<Step> {
            if (raw.isEmpty()) return DEFAULT_ORDER
            val steps = raw.mapNotNull { entry ->
                val off = entry.trim().startsWith("-")
                Source.parse(entry.trim().removePrefix("-"))?.let { Step(it, !off) }
            }.distinctBy { it.source }
            return steps + Source.entries.filter { s -> steps.none { it.source == s } }.map { Step(it, enabled = false) }
        }
    }
}

/**
 * `settings/spawn.yml` — 운영 설정(게임 안에서 고치지 않는 것). [from] 은 던지지 않는다 — 이상한 값은 기본값으로(규칙 12).
 */
data class SpawnSettings(
    /** 접속 직후 무적(초, CMI Optimizations.ImmortalityOnJoin). 0 = 끔, 최대 60. */
    val joinInvulnerable: Int = 3,
    /** 부활 직후 무적(초, CMI ReSpawn.Immortality). 0 = 끔, 최대 60. */
    val respawnInvulnerable: Int = 3,
    /** false 면 부활 자리를 건드리지 않는다(CMI ReSpawn.Enabled — 다른 플러그인에 맡김). */
    val handleRespawn: Boolean = true,
    /** 첫 접속 킷(kits 기능의 `giveFirstJoin`)을 부르나. */
    val firstJoinKit: Boolean = true,
    /** 매 접속 스폰에서 뺄 월드(CMI Spawn.IgnoredWorlds). */
    val ignoredWorlds: Set<String> = emptySet(),
) {
    companion object {
        const val MAX_INVULNERABLE = 60

        fun from(y: YamlConfiguration): SpawnSettings = SpawnSettings(
            joinInvulnerable = y.getInt("join-invulnerable-seconds", 3).coerceIn(0, MAX_INVULNERABLE),
            respawnInvulnerable = y.getInt("respawn-invulnerable-seconds", 3).coerceIn(0, MAX_INVULNERABLE),
            handleRespawn = y.getBoolean("handle-respawn", true),
            firstJoinKit = y.getBoolean("first-join-kit", true),
            ignoredWorlds = y.getStringList("ignored-worlds").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        )
    }
}
