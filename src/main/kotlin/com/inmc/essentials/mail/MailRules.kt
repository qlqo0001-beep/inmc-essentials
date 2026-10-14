package com.inmc.essentials.mail

import kr.inmc.core.integration.Mail
import org.bukkit.configuration.file.YamlConfiguration
import java.util.Base64
import java.util.UUID

/**
 * 우편함 한 칸. [parcel] 은 core 의 불변 우편, [readAt] 은 편지를 연 때(보상은 쓰지 않는다), [rich] 는 편지 글이 MiniMessage 인가
 * (관리자가 색 권한으로 보낸 편지 · CMI 에서 가져온 편지) — 아니면 글자 그대로 보인다.
 */
data class MailEntry(val id: String, val owner: UUID, val parcel: Mail.Parcel, val readAt: Long? = null, val rich: Boolean = false) {

    val isLetter: Boolean get() = parcel.kind == Mail.Kind.LETTER

    /** 받을 것(아이템·돈·명령)이 남았나 — 글만 남은 편지는 false. */
    val hasContent: Boolean get() = MailRules.hasContent(parcel)

    /** 화면·명령에서 가리키는 짧은 이름(id 앞 8자). */
    val handle: String get() = id.take(8)
}

/** mail 기능의 순수 규칙 — 서버 없이 시험한다(`MailRulesTest`). */
object MailRules {

    fun hasContent(parcel: Mail.Parcel): Boolean =
        parcel.items.isNotEmpty() || parcel.money > 0.0 || parcel.moneyBy.values.any { it > 0.0 } || parcel.commands.isNotEmpty()

    // --- 넣기 ------------------------------------------------------------------------------------

    sealed interface Admit {
        /** 같은 사람·origin·key 가 이미 있다 — 넣지 않고 "받았다"(core 계약: 다시 넘겨도 한 통). */
        data object Duplicate : Admit

        /** 넣는다. [dropOldest] 는 함께 지울 편지(CMI 처럼 오래된 것부터 — 설정이 그럴 때만). */
        data class Accept(val dropOldest: List<String> = emptyList()) : Admit

        /** 보상 한도를 넘었다 — 거절(부른 쪽이 제 길로) + 콘솔 경고. */
        data object RewardLimit : Admit

        /** 편지 한도를 넘었다 — 거절(보낸 사람에게 알림). */
        data object LetterLimit : Admit
    }

    /**
     * [existing] 은 그 사람의 우편 전부(오래된 것부터). [spent] 는 이미 받았거나 지운 열쇠 우편의 묘비([spentKey]) — 다 받아 지운 뒤에 core 우편함이
     * (서버가 죽어서) 같은 것을 다시 넘겨도 한 통이다. [force] 는 편지 한도를 넘어서도 넣는다(콘솔·관리자 편지 — 플레이어 편지 도배에 막히지 않게).
     */
    fun admit(existing: List<MailEntry>, parcel: Mail.Parcel, settings: MailSettings, spent: Set<String> = emptySet(), force: Boolean = false): Admit {
        val key = parcel.key
        if (key != null && (spentKey(parcel.origin, key) in spent || existing.any { it.parcel.origin == parcel.origin && it.parcel.key == key })) return Admit.Duplicate
        return if (parcel.kind == Mail.Kind.REWARD) {
            val rewards = existing.count { !it.isLetter }
            if (settings.rewardLimit > 0 && rewards >= settings.rewardLimit) Admit.RewardLimit else Admit.Accept()
        } else {
            val letters = existing.filter { it.isLetter }
            if (force || settings.letterLimit <= 0 || letters.size < settings.letterLimit) return Admit.Accept()
            if (!settings.letterDropOldest) return Admit.LetterLimit
            // 지울 수 있는 것은 받을 것이 남지 않은 편지만 — 아이템이 든 편지를 한도 때문에 버리지 않는다.
            val removable = letters.filter { !it.hasContent }.sortedBy { it.parcel.at }
            val need = letters.size - settings.letterLimit + 1
            if (removable.size < need) Admit.LetterLimit else Admit.Accept(removable.take(need).map { it.id })
        }
    }

