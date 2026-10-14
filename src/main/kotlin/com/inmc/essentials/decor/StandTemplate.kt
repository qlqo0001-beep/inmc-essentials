package com.inmc.essentials.decor

import org.bukkit.configuration.ConfigurationSection

/**
 * 갑옷 거치대의 모양 하나(자세·켜고 끄기·크기) — 복사/붙여넣기와 템플릿이 같이 쓴다. **장비는 담지 않는다**: 템플릿 아이템이나 붙여넣기로
 * 아이템이 생기면 복사 길이 된다(CMI 는 장비도 저장했다 — 다른 점). 각도는 도.
 */
data class StandTemplate(
    val head: Angles = Angles.ZERO,
    val body: Angles = Angles.ZERO,
    val leftArm: Angles = Angles(-10.0, 0.0, -10.0),
    val rightArm: Angles = Angles(-15.0, 0.0, 10.0),
    val leftLeg: Angles = Angles(-1.0, 0.0, -1.0),
    val rightLeg: Angles = Angles(1.0, 0.0, 1.0),
    val basePlate: Boolean = true,
    val small: Boolean = false,
    val visible: Boolean = true,
    val arms: Boolean = false,
    val gravity: Boolean = true,
    val glowing: Boolean = false,
    val invulnerable: Boolean = false,
    val nameVisible: Boolean = false,
    val locked: Boolean = false,
    val scale: Double = 1.0,
) {

    data class Angles(val x: Double, val y: Double, val z: Double) {
        fun toList(): List<Double> = listOf(x, y, z)

        companion object {
            val ZERO = Angles(0.0, 0.0, 0.0)

            fun of(list: List<*>?): Angles? {
                val nums = list?.mapNotNull { (it as? Number)?.toDouble() ?: it?.toString()?.toDoubleOrNull() } ?: return null
                return if (nums.size == 3 && nums.all { it.isFinite() }) Angles(nums[0], nums[1], nums[2]) else null
            }
        }
    }

    fun write(s: ConfigurationSection) {
        s.set("head", head.toList())
        s.set("body", body.toList())
        s.set("left-arm", leftArm.toList())
        s.set("right-arm", rightArm.toList())
        s.set("left-leg", leftLeg.toList())
        s.set("right-leg", rightLeg.toList())
        s.set("base-plate", basePlate)
        s.set("small", small)
        s.set("visible", visible)
        s.set("arms", arms)
        s.set("gravity", gravity)
        s.set("glowing", glowing)
        s.set("invulnerable", invulnerable)
        s.set("name-visible", nameVisible)
        s.set("locked", locked)
        s.set("scale", scale)
    }

    companion object {

        /** 못 읽은 칸은 기본값(던지지 않는다). */
        fun read(s: ConfigurationSection): StandTemplate {
            val d = StandTemplate()
            return StandTemplate(
                head = Angles.of(s.getList("head")) ?: d.head,
                body = Angles.of(s.getList("body")) ?: d.body,
                leftArm = Angles.of(s.getList("left-arm")) ?: d.leftArm,
                rightArm = Angles.of(s.getList("right-arm")) ?: d.rightArm,
                leftLeg = Angles.of(s.getList("left-leg")) ?: d.leftLeg,
                rightLeg = Angles.of(s.getList("right-leg")) ?: d.rightLeg,
                basePlate = s.getBoolean("base-plate", d.basePlate),
                small = s.getBoolean("small", d.small),
                visible = s.getBoolean("visible", d.visible),
                arms = s.getBoolean("arms", d.arms),
                gravity = s.getBoolean("gravity", d.gravity),
                glowing = s.getBoolean("glowing", d.glowing),
                invulnerable = s.getBoolean("invulnerable", d.invulnerable),
                nameVisible = s.getBoolean("name-visible", d.nameVisible),
                locked = s.getBoolean("locked", d.locked),
                scale = s.getDouble("scale", d.scale).takeIf { it in 0.0625..16.0 } ?: d.scale,
            )
        }

        /** 템플릿 이름 — `DefinitionKey` 와 같은 규칙(YAML 열쇠에 점이 들어가면 사라진다 — 지뢰 1). */
        val NAME = Regex("^[a-z0-9_가-힣-]{1,32}$")
    }
}
