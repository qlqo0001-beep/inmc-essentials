package com.inmc.essentials.player

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import kr.inmc.core.integration.TitleForgeNames
import kr.inmc.core.integration.Vanish
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/**
 * 체력 보스바(CMI hpBossBar) — 권한 `inmcessentials.player.hpbar` 인 사람이 몹·플레이어를 때리면 상대 체력을 보스바로. 몇 초 동안 안 때리면 사라진다.
 * 제외 몹은 설정(기본 엔더 드래곤 — 자기 보스바가 있다)과 커스텀 몹(`hp-bar.skip-custom-mobs`). 투명이라 못 보는 사람은 보스바도 없다. 체력은 피해가 들어간 **다음 틱**에 읽는다.
 */
class HpBars(private val ess: Ess, private val config: () -> PlayerConfig) : Listener {

    private class Shown(val bar: BossBar, var victim: UUID, var until: Long)

    private val shown = HashMap<UUID, Shown>()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHit(event: EntityDamageByEntityEvent) {
        val c = config()
        if (!c.hpBarEnabled) return
        val attacker = when (val d = event.damager) {
            is Player -> d
            is Projectile -> d.shooter as? Player
            else -> null
        } ?: return
        val victim = event.entity as? LivingEntity ?: return
        if (victim == attacker || victim is ArmorStand) return
        if (victim.type.key.key in c.hpBarBlacklist) return
        if (c.hpBarSkipCustomMobs && isCustomMob(victim)) return
        if (victim is Player && (!c.hpBarPlayers || !Vanish.canSee(attacker, victim))) return
        if (!attacker.hasPermission(PlayerFeature.HPBAR)) return
        val damage = event.finalDamage
        attacker.scheduler.run(ess.plugin, { _ -> show(attacker, victim, damage) }, null)
    }

    /**
     * inmc-monster 의 커스텀 몹인가 — 엔티티 PDC `inmc-monster:mob_id` 가 비어 있지 않으면(ARCHITECTURE "남의 PDC 를 읽는 곳" — 커스텀 드랍과 같은 판단).
     * 그 몹은 보스바 깃발이 있으면 자기 보스바(RED NOTCHED_10)를 띄우므로 겹치지 않게 뺀다(2026-10-10 리뷰).
     */
    private fun isCustomMob(victim: LivingEntity): Boolean =
        !victim.persistentDataContainer.get(MOB_ID, PersistentDataType.STRING).isNullOrEmpty()

    /** 보스바를 띄우거나 고친다. */
    fun show(attacker: Player, victim: LivingEntity, damage: Double) {
        if (!attacker.isOnline) return
        val max = victim.getAttribute(Attribute.MAX_HEALTH)?.value ?: return
        val health = if (victim.isDead) 0.0 else victim.health.coerceAtLeast(0.0)
        val name = when {
            victim is Player -> TitleForgeNames.displayName(victim.uniqueId, victim.name)
            victim.customName() != null -> MiniMessage.miniMessage().serialize(victim.customName()!!)
            else -> "<lang:${victim.type.translationKey()}>"
        }
        val ph = Ph.of().subject(name).value(MaxHpMath.format(health)).amount(MaxHpMath.format(max)).raw("{피해}", MaxHpMath.format(damage))
            .raw("{흡수}", if (victim.absorptionAmount > 0) "+" + MaxHpMath.format(victim.absorptionAmount) else "")
        val title = ess.messages.component(if (victim is Player) "player-hpbar-player" else "player-hpbar", ph)
        val progress = (health / max).toFloat().coerceIn(0f, 1f)
        val color = when {
            progress > 0.5f -> BossBar.Color.GREEN
            progress > 0.25f -> BossBar.Color.YELLOW
            else -> BossBar.Color.RED
        }
        val until = System.currentTimeMillis() + config().hpBarSeconds * 1000L
        val current = shown[attacker.uniqueId]
        if (current == null) {
            val bar = BossBar.bossBar(title, progress, color, BossBar.Overlay.NOTCHED_10)
            shown[attacker.uniqueId] = Shown(bar, victim.uniqueId, until)
            attacker.showBossBar(bar)
        } else {
            current.bar.name(title)
            current.bar.progress(progress)
            current.bar.color(color)
            current.victim = victim.uniqueId
            current.until = until
        }
    }

    fun isShowing(player: Player): Boolean = player.uniqueId in shown

    fun hide(player: Player) {
        shown.remove(player.uniqueId)?.let { player.hideBossBar(it.bar) }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        shown.remove(event.player.uniqueId)
    }

    /** 1초 — 시간이 지난 보스바를 내린다. */
    fun tick(now: Long) {
        val it = shown.entries.iterator()
        while (it.hasNext()) {
            val (id, s) = it.next()
            if (s.until > now) continue
            ess.plugin.server.getPlayer(id)?.hideBossBar(s.bar)
            it.remove()
        }
    }

    fun clear() {
        for ((id, s) in shown) ess.plugin.server.getPlayer(id)?.hideBossBar(s.bar)
        shown.clear()
    }

    private companion object {
        /** inmc-monster `runtime/MobTracker.kt` 의 열쇠(그 플러그인 이름으로 만든다 — 이름이 바뀌면 이 판단이 조용히 꺼진다). */
        val MOB_ID = NamespacedKey("inmc-monster", "mob_id")
    }
}
