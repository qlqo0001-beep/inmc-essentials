package com.inmc.essentials.spawn

import com.inmc.essentials.Ess
import kr.inmc.core.integration.Realms
import org.bukkit.Bukkit
import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 스폰의 상태 — 설정 한 벌(`@Volatile`: 접속 자리 사건은 비동기라 읽기만 한다), 접속·부활 무적, 첫 접속 표시.
 * 고치면 메모리를 먼저 바꾸고 파일은 I/O 워커에서(`ConfigService.save` 는 원자적 — 지뢰 10).
 */
class SpawnService(private val ess: Ess, private val feature: SpawnFeature) {

    @Volatile
    var config: SpawnConfig = SpawnConfig()
        private set

    val file: File get() = ess.io.file(FILE)

    /** 마지막으로 읽었거나 우리가 쓴 뒤의 파일 시각 — 손으로 고친 것을 알아본다([reloadIfChanged]). */
    @Volatile
    private var stamp = 0L

    /** 아직 끝나지 않은 우리 쓰기 수 — 쓰는 중에는 다시 읽지 않는다. */
    private val writing = AtomicInteger()

    /** 무적이 끝나는 시각(밀리초). 피해 사건(메인)과 접속·부활(메인)에서만 만진다 — 그래도 맵은 동시성 안전한 것으로. */
    private val invulnerableUntil = ConcurrentHashMap<UUID, Long>()

    /** `AsyncPlayerSpawnLocationEvent.isNewPlayer` 가 true 였던 사람 — 접속 사건에서 꺼낸다(킷·로그). */
    val newPlayers: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /**
     * 매 접속 스폰으로 옮긴 사람 — [JoinMove.original] 은 우리가 바꾸기 **전**의 접속 자리(= 서버가 저장해 둔 나간 자리, 튕긴 뒤에도 맞다).
     * 접속 사건에서 우회 권한을 보고 그리로 되돌린다(비동기 사건에는 권한이 없다). 뒤의 플러그인(던전 HIGHEST)이 자리를 다시 바꿨으면
     * MONITOR 에서 뺀다 — 그 플러그인의 결정을 되돌리지 않게.
     */
    val movedOnJoin: ConcurrentHashMap<UUID, JoinMove> = ConcurrentHashMap()

    data class JoinMove(val original: Location, val ours: Location)

    /** 켤 때(메인). 파일이 없으면 기본값을 쓴다. */
    fun loadNow() {
        if (file.isFile) {
            stamp = file.lastModified()
            config = SpawnConfig.from(ess.io.load(file))
        } else {
            config = SpawnConfig().also { save(it) }
        }
    }

    /**
     * `/에센셜 리로드` — 손으로 고친 `spawn.yml` 을 다시 읽는다. 시각·쓰기 중 검사만 메인에서, **읽기는 I/O 워커**, 반영은 메인에서(메인에서 파일을
     * 읽지 않는다). 우리 쓰기 중이거나 읽는 사이 게임 안에서 고쳐 스냅샷이 바뀌었으면 반영하지 않고 로그만 남긴다.
     */
    fun reloadIfChanged() {
        if (!file.isFile || file.lastModified() == stamp) return
        if (writing.get() > 0) {
            ess.logger.warning("spawn.yml 을 쓰는 중이라 다시 읽지 않았습니다 — 잠시 뒤 다시 리로드하세요")
            return
        }
        val before = config
        ess.io.async({ file.lastModified() to SpawnConfig.from(ess.io.load(file)) }) { (modified, read) ->
            if (config !== before || writing.get() > 0) {
                ess.logger.warning("spawn.yml 을 다시 읽는 사이 게임 안에서 스폰을 고쳐 손으로 고친 것을 반영하지 않았습니다 — 잠시 뒤 다시 리로드하세요")
                return@async
            }
            stamp = modified
            config = read
            ess.logger.info("손으로 고친 spawn.yml 을 다시 읽었습니다")
        }
    }

    /** 검증기 — 파일에 쓰지 않고 잠깐 바꿨다가 되돌린다. */
    fun swap(value: SpawnConfig) {
        config = value
    }

    fun update(change: (SpawnConfig) -> SpawnConfig) {
        config = change(config)
        save(config)
    }

    private fun save(value: SpawnConfig) {
        val yaml = value.toYaml()
        writing.incrementAndGet()
        ess.io.asyncRun {
            try {
                ess.io.save(file, yaml)
                stamp = file.lastModified()
            } finally {
                writing.decrementAndGet()
            }
        }
    }

    // --- 자리 ----------------------------------------------------------------------------------

