package com.inmc.essentials.admininfo

import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer

/** CMI 가져오기 중 서버 없이 시험할 수 있는 판독·변환(형식은 CMI 9.8.9.8 jar 로 확인). */
internal object CmiConvert {

    /** 바꾼 명령 · 그중 `cmi …` 로 남아 CMI 를 빼면 안 도는 것 · 옮기지 않은 원문(콘솔이 칠 수 없는 특수 명령). */
    class Commands(val commands: List<String>, val cmi: List<String>, val rejected: List<String>)

    private val SPECIAL = Regex("^[A-Za-z]+!")

    /**
     * CMI 명령 줄 → 콘솔이 칠 명령. `asConsole!` 은 뗀다. 다른 `xxx!` 특수 명령(`asPlayer!`·`delay!`·`closeinv!` …)은 콘솔로 옮길 수 없어
     * [Commands.rejected] 로. `[playerName]`·`[playerDisplayName]` → `{player}`, `[serviceName]` → `{service}`.
     */
    fun commands(raw: List<String>): Commands {
        val out = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        for (line in raw) {
            var c = line.trim()
            if (c.startsWith("asConsole!", true)) c = c.substring("asConsole!".length).trim()
            if (SPECIAL.containsMatchIn(c)) {
                rejected += line
                continue
            }
            c = c.replace("[playerName]", "{player}").replace("[playerDisplayName]", "{player}").replace("[serviceName]", "{service}")
            if (c.isNotEmpty()) out += c
        }
        return Commands(out, out.filter { it.startsWith("cmi ", true) }, rejected)
    }

    private val LEGACY = Regex("[&§][0-9a-fk-orA-FK-OR#]")

    /** CMI 글(`&7Hourly reward`)을 MiniMessage 로. `&`·`§` 색 부호가 없으면 그대로(이미 MiniMessage 일 수 있다). */
    fun legacyToMini(text: String): String {
        if (!LEGACY.containsMatchIn(text)) return text
        val serializer = if (text.contains('§')) LegacyComponentSerializer.legacySection() else LegacyComponentSerializer.builder().character('&').hexColors().build()
        return MiniMessage.miniMessage().serialize(serializer.deserialize(text))
    }

    /** CMI `VanishAction` 의 순서(= users.Vanish 열의 자리). 마지막 `level` 은 숫자. */
    val VANISH_ORDER: List<String> = listOf(
        "isVanished", "damageToEntity", "playerDamage", "itemPickup", "mobAggro", "interaction", "noisyChest", "informOnLeave", "informOnJoin",
        "nightVision", "bossbar", "afkcommands", "PrivateMessages", "relogDisable", "noMessages", "fakeJoinLeave", "mobSpawning", "stopPlaytime",
        "sleepIgnore", "joinVanished", "deathMessages", "hookPlayers", "level",
    )

    /** 다른 기능의 몫이라 옮기지 않는 CMI 투명 옵션. */
    val VANISH_ELSEWHERE: Set<String> = setOf("noisyChest", "afkcommands", "PrivateMessages")

    /** CMI 투명 한 사람. [values] 는 CMI 이름 → 값(`isVanished`·`level` 제외). */
    data class Vanish(val vanished: Boolean, val values: Map<String, Boolean>, val level: Int?)

    /**
     * CMI users 표의 `Vanish` 열(`CMIVanish.toString` — [VANISH_ORDER] 자리마다 `1`/`0` 을 `:` 로 이은 것, `level` 은 숫자).
     * `:` 가 없으면 CMI 도 읽지 않는다 → null. 자리가 모자라면 있는 것만. `1`·`0` 이 아닌 자리는 건너뛴다.
     */
    fun parseVanish(raw: String?): Vanish? {
        if (raw == null || !raw.contains(':')) return null
        val parts = raw.trim().split(':')
        var vanished = false
        var level: Int? = null
        val values = LinkedHashMap<String, Boolean>()
        for ((i, name) in VANISH_ORDER.withIndex()) {
            val part = parts.getOrNull(i)?.trim() ?: break
            when (name) {
                "level" -> level = part.toIntOrNull()
                "isVanished" -> vanished = part == "1"
                else -> when (part) {
                    "1" -> values[name] = true
                    "0" -> values[name] = false
                }
            }
        }
        return Vanish(vanished, values, level)
    }

    /** CMI `playtimereward.onetime`(`CMIUser.getPTROString` — 받은 한 번 보상 이름을 `;` 로, 소문자). */
    fun oneTimeClaims(raw: String?): Set<String> =
        raw.orEmpty().split(';').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    /** CMI 보상 이름 → 이 기능의 id(영문 소문자·숫자·`_-` 1~32자). */
    fun rewardId(cmiName: String): String = cmiName.lowercase().replace(Regex("[^a-z0-9_-]"), "_").take(32)
}

/** 투표 계산. */
internal object VoteMath {

    /**
     * 이번 투표로 줄 누적 단계 — 수가 [before] 에서 [after] 로 올라가며 **새로 지난** 단계 중 아직 안 받았고([given]) 묶음이 빈 것이 아닌
     * 것([filled]). 관리자가 수를 올렸거나 가져온 수 아래의 단계는 다음 투표 때 몰아 주지 않는다(CMI 도 그 수에 닿는 투표에만 준다).
     */
    fun milestonesToPay(filled: Collection<Int>, given: Set<Int>, before: Int, after: Int): List<Int> =
        filled.filter { it in (before + 1)..after && it !in given }.sorted()
}
