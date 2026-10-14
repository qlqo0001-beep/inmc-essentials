package com.inmc.essentials.portal

import com.inmc.essentials.Ess
import com.inmc.essentials.service.TeleportKind
import com.inmc.essentials.util.Ph
import kr.inmc.core.integration.Selections
import kr.inmc.core.util.Text
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Registry
import org.bukkit.SoundCategory
import org.bukkit.command.CommandSender
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.potion.PotionEffect
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/**
 * 포탈의 일 — 들어서기 판정(걷기마다, 블록이 바뀔 때만), 이동·명령·효과, 막기·밀어내기, 파티클(1초 반복 작업 한 칸), 관리자의 두 모서리.
 * 전부 메인 스레드.
 *
 * 걷기는 둘로 나눈다: [guard](HIGHEST — 막을 포탈이면 걷기를 취소하고 밀어낸다, 다른 일은 하지 않는다) → [moved](MONITOR, 취소되지 않은 걷기만 —
 * 이동·명령·효과·쿨타임). 그래서 WorldGuard·Lands 같은 보호 플러그인이 걷기를 막으면(HIGH 까지) 포탈은 쏘지 않는다.
 *
 * 탈것(광산 수레·보트·말 …)에 탄 사람은 걷기 사건이 오지 않는다 — [ridden](`VehicleMoveEvent`)이 탄 사람마다 같은 판정을 한다. 탈것의 움직임은
 * 취소할 수 없으므로 막을 때는 탈것을 민다(안전 위치가 있으면 사람을 그리로 — 이동이 내려 준다).
 */
class PortalService(private val ess: Ess, private val feature: PortalsFeature, val store: PortalStore) {

    val settings: PortalSettings get() = feature.settings

    /** 사람마다 지금 서 있는 포탈(열쇠). 들어서는 순간만 쓰게 — 안에서 걸어도 다시 쏘지 않는다. */
    private val inside = HashMap<UUID, String>()

    /** 사람 → 포탈 열쇠 → 마지막으로 쓴 때(밀리초). */
    private val lastUse = HashMap<UUID, HashMap<String, Long>>()

    /** 관리자가 명령으로 찍은 두 모서리. 둘 다 있으면 WorldEdit 선택보다 먼저(방금 찍은 것) — 만들거나 다시 정하면 지운다. */
    private val corners = HashMap<UUID, Array<Location?>>()

    /** 권한 없음 알림의 마지막 때(사람마다) — 막힌 채 계속 걸으면 걸음마다 알리지 않게. */
    private val lastDenied = HashMap<UUID, Long>()

    /** 검증기가 들여다보는 마지막 들어서기(사람 → 포탈 열쇠). 메모리만. */
    internal val lastEntered = HashMap<UUID, String>()

    /** 이동이 아직 끝나지 않은 사람 — 그동안 다시 들어서도 쏘지 않는다(쿨타임 0 인 포탈의 명령이 두 번 돌지 않게). */
    private val pending = HashSet<UUID>()

    /** 검증기 — 명령을 실제로 돌리지 않고 여기에 모은다(이동·밀기도 하지 않는다). */
    internal var capture: MutableList<String>? = null

    fun insideOf(player: Player): String? = inside[player.uniqueId]

    /** 들어선 것으로 적기만(도착·접속·부활·포탈 이동) — 이미 안에 있는 자리에서 다시 쏘지 않게. */
    fun mark(player: Player, at: Location) {
        val portal = store.at(at)
        if (portal == null) inside.remove(player.uniqueId) else inside[player.uniqueId] = portal.key
    }

    fun forget(player: Player) {
        inside.remove(player.uniqueId)
        pending.remove(player.uniqueId)
        corners.remove(player.uniqueId)
        lastEntered.remove(player.uniqueId)
        lastDenied.remove(player.uniqueId)
    }

    /**
     * [from]→[to] 길에서 처음 만나는, 지금 서 있는 포탈([was])이 아닌 포탈. 한 걸음이 1블록을 넘어도(겉날개·급류·속도·지연) 지나간 블록을
     * 전부 본다(`PortalRules.blocksAlong`) — 얇은 벽 포탈을 건너뛰지 않게. 블록마다 청크 색인 한 번.
     */
    private fun crossed(from: Location, to: Location, was: String?): Portal? {
        val world = to.world?.name ?: return null
        if (from.world != to.world) return store.at(to)?.takeIf { it.key != was }
        for ((x, y, z) in PortalRules.blocksAlong(from.x, from.y, from.z, to.x, to.y, to.z, MAX_STEPS)) {
            val portal = store.at(world, x, y, z) ?: continue
            if (portal.key != was) return portal
        }
        return null
    }

