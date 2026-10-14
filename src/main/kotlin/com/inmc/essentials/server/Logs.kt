package com.inmc.essentials.server

import com.inmc.essentials.Ess
import io.papermc.paper.event.player.AsyncChatEvent
import kr.inmc.core.util.Text
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.server.ServerCommandEvent
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 명령·채팅 기록(`plugins/inmc-essentials/logs/commands-<날짜>.log` · `chat-<날짜>.log`) — CMI `Logs` 폴더 자리. 기본은 꺼짐(`logs.*`).
 *
 * 사건에서는 줄을 큐에 넣기만 하고(채팅은 비동기 사건), 5초마다 I/O 스레드가 날짜별 파일 끝에 붙인다(메인에서 파일 금지).
 * 로그인 비밀번호 같은 명령(`logs.hidden-commands`)은 이름만 남긴다. `logs.keep-days` 보다 오래된 파일은 켤 때·하루에 한 번 지운다.
 */
class Logs(private val ess: Ess, private val feature: ServerFeature) : Listener {

    private data class Line(val kind: String, val date: LocalDate, val text: String)

    private val queue = ConcurrentLinkedQueue<Line>()
    private var lastPurge: LocalDate? = null

    val folder: File get() = ess.io.file("logs")

    private fun add(kind: String, text: String) {
        queue += Line(kind, LocalDate.now(), "[" + LocalTime.now().format(TIME) + "] " + text.replace('\n', ' '))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val s = feature.settings
        if (!s.logCommands) return
        add("commands", event.player.name + ": " + mask(event.message, s.logHiddenCommands) + if (event.isCancelled) "  (막힘)" else "")
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onConsole(event: ServerCommandEvent) {
        val s = feature.settings
        if (!s.logCommands) return
        add("commands", event.sender.name + ": /" + mask(event.command, s.logHiddenCommands))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        if (!feature.settings.logChat) return
        add("chat", event.player.name + ": " + Text.plain(event.message()))
    }

    /** 1초 틱에서 5초마다. */
    fun flush() {
        if (queue.isEmpty()) {
            maybePurge()
            return
        }
        val batch = ArrayList<Line>()
        while (true) batch += queue.poll() ?: break
        val dir = folder
        ess.io.asyncRun {
            dir.mkdirs()
            for ((key, lines) in batch.groupBy { it.kind to it.date }) {
                val file = File(dir, "${key.first}-${key.second}.log")
                Files.write(file.toPath(), lines.map { it.text }, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }
        }
        maybePurge()
    }

    /** 끌 때 — 남은 줄을 기다려 쓴다(I/O 워커가 닫히기 전에 넘긴다). */
    fun flushNow() = flush()

    private fun maybePurge() {
        val today = LocalDate.now()
        if (lastPurge == today) return
        lastPurge = today
        val keep = feature.settings.logKeepDays
        if (keep <= 0) return
        val dir = folder
        ess.io.asyncRun {
            val limit = today.minusDays(keep.toLong())
            for (f in dir.listFiles { f -> f.isFile && f.name.endsWith(".log") }.orEmpty()) {
                val date = runCatching { LocalDate.parse(f.name.removeSuffix(".log").substringAfter('-')) }.getOrNull() ?: continue
                if (date.isBefore(limit)) f.delete()
            }
        }
    }

    /** 오늘 파일들의 크기(바이트) — `/에센셜 로그`. I/O 스레드에서. */
    fun todaySizes(): Map<String, Long> {
        val today = LocalDate.now()
        return listOf("commands", "chat").associateWith { File(folder, "$it-$today.log").takeIf { f -> f.isFile }?.length() ?: 0L }
    }

    companion object {
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

        /** 숨길 명령이면 인자를 가린다(순수). */
        fun mask(message: String, hidden: Set<String>): String {
            val body = message.removePrefix("/")
            val label = body.substringBefore(' ').substringAfterLast(':').lowercase()
            return if (label in hidden && body.contains(' ')) "/" + body.substringBefore(' ') + " ***" else "/" + body
        }
    }
}
