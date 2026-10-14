package com.inmc.essentials.admininfo

import com.inmc.essentials.Ess
import com.inmc.essentials.service.Abilities
import kr.inmc.core.util.Durations
import org.bukkit.entity.Player

/**
 * `/정보` 가 보이는 무적·시간제 비행/무적 — 상태의 주인은 player 기능(규칙 1: 그 패키지를 import 하지 않는다). 접점 `ess.abilities` 로 읽는다(2026-10-14,
 * 전에는 `%inmcplayer_…%` 플레이스홀더). player 기능이 꺼져 있으면([Abilities.NONE]) 무적은 바닐라 `isInvulnerable` 로, 남은 시간은 없음으로.
 */
internal class PlayerAbilities(private val ess: Ess) {

    fun god(player: Player): Boolean {
        val a = ess.abilities
        return if (a === Abilities.NONE) player.isInvulnerable else a.isGod(player)
    }

    /** 남은 시간제 무적 글(없으면 null). */
    fun godLeft(player: Player): String? = left(ess.abilities.timedGodLeft(player))

    /** 남은 시간제 비행 글(없으면 null). */
    fun flightLeft(player: Player): String? = left(ess.abilities.timedFlightLeft(player))

    private fun left(seconds: Long?): String? = when {
        seconds == null -> null
        seconds == Abilities.UNTIL_RELOG -> ess.messages.raw("admininfo-until-relog")
        else -> Durations.formatShort(seconds)
    }
}
