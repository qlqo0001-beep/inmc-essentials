package com.inmc.essentials.inventory

import kr.inmc.core.integration.Realms
import org.bukkit.entity.Player

/**
 * 몸이 지금 쥔 가방 칸 — core [Realms] 의 `worlds.inventory` scope(2026-10-15, inmc-worlds — 전에는 Multiverse-Inventories 그룹을 리플렉션으로 읽었다).
 * 같은 열쇠면 같은 가방이다. 모드 공급처가 없으면 null — 그러면 칸을 따지지 않는다.
 *
 * 엔더 상자는 따로 토글(`worlds.ender`)이지만 열쇠는 가방 것 하나로 본다 — 둘을 다르게 나눈 서버에서는 엔더 대기 편집이 가방 칸을 따른다.
 * 모드 공급처(inmc-worlds)는 우리보다 먼저 켜지고 늦게 꺼지므로(그쪽 `paper-plugin.yml` 의 `load: AFTER`) 끌 때의 모습에도 열쇠가 남는다.
 */
object InvGroups {

    const val INVENTORY_KEY = "worlds.inventory"

    /** [player] 의 가방 칸 열쇠. 모드가 없으면 null. 메인 스레드. */
    fun of(player: Player): String? = if (Realms.available) Realms.scope(INVENTORY_KEY, player) else null

    /** 같은 가방인가(순수) — 어느 쪽이든 모르면(모드 없음) 같다고 본다. */
    fun matches(recorded: String?, current: String?): Boolean = recorded == null || current == null || recorded == current
}
