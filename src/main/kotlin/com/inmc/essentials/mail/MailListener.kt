package com.inmc.essentials.mail

import com.inmc.essentials.Ess
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/** 접속 알림(안 읽은 편지·받을 것) · 공용 DB 면 접속 직전에 그 사람의 우편 다시 읽기. */
class MailListener(private val ess: Ess, private val feature: MailFeature) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        player.scheduler.runDelayed(ess.plugin, { _ -> if (player.isOnline) feature.service.notifyJoin(player) }, null, feature.settings.joinDelayTicks.coerceAtLeast(1L))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        feature.lastSent.remove(event.player.uniqueId)
    }

    /** 다른 서버가 넣고 받은 우편을 이 서버가 모르는 채로 받기를 열지 않게(비동기 사건 — 기다려도 된다). */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        if (event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED || ess.db.network == null) return
        try {
            feature.store.reload(event.uniqueId)
        } catch (t: Throwable) {
            // 낡은 사본을 믿지 않는다 — 다른 서버에서 이미 받은 우편이 남아 있을 수 있다. 받기는 다음 다시 읽기가 될 때까지 막는다(MailService.ensureSynced).
            feature.store.markUnsynced(event.uniqueId)
            ess.logger.warning("공용 DB 에서 ${event.name} 의 우편을 다시 읽지 못했습니다 — 다시 읽을 때까지 받기를 막습니다: ${t.message}")
        }
    }
}
