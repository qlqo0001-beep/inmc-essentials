package com.inmc.essentials.moderation

import com.inmc.essentials.service.TeleportKind
import com.inmc.essentials.store.LogoutSpot
import com.inmc.essentials.store.UserRecord
import com.inmc.essentials.util.Ph
import kr.inmc.core.util.AtomicFiles
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 감옥(CMI Jail) — 정의는 `data/jails.yml`, 갇힌 사람은 DB(`ess_moderation_jailed`). 메인 스레드.
 *
 * 남은 시간은 접속해 있는 동안 1초씩 준다(설정 `count-while-offline` 이면 나가 있는 동안도 — 들어올 때 그만큼 뺀다). 잠수 중엔 멈출 수 있다
 * (`no-afk`). 벗어나기는 이동 사건이 막고(영역 밖으로 한 걸음), 그래도 밖에 있으면(밀려남·영역을 고침) 1초 틱이 감방으로 돌려보낸다.
 * 모든 이동은 `ess.teleporter` 의 JAIL 로 — 이동 사건 리스너는 **목적지가 그 사람의 감옥 안**일 때만 갇힌 사람의 순간이동을 통과시킨다.
 */
class JailService(private val mod: Moderation) {

    private val ess get() = mod.ess
    private val state get() = mod.state
    private val settings get() = mod.settings

    /** 감옥 정의(메인에서만 바꾼다 — 읽기는 다른 스레드에서도 하므로 통째로 갈아 끼운다). */
    @Volatile
    var jails: List<Jail> = emptyList()
        private set

    /** 못 읽은 감옥의 원문 — 다음 저장에 되써 넣는다. */
    private var raw: Map<String, Any?> = emptyMap()

    /** 쓰지 않는 감방의 원문(못 읽음·영역 밖) — 다음 저장에 되써 넣는다. 영역을 다시 정하면([replaceArea]) 안에 드는 것은 돌아오고 나머지는 버린다. */
    internal var held: HeldCells = emptyMap()
        private set

    private val bars = ConcurrentHashMap<UUID, BossBar>()

    /** 감방으로 가는 중인 사람(이동이 비동기라 그 사이 다시 보내지 않게). */
    private val moving = ConcurrentHashMap.newKeySet<UUID>()

    private val file: File get() = ess.io.file("data", "jails.yml")

    // --- 정의 ------------------------------------------------------------------------------------

    /** 켤 때·리로드 때(메인 — 작은 파일 하나, 설정 파일 읽기와 같은 자리). */
    fun load() {
        val result = JailCodec.read(if (file.isFile) YamlConfiguration.loadConfiguration(file) else YamlConfiguration())
        jails = result.jails
        raw = result.raw
        held = result.held
        for (p in result.problems) ess.logger.warning("jails.yml: $p")
        // 갇힌 사람의 감옥이 파일에서 사라졌으면 — 가둘 자리가 없어 막지도 못한다(insideOwnJail 은 어디든 안으로 본다).
        for (jailed in state.jailed.values) if (jail(jailed.jail) == null) {
            ess.logger.warning("jails.yml: 갇힌 사람 ${ess.users.get(jailed.id)?.name ?: jailed.id} 의 감옥 '${jailed.jail}' 이(가) 없습니다 — /석방 하거나 감옥을 다시 만드세요")
        }
    }

    fun save() {
        val text = JailCodec.write(jails, raw, held).saveToString()
        val target = file
        ess.io.asyncRun { AtomicFiles.write(target, text) }
    }

    fun jail(name: String): Jail? = jails.firstOrNull { it.name.equals(name, true) }

    fun replace(jail: Jail) {
        jails = jails.filterNot { it.name.equals(jail.name, true) } + jail
        save()
    }

    /** 검증기 — 파일에 쓰지 않고 메모리에만 넣고 뺀다. */
    internal fun putMemory(jail: Jail) {
        jails = jails.filterNot { it.name.equals(jail.name, true) } + jail
    }

    internal fun removeMemory(name: String) {
        jails = jails.filterNot { it.name.equals(name, true) }
    }

    /** 지우고 그 감옥에 갇힌 사람을 푼다. 푼 수. 사람을 먼저 푼다 — 그 감옥의 석방 위치(`outside`)를 쓰기 위해. */
    fun remove(name: String): Int {
        val jail = jail(name) ?: return 0
        val inside = state.jailed.values.filter { it.jail.equals(jail.name, true) }
        for (j in inside) release(null, j.id, silent = true, auto = true)
        jails = jails - jail
        held = held - jail.name
        save()
        return inside.size
    }

