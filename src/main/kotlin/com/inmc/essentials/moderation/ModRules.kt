package com.inmc.essentials.moderation

import java.util.UUID

/**
 * moderation 의 순수 규칙 — 서버 없이 시험한다(`ModRulesTest`). 시간 읽기 · 플래그(-s · r:) · IP · 권한으로 정하는 최대 시간 · 경고 문턱 ·
 * 명령 허용 목록 · 중복 계정 고르기.
 */
object ModRules {

    // --- 시간 ------------------------------------------------------------------------------------

    private val UNIT = Regex("""(\d+)\s*(시간|초|분|일|주|년|[smhdwy])""", RegexOption.IGNORE_CASE)

    /**
     * `30s` · `10m` · `1h` · `1d2h` · `1w` · `1y` · `1일 2시간` · 맨 숫자(= 초). 글자 전체가 시간이어야 한다 — `3번` 은 시간이 아니다(사유의 첫 낱말).
     * 0 이하·못 읽으면 null.
     */
    fun parseSeconds(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        text.toLongOrNull()?.let { return it.takeIf { n -> n > 0 } }
        var total = 0L
        var covered = 0
        for (m in UNIT.findAll(text)) {
            val n = m.groupValues[1].toLongOrNull() ?: return null
            val unit = when (m.groupValues[2].lowercase()) {
                "s", "초" -> 1L
                "m", "분" -> 60L
                "h", "시간" -> 3600L
                "d", "일" -> 86_400L
                "w", "주" -> 604_800L
                "y", "년" -> 31_536_000L
                else -> return null
            }
            total += n * unit
            covered += m.value.replace(" ", "").length
        }
        if (covered == 0 || covered != text.replace(" ", "").length) return null
        return total.takeIf { it > 0 }
    }

    enum class Mode { SET, ADD, SUB }

    data class Change(val mode: Mode, val seconds: Long)

    /** `+30s`(더하기) · `-15m`(빼기) · `1h`(정하기). 시간이 아니면 null. */
    fun parseChange(raw: String): Change? {
        val text = raw.trim()
        return when {
            text.startsWith("+") -> parseSeconds(text.substring(1))?.let { Change(Mode.ADD, it) }
            text.startsWith("-") && text.length > 1 && text[1].isDigit() -> parseSeconds(text.substring(1))?.let { Change(Mode.SUB, it) }
            else -> parseSeconds(text)?.let { Change(Mode.SET, it) }
        }
    }

    /**
     * 기존 남은 시간([currentUntil], null = 영구/없음)에 [change] 를 적용한 새 해제 시각. null = 영구. 0 이하가 되면 [now] 이하를 돌려준다(= 풀림).
     * 영구에 더하고 빼기는 영구 그대로(무엇에 더할지 없다).
     */
    fun applyChange(currentUntil: Long?, existed: Boolean, change: Change, now: Long): Long? = when (change.mode) {
        Mode.SET -> now + change.seconds * 1000
        Mode.ADD -> if (existed && currentUntil == null) null else (if (existed) maxOf(currentUntil!!, now) else now) + change.seconds * 1000
        Mode.SUB -> if (existed && currentUntil == null) null else (if (existed) currentUntil!! else now) - change.seconds * 1000
    }

    /**
     * 권한 노드로 정하는 최대 시간(초). [nodes] = 사람이 가진(true) 권한들, [prefix] = `…max.`. `unlimited` 가 있으면 [Long.MAX_VALUE],
     * 숫자 노드가 있으면 그중 가장 큰 것, 아무것도 없으면 [fallback](-1 = 제한 없음 → MAX).
     */
    fun maxSeconds(nodes: Collection<String>, prefix: String, fallback: Long): Long {
        var best: Long? = null
        for (node in nodes) {
            if (!node.startsWith(prefix)) continue
            val tail = node.substring(prefix.length)
            if (tail == "unlimited") return Long.MAX_VALUE
            val n = tail.toLongOrNull() ?: parseSeconds(tail) ?: continue
            if (best == null || n > best) best = n
        }
        return best ?: if (fallback < 0) Long.MAX_VALUE else fallback
    }

    // --- 플래그 ----------------------------------------------------------------------------------

    /** 꼬리 낱말에서 뽑은 것. [rest] = 플래그를 뺀 낱말(순서 그대로). [reason] = `r:` 로 시작한 사유(감옥). */
    data class Flags(val silent: Boolean, val rest: List<String>, val reason: String?, val options: Map<String, String>)

