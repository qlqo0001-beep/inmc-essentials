package com.inmc.essentials.item

/**
 * `/지급 <대상> <아이템…> [개수]` 의 꼬리(greedy)를 읽는다 — 순수(서버 없이 시험).
 *
 * 낱말(빈칸으로 가르되 `{…}` 안의 빈칸은 한 낱말):
 * - 아이템: `minecraft:diamond`·`diamond`(재질만)·`inmc:<id>`·`mmoitems:TYPE:ID`·`<네임스페이스>:<id>`(CustomItemHook) ·
 *   `hand`(보내는 사람 손) · `hand:<플레이어>`(그 사람 손 — CMI giveall `h`) · `inv:<플레이어>`(그 사람 가방 전체 — CMI giveall `inv`)
 * - 만들기 꼬리(CMI 처럼): `n{이름}` · `l{줄1|줄2}` · `e{sharpness:5,unbreaking:3}` · `unbreakable`
 * - 숫자 하나 = 개수(아이템 낱말 뒤). `-s` 조용히 · `-slot:<칸>` 그 칸에 · `-d:<일>`(`**` 의 기간) · `offline`/`오프라인`(전원 → 오프라인 포함)
 */
data class GiveSpec(
    val items: List<String>,
    val amount: Int?,
    val name: String? = null,
    val lore: List<String> = emptyList(),
    val enchants: List<Pair<String, Int>> = emptyList(),
    val unbreakable: Boolean = false,
    val silent: Boolean = false,
    val slot: Int? = null,
    val days: Int? = null,
    val offline: Boolean = false,
) {
    val hasBuilder: Boolean get() = name != null || lore.isNotEmpty() || enchants.isNotEmpty() || unbreakable

    sealed interface Parsed {
        data class Ok(val spec: GiveSpec) : Parsed
        data class Error(val reason: String, val token: String) : Parsed
    }

    companion object {

        const val HAND = "hand"
        const val HAND_OF = "hand:"
        const val INV_OF = "inv:"

        fun parse(text: String): Parsed {
            val tokens = tokenize(text) ?: return Parsed.Error(UNCLOSED, text)
            val items = ArrayList<String>()
            var amount: Int? = null
            var name: String? = null
            val lore = ArrayList<String>()
            val enchants = ArrayList<Pair<String, Int>>()
            var unbreakable = false
            var silent = false
            var slot: Int? = null
            var days: Int? = null
            var offline = false
            for (token in tokens) {
                val lower = token.lowercase()
                when {
                    lower == "-s" -> silent = true
                    lower.startsWith("-slot:") -> slot = lower.removePrefix("-slot:").toIntOrNull()?.takeIf { it in 0..40 }
                        ?: return Parsed.Error(BAD_SLOT, token)
                    lower.startsWith("-d:") -> days = lower.removePrefix("-d:").toIntOrNull()?.takeIf { it in 1..3650 }
                        ?: return Parsed.Error(BAD_DAYS, token)
                    lower == "offline" || lower == "오프라인" -> offline = true
                    lower == "unbreakable" || lower == "부서지지않음" -> unbreakable = true
                    braced(token, "n") != null -> name = braced(token, "n")
                    braced(token, "l") != null -> lore += braced(token, "l")!!.split('|')
                    braced(token, "e") != null -> {
                        for (part in braced(token, "e")!!.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                            val key = part.substringBeforeLast(':').trim().lowercase()
                            val level = if (part.contains(':')) part.substringAfterLast(':').trim().toIntOrNull() else 1
                            if (key.isEmpty() || level == null || level !in 1..255) return Parsed.Error(BAD_ENCHANT, part)
                            enchants += key to level
                        }
                    }
                    token.all { it.isDigit() } && token.isNotEmpty() -> {
                        if (items.isEmpty() || amount != null) return Parsed.Error(BAD_AMOUNT, token)
                        amount = token.toIntOrNull()?.takeIf { it >= 1 } ?: return Parsed.Error(BAD_AMOUNT, token)
                    }
                    token.startsWith("-") -> return Parsed.Error(UNKNOWN_FLAG, token)
                    else -> items += token
                }
            }
            if (items.isEmpty()) return Parsed.Error(NO_ITEM, text)
            return Parsed.Ok(GiveSpec(items, amount, name, lore, enchants, unbreakable, silent, slot, days, offline))
        }

        /** `/i <아이템> [꼬리]` 를 자기 지급으로 읽을 때의 꼬리 — 대상 자리의 첫 낱말을 아이템으로 되돌려 붙인다. */
        fun selfText(targetRaw: String, tail: String): String = (targetRaw.trim() + " " + tail.trim()).trim()

        /** `x{…}` 의 안쪽. 그 모양이 아니면 null. */
        fun braced(token: String, letter: String): String? {
            if (token.length < letter.length + 2) return null
            if (!token.startsWith("$letter{", ignoreCase = true) || !token.endsWith("}")) return null
            return token.substring(letter.length + 1, token.length - 1)
        }

        /** 빈칸으로 가르되 `{…}` 안은 한 낱말. 괄호가 안 닫히면 null. */
        fun tokenize(text: String): List<String>? {
            val out = ArrayList<String>()
            val current = StringBuilder()
            var depth = 0
            for (c in text) {
                when {
                    c == '{' -> { depth++; current.append(c) }
                    c == '}' -> { depth--; if (depth < 0) return null; current.append(c) }
                    c.isWhitespace() && depth == 0 -> if (current.isNotEmpty()) { out += current.toString(); current.clear() }
                    else -> current.append(c)
                }
            }
            if (depth != 0) return null
            if (current.isNotEmpty()) out += current.toString()
            return out
        }

        /** 제안용 — 지금 치고 있는 낱말이 시작하는 자리(괄호 안이면 -1). */
        fun lastTokenStart(text: String): Int {
            var depth = 0
            var start = 0
            for ((i, c) in text.withIndex()) {
                when {
                    c == '{' -> depth++
                    c == '}' -> depth = (depth - 1).coerceAtLeast(0)
                    c == ' ' && depth == 0 -> start = i + 1
                }
            }
            return if (depth > 0) -1 else start
        }

        const val UNCLOSED = "unclosed"
        const val BAD_SLOT = "slot"
        const val BAD_DAYS = "days"
        const val BAD_ENCHANT = "enchant"
        const val BAD_AMOUNT = "amount"
        const val UNKNOWN_FLAG = "flag"
        const val NO_ITEM = "no-item"
    }
}

/** `/지급` 의 대상 낱말. */
sealed interface GiveTarget {
    /** 이름 하나(접속 중이면 바로, 아니면 우편). */
    data class Named(val name: String) : GiveTarget

    /** `*`·`all`·`전원` — 보이는 접속자 전원. */
    data object Online : GiveTarget

    /** `**` — 최근 N일 안에 들어온 사람 전원(접속자는 바로, 나머지는 우편). */
    data object Everyone : GiveTarget

    companion object {
        fun parse(raw: String): GiveTarget {
            val text = raw.trim()
            return when (text.lowercase()) {
                "**", "all-offline" -> Everyone
                "*", "all", "전원" -> Online
                else -> Named(text)
            }
        }
    }
}
