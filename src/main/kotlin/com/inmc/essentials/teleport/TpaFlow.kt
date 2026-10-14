package com.inmc.essentials.teleport

import com.inmc.essentials.Ess
import com.inmc.essentials.command.Args
import com.inmc.essentials.service.TeleportKind
import com.inmc.essentials.util.Ph
import kr.inmc.core.integration.Vanish
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player
import java.util.UUID

/**
 * 이동 요청의 흐름 — 요청 · 수락(대기 뒤 이동) · 거절 · 취소 · 만료 · 나감 · 받기 끄기 · 사람 막기. 메인 스레드.
 *
 * 쿨타임: 요청한 사람이 친 명령 이름(`TpaBook.Request.label`)으로 **이동이 성공한 순간** `ess.gate?.commit` 을 부른다.
 * 감옥·수갑(`ess.moderation`)인 사람은 요청도 수락도 못 한다(요청한 쪽·받는 쪽 둘 다).
 */
class TpaFlow(private val ess: Ess, private val feature: TeleportFeature, private val service: TeleportService) {

    private val book get() = service.book

    /** 수락 뒤 기다리는 사람(옮겨질 사람) → 그 요청. */
    private val waiting = HashMap<UUID, TpaBook.Request>()

    private fun restricted(id: UUID): Boolean = ess.moderation.isJailed(id) || ess.moderation.isCuffed(id)

    /** [worlds] 중 이동 요청 제외 월드(`tpa.excluded-worlds` — 던전)의 이름. 없으면 null. */
    fun excludedWorld(vararg worlds: World?): String? = worlds.firstOrNull { it != null && feature.settings.tpaExcluded(it.name) }?.name

    /** [id] 가 접속해 있으면 그 사람에게 이동이 이루어지지 않았다고 알린다(옮겨질 사람이 아닌 쪽 — 기다리던 사람). */
    private fun tellFailed(id: UUID, moverName: String) {
        Bukkit.getPlayer(id)?.let { ess.messages.send(it, "teleport-tpa-failed", Ph.of().player(moverName)) }
    }

    /**
     * 요청 하나를 건다. [here] = 소환요청. 막히면 까닭을 [sender] 에게 말하고 false([quiet] 면 말하지 않는다 — 전원 요청에서 건너뛸 때).
     */
    fun request(sender: Player, target: Player, here: Boolean, label: String, all: Boolean = false, quiet: Boolean = false): Boolean {
        val s = feature.settings
        fun fail(key: String, ph: Ph = Ph.of()): Boolean {
            if (!quiet) ess.messages.send(sender, key, ph)
            return false
        }
        if (target == sender) return fail("teleport-self")
        if (restricted(sender.uniqueId)) return fail("teleport-tpa-restricted-self")
        if (restricted(target.uniqueId)) return fail("teleport-tpa-restricted-target", Ph.of().target(target.name))
        // 던전 같은 제외 월드 — 안에서 부르거나 안으로 가지 못하게(요청한 쪽·받는 쪽 어느 쪽이 그 월드에 있어도).
        excludedWorld(sender.world, target.world)?.let { return fail("teleport-tpa-excluded-world", Ph.of().world(it)) }
        val bypass = sender.hasPermission(TeleportFeature.TOGGLE_BYPASS)
        if (service.refuses(target.uniqueId, sender.uniqueId, bypass)) {
            return fail("teleport-tpa-not-accepting", Ph.of().target(target.name))
        }
        val now = System.currentTimeMillis()
        val blockedFor = book.denyBlockLeft(sender.uniqueId, target.uniqueId, now)
        if (blockedFor > 0 && !bypass) return fail("teleport-tpa-deny-blocked", Ph.of().target(target.name).time(seconds(blockedFor).toString()))
        val max = if (here) s.tpaHereMaxDistance else s.tpaMaxDistance
        if (max > 0 && !sender.hasPermission(TeleportFeature.NO_DISTANCE)) {
            val far = sender.world != target.world || sender.location.distance(target.location) > max
            if (far) return fail("teleport-tpa-too-far", Ph.of().target(target.name).value(max.toString()))
        }
        // 이동요청이면 옮겨질 사람은 요청한 사람 — 금지 아이템을 지금 알려 준다(수락받고 나서 막히면 상대가 헛걸음).
        if (!here && s.blacklistApplies(TeleportKind.TPA) && !sender.hasPermission(EssTeleporter.BYPASS_BLACKLIST)) {
            val over = feature.teleporter.violations(sender, s)
            if (over.isNotEmpty()) {
                if (!quiet) ess.messages.send(sender, "teleport-blacklisted", Ph.of().count(over.size).raw("{목록}", over.joinToString("<newline>")))
                return false
            }
        }
        val snapshot = when {
            here && !s.tpaHereCurrentLocation -> sender.location.clone()
            !here && !s.tpaCurrentLocation -> target.location.clone()
            else -> null
        }
        book.add(TpaBook.Request(sender.uniqueId, sender.name, target.uniqueId, target.name, here, label, now, now + s.tpaExpireSeconds * 1000L, snapshot, all))

        val ph = Ph.of().player(sender.name).target(target.name).time(s.tpaExpireSeconds.toString())
        if (!all) {
            ess.messages.send(sender, if (here) "teleport-tpahere-sent" else "teleport-tpa-sent", ph)
            if (s.tpaButtons) ess.messages.send(sender, "teleport-tpa-cancel-button", ph)
        }
        ess.messages.send(target, if (here) "teleport-tpahere-received" else "teleport-tpa-received", ph)
        ess.messages.send(target, if (s.tpaButtons) "teleport-tpa-buttons" else "teleport-tpa-hint", ph)
        feature.teleporter.play(target, target.location, s.tpaRequestSound, onlyPlayer = true)
        return true
    }