    /** 편지의 만료 시각 — 넣는 쪽이 정했으면 그것, 아니면 설정의 일수. 보상은 늘 null(사용자 결정: 보상은 만료 없음). */
    fun expiryOf(parcel: Mail.Parcel, settings: MailSettings): Long? = when {
        parcel.kind == Mail.Kind.REWARD -> null
        parcel.expiresAt != null -> parcel.expiresAt
        settings.letterDays <= 0 -> null
        else -> parcel.at + settings.letterDays * DAY
    }

    /**
     * 지울 때가 된 편지. 보상은 절대 아니고, **받을 것(첨부 아이템·돈·명령)이 남은 편지도 아니다** — 지우면 첨부가 사라진다.
     * 그런 편지는 받은 뒤에 시계가 다시 돈다([expiryAfterClaim]).
     */
    fun expired(entry: MailEntry, now: Long): Boolean {
        val at = entry.parcel.expiresAt ?: return false
        return entry.isLetter && !entry.hasContent && at <= now
    }

    /** 편지의 첨부를 다 받았을 때의 새 만료 시각 — 만료가 있던 편지면 "지금부터 보관 일수" 와 원래 것 중 늦은 쪽. */
    fun expiryAfterClaim(old: Long?, now: Long, settings: MailSettings): Long? {
        if (old == null || settings.letterDays <= 0) return old
        return maxOf(old, now + settings.letterDays * DAY)
    }

    /** 묘비 열쇠(출처 + 열쇠). */
    fun spentKey(origin: String, key: String): String = "$origin\u0000$key"

    /** 쿨타임이 남은 초(올림). [last] 가 없거나 [seconds] 가 0 이면 0. */
    fun cooldownLeft(last: Long?, now: Long, seconds: Int): Long {
        if (last == null || seconds <= 0) return 0L
        val left = last + seconds * 1000L - now
        return if (left <= 0L) 0L else (left + 999L) / 1000L
    }

    /** 받는 사람의 우편함에 [sender] 가 보낸 안 읽은 편지 수(사람마다 한도). */
    fun unreadFrom(existing: List<MailEntry>, sender: UUID): Int =
        existing.count { it.isLetter && it.readAt == null && it.parcel.senderId == sender }

    /**
     * CMI 에서 가져온 편지의 열쇠 — 다시 가져와도 한 통. 시각이 있으면 `cmi:<시각>:<글 해시>`, 없으면 시각 대신 보낸 사람과 그 사람 목록에서의
     * 순번(`cmi:notime:<보낸이>:<글 해시>:<순번>`)으로 — 지금 시각을 쓰면 가져올 때마다 열쇠가 달라진다.
     */
    fun cmiKey(sender: String?, time: Long?, text: String, index: Int): String =
        if (time != null) "cmi:$time:${text.hashCode()}" else "cmi:notime:${sender ?: "?"}:${text.hashCode()}:$index"

    // --- 고르기 · 알림 ----------------------------------------------------------------------------

    /**
     * `/우편 읽기·지우기 <입력>` 의 편지 고르기. `#` 으로 시작하면 id 앞부분으로만(목록의 클릭 명령이 이것을 쓴다 — 앞 8자가 숫자뿐인 id 도 있다).
     * 8자 이상이면 id 앞부분을 먼저, 아니면 번호([letters] 의 1부터 — 가장 새 편지가 1), 그것도 아니면 id 앞부분(4자 이상, 하나만 맞을 때).
     * id 앞부분도 **편지만** 본다 — 보상 우편은 우편함 화면에서 받는 것이라 읽기·지우기 대상이 아니다(책으로 열리거나 읽음 시각이 남으면 안 된다).
     */
    fun pick(letters: List<MailEntry>, all: List<MailEntry>, input: String): MailEntry? {
        val text = input.trim().lowercase()
        val candidates = all.filter { it.isLetter }
        if (text.startsWith("#")) return byPrefix(candidates, text.substring(1))
        if (text.length >= 8) byPrefix(candidates, text)?.let { return it }
        text.toIntOrNull()?.let { return letters.getOrNull(it - 1) }
        return byPrefix(candidates, text)
    }