    /** HIGHEST — 쓸 수 없는 포탈(밀어내기 켜짐)로 들어서려는 걷기를 취소하고 밀어낸다. 쓸 수 있으면 아무것도 하지 않는다(쏘기는 [moved]). */
    fun guard(player: Player, from: Location, to: Location, event: Cancellable) {
        val portal = crossed(from, to, inside[player.uniqueId]) ?: return
        if (!portal.enabled || !portal.kickBack || canUse(player, portal)) return
        event.isCancelled = true
        denied(player, portal)
        kickBack(player, portal, from, to, cancelled = true)
    }

    /**
     * MONITOR — 걷기가 정말 일어났다(취소되지 않음). 새로 들어선 포탈을 쏘고, 서 있는 포탈을 적는다.
     * [vehicle] 이 있으면 그 사람은 탈것을 타고 지나갔다(밀어내기는 탈것을).
     */
    fun moved(player: Player, from: Location, to: Location, vehicle: Entity? = null) {
        val id = player.uniqueId
        val hit = crossed(from, to, inside[id])
        val at = store.at(to)
        if (at == null) inside.remove(id) else inside[id] = at.key
        if (hit == null || !hit.enabled) return
        enter(player, hit, from, to, vehicle)
    }

    /** 탈것이 블록을 옮겼다(`VehicleMoveEvent`) — 탄 사람마다 걷기와 같은 판정. */
    fun ridden(vehicle: Entity, from: Location, to: Location) {
        for (passenger in vehicle.passengers) if (passenger is Player) moved(passenger, from, to, vehicle)
    }

    /** 고칠 수 있나 — portals.yml 이 깨져 잠겼으면 알리고 false(저장하면 손으로 고치던 파일을 덮는다). */
    fun editable(sender: CommandSender): Boolean {
        val why = store.locked ?: return true
        ess.messages.send(sender, "portals-locked", Ph.of().value(why))
        return false
    }

    /** 콘솔 명령을 더하거나 바꿀 수 있나 — [PortalsFeature.CONSOLE] 이 없으면 `player:` 명령만. 안 되면 알리고 false. */
    fun mayAddConsole(sender: CommandSender, before: List<String>, after: List<String>): Boolean {
        if (sender.hasPermission(PortalsFeature.CONSOLE)) return true
        val had = before.filter(PortalRules::isConsole).map { it.trim() }.toMutableList()
        val added = after.filter(PortalRules::isConsole).map { it.trim() }.filter { !had.remove(it) }
        if (added.isEmpty()) return true
        ess.messages.send(sender, "portals-console-denied", Ph.of().value(added.joinToString(" | ")))
        return false
    }

    /** 검증기 — 쿨타임 기록을 지운다(메모리만). */
    internal fun forgetUse(player: Player, key: String) {
        lastUse[player.uniqueId]?.remove(key)
    }

    fun canUse(player: Player, portal: Portal): Boolean =
        player.hasPermission(PortalsFeature.BYPASS) ||
            (player.hasPermission(PortalsFeature.USE) && (!portal.permission || player.hasPermission(PortalRules.node(portal))))

    private fun denied(player: Player, portal: Portal) {
        if (!portal.informPermission) return
        val now = System.currentTimeMillis()
        val last = lastDenied[player.uniqueId]
        if (last != null && now - last < DENY_MESSAGE_MILLIS) return
        lastDenied[player.uniqueId] = now
        ess.messages.send(player, "portals-no-permission", Ph.of().raw("{포탈}", portal.name))
    }

    private fun enter(player: Player, portal: Portal, from: Location, to: Location, vehicle: Entity?) {
        val id = player.uniqueId
        val ph = Ph.of().raw("{포탈}", portal.name)
        if (id in pending) return // 앞의 이동이 아직 — 그 이동이 끝나면 효과·명령이 한 번 돈다
        if (!canUse(player, portal)) {
            // 밀어내기가 켜진 포탈은 [guard] 가 이미 막았다 — 여기는 밀어내기가 꺼졌거나(알리기만) 다른 플러그인이 취소를 되돌린 경우, 또는 탈것.
            denied(player, portal)
            if (portal.kickBack) kickBack(player, portal, from, to, cancelled = false, vehicle = vehicle)
            return
        }
        val now = System.currentTimeMillis()
        if (!player.hasPermission(PortalsFeature.BYPASS)) {
            val left = PortalRules.cooldownLeft(lastUse[id]?.get(portal.key), portal.cooldown, now)
            if (left > 0) return ess.messages.send(player, "portals-cooldown", ph.time(left.toString()))
        }
        lastEntered[id] = portal.key
        val target = portal.target
        if (target == null) {
            if (!portal.commandsWithoutTarget) return
            lastUse.getOrPut(id) { HashMap() }[portal.key] = now
            effects(player, portal)
            runCommands(player, portal)
            return
        }
        val location = target.toLocation() ?: return ess.messages.send(player, "portals-target-missing", ph.world(target.world))
        // 쿨타임은 먼저 적어 두고(이동이 끝나기 전 다시 들어서도 두 번 쏘지 않게) 이동이 실패하면 되돌린다.
        lastUse.getOrPut(id) { HashMap() }[portal.key] = now
        if (capture != null) {
            runCommands(player, portal)
            return
        }
        pending += id
        ess.teleporter.teleport(player, location, TeleportKind.PORTAL).whenComplete { ok, _ ->
            // teleportAsync 의 완료는 메인이 아닐 수 있다 — 효과·명령은 그 사람의 스케줄러에서. 효과(실명 등)·소리는 이동이 된 뒤에만.
            // 예외로 끝나도(thenAccept 는 건너뛴다) 대기를 푼다. 그 사람이 나갔으면 [forget] 이 푼다.
            player.scheduler.run(ess.plugin, { _ ->
                pending.remove(id)
                if (ok == true) {
                    effects(player, portal)
                    runCommands(player, portal)
                } else {
                    lastUse[id]?.let { if (it[portal.key] == now) it.remove(portal.key) }
                    ess.messages.send(player, "portals-teleport-failed", Ph.of().raw("{포탈}", portal.name))
                }
            }, null)
        }
    }

