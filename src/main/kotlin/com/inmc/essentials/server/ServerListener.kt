package com.inmc.essentials.server

import com.destroystokyo.paper.event.server.PaperServerListPingEvent
import com.inmc.essentials.Ess
import io.papermc.paper.event.player.PlayerServerFullCheckEvent
import kr.inmc.core.integration.Vanish
import kr.inmc.core.util.Text
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID

/**
 * 점검 모드 · 가득 참 우회 · 서버 목록(MOTD).
 *
 * - 점검: 접속 직전(`AsyncPlayerPreLoginEvent` HIGHEST)에 우회가 아니면 거절. 우회 = op · `maintenance.allow` 목록 · LuckPerms 의
 *   `inmcessentials.server.maintenance.bypass`([OfflinePerms] — 접속 직전엔 Player 가 없다). LuckPerms 가 없거나 그 사람을 아직 안 읽어
 *   **모르면** 들여보내고 접속 사건(LOWEST)에서 권한을 다시 보고 다음 틱에 내보낸다(다른 권한 플러그인으로 우회를 받은 운영진도 들어오게).
 *   `PlayerLoginEvent` 는 쓰지 않는다(Paper 26 에서 없어질 예정 — 지뢰 25).
 * - 데이터 이전 잠금([ServerFeature.migrationLocked]): 이전을 시작한 때부터 재시작까지 **아무도** 들이지 않는다(점검 우회도) — 그 사이의 기록은
 *   옛 DB 에만 남아 재시작 뒤 사라지거나 되살아난다.
 * - 가득 참: `PlayerServerFullCheckEvent` 에서 `inmcessentials.server.fullbypass` 면 들인다(CMI `cmi.fullserver.bypass`).
 * - MOTD: `PaperServerListPingEvent` — 점검 중 문구 · `/서버소개` · 보이는 최대 인원 · 투명인 사람은 인원수·목록에서 뺀다(보는 사람을 모른다 — 규칙 8).
 */
class ServerListener(private val ess: Ess, private val feature: ServerFeature) : Listener {

    private var bar: BossBar? = null

    /** 우회하나 — 모르면 null(LuckPerms 가 없거나 그 사람을 아직 안 읽음 · 접속 사건에서 다시 본다). */
    fun bypassesMaintenance(id: UUID, name: String): Boolean? {
        Bukkit.getPlayer(id)?.let { return it.hasPermission(ServerFeature.MAINTENANCE_BYPASS) || allowed(id, name) }
        if (allowed(id, name)) return true
        if (runCatching { Bukkit.getOfflinePlayer(id).isOp }.getOrDefault(false)) return true
        return OfflinePerms.has(id, ServerFeature.MAINTENANCE_BYPASS)
    }

    private fun allowed(id: UUID, name: String): Boolean {
        val list = feature.settings.maintenanceAllow
        return id.toString() in list || name.lowercase() in list
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        if (event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED) return
        if (feature.migrationLocked) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, ess.messages.component("server-migrate-login-blocked"))
            return
        }
        if (!feature.state.maintenance) return
        // 모름(null)은 들인다 — 접속 사건에서 다시 본다.
        if (bypassesMaintenance(event.uniqueId, event.name) != false) return
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMessage())
    }

    fun kickMessage(): Component {
        val reason = feature.state.maintenanceReason
        return Text.render(feature.settings.maintenanceMessage + if (reason.isNotBlank()) "\n<gray>$reason</gray>" else "")
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onFull(event: PlayerServerFullCheckEvent) {
        if (event.isAllowed || !feature.settings.fullBypass) return
        val id = event.playerProfile.id ?: return
        val ok = runCatching { Bukkit.getOfflinePlayer(id).isOp }.getOrDefault(false) || OfflinePerms.has(id, ServerFeature.FULL_BYPASS) == true
        if (ok) event.allow(true)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (!feature.state.maintenance) return
        if (!player.hasPermission(ServerFeature.MAINTENANCE_BYPASS) && !allowed(player.uniqueId, player.name) && !player.isOp) {
            // 접속 직전에 권한을 알 수 없던 사람(LuckPerms 없음·아직 안 읽음) — 이제 안다. 접속 사건 안에서는 내보내지 않는다(다음 틱).
            player.scheduler.run(ess.plugin, { _ -> if (player.isOnline) player.kick(kickMessage()) }, null)
            return
        }
        bar?.let { player.showBossBar(it) }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        bar?.let { event.player.hideBossBar(it) }
    }

    /** 점검 상태가 바뀌었다 — 보스바를 맞추고, 켰으면 우회가 아닌 사람을 내보낸다(`maintenance.auto-kick`). */
    fun maintenanceChanged(kick: Boolean) {
        val s = feature.settings
        bar?.let { old -> Bukkit.getOnlinePlayers().forEach { it.hideBossBar(old) } }
        bar = null
        if (!feature.state.maintenance) return
        if (s.maintenanceBossBar) {
            val color = BossBar.Color.entries.firstOrNull { it.name.equals(s.maintenanceBossBarColor, true) } ?: BossBar.Color.RED
            val title = ess.messages.component("server-maintenance-bossbar", com.inmc.essentials.util.Ph.of().reason(feature.state.maintenanceReason.ifBlank { "-" }))
            bar = BossBar.bossBar(title, 1f, color, BossBar.Overlay.PROGRESS).also { b -> Bukkit.getOnlinePlayers().forEach { it.showBossBar(b) } }
        }
        if (kick && s.maintenanceAutoKick) {
            for (p in Bukkit.getOnlinePlayers().toList()) {
                if (!p.hasPermission(ServerFeature.MAINTENANCE_BYPASS) && !allowed(p.uniqueId, p.name)) p.kick(kickMessage())
            }
        }
    }

    fun hideBar() {
        bar?.let { old -> Bukkit.getOnlinePlayers().forEach { it.hideBossBar(old) } }
        bar = null
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onPing(event: PaperServerListPingEvent) {
        val s = feature.settings
        val state = feature.state
        if (state.maintenance) {
            if (s.maintenanceMotd.isNotEmpty()) event.motd(lines(s.maintenanceMotd))
            if (s.maintenanceVersionText.isNotBlank()) {
                // 손님 판과 다른 프로토콜 번호면 목록에 이 글이 빨갛게 보인다(접속 단추는 그대로 — 접속 직전에 막는다).
                event.version = Text.plain(s.maintenanceVersionText)
                event.protocolVersion = -1
            }
        } else if (s.motdEnabled) {
            val custom = state.motd.ifEmpty { s.motdLines }
            if (custom.isNotEmpty()) event.motd(lines(custom))
        }
        if (s.motdMaxPlayers > 0) event.maxPlayers = s.motdMaxPlayers
        if (s.motdHideVanished) {
            val hidden = Bukkit.getOnlinePlayers().filter { Vanish.isVanished(it) }
            if (hidden.isNotEmpty()) {
                val ids = hidden.map(Player::getUniqueId).toSet()
                event.numPlayers = (event.numPlayers - hidden.size).coerceAtLeast(0)
                event.listedPlayers.removeIf { it.id() in ids }
            }
        }
        if (s.motdHover.isNotEmpty()) {
            event.listedPlayers.clear()
            for (line in s.motdHover) event.listedPlayers += PaperServerListPingEvent.ListedPlayerInfo(legacy(line), UUID(0, 0))
        }
    }

    private fun lines(raw: List<String>): Component = Text.render(raw.take(2).joinToString("\n"))

    /** 목록 호버는 옛 문자열(§ 색)만 된다. */
    private fun legacy(line: String): String =
        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(Text.render(line))
}
