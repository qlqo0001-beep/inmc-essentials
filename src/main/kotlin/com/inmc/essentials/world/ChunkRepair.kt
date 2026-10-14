package com.inmc.essentials.world

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import kr.inmc.core.util.Durations
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level

/**
 * `/청크복구` — 지역 파일을 **읽기만** 해서 서버가 못 읽을 청크를 찾고([RegionFiles]), `복구` 로 그 청크들을 읽게 해 서버가 새로 만들게 한다.
 *
 * 서버에 청크 다시 만들기 API 가 없다(`World.regenerateChunk` 는 Paper 에서 "Not supported in this Minecraft version" 을 던진다 — leaf-api
 * 26.2 javap). 대신 Moonrise 는 못 읽은 청크를 **새로 만든다**("chunk data will be lost") — 그래서 복구 = 찾은 청크를 비동기로 읽고 내려놓기.
 * 멀쩡한 청크를 읽어도 아무 일이 없으니 검사가 틀려도 해가 없다. 찾기는 월드마다 하나, 워커 스레드(메인을 막지 않음).
 */
class ChunkRepair(private val ess: Ess, private val settings: () -> WorldSettings) {

    data class Found(val x: Int, val z: Int, val reason: String)

    class Scan(
        val world: String,
        val owner: UUID?,
        val regionDir: File,
        /** 지역 → 볼 칸 번호들(가운데 가까운 지역부터). */
        val plan: List<Pair<File, IntArray>>,
        val total: Long,
        val range: String,
        @Volatile var speed: Int,
        @Volatile var autoSpeed: Boolean,
        @Volatile var messages: Boolean,
    ) {
        val checked = AtomicLong()
        val found = CopyOnWriteArrayList<Found>()
        val unsupported = AtomicLong()

        @Volatile var paused = false
        @Volatile var stopped = false
        @Volatile var finished = false
        @Volatile var lastProgress = 0L
        val startedAt = System.currentTimeMillis()
        var pausedMillis = 0L
        var pausedAt = 0L

        val elapsedSeconds: Long get() = ((System.currentTimeMillis() - startedAt - pausedMillis) / 1000).coerceAtLeast(1)
    }

    private val scans = ConcurrentHashMap<String, Scan>()

    /** 끝난 찾기도 정보·복구를 위해 남겨 둔다(월드마다 마지막 것). */
    private val results = ConcurrentHashMap<String, Scan>()

    @Volatile
    private var tps = 20.0

    /**
     * 복구 요청 하나 — 부른 사람마다 따로 센다(둘이 겹쳐도 각자 끝 알림을 받는다). 알림은 그 요청의 **모든 청크 읽기가 끝난 뒤**
     * (대기열이 빈 순간이 아니라 — 마지막 몇 개는 아직 읽는 중이었다 — 2026-10-10 리뷰).
     */
    private class FixRequest(val sender: CommandSender, val total: Int) {
        var done = 0
    }

    private data class FixItem(val world: String, val x: Int, val z: Int, val request: FixRequest)

    /** 복구 대기열(메인). 1초에 [FIX_PER_SECOND] 개씩. */
    private val fixQueue = ArrayDeque<FixItem>()

    fun running(): Collection<Scan> = scans.values

    fun scanOf(world: String): Scan? = scans[world.lowercase()] ?: results[world.lowercase()]

    /** 지역 파일 폴더 후보 — Paper 26 은 본 월드 안 `dimensions/<ns>/<이름>` 에 둔다(지뢰 22). 경로만 짓는다(메인 — 파일을 건드리지 않는다). */
    fun regionCandidates(world: World): List<File> {
        val folder = world.worldFolder
        val key = world.key
        return listOf(
            File(folder, "dimensions/${key.namespace}/${key.key}/region"),
            File(folder, "region"),
            File(folder, "DIM-1/region"),
            File(folder, "DIM1/region"),
        )
    }

    /** .mca 가 있는 첫 후보(워커 — 폴더를 훑는다. 지역 파일이 수만 개인 월드도 있어 메인에서 부르지 않는다). */
    private fun findRegionDir(candidates: List<File>): File? =
        candidates.firstOrNull { dir -> dir.isDirectory && dir.list()?.any { it.endsWith(".mca") } == true }