    /** 보이는 접속자 전원에게 소환요청(받기를 끈 사람·감옥 등은 조용히 건너뛴다). */
    fun requestAll(sender: Player, label: String) {
        if (restricted(sender.uniqueId)) {
            ess.messages.send(sender, "teleport-tpa-restricted-self")
            return
        }
        var sent = 0
        for (target in Vanish.visibleOnline(sender)) {
            if (target == sender) continue
            if (request(sender, target, here = true, label = label, all = true, quiet = true)) sent++
        }
        ess.messages.send(sender, "teleport-tpaall-sent", Ph.of().count(sent).time(feature.settings.tpaExpireSeconds.toString()))
    }

    /** [player] 가 받은 요청 중 [name](없으면 가장 최근)을 고른다. */
    private fun pickIncoming(player: Player, name: String?): TpaBook.Request? {
        val incoming = book.incoming(player.uniqueId)
        if (name.isNullOrBlank()) return incoming.firstOrNull()
        return Args.pick(name.trim(), incoming) { it.fromName }
    }

    fun accept(player: Player, name: String?) {
        if (restricted(player.uniqueId)) {
            ess.messages.send(player, "teleport-tpa-restricted-self")
            return
        }
        val request = pickIncoming(player, name)
        if (request == null) {
            ess.messages.send(player, "teleport-tpa-none")
            return
        }
        book.take(player.uniqueId, request.from)
        val requester = Bukkit.getPlayer(request.from)
        if (requester == null) {
            ess.messages.send(player, "teleport-tpa-gone", Ph.of().player(request.fromName))
            return
        }
        if (restricted(requester.uniqueId)) {
            ess.messages.send(player, "teleport-tpa-restricted-target", Ph.of().target(requester.name))
            return
        }
        val excluded = excludedWorld(player.world, requester.world, request.snapshot?.world)
        if (excluded != null) {
            ess.messages.send(player, "teleport-tpa-excluded-world", Ph.of().world(excluded))
            ess.messages.send(requester, "teleport-tpa-excluded-world", Ph.of().world(excluded))
            return
        }
        val mover = if (request.here) player else requester
        val ph = Ph.of().player(requester.name).target(player.name)
        ess.messages.send(player, "teleport-tpa-accepted", ph)
        ess.messages.send(requester, "teleport-tpa-accepted-by", ph)
        val warmup = feature.settings.tpaWarmupSeconds
        if (warmup <= 0) {
            move(request, mover)
            return
        }
        startWarmup(request, mover, warmup)
    }