    /**
     * 막을 때 — 안전 자리가 있으면 그리로(이동이 거절되면 — 감옥·수갑·던전·안전 자리 없음 — 밀기로), 없으면 왔던 쪽으로 민다.
     * [cancelled] = 걷기를 이미 취소했나(HIGHEST). MONITOR 에서 불리면 이미 들어섰으니 "안에 있음" 을 지워 다시 들어서면 또 막게 한다.
     */
    private fun kickBack(player: Player, portal: Portal, from: Location, to: Location, cancelled: Boolean, vehicle: Entity? = null) {
        if (!cancelled) inside.remove(player.uniqueId)
        if (capture != null) return // 검증기 — 사람을 실제로 밀거나 옮기지 않는다
        val safe = portal.safe?.toLocation() ?: return push(vehicle ?: player, portal, from, to)
        ess.teleporter.teleport(player, safe, TeleportKind.PORTAL).whenComplete { ok, _ ->
            if (ok != true) player.scheduler.run(ess.plugin, { _ -> push(vehicle?.takeIf { it.isValid } ?: player, portal, from, to) }, null)
        }
    }

    /** 왔던 쪽으로 민다 — 탈것이면 탈것을(위로 띄우지 않는다 — 수레가 선로에서 벗어나지 않게). */
    private fun push(entity: Entity, portal: Portal, from: Location, to: Location) {
        val (dx, dz) = PortalRules.pushBack(from.x, from.z, to.x, to.z, portal.box)
        val strength = settings.pushStrength
        if (strength > 0 && (dx != 0.0 || dz != 0.0)) entity.velocity = Vector(dx * strength, if (entity is Player) 0.25 else 0.0, dz * strength)
    }

    private fun effects(player: Player, portal: Portal) {
        for (raw in portal.effects) {
            val spec = PortalRules.parseEffect(raw) ?: continue
            val type = NamespacedKey.fromString(spec.type)?.let { Registry.MOB_EFFECT.get(it) } ?: continue
            player.addPotionEffect(PotionEffect(type, spec.seconds * 20, spec.level - 1, true, false, true))
        }
        portal.sound?.let { sound ->
            runCatching { player.playSound(player.location, sound, SoundCategory.PLAYERS, 1f, 1f) }
        }
    }

    /** 명령 — `{player}`·`[playerName]`·`{포탈}`·PAPI(그 사람 기준). 콘솔 명령은 `player:` 가 없을 때. */
    fun runCommands(player: Player, portal: Portal) {
        val ph = Ph.of().player(player.name).raw("[playerName]", player.name).raw("{포탈}", portal.name).raw("{portal}", portal.name)
        for (raw in portal.commands) {
            val command = PortalRules.parseCommand(raw) ?: continue
            val text = Text.substituteOnly(command.command, ph, player)
            capture?.let {
                it += (if (command.asPlayer) "player:" else "console:") + text
                continue
            }
            try {
                if (command.asPlayer) player.performCommand(text) else Bukkit.dispatchCommand(Bukkit.getConsoleSender(), text)
            } catch (t: Throwable) {
                ess.logger.warning("포탈 ${portal.name} 의 명령 실패: $text — $t")
            }
        }
    }

    // --- 파티클 (반복 작업) ------------------------------------------------------------------------------