    /** 서버 스폰 — 정하지 않았거나 그 월드가 없으면 기본 월드의 스폰. */
    fun target(): Location = config.point?.location() ?: worldSpawn(Bukkit.getWorlds().first())

    /** 이 사람의 스폰 — 모드가 있으면(core `Realms` — inmc-worlds, 2026-10-15) 그 사람 모드의 스폰, 없으면 서버 스폰. */
    fun target(player: Player): Location = modeSpawn(player.uniqueId) ?: target()

    /** 이 사람 모드의 스폰. 모드가 없으면 null. 아무 스레드(모드는 메모리, 자리는 월드 이름으로 찾는다). */
    fun modeSpawn(id: UUID): Location? = Realms.of(id)?.let(Realms::spawn)

    /** 같은 모드의 자리인가 — 빌려 쓰는 월드·모드 없음은 같다고 본다. 부활 순서가 다른 모드의 홈·워프로 보내지 않게. */
    fun sameMode(player: Player, at: Location): Boolean {
        val mode = at.world?.let(Realms::ofWorld) ?: return true
        return mode == Realms.of(player)
    }

    /** 첫 스폰 — 따로 정하지 않았으면 그 사람 모드의 스폰(모드가 있으면), 아니면 서버 스폰. 다 없거나 월드가 없으면 null. */
    fun firstTarget(id: UUID? = null): Location? = config.firstPoint?.location() ?: id?.let(::modeSpawn) ?: config.point?.location()

    /** 월드 스폰 — 블록 가운데. 네더·엔드면 기본 월드의 것. */
    fun worldSpawn(world: World): Location {
        val base = if (world.environment == World.Environment.NORMAL) world else Bukkit.getWorlds().first()
        return base.spawnLocation.toCenterLocation().apply { y = base.spawnLocation.y }
    }

    /**
     * `/스폰` 의 흩기 — [base] 둘레 [radius] 안의 칸을 골라 **청크를 비동기로 올린 뒤** 땅 위를 고른다(메인 스레드에서 끝난다).
     * 맨 위가 물·용암·위험한 블록이면 다른 칸을 몇 번 더, 그래도 안 되면 [base].
     */
    fun scatterAsync(base: Location, radius: Int): CompletableFuture<Location> {
        if (radius <= 0) return CompletableFuture.completedFuture(base)
        val result = CompletableFuture<Location>()
        fun attempt(left: Int) {
            if (left <= 0) {
                result.complete(base)
                return
            }
            val (x, z) = randomColumn(base, radius)
            base.world.getChunkAtAsync(floor(x).toInt() shr 4, floor(z).toInt() shr 4).whenComplete { _, error ->
                val found = if (error == null) runCatching { ground(base, x, z) }.getOrNull() else null
                if (found != null) result.complete(found) else attempt(left - 1)
            }
        }
        attempt(ASYNC_TRIES)
        return result
    }

    /**
     * 부활의 흩기 — 부활 사건 안이라 청크를 읽지 않는다: **이미 올라와 있는** 칸만 몇 번 골라 보고, 안 되면 [base].
     * (안전 검사가 없는 부활 길이라 물·용암 위는 고르지 않는다.)
     */
    fun scatterLoaded(base: Location, radius: Int): Location {
        if (radius <= 0) return base
        repeat(LOADED_TRIES) {
            val (x, z) = randomColumn(base, radius)
            if (!base.world.isChunkLoaded(floor(x).toInt() shr 4, floor(z).toInt() shr 4)) return@repeat
            ground(base, x, z)?.let { return it }
        }
        return base
    }

    private fun randomColumn(base: Location, radius: Int): Pair<Double, Double> {
        val random = ThreadLocalRandom.current()
        return column(base.blockX, base.blockZ, radius, random.nextDouble(), random.nextDouble())
    }

    /**
     * 그 칸의 땅 위 — 맨 위 블록이 단단하고 위험하지 않고, 그 위 두 칸(발·머리)에 서도 될 때만([standable]). 청크가 올라와 있어야 한다(메인).
     * MOTION_BLOCKING 높이는 불·덤불·위더 장미·고인 물처럼 지나갈 수 있는 것을 세지 않는다 — 맨 위 블록 위를 따로 본다.
     */
    private fun ground(base: Location, x: Double, z: Double): Location? {
        val top = base.world.getHighestBlockAt(floor(x).toInt(), floor(z).toInt(), HeightMap.MOTION_BLOCKING)
        if (top.isLiquid || !top.type.isSolid || top.type in DANGER) return null
        if (!standable(listOf(top.getRelative(BlockFace.UP).type, top.getRelative(BlockFace.UP, 2).type)) { it.isSolid }) return null
        return base.clone().apply {
            this.x = x
            this.z = z
            y = top.y + 1.0
        }
    }

