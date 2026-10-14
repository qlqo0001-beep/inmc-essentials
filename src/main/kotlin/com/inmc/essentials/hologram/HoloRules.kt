package com.inmc.essentials.hologram

/** 서버 없이 도는 홀로그램 규칙들(`HologramRulesTest`). */
object HoloRules {

    /** 한글·영문·숫자·`_` `-` 32자까지 — 점은 YAML 경로가 되므로 안 된다(지뢰 1). */
    val NAME = Regex("^[A-Za-z0-9_\\-가-힣]{1,32}$")

    /** `[다음쪽]` 같은 쪽 넘기기 동작. */
    val NEXT_PAGE = setOf("[nextpage]", "[다음쪽]", "[다음]")
    val PREV_PAGE = setOf("[prevpage]", "[이전쪽]", "[이전]")

    /** 사람마다 다르게 그려야 하는 토큰(우리 것). PAPI 는 [isPersonal] 이 따로 본다. */
    private val PLAYER_TOKENS = listOf("{player}", "{플레이어}", "{플레이어네임}")

    private val PAPI = Regex("%([A-Za-z0-9]+)_[^%\\s]+%")

    fun keyOf(name: String): String = name.trim().lowercase()

    /**
     * 사람마다 엔티티를 따로 둬야 하는 글인가 — `{player}` 가 있거나, [globalPrefixes](예: `server_`) 로 시작하지 않는 PAPI 가 있으면.
     * 사람마다의 엔티티는 비싸므로(보는 사람 수만큼) 정말 필요한 줄만.
     */
    fun isPersonal(text: String, globalPrefixes: List<String>): Boolean {
        val lower = text.lowercase()
        if (PLAYER_TOKENS.any { lower.contains(it) }) return true
        for (m in PAPI.findAll(text)) {
            val body = m.value.trim('%').lowercase()
            if (globalPrefixes.none { body.startsWith(it.lowercase()) }) return true
        }
        return false
    }

    /**
     * 사람 없이 그릴 때(공용 묶음 — `max-personal-viewers` 를 넘은 사람들) 사람마다의 토큰을 [fallback] 으로 바꾼다 — `{player}` 가 글자 그대로
     * 보이거나, 사람 PAPI 가 사람 없이(null) 풀려 확장이 던지지 않게. [globalPrefixes] 로 시작하는 PAPI 는 그대로 둔다.
     */
    fun stripPersonal(text: String, globalPrefixes: List<String>, fallback: String): String {
        var out = text
        for (token in PLAYER_TOKENS) out = out.replace(token, fallback, ignoreCase = true)
        return PAPI.replace(out) { m ->
            val body = m.value.trim('%').lowercase()
            if (globalPrefixes.any { body.startsWith(it.lowercase()) }) m.value else fallback
        }
    }

    /** 보여 줄 쪽들(줄이 있는 쪽의 번호, 0부터). 다 비었으면 첫 쪽 하나 — 빈 쪽으로 넘어가 아무것도 안 보이고 누를 것도 없는 일이 없게. */
    fun shownPages(lineCounts: List<Int>): List<Int> = lineCounts.indices.filter { lineCounts[it] > 0 }.ifEmpty { listOf(0) }

    /** 보여 줄 쪽들 중 [page] 쪽, 비었으면 그 뒤로 처음 줄이 있는 쪽(없으면 처음)의 자리(0부터 — [shown] 의 번호). */
    fun shownIndexOf(shown: List<Int>, page: Int): Int =
        shown.indexOf(page).takeIf { it >= 0 } ?: shown.indexOfFirst { it > page }.takeIf { it >= 0 } ?: 0

    /** 다시 그릴 만한 글인가(플레이스홀더·토큰이 있다). 없으면 갱신 주기에도 다시 그리지 않는다. */
    fun isDynamic(text: String): Boolean = text.contains('%') || PLAYER_TOKENS.any { text.lowercase().contains(it) }

    /** 사람마다 보는 쪽 — 모두의 기준 쪽 + 그 사람이 넘긴 만큼. */
    fun pageOf(global: Int, offset: Int, pages: Int): Int = if (pages <= 1) 0 else Math.floorMod(global + offset, pages)

    /** [target] 쪽을 보게 하는 넘긴 만큼(offset). */
    fun offsetFor(global: Int, target: Int, pages: Int): Int = if (pages <= 1) 0 else Math.floorMod(target - global, pages)

