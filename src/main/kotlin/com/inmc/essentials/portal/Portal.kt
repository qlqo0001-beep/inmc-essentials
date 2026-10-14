package com.inmc.essentials.portal

import org.bukkit.Bukkit
import org.bukkit.Location

/**
 * 포탈 한 개(2026-10-10) — 카탈로그 cfg3:cfg-portals-custom. 상자 영역([box])에 들어서면 [target] 으로 옮기고 [commands] 를 돈다.
 * 불변 값이다 — 고치면 새 값으로 바꿔 끼우고 저장소가 통째로 원자적으로 쓴다. **서버 없이 만들어진다**(시험).
 */
data class Portal(
    val name: String,
    val box: PortalBox,
    val target: PortalSpot? = null,
    /** 권한이 없어 막힐 때 보낼 자리(CMI safeLoc). 없으면 왔던 쪽으로 밀어낸다. */
    val safe: PortalSpot? = null,
    val enabled: Boolean = true,
    /** true 면 [PortalRules.node] 권한이 있어야 쓴다(CMI requiresPerm — `cmi.command.portal.<이름>`). */
    val permission: Boolean = false,
    /** 권한이 없으면 들어서지 못하게 밀어낸다(CMI kickBack). */
    val kickBack: Boolean = true,
    /** 권한이 없을 때 알린다(CMI informOnMissingPerm — CMI 기본은 false, 우리 새 포탈의 기본은 설정 `defaults.inform-permission`). */
    val informPermission: Boolean = true,
    /** 목적지가 없어도 명령을 돈다(CMI commandsWithoutTp · Defaults.PerformCommands). */
    val commandsWithoutTarget: Boolean = true,
    /** `player:` 로 시작하면 그 사람으로, 아니면 콘솔로. `{player}` · `[playerName]` · PAPI. */
    val commands: List<String> = emptyList(),
    /** `효과:초:레벨`(레벨 1부터) — 들어설 때 건다(CMI 기본 명령 `cmi effect … blindness 2 1` 을 명령 없이). */
    val effects: List<String> = emptyList(),
    /** 같은 사람이 이 포탈을 다시 쓰기까지(초). 0 = 없음. */
    val cooldown: Int = 0,
    /** 쓸 때 그 사람에게 들리는 소리(`minecraft:` 열쇠). 비우면 없음. */
    val sound: String? = null,
    val particles: Particles = Particles(),
) {
    val key: String get() = PortalRules.keyOf(name)

    data class Particles(
        val enabled: Boolean = true,
        /** `org.bukkit.Particle` 이름. 데이터가 필요한 것은 DUST(색 = [color])만 받는다 — 나머지는 PORTAL 로. */
        val type: String = "PORTAL",
        /** 0xRRGGBB — DUST 일 때. */
        val color: Int? = null,
        /** 1초에 보는 사람마다 뿌리는 수. */
        val amount: Int = 20,
        val origin: ParticleOrigin = ParticleOrigin.ANY,
        /** 면을 안쪽으로 줄이는 비율(%) — CMI particleHide. */
        val shrink: Int = 0,
        /** 이 거리(블록) 안의 사람에게만. CMI activationRange. */
        val range: Int = 8,
        /** false 면 그 포탈을 쓸 수 있는 사람에게만 보인다(CMI particlesByPermission 의 반대). */
        val withoutPermission: Boolean = true,
    )
}

/** 파티클을 뿌리는 자리(CMI CMIPortalParticleOrigin). 디스크에는 이름으로(지뢰 5). */
enum class ParticleOrigin(val label: String) {
    ANY("영역 전체"),
    TOP("윗면"),
    BOTTOM("아랫면"),
    CENTER("가운데 높이");

    fun next(): ParticleOrigin = entries[(ordinal + 1) % entries.size]

    companion object {
        fun parse(raw: String?): ParticleOrigin = entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: ANY
    }
}

/** 블록 좌표의 상자(양 끝 포함). */
data class PortalBox(val world: String, val minX: Int, val minY: Int, val minZ: Int, val maxX: Int, val maxY: Int, val maxZ: Int) {