    private fun startWarmup(request: TpaBook.Request, mover: Player, seconds: Int) {
        var left = seconds
        ess.messages.send(mover, "teleport-tpa-warmup", Ph.of().time(left.toString()))
        val task = mover.scheduler.runAtFixedRate(ess.plugin, { task ->
            left--
            if (left > 0) {
                mover.sendActionBar(ess.messages.component("teleport-tpa-warmup-bar", Ph.of().time(left.toString())))
                return@runAtFixedRate
            }
            task.cancel()
            service.warmupDone(mover.uniqueId, task)
            waiting.remove(mover.uniqueId)
            move(request, mover)
        }, null, 20L, 20L)
        if (task != null) {
            waiting[mover.uniqueId] = request
            service.startWarmup(mover.uniqueId, task)
        }
    }

    /** 옮긴다 — 도착 자리는 요청한 때의 자리(설정) 또는 지금 상대가 있는 자리. 성공하면 요청한 사람의 명령 쿨타임을 기록한다. */
    private fun move(request: TpaBook.Request, mover: Player) {
        val anchorId = if (request.here) request.from else request.to
        val anchorName = if (request.here) request.fromName else request.toName
        val anchor = Bukkit.getPlayer(anchorId)
        val destination = request.snapshot ?: anchor?.location
        if (destination == null || !mover.isOnline) {
            if (mover.isOnline) ess.messages.send(mover, "teleport-tpa-gone", Ph.of().player(anchorName))
            return
        }
        // 기다리는 사이에 바뀌었을 수 있다 — 감옥·수갑이 됐거나 상대가 던전(제외 월드)에 들어갔다. 감방·던전으로 데려가지 않는다.
        if (restricted(mover.uniqueId) || restricted(anchorId)) {
            ess.messages.send(mover, "teleport-tpa-restricted-target", Ph.of().target(anchorName))
            tellFailed(anchorId, mover.name)
            return
        }
        excludedWorld(mover.world, anchor?.world, destination.world)?.let { world ->
            ess.messages.send(mover, "teleport-tpa-excluded-world", Ph.of().world(world))
            tellFailed(anchorId, mover.name)
            return
        }
        val effect = if (request.all) TeleportSettings.FX_TPAALL else null
        feature.move(mover, destination, TeleportKind.TPA, command = effect).thenAccept { ok ->
            if (ok != true) {
                // 옮겨질 사람에게는 EssTeleporter 가 까닭을 말했다 — 기다리던 쪽에도 알린다.
                tellFailed(anchorId, mover.name)
                return@thenAccept
            }
            ess.messages.send(mover, "teleport-tpa-done", Ph.of().target(anchorName))
            Bukkit.getPlayer(request.from)?.let { requester -> ess.gate?.commit(requester, request.label) }
        }
    }

    fun deny(player: Player, name: String?) {
        val request = pickIncoming(player, name)
        if (request == null) {
            ess.messages.send(player, "teleport-tpa-none")
            return
        }
        book.take(player.uniqueId, request.from)
        val blockSeconds = feature.settings.tpaDenyBlockSeconds
        if (blockSeconds > 0) book.blockAfterDeny(request.from, player.uniqueId, System.currentTimeMillis() + blockSeconds * 1000L)
        val ph = Ph.of().player(request.fromName).target(player.name)
        ess.messages.send(player, "teleport-tpa-denied", ph)
        Bukkit.getPlayer(request.from)?.let { ess.messages.send(it, "teleport-tpa-denied-by", ph) }
    }