    /**
     * `-s`(조용히) · `r:사유…`(그 뒤 낱말 전부가 사유) · `-p:값` `-w:값` 같은 옵션을 뗀다. 나머지는 순서대로 [Flags.rest].
     */
    fun flags(tokens: List<String>, reasonTag: Boolean = false): Flags {
        var silent = false
        val rest = ArrayList<String>()
        val options = LinkedHashMap<String, String>()
        var reason: String? = null
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t.equals("-s", true) || t == "-조용히" -> silent = true
                reasonTag && t.startsWith("r:", true) -> {
                    reason = (listOf(t.substring(2)) + tokens.drop(i + 1).filterNot { it.equals("-s", true).also { s -> if (s) silent = true } })
                        .joinToString(" ").trim().ifEmpty { null }
                    break
                }
                OPTION.matches(t) -> {
                    val (k, v) = t.substring(1).split(':', limit = 2)
                    options[k.lowercase()] = v
                }
                else -> rest += t
            }
            i++
        }
        return Flags(silent, rest, reason, options)
    }

    private val OPTION = Regex("""-[a-zA-Z가-힣]+:.+""")

    /** 켜기/끄기 낱말. 모르면 null. */
    fun bool(text: String): Boolean? = when (text.trim().lowercase()) {
        "true", "on", "yes", "켜기", "켬", "예", "네", "1" -> true
        "false", "off", "no", "끄기", "끔", "아니오", "0" -> false
        else -> null
    }

    // --- IP --------------------------------------------------------------------------------------

    private val IPV4 = Regex("""^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$""")
    private val IPV6 = Regex("""^[0-9a-fA-F:]{2,39}$""")

    fun isIp(text: String): Boolean = IPV4.matches(text) || (text.count { it == ':' } >= 2 && IPV6.matches(text))

    /** CMI 의 IP 칸(`1_2_3_4`·`1.2.3.4`, 뒤에 `:횟수`·`=횟수` 가 붙을 수 있다)에서 IP 와 횟수를 뽑는다. 못 읽은 조각은 [problem]. */
    fun cmiIps(raw: String?, problem: (String) -> Unit): Map<String, Int> {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() && it != "{}" && it != "[]" } ?: return emptyMap()
        val out = LinkedHashMap<String, Int>()
        val found = Regex("""(\d{1,3}[._]\d{1,3}[._]\d{1,3}[._]\d{1,3})(?:"?\s*[:=]\s*"?(\d+))?""").findAll(text).toList()
        for (m in found) {
            val ip = m.groupValues[1].replace('_', '.')
            if (!isIp(ip)) {
                problem(m.value)
                continue
            }
            out[ip] = (out[ip] ?: 0) + (m.groupValues[2].toIntOrNull() ?: 1)
        }
        if (found.isEmpty()) problem(text.take(120))
        return out
    }

    // --- 경고 ------------------------------------------------------------------------------------

    /** 점수가 [before] 에서 [after] 로 올랐을 때 새로 넘은 문턱들(오름차순). */
    fun crossed(thresholds: Collection<Int>, before: Int, after: Int): List<Int> =
        thresholds.filter { it in (before + 1)..after }.sorted()

    // --- 명령 허용 --------------------------------------------------------------------------------

    /**
     * [line](앞 `/` 없음)이 허용 목록 [allowed] 에 드나. 첫 낱말은 이름공간(`minecraft:`)을 떼고 대소문자 무시로 견준다. 여러 낱말 항목
     * (`에센셜 도움`)은 그 낱말들로 시작하면.
     */
    fun commandAllowed(line: String, allowed: Collection<String>): Boolean {
        val words = line.trim().removePrefix("/").split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val head = words[0].substringAfter(':').lowercase()
        for (entry in allowed) {
            val parts = entry.trim().removePrefix("/").split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            if (parts[0].substringAfter(':').lowercase() != head) continue
            if (parts.size == 1) return true
            if (words.size >= parts.size && (1 until parts.size).all { words[it].equals(parts[it], true) }) return true
        }
        return false
    }

    // --- 중복 계정 --------------------------------------------------------------------------------

    /** 오프라인 서버의 uuid(이름으로 짓는 v3). */
    fun offlineUuid(name: String): UUID = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))

    /**
     * 같은 이름을 가진 기록 가운데 **서버 방식과 맞지 않는** uuid(온라인 서버의 v3 · 오프라인 서버의 v4) — 단 같은 이름에 맞는 uuid 가
     * 하나라도 있을 때만(그 사람의 진짜 계정이 남는다). 이름을 넘겨받은 다른 사람의 옛 기록은 건드리지 않는다(둘 다 v4).
     */
    fun duplicates(records: Collection<Pair<UUID, String>>, onlineMode: Boolean): List<UUID> {
        val out = ArrayList<UUID>()
        for ((_, group) in records.groupBy { it.second.lowercase() }) {
            if (group.size < 2) continue
            fun proper(id: UUID, name: String): Boolean = if (onlineMode) id.version() == 4 else id == offlineUuid(name)
            if (group.none { proper(it.first, it.second) }) continue
            for ((id, name) in group) {
                val wrong = if (onlineMode) id == offlineUuid(name) else id.version() == 4
                if (wrong) out += id
            }
        }
        return out
    }

    // --- 이름 ------------------------------------------------------------------------------------

    private val NAME = Regex("""^[a-z0-9_가-힣-]{1,32}$""")

    /** 감옥·분류·메타 키 이름 규칙(소문자로 견준다). */
    fun validName(name: String): Boolean = NAME.matches(name.lowercase())
}
