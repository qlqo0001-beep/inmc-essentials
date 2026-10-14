package com.inmc.essentials.service

import org.bukkit.entity.Player

/**
 * 킷 기능이 다른 기능에 내놓는 것(`ess.kits`, 기본 null — kits 기능이 꺼져 있으면 없다).
 * 첫 접속 킷은 spawn 기능이 첫 스폰을 끝낸 뒤 `ess.kits?.giveFirstJoin(player)` 로 부른다. 받았는지 기록하고 한 번만 주는 것은 킷 기능의 몫.
 */
fun interface KitsApi {

    fun giveFirstJoin(player: Player)
}
