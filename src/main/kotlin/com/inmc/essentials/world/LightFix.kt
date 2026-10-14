package com.inmc.essentials.world

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import kr.inmc.core.util.Durations
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import java.util.function.IntConsumer
import java.util.logging.Level

/**
 * `/조명수정` — 주변 청크의 빛을 다시 계산한다(CMI lfix). Bukkit API 에 빛 다시 계산이 없어 **서버 내부를 리플렉션으로** 부른다 —
 * Paper 의 `/paper fixlight` 가 부르는 것과 같은 길(leaf-26.2.jar `FixLightCommand` javap):
 * `CraftWorld.getHandle()` → `ServerLevel.getChunkSource()` → `ServerChunkCache.getLightEngine()` →
 * `ThreadedLevelLightEngine.starlight$serverRelightChunks(Collection<ChunkPos>, Consumer<ChunkPos>, IntConsumer)`.
 * 이름이 바뀌면 던지지 않고 "쓸 수 없음" 을 알린다(ARCHITECTURE 리플렉션 진입점 표).
 *
 * 빛 계산 자체는 서버가 자기 스레드에서 한다. 우리는 [WorldSettings.LightFix.batch] 개씩 넘기고 끝나면 다음 묶음 — 그래서 멈출 수 있고
 * 진행 상황을 셀 수 있다. 읽혀 있는 청크만 다룬다(Paper 와 같다 — 안 읽힌 청크는 읽힐 때 서버가 빛을 다시 본다).
 */
class LightFix(private val ess: Ess, private val settings: () -> WorldSettings) {

    private class Hooks(val getHandle: Method, val getChunkSource: Method, val getLightEngine: Method, val relight: Method, val chunkPos: Constructor<*>)

    /** 월드는 이름으로만 — 내려간 월드(던전 인스턴스)를 붙들지 않게(2026-10-10 리뷰). */
    private class Job(val owner: UUID?, val world: String, val queue: ArrayDeque<Pair<Int, Int>>, val total: Int) {
        val done = AtomicInteger()
        val startedAt = System.currentTimeMillis()
        @Volatile var lastProgress = startedAt
        /** 마지막으로 끝난 청크 수와 그때 — 서버가 답을 안 주면(묶음 도중 월드가 내려감 등) [STALL_MILLIS] 뒤 작업을 버린다. */
        @Volatile var seenDone = 0
        @Volatile var seenAt = startedAt
    }

    @Volatile
    private var hooks: Hooks? = null

    private val jobs = ConcurrentHashMap<UUID, Job>()
    private val consoleJobs = ConcurrentHashMap<String, Job>()

    /** 리플렉션 길을 찾는다. 실패하면 까닭(던지지 않는다). */
    fun probe(world: World): String? = try {
        hooksFor(world)
        null
    } catch (t: Throwable) {
        t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
    }

    private fun hooksFor(world: World): Hooks {
        hooks?.let { return it }
        val getHandle = world.javaClass.getMethod("getHandle")
        val level = getHandle.invoke(world)
        val getChunkSource = level.javaClass.getMethod("getChunkSource")
        val source = getChunkSource.invoke(level)
        val getLightEngine = source.javaClass.getMethod("getLightEngine")
        val engine = getLightEngine.invoke(source)
        val relight = engine.javaClass.getMethod("starlight\$serverRelightChunks", Collection::class.java, Consumer::class.java, IntConsumer::class.java)
        val posClass = Class.forName("net.minecraft.world.level.ChunkPos", true, level.javaClass.classLoader)
        val ctor = posClass.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        return Hooks(getHandle, getChunkSource, getLightEngine, relight, ctor).also { hooks = it }
    }

    fun isRunning(sender: CommandSender): Boolean = jobOf(sender) != null

    private fun jobOf(sender: CommandSender): Job? = (sender as? Player)?.let { jobs[it.uniqueId] } ?: consoleJobs[sender.name]

    /** [center] 둘레 [range] 청크(시야 거리까지). 시작했으면 true. */
    fun start(sender: CommandSender, center: Player, range: Int): Boolean {
        if (jobOf(sender) != null) {
            ess.messages.send(sender, "world-lfix-already")
            return false
        }
        val world = center.world
        probe(world)?.let {
            ess.messages.send(sender, "world-lfix-unavailable", Ph.of().value(it))
            return false
        }
        val view = Bukkit.getViewDistance()
        val r = if (range > view) {
            ess.messages.send(sender, "world-lfix-capped", Ph.of().value(view.toString()))
            view
        } else range.coerceAtLeast(0)
        val cx = center.location.blockX shr 4
        val cz = center.location.blockZ shr 4
        val queue = ArrayDeque<Pair<Int, Int>>()
        val spiral = Spiral(r)
        while (true) {
            val (dx, dz) = spiral.next() ?: break
            if (world.isChunkLoaded(cx + dx, cz + dz)) queue.addLast((cx + dx) to (cz + dz))
        }
        val job = Job((sender as? Player)?.uniqueId, world.name, queue, queue.size)
        if (sender is Player) jobs[sender.uniqueId] = job else consoleJobs[sender.name] = job
        ess.messages.send(sender, "world-lfix-started", Ph.of().count(job.total))
        next(job)
        return true
    }

