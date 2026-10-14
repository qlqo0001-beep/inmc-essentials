package com.inmc.essentials.server

/**
 * `/플레이스홀더 목록` 한 줄의 글 규칙(서버 없이 시험) — 매개변수 자리인지, 옆에 보일 값.
 */
object PapiLine {

    /** 값 옆에 보일 최대 글자 수 — 넘으면 자르고 `…`. */
    const val MAX = 80

    /** `<…>`·`{…}`·`[…]` 자리가 있으면 그대로는 값을 낼 수 없다. */
    fun isTemplate(placeholder: String): Boolean = placeholder.any { it == '<' || it == '{' || it == '[' }

    /**
     * 풀린 값 [value] 를 한 줄로. 풀리지 않았거나(PAPI 는 모르는 자리표시를 그대로 돌려준다) 비었으면 null — "(값 없음)" 으로 보인다.
     * 줄바꿈은 ` ⏎ `, 너무 길면 [MAX] 에서 자른다.
     */
    fun shown(placeholder: String, value: String): String? {
        if (value.isBlank() || value == placeholder) return null
        val flat = value.replace("\r\n", "\n").replace("\n", " ⏎ ")
        return if (flat.length <= MAX) flat else flat.take(MAX) + "…"
    }
}
