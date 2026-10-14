package com.inmc.essentials.teleport

import com.inmc.essentials.Ess
import com.inmc.essentials.service.TeleportKind
import com.inmc.essentials.service.Teleporter
import com.inmc.essentials.util.Ph
import kr.inmc.core.integration.CarriedStorage
import kr.inmc.core.integration.Vanish
import kr.inmc.core.item.ItemRef
import kr.inmc.core.util.VanillaNames
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.ItemStack
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

/**
 * teleport 기능의 [Teleporter] — 모든 기능의 이동이 여기를 지난다(`ess.teleporter`).
 *
 * 순서: 금지 아이템(사람이 고른 이동만) → 도착 청크를 비동기로 읽기 → 안전한 자리(서바이벌·모험) → 탄 것·태운 것·끈에 묶인 것 떼기 →
 * 출발 파티클 → `teleportAsync(PLUGIN)`(뒤로 기록은 사건 리스너가 — `/뒤로` 자신의 이동은 뺀다) → 따라오는 것 옮기기 → 도착 효과 → 무적.
 *
 * 막히면(금지 아이템·안전한 자리 없음) **이 구현이 그 사람에게 까닭을 말하고** false 를 돌려준다 — 부른 쪽은 같은 말을 되풀이하지 않아도 된다.
 */
