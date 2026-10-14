package com.inmc.essentials.teleport

/**
 * CMI 개인 옵션 글에서 `acceptingTPA` 읽기(순수). CMI 는 개인 옵션을 users 표의 한 열에 묶어 두고 형식이 버전마다 다르다
 * (`acceptingTPA:false` · JSON `"acceptingTPA":false` · `acceptingTPA=0` …) — 테섭 DB 에는 값이 없어 실물로 못 봤다. 그래서 낱말 뒤의
 * 구분자를 건너뛰고 참/거짓·1/0 만 알아본다. 모르는 모양은 [Unreadable] — 가져오기 보고서의 "못 읽음" 으로 간다.
 */
sealed interface AcceptingTpa {
    /** 글에 `acceptingTPA` 가 없다. */
    object Absent : AcceptingTpa

    /** 있는데 값을 못 읽었다. */
    object Unreadable : AcceptingTpa

    data class Value(val accepting: Boolean) : AcceptingTpa

    companion object {
        private const val WORD = "acceptingTPA"

        fun parse(text: String): AcceptingTpa {
            val at = text.indexOf(WORD, ignoreCase = true)
            if (at < 0) return Absent
            val tail = text.substring(at + WORD.length).trimStart(' ', '"', '\'', ':', '=', '%', ';', '|').lowercase()
            return when {
                tail.startsWith("false") || tail.startsWith("0") -> Value(false)
                tail.startsWith("true") || tail.startsWith("1") -> Value(true)
                else -> Unreadable
            }
        }
    }
}