    fun contains(world: String, x: Int, y: Int, z: Int): Boolean =
        world == this.world && x in minX..maxX && y in minY..maxY && z in minZ..maxZ

    fun contains(loc: Location): Boolean = loc.world?.name?.let { contains(it, loc.blockX, loc.blockY, loc.blockZ) } ?: false

    fun intersects(other: PortalBox): Boolean =
        world == other.world && minX <= other.maxX && maxX >= other.minX && minY <= other.maxY && maxY >= other.minY &&
            minZ <= other.maxZ && maxZ >= other.minZ

    val volume: Long get() = (maxX - minX + 1).toLong() * (maxY - minY + 1) * (maxZ - minZ + 1)

    /** 걸친 청크 수 — 색인 비용. */
    val chunkCount: Long get() = ((maxX shr 4) - (minX shr 4) + 1).toLong() * ((maxZ shr 4) - (minZ shr 4) + 1)

    val centerX: Double get() = (minX + maxX + 1) / 2.0
    val centerY: Double get() = (minY + maxY + 1) / 2.0
    val centerZ: Double get() = (minZ + maxZ + 1) / 2.0

    /** 이 상자가 걸친 청크마다 [PortalRules.chunkKey]. [PortalRules.MAX_CHUNKS] 를 넘는 상자는 빈 목록(읽기·만들기가 이미 막는다 — 마지막 방벽). */
    fun chunks(): List<Long> {
        if (chunkCount > PortalRules.MAX_CHUNKS) return emptyList()
        val out = ArrayList<Long>(chunkCount.toInt())
        for (cx in (minX shr 4)..(maxX shr 4)) for (cz in (minZ shr 4)..(maxZ shr 4)) out += PortalRules.chunkKey(cx, cz)
        return out
    }

    /** 점에서 상자까지의 거리의 제곱(안이면 0) — 파티클을 볼 사람 고르기. */
    fun distanceSquared(x: Double, y: Double, z: Double): Double {
        val dx = maxOf(minX - x, 0.0, x - (maxX + 1))
        val dy = maxOf(minY - y, 0.0, y - (maxY + 1))
        val dz = maxOf(minZ - z, 0.0, z - (maxZ + 1))
        return dx * dx + dy * dy + dz * dz
    }

    fun describe(): String = "$world ($minX, $minY, $minZ) ~ ($maxX, $maxY, $maxZ)"

    /** 저장 모양 `x1;y1;z1;x2;y2;z2`. */
    fun text(): String = "$minX;$minY;$minZ;$maxX;$maxY;$maxZ"

    companion object {
        /** 두 모서리를 아무 순서로. */
        fun of(world: String, x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int): PortalBox =
            PortalBox(world, minOf(x1, x2), minOf(y1, y2), minOf(z1, z2), maxOf(x1, x2), maxOf(y1, y2), maxOf(z1, z2))

        /** `x1;y1;z1;x2;y2;z2` 또는 CMI 의 `x1:y1:z1:x2:y2:z2`(소수도 받는다 — 내림). 못 읽으면 null. */
        fun parse(world: String, raw: String?): PortalBox? {
            val parts = raw?.trim()?.split(';', ':')?.map { it.trim() } ?: return null
            if (parts.size != 6) return null
            val n = parts.map { part -> part.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { kotlin.math.floor(it).toInt() } ?: return null }
            return of(world, n[0], n[1], n[2], n[3], n[4], n[5])
        }
    }
}

/** 월드 이름 + 좌표 + 방향. Location 은 월드가 열려 있어야 만들어지므로 디스크·메모리에는 이것으로. */
data class PortalSpot(val world: String, val x: Double, val y: Double, val z: Double, val yaw: Float = 0f, val pitch: Float = 0f) {

    fun toLocation(): Location? = Bukkit.getWorld(world)?.let { Location(it, x, y, z, yaw, pitch) }

    fun text(): String = listOf(world, num(x), num(y), num(z), num(yaw.toDouble()), num(pitch.toDouble())).joinToString(";")

    fun describe(): String = "$world ${num(x)}, ${num(y)}, ${num(z)}"