class EssTeleporter(
    private val ess: Ess,
    private val service: TeleportService,
    private val settings: () -> TeleportSettings,
) : Teleporter {

    override fun teleport(player: Player, to: Location, kind: TeleportKind, safe: Boolean): CompletableFuture<Boolean> =
        go(player, to, kind, safe, command = null)

    /**
     * 이 기능 자신의 명령이 부른다 — [command] 는 명령마다 효과 열쇠(`TeleportSettings.FX_*`, CMI TeleportEffects 이름).
     * 종류가 OTHER 이고 [command] 가 TP·TPPOS 면 **사람이 고른 자기 이동**(`/이동`·`/좌표이동`)이다 — 금지 아이템은 설정의 `TP` 칸으로 보고,
     * 안전한 자리가 없으면 막는다. 다른 기능이 부르는 OTHER 는 시스템 이동(토템 구조·앉기·접속 복귀)이라 금지 아이템을 보지 않고,
     * 안전한 자리가 없으면 관리자 이동처럼 그 자리 그대로 간다(막으면 허공에서 구조가 안 된다).
     */
    fun go(player: Player, to: Location, kind: TeleportKind, safe: Boolean, command: String?): CompletableFuture<Boolean> {
        val world = to.world ?: return CompletableFuture.completedFuture(false)
        if (!player.isOnline || player.isDead) return CompletableFuture.completedFuture(false)
        val s = settings()
        val chosen = isChosen(kind, command)
        val checkBlacklist = if (chosen) s.blacklistAppliesToTp() else s.blacklistApplies(kind)
        if (checkBlacklist && !player.hasPermission(BYPASS_BLACKLIST)) {
            val over = violations(player, s)
            if (over.isNotEmpty()) {
                ess.messages.send(player, "teleport-blacklisted", Ph.of().count(over.size).raw("{목록}", over.joinToString("<newline>")))
                play(player, player.location, s.failSound, onlyPlayer = true)
                return CompletableFuture.completedFuture(false)
            }
        }
        val result = CompletableFuture<Boolean>()
        // 청크를 먼저 비동기로 읽는다 — 안전한 자리 찾기가 메인에서 청크를 동기로 읽어 서버를 멈추지 않게. 완료는 메인 스레드다.
        world.getChunkAtAsync(to).whenComplete { _, error ->
            try {
                if (error != null || !player.isOnline) {
                    result.complete(false)
                    return@whenComplete
                }
                move(player, to, kind, safe, s, result, chosen, command)
            } catch (t: Throwable) {
                ess.logger.log(Level.WARNING, "이동 실패(${player.name} → ${world.name})", t)
                result.complete(false)
            }
        }
        return result
    }

    private fun move(
        player: Player, to: Location, kind: TeleportKind, safe: Boolean, s: TeleportSettings, result: CompletableFuture<Boolean>,
        chosen: Boolean, command: String?,
    ) {
        val checkSafe = safe && s.safeEnabled && kind != TeleportKind.JAIL && player.gameMode.let { it == GameMode.SURVIVAL || it == GameMode.ADVENTURE }
        // 관리자 이동·다른 기능의 시스템 이동(OTHER)은 안전한 자리를 못 찾아도 그 자리 그대로 간다.
        val dest = if (checkSafe) SafeSpot.find(to, s, needGround = !player.allowFlight) ?: to.takeIf { exactFallback(kind, chosen) } else to
        if (dest == null) {
            ess.messages.send(player, "teleport-unsafe")
            play(player, player.location, s.failSound, onlyPlayer = true)
            result.complete(false)
            return
        }

        // 내려야 옮길 수 있다. 막히면 **권한과 무관하게** 다시 태운다(아무것도 안 움직였는데 말에서 떨어지지 않게).
        // 따라오기는 권한이 있을 때만 — 없으면 Bukkit 이 하는 대로 혼자 간다(타고 있던 것은 남고 태운 것은 내려진다).
        val with = player.hasPermission(WITH)
        val vehicle = player.vehicle
        val passengers = player.passengers.toList()
        val leashed = if (with) leashedBy(player).filter { it.world == dest.world } else emptyList()
        if (player.isInsideVehicle) player.leaveVehicle()
        if (player.passengers.isNotEmpty()) player.eject()

        val from = player.location.clone()
        val effect = s.effectFor(kind, command)
        // 투명인 사람의 자리를 파티클로 알리지 않는다(CLAUDE.md 8).
        val hidden = Vanish.isVanished(player)
        if (!hidden) particles(from, effect?.from)
        val suppress = suppressesBack(kind, s)
        if (suppress) service.suppress(player.uniqueId)
        val future = try {
            player.teleportAsync(dest, PlayerTeleportEvent.TeleportCause.PLUGIN)
        } catch (t: Throwable) {
            if (suppress) service.unsuppress(player.uniqueId)
            throw t
        }
        future.whenComplete { ok, error ->
            if (suppress) service.unsuppress(player.uniqueId)
            if (error != null || ok != true || !player.isOnline) {
                // 이동이 막혔다(다른 플러그인이 취소 등) — 내린 것을 다시 태운다(같은 자리).
                if (player.isOnline) {
                    vehicle?.takeIf { it.isValid }?.addPassenger(player)
                    for (p in passengers) if (p.isValid) player.addPassenger(p)
                }
                result.complete(false)
                return@whenComplete
            }
            try {
                if (with) follow(player, dest, vehicle?.takeIf { it !is Player }, passengers, leashed)
                if (!hidden) particles(dest, effect?.to)
                play(player, dest, effect?.sound)
                service.grantInvulnerability(player.uniqueId, s.invulnerabilityFor(dest.world?.name.orEmpty()))
            } catch (t: Throwable) {
                ess.logger.log(Level.WARNING, "이동 뒤 처리 실패(${player.name})", t)
            }
            result.complete(true)
        }
    }

    /** 탄 것·태운 것·끈에 묶인 것을 옮기고 다시 잇는다. 탈것에 같이 탔던 다른 손님은 내려 둔다(태운 채로는 옮길 수 없다). */
    private fun follow(player: Player, dest: Location, vehicle: Entity?, passengers: List<Entity>, leashed: List<LivingEntity>) {
        if (vehicle != null && vehicle.isValid) {
            vehicle.eject()
            vehicle.teleportAsync(dest, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept { ok ->
                if (ok == true && player.isOnline && vehicle.isValid && player.world == vehicle.world) vehicle.addPassenger(player)
            }
        }
        for (passenger in passengers) {
            if (!passenger.isValid) continue
            passenger.teleportAsync(dest, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept { ok ->
                if (ok == true && player.isOnline && passenger.isValid && player.world == passenger.world) player.addPassenger(passenger)
            }
        }
        for (mob in leashed) {
            if (!mob.isValid) continue
            mob.teleportAsync(dest, PlayerTeleportEvent.TeleportCause.PLUGIN)
        }
    }

    /**
     * 끈으로 [player] 에게 묶인 것(10블록 안 — 끈이 닿는 거리). **같은 월드 이동만** 따라온다 — 월드를 넘으면 바닐라가 끈을 끊어 떨어뜨리는데,
     * 그 뒤 다시 묶으면 끈이 하나 늘어난다(복사 경로를 만들지 않는다).
     */
    private fun leashedBy(player: Player): List<LivingEntity> =
        player.getNearbyEntities(10.0, 10.0, 10.0).mapNotNull { entity ->
            val living = entity as? LivingEntity ?: return@mapNotNull null
            if (living is Player) return@mapNotNull null
            val holder = runCatching { if (living.isLeashed) living.leashHolder else null }.getOrNull()
            living.takeIf { holder == player }
        }

    // --- 금지 아이템 ---------------------------------------------------------------------------

    @Volatile
    private var resolvedFor: List<BlacklistRule>? = null

    @Volatile
    private var resolved: List<Pair<BlacklistRule, ItemRef>> = emptyList()

    /** 규칙을 core `ItemRef` 로(설정이 바뀔 때만 다시). 못 읽는 줄은 로그 한 번. */
    private fun rules(s: TeleportSettings): List<Pair<BlacklistRule, ItemRef>> {
        if (resolvedFor === s.blacklist) return resolved
        val out = s.blacklist.mapNotNull { rule ->
            val ref = ItemRef.parse(rule.ref).takeUnless { it is ItemRef.None } ?: cmiMaterial(rule.ref) ?: ItemRef.None
            if (ref is ItemRef.None) {
                ess.logger.warning("settings/teleport.yml 이동 금지 아이템 '${rule.ref}' 을(를) 알아보지 못했습니다 — 건너뜁니다")
                null
            } else {
                rule to ref
            }
        }
        resolved = out
        resolvedFor = s.blacklist
        return out
    }

    /** CMI 모양 재료 이름(`DiamondBlock` · `ironore`) — 밑줄·대소문자를 빼고 맞춘다. 이름공간이 붙었으면 보지 않는다. */
    private fun cmiMaterial(name: String): ItemRef? {
        if (name.contains(':')) return null
        val squashed = name.replace("_", "").replace(" ", "").uppercase()
        return org.bukkit.Material.entries.firstOrNull { !it.isLegacy && it.name.replace("_", "") == squashed }?.let { ItemRef.Vanilla(it) }
    }

    /** 넘은 것마다 한 줄(`이름 n개 (최대 m개)`) — 메시지의 마우스 올림 목록. 없으면 빈 목록. */
    fun violations(player: Player, s: TeleportSettings): List<String> {
        val rules = rules(s)
        if (rules.isEmpty()) return emptyList()
        val stacks = player.inventory.contents.filterNotNull().filter { !it.type.isAir }
        return rules.mapNotNull { (rule, ref) ->
            val test = { stack: ItemStack -> matches(stack, ref) }
            val count = stacks.sumOf { if (test(it)) it.amount else 0 } + CarriedStorage.count(player, test)
            if (count > rule.max) ess.messages.raw("teleport-blacklisted-line")
                .replace("{아이템}", label(ref))
                .replace("{개수}", count.toString())
                .replace("{최대}", rule.max.toString())
            else null
        }
    }

    private fun matches(stack: ItemStack, ref: ItemRef): Boolean = when (ref) {
        is ItemRef.Vanilla -> stack.type == ref.material && ess.mmoItems.identify(stack) == null && ess.customItems.identify(stack) == null
        is ItemRef.MMOItems -> ess.mmoItems.identify(stack) == ref
        is ItemRef.Namespaced -> ref in ess.customItems.identifyAll(stack)
        ItemRef.None -> false
    }

    /** 마우스 올림 글 안에 들어가므로 따옴표·꺾쇠를 뺀다(MiniMessage 인자를 깨지 않게). */
    private fun label(ref: ItemRef): String {
        val text = when (ref) {
            is ItemRef.Vanilla -> VanillaNames.of(ref.material) ?: ref.material.name.lowercase()
            else -> ref.serialize()
        }
        return text.replace("'", "’").replace("<", "‹").replace(">", "›")
    }

    // --- 효과 -------------------------------------------------------------------------------------

    private fun particles(at: Location, spec: ParticleSpec?) {
        spec ?: return
        val world = at.world ?: return
        val particle = runCatching { Particle.valueOf(spec.name.uppercase()) }.getOrNull() ?: return
        // 데이터가 필요한 파티클(색 먼지·블록 조각)은 값 없이 뿌리면 던진다 — 건너뛴다.
        if (particle.dataType != Void::class.java) return
        runCatching { world.spawnParticle(particle, at.clone().add(0.0, 1.0, 0.0), spec.count, 0.4, 0.8, 0.4, 0.05) }
    }

    /**
     * 소리 — `entity.enderman.teleport` 같은 키면 그대로, CMI 의 `ENTITY_ENDERMAN_TELEPORT` 꼴이면 Sound 상수로. 둘레에 들리게 틀되,
     * 투명인 사람이면(또는 [onlyPlayer] 면) 그 사람에게만(자리가 새지 않게).
     */
    fun play(player: Player, at: Location, spec: SoundSpec?, onlyPlayer: Boolean = false) {
        spec ?: return
        val world = at.world ?: return
        val onlyMe = onlyPlayer || Vanish.isVanished(player)
        runCatching {
            val sound = if (spec.key.contains('.') || spec.key.contains(':')) null
            else runCatching { Sound::class.java.getField(spec.key.uppercase()).get(null) as? Sound }.getOrNull()
            when {
                sound != null && onlyMe -> player.playSound(at, sound, SoundCategory.PLAYERS, spec.volume, spec.pitch)
                sound != null -> world.playSound(at, sound, SoundCategory.PLAYERS, spec.volume, spec.pitch)
                onlyMe -> player.playSound(at, spec.key.lowercase(), SoundCategory.PLAYERS, spec.volume, spec.pitch)
                else -> world.playSound(at, spec.key.lowercase(), SoundCategory.PLAYERS, spec.volume, spec.pitch)
            }
        }
    }

    companion object {
        const val WITH = "inmcessentials.teleport.with"
        const val BYPASS_BLACKLIST = "inmcessentials.teleport.blacklist.bypass"

        /** 사람이 고른 자기 이동(`/이동`·`/좌표이동`)인가 — 다른 기능의 OTHER 와 가른다(순수). */
        fun isChosen(kind: TeleportKind, command: String?): Boolean =
            kind == TeleportKind.OTHER && (command == TeleportSettings.FX_TP || command == TeleportSettings.FX_TPPOS)

        /** 안전한 자리를 못 찾았을 때 그 자리 그대로 가나(순수) — 관리자 이동과 다른 기능의 시스템 이동(OTHER). */
        fun exactFallback(kind: TeleportKind, chosen: Boolean): Boolean =
            kind == TeleportKind.ADMIN || (kind == TeleportKind.OTHER && !chosen)

        /** 이 이동은 뒤로 기록에서 빼나(순수) — `/뒤로` 자신(설정) · 감옥 이동(가두기·풀기 — 풀린 뒤 `/뒤로` 로 감방에 돌아가지 않게). */
        fun suppressesBack(kind: TeleportKind, s: TeleportSettings): Boolean =
            (kind == TeleportKind.BACK && !s.backRecordBackMoves) || kind == TeleportKind.JAIL
    }
}
