package com.inmc.essentials.chat

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** chat 기능의 순수 규칙 — 서버 없이 시험한다(`ChatRulesTest`). */
object ChatRules {

    // --- 명령 ---------------------------------------------------------------------------------

    /** `/minecraft:msg 철수 안녕` → `msg`. 앞의 `/` 와 이름공간을 뗀 소문자 첫 낱말. */
    fun commandRoot(command: String): String =
        command.trim().removePrefix("/").substringBefore(' ').substringAfterLast(':').lowercase()

    /** 바닐라 귓속말 루트(이름공간을 붙여 쳐도 우리 것으로 돌린다 — 사용자 결정). */
    val VANILLA_WHISPER: Set<String> = setOf("msg", "tell", "w")

    /**
     * `/minecraft:msg …`·`/minecraft:tell …`·`/minecraft:w …` 을 `/<우리 이름> …` 으로. 바닐라 것이 아니면 null.
     * 이름공간 없는 `/msg` 는 이미 우리 별칭이라 건드리지 않는다.
     */
    fun rewriteVanillaWhisper(message: String, ours: String): String? {
        if (!message.startsWith("/")) return null
        val head = message.substring(1).substringBefore(' ')
        val colon = head.indexOf(':')
        if (colon < 0) return null
        val namespace = head.substring(0, colon).lowercase()
        val root = head.substring(colon + 1).lowercase()
        if (namespace != "minecraft" || root !in VANILLA_WHISPER) return null
        val rest = message.substring(1 + head.length)
        return "/$ours$rest"
    }

    /** 명령 스파이에 보일 명령인가. [all] = `…spy.all` 권한(보기 목록 무시), [bypass] = `…spy.bypass` 권한(숨김 목록 무시). */
    fun spyShows(command: String, settings: ChatSettings, all: Boolean, bypass: Boolean): Boolean {
        val root = commandRoot(command)
        if (root.isEmpty()) return false
        if (!bypass && root in settings.spyHidden) return false
        if (!all && settings.spyOnly.isNotEmpty() && root !in settings.spyOnly) return false
        return true
    }

    // --- CMI 개인 설정 ------------------------------------------------------------------------------

    /** CMI `PlayerOption` 순서(CMI 9.8.9.8). `users.Options` 는 이 순서대로 `1`/`0` 을 `:` 로 이은 것이다(`PlayerOption.fromString`). */
    val CMI_OPTIONS: List<String> = listOf(
        "visibleHolograms", "shiftSignEdit", "totemBossBar", "bossBarCompass", "tagSound", "chatSpy", "cmdSpy", "signSpy", "acceptingPM",
        "acceptingTPA", "acceptingMoney", "chatbubble", "pmSound", "rideMe", "pveDamageNumbers", "pvpDamageNumbers", "InformDurability",
        "receivePets", "deathMessages", "notarget", "showVehicleSpeed",
    )

    data class CmiOptions(val values: Map<String, Boolean>, val bad: List<String>)

