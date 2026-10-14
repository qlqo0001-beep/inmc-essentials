package com.inmc.essentials.commands

import java.util.Random

/**
 * 명령 줄의 특수 머리(순수) — 이벤트 명령·일정·별칭이 같이 쓴다. CMI 의 "specialized commands" 중 쓸 만한 것만(목록은 `docs/commands.md`).
 *
 * 머리는 줄 앞에 몇 개든 이어 붙인다: `perm:vip.notice! asConsole! say 안녕`. 한 줄에 여러 명령은 `;;` 로 나눈다.
 * `delay! 3` 은 **그 줄 뒤의 나머지**를 3초 뒤에 돌린다(소수 가능).
 */
object CommandLines {

    enum class Action { COMMAND, MSG, BROADCAST, ACTIONBAR, TITLE, SUBTITLE, KICKALL }

    data class Parsed(
        val body: String,
        val action: Action = Action.COMMAND,
        val permission: String? = null,
        val moneyCost: Double? = null,
        val expCost: Int? = null,
        val asConsole: Boolean = false,
        val asPlayer: Boolean = false,
        val fromConsole: Boolean = false,
        val allPlayers: Boolean = false,
    )

    /** 실행할 차례 하나 — 기다리기(`delay! N`) 또는 머리를 읽은 줄. 나누기·머리 읽기는 **변수·인자를 넣기 전에** 끝난다. */
    sealed interface Step {
        data class Wait(val seconds: Double) : Step
        data class Line(val parsed: Parsed) : Step
    }

    private val DELAY = Regex("""^\s*delay!\s*([0-9]+(?:\.[0-9]+)?)\s*$""", RegexOption.IGNORE_CASE)
    private val RAND = Regex("""%rand/(-?\d+)-(-?\d+)%""")

    /** `;;` 로 나누고 빈 줄은 버린다. */
    fun split(lines: List<String>): List<String> = lines.flatMap { it.split(";;") }.map { it.trim() }.filter { it.isNotEmpty() }

    /** 나누고(`;;`) 줄마다 머리를 읽는다. 이 뒤에 넣는 값(사람이 친 인자·변수)은 줄을 더 만들거나 머리를 바꾸지 못한다. */
    fun steps(lines: List<String>): List<Step> = split(lines).map { line -> delay(line)?.let { Step.Wait(it) } ?: Step.Line(parse(line)) }

    /** 어느 줄이 `[randomPlayer]` 를 쓰나 — 한 번 돌릴 때 한 사람만 고르려고(CMI `Schedule.perform` 과 같다). */
    fun mentionsRandom(steps: List<Step>): Boolean =
        steps.any { it is Step.Line && it.parsed.body.contains("[randomPlayer]", ignoreCase = true) }

    /** `delay! N` 이면 그 초. */
    fun delay(line: String): Double? = DELAY.matchEntire(line)?.groupValues?.get(1)?.toDoubleOrNull()

    fun parse(line: String): Parsed {
        var rest = line.trim()
        var permission: String? = null
        var money: Double? = null
        var exp: Int? = null
        var asConsole = false
        var asPlayer = false
        var fromConsole = false
        var all = false
        while (true) {
            val lower = rest.lowercase()
            val bang = rest.indexOf('!')
            if (bang <= 0) break
            val head = lower.substring(0, bang)
            when {
                head == "asconsole" -> asConsole = true
                head == "asplayer" -> asPlayer = true
                head == "fromconsole" -> fromConsole = true
                head == "allplayers" -> all = true
                head.startsWith("perm:") -> permission = rest.substring(5, bang).trim()
                head.startsWith("moneycost:") -> money = rest.substring(10, bang).trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
                head.startsWith("expcost:") -> exp = rest.substring(8, bang).trim().toIntOrNull()?.takeIf { it > 0 }
                else -> break
            }
            rest = rest.substring(bang + 1).trimStart()
        }
        val lower = rest.lowercase()
        val (action, body) = ACTIONS.firstOrNull { lower.startsWith(it.first) }
            ?.let { it.second to rest.substring(it.first.length).trimStart() }
            ?: (Action.COMMAND to rest.removePrefix("/"))
        return Parsed(body, action, permission, money, exp, asConsole, asPlayer, fromConsole, all)
    }

    private val ACTIONS = listOf(
        "msg!" to Action.MSG,
        "broadcast!" to Action.BROADCAST,
        "actionbar!" to Action.ACTIONBAR,
        "title!" to Action.TITLE,
        "subtitle!" to Action.SUBTITLE,
        "kickall!" to Action.KICKALL,
    )

    /** `%rand/1-5%` → 1~5 사이 정수(양 끝 포함). */
    fun rand(text: String, random: Random = Random()): String = RAND.replace(text) { m ->
        val a = m.groupValues[1].toLong()
        val b = m.groupValues[2].toLong()
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        (lo + (random.nextDouble() * (hi - lo + 1)).toLong().coerceAtMost(hi - lo)).toString()
    }

    /** `[이름]` 꼴 변수 바꾸기(대소문자 무시). */
    fun variables(text: String, values: Map<String, String>): String {
        if (values.isEmpty() || text.indexOf('[') < 0) return text
        var out = text
        for ((key, value) in values) out = out.replace("[$key]", value, ignoreCase = true)
        return out
    }
}
