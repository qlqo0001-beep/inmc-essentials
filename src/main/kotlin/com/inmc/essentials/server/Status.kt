package com.inmc.essentials.server

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import kr.inmc.core.integration.Vanish
import kr.inmc.core.util.Durations
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.command.CommandSender
import java.io.File
import java.lang.management.ManagementFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `/서버상태` — CMI `status`·`tps`. TPS·MSPT·CPU·메모리·가동 시간·Java·디스크·접속자·대기 작업·월드별 청크·엔티티·타일과 가장 붐비는 청크.
 * `/서버상태 틱` 은 3·10·30·60·600초 TPS 와 최근 10분의 최저·최고·느려진 순간([TpsSampler]).
 * 접속자 수는 **부른 사람에게 보이는 사람만**(투명 — CLAUDE.md 8).
 */
class Status(private val ess: Ess, private val feature: ServerFeature) {

    private fun color(tps: Double): String {
        val tag = when {
            tps >= 19.0 -> "green"
            tps >= 15.0 -> "yellow"
            else -> "red"
        }
        return "<$tag>" + "%.1f".format(tps) + "</$tag>"
    }

    fun summary(sender: CommandSender) {
        val server = Bukkit.getServer()
        val tps = server.tps
        ess.messages.send(sender, "server-status-header", Ph.of().value(server.name + " " + server.version))
        ess.messages.send(
            sender, "server-status-tps",
            Ph.of().raw("{1분}", color(tps.getOrElse(0) { 20.0 })).raw("{5분}", color(tps.getOrElse(1) { 20.0 })).raw("{15분}", color(tps.getOrElse(2) { 20.0 }))
                .raw("{mspt}", "%.2f".format(server.averageTickTime)),
        )
        val os = ManagementFactory.getOperatingSystemMXBean()
        val sun = os as? com.sun.management.OperatingSystemMXBean
        val proc = sun?.processCpuLoad?.takeIf { it >= 0 }?.let { "%.1f".format(it * 100) } ?: "?"
        val sys = sun?.cpuLoad?.takeIf { it >= 0 }?.let { "%.1f".format(it * 100) } ?: "?"
        ess.messages.send(
            sender, "server-status-cpu",
            Ph.of().raw("{서버}", proc).raw("{전체}", sys).raw("{코어}", os.availableProcessors.toString()).raw("{스레드}", Thread.activeCount().toString()),
        )
        val rt = Runtime.getRuntime()
        val mb = 1024L * 1024L
        val used = (rt.totalMemory() - rt.freeMemory()) / mb
        val max = rt.maxMemory() / mb
        ess.messages.send(
            sender, "server-status-memory",
            Ph.of().raw("{사용}", used.toString()).raw("{최대}", max.toString()).raw("{할당}", (rt.totalMemory() / mb).toString())
                .raw("{남음}", (rt.freeMemory() / mb).toString()).raw("{퍼센트}", if (max > 0) (used * 100 / max).toString() else "?"),
        )
        val uptime = ManagementFactory.getRuntimeMXBean().uptime / 1000
        ess.messages.send(sender, "server-status-uptime", Ph.of().time(Durations.formatShort(uptime)))
        ess.messages.send(sender, "server-status-java", Ph.of().value(System.getProperty("java.version")).raw("{os}", "${System.getProperty("os.name")} ${System.getProperty("os.arch")}"))
        val root = File(".").absoluteFile
        val gb = 1024.0 * 1024 * 1024
        ess.messages.send(
            sender, "server-status-disk",
            Ph.of().raw("{사용}", "%.1f".format((root.totalSpace - root.usableSpace) / gb)).raw("{전체}", "%.1f".format(root.totalSpace / gb)),
        )
        ess.messages.send(
            sender, "server-status-players",
            Ph.of().count(Vanish.visibleOnline(sender).size).raw("{최대}", server.maxPlayers.toString())
                .raw("{작업}", runCatching { Bukkit.getScheduler().pendingTasks.size }.getOrDefault(0).toString()),
        )
        for (world in Bukkit.getWorlds()) worldLine(sender, world)
    }

    private fun worldLine(sender: CommandSender, world: World) {
        ess.messages.send(
            sender, "server-status-world",
            Ph.of().world(world.name).raw("{청크}", world.chunkCount.toString()).raw("{엔티티}", world.entityCount.toString())
                .raw("{타일}", world.tileEntityCount.toString()).count(world.players.count { Vanish.canSee(sender, it) || sender == it }),
        )
    }

    /** `/서버상태 월드` — 월드마다 엔티티·타일이 가장 많은 청크. */
    fun busiest(sender: CommandSender) {
        val n = feature.settings.statusBusiestChunks
        for (world in Bukkit.getWorlds()) {
            worldLine(sender, world)
            if (n <= 0) continue
            val byChunk = HashMap<Long, Int>()
            for (e in world.entities) {
                val key = (e.location.blockX shr 4).toLong() shl 32 or ((e.location.blockZ shr 4).toLong() and 0xffffffffL)
                byChunk.merge(key, 1, Int::plus)
            }
            for ((key, count) in byChunk.entries.sortedByDescending { it.value }.take(n)) {
                ess.messages.send(sender, "server-status-busy-entities", Ph.of().world(world.name).raw("{x}", (key shr 32).toInt().toString()).raw("{z}", key.toInt().toString()).count(count))
            }
            val tiles = world.loadedChunks.map { it to runCatching { it.getTileEntities(false).size }.getOrDefault(0) }
                .filter { it.second > 0 }.sortedByDescending { it.second }.take(n)
            for ((chunk, count) in tiles) {
                ess.messages.send(sender, "server-status-busy-tiles", Ph.of().world(world.name).raw("{x}", chunk.x.toString()).raw("{z}", chunk.z.toString()).count(count))
            }
        }
    }

    /** `/서버상태 틱` — 창마다 TPS · 최근 10분 최저/최고 · 느려진 순간. */
    fun ticks(sender: CommandSender) {
        val s = feature.sampler
        fun t(sec: Int) = s.tps(sec)?.let { color(it) } ?: "?"
        ess.messages.send(sender, "server-status-tps-windows", Ph.of().raw("{3초}", t(3)).raw("{10초}", t(10)).raw("{30초}", t(30)).raw("{1분}", t(60)).raw("{10분}", t(600)))
        val per = s.perSecond(600)
        if (per.isEmpty()) return
        ess.messages.send(sender, "server-status-tps-minmax", Ph.of().raw("{최저}", color(per.minOf { it.second })).raw("{최고}", color(per.maxOf { it.second })))
        val below = feature.settings.tpsSpikeBelow
        val spikes = per.filter { it.second < below }
        if (spikes.isEmpty()) return ess.messages.send(sender, "server-status-tps-no-spikes", Ph.of().value("%.0f".format(below)))
        val fmt = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
        for ((at, tps) in spikes.sortedBy { it.second }.take(10).sortedBy { it.first }) {
            ess.messages.send(sender, "server-status-tps-spike", Ph.of().time(fmt.format(Instant.ofEpochMilli(at))).raw("{tps}", color(tps)))
        }
    }
}