    companion object {
        fun of(loc: Location): PortalSpot = PortalSpot(loc.world?.name ?: "world", loc.x, loc.y, loc.z, loc.yaw, loc.pitch)

        /** `월드;x;y;z[;yaw;pitch]` 또는 `:` 구분(CMI DB). 못 읽으면 null. */
        fun parse(raw: String?): PortalSpot? {
            val parts = raw?.trim()?.split(';', ':')?.map { it.trim() } ?: return null
            if (parts.size < 4 || parts[0].isEmpty()) return null
            val n = parts.drop(1).map { part -> part.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null }
            return PortalSpot(parts[0], n[0], n[1], n[2], n.getOrNull(3)?.toFloat() ?: 0f, n.getOrNull(4)?.toFloat() ?: 0f)
        }

        private fun num(v: Double): String =
            if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e9) v.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, v)
    }
}

/** 효과 한 줄(`효과:초:레벨`) — 이름은 실행할 때 레지스트리로 찾는다. */
data class EffectSpec(val type: String, val seconds: Int, val level: Int)

/** 명령 한 줄 — [asPlayer] 면 그 사람으로, 아니면 콘솔로. */
data class PortalCommand(val asPlayer: Boolean, val command: String)

/** 서버 없이 도는 규칙들(`PortalRulesTest`). */
object PortalRules {

    /** 한글·영문·숫자·`_` `-` 32자까지 — 점은 YAML 경로가 되므로 안 된다(지뢰 1). */
    val NAME = Regex("^[A-Za-z0-9_\\-가-힣]{1,32}$")

    /** 포탈마다의 사용 권한(선언 안 된 노드 — op 는 저절로 가진다). CMI `cmi.command.portal.<이름>`. */
    const val NODE_PREFIX = "inmcessentials" + ".portals.portal."

    fun keyOf(name: String): String = name.trim().lowercase()

    fun node(portal: Portal): String = NODE_PREFIX + portal.key

    fun chunkKey(cx: Int, cz: Int): Long = (cx.toLong() shl 32) or (cz.toLong() and 0xffffffffL)

    /** 월드 경계(바닐라 ±30,000,000)와 높이 — 이것을 넘는 좌표는 손으로 잘못 고친 것이다. */
    const val MAX_COORD = 30_000_000
    const val MIN_Y = -4096
    const val MAX_Y = 4096

    /** 한 포탈이 걸칠 수 있는 청크 수의 상한(256×256 청크) — 색인이 메인에서 지어지므로. */
    const val MAX_CHUNKS = 65_536L

    enum class BoxCheck { OK, TOO_BIG, OUT_OF_BOUNDS }

    /** 상자를 받아도 되나 — 좌표·청크 수(늘) · 블록 수([maxVolume]). 읽기·CMI 가져오기·만들기·다시 정하기가 같이 쓴다. */
    fun check(box: PortalBox, maxVolume: Long): BoxCheck {
        val coords = listOf(box.minX, box.maxX, box.minZ, box.maxZ)
        if (coords.any { it < -MAX_COORD || it > MAX_COORD } || box.minY < MIN_Y || box.maxY > MAX_Y || box.chunkCount > MAX_CHUNKS) {
            return BoxCheck.OUT_OF_BOUNDS
        }
        return if (box.volume > maxVolume) BoxCheck.TOO_BIG else BoxCheck.OK
    }