    /**
     * 줄 가리키기: `3`(= [page] 쪽의 3번째 줄) · `2:3`(2쪽 3번째). 1부터 센 것을 0부터로. 못 읽으면 null.
     */
    fun lineRef(raw: String, page: Int = 0): Pair<Int, Int>? {
        val text = raw.trim()
        val parts = text.split(':')
        return when (parts.size) {
            1 -> parts[0].toIntOrNull()?.takeIf { it >= 1 }?.let { page to it - 1 }
            2 -> {
                val p = parts[0].toIntOrNull()?.takeIf { it >= 1 } ?: return null
                val l = parts[1].toIntOrNull()?.takeIf { it >= 1 } ?: return null
                p - 1 to l - 1
            }
            else -> null
        }
    }

    /**
     * 보이기 조건. `<왼쪽> <연산> <오른쪽>` — 연산: `==` `!=` `>=` `<=` `>` `<` `contains`(포함) `!contains`(안포함).
     * 양쪽은 [resolve](PAPI)를 거친다. 둘 다 숫자면 숫자로, 아니면 글자로(대소문자 무시). **못 읽으면 false** — 숨길 것을 잘못 보이는 것보다 낫다.
     */
    fun condition(raw: String, resolve: (String) -> String): Boolean {
        val text = raw.trim()
        if (text.isEmpty()) return true
        for (op in OPS) {
            val at = findOp(text, op) ?: continue
            val left = resolve(text.substring(0, at).trim()).trim()
            val right = resolve(text.substring(at + op.length).trim()).trim()
            return compare(left, op, right)
        }
        return false
    }

    /** 조건을 읽을 수 있는가(편집 화면의 경고). */
    fun conditionValid(raw: String): Boolean {
        val text = raw.trim()
        return text.isEmpty() || OPS.any { findOp(text, it) != null }
    }

    /** 긴 것부터 — `>=` 를 `>` 로 자르지 않게. 글자 연산은 앞뒤에 빈칸이 있어야. */
    private val OPS = listOf("!contains", "contains", "안포함", "포함", ">=", "<=", "==", "!=", ">", "<")

    private val WORD_OPS = setOf("!contains", "contains", "안포함", "포함")

    private fun findOp(text: String, op: String): Int? {
        if (op in WORD_OPS) {
            val at = text.indexOf(" $op ")
            return if (at < 0) null else at + 1
        }
        val at = text.indexOf(op)
        if (at <= 0) return null
        // ">" 를 찾았는데 실은 ">=" 의 일부면 건너뛴다 — 긴 연산을 먼저 보므로 여기에는 "=" 가 뒤따르지 않는 것만 온다.
        if (op.length == 1 && text.getOrNull(at + 1) == '=') return null
        return at
    }

    private fun compare(left: String, op: String, right: String): Boolean {
        val l = left.replace(",", "").toDoubleOrNull()
        val r = right.replace(",", "").toDoubleOrNull()
        return when (op) {
            "contains", "포함" -> left.lowercase().contains(right.lowercase())
            "!contains", "안포함" -> !left.lowercase().contains(right.lowercase())
            "==" -> if (l != null && r != null) l == r else left.equals(right, ignoreCase = true)
            "!=" -> if (l != null && r != null) l != r else !left.equals(right, ignoreCase = true)
            ">=" -> l != null && r != null && l >= r
            "<=" -> l != null && r != null && l <= r
            ">" -> l != null && r != null && l > r
            "<" -> l != null && r != null && l < r
            else -> false
        }
    }