    /**
     * 부활 자리 — 순서대로 처음 되는 곳. null 이면 바닐라 그대로(침대·앵커가 이겼거나 다 꺼짐).
     * 홈·워프 칸은 그 기능이 창구를 걸었을 때만 된다([SpawnFeature.homes]·[SpawnFeature.warps]).
     */
    fun respawnLocation(player: Player, bed: Boolean, anchor: Boolean): Location? {
        val c = config
        val homes = feature.homes
        val warps = feature.warps
        var found: Location? = null
        val source = c.choose { source ->
            when (source) {
                Source.BED -> bed
                Source.ANCHOR -> anchor
                Source.SERVER -> (modeSpawn(player.uniqueId) ?: c.point?.location()).also { found = it } != null
                Source.BED_HOME -> homes?.bedHome(player)?.takeIf { sameMode(player, it) }.also { found = it } != null
                Source.HOME -> homes?.defaultHome(player)?.takeIf { sameMode(player, it) }.also { found = it } != null
                Source.WARP -> c.respawnWarp.isNotBlank() && warps?.warp(c.respawnWarp)?.takeIf { sameMode(player, it) }.also { found = it } != null
                Source.WORLD -> true
            }
        }
        return when (source) {
            null, Source.BED, Source.ANCHOR -> null
            Source.SERVER -> scatterLoaded(found!!, c.radius)
            Source.WORLD -> worldSpawn(player.world)
            else -> found
        }
    }

    // --- 무적 ----------------------------------------------------------------------------------

    fun protect(id: UUID, seconds: Int) {
        if (seconds <= 0) return
        invulnerableUntil[id] = System.currentTimeMillis() + seconds * 1000L
    }

    fun isProtected(id: UUID): Boolean {
        val until = invulnerableUntil[id] ?: return false
        if (System.currentTimeMillis() < until) return true
        invulnerableUntil.remove(id, until)
        return false
    }

    /** 검증기 — 무적 상태를 메모리에서만 바꿨다 되돌린다. */
    internal fun invulnerableSnapshot(id: UUID): Long? = invulnerableUntil[id]

    internal fun restoreInvulnerable(id: UUID, until: Long?) {
        if (until == null) invulnerableUntil.remove(id) else invulnerableUntil[id] = until
    }

    fun forget(id: UUID) {
        invulnerableUntil.remove(id)
        newPlayers.remove(id)
        movedOnJoin.remove(id)
    }

    fun shutdown() {
        invulnerableUntil.clear()
        newPlayers.clear()
        movedOnJoin.clear()
    }

    companion object {
        const val FILE = "spawn.yml"

        private const val ASYNC_TRIES = 4
        private const val LOADED_TRIES = 8

        /** 서면 다치는 맨 위(단단한) 블록. */
        private val DANGER = setOf(
            Material.MAGMA_BLOCK, Material.CACTUS, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.POWDER_SNOW,
            Material.POINTED_DRIPSTONE, Material.SWEET_BERRY_BUSH, Material.FIRE, Material.SOUL_FIRE, Material.LAVA,
        )

        /** 지나갈 수 있어 MOTION_BLOCKING 높이에 안 잡히지만 그 안에 서면 다치는 것(물은 땅 위에 고인 것 — 부활 길에는 안전 검사가 없다). */
        private val HARMFUL_ABOVE = setOf(
            Material.FIRE, Material.SOUL_FIRE, Material.WITHER_ROSE, Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW,
            Material.WATER, Material.LAVA, Material.BUBBLE_COLUMN,
        )

        /**
         * 땅 위의 칸들([above] — 발·머리)에 서도 되는가: 단단하지 않고 [HARMFUL_ABOVE] 가 아닐 때. [solid] 는 밖에서 받는다 — `Material.isSolid` 는
         * 이 API 판에서 레지스트리를 거쳐 서버 없이 못 부른다(`SpawnMigrationTest` 가 가짜를 넘긴다).
         */
        fun standable(above: List<Material>, solid: (Material) -> Boolean): Boolean = above.all { !solid(it) && it !in HARMFUL_ABOVE }

        /** [baseX]·[baseZ] 블록 가운데 둘레 [radius] 원 안의 한 점 — [r1]·[r2] 는 0..1 난수(순수 — `SpawnConfigTest`). 원 안에 고르게. */
        fun column(baseX: Int, baseZ: Int, radius: Int, r1: Double, r2: Double): Pair<Double, Double> {
            val angle = r1 * Math.PI * 2
            val distance = radius * sqrt(r2)
            return (baseX + 0.5 + cos(angle) * distance) to (baseZ + 0.5 + sin(angle) * distance)
        }
    }
}
