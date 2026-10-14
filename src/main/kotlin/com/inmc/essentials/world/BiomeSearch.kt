package com.inmc.essentials.world

import com.inmc.essentials.Ess
import com.inmc.essentials.service.TeleportKind
import com.inmc.essentials.util.Ph
import org.bukkit.Bukkit
import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Biome
import org.bukkit.entity.Player
import org.bukkit.generator.BiomeProvider
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

/**
 * `/바이옴찾기` 의 엔진 — 사람마다 하나, 서버 전체 동시 [WorldSettings.FindBiome.maxConcurrent] 개.
 *
 * 찾기는 **워커 스레드**에서 `world.vanillaBiomeProvider().getBiome(...)` 로 나선을 돈다(청크를 읽지 않고 노이즈만 — inmc-menu RTP 가 운영 중
 * 같은 방식으로 다른 스레드에서 돈다). 맞는 칸을 찾으면 메인으로 넘겨 그 청크를 비동기로 읽고, **실제 블록의 생물군계를 다시 확인**한 뒤
 * 설 자리를 찾는다(노이즈와 이미 만들어진 청크가 다를 수 있다 — 데이터팩을 나중에 넣었거나 WorldEdit 으로 바꾼 곳). 못 찾으면 이어서 찾는다.
 *
 * 동굴 생물군계는 지표가 아니라 찾은 높이 근처의 빈 곳으로 보낸다(deep_dark 는 경고). **지붕이 있는 월드**(네더 — `World.hasCeiling`)는 모든 생물군계를
 * 동굴처럼 다루고 지붕 아래(`logicalHeight` 안)에서만 고른다 — 지표 높이맵이 기반암 천장을 가리켜 천장 위로 보내던 것(2026-10-10 리뷰).
 * 생성기가 붙은 월드(던전 VoidGenerator)는 거부한다 —
 * 그 월드의 vanillaBiomeProvider 는 생성기 뒤의 원래 바닐라 바이옴을 보고 엉뚱한 답을 낸다(research '# findbiome').
 */
class BiomeSearch(private val ess: Ess, private val settings: () -> WorldSettings, private val names: BiomeNames) {

    private class Session(
        val player: UUID,
        val world: World,
        val provider: BiomeProvider,
        val originX: Int,
        val originZ: Int,
        val step: Int,
        val ys: IntArray,
        val targets: Set<Biome>,
        val label: String,
        val display: String,
        val cave: Boolean,
        val deepDark: Boolean,
        val coordsOnly: Boolean,
        val spiral: Spiral,
        val deadline: Long,
    ) {
        val cancelled = AtomicBoolean(false)
        val startedAt = System.currentTimeMillis()

        @Volatile
        var lastProgress = startedAt
        var retries = 0
        val chunksChecked: Long get() = spiral.taken * step.toLong() * step / 256
    }

