package com.inmc.essentials.teleport

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block

/**
 * 안전한 도착 자리 찾기. 메인 스레드에서, 도착 청크가 읽힌 뒤에 부른다(옆 청크가 안 읽혀 있으면 그 기둥은 건너뛴다 — 동기 로딩으로 서버를
 * 멈추지 않게).
 *
 * 안전 = 발·머리 칸이 지나갈 수 있고 위험하지 않음(용암·불·선인장·가루눈·포탈 …) + 발밑이 단단하거나 물(물에 뜨는 것은 안전). 날 수 있는
 * 사람([needGround] = false)은 발밑을 보지 않는다. 처음 자리가 안전하면 **그 자리 그대로**(소수 좌표·시선 유지).
 */
object SafeSpot {

    /**
     * 시작 높이에서 위아래로 볼 순서(순수). CMI `SafeLocationDownThenUp`: false = 위부터 끝까지 본 다음 아래, true = 아래부터 다음에 위.
     * [minY]..[maxY] 밖은 뺀다.
     */
    fun order(startY: Int, minY: Int, maxY: Int, range: Int, downThenUp: Boolean): List<Int> {
        val up = (1..range).map { startY + it }
        val down = (1..range).map { startY - it }
        return (listOf(startY) + if (downThenUp) down + up else up + down).filter { it in minY..maxY }
    }

    /** 옆으로 볼 기둥 순서(순수) — 가까운 고리부터. (0,0) 이 먼저. */
    fun columns(radius: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        out += 0 to 0
        for (r in 1..radius) {
            for (dx in -r..r) for (dz in -r..r) if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz)) == r) out += dx to dz
        }
        return out
    }

    /** [to] 근처의 안전한 자리. 없으면 null. 월드 경계 밖이면 먼저 안으로 당긴다. */
    fun find(to: Location, settings: TeleportSettings, needGround: Boolean): Location? {
        val world = to.world ?: return null
        val start = insideBorder(to)
        if (isSafeExact(start, needGround)) return start
        val baseX = start.blockX
        val baseZ = start.blockZ
        val minY = world.minHeight + 1
        val maxY = world.maxHeight - 2
        val ys = order(start.blockY.coerceIn(minY, maxY), minY, maxY, settings.safeVertical, settings.safeDownThenUp)
        for ((dx, dz) in columns(settings.safeHorizontal)) {
            val x = baseX + dx
            val z = baseZ + dz
            if (!world.isChunkLoaded(x shr 4, z shr 4)) continue
            for (y in ys) {
                if (isSafeColumn(world, x, y, z, needGround)) {
                    val spot = Location(world, x + 0.5, y.toDouble(), z + 0.5, to.yaw, to.pitch)
                    if (world.worldBorder.isInside(spot)) return spot
                }
            }
        }
        return null
    }

    /** 월드 경계 안으로(경계에서 1.5 블록 안쪽). 이미 안이면 그대로. */
    fun insideBorder(to: Location): Location {
        val world = to.world ?: return to
        val border = world.worldBorder
        if (border.isInside(to)) return to
        val half = (border.size / 2.0 - 1.5).coerceAtLeast(0.0)
        val center = border.center
        val x = to.x.coerceIn(center.x - half, center.x + half)
        val z = to.z.coerceIn(center.z - half, center.z + half)
        return Location(world, x, to.y, z, to.yaw, to.pitch)
    }

    /** 처음 자리 그대로 서도 되나 — 반 블록(반 블록 위·눈 쌓인 곳) 위에 선 경우도 본다. */
    fun isSafeExact(at: Location, needGround: Boolean): Boolean {
        val world = at.world ?: return false
        val y = at.blockY
        if (y < world.minHeight || y > world.maxHeight - 2) return false
        val x = at.blockX
        val z = at.blockZ
        if (isSafeColumn(world, x, y, z, needGround)) return true
        // 발 칸이 반 블록 같은 것이고 그 위에 서 있다(y 의 소수부가 그 블록의 윗면 이상) — 몸은 그 위 두 칸.
        val feet = world.getBlockAt(x, y, z)
        if (feet.isPassable || danger(feet.type)) return false
        val top = feet.collisionShape.boundingBoxes.maxOfOrNull { it.maxY } ?: 0.0
        val frac = at.y - y
        if (top > frac + 0.01) return false
        val body1 = world.getBlockAt(x, y + 1, z)
        val body2 = world.getBlockAt(x, y + 2, z)
        return open(body1) && (frac < 0.2 || open(body2))
    }

    private fun isSafeColumn(world: World, x: Int, y: Int, z: Int, needGround: Boolean): Boolean {
        val feet = world.getBlockAt(x, y, z)
        val head = world.getBlockAt(x, y + 1, z)
        if (!open(feet) || !open(head)) return false
        if (!needGround) return true
        val ground = world.getBlockAt(x, y - 1, z)
        if (danger(ground.type)) return false
        return ground.type.isSolid || ground.type == Material.WATER || feet.type == Material.WATER
    }

    /** 몸이 들어가도 되는 칸 — 지나갈 수 있고 위험하지 않다. 물은 된다, 용암은 안 된다. */
    private fun open(block: Block): Boolean = (block.isPassable || block.type == Material.WATER) && !danger(block.type)

    /** 닿으면 다치거나 갇히거나 다른 곳으로 끌려가는 블록. */
    fun danger(type: Material): Boolean = type in DANGER

    // lazy — 순수 시험(order·columns)이 Material 을 건드리지 않게.
    private val DANGER: Set<Material> by lazy {
        setOf(
            Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.MAGMA_BLOCK,
            Material.CACTUS, Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE, Material.POWDER_SNOW, Material.COBWEB,
            Material.NETHER_PORTAL, Material.END_PORTAL, Material.END_GATEWAY, Material.POINTED_DRIPSTONE, Material.LAVA_CAULDRON,
        )
    }
}
