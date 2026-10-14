package com.inmc.essentials.commands

import com.inmc.essentials.Ess
import kr.inmc.core.integration.Vanish
import kr.inmc.core.util.AtomicFiles
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Random

/**
 * 일정(`commands/schedules.yml` — CMI `Schedules.yml` 과 같은 키). `ess.ticks` 의 1초 틱에서 돈다(반복 작업은 하나 — 규칙 11).
 *
 * - `Delay` 만 있으면 그 초마다(켠 때부터 셈 — CMI 와 같다, 재시작하면 처음부터). `PerformOn` 이 있으면 그 시각에(`Delay` 는 무시).
 * - 서버가 느려 1초 틱이 건너뛰어도 그 사이 초를 다 본다(2분까지) — 정각 일정을 놓치지 않게.
 * - `Repeat: false` = 이번 서버 실행에서 한 번만.
 */
class ScheduleService(private val ess: Ess, private val feature: CommandsFeature) {

    /** [delay] = 이 상태를 만들 때의 간격(초) — 리로드에서 간격이 바뀌었는지 본다. */
    private class State(var nextRun: Long, val delay: Long, var done: Boolean = false, var linear: Int = 0, val used: MutableSet<Int> = HashSet())

    @Volatile
    var schedules: List<Schedule> = emptyList()
        private set

    /** 파일 원본 — `/일정 켜기·끄기` 가 고쳐 쓴다(주석을 지키려고 들고 있는다). */
    private var raw: YamlConfiguration = YamlConfiguration()

    private val states = HashMap<String, State>()
    private var lastSecond = 0L
    private val random = Random()

    val file: File get() = ess.io.file("commands", "schedules.yml")

    fun apply(yaml: YamlConfiguration, list: List<Schedule>) {
        raw = yaml
        schedules = list
        val now = System.currentTimeMillis()
        val old = HashMap(states)
        states.clear()
        for (s in list) {
            // 리로드는 간격 타이머를 처음부터 세지 않는다(같은 간격이면) — 리로드할 때마다 공지가 밀리지 않게.
            // 간격이 바뀌었으면 새 간격으로 다시 센다(3600 → 60 으로 줄였는데 한 시간을 기다리지 않게).
            val prev = old[s.name]?.takeIf { it.delay == s.delay }
            states[s.name] = prev ?: State(now + s.delay * 1000, s.delay)
        }
    }

    fun loadAsync() {
        ess.io.async({
            ess.io.copyDefault("commands/schedules.yml", file)
            val yaml = if (file.isFile) ess.io.load(file) else YamlConfiguration()
            val problems = mutableListOf<String>()
            Triple(yaml, Schedule.readAll(yaml) { problems += it }, problems)
        }) { (yaml, list, problems) ->
            for (p in problems) ess.logger.warning("commands/schedules.yml: $p")
            apply(yaml, list)
        }
    }

    fun tick(now: Long) {
        val second = now / 1000
        val from = if (lastSecond == 0L || second - lastSecond > 120) second else lastSecond + 1
        lastSecond = second
        for (s in schedules) {
            if (!s.enabled) continue
            val state = states.getOrPut(s.name) { State(now + s.delay * 1000, s.delay) }
            if (state.done) continue
            val due = if (s.timed) {
                (from..second).any { sec -> s.frames.any { it.matches(LocalDateTime.ofInstant(Instant.ofEpochSecond(sec), zone())) } }
            } else {
                now >= state.nextRun
            }
            if (!due) continue
            if (!s.timed) state.nextRun = now + s.delay * 1000
            run(s, state)
            if (!s.repeat) state.done = true
        }
    }

    private fun zone(): ZoneId = ZoneId.systemDefault()

    /** 지금 돌린다(`/일정 실행` 도 여기로 — 인원 조건은 본다). */
    fun runNow(name: String): Boolean {
        val s = schedules.firstOrNull { it.name.equals(name, true) } ?: return false
        run(s, states.getOrPut(s.name) { State(System.currentTimeMillis() + s.delay * 1000, s.delay) })
        return true
    }

