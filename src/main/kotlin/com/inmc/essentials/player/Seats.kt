package com.inmc.essentials.player

import com.inmc.essentials.Ess
import com.inmc.essentials.service.TeleportKind
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.type.Slab
import org.bukkit.block.data.type.Stairs
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/**
 * 앉기 — 보이지 않는 좌석 엔티티(`ItemDisplay`, **저장되지 않는** 엔티티)에 태운다. 좌석에는 PDC `inmcess:seat` 를 단다 —
 * 서버 기능의 `/유틸` 이 이 열쇠로 남은 좌석을 치운다(바꾸지 말 것 — ARCHITECTURE PDC 표).
 *
 * 일어나기: 웅크리기(내리기 사건) · 나감 · 죽음 · 이동 · 피해(설정) · 의자 블록이 부서짐 · 기능 끔.
 * `-persistent` 로 앉힌 사람은 나갔다 들어와도 다시 앉힌다(메모리 — 서버를 다시 켜면 잊는다).
 */
class Seats(private val ess: Ess, private val config: () -> PlayerConfig) {

    /** 앉은 사람 → 좌석 엔티티. 메인. */
    private val seated = HashMap<UUID, Entity>()

    /** 좌석 블록(의자) → 앉은 사람. 같은 의자에 둘이 앉지 않게. */
    private val chairs = HashMap<String, UUID>()

    /** 다시 접속하면 다시 앉힐 사람 → 자리. */
    private val persistent = HashMap<UUID, Location>()

    /** 지금 일어나는 중(내리기 사건이 다시 [stand] 를 부르지 않게). */
    private val standing = HashSet<UUID>()

    /** 의자에 앉기 전 서 있던 자리 — 일어날 때(내리기 이동) 여기로 돌려보낸다. */
    private val origins = HashMap<UUID, Location>()

    /** 더블클릭 판정 — 사람 → (블록 열쇠, 시각). */
    private val clicks = HashMap<UUID, Pair<String, Long>>()

    fun isSeated(player: Player): Boolean = seated[player.uniqueId]?.isValid == true

    fun seatOf(player: Player): Entity? = seated[player.uniqueId]

    fun isSeat(entity: Entity): Boolean = entity.persistentDataContainer.has(SEAT_KEY, PersistentDataType.BYTE)

    fun occupant(block: Block): UUID? = chairs[key(block)]

    /**
     * [at] 에 앉힌다. 이미 앉아 있으면 먼저 일으킨다. 타고 있는 게 있으면 내린다. 성공하면 true.
     * [origin] 이 있으면 일어날 때 그 자리로 돌려보낸다(의자 — 손이 닿지만 걸어갈 수 없는 자리에 앉았다 빠져나가지 못하게).
     */
    fun sit(player: Player, at: Location, chair: Block? = null, keep: Boolean = false, origin: Location? = null): Boolean {
        if (isSeated(player)) stand(player)
        if (player.isInsideVehicle) player.leaveVehicle()
        val world = at.world ?: return false
        val spot = at.clone().add(0.0, config().sitHeight, 0.0)
        spot.yaw = if (chair != null) at.yaw else player.location.yaw
        spot.pitch = 0f
        val seat = world.spawn(spot, ItemDisplay::class.java) { e ->
            e.isPersistent = false
            e.persistentDataContainer.set(SEAT_KEY, PersistentDataType.BYTE, 1)
            e.setGravity(false)
            e.isInvulnerable = true
        }
        if (!seat.addPassenger(player)) {
            seat.remove()
            return false
        }
        seated[player.uniqueId] = seat
        if (origin != null) origins[player.uniqueId] = origin.clone() else origins.remove(player.uniqueId)
        if (chair != null) chairs[key(chair)] = player.uniqueId
        if (keep) persistent[player.uniqueId] = at.clone() else persistent.remove(player.uniqueId)
        return true
    }

    /**
     * 일으킨다. 좌석에서 내리면 바닐라가 좌석 자리(계단이면 아래 반 블록 위)에 세운다 — 우리가 옮기지 않는다(옮기면 이동 기능의
     * "이동 뒤 무적" 이 걸려 앉았다 일어나기로 무적을 얻는다). [forget] 이면 다시 접속해 앉기도 잊는다. 앉아 있었으면 true.
     */
    fun stand(player: Player, forget: Boolean = true): Boolean {
        val seat = seated.remove(player.uniqueId) ?: return false
        chairs.values.removeIf { it == player.uniqueId }
        if (forget) persistent.remove(player.uniqueId)
        standing += player.uniqueId
        try {
            if (player.vehicle == seat) seat.removePassenger(player)
            seat.remove()
        } finally {
            standing -= player.uniqueId
            origins.remove(player.uniqueId)
        }
        return true
    }

