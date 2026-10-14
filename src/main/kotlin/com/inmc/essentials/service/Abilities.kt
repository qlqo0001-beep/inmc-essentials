package com.inmc.essentials.service

import org.bukkit.entity.Player
import java.util.UUID

/**
 * 무적·비행 상태를 묻는 곳(`ess.abilities`, 2026-10-14). 상태의 주인은 player 기능 — `enable()` 에서 바꿔 끼우고 `disable()` 에서 [NONE] 으로.
 * 기본 구현([NONE])은 아무도 무적이 아니고 아무것도 바꾸지 않는다.
 *
 * 쓰는 쪽: world 기능(무적 금지 월드에서 끄기 · 비행 금지 월드를 나갈 때 돌려주기) · admininfo `/정보`(무적·남은 시간제 비행/무적).
 * 전에는 둘이 player 기능의 메서드를 이름으로 부르거나 `%inmcplayer_…%` 로 읽었다. **메인 스레드에서 부른다**(메모리만 본다).
 */
interface Abilities {

    /** `/무적` 또는 시간제 무적이 남았다. */
    fun isGod(player: Player): Boolean

    /** `/무적` 켜기·끄기. **끄면 시간제 무적도 끝낸다**(저장은 주인 기능이). */
    fun setGod(player: Player, on: Boolean)

    /** `/비행` 으로 켰거나 시간제 비행이 남았다 — 비행 금지 월드를 나갈 때 돌려줘도 되는 사람. */
    fun wantsFlight(id: UUID): Boolean

    /** 남은 시간제 무적(초). 없으면 null, 다시 접속할 때까지면 [UNTIL_RELOG]. */
    fun timedGodLeft(player: Player): Long?

    /** 남은 시간제 비행(초). 없으면 null, 다시 접속할 때까지면 [UNTIL_RELOG]. */
    fun timedFlightLeft(player: Player): Long?

    companion object {

        /** "다시 접속할 때까지" 를 뜻하는 남은 초(player 기능 `PlayerRecord.UNTIL_RELOG` 와 같은 값). */
        const val UNTIL_RELOG = -1L

        /** player 기능이 꺼져 있을 때 — 아무도 무적·시간제가 아니고, 바꾸는 것은 아무 일도 하지 않는다. */
        val NONE: Abilities = object : Abilities {
            override fun isGod(player: Player): Boolean = false
            override fun setGod(player: Player, on: Boolean) {}
            override fun wantsFlight(id: UUID): Boolean = false
            override fun timedGodLeft(player: Player): Long? = null
            override fun timedFlightLeft(player: Player): Long? = null
        }
    }
}