    /**
     * 영역을 바꾼다(관리자의 뜻). 새 영역 밖이 되는 감방은 뺀다(들어갈 수 없는 자리) — 그 감방의 사람은 감방 없음으로. 파일에만 남겨 둔 감방([held])은
     * 새 영역 안에 들면 되살리고, 아니면 버린다. 뺀 감방들.
     */
    fun replaceArea(jail: Jail, area: JailArea): List<JailCell> {
        val dropped = jail.cells.filterNot { area.contains(it.spot) }
        val kept = jail.cells - dropped.toSet()
        val revived = held[jail.name].orEmpty().mapNotNull { (key, text) ->
            val id = key.toIntOrNull() ?: return@mapNotNull null
            val spot = JailCodec.spot(text) ?: return@mapNotNull null
            if (area.contains(spot) && kept.none { it.id == id }) JailCell(id, spot) else null
        }
        held = held - jail.name
        replace(jail.copy(area = area, cells = (kept + revived).sortedBy { it.id }))
        for (cell in dropped) for (j in occupants(jail.name, cell.id)) {
            j.cell = null
            mod.store.putJailed(j)
        }
        return dropped
    }

    fun occupants(jail: String, cell: Int? = null): List<Jailed> =
        state.jailed.values.filter { it.jail.equals(jail, true) && (cell == null || it.cell == cell) }

    // --- 가두기·풀기 -------------------------------------------------------------------------------

