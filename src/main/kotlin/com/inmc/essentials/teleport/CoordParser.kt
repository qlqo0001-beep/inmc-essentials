package com.inmc.essentials.teleport

/**
 * 좌표 글 읽기(순수) — `/좌표이동` · `/강제좌표이동` · `/월드전원소환` 의 위치.
 *
 * - `x y z [월드] [pitch yaw]`(CMI 순서 — [angles]) — 각 좌표는 숫자 또는 `~`·`~5`·`~-2`(지금 자리에서). 소수점 없는 x·z 는 바닐라 `/tp` 처럼 블록 가운데(+0.5).
 * - CMI 모양 `월드;x;y;z(;yaw;pitch)` 한 낱말도 된다.
 * - 꼬리표: `-p:이름`(남을 보냄) · `-rng:범위`(그 둘레 무작위) · `-s`(조용히). 순서는 상관없다.
 */
object CoordParser {

    /** 좌표 하나. [relative] 면 기준 값에 더한다. */
    data class Coord(val value: Double, val relative: Boolean, val center: Boolean = false) {
        fun resolve(base: Double): Double = if (relative) base + value else value + if (center) 0.5 else 0.0
    }

    data class Parsed(
        val x: Coord,
        val y: Coord,
        val z: Coord,
        val world: String? = null,
        val yaw: Float? = null,
        val pitch: Float? = null,
        val target: String? = null,
        val range: Int? = null,
        val silent: Boolean = false,
    )

    /** 읽기 결과 — 성공이면 [parsed], 아니면 [error] 에 걸린 낱말(메시지에 그대로 보인다). */
    data class Result(val parsed: Parsed?, val error: String?)

    fun parse(text: String): Result {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var target: String? = null
        var range: Int? = null
        var silent = false
        val rest = ArrayList<String>()
        for (token in tokens) {
            val lower = token.lowercase()
            when {
                lower == "-s" -> silent = true
                lower.startsWith("-p:") -> target = token.substring(3).ifEmpty { return Result(null, token) }
                lower.startsWith("-rng:") -> range = token.substring(5).toIntOrNull()?.takeIf { it in 1..100_000 } ?: return Result(null, token)
                else -> rest += token
            }
        }
        // CMI 모양 한 낱말.
        if (rest.size == 1 && rest[0].contains(';')) {
            val cmi = cmiLocation(rest[0]) ?: return Result(null, rest[0])
            return Result(cmi.copy(target = target, range = range, silent = silent), null)
        }
        if (rest.size < 3) return Result(null, rest.joinToString(" ").ifEmpty { text.trim() })
        val x = coord(rest[0], center = true) ?: return Result(null, rest[0])
        val y = coord(rest[1], center = false) ?: return Result(null, rest[1])
        val z = coord(rest[2], center = true) ?: return Result(null, rest[2])
        var world: String? = null
        val angles = ArrayList<Float>()
        for (token in rest.drop(3)) {
            val number = token.toFloatOrNull()
            when {
                number != null && angles.size < 2 -> angles += number
                number == null && world == null -> world = token
                else -> return Result(null, token)
            }
        }
        val (yaw, pitch) = angles(angles)
        return Result(Parsed(x, y, z, world, yaw, pitch, target, range, silent), null)
    }

    /**
     * 빈칸 꼴의 꼬리 각도 → (yaw, pitch). **CMI 순서(`(pitch) (yaw)`, Locale_EN `command.tppos.help.args`)** 가 기본이다. 다만 pitch 는 ±90 을
     * 넘을 수 없으니, 첫 값이 ±90 밖이고 둘째가 안이면 바닐라 `/tp` 순서(yaw pitch)로 본다. 하나만 적으면 ±90 안은 pitch, 밖은 yaw.
     */
    fun angles(values: List<Float>): Pair<Float?, Float?> = when (values.size) {
        0 -> null to null
        1 -> if (kotlin.math.abs(values[0]) > 90f) values[0] to null else null to values[0]
        else -> {
            val (a, b) = values
            if (kotlin.math.abs(a) > 90f && kotlin.math.abs(b) <= 90f) a to b else b to a
        }
    }

    /** `~` · `~3` · `~-1.5` · `12` · `-3.25`. 이상하면 null. 너무 먼 값(3천만 밖)도 null. */
    fun coord(token: String, center: Boolean): Coord? {
        if (token.startsWith("~")) {
            val offset = token.substring(1)
            val value = if (offset.isEmpty()) 0.0 else offset.toDoubleOrNull() ?: return null
            return if (value.isFinite() && kotlin.math.abs(value) <= LIMIT) Coord(value, relative = true) else null
        }
        val value = token.toDoubleOrNull() ?: return null
        if (!value.isFinite() || kotlin.math.abs(value) > LIMIT) return null
        return Coord(value, relative = false, center = center && !token.contains('.'))
    }

    /** `월드;x;y;z(;yaw;pitch)` — 좌표는 절대값만. */
    fun cmiLocation(token: String): Parsed? {
        val parts = token.split(';')
        if (parts.size < 4) return null
        val world = parts[0].trim().ifEmpty { return null }
        val x = parts[1].trim().toDoubleOrNull() ?: return null
        val y = parts[2].trim().toDoubleOrNull() ?: return null
        val z = parts[3].trim().toDoubleOrNull() ?: return null
        if (listOf(x, y, z).any { !it.isFinite() || kotlin.math.abs(it) > LIMIT }) return null
        val yaw = parts.getOrNull(4)?.trim()?.toFloatOrNull()
        val pitch = parts.getOrNull(5)?.trim()?.toFloatOrNull()
        return Parsed(Coord(x, false), Coord(y, false), Coord(z, false), world, yaw, pitch)
    }

    private const val LIMIT = 30_000_000.0
}
