package com.inmc.essentials.service

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent
import java.util.concurrent.CompletableFuture

/** 이동의 종류 — 이동 기능이 종류마다 파티클·뒤로 기록·이동 금지 아이템 규칙을 고른다. **저장하지 않는다**(지뢰 5 — 순서값). */
enum class TeleportKind { HOME, WARP, SPAWN, TPA, BACK, DEATH_BACK, ADMIN, PORTAL, JAIL, BIOME, OTHER }

/**
 * 모든 기능이 사람을 옮길 때 지나는 한 곳(`ess.teleporter`). 기본 구현([PLAIN])은 그냥 `teleportAsync` 이고, teleport 기능이 켜지면
 * 자기 구현으로 바꿔 끼운다 — 뒤로 기록·안전한 자리·이동 금지 아이템·효과·이동 뒤 무적·탑승 동반은 그 구현의 몫이다.
 *
 * 결과는 이동했으면 true(막혔거나 실패하면 false). **메인 스레드에서 부른다.** 이동 성공 때 쿨타임을 쓰는 명령은 true 를 받은 뒤
 * `ess.gate?.commit(player, 명령)` 을 부른다([CommandGate]).
 */
interface Teleporter {

    fun teleport(player: Player, to: Location, kind: TeleportKind, safe: Boolean = true): CompletableFuture<Boolean>

    companion object {

        /** teleport 기능이 꺼져 있을 때 — 안전 확인도 기록도 없이 옮기기만. */
        val PLAIN: Teleporter = object : Teleporter {
            override fun teleport(player: Player, to: Location, kind: TeleportKind, safe: Boolean): CompletableFuture<Boolean> =
                // 포탈만 모드를 넘는다(core `Realms.travel` — EssTeleporter 와 같은 규칙).
                if (kind == TeleportKind.PORTAL) kr.inmc.core.integration.Realms.travel(player, to)
                else player.teleportAsync(to, PlayerTeleportEvent.TeleportCause.PLUGIN)
        }
    }
}
