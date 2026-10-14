package com.inmc.essentials.spawn

import org.bukkit.Location
import org.bukkit.entity.Player

/**
 * 부활 순서의 홈 칸(침대 홈·기본 홈)을 채우는 쪽 — homes 기능이 `ready()` 에서 [SpawnFeature.homes] 에 넣고 `disable()` 에서 뺀다.
 * 기능끼리 import 하지 않는 규칙(CLAUDE.md 1)의 예외로, 스폰·홈·워프를 한 작업자가 맡으며 정한 작은 창구다 — 방향은 늘 홈·워프 → 스폰.
 * 메인 스레드에서 불린다(부활 사건). 메모리만 본다.
 */
interface SpawnHomes {

    /** 침대를 눌러 만든 홈 — 없거나 침대가 사라졌거나 월드가 없으면 null. */
    fun bedHome(player: Player): Location?

    /** 기본 이름의 홈, 없으면 첫 홈(CMI homeLocation) — 없으면 null. */
    fun defaultHome(player: Player): Location?
}

/** 부활 순서의 워프 칸 — warps 기능이 [SpawnFeature.warps] 에 넣는다. 권한·숨김과 무관하게(CMI `warp!` 와 같다). */
interface SpawnWarps {

    fun warp(name: String): Location?

    /** 관리 화면의 고르기용. */
    fun names(): List<String>
}
