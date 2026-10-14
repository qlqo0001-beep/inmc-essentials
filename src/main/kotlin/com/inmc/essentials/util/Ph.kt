package com.inmc.essentials.util

import kr.inmc.core.util.TokenBag

/**
 * 메시지 한 번 렌더링에 쓰이는 토큰 주머니. 한글·영문 둘 다 받는다(다른 INMC 플러그인과 같은 관례).
 *
 * **여러 기능이 같이 쓰는 파일이다 — 기능 작업자는 여기에 토큰을 더하지 않는다.** 기능에만 있는 토큰은 `raw("{홈}", 이름)` 처럼
 * 중괄호까지 적어 넣는다(별칭이 없으면 토큰 이름 그대로 바뀐다).
 */
class Ph : TokenBag<Ph>() {

    override val aliases: Map<String, List<String>> get() = ALIASES

    fun player(name: String): Ph = put(PLAYER, name)

    fun target(name: String): Ph = put(TARGET, name)

    fun value(text: String): Ph = put(VALUE, text)

    fun count(value: Int): Ph = put(COUNT, value.toString())

    fun count(value: Long): Ph = put(COUNT, value.toString())

    fun amount(text: String): Ph = put(AMOUNT, text)

    fun world(name: String): Ph = put(WORLD, name)

    fun time(text: String): Ph = put(TIME, text)

    fun reason(text: String): Ph = put(REASON, text)

    fun subject(text: String): Ph = put(SUBJECT, text)

    fun item(text: String): Ph = put(ITEM, text)

    fun rank(text: String): Ph = put(RANK, text)

    fun season(text: String): Ph = put(SEASON, text)

    companion object {

        fun of(): Ph = Ph()

        const val PLAYER = "player"
        const val TARGET = "target"
        const val VALUE = "value"
        const val COUNT = "count"
        const val AMOUNT = "amount"
        const val WORLD = "world"
        const val TIME = "time"
        const val REASON = "reason"

        /** core 보상·순위가 넘기는 이름(보상 출처·순위표 이름) — [kr.inmc.core.reward.RewardHost.placeholders]. */
        const val SUBJECT = "subject"
        const val ITEM = "item"
        const val RANK = "rank"
        const val SEASON = "season"

        private val ALIASES: Map<String, List<String>> = mapOf(
            PLAYER to listOf("{플레이어네임}", "{플레이어}", "{player}"),
            TARGET to listOf("{대상}", "{target}"),
            VALUE to listOf("{값}", "{value}"),
            COUNT to listOf("{개수}", "{count}"),
            AMOUNT to listOf("{금액}", "{amount}"),
            WORLD to listOf("{월드}", "{world}"),
            TIME to listOf("{시간}", "{time}"),
            REASON to listOf("{사유}", "{reason}"),
            SUBJECT to listOf("{이름}", "{subject}"),
            ITEM to listOf("{아이템}", "{item}"),
            RANK to listOf("{순위}", "{rank}"),
            SEASON to listOf("{시즌}", "{season}"),
        )
    }
}
