package com.inmc.essentials.home

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import org.bukkit.Tag
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.block.Block
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.EquipmentSlot

/**
 * 침대 홈(CMI homeInteractions) · 침대가 부서지면 그 홈 지우기 · 접속 때 한도 초과 알림 · 공용 DB 면 접속 직전에 그 사람의 홈 다시 읽기.
 */
class HomeListener(private val ess: Ess, private val feature: HomesFeature, private val service: HomeService) : Listener {

    /**
     * 침대 우클릭 → 침대 홈. 블록 사용이 막혔으면(보호구역 등) 아무것도 안 한다. 잠자기·바닐라 리스폰 지정은 그대로 둔다(취소하지 않는다).
     * 블록만 받는 리스너라 `ignoreCancelled` 를 써도 허공 클릭 문제(지뢰 14)와 무관하다 — 그래도 블록 사용 결과로 본다.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onBed(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND) return
        if (event.useInteractedBlock() == Event.Result.DENY) return
        val block = event.clickedBlock ?: return
        if (!Tag.BEDS.isTagged(block.type)) return
        // 네더·엔드의 침대는 터진다(침대 캐기) — 그 자리를 침대 홈으로 삼으면 진짜 침대 홈을 잃는다.
        if (!HomeRules.bedWorks(block.world.environment)) return
        val s = feature.settings
        if (!s.bedEnabled) return
        val player = event.player
        if (s.bedShiftOnly && !player.isSneaking) return
        if (!player.hasPermission(HomesFeature.BED)) return
        service.setBed(player, block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) = broken(listOf(event.block))

    /** 폭발(TNT·크리퍼·침대 폭발)·불에 탄 침대도 바로 지운다 — 청크가 내려가 있으면 이동 때 알아채는 것([HomeService.bedStillThere])에 기대지 않게. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) = broken(event.blockList())

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) = broken(event.blockList())

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBurn(event: BlockBurnEvent) = broken(listOf(event.block))

    private fun broken(blocks: List<Block>) {
        if (!feature.settings.bedRemoveOnBreak) return
        for (block in blocks) if (Tag.BEDS.isTagged(block.type)) service.bedBroken(block)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val s = feature.settings
        if (!s.notifyOnLogin) return
        val player = event.player
        // 1초 뒤 — 접속 인사들 사이에 묻히지 않게, 권한(LuckPerms)이 다 실린 뒤에.
        player.scheduler.runDelayed(ess.plugin, {
            val limit = service.limitOf(player) ?: return@runDelayed
            val count = service.counted(player.uniqueId)
            if (count > limit) ess.messages.send(player, if (s.blockExtraHomes) "homes-over-limit-login" else "homes-over-limit-notice", Ph.of().count(count).value(limit.toString()))
        }, null, 20L)
    }

    /** 공용 DB 면 다른 서버에서 고친 홈이 있을 수 있다 — 들어오기 직전(비동기 — 기다려도 된다)에 그 사람 것만 다시 읽는다. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        if (event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED || ess.db.network == null) return
        try {
            service.store.reload(event.uniqueId)
        } catch (t: Throwable) {
            ess.logger.warning("공용 DB 에서 ${event.name} 의 홈을 다시 읽지 못했습니다 — 이 서버가 들고 있던 것으로 처리합니다: ${t.message}")
        }
    }
}