    /** 명령 한 줄 — `player:`·`플레이어:`·CMI `asPlayer!` → 그 사람, 아니면 콘솔. 앞의 `/` 는 뗀다. */
    fun parseCommand(raw: String): Pair<Boolean, String>? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        val lower = text.lowercase()
        var asPlayer = false
        for ((prefix, player) in PREFIXES) {
            if (lower.startsWith(prefix)) {
                asPlayer = player
                text = text.substring(prefix.length).trim()
                break
            }
        }
        text = text.removePrefix("/").trim()
        return if (text.isEmpty()) null else asPlayer to text
    }

    private val PREFIXES = listOf(
        "player:" to true, "플레이어:" to true, "asplayer!" to true,
        "console:" to false, "콘솔:" to false, "asconsole!" to false,
    )

    /** 콘솔이 칠 명령인가(쪽 넘기기도 `player:` 도 아니다) — 콘솔 명령을 더하고 고치는 것은 [HologramsFeature.CONSOLE] 이 있어야. */
    fun isConsoleAction(action: String): Boolean = pageStep(action) == null && parseCommand(action)?.first == false

    /** CMI 명령의 특수 접두(`delay! 5` · `chance! 50` · `asFakeOp!` …) — 우리가 읽는 `asPlayer!`/`asConsole!` 밖의 것. 없으면 null. */
    fun cmiSpecialPrefix(raw: String): String? {
        val first = raw.trim().substringBefore(' ')
        if (!first.endsWith("!") || first.length < 2) return null
        return first.takeUnless { it.equals("asPlayer!", ignoreCase = true) || it.equals("asConsole!", ignoreCase = true) }
    }

    /** 쪽 넘기기 동작이면 +1/-1, 아니면 null. */
    fun pageStep(action: String): Int? = when (action.trim().lowercase()) {
        in NEXT_PAGE -> 1
        in PREV_PAGE -> -1
        else -> null
    }

    /** 배경: null = 기본(반투명 검정), 0 = 없음, 그 밖 = ARGB. 못 읽으면 기본. */
    fun background(raw: String): Int? {
        val text = raw.trim().lowercase()
        if (text.isEmpty() || text == "default" || text == "기본") return null
        if (text == "none" || text == "없음" || text == "transparent") return 0
        val hex = text.removePrefix("#")
        return when (hex.length) {
            6 -> hex.toLongOrNull(16)?.let { (0x40L shl 24 or it).toInt() }
            8 -> hex.toLongOrNull(16)?.toInt()
            else -> null
        }
    }

    /** 누르는 상자 너비(블록) — 글자 수로 짐작(한 글자 ≈ 0.11블록). */
    fun clickWidth(plainLength: Int, scale: Double, fixed: Double): Double =
        if (fixed > 0) fixed else ((plainLength * 0.11 + 0.3) * scale).coerceIn(0.4, 8.0)

    /** 명령 입력의 한 줄 — `icon:<아이템>`·`아이콘:<아이템>` 이면 아이콘 줄의 아이템 문자열, 아니면 null. */
    fun iconSpec(input: String): String? {
        val text = input.trim()
        val lower = text.lowercase()
        for (prefix in listOf("icon:", "아이콘:")) if (lower.startsWith(prefix)) return text.substring(prefix.length).trim().takeIf { it.isNotEmpty() }
        return null
    }

    // --- CMI 글 바꾸기 ----------------------------------------------------------------------------------

    private val HEX = Regex("\\{#([0-9A-Fa-f]{6})}")
    private val NAMED = Regex("\\{#([a-z_]+)}")
    private val CTEXT = Regex("<T>(.*?)</T>(?:<H>(.*?)</H>)?(?:<C>(.*?)</C>)?(?:<H>(.*?)</H>)?", RegexOption.DOT_MATCHES_ALL)

    /** CMI 줄 하나 → 우리 줄 + 알림. `{#rrggbb}`→`<#rrggbb>`, `{#이름}`→`<이름>`, `[playerName]`→`{player}`, CText 의 글·명령을 줄 동작으로. */
    fun fromCmiLine(raw: String): Pair<HoloLine, List<String>> {
        val notes = ArrayList<String>()
        var text = raw
        if (text.trim() == "_") return HoloLine(text = "") to notes // CMI 의 빈 줄
        val upper = text.trim().uppercase()
        if (upper.startsWith("ICON:") || upper.startsWith("SICON:")) {
            val item = text.trim().substringAfter(':').substringBefore(';').trim()
            if (text.contains(';')) notes += "아이콘 줄 '$raw' 의 추가 속성(';' 뒤)은 옮기지 않았습니다"
            return HoloLine(icon = if (item.contains(':')) item else "minecraft:" + item.lowercase()) to notes
        }
        if (upper.startsWith("IMG:") || upper.startsWith("PARTICLE:") || upper.startsWith("BOARD:")) {
            notes += "이미지·파티클·보드 줄 '$raw' 은(는) 글로 남깁니다(같은 기능이 없습니다)"
        }
        var actions = HoloActions.NONE
        if (text.contains("<T>")) {
            val commands = ArrayList<String>()
            val parts = StringBuilder()
            var last = 0
            for (m in CTEXT.findAll(text)) {
                parts.append(text, last, m.range.first)
                parts.append(m.groupValues[1])
                m.groupValues[3].split(";;").map { it.trim() }.filter { it.isNotEmpty() }.forEach { commands += it }
                if (m.groupValues[2].isNotEmpty() || m.groupValues[4].isNotEmpty()) notes += "줄 '$raw' 의 마우스 올리기 글(<H>)은 옮기지 않았습니다"
                last = m.range.last + 1
            }
            parts.append(text.substring(last))
            text = parts.toString()
            if (commands.isNotEmpty()) {
                actions = HoloActions(mapOf(HoloClick.ANY to commands.map { convertCommand(it) }))
                if (CTEXT.findAll(raw).count() > 1) notes += "줄 '$raw' 의 구간별 명령을 줄 하나의 명령으로 합쳤습니다"
            }
        }
        text = HEX.replace(text) { "<#${it.groupValues[1]}>" }
        text = NAMED.replace(text) { "<${it.groupValues[1]}>" }
        text = text.replace("[playerName]", "{player}").replace("[playerDisplayName]", "{player}")
        if (text.contains("{#")) notes += "줄 '$raw' 의 CMI 그라데이션 색은 그대로 둡니다 — MiniMessage <gradient> 로 고치세요"
        val left = (cmiLeftovers(text) + actions.map.values.flatten().flatMap { cmiLeftovers(it) }).distinct()
        if (left.isNotEmpty()) notes += "줄 '$raw' 에 CMI 만의 자리 ${left.joinToString(" ")} 이(가) 남습니다 — CMI 를 빼면 글자 그대로 보입니다"
        return HoloLine(text = text, actions = actions) to notes
    }

    /**
     * CMI 를 빼면 글자 그대로 남는 것 — 우리가 바꾸지 않는 CMI 변수(`[world]`·`[x]` … 소문자로 시작하는 `[낱말]`, 쪽 넘기기 `[nextPage]`·`[prevPage]` 빼고)와
     * `%cmi_…%` PAPI. `[VIP]` 같은 대문자 꼬리표는 건드리지 않는다.
     */
    fun cmiLeftovers(text: String): List<String> =
        (CMI_VARIABLE.findAll(text).map { it.value }.filter { it.lowercase() !in NEXT_PAGE && it.lowercase() !in PREV_PAGE } +
            CMI_PAPI.findAll(text).map { it.value }).distinct().toList()

    private val CMI_VARIABLE = Regex("""\[[a-z][A-Za-z0-9]*]""")
    private val CMI_PAPI = Regex("""%cmi_[^%\s]+%""", RegexOption.IGNORE_CASE)

    /** CMI 색(`{#rrggbb}` · `#rrggbb` · `&#rrggbb` · 이름 색 `{#black}`/`BLACK`) → `RRGGBB`. 못 읽으면 null. */
    fun cmiColorHex(raw: String): String? {
        val text = raw.trim().removePrefix("{").removeSuffix("}").removePrefix("&").removePrefix("#").trim()
        if (text.length == 6 && text.toIntOrNull(16) != null) return text.uppercase()
        return NAMED_COLORS[text.lowercase().replace(' ', '_')]
    }

    /** 마인크래프트 16색(CMIChatColor 이름). */
    private val NAMED_COLORS = mapOf(
        "black" to "000000", "dark_blue" to "0000AA", "dark_green" to "00AA00", "dark_aqua" to "00AAAA", "dark_red" to "AA0000",
        "dark_purple" to "AA00AA", "gold" to "FFAA00", "gray" to "AAAAAA", "dark_gray" to "555555", "blue" to "5555FF", "green" to "55FF55",
        "aqua" to "55FFFF", "red" to "FF5555", "light_purple" to "FF55FF", "yellow" to "FFFF55", "white" to "FFFFFF",
    )

    fun convertCommand(raw: String): String {
        val text = raw.trim().replace("[playerName]", "{player}").replace("[playerDisplayName]", "{player}")
        return when (text.lowercase()) {
            "[nextpage]" -> "[nextPage]"
            "[prevpage]" -> "[prevPage]"
            else -> text
        }
    }

    /** CMI `Lines` → 쪽들(`!nextpage!` 로 나뉜다). */
    fun splitPages(lines: List<String>): List<List<String>> {
        val pages = ArrayList<List<String>>()
        var current = ArrayList<String>()
        for (line in lines) {
            if (line.trim().equals("!nextpage!", ignoreCase = true)) {
                pages += current
                current = ArrayList()
            } else current += line
        }
        pages += current
        return pages.filterIndexed { index, page -> page.isNotEmpty() || index == 0 }
    }
}