    private fun run(s: Schedule, state: State) {
        val online = Vanish.visibleOnline(null).size
        val ctx = CommandRunner.Context(sender = Bukkit.getConsoleSender(), translatePlaceholders = !s.dontTranslate, origin = "일정 ${s.name}")
        if (s.minPlayers > 0 && online < s.minPlayers) {
            if (s.feedback) ess.logger.info("일정 ${s.name}: 접속자 ${online}명 < 최소 ${s.minPlayers}명 — 건너뜀")
            if (s.minPlayersCommands.isNotEmpty()) feature.runner.run(s.minPlayersCommands, ctx)
            return
        }
        if (s.maxPlayers > 0 && online > s.maxPlayers) {
            if (s.feedback) ess.logger.info("일정 ${s.name}: 접속자 ${online}명 > 최대 ${s.maxPlayers}명 — 건너뜀")
            return
        }
        feature.runner.run(pick(s, state), ctx)
    }

    /** 무작위·차례 고르기. `delay!` 줄은 고를 대상이 아니다. */
    private fun pick(s: Schedule, state: State): List<String> {
        val choices = s.commands.filter { CommandLines.delay(it) == null }
        if (choices.isEmpty()) return s.commands
        return when {
            s.randomize -> {
                if (state.used.size >= choices.size) state.used.clear()
                val left = choices.indices.filter { it !in state.used }
                val i = left[random.nextInt(left.size)]
                state.used += i
                listOf(choices[i])
            }
            s.duplicateRandomize -> listOf(choices[random.nextInt(choices.size)])
            s.singleLinear -> {
                val i = state.linear % choices.size
                state.linear = i + 1
                listOf(choices[i])
            }
            else -> s.commands
        }
    }

    /** 다음 실행까지(초) — 간격 일정만. 시각 일정은 null. */
    fun secondsLeft(name: String): Long? {
        val s = schedules.firstOrNull { it.name == name } ?: return null
        if (s.timed) return null
        val state = states[s.name] ?: return null
        return ((state.nextRun - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
    }

    fun done(name: String): Boolean = states[name]?.done == true

    fun resetTimer(name: String): Boolean {
        val s = schedules.firstOrNull { it.name.equals(name, true) } ?: return false
        states[s.name] = State(System.currentTimeMillis() + s.delay * 1000, s.delay)
        return true
    }

    /** 켜기·끄기 — 메모리와 파일 둘 다. */
    fun setEnabled(name: String, enabled: Boolean): Boolean {
        val s = schedules.firstOrNull { it.name.equals(name, true) } ?: return false
        schedules = schedules.map { if (it === s) it.copy(enabled = enabled) else it }
        val key = if (raw.getConfigurationSection(s.name)?.contains("enabled") == true) "enabled" else "Enabled"
        raw.set("${s.name}.$key", enabled)
        if (enabled) states[s.name] = State(System.currentTimeMillis() + s.delay * 1000, s.delay)
        write()
        return true
    }

    /** 가져오기 — 같은 이름은 건너뛴다. 넣은 이름들. */
    fun merge(add: Map<String, Map<String, Any?>>): List<String> {
        val fresh = add.filterKeys { name -> schedules.none { it.name.equals(name, true) } && !raw.contains(name) }
        if (fresh.isEmpty()) return emptyList()
        for ((name, values) in fresh) {
            val section = raw.createSection(name)
            for ((k, v) in values) section.set(k, v)
        }
        val problems = mutableListOf<String>()
        apply(raw, Schedule.readAll(raw) { problems += it })
        for (p in problems) ess.logger.warning("commands/schedules.yml: $p")
        write()
        return fresh.keys.toList()
    }

    private fun write() {
        val text = raw.saveToString()
        val target = file
        ess.io.asyncRun { AtomicFiles.write(target, text) }
    }
}