    /** `users.Options` 를 읽는다. 비었으면 null(CMI 기본값 그대로). 못 읽은 칸은 [CmiOptions.bad] 에(조용히 버리지 않는다). */
    fun cmiOptions(raw: String?): CmiOptions? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (':' !in text) return CmiOptions(emptyMap(), listOf("'$text' — ':' 로 이은 1/0 목록이 아닙니다"))
        val values = LinkedHashMap<String, Boolean>()
        val bad = ArrayList<String>()
        for ((index, part) in text.split(':').withIndex()) {
            val name = CMI_OPTIONS.getOrNull(index)
            when {
                name == null -> bad += "${index + 1}번째 '$part' — 이 CMI 판에 없는 칸"
                part.trim() == "1" -> values[name] = true
                part.trim() == "0" -> values[name] = false
                else -> bad += "$name '$part' — 1/0 이 아닙니다"
            }
        }
        return CmiOptions(values, bad)
    }

    /** CMI `/ignore all` 의 표시 — 무시 목록에 이 uuid 하나를 넣는다(CMI 9.8.9.8 `PlayerManager.emptyUserUUID`). */
    val CMI_IGNORE_ALL: UUID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")

    /** CMI 무시 목록의 한 칸 → 우리 무시 대상(모두 무시 표시는 [ChatStore.ALL]). */
    fun cmiIgnoreTarget(target: UUID): String = if (target == CMI_IGNORE_ALL) ChatStore.ALL else target.toString()

    // --- 귓속말 앞 기호 -------------------------------------------------------------------------

    data class Whisper(val text: String, val clean: Boolean, val noReply: Boolean)

    /** CMI: `!` = 보낸 이름 없는 깨끗한 글(권한), `!-` = 깨끗하고 답장 불가(권한). 권한이 없으면 글자 그대로. */
    fun parseWhisper(raw: String, mayClean: Boolean, mayNoReply: Boolean): Whisper {
        val text = raw.trim()
        if (mayNoReply && text.startsWith("!-")) return Whisper(text.substring(2).trim(), clean = true, noReply = true)
        if (mayClean && text.startsWith("!") && !text.startsWith("!-")) return Whisper(text.substring(1).trim(), clean = true, noReply = false)
        if (mayClean && !mayNoReply && text.startsWith("!-")) return Whisper(text.substring(1).trim(), clean = true, noReply = false)
        return Whisper(text, clean = false, noReply = false)
    }

    // --- 거리 채팅 -------------------------------------------------------------------------------

    /**
     * 일반 채팅의 거리. null = 제한 없음(모든 월드). [online] = 지금 접속 인원.
     * 동적 거리는 (인원, 거리) 표에서 인원이 그 이상인 가장 큰 줄 — 거리 0 은 제한 없음. 표의 첫 줄보다 적으면 기본 거리.
     */
    fun generalRange(settings: ChatSettings, online: Int): Double? {
        if (settings.rangeGeneral <= 0) return null
        if (settings.dynamicRange) {
            val row = settings.dynamicLimits.lastOrNull { online >= it.first }
            if (row != null) return if (row.second <= 0) null else row.second.toDouble()
        }
        return settings.rangeGeneral.toDouble()
    }

    /** 외침의 거리. null = 모든 월드. 외침을 쓸 수 없으면(거리 채팅이 꺼졌거나 -1) [NO_SHOUT]. */
    fun shoutRange(settings: ChatSettings): Double? = when {
        settings.rangeGeneral <= 0 || settings.rangeShout < 0 -> NO_SHOUT
        settings.rangeShout == 0 -> null
        else -> settings.rangeShout.toDouble()
    }

    /** "외침을 쓸 수 없음" 표식(거리로는 나올 수 없는 값). */
    const val NO_SHOUT: Double = -1.0

    /** 외침 글인가 — `!` 로 시작하고 뒤에 글이 있다. */
    fun isShout(plain: String): Boolean = plain.startsWith("!") && plain.length > 1 && plain.substring(1).isNotBlank()

    /** [range] 가 null 이면 늘 닿는다. 아니면 같은 월드이고 거리 안. */
    fun inRange(range: Double?, sameWorld: Boolean, distanceSquared: Double): Boolean =
        range == null || (sameWorld && distanceSquared <= range * range)

    // --- 공지 · 타이틀 옵션 --------------------------------------------------------------------------

    /**
     * [badRange] = 거리 조각을 읽지 못했다(숫자가 아니거나 0 이하 — 원문). 부르는 쪽이 알리고 공지를 보내지 않는다(서버 전체 공지가 되지 않게).
     */
    data class Broadcast(val text: String, val clean: Boolean, val worlds: Set<String>, val range: Double?, val center: List<String>?, val badRange: String? = null)

    /**
     * CMI `/broadcast (!) [글] (-w:월드,월드) (-r:거리) (-c:월드;x;y;z)`. 한글 `-월드:` `-거리:` `-위치:` 도 받는다. 옵션은 어디에 있어도 된다.
     * 거리가 양수가 아니면 [Broadcast.badRange]. 중심(-c)은 조각 그대로 넘긴다(월드·숫자 확인은 부르는 쪽 — 서버가 있어야 한다).
     */
    fun parseBroadcast(raw: String): Broadcast {
        val worlds = LinkedHashSet<String>()
        var range: Double? = null
        var badRange: String? = null
        var center: List<String>? = null
        val words = ArrayList<String>()
        for (token in raw.trim().split(' ')) {
            val lower = token.lowercase()
            val value = when {
                lower.startsWith("-w:") -> { token.substring(3).split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach(worlds::add); continue }
                lower.startsWith("-월드:") -> { token.substring(4).split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach(worlds::add); continue }
                lower.startsWith("-r:") -> token.substring(3)
                lower.startsWith("-거리:") -> token.substring(4)
                lower.startsWith("-c:") -> { center = token.substring(3).split(';'); continue }
                lower.startsWith("-위치:") -> { center = token.substring(4).split(';'); continue }
                else -> { words += token; continue }
            }
            range = value.toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }
            badRange = if (range == null) token else null
        }
        var text = words.joinToString(" ").trim()
        val clean = text.startsWith("!")
        if (clean) text = text.substring(1).trim()
        return Broadcast(text, clean, worlds, range, center, badRange)
    }

    /** 공지의 거리 옵션이 틀렸나 — 거리 조각을 못 읽었거나, 중심(-c)만 주고 거리(-r)를 빠뜨렸다. 틀렸으면 알릴 원문. */
    fun broadcastRangeProblem(parsed: Broadcast): String? = when {
        parsed.badRange != null -> parsed.badRange
        parsed.center != null && parsed.range == null -> "-c:" + parsed.center.joinToString(";")
        else -> null
    }

    // --- 귓속말 그룹 ------------------------------------------------------------------------------

    /**
     * 받는 사람의 귓속말 그룹(`private.groups.order` 의 앞에서부터) — **실제로 준** `group.<이름>` 만 본다([explicit] = `isPermissionSet && hasPermission`).
     * `hasPermission` 만 보면 선언되지 않은 노드라 OP·`*` 가 모든 그룹을 가진 것으로 보여 늘 첫 그룹이 된다.
     */
    fun groupOf(order: List<String>, explicit: (String) -> Boolean): String? = order.firstOrNull { explicit("group.$it") }

    data class TitleText(val title: String, val subtitle: String, val fadeIn: Int, val stay: Int, val fadeOut: Int)

    /**
     * CMI `/titlemsg [대상] [제목 \n 부제목] (-in:틱) (-out:틱) (-keep:틱)`. 줄 나누기는 글자 그대로의 `\n`. 한글 `-들어옴:` `-나감:` `-유지:` 도.
     */
    fun parseTitle(raw: String, settings: ChatSettings): TitleText {
        var fadeIn = settings.titleFadeIn
        var stay = settings.titleStay
        var fadeOut = settings.titleFadeOut
        val words = ArrayList<String>()
        for (token in raw.trim().split(' ')) {
            val lower = token.lowercase()
            fun ticks(prefix: Int): Int? = token.substring(prefix).toIntOrNull()?.coerceIn(0, 12000)
            when {
                lower.startsWith("-in:") -> ticks(4)?.let { fadeIn = it } ?: words.add(token)
                lower.startsWith("-out:") -> ticks(5)?.let { fadeOut = it } ?: words.add(token)
                lower.startsWith("-keep:") -> ticks(6)?.let { stay = it } ?: words.add(token)
                lower.startsWith("-들어옴:") -> ticks(5)?.let { fadeIn = it } ?: words.add(token)
                lower.startsWith("-나감:") -> ticks(4)?.let { fadeOut = it } ?: words.add(token)
                lower.startsWith("-유지:") -> ticks(4)?.let { stay = it } ?: words.add(token)
                else -> words.add(token)
            }
        }
        val text = words.joinToString(" ")
        val split = text.split("\\n", limit = 2)
        return TitleText(split[0].trim(), split.getOrElse(1) { "" }.trim(), fadeIn, stay, fadeOut)
    }

    // --- 도배 ------------------------------------------------------------------------------------

    /** 두 글이 얼마나 같은가(0~100). 대소문자·빈칸을 무시한 편집 거리 기준. */
    fun similarity(a: String, b: String): Int {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() && y.isEmpty()) return 100
        val longest = maxOf(x.length, y.length)
        if (longest == 0) return 100
        return ((longest - distance(x, y)) * 100) / longest
    }

    private fun normalize(text: String): String = text.lowercase().filterNot { it.isWhitespace() }

    /** 레벤슈타인 거리(두 줄만 들고 계산). 채팅 글은 짧다(최대 256자). */
    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}