    private val sessions = ConcurrentHashMap<UUID, Session>()

    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "inmc-essentials-biome").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    val running: Int get() = sessions.size

    fun isSearching(id: UUID): Boolean = sessions.containsKey(id)

    /**
     * 찾기를 시작한다(메인). [targets] 는 같은 이름의 바이옴들(전부 한꺼번에 찾는다). 시작했으면 true — 실패 까닭은 직접 알린다.
     */
    fun start(player: Player, targets: List<BiomeNames.Entry>, radius: Int, coordsOnly: Boolean, label: String): Boolean {
        val s = settings().findBiome
        val world = player.world
        if (world.generator != null) {
            ess.messages.send(player, "world-biome-generator-world", Ph.of().world(world.name))
            return false
        }
        if (sessions.containsKey(player.uniqueId)) {
            ess.messages.send(player, "world-biome-already")
            return false
        }
        if (sessions.size >= s.maxConcurrent) {
            ess.messages.send(player, "world-biome-busy", Ph.of().count(sessions.size))
            return false
        }
        val provider = try {
            world.vanillaBiomeProvider()
        } catch (t: Throwable) {
            ess.messages.send(player, "world-biome-error", Ph.of().value(t.javaClass.simpleName))
            return false
        }
        val possible = runCatching { provider.getBiomes(world).toSet() }.getOrDefault(emptySet())
        val wanted = targets.filter { possible.isEmpty() || it.biome in possible }
        val display = targets.first().display
        if (wanted.isEmpty()) {
            ess.messages.send(player, "world-biome-not-in-world", Ph.of().raw("{이름}", display).world(world.name))
            return false
        }
        val cave = wanted.all { BiomeNames.isCave(it.key) } || world.hasCeiling()
        val ys = sampleHeights(world, cave)
        val loc = player.location
        val session = Session(
            player.uniqueId, world, provider, loc.blockX, loc.blockZ, s.step, ys, wanted.map { it.biome }.toSet(), label, display, cave,
            wanted.any { BiomeNames.isDeepDark(it.key) }, coordsOnly, Spiral((radius / s.step).coerceAtLeast(1)),
            System.currentTimeMillis() + s.maxSeconds * 1000L,
        )
        sessions[player.uniqueId] = session
        ess.messages.send(player, "world-biome-started", Ph.of().raw("{이름}", display).value(radius.toString()))
        submit(session)
        return true
    }

    /** 세로 표본(검증기도 부른다) — [heights]. */
    internal fun sampleHeights(world: World, cave: Boolean): IntArray =
        heights(world.minHeight, world.maxHeight, world.seaLevel, world.logicalHeight, cave, world.hasCeiling())

    private fun submit(session: Session) {
        executor.execute { search(session) }
    }

    /** 워커. 맞는 칸이면 메인으로 넘기고 끝난다(이어 찾기는 메인이 다시 [submit]). Bukkit API 는 쓰지 않는다(노이즈 공급자만). */
    private fun search(session: Session) {
        try {
            while (!session.cancelled.get()) {
                if (System.currentTimeMillis() > session.deadline) return main { finish(session, "world-biome-timeout") }
                val cell = session.spiral.next() ?: return main { finish(session, "world-biome-not-found") }
                val x = session.originX + cell.first * session.step
                val z = session.originZ + cell.second * session.step
                for (y in session.ys) {
                    val biome = session.provider.getBiome(session.world, x, y, z)
                    if (biome in session.targets) return main { verify(session, x, y, z) }
                }
            }
        } catch (t: Throwable) {
            ess.logger.log(Level.WARNING, "바이옴 찾기 중 오류", t)
            main { finish(session, "world-biome-error", t.javaClass.simpleName) }
        }
    }

    private fun main(block: () -> Unit) {
        if (!ess.plugin.isEnabled) return
        Bukkit.getScheduler().runTask(ess.plugin, Runnable { block() })
    }

    /** 메인 — 청크를 비동기로 읽고 실제 생물군계·설 자리를 확인한다. */
    private fun verify(session: Session, x: Int, y: Int, z: Int) {
        if (session.cancelled.get()) return
        val player = Bukkit.getPlayer(session.player)
        if (player == null || player.world != session.world) return finish(session, null)
        session.world.getChunkAtAsync(x shr 4, z shr 4, true).whenComplete { _, error ->
            main {
                if (session.cancelled.get()) return@main
                val spot = if (error == null) findSpot(session, x, y, z) else null
                if (spot != null) return@main arrive(session, spot)
                session.retries++
                if (session.retries > MAX_RETRIES) return@main finish(session, "world-biome-no-spot", "$x $z")
                submit(session)
            }
        }
    }

    /** 찾은 칸이 든 청크 안에서 [x],[z] 가까운 기둥부터 — 실제 생물군계가 맞고 설 수 있는 자리. */
    private fun findSpot(session: Session, x: Int, y: Int, z: Int): Location? {
        val world = session.world
        val baseX = x and 15.inv()
        val baseZ = z and 15.inv()
        val columns = (0 until 256).map { (baseX + (it and 15)) to (baseZ + (it shr 4)) }
            .sortedBy { (cx, cz) -> (cx - x).toLong() * (cx - x) + (cz - z).toLong() * (cz - z) }
        for ((cx, cz) in columns) {
            val spot = if (session.cave) caveSpot(world, cx, y, cz, session.targets) else surfaceSpot(world, cx, cz, session.targets)
            if (spot != null) return spot
        }
        return null
    }

    private fun surfaceSpot(world: World, x: Int, z: Int, targets: Set<Biome>): Location? {
        val top = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES)
        if (top <= world.minHeight) return null
        val floor = world.getBlockAt(x, top, z)
        if (floor.type in DANGER) return null
        val feet = world.getBlockAt(x, top + 1, z)
        val head = world.getBlockAt(x, top + 2, z)
        if (!feet.isPassable || !head.isPassable || feet.type == Material.LAVA) return null
        if (world.getBiome(x, top + 1, z) !in targets && world.getBiome(x, top, z) !in targets) return null
        return Location(world, x + 0.5, top + 1.0, z + 0.5)
    }

    private fun caveSpot(world: World, x: Int, y: Int, z: Int, targets: Set<Biome>): Location? {
        val top = standTop(world.minHeight, world.maxHeight, world.logicalHeight, world.hasCeiling())
        for (d in 0..CAVE_SPAN) {
            for (dy in if (d == 0) intArrayOf(0) else intArrayOf(d, -d)) {
                val fy = y + dy
                if (fy - 1 < world.minHeight || fy > top) continue
                val floor = world.getBlockAt(x, fy - 1, z)
                val feet = world.getBlockAt(x, fy, z)
                val head = world.getBlockAt(x, fy + 1, z)
                if (!floor.type.isSolid || floor.type in DANGER) continue
                if (!feet.isPassable || feet.isLiquid || !head.isPassable || head.isLiquid) continue
                if (world.getBiome(x, fy, z) !in targets) continue
                return Location(world, x + 0.5, fy.toDouble(), z + 0.5)
            }
        }
        return null
    }

    private fun arrive(session: Session, spot: Location) {
        val player = Bukkit.getPlayer(session.player) ?: return finish(session, null)
        val coords = Ph.of().raw("{이름}", session.display).raw("{x}", spot.blockX.toString()).raw("{y}", spot.blockY.toString()).raw("{z}", spot.blockZ.toString())
        if (session.coordsOnly) {
            ess.messages.send(player, "world-biome-found-coords", coords)
            return finish(session, null)
        }
        spot.yaw = player.location.yaw
        spot.pitch = player.location.pitch
        // 동굴·지붕 월드는 우리가 고른 빈 곳 그대로 — 이동 기능의 "안전한 자리" 가 지표(네더면 천장 위)로 끌어올리지 않게.
        ess.teleporter.teleport(player, spot, TeleportKind.BIOME, safe = !session.cave).whenComplete { ok, _ ->
            main {
                if (ok == true) {
                    ess.messages.send(player, "world-biome-teleported", coords)
                    if (session.deepDark && settings().findBiome.deepDarkWarning) ess.messages.send(player, "world-biome-deep-dark")
                    ess.gate?.commit(player, session.label)
                } else {
                    ess.messages.send(player, "world-biome-teleport-failed", coords)
                }
                finish(session, null)
            }
        }
    }

    /** 끝 — [key] 가 있으면 그 까닭을 알린다(확인한 청크 수와 함께). */
    private fun finish(session: Session, key: String?, value: String = "") {
        sessions.remove(session.player, session)
        session.cancelled.set(true)
        if (key == null) return
        val player = Bukkit.getPlayer(session.player) ?: return
        ess.messages.send(player, key, Ph.of().raw("{이름}", session.display).count(session.chunksChecked).value(value))
    }

    /** 내 찾기 멈춤. */
    fun stop(id: UUID): Boolean {
        val session = sessions.remove(id) ?: return false
        session.cancelled.set(true)
        return true
    }

    /** 전부 멈춤 — 멈춘 수. */
    fun stopAll(): Int {
        val all = sessions.values.toList()
        sessions.clear()
        for (s in all) {
            s.cancelled.set(true)
            Bukkit.getPlayer(s.player)?.let { ess.messages.send(it, "world-biome-stopped") }
        }
        return all.size
    }

    /** 1초 틱 — 진행 상황(TPS · 남은 칸 · 걸린 시간 · 속도). */
    fun tick(now: Long) {
        if (sessions.isEmpty()) return
        val every = settings().findBiome.progressEverySeconds * 1000L
        val tps = runCatching { Bukkit.getTPS()[0] }.getOrDefault(20.0).coerceAtMost(20.0)
        for (s in sessions.values) {
            if (now - s.lastProgress < every) continue
            s.lastProgress = now
            val player = Bukkit.getPlayer(s.player)
            if (player == null) {
                stop(s.player)
                continue
            }
            val elapsed = ((now - s.startedAt) / 1000).coerceAtLeast(1)
            val left = (s.spiral.total - s.spiral.taken).coerceAtLeast(0)
            // 채팅을 덮지 않게 행동 표시줄로(CMI 는 채팅이었다).
            player.sendActionBar(ess.messages.component("world-biome-progress", Ph.of().raw("{tps}", "%.1f".format(tps)).count(left)
                .time(kr.inmc.core.util.Durations.formatShort(elapsed)).raw("{속도}", (s.spiral.taken / elapsed).toString()), player))
        }
    }

    fun quit(id: UUID) {
        stop(id)
    }

    fun shutdown() {
        for (s in sessions.values) s.cancelled.set(true)
        sessions.clear()
        executor.shutdownNow()
    }

    companion object {
        /**
         * 세로 표본(순수 — `WorldRulesTest`). 동굴·지붕 월드는 바닥 위 8칸부터 16칸마다 — 동굴은 바다 높이까지, 지붕 월드는 지붕 아래([standTop])까지.
         * 지표는 바다 높이부터 위로 몇 층(산꼭대기 바이옴은 높은 곳에서만 나온다).
         */
        fun heights(min: Int, maxHeight: Int, sea: Int, logicalHeight: Int, cave: Boolean, ceiling: Boolean): IntArray {
            val max = maxHeight - 1
            val list = when {
                ceiling -> (min + 8..standTop(min, maxHeight, logicalHeight, true) step 16).toList()
                cave -> (min + 8..sea step 16).toList()
                else -> listOf(sea, sea + 32, sea + 64, sea + 96, sea + 128)
            }
            return list.filter { it in min..max }.ifEmpty { listOf(sea.coerceIn(min, max)) }.toIntArray()
        }

        /**
         * 발이 설 수 있는 가장 높은 y(순수). 머리 칸(y+1)이 월드 위쪽 안에 있어야 하고, 지붕 월드는 머리가 기반암 천장 층 아래까지 —
         * 네더(논리 높이 128)의 천장은 123~127 이라 발 121·머리 122 까지(천장 위나 천장 속 틈에 서지 못하게).
         */
        fun standTop(min: Int, maxHeight: Int, logicalHeight: Int, ceiling: Boolean): Int =
            if (ceiling) (min + logicalHeight - 7).coerceAtMost(maxHeight - 2) else maxHeight - 2

        const val MAX_RETRIES = 8
        const val CAVE_SPAN = 24
        val DANGER = setOf(Material.LAVA, Material.MAGMA_BLOCK, Material.FIRE, Material.SOUL_FIRE, Material.CACTUS, Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.WITHER_ROSE, Material.POINTED_DRIPSTONE)
    }
}
