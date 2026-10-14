package com.inmc.essentials.decor

import org.bukkit.configuration.file.YamlConfiguration

/**
 * `settings/decor.yml` 의 불변 스냅샷(리로드 때 통째로 바뀐다). 값이 이상하면 기본값(던지지 않는다). **서버 없이 읽힌다** — `DecorLogicTest`.
 * 괄호 안은 옮겨 온 CMI 설정 키.
 */
data class DecorSettings(
    val armorStand: ArmorStand = ArmorStand(),
    val painting: Painting = Painting(),
    val sign: Sign = Sign(),
    val dynamic: Dynamic = Dynamic(),
    val itemFrame: ItemFrame = ItemFrame(),
) {

    data class ArmorStand(
        /** 웅크리고 우클릭하면 편집기(armorstand 모듈). */
        val shiftClick: Boolean = true,
        val rotateStep: Double = 5.0,
        val moveStep: Double = 0.05,
        /** 한 번 열어 옮길 수 있는 거리(블록) — 조금씩 밀어 남의 땅으로 넣지 못하게. 우회: `…armorstand.move.unlimited`. */
        val maxMove: Double = 2.0,
        val minScale: Double = 0.5,
        val maxScale: Double = 3.0,
        /** true 면 템플릿마다 `inmcessentials.decor.armorstand.template.use.<이름>` 이 있어야 꺼낸다(Templates.SpecificPermission). */
        val templatePermission: Boolean = false,
        /** `/갑옷거치대 가까이` 반경. */
        val nearRadius: Int = 16,
    )

    data class Painting(val enabled: Boolean = true, val scrollSeconds: Int = 5)

    data class Sign(
        val enabled: Boolean = true,
        /**
         * 웅크려야 편집(RequireSneaking — CMI 기본은 false, 우리는 웅크림을 기본으로). 바닐라가 맨손 우클릭으로 스스로 여는 화면에도 듣는다
         * (`SignEdit.onOpen`). 밀랍칠한 것은 늘 웅크림.
         */
        val requireSneaking: Boolean = true,
        /** 바닐라 표지판 화면 대신 입력창(Dialog) — 색·긴 글을 치기 쉽다(Signs.DialogEditor). */
        val dialog: Boolean = false,
        /** 첫 줄이 이것이면 편집·복사 금지(Signs.editBlackList + Optimizations.SignEdit.BlackList), 대소문자 무시. */
        val blacklist: List<String> = listOf("[Shop]", "[Private]", "[More Users]", "[Everyone]", "[AdminShop]", "[ChestShop]", "[CMIElevator]"),
    ) {
        fun blocked(firstLine: String): Boolean = firstLine.trim().let { line -> line.isNotEmpty() && blacklist.any { it.equals(line, ignoreCase = true) } }
    }

    data class Dynamic(
        val defaultInterval: Int = 5,
        val defaultRange: Int = 16,
        val maxSigns: Int = 200,
        /** `{스크롤}` 줄이 한 번에 보여 주는 글자 수. */
        val marqueeWidth: Int = 15,
    )

    data class ItemFrame(val radius: Int = 8, val maxRadius: Int = 32)

    companion object {

        fun from(y: YamlConfiguration): DecorSettings {
            val d = DecorSettings()
            val a = y.getConfigurationSection("armorstand")
            val minScale = a?.getDouble("min-scale", d.armorStand.minScale)?.takeIf { it in 0.0625..16.0 } ?: d.armorStand.minScale
            val maxScale = (a?.getDouble("max-scale", d.armorStand.maxScale)?.takeIf { it in 0.0625..16.0 } ?: d.armorStand.maxScale).coerceAtLeast(minScale)
            val armorStand = if (a == null) d.armorStand else DecorSettings.ArmorStand(
                shiftClick = a.getBoolean("shift-click", d.armorStand.shiftClick),
                rotateStep = a.getDouble("rotate-step", d.armorStand.rotateStep).takeIf { it > 0 && it <= 90 } ?: d.armorStand.rotateStep,
                moveStep = a.getDouble("move-step", d.armorStand.moveStep).takeIf { it > 0 && it <= 1 } ?: d.armorStand.moveStep,
                maxMove = a.getDouble("max-move", d.armorStand.maxMove).takeIf { it >= 0 } ?: d.armorStand.maxMove,
                minScale = minScale,
                maxScale = maxScale,
                templatePermission = a.getBoolean("template-permission", d.armorStand.templatePermission),
                nearRadius = a.getInt("near-radius", d.armorStand.nearRadius).takeIf { it in 1..128 } ?: d.armorStand.nearRadius,
            )
            val p = y.getConfigurationSection("painting")
            val painting = if (p == null) d.painting else Painting(
                p.getBoolean("enabled", d.painting.enabled),
                p.getInt("scroll-seconds", d.painting.scrollSeconds).takeIf { it in 0..60 } ?: d.painting.scrollSeconds,
            )
            val s = y.getConfigurationSection("sign-edit")
            val sign = if (s == null) d.sign else Sign(
                enabled = s.getBoolean("enabled", d.sign.enabled),
                requireSneaking = s.getBoolean("require-sneaking", d.sign.requireSneaking),
                dialog = s.getBoolean("dialog", d.sign.dialog),
                blacklist = if (s.isList("blacklist")) s.getStringList("blacklist").map { it.trim() }.filter { it.isNotEmpty() } else d.sign.blacklist,
            )
            val ds = y.getConfigurationSection("dynamic-sign")
            val dynamic = if (ds == null) d.dynamic else Dynamic(
                defaultInterval = ds.getInt("default-interval", d.dynamic.defaultInterval).takeIf { it in 1..3600 } ?: d.dynamic.defaultInterval,
                defaultRange = ds.getInt("default-range", d.dynamic.defaultRange).takeIf { it in 1..256 } ?: d.dynamic.defaultRange,
                maxSigns = ds.getInt("max-signs", d.dynamic.maxSigns).takeIf { it in 1..10_000 } ?: d.dynamic.maxSigns,
                marqueeWidth = ds.getInt("marquee-width", d.dynamic.marqueeWidth).takeIf { it in 4..90 } ?: d.dynamic.marqueeWidth,
            )
            val f = y.getConfigurationSection("item-frame")
            val itemFrame = if (f == null) d.itemFrame else {
                val max = f.getInt("max-radius", d.itemFrame.maxRadius).takeIf { it in 1..256 } ?: d.itemFrame.maxRadius
                ItemFrame((f.getInt("radius", d.itemFrame.radius).takeIf { it > 0 } ?: d.itemFrame.radius).coerceAtMost(max), max)
            }
            return DecorSettings(armorStand, painting, sign, dynamic, itemFrame)
        }
    }
}
