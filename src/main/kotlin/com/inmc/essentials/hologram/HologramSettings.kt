package com.inmc.essentials.hologram

import org.bukkit.configuration.file.YamlConfiguration

/**
 * `settings/holograms.yml` 의 불변 스냅샷(리로드 때 통째로 바뀐다). 값이 이상하면 기본값(던지지 않는다). **서버 없이 읽힌다.**
 * 괄호 안은 옮겨 온 CMI 설정 키(config.yml `Holograms.Defaults`).
 */
data class HologramSettings(
    /** 홀로그램마다 정하지 않았을 때의 보이는 거리(viewRange 16). */
    val viewRange: Int = 16,
    /**
     * 글 다시 그리기 주기(초) 기본값(Interval.Update — CMI 는 틱, 우리는 1초 단위). 0 = 안 함 — 그러면 플레이스홀더 줄이 세울 때의 값에 멈춘다.
     * 싸다: 플레이스홀더가 있는 줄만, 갱신 거리 안의 묶음만, 글이 바뀐 때만 보낸다.
     */
    val updateSeconds: Int = 2,
    /** 자동 쪽 넘김 기본값(초)(pageChangeInterval — CMI 는 틱). 0 = 안 함. */
    val pageSeconds: Int = 0,
    /** 글 한 줄의 높이(크기 1 기준, 블록). */
    val lineHeight: Double = 0.28,
    /** 아이콘 한 줄의 높이(크기 1 기준, 블록). */
    val iconHeight: Double = 0.55,
    /** 사람마다 다른 글(플레이스홀더)을 보여 줄 사람 수의 상한 — 홀로그램 하나에. 넘으면 나머지는 공용 글(플레이스홀더를 사람 없이 푼 것). */
    val maxPersonalViewers: Int = 30,
    /** 이것으로 시작하는 PAPI 는 사람과 무관한 것으로 본다 — 공용 엔티티 하나로 충분(`%server_online%` 같은 것). */
    val globalPlaceholders: List<String> = listOf("server_", "math_", "time_"),
    /** 같은 사람의 클릭 사이 최소 간격(밀리초) — 한 번 누름이 두 번 들어오는 것 막기. */
    val clickCooldownMillis: Long = 250,
    /** 새 홀로그램을 선 사람 눈높이에서 얼마나 위에(블록). */
    val createHeight: Double = 0.6,
    /** 이 거리(블록) 안에 보는 사람이 있는 묶음만 글을 다시 그린다(CMI Defaults.updateRange 8). 0 = 보이는 거리 전체. */
    val updateRange: Int = 8,
    /** 상한을 넘어 공용 글을 보는 사람에게 `{player}`·사람 PAPI 자리에 보일 글(비우면 지운다). */
    val personalFallback: String = "",
) {

    companion object {

        fun from(y: YamlConfiguration): HologramSettings {
            val d = HologramSettings()
            return HologramSettings(
                viewRange = y.getInt("view-range", d.viewRange).coerceIn(1, 256),
                updateSeconds = y.getInt("update-seconds", d.updateSeconds).coerceIn(0, 86_400),
                pageSeconds = y.getInt("page-seconds", d.pageSeconds).coerceIn(0, 86_400),
                lineHeight = y.getDouble("line-height", d.lineHeight).takeIf { it.isFinite() && it > 0 }?.coerceAtMost(5.0) ?: d.lineHeight,
                iconHeight = y.getDouble("icon-height", d.iconHeight).takeIf { it.isFinite() && it > 0 }?.coerceAtMost(5.0) ?: d.iconHeight,
                maxPersonalViewers = y.getInt("max-personal-viewers", d.maxPersonalViewers).coerceIn(0, 500),
                globalPlaceholders = if (y.isList("global-placeholders")) y.getStringList("global-placeholders").map { it.trim().lowercase() }.filter { it.isNotEmpty() } else d.globalPlaceholders,
                clickCooldownMillis = y.getLong("click-cooldown-ms", d.clickCooldownMillis).coerceIn(0, 10_000),
                createHeight = y.getDouble("create-height", d.createHeight).takeIf { it.isFinite() }?.coerceIn(-5.0, 10.0) ?: d.createHeight,
                updateRange = y.getInt("update-range", d.updateRange).coerceIn(0, 256),
                personalFallback = y.getString("personal-fallback") ?: d.personalFallback,
            )
        }
    }
}