/**
 * 같은 글 도배 막기(CMI DuplicatedMessagePrevention). 사람마다 최근 [ChatSettings.spamIntervalSeconds] 초의 글을 들고, 새 글과
 * [ChatSettings.spamPercentage]% 이상 같은 것이 [ChatSettings.spamMinAmount] 개 이상이면 막는다(막힌 글은 기록하지 않는다 — 기다리면 풀린다).
 * 채팅 사건(비동기)과 귓속말(메인)이 같이 부른다 — 사람마다 목록을 잠그고 쓴다.
 */
class SpamGuard {

    private class Line(val text: String, val at: Long)

    private val history = ConcurrentHashMap<String, ArrayDeque<Line>>()

    /** 막아야 하면 true. 아니면 기록하고 false. [channel] 은 공개 채팅·귓속말을 따로 센다. */
    fun check(id: UUID, channel: String, text: String, now: Long, settings: ChatSettings): Boolean {
        val lines = history.computeIfAbsent("$id/$channel") { ArrayDeque() }
        synchronized(lines) {
            val since = now - settings.spamIntervalSeconds * 1000L
            while (lines.isNotEmpty() && lines.first().at < since) lines.removeFirst()
            val similar = lines.count { ChatRules.similarity(it.text, text) >= settings.spamPercentage }
            if (similar >= settings.spamMinAmount) return true
            lines.addLast(Line(text, now))
            while (lines.size > MAX_LINES) lines.removeFirst()
            return false
        }
    }

