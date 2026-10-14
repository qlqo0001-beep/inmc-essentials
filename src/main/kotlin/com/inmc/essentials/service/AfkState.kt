package com.inmc.essentials.service

import org.bukkit.entity.Player

/**
 * 잠수 상태를 묻는 곳(`ess.afk`). 기본 구현([NEVER])은 아무도 잠수가 아니고, afk 기능이 켜지면 바꿔 끼운다.
 * 메인 스레드에서 묻는다(구현은 메모리만 본다 — 채팅·이동 사건마다 불린다).
 */
interface AfkState {

    fun isAfk(player: Player): Boolean

    /** 마지막으로 움직이거나 말한 시각(밀리초). 모르면 지금. */
    fun lastActive(player: Player): Long

    companion object {

        val NEVER: AfkState = object : AfkState {
            override fun isAfk(player: Player): Boolean = false
            override fun lastActive(player: Player): Long = System.currentTimeMillis()
        }
    }
}
