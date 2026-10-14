package com.inmc.essentials.service

import org.bukkit.entity.Player

/**
 * commands 기능의 명령 대기·쿨타임·비용이 다른 기능과 만나는 곳(`ess.gate`, 기본 null).
 *
 * 대기·쿨타임·비용은 **명령 이름으로** 설정 파일에서 걸리므로 다른 기능은 모른다. 예외는 이동 계열의 "성공해야 쿨타임" —
 * 이동 기능은 이동이 성공한 순간 `ess.gate?.commit(player, 명령 이름)` 을 부른다. gate 가 없거나 그 명령을 "성공해야 쿨타임" 으로
 * 걸지 않았으면 아무 일도 없다(실행 즉시 기록된다). [command] 는 앞의 `/` 없이, 친 그대로의 루트 이름(별칭이면 별칭).
 */
fun interface CommandGate {

    fun commit(player: Player, command: String)
}