    private fun registered(job: Job): Boolean = jobs.values.any { it === job } || consoleJobs.values.any { it === job }

    /** 다음 묶음(메인). 끝났으면 알린다. 멈춤·시간 초과로 이미 빠진 작업이면 아무것도 안 한다(늦게 온 서버 답). */
    private fun next(job: Job) {
        if (!registered(job)) return
        if (job.queue.isEmpty()) return finish(job, "world-lfix-finished")
        val h = hooks ?: return finish(job, "world-lfix-stopped")
        val world = Bukkit.getWorld(job.world) ?: return finish(job, "world-lfix-stopped")
        val batch = ArrayList<Any>()
        repeat(settings().lightFix.batch) { job.queue.removeFirstOrNull()?.let { (x, z) -> batch += h.chunkPos.newInstance(x, z) } }
        try {
            val level = h.getHandle.invoke(world)
            val engine = h.getLightEngine.invoke(h.getChunkSource.invoke(level))
            h.relight.invoke(engine, batch, Consumer<Any> { job.done.incrementAndGet() }, IntConsumer { _ ->
                if (ess.plugin.isEnabled) Bukkit.getScheduler().runTask(ess.plugin, Runnable { next(job) })
            })
        } catch (t: Throwable) {
            ess.logger.log(Level.WARNING, "조명 수정 실패", t)
            hooks = null
            sendTo(job, "world-lfix-unavailable", Ph.of().value(t.cause?.javaClass?.simpleName ?: t.javaClass.simpleName))
            remove(job)
        }
    }

    private fun finish(job: Job, key: String) {
        remove(job)
        val seconds = ((System.currentTimeMillis() - job.startedAt) / 1000).coerceAtLeast(0)
        sendTo(job, key, Ph.of().count(job.done.get()).time(Durations.formatShort(seconds.coerceAtLeast(1))))
    }

    private fun remove(job: Job) {
        jobs.values.remove(job)
        consoleJobs.values.remove(job)
    }

    private fun sendTo(job: Job, key: String, ph: Ph) {
        val to = job.owner?.let { Bukkit.getPlayer(it) } ?: Bukkit.getConsoleSender()
        ess.messages.send(to, key, ph)
    }

    /** 내 작업을 바로 뺀다(멈춤 알림까지) — 서버의 다음 답을 기다리지 않는다(그 답이 안 오면 작업이 영영 남았다 — 2026-10-10 리뷰). */
    fun stop(sender: CommandSender): Boolean {
        val job = jobOf(sender) ?: return false
        finish(job, "world-lfix-stopped")
        return true
    }

    fun stopAll(): Int {
        val all = (jobs.values + consoleJobs.values).distinct()
        all.forEach { finish(it, "world-lfix-stopped") }
        return all.size
    }

    /** 1초 틱 — 진행 상황(사람은 행동 표시줄, 콘솔은 채팅 줄 — CMI 처럼 TPS·남은 청크·예상 시간)과 답이 끊긴 작업 버리기. */
    fun tick(now: Long) {
        if (jobs.isEmpty() && consoleJobs.isEmpty()) return
        val every = settings().lightFix.progressEverySeconds * 1000L
        val tps = runCatching { Bukkit.getTPS()[0] }.getOrDefault(20.0).coerceAtMost(20.0)
        for (job in (jobs.values + consoleJobs.values).distinct()) {
            val done = job.done.get()
            if (done != job.seenDone) {
                job.seenDone = done
                job.seenAt = now
            } else if (now - job.seenAt > STALL_MILLIS) {
                finish(job, "world-lfix-timeout")
                continue
            }
            if (now - job.lastProgress < every) continue
            job.lastProgress = now
            val elapsed = ((now - job.startedAt) / 1000).coerceAtLeast(1)
            val left = (job.total - done).coerceAtLeast(0)
            val speed = done / elapsed
            val eta = if (speed > 0) left / speed else 0
            val ph = Ph.of().raw("{tps}", "%.1f".format(tps)).count(left.toLong()).time(Durations.formatShort(eta)).raw("{속도}", speed.toString())
            if (job.owner == null) {
                ess.messages.send(Bukkit.getConsoleSender(), "world-lfix-progress", ph)
            } else {
                val player = Bukkit.getPlayer(job.owner) ?: continue
                player.sendActionBar(ess.messages.component("world-lfix-progress", ph, player))
            }
        }
    }

    fun shutdown() {
        jobs.clear()
        consoleJobs.clear()
    }

    private companion object {
        /** 서버가 이만큼 아무 청크도 끝내지 못하면 작업을 버린다. */
        const val STALL_MILLIS = 60_000L
    }
}