    /**
     * 가둔다. 이미 갇혀 있으면 시간만 바꾼다([change]). [jailName]·[cellId] 가 없으면 사람이 가장 적은 감옥·감방.
     * @return 성공했나
     */
    fun imprison(sender: CommandSender, user: UserRecord, change: ModRules.Change?, jailName: String?, cellId: Int?, reason: String?, silent: Boolean): Boolean {
        val now = System.currentTimeMillis()
        val existing = state.jailed[user.id]
        if (existing != null && change != null && jailName == null) {
            val until = ModRules.applyChange(now + existing.remainingMs, true, change, now) ?: return false
            val seconds = ((until - now) / 1000)
            if (seconds <= 0) {
                release(sender, user.id, silent, auto = false)
                return true
            }
            if (change.mode != ModRules.Mode.SUB && !mod.withinLimit(sender, seconds, ModerationFeature.JAIL_MAX, settings.jailMaxWithout)) return false
            existing.remainingMs = seconds * 1000
            existing.updated = now
            mod.store.putJailTime(existing.id, existing.remainingMs, now)
            val ph = Ph.of().player(user.name).time(mod.timeText(seconds))
            ess.messages.send(sender, "moderation-jail-time-changed", ph)
            Bukkit.getPlayer(user.id)?.let { ess.messages.send(it, "moderation-jail-target-changed", ph) }
            return true
        }
        if (!mod.canTarget(sender, user.id, user.name, ModerationFeature.JAIL_BYPASS)) return false
        if (jails.isEmpty()) {
            ess.messages.send(sender, "moderation-jail-none")
            return false
        }
        val jail = if (jailName != null) jail(jailName) ?: return false.also { ess.messages.send(sender, "moderation-jail-unknown", Ph.of().value(jailName)) }
        else jails.minBy { occupants(it.name).size }
        val cell = when {
            cellId != null -> jail.cell(cellId) ?: return false.also {
                ess.messages.send(sender, "moderation-jail-cell-unknown", Ph.of().value(cellId.toString()).raw("{감옥}", jail.name))
            }
            jail.cells.isEmpty() -> null
            else -> jail.cells.minBy { occupants(jail.name, it.id).size }
        }
        val seconds = change?.seconds ?: settings.jailDefaultSeconds
        if (!mod.withinLimit(sender, seconds, ModerationFeature.JAIL_MAX, settings.jailMaxWithout)) return false
        val online = Bukkit.getPlayer(user.id)
        val prior = existing?.prior ?: online?.location?.let { LogoutSpot.of(it) } ?: user.logout
        val why = reason?.takeIf { it.isNotBlank() } ?: settings.jailDefaultReason
        val jailed = Jailed(user.id, jail.name, cell?.id, seconds * 1000, now, why, mod.who(sender), now, prior)
        // 나가 있는 동안 석방됐다가 다시 갇히면 — 접속 때 밖으로 보내지 않는다.
        if (state.pendingRelease.remove(user.id) != null) mod.store.deleteRelease(user.id)
        state.jailed[user.id] = jailed
        mod.store.putJailed(jailed)

        val time = mod.timeText(seconds)
        val ph = Ph.of().player(user.name).time(time).reason(why).raw("{감옥}", jail.name).raw("{감방}", cell?.id?.toString() ?: "-")
            .raw("{관리자}", mod.who(sender))
        if (online != null) {
            sendToCell(online, jailed)
            ess.messages.send(online, "moderation-jail-target", ph)
            if (settings.jailTitle) online.showTitle(Title.title(ess.messages.component("moderation-jail-title", ph), ess.messages.component("moderation-jail-subtitle", ph),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofMillis(500))))
            showBar(online, jailed)
        }
        ess.messages.send(sender, "moderation-jail-done", ph)
        mod.announce(sender, "moderation-jail-broadcast", ph, online, silent, public = true)
        mod.runCommands(settings.onJail, user.name, why, time)
        return true
    }

    /**
     * 푼다. [auto] = 시간이 다 돼서·감옥이 지워져서(보낸 사람 없음).
     * 나가 있는 사람은 감방 안에서 로그아웃했을 수 있으니 석방 자리를 적어 두고([ModState.pendingRelease]) 다음 접속 때 보낸다([joined]).
     */
    fun release(sender: CommandSender?, id: UUID, silent: Boolean, auto: Boolean): Boolean {
        val jailed = state.jailed.remove(id) ?: return false
        mod.store.deleteJailed(id)
        val player = Bukkit.getPlayer(id)
        val name = player?.name ?: ess.users.get(id)?.name ?: id.toString()
        hideBar(id)
        val spot = releaseSpot(jailed)
        if (player != null) {
            spot?.toLocation()?.let { ess.teleporter.teleport(player, it, TeleportKind.JAIL, safe = true) }
            ess.messages.send(player, "moderation-unjail-target")
        } else if (spot != null) {
            state.pendingRelease[id] = spot
            mod.store.putRelease(id, spot)
        }
        val ph = Ph.of().player(name)
        if (sender != null) {
            ess.messages.send(sender, "moderation-unjail-done", ph)
            if (!auto) mod.announce(sender, "moderation-unjail-broadcast", ph, player, silent, public = true)
        }
        mod.runCommands(settings.onUnjail, name)
        return true
    }

    // --- 자리 ------------------------------------------------------------------------------------

    fun cellLocation(jailed: Jailed): Location? {
        val jail = jail(jailed.jail) ?: return null
        val spot = jailed.cell?.let { jail.cell(it)?.spot } ?: jail.area.center()
        return spot.toLocation()
    }

    /** 석방 자리 — 감옥의 석방 위치 → 가두기 전 자리 → 감옥 월드의 스폰 → 첫 월드의 스폰. */
    private fun releaseSpot(jailed: Jailed): LogoutSpot? =
        jail(jailed.jail)?.outside ?: jailed.prior
            ?: (jail(jailed.jail)?.area?.world?.let { Bukkit.getWorld(it) } ?: Bukkit.getWorlds().firstOrNull())?.spawnLocation?.let { LogoutSpot.of(it) }

    /** 그 자리가 이 사람의 감옥 안인가(감옥 정의가 사라졌으면 어디든 — 가둘 수 없으니 막지 않는다). */
    fun insideOwnJail(id: UUID, loc: Location): Boolean {
        val jailed = state.jailed[id] ?: return true
        val jail = jail(jailed.jail) ?: return true
        return jail.area.contains(loc)
    }

    /** 지금 이 기능이 감방으로 보내는 중인가 — 이동 리스너가 그 이동은 막지 않는다. */
    fun isMoving(id: UUID): Boolean = id in moving

    fun sendToCell(player: Player, jailed: Jailed) {
        val to = cellLocation(jailed) ?: return
        if (!moving.add(player.uniqueId)) return
        ess.teleporter.teleport(player, to, TeleportKind.JAIL, safe = false).whenComplete { _, _ -> moving.remove(player.uniqueId) }
    }

    // --- 시계 ------------------------------------------------------------------------------------

    /** 1초마다. 접속한 갇힌 사람의 시간을 깎고, 다 되면 풀고, 밖에 있으면 돌려보낸다. */
    fun tick(now: Long) {
        for (jailed in state.jailed.values.toList()) {
            val player = Bukkit.getPlayer(jailed.id) ?: continue
            val elapsed = (now - jailed.updated).coerceIn(0, 5_000)
            jailed.updated = now
            if (!(settings.jailNoAfk && ess.afk.isAfk(player))) {
                jailed.remainingMs -= elapsed
                jailed.dirty = true
            }
            if (jailed.remainingMs <= 0) {
                release(null, jailed.id, silent = true, auto = true)
                continue
            }
            if (!insideOwnJail(jailed.id, player.location)) sendToCell(player, jailed)
            updateBar(player, jailed)
        }
    }

    /** 30초마다·끌 때 — 바뀐 남은 시간을 쓴다. */
    fun flush() {
        for (jailed in state.jailed.values) {
            if (!jailed.dirty) continue
            jailed.dirty = false
            mod.store.putJailTime(jailed.id, jailed.remainingMs, jailed.updated)
        }
    }

    /** 들어왔을 때. 나가 있는 동안 석방됐으면 석방 자리로. 오프라인에도 깎는 설정이면 그만큼 빼고, 다 됐으면 풀고, 아니면 감방으로. */
    fun joined(player: Player) {
        state.pendingRelease.remove(player.uniqueId)?.let { spot ->
            mod.store.deleteRelease(player.uniqueId)
            if (!state.jailed.containsKey(player.uniqueId)) {
                spot.toLocation()?.let { ess.teleporter.teleport(player, it, TeleportKind.JAIL, safe = true) }
                ess.messages.send(player, "moderation-unjail-target")
            }
        }
        val jailed = state.jailed[player.uniqueId] ?: return
        val now = System.currentTimeMillis()
        if (settings.jailCountOffline) jailed.remainingMs -= (now - jailed.updated).coerceAtLeast(0)
        jailed.updated = now
        jailed.dirty = true
        if (jailed.remainingMs <= 0) {
            release(null, player.uniqueId, silent = true, auto = true)
            return
        }
        if (!insideOwnJail(player.uniqueId, player.location)) sendToCell(player, jailed)
        showBar(player, jailed)
    }

    fun quit(player: Player) {
        val jailed = state.jailed[player.uniqueId] ?: return
        jailed.updated = System.currentTimeMillis()
        jailed.dirty = false
        mod.store.putJailTime(jailed.id, jailed.remainingMs, jailed.updated)
        hideBar(player.uniqueId)
        moving.remove(player.uniqueId)
    }

    // --- 보스바 ----------------------------------------------------------------------------------

    private fun barText(jailed: Jailed) = ess.messages.component("moderation-jail-bossbar", Ph.of().time(mod.timeText(jailed.remainingSeconds())))

    private fun showBar(player: Player, jailed: Jailed) {
        if (!settings.jailBossbar) return
        val bar = bars.getOrPut(player.uniqueId) { BossBar.bossBar(barText(jailed), 1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS) }
        player.showBossBar(bar)
    }

    private fun updateBar(player: Player, jailed: Jailed) {
        if (!settings.jailBossbar) return hideBar(player.uniqueId)
        val bar = bars[player.uniqueId] ?: return showBar(player, jailed)
        bar.name(barText(jailed))
    }

    fun hideBar(id: UUID) {
        val bar = bars.remove(id) ?: return
        Bukkit.getPlayer(id)?.hideBossBar(bar)
    }

    fun hideAll() {
        for (id in bars.keys.toList()) hideBar(id)
    }

    // --- 영역 보여 주기 ----------------------------------------------------------------------------

    /** 영역의 모서리를 [viewer] 에게만 10초 동안 입자로(그 사람의 일 — player.scheduler). */
    fun show(viewer: Player, jail: Jail) {
        val a = jail.area
        val world = Bukkit.getWorld(a.world) ?: return
        var left = 10
        viewer.scheduler.runAtFixedRate(ess.plugin, { task ->
            if (left-- <= 0 || !viewer.isOnline || viewer.world != world) {
                task.cancel()
                return@runAtFixedRate
            }
            val xs = listOf(a.minX.toDouble(), a.maxX + 1.0)
            val ys = listOf(a.minY.toDouble(), a.maxY + 1.0)
            val zs = listOf(a.minZ.toDouble(), a.maxZ + 1.0)
            fun line(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double) {
                val len = maxOf(Math.abs(x2 - x1), Math.abs(y2 - y1), Math.abs(z2 - z1)).coerceAtLeast(1.0)
                val steps = (len * 2).toInt().coerceAtMost(400)
                for (i in 0..steps) {
                    val t = i / steps.toDouble()
                    viewer.spawnParticle(org.bukkit.Particle.END_ROD, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, z1 + (z2 - z1) * t, 1, 0.0, 0.0, 0.0, 0.0)
                }
            }
            for (y in ys) for (z in zs) line(xs[0], y, z, xs[1], y, z)
            for (x in xs) for (z in zs) line(x, ys[0], z, x, ys[1], z)
            for (x in xs) for (y in ys) line(x, y, zs[0], x, y, zs[1])
        }, null, 1L, 20L)
    }

    companion object {
        /** 화면·목록에서 쓰는 남은 시간 글. */
        fun remainingText(mod: Moderation, jailed: Jailed): String {
            val online = Bukkit.getPlayer(jailed.id) != null
            return mod.timeText((jailed.estimate(System.currentTimeMillis(), mod.settings.jailCountOffline, online) / 1000).coerceAtLeast(1))
        }
    }
}