    /**
     * 찾기 시작(메인). [range] 청크 반경(null = 월드 전체), [center] 가운데 청크. 계획은 파일 이름만으로 세운다(파일 내용은 워커가 읽는다).
     */
    fun start(sender: CommandSender, world: World, range: Int?, center: Pair<Int, Int>) {
        val key = world.name.lowercase()
        if (scans.containsKey(key)) return ess.messages.send(sender, "world-fix-already", Ph.of().world(world.name))
        val candidates = regionCandidates(world)
        val s = settings().fixChunk
        ess.io.async({ findRegionDir(candidates)?.let { dir -> plan(dir, range, center).let { (p, t) -> Triple(dir, p, t) } } }) { found ->
            val (dir, plan, total) = found ?: return@async ess.messages.send(sender, "world-fix-no-region", Ph.of().world(world.name))
            if (scans.containsKey(key)) return@async ess.messages.send(sender, "world-fix-already", Ph.of().world(world.name))
            val scan = Scan(world.name, (sender as? Player)?.uniqueId, dir, plan, total, range?.toString() ?: "전체", s.speed, s.autoSpeed, s.messages)
            scans[key] = scan
            results[key] = scan
            ess.messages.send(sender, "world-fix-started", Ph.of().count(total).world(world.name))
            Thread({ run(scan) }, "inmc-essentials-fixchunk").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        }
    }

    /** 파일 이름으로 계획을 세운다(워커). */
    private fun plan(dir: File, range: Int?, center: Pair<Int, Int>): Pair<List<Pair<File, IntArray>>, Long> {
        val files = dir.listFiles { f -> f.isFile && RegionFiles.regionOf(f.name) != null }.orEmpty()
        val (cx, cz) = center
        val out = ArrayList<Pair<File, IntArray>>()
        var total = 0L
        for (file in files.sortedBy { f -> RegionFiles.regionOf(f.name)!!.let { (rx, rz) -> (rx * 32 + 16 - cx).toLong().let { it * it } + (rz * 32 + 16 - cz).toLong().let { it * it } } }) {
            val (rx, rz) = RegionFiles.regionOf(file.name)!!
            val indices = (0 until 1024).filter { i ->
                range == null || (kotlin.math.abs(rx * 32 + (i and 31) - cx) <= range && kotlin.math.abs(rz * 32 + (i shr 5) - cz) <= range)
            }.toIntArray()
            if (indices.isEmpty()) continue
            out += file to indices
            total += indices.size
        }
        return out to total
    }

    /** 워커 — 속도·일시정지·멈춤을 지키며 훑는다. Bukkit API 를 쓰지 않는다. */
    private fun run(scan: Scan) {
        try {
            var windowStart = System.nanoTime()
            var inWindow = 0
            for ((file, indices) in scan.plan) {
                if (scan.stopped) break
                val region = RegionFiles.regionOf(file.name)!!
                RandomAccessFile(file, "r").use { raf ->
                    for (i in indices) {
                        while (scan.paused && !scan.stopped) Thread.sleep(200)
                        if (scan.stopped) return@use
                        val limit = effectiveSpeed(scan)
                        if (inWindow >= limit) {
                            val waited = (System.nanoTime() - windowStart) / 1_000_000
                            if (waited < 1000) Thread.sleep(1000 - waited)
                            windowStart = System.nanoTime()
                            inWindow = 0
                        }
                        inWindow++
                        val result = runCatching { RegionFiles.check(raf, file, i) }.getOrElse { RegionFiles.Result.Damaged("읽기 실패(${it.javaClass.simpleName})") }
                        when (result) {
                            is RegionFiles.Result.Damaged -> {
                                // 서버가 쓰는 중에 읽은 찢어진 조각일 수 있다 — 잠깐 뒤 다시 본다.
                                Thread.sleep(50)
                                val again = runCatching { RegionFiles.check(file, i) }.getOrElse { result }
                                if (again is RegionFiles.Result.Damaged) scan.found += Found(region.first * 32 + (i and 31), region.second * 32 + (i shr 5), again.reason)
                            }
                            is RegionFiles.Result.Unsupported -> scan.unsupported.incrementAndGet()
                            else -> Unit
                        }
                        scan.checked.incrementAndGet()
                    }
                }
            }
        } catch (_: InterruptedException) {
            // 종료
        } catch (t: Throwable) {
            ess.logger.log(Level.WARNING, "청크 검사 중 오류", t)
        } finally {
            scan.finished = true
            if (ess.plugin.isEnabled) Bukkit.getScheduler().runTask(ess.plugin, Runnable { done(scan) })
        }
    }

    private fun effectiveSpeed(scan: Scan): Int {
        if (!scan.autoSpeed) return scan.speed
        val t = tps
        // TPS 가 18 아래로 내려가면 줄이고 15 아래면 10분의 1 로 — 디스크 읽기가 서버 저장과 다투지 않게.
        return when {
            t >= 18.0 -> scan.speed
            t >= 15.0 -> (scan.speed / 2).coerceAtLeast(1)
            else -> (scan.speed / 10).coerceAtLeast(1)
        }
    }