    fun isStanding(player: Player): Boolean = player.uniqueId in standing

    /** 좌석에서 내리는 이동을 돌려보낼 자리 — 앉아 있거나 일어나는 중이고 앉기 전 자리를 적어 둔 사람만. */
    fun dismountOrigin(player: Player): Location? {
        val origin = origins[player.uniqueId] ?: return null
        return if (player.uniqueId in seated || player.uniqueId in standing) origin else null
    }

    /** 나감 — 좌석을 치운다(움직이지 않고). 다시 앉을 사람이면 자리를 남긴다. */
    fun quit(player: Player) {
        stand(player, forget = false)
    }

    /** 접속 — 다시 앉을 사람이면 한 틱 뒤 앉힌다. */
    fun joined(player: Player) {
        val at = persistent[player.uniqueId] ?: return
        player.scheduler.runDelayed(ess.plugin, { _ ->
            if (!player.isOnline || at.world == null) return@runDelayed
            ess.teleporter.teleport(player, at, TeleportKind.OTHER, safe = false).thenAccept { ok ->
                if (ok == true && sit(player, at, keep = true)) ess.messages.send(player, "player-sit-restored")
            }
        }, null, 2L)
    }

    /** 이 블록에 앉은 사람을 일으킨다(블록이 부서졌을 때). */
    fun freeChair(block: Block) {
        val id = chairs[key(block)] ?: return
        Bukkit.getPlayer(id)?.let { stand(it) }
    }

    /** 끌 때 — 좌석을 모두 치운다. */
    fun clear() {
        for ((id, seat) in seated.toMap()) {
            Bukkit.getPlayer(id)?.let { if (it.vehicle == seat) seat.removePassenger(it) }
            seat.remove()
        }
        seated.clear()
        chairs.clear()
        origins.clear()
    }

    /** 1초 — 좌석이 사라졌거나 비었으면 정리. */
    fun sweep() {
        val it = seated.entries.iterator()
        while (it.hasNext()) {
            val (id, seat) = it.next()
            val player = Bukkit.getPlayer(id)
            if (!seat.isValid || player == null || player.vehicle != seat) {
                seat.remove()
                chairs.values.removeIf { v -> v == id }
                origins.remove(id)
                it.remove()
            }
        }
    }

    // --- 의자 블록 ---------------------------------------------------------------------------

    /** 이 블록이 의자면 앉을 자리(블록 가운데, 윗면 높이). 아니면 null. */
    fun chairSpot(block: Block): Location? {
        val c = config()
        if (!c.sitChairs) return null
        val data = block.blockData
        val name = block.type.name
        val base = block.location.add(0.5, 0.0, 0.5)
        return when {
            c.sitStairs && data is Stairs && data.half == Bisected.Half.BOTTOM -> base.add(0.0, 0.5, 0.0).also { it.yaw = facingYaw(data.facing) }
            c.sitSlabs && data is Slab && data.type == Slab.Type.BOTTOM -> base.add(0.0, 0.5, 0.0)
            c.sitCarpets && name.endsWith("_CARPET") -> base.add(0.0, 0.0625, 0.0)
            else -> null
        }
    }

    /** 더블클릭이 켜져 있으면 같은 블록을 [CMI DoubleClickDelay] 안에 두 번 눌러야 true. */
    fun doubleClicked(player: Player, block: Block, now: Long = System.currentTimeMillis()): Boolean {
        val c = config()
        if (!c.sitDoubleClick) return true
        val k = key(block)
        val last = clicks[player.uniqueId]
        if (last != null && last.first == k && now - last.second <= c.sitDoubleClickMillis) {
            clicks.remove(player.uniqueId)
            return true
        }
        clicks[player.uniqueId] = k to now
        return false
    }

    fun forgetClicks(player: Player) {
        clicks.remove(player.uniqueId)
    }

    /** 계단이 향한 쪽의 반대(앉은 사람이 바라볼 쪽). */
    private fun facingYaw(face: org.bukkit.block.BlockFace): Float = when (face) {
        org.bukkit.block.BlockFace.NORTH -> 0f
        org.bukkit.block.BlockFace.EAST -> 90f
        org.bukkit.block.BlockFace.SOUTH -> 180f
        org.bukkit.block.BlockFace.WEST -> -90f
        else -> 0f
    }

    private fun key(block: Block): String = "${block.world.name}:${block.x}:${block.y}:${block.z}"

    companion object {
        /** 좌석 표시 — **바꾸지 않는다**(서버 기능 `/유틸` 의 남은 좌석 치우기가 이 열쇠를 본다). */
        val SEAT_KEY = NamespacedKey(Ess.NAMESPACE, "seat")
    }
}