    private fun byPrefix(all: List<MailEntry>, prefix: String): MailEntry? =
        if (prefix.length < 4) null else all.filter { it.id.startsWith(prefix) }.singleOrNull()

    /** /우편전체 묶음 열쇠([prefix] + 보낸 시각) 중 가장 새것 — 남은 편지·묘비·이번 실행에 보낸 것을 다 넣어 부른다. */
    fun latestBatch(keys: Sequence<String>, prefix: String): String? =
        keys.filter { it.startsWith(prefix) }.maxByOrNull { it.removePrefix(prefix).toLongOrNull() ?: Long.MIN_VALUE }

    /** 묶음 열쇠의 보낸 시각(못 읽으면 null). */
    fun batchTime(key: String, prefix: String): Long? = key.removePrefix(prefix).toLongOrNull()

    /** 접속 알림의 (안 읽은 편지, 받을 것이 남은 우편) 수. */
    fun joinCounts(entries: List<MailEntry>): Pair<Int, Int> =
        entries.count { it.isLetter && it.readAt == null } to entries.count { it.hasContent }

    // --- 받기 ------------------------------------------------------------------------------------

    /**
     * 한 통 받기의 결과. [rest] = 남은 우편(받을 것이 없으면 내용이 빈 것), [done] = 받을 것을 다 줬다, [partial] = 가방이 모자라 아이템이 남았다,
     * [moneyHeld] = 돈을 못 줘 돈만 남았다, [broken] = 되살리지 못해 버린 아이템 수, [commands] = 이제 실행할 명령.
     */
    data class Claim(val rest: Mail.Parcel, val done: Boolean, val partial: Boolean, val moneyHeld: Boolean, val broken: Int, val commands: List<String>)

    /**
     * core `Mailbox.claimAll` 과 같은 순서: 아이템을 가방에 → 안 들어간 것은 남김(그때는 돈·명령을 주지 않는다) → 다 들어갔으면 돈 → 이름 있는 화폐
     * (막히면 나머지를 들고 남음, 명령도 미룸) → 명령.
     *
     * @param give 아이템 바이트 하나를 가방에 넣고 안 들어간 것의 바이트를 돌려준다. 되살리지 못하면 null(버림).
     * @param pay (금액, 화폐 id — 기본은 null) 를 주고 성공 여부.
     */
    fun claim(parcel: Mail.Parcel, give: (ByteArray) -> List<ByteArray>?, pay: (Double, String?) -> Boolean): Claim {
        val left = ArrayList<ByteArray>()
        var broken = 0
        for (bytes in parcel.items) {
            val overflow = give(bytes)
            if (overflow == null) broken++ else left += overflow
        }
        if (left.isNotEmpty()) return Claim(parcel.copy(items = left), done = false, partial = true, moneyHeld = false, broken = broken, commands = emptyList())
        var money = parcel.money
        if (money > 0.0 && pay(money, null)) money = 0.0
        val moneyBy = LinkedHashMap<String, Double>()
        var blocked = money > 0.0
        for ((currency, amount) in parcel.moneyBy) {
            if (amount <= 0.0) continue
            if (blocked || !pay(amount, currency)) {
                blocked = true
                moneyBy[currency] = amount
            }
        }
        if (blocked) {
            return Claim(parcel.copy(items = emptyList(), money = money, moneyBy = moneyBy), done = false, partial = false, moneyHeld = true, broken = broken, commands = emptyList())
        }
        return Claim(
            parcel.copy(items = emptyList(), money = 0.0, moneyBy = emptyMap(), commands = emptyList()),
            done = true, partial = false, moneyHeld = false, broken = broken, commands = parcel.commands,
        )
    }

    // --- 저장 모양 ----------------------------------------------------------------------------------