    private fun done(scan: Scan) {
        scans.remove(scan.world.lowercase(), scan)
        val ph = Ph.of().count(scan.found.size).world(scan.world)
        val to = scan.owner?.let { Bukkit.getPlayer(it) } ?: Bukkit.getConsoleSender()
        ess.messages.send(to, if (scan.stopped) "world-fix-stopped" else "world-fix-finished", ph)
        if (scan.unsupported.get() > 0) ess.messages.send(to, "world-fix-unsupported", Ph.of().count(scan.unsupported.get()))
    }

    /** 1초 틱 — TPS 를 워커에 건네고 진행 상황을 알리고 복구 대기열을 조금씩 넘긴다. */
    fun tick(now: Long) {
        tps = runCatching { Bukkit.getTPS()[0] }.getOrDefault(20.0)
        val every = settings().fixChunk.progressEverySeconds * 1000L
        for (scan in scans.values) {
            if (!scan.messages || scan.paused || now - scan.lastProgress < every) continue
            scan.lastProgress = now
            val to = scan.owner?.let { Bukkit.getPlayer(it) } ?: Bukkit.getConsoleSender()
            ess.messages.send(to, "world-fix-progress", progress(scan))
        }
        stepFix()
    }

    fun progress(scan: Scan): Ph {
        val elapsed = scan.elapsedSeconds
        val left = (scan.total - scan.checked.get()).coerceAtLeast(0)
        val cps = scan.checked.get() / elapsed
        val eta = if (cps > 0) left / cps else 0
        return Ph.of().raw("{tps}", "%.1f".format(tps.coerceAtMost(20.0))).count(left).time(Durations.formatShort(eta))
            .raw("{속도}", effectiveSpeed(scan).toString()).raw("{찾음}", scan.found.size.toString())
    }

    fun stats(sender: CommandSender, scan: Scan) {
        val elapsed = scan.elapsedSeconds
        ess.messages.send(sender, "world-fix-stats", Ph.of().world(scan.world).raw("{확인}", scan.checked.get().toString()).raw("{전체}", scan.total.toString())
            .raw("{범위}", scan.range).raw("{속도}", scan.speed.toString()).raw("{자동}", if (scan.autoSpeed) "자동" else "고정")
            .raw("{실속도}", (scan.checked.get() / elapsed).toString()).raw("{찾음}", scan.found.size.toString()).time(Durations.formatShort(elapsed)))
    }

    fun pause(scan: Scan, on: Boolean) {
        if (on && !scan.paused) scan.pausedAt = System.currentTimeMillis()
        if (!on && scan.paused) scan.pausedMillis += System.currentTimeMillis() - scan.pausedAt
        scan.paused = on
    }

    fun stop(scan: Scan) {
        scan.stopped = true
        scan.paused = false
    }

    fun stopAll(): Int {
        val all = scans.values.toList()
        all.forEach { stop(it) }
        return all.size
    }

    /** 찾은 청크를 대기열에 넣는다(메인). 넣은 수. */
    fun fix(sender: CommandSender, scan: Scan): Int {
        val list = scan.found.toList()
        if (list.isEmpty()) return 0
        val request = FixRequest(sender, list.size)
        for (f in list) fixQueue.addLast(FixItem(scan.world, f.x, f.z, request))
        scan.found.clear()
        return list.size
    }

    private fun stepFix() {
        repeat(FIX_PER_SECOND) {
            val item = fixQueue.removeFirstOrNull() ?: return
            val world = Bukkit.getWorld(item.world)
            if (world == null) return@repeat fixed(item.request) // 월드가 내려갔다 — 그 청크는 셈만
            // 서버가 못 읽는 청크는 읽는 순간 새로 만들어진다. 다 읽으면 내려놓기를 청해 새 내용이 저장되게 한다.
            world.getChunkAtAsync(item.x, item.z, true).whenComplete { _, _ ->
                if (ess.plugin.isEnabled) Bukkit.getScheduler().runTask(ess.plugin, Runnable {
                    world.unloadChunkRequest(item.x, item.z)
                    fixed(item.request)
                })
            }
        }
    }

    /** 요청의 청크 하나가 끝났다(메인) — 다 끝났으면 그 사람에게 알린다. */
    private fun fixed(request: FixRequest) {
        request.done++
        if (request.done == request.total) ess.messages.send(request.sender, "world-fix-fixed", Ph.of().count(request.total))
    }

    fun shutdown() {
        scans.values.forEach { stop(it) }
        scans.clear()
        fixQueue.clear()
    }

    private companion object {
        const val FIX_PER_SECOND = 8
    }
}