    fun particles() {
        val random = ThreadLocalRandom.current()
        for (portal in store.all()) {
            val p = portal.particles
            if (!portal.enabled || !p.enabled) continue
            val world = Bukkit.getWorld(portal.box.world) ?: continue
            if (!world.isChunkLoaded(portal.box.centerX.toInt() shr 4, portal.box.centerZ.toInt() shr 4)) continue
            val range2 = p.range.toDouble() * p.range
            val viewers = world.players.filter { viewer ->
                val l = viewer.location
                portal.box.distanceSquared(l.x, l.y, l.z) <= range2 && (p.withoutPermission || canUse(viewer, portal))
            }
            if (viewers.isEmpty()) continue
            val (particle, data) = particleOf(p)
            val points = List(p.amount) { PortalRules.particlePoint(portal.box, p.origin, p.shrink, random.nextDouble(), random.nextDouble(), random.nextDouble()) }
            for (viewer in viewers) {
                for ((x, y, z) in points) viewer.spawnParticle(particle, x, y, z, 1, 0.0, 0.0, 0.0, 0.0, data)
            }
        }
    }

    private fun particleOf(p: Portal.Particles): Pair<Particle, Any?> {
        val particle = runCatching { Particle.valueOf(p.type.trim().uppercase()) }.getOrNull() ?: return Particle.PORTAL to null
        return when (particle.dataType) {
            Void::class.java -> particle to null
            Particle.DustOptions::class.java -> particle to Particle.DustOptions(Color.fromRGB(p.color ?: DEFAULT_DUST), 1f)
            else -> Particle.PORTAL to null
        }
    }

    // --- 관리 -----------------------------------------------------------------------------------------

    /** 모서리 찍기(1 또는 2) — 서 있는 블록. */
    fun corner(player: Player, index: Int) {
        val array = corners.getOrPut(player.uniqueId) { arrayOfNulls(2) }
        array[index] = player.location.block.location
    }

    /** 고른 영역과 그 출처. */
    data class Selected(val box: PortalBox, val fromCorners: Boolean)

    /**
     * `/포탈 위치1·2` 로 찍은 두 모서리가 둘 다 있으면 그것(방금 찍은 것 — 오래된 `//wand` 선택보다 먼저), 아니면 WorldEdit 선택.
     * 둘 다 없으면 null. 만들기·다시 정하기가 성공하면 [clearCorners].
     */
    fun selection(player: Player): Selected? {
        val array = corners[player.uniqueId]
        val a = array?.get(0)
        val b = array?.get(1)
        val world = a?.world
        if (a != null && b != null && world != null && world == b.world) {
            return Selected(PortalBox.of(world.name, a.blockX, a.blockY, a.blockZ, b.blockX, b.blockY, b.blockZ), true)
        }
        Selections.of(player)?.let { s -> return Selected(PortalBox(s.world.name, s.minX, s.minY, s.minZ, s.maxX, s.maxY, s.maxZ), false) }
        return null
    }

    fun clearCorners(player: Player) {
        corners.remove(player.uniqueId)
    }

    /** 출처를 사람 말로(`portals-source-*`). */
    fun sourceLabel(selected: Selected): String =
        ess.messages.raw(if (selected.fromCorners) "portals-source-corners" else "portals-source-worldedit")

    /** 다른 포탈과 겹치면 그 포탈(CMI collides). */
    fun collision(box: PortalBox, except: String? = null): Portal? =
        store.all().firstOrNull { it.key != except?.let(PortalRules::keyOf) && it.box.intersects(box) }

    /** 가까운 순. 다른 월드는 뒤로. */
    fun byDistance(from: Location): List<Portal> =
        store.all().sortedWith(compareBy({ it.box.world != from.world?.name }, { it.box.distanceSquared(from.x, from.y, from.z) }))

    /** 포탈 안 가운데 바닥(관리자 이동용). */
    fun center(portal: Portal): Location? =
        Bukkit.getWorld(portal.box.world)?.let { Location(it, portal.box.centerX, portal.box.minY.toDouble(), portal.box.centerZ) }

    /** 오래된 쿨타임 기록 비우기(1분마다). */
    fun prune(now: Long) {
        val longest = (store.all().maxOfOrNull { it.cooldown } ?: 0) * 1000L
        lastUse.values.forEach { map -> map.values.removeIf { now - it > longest } }
        lastUse.values.removeIf { it.isEmpty() }
        lastDenied.values.removeIf { now - it > DENY_MESSAGE_MILLIS }
    }

    companion object {
        const val DEFAULT_DUST = 0xAA00FF

        /** 한 걸음에 따라가 볼 블록 수의 상한. */
        const val MAX_STEPS = 64

        const val DENY_MESSAGE_MILLIS = 1500L

        /** 포탈에 쓸 수 있는 파티클 이름 — 데이터가 없거나 DUST(색)인 것만(나머지는 뿌릴 때 PORTAL 로 바뀌므로 고르지 못하게). */
        fun usableParticles(): List<String> = Particle.entries
            .filter { it.dataType == Void::class.java || it.dataType == Particle.DustOptions::class.java }
            .map { it.name }
    }
}
