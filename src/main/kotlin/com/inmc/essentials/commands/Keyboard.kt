package com.inmc.essentials.commands

/**
 * 두벌식 자판 변환(순수). 한글 모드로 영어 명령을 치거나(`넴주` = spawn) 영어 모드로 한글 명령을 친 것(`tmvhs` = 스폰)을 알아본다.
 *
 * 합성(키 → 한글)은 하지 않는다 — 양쪽을 **키 문자열**로 풀어 견주면 된다. 한글은 음절을 자모로 나누고(겹모음·겹받침도 둘로)
 * 자모마다 두벌식 키로, 그 밖의 글자는 그대로 둔다. 그래서 `넴주` 와 `네ㅁ주`(받침을 안 붙인 것)가 같은 키(`spawn`)가 된다.
 * 대문자는 쌍자음·ㅒ·ㅖ(R E Q T W O P)에만 뜻이 있다 — 견줄 때 정확히 같지 않으면 소문자로 한 번 더 본다([KeyboardIndex]).
 */
object Keyboard {

    private const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private const val JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
    private val JONG = listOf(
        "", "ㄱ", "ㄲ", "ㄳ", "ㄴ", "ㄵ", "ㄶ", "ㄷ", "ㄹ", "ㄺ", "ㄻ", "ㄼ", "ㄽ", "ㄾ", "ㄿ", "ㅀ",
        "ㅁ", "ㅂ", "ㅄ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ",
    )

    /** 자모 → 두벌식 키. */
    private val KEY: Map<Char, Char> = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎㅏㅐㅑㅒㅓㅔㅕㅖㅗㅛㅜㅠㅡㅣ"
        .zip("rRseEfaqQtTdwWczxvgkoiOjpuPhynbml").toMap()

    /** 겹모음·겹받침 → 두 자모. */
    private val SPLIT: Map<Char, String> = mapOf(
        'ㅘ' to "ㅗㅏ", 'ㅙ' to "ㅗㅐ", 'ㅚ' to "ㅗㅣ", 'ㅝ' to "ㅜㅓ", 'ㅞ' to "ㅜㅔ", 'ㅟ' to "ㅜㅣ", 'ㅢ' to "ㅡㅣ",
        'ㄳ' to "ㄱㅅ", 'ㄵ' to "ㄴㅈ", 'ㄶ' to "ㄴㅎ", 'ㄺ' to "ㄹㄱ", 'ㄻ' to "ㄹㅁ", 'ㄼ' to "ㄹㅂ", 'ㄽ' to "ㄹㅅ",
        'ㄾ' to "ㄹㅌ", 'ㄿ' to "ㄹㅍ", 'ㅀ' to "ㄹㅎ", 'ㅄ' to "ㅂㅅ",
    )

    fun isSyllable(c: Char): Boolean = c in '가'..'힣'

    fun isJamo(c: Char): Boolean = c in 'ㄱ'..'ㅣ'

    fun hasHangul(text: String): Boolean = text.any { isSyllable(it) || isJamo(it) }

    /** 낱자모로(겹은 둘로). 한글이 아닌 글자는 그대로. */
    fun decompose(text: String): String = buildString {
        for (c in text) {
            if (isSyllable(c)) {
                val i = c.code - 0xAC00
                append(CHO[i / 588])
                appendSplit(JUNG[(i % 588) / 28])
                val t = i % 28
                if (t != 0) JONG[t].forEach { appendSplit(it) }
            } else if (isJamo(c)) {
                appendSplit(c)
            } else {
                append(c)
            }
        }
    }

    private fun StringBuilder.appendSplit(c: Char) {
        val split = SPLIT[c]
        if (split != null) append(split) else append(c)
    }

    /** 두벌식 키 문자열. `스폰` → `tmvhs`, `넴주` → `spawn`, `spawn` → `spawn`. */
    fun keys(text: String): String = decompose(text).map { KEY[it] ?: it }.joinToString("")

    /** 키 문자열이 한글 명령의 "영타형" 으로 쓸 만한가(손님 명령 트리 별칭 — 영문 소문자·대문자만). */
    fun isTypable(key: String): Boolean = key.isNotEmpty() && key.all { it in 'a'..'z' || it in 'A'..'Z' }
}

/**
 * 이름 목록의 키 색인(순수). [convert] 는 친 낱말이 목록에 **없을 때만**, 키가 **정확히 하나**에 맞으면 그 이름을 돌려준다
 * (둘 이상이면 [ambiguous] 로 안내만 — 엉뚱한 명령을 실행하지 않는다). 실제 이름이 언제나 변환보다 먼저다.
 */
class KeyboardIndex(names: Collection<String>) {

    private val names: Set<String> = names.toHashSet()
    private val byKey = HashMap<String, MutableSet<String>>()
    private val byLower = HashMap<String, MutableSet<String>>()

    init {
        for (name in this.names) {
            val key = Keyboard.keys(name)
            byKey.getOrPut(key) { LinkedHashSet() } += name
            byLower.getOrPut(key.lowercase()) { LinkedHashSet() } += name
        }
    }

    val size: Int get() = names.size

    operator fun contains(name: String): Boolean = name in names

    /** 바꿀 이름(하나뿐일 때) 또는 null. 이미 있는 이름이면 null. */
    fun convert(typed: String): String? {
        if (typed.isEmpty() || typed in names) return null
        return candidates(typed).singleOrNull()
    }

    /** 같은 키의 이름들(친 것 자신은 빼고). 정확한 키가 없으면 소문자 키로. */
    fun candidates(typed: String): List<String> {
        val key = Keyboard.keys(typed)
        val exact = byKey[key]?.filter { it != typed }.orEmpty()
        if (exact.isNotEmpty()) return exact
        return byLower[key.lowercase()]?.filter { it != typed }.orEmpty()
    }

    /** 둘 이상이 같은 키 — 안내용. */
    fun ambiguous(typed: String): List<String> = candidates(typed).takeIf { it.size > 1 }.orEmpty()
}