    /**
     * [from]→[to] 이 지나는 블록(시작 블록은 빼고, 끝 블록까지) — 3차원 DDA. 한 걸음에 1블록 넘게 가도(겉날개·급류·지연) 얇은 포탈을
     * 건너뛰지 않게. [maxSteps] 를 넘으면 거기서 끊고 끝 블록을 붙인다.
     */
    fun blocksAlong(fx: Double, fy: Double, fz: Double, tx: Double, ty: Double, tz: Double, maxSteps: Int = 64): List<Triple<Int, Int, Int>> {
        var x = kotlin.math.floor(fx).toInt()
        var y = kotlin.math.floor(fy).toInt()
        var z = kotlin.math.floor(fz).toInt()
        val ex = kotlin.math.floor(tx).toInt()
        val ey = kotlin.math.floor(ty).toInt()
        val ez = kotlin.math.floor(tz).toInt()
        val out = ArrayList<Triple<Int, Int, Int>>(4)
        if (x == ex && y == ey && z == ez) return out
        val dx = tx - fx
        val dy = ty - fy
        val dz = tz - fz
        val sx = if (dx > 0) 1 else if (dx < 0) -1 else 0
        val sy = if (dy > 0) 1 else if (dy < 0) -1 else 0
        val sz = if (dz > 0) 1 else if (dz < 0) -1 else 0
        fun next(p: Double, cell: Int, s: Int, d: Double): Double =
            if (s == 0) Double.POSITIVE_INFINITY else ((if (s > 0) cell + 1.0 else cell.toDouble()) - p) / d
        fun delta(s: Int, d: Double): Double = if (s == 0) Double.POSITIVE_INFINITY else s / d
        var tMaxX = next(fx, x, sx, dx)
        var tMaxY = next(fy, y, sy, dy)
        var tMaxZ = next(fz, z, sz, dz)
        val tDeltaX = delta(sx, dx)
        val tDeltaY = delta(sy, dy)
        val tDeltaZ = delta(sz, dz)
        while (out.size < maxSteps) {
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                x += sx; tMaxX += tDeltaX
            } else if (tMaxY <= tMaxZ) {
                y += sy; tMaxY += tDeltaY
            } else {
                z += sz; tMaxZ += tDeltaZ
            }
            out += Triple(x, y, z)
            if (x == ex && y == ey && z == ez) return out
            // 부동소수 오차로 끝을 지나치면 끝 블록을 붙이고 멈춘다.
            if ((sx != 0 && (x - ex) * sx > 0) || (sy != 0 && (y - ey) * sy > 0) || (sz != 0 && (z - ez) * sz > 0)) break
        }
        if (out.lastOrNull() != Triple(ex, ey, ez)) out += Triple(ex, ey, ez)
        return out
    }

    /**
     * CMI `CMIEffect.serialize()`(`이름;a{N};s{F}` — 이름은 빈칸을 뺀 CMILib 이름) · 옛 Bukkit 이름 · 지금 이름 → [known](지금 쓸 수 있는 Particle
     * 이름들) 중 하나. 못 찾으면 null. 대소문자·`_`·빈칸은 가리지 않는다.
     */
    fun particleName(raw: String?, known: Collection<String>): String? {
        val base = raw?.substringBefore(';')?.trim()?.removePrefix("minecraft:") ?: return null
        val compact = compact(base)
        if (compact.isEmpty()) return null
        LEGACY_PARTICLES[compact]?.let { if (it in known) return it }
        return known.firstOrNull { compact(it) == compact }
    }

    /** CMI 효과 문자열의 `a{N}`(한 번에 뿌리는 수). 없으면 null. */
    fun particleAmount(raw: String?): Int? =
        raw?.let { Regex("""(?i);\s*a\{(\d+)}""").find(it) }?.groupValues?.get(1)?.toIntOrNull()

    private fun compact(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

    /** CMILib·1.20.4 이전 이름(압축형) → 지금 이름. */
    private val LEGACY_PARTICLES = mapOf(
        "reddust" to "DUST", "coloreddust" to "DUST", "redstone" to "DUST",
        "witchmagic" to "WITCH", "spellwitch" to "WITCH",
        "spell" to "EFFECT", "instantspell" to "INSTANT_EFFECT", "spellinstant" to "INSTANT_EFFECT",
        "happyvillager" to "HAPPY_VILLAGER", "villagerhappy" to "HAPPY_VILLAGER",
        "angryvillager" to "ANGRY_VILLAGER", "villagerangry" to "ANGRY_VILLAGER",
        "fireworksspark" to "FIREWORK", "fireworkspark" to "FIREWORK", "fireworks" to "FIREWORK",
        "largesmoke" to "LARGE_SMOKE", "smokelarge" to "LARGE_SMOKE", "smokenormal" to "SMOKE",
        "hugeexplosion" to "EXPLOSION_EMITTER", "explosionhuge" to "EXPLOSION_EMITTER",
        "largeexplode" to "EXPLOSION", "explosionlarge" to "EXPLOSION", "explode" to "POOF", "explosionnormal" to "POOF",
        "waterbubble" to "BUBBLE", "watersplash" to "SPLASH", "waterwake" to "FISHING", "wake" to "FISHING",
        "suspended" to "UNDERWATER", "depthsuspend" to "MYCELIUM", "suspendeddepth" to "MYCELIUM", "townaura" to "MYCELIUM",
        "magiccrit" to "ENCHANTED_HIT", "critmagic" to "ENCHANTED_HIT",
        "enchantmenttable" to "ENCHANT", "enchantingtable" to "ENCHANT",
        "dripwater" to "DRIPPING_WATER", "driplava" to "DRIPPING_LAVA",
        "snowshovel" to "ITEM_SNOWBALL", "snowballpoof" to "ITEM_SNOWBALL", "slime" to "ITEM_SLIME",
        "totem" to "TOTEM_OF_UNDYING", "endrod" to "END_ROD", "dragonbreath" to "DRAGON_BREATH",
        "sweepattack" to "SWEEP_ATTACK", "damageindicator" to "DAMAGE_INDICATOR", "flyingglyph" to "ENCHANT",
    )

    /** CMI 의 기본 포탈 명령 `cmi effect [playerName] <효과> <초> <배수> [-s]` — 명령 대신 효과 한 줄(`효과:초:레벨`)로. 아니면 null. */
    fun cmiEffectCommand(raw: String): String? {
        val text = raw.trim().let { t -> CMI_PREFIX.find(t)?.let { t.substring(it.range.last + 1).trim() } ?: t }
        val m = CMI_EFFECT.matchEntire(text) ?: return null
        val effect = parseEffect("${m.groupValues[1]}:${m.groupValues[2]}:${m.groupValues[3]}") ?: return null
        return "${effect.type}:${effect.seconds}:${effect.level}"
    }

    private val CMI_PREFIX = Regex("^(?i)asconsole!")
    private val CMI_EFFECT = Regex("""(?i)/?cmi effect (?:\[playerName]|\{player}) ([a-z_:.]+) (\d+) (\d+)(?: -s)?""")

    /** CMI 명령의 특수 접두(`delay! 5` · `chance! 50` · `asFakeOp!` …) — 우리가 읽는 `asPlayer!`/`asConsole!` 밖의 것. 없으면 null. */
    fun cmiSpecialPrefix(raw: String): String? {
        val first = raw.trim().substringBefore(' ')
        if (!first.endsWith("!") || first.length < 2) return null
        return first.takeUnless { it.equals("asPlayer!", ignoreCase = true) || it.equals("asConsole!", ignoreCase = true) }
    }

    /**
     * CMI 를 빼면 글자 그대로 남는 것 — 우리가 바꾸지 않는 CMI 변수(`[world]`·`[x]` … 소문자로 시작하는 `[낱말]`)와 `%cmi_…%` PAPI.
     * 가져오기 보고서에 알리려고(조용히 깨지지 않게). `[VIP]` 같은 대문자 꼬리표는 건드리지 않는다.
     */
    fun cmiLeftovers(text: String): List<String> =
        (CMI_VARIABLE.findAll(text).map { it.value } + CMI_PAPI.findAll(text).map { it.value }).distinct().toList()

    private val CMI_VARIABLE = Regex("""\[[a-z][A-Za-z0-9]*]""")
    private val CMI_PAPI = Regex("""%cmi_[^%\s]+%""", RegexOption.IGNORE_CASE)

    /** 콘솔이 칠 명령인가(`player:` 가 없다) — 콘솔 명령을 더하고 고치는 것은 [PortalsFeature.CONSOLE] 이 있어야. */
    fun isConsole(raw: String): Boolean = parseCommand(raw)?.asPlayer == false

    /** `player:`·`플레이어:`·CMI `asPlayer!` → 그 사람, `console:`·`콘솔:`·`asConsole!` 또는 접두 없음 → 콘솔. 앞의 `/` 는 뗀다. */
    fun parseCommand(raw: String): PortalCommand? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        val lower = text.lowercase()
        var asPlayer = false
        for ((prefix, player) in PREFIXES) {
            if (lower.startsWith(prefix)) {
                asPlayer = player
                text = text.substring(prefix.length).trim()
                break
            }
        }
        text = text.removePrefix("/").trim()
        return if (text.isEmpty()) null else PortalCommand(asPlayer, text)
    }

    private val PREFIXES = listOf(
        "player:" to true, "플레이어:" to true, "asplayer!" to true,
        "console:" to false, "콘솔:" to false, "asconsole!" to false,
    )

    /** `blindness:2:1` · `blindness 2 1` · `blindness`(1초 1레벨). 못 읽으면 null. */
    fun parseEffect(raw: String): EffectSpec? {
        val parts = raw.trim().removePrefix("minecraft:").split(':', ' ').filter { it.isNotBlank() }
        val type = parts.firstOrNull()?.lowercase() ?: return null
        if (!type.matches(EFFECT_NAME)) return null
        val seconds = parts.getOrNull(1)?.let { it.toIntOrNull() ?: return null } ?: 1
        val level = parts.getOrNull(2)?.let { it.toIntOrNull() ?: return null } ?: 1
        if (seconds !in 1..86_400 || level !in 1..255) return null
        return EffectSpec(type, seconds, level)
    }

    private val EFFECT_NAME = Regex("[a-z0-9_.\\-]+")

    /** 이번에 쓸 수 있는가 — 남은 초(0 이면 지금 된다). */
    fun cooldownLeft(lastUsed: Long?, cooldownSeconds: Int, now: Long): Long {
        if (lastUsed == null || cooldownSeconds <= 0) return 0
        val left = lastUsed + cooldownSeconds * 1000L - now
        return if (left <= 0) 0 else (left + 999) / 1000
    }

    /** 밀어낼 방향(수평, 길이 1) — 들어서려던 방향의 반대. 제자리면 포탈 가운데에서 바깥쪽. */
    fun pushBack(fromX: Double, fromZ: Double, toX: Double, toZ: Double, box: PortalBox): Pair<Double, Double> {
        var dx = fromX - toX
        var dz = fromZ - toZ
        if (dx * dx + dz * dz < 1e-6) {
            dx = toX - box.centerX
            dz = toZ - box.centerZ
        }
        val len = kotlin.math.sqrt(dx * dx + dz * dz)
        return if (len < 1e-6) 0.0 to 0.0 else (dx / len) to (dz / len)
    }

    /** 파티클 한 점 — r1~r3 은 0..1 난수. 줄임 %([shrink])만큼 면을 안쪽으로. */
    fun particlePoint(box: PortalBox, origin: ParticleOrigin, shrink: Int, r1: Double, r2: Double, r3: Double): Triple<Double, Double, Double> {
        val s = shrink.coerceIn(0, 90) / 100.0
        val w = (box.maxX + 1 - box.minX).toDouble()
        val h = (box.maxY + 1 - box.minY).toDouble()
        val d = (box.maxZ + 1 - box.minZ).toDouble()
        val x = box.minX + w * s / 2 + r1 * w * (1 - s)
        val z = box.minZ + d * s / 2 + r3 * d * (1 - s)
        val y = when (origin) {
            ParticleOrigin.ANY -> box.minY + h * s / 2 + r2 * h * (1 - s)
            ParticleOrigin.TOP -> box.maxY + 1.0
            ParticleOrigin.BOTTOM -> box.minY + 0.1
            ParticleOrigin.CENTER -> box.centerY
        }
        return Triple(x, y, z)
    }

    /** `#RRGGBB` · `RRGGBB` · 10진 정수 → 0xRRGGBB. 비우면 null. */
    fun parseColor(raw: String?): Int? {
        val text = raw?.trim()?.removePrefix("#") ?: return null
        if (text.isEmpty()) return null
        if (text.length == 6) text.toIntOrNull(16)?.let { return it }
        return text.toIntOrNull()?.takeIf { it in 0..0xFFFFFF }
    }
}