    /** 내가 보낸 요청을 거둔다([name] 이 없으면 전부). 이미 수락돼 기다리는 이동도 멈춘다. */
    fun cancel(player: Player, name: String?) {
        val outgoing = book.outgoing(player.uniqueId)
        val mine = waiting.entries.filter { it.value.from == player.uniqueId }.map { it.key to it.value }
        // 이름은 한 번만 고른다 — 장부의 요청과 이미 수락돼 기다리는 이동을 합친 이름들에서 같은 규칙(Args.pick)으로.
        val picked: UUID? = if (name.isNullOrBlank()) null else {
            val candidates = (outgoing.map { it.to to it.toName } + mine.map { it.second.to to it.second.toName }).distinctBy { it.first }
            Args.pick(name.trim(), candidates) { it.second }?.first ?: run {
                ess.messages.send(player, "teleport-tpa-none-sent")
                return
            }
        }
        val chosen = outgoing.filter { picked == null || it.to == picked }
        for (request in chosen) book.cancel(player.uniqueId, request.to)
        var count = chosen.size
        for ((moverId, request) in mine) {
            if (picked != null && request.to != picked) continue
            service.cancelWarmup(moverId)
            waiting.remove(moverId)
            Bukkit.getPlayer(moverId)?.takeIf { it != player }?.let { ess.messages.send(it, "teleport-tpa-cancelled-by", Ph.of().player(player.name)) }
            count++
        }
        if (count == 0) {
            ess.messages.send(player, "teleport-tpa-none-sent")
            return
        }
        ess.messages.send(player, "teleport-tpa-cancelled", Ph.of().count(count))
        for (request in chosen) if (!request.all) Bukkit.getPlayer(request.to)?.let { ess.messages.send(it, "teleport-tpa-cancelled-by", Ph.of().player(player.name)) }
    }

    fun toggle(player: Player) {
        val now = !service.accepting(player.uniqueId)
        service.setAccepting(player.uniqueId, now)
        ess.messages.send(player, if (now) "teleport-tptoggle-on" else "teleport-tptoggle-off")
    }

    fun toggleBlock(player: Player, other: UUID, otherName: String) {
        if (other == player.uniqueId) {
            ess.messages.send(player, "teleport-self")
            return
        }
        val blocked = service.toggleBlock(player.uniqueId, other)
        if (blocked) book.cancel(other, player.uniqueId)
        ess.messages.send(player, if (blocked) "teleport-tpblock-on" else "teleport-tpblock-off", Ph.of().target(otherName))
    }

    fun movedDuringWarmup(player: Player) {
        if (!service.cancelWarmup(player.uniqueId)) return
        val request = waiting.remove(player.uniqueId)
        ess.messages.send(player, "teleport-tpa-warmup-moved")
        if (request != null) {
            val otherId = if (request.from == player.uniqueId) request.to else request.from
            Bukkit.getPlayer(otherId)?.let { ess.messages.send(it, "teleport-tpa-warmup-cancelled", Ph.of().player(player.name)) }
        }
    }

    /** [player] 가 나갔다 — 걸려 있던 요청의 상대에게 알리고, 그 사람에게 가려고 기다리던 이동을 멈춘다. */
    fun leftWith(player: Player, gone: List<TpaBook.Request>) {
        waiting.remove(player.uniqueId)
        for (request in gone) {
            val otherId = if (request.from == player.uniqueId) request.to else request.from
            if (request.all && request.from == player.uniqueId) continue
            Bukkit.getPlayer(otherId)?.let { ess.messages.send(it, "teleport-tpa-left", Ph.of().player(player.name)) }
        }
        for ((moverId, request) in waiting.entries.toList()) {
            if (request.from != player.uniqueId && request.to != player.uniqueId) continue
            service.cancelWarmup(moverId)
            waiting.remove(moverId)
            Bukkit.getPlayer(moverId)?.let { ess.messages.send(it, "teleport-tpa-left", Ph.of().player(player.name)) }
        }
    }

    fun tick(now: Long) {
        for (request in service.tick(now)) {
            if (request.all) continue
            Bukkit.getPlayer(request.from)?.let { ess.messages.send(it, "teleport-tpa-expired", Ph.of().target(request.toName)) }
        }
    }

    fun shutdown() {
        waiting.clear()
    }

    private fun seconds(millis: Long): Long = (millis + 999) / 1000
}