    fun forget(id: UUID) {
        history.keys.removeIf { it.startsWith("$id/") }
    }

    private companion object {
        const val MAX_LINES = 32
    }
}

/**
 * 답장 상대 장부(CMI ReplyToLastMessenger · LastMessengerTimeOut). 메모리만 — 재시작하면 비운다.
 *
 * - [sent]: 내가 X 에게 보냈다 → 내 대화 상대 = X(지금).
 * - [received]: X 가 내게 보냈다 → 마지막으로 보낸 사람 = X. 대화 상대가 X 면 시각만 새로.
 * - [target]: ReplyToLastMessenger 면 마지막으로 보낸 사람. 아니면 시간 안의 대화 상대가 있으면 그 사람, 없으면 마지막으로 보낸 사람(그것도 없으면 옛 대화 상대).
 */
class ReplyBook {

    private data class Mark(val id: UUID, val at: Long)

    private val partner = ConcurrentHashMap<UUID, Mark>()
    private val lastFrom = ConcurrentHashMap<UUID, Mark>()

    fun sent(from: UUID, to: UUID, now: Long) {
        partner[from] = Mark(to, now)
    }

    fun received(to: UUID, from: UUID, now: Long) {
        lastFrom[to] = Mark(from, now)
        partner.computeIfPresent(to) { _, mark -> if (mark.id == from) Mark(from, now) else mark }
    }

    fun target(who: UUID, now: Long, settings: ChatSettings): UUID? {
        val last = lastFrom[who]
        if (settings.replyToLastMessenger) return last?.id
        val talk = partner[who]
        if (talk != null && now - talk.at <= settings.replyTimeoutSeconds * 1000L) return talk.id
        return last?.id ?: talk?.id
    }

    /** 검증기용 — 한 사람의 기록을 꺼냈다가 되돌린다. */
    internal fun snapshot(who: UUID): Pair<Any?, Any?> = partner[who] to lastFrom[who]

    internal fun restore(who: UUID, snap: Pair<Any?, Any?>) {
        (snap.first as? Mark)?.let { partner[who] = it } ?: partner.remove(who)
        (snap.second as? Mark)?.let { lastFrom[who] = it } ?: lastFrom.remove(who)
    }

    fun forget(who: UUID) {
        partner.remove(who)
        lastFrom.remove(who)
    }
}