    /** 받을 것(아이템·돈·명령)과 글 형식을 한 칸에 — YAML. 화폐 id 는 열쇠가 아니라 `id=금액` 줄로(id 의 점이 경로가 되지 않게 · 지뢰 1). */
    fun encode(parcel: Mail.Parcel, rich: Boolean): String {
        val yaml = YamlConfiguration()
        if (parcel.items.isNotEmpty()) yaml.set("items", parcel.items.map { Base64.getEncoder().encodeToString(it) })
        if (parcel.money > 0.0) yaml.set("money", parcel.money)
        if (parcel.moneyBy.isNotEmpty()) yaml.set("money-by", parcel.moneyBy.map { (k, v) -> "$k=$v" })
        if (parcel.commands.isNotEmpty()) yaml.set("commands", parcel.commands)
        if (rich) yaml.set("rich", true)
        return yaml.saveToString()
    }

    data class Payload(val items: List<ByteArray>, val money: Double, val moneyBy: Map<String, Double>, val commands: List<String>, val rich: Boolean, val bad: List<String>)

    /** [encode] 의 거꾸로. 못 읽은 조각은 [Payload.bad] 에 원문으로(조용히 버리지 않는다 — 부른 쪽이 로그로). */
    fun decode(text: String?): Payload {
        if (text.isNullOrBlank()) return Payload(emptyList(), 0.0, emptyMap(), emptyList(), false, emptyList())
        val yaml = YamlConfiguration()
        val bad = ArrayList<String>()
        try {
            yaml.loadFromString(text)
        } catch (t: Throwable) {
            return Payload(emptyList(), 0.0, emptyMap(), emptyList(), false, listOf("YAML: ${t.message}"))
        }
        val items = yaml.getStringList("items").mapNotNull { raw ->
            runCatching { Base64.getDecoder().decode(raw) }.getOrNull() ?: null.also { bad += "아이템 ${raw.take(24)}…" }
        }
        val moneyBy = LinkedHashMap<String, Double>()
        for (line in yaml.getStringList("money-by")) {
            val id = line.substringBeforeLast('=', "")
            val amount = line.substringAfterLast('=').toDoubleOrNull()
            if (id.isEmpty() || amount == null) bad += "화폐 $line" else moneyBy[id] = amount
        }
        return Payload(items, yaml.getDouble("money", 0.0), moneyBy, yaml.getStringList("commands"), yaml.getBoolean("rich", false), bad)
    }

    /** 편지 글을 책 쪽으로 나눈다 — 줄을 지키고 한 쪽에 [perPage] 자까지. */
    fun pages(text: String, perPage: Int = 180): List<String> {
        val out = ArrayList<String>()
        val page = StringBuilder()
        for (line in text.split('\n')) {
            var rest = line
            while (true) {
                val room = perPage - page.length - (if (page.isEmpty()) 0 else 1)
                if (rest.length <= room) {
                    if (page.isNotEmpty()) page.append('\n')
                    page.append(rest)
                    break
                }
                if (room > 0 && page.isEmpty()) {
                    page.append(rest, 0, room)
                    rest = rest.substring(room)
                }
                out += page.toString()
                page.setLength(0)
            }
        }
        if (page.isNotEmpty() || out.isEmpty()) out += page.toString()
        return out
    }

    const val DAY = 86_400_000L
}

/**
 * 같은 경고를 [windowMillis] 안에 한 번만 — 보상 한도로 거절한 우편은 core 우편함이 저장 차례마다 다시 넘겨, 그대로 두면 같은 경고가 끝없이 찍힌다.
 * 열쇠가 많아지면 오래된 것부터 걷는다.
 */
class DailyWarnings(private val windowMillis: Long = MailRules.DAY, private val maxKeys: Int = 4096) {

    private val last = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 지금 알려야 하면 true(그리고 시각을 적는다). */
    fun shouldWarn(key: String, now: Long): Boolean {
        var warn = false
        last.compute(key) { _, at -> if (at == null || now - at >= windowMillis) now.also { warn = true } else at }
        if (warn && last.size > maxKeys) last.values.removeIf { now - it >= windowMillis }
        return warn
    }
}
