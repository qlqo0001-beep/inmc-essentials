package com.inmc.essentials.hologram

/**
 * 홀로그램 한 개(2026-10-10) — 카탈로그 web:wiki-holograms. 위치는 **첫 줄의 윗자리**이고 줄은 아래로 쌓인다.
 * 불변 값이다 — 고치면 새 값으로 바꿔 끼우고 저장소가 통째로 원자적으로 쓴다. **서버 없이 만들어진다**(시험).
 *
 * 쪽([pages])은 사람마다 따로 넘긴다(CMI 와 같다). 자동 넘김([pageSeconds])은 모두의 기준 쪽을 넘기고, 누르거나 `/홀로그램 넘기기` 로
 * 넘긴 사람은 그 기준에서 떨어진 만큼(offset)을 갖는다 — 아무도 안 누르면 모두 같은 엔티티를 본다.
 */
data class Hologram(
    val name: String,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val pages: List<HoloPage> = listOf(HoloPage()),
    val enabled: Boolean = true,
    /** 보이는 거리(블록). null = 설정의 기본값(CMI viewRange). */
    val viewRange: Int? = null,
    /** 글을 다시 그리는 주기(초). null = 기본, 0 = 안 함(CMI Interval.Update). 플레이스홀더가 있는 줄만, 바뀐 때만 보낸다. */
    val updateSeconds: Int? = null,
    /** 이 거리(블록) 안에 보는 사람이 있을 때만 글을 다시 그린다. null = 기본, 0 = 보이는 거리 전체(CMI updateRange). */
    val updateRange: Int? = null,
    /** 자동으로 쪽을 넘기는 주기(초). null = 기본, 0 = 안 함(CMI pageChangeInterval). */
    val pageSeconds: Int? = null,
    /** 이 권한이 있어야 보인다(비우면 누구나). CMI "Requires permission to see". */
    val permission: String? = null,
    /** `%플레이스홀더% 연산 값`(연산 == != >= <= > < 포함 안포함) — 맞는 사람에게만 보인다. 못 읽으면 아무에게도 안 보인다. */
    val condition: String? = null,
    /** CENTER · VERTICAL · HORIZONTAL · FIXED(= [yaw] 방향 고정). */
    val billboard: String = "CENTER",
    val yaw: Float = 0f,
    val scale: Double = 1.0,
    /** `default`(반투명 검정) · `none`(없음) · `#AARRGGBB` · `#RRGGBB`. */
    val background: String = "default",
    val shadow: Boolean = true,
    val seeThrough: Boolean = false,
    /** 누를 동작이 없는 줄을 누르면 쪽 넘기기(우클릭 다음, 좌클릭 이전) — CMI 의 [prevPage]/[nextPage] 버튼 대신. */
    val clickPages: Boolean = true,
    /** 누르는 상자의 너비(블록). 0 = 글 길이로 짐작. */
    val clickWidth: Double = 0.0,
    /** 글 정렬 `CENTER` · `LEFT` · `RIGHT`(CMI Text.Align) — 여러 줄로 감길 때 보인다. */
    val alignment: String = "CENTER",
    /** 글이 이 너비(픽셀 쯤)를 넘으면 다음 줄로 감는다(CMI Text.Line — 기본 1000). */
    val lineWidth: Int = DEFAULT_LINE_WIDTH,
    /** 글자 불투명도 0~255(CMI Text.TAlpha — 기본 255 = 불투명). 마인크래프트는 아주 낮은 값(≈ 26 아래)을 불투명으로 그린다. */
    val textOpacity: Int = 255,
) {
    companion object {
        const val DEFAULT_LINE_WIDTH = 1000
        val ALIGNMENTS = setOf("CENTER", "LEFT", "RIGHT")
    }

    val key: String get() = HoloRules.keyOf(name)

    val lineCount: Int get() = pages.sumOf { it.lines.size }
}

data class HoloPage(val lines: List<HoloLine> = emptyList(), val actions: HoloActions = HoloActions.NONE)

/**
 * 한 줄 — 글(MiniMessage · `&` 색 · PAPI · `{player}`) 또는 아이콘([icon] = core `ItemRef` 문자열, [snapshot] = 아이템 바이트 base64).
 */
data class HoloLine(
    val text: String = "",
    val icon: String? = null,
    val material: String? = null,
    val snapshot: String? = null,
    val actions: HoloActions = HoloActions.NONE,
) {
    val isIcon: Boolean get() = icon != null || snapshot != null
}

/** 누르는 종류. 디스크에는 [id] 로(지뢰 5). */
enum class HoloClick(val id: String, val label: String) {
    LEFT("left", "좌클릭"),
    RIGHT("right", "우클릭"),
    SHIFT_LEFT("shift-left", "Shift+좌클릭"),
    SHIFT_RIGHT("shift-right", "Shift+우클릭"),
    ANY("any", "아무 클릭");

    companion object {
        fun parse(raw: String?): HoloClick? {
            val text = raw?.trim()?.lowercase()?.replace('_', '-') ?: return null
            return entries.firstOrNull { it.id == text || it.name.equals(text.replace('-', '_'), ignoreCase = true) }
                ?: when (text) {
                    "좌", "좌클릭", "왼쪽" -> LEFT
                    "우", "우클릭", "오른쪽" -> RIGHT
                    "shift좌", "쉬프트좌" -> SHIFT_LEFT
                    "shift우", "쉬프트우" -> SHIFT_RIGHT
                    "아무", "전부", "all" -> ANY
                    else -> null
                }
        }
    }
}

/** 누르면 돌 명령들. `player:` 로 시작하면 그 사람이, 아니면 콘솔이. `[nextPage]`·`[prevPage]`(`[다음쪽]`·`[이전쪽]`)는 쪽 넘기기. */
data class HoloActions(val map: Map<HoloClick, List<String>> = emptyMap()) {

    val isEmpty: Boolean get() = map.values.all { it.isEmpty() }

    /** 정확히 그 종류 → Shift 없는 같은 쪽 → 아무 클릭. */
    fun resolve(click: HoloClick): List<String> {
        map[click]?.takeIf { it.isNotEmpty() }?.let { return it }
        val plain = when (click) {
            HoloClick.SHIFT_LEFT -> HoloClick.LEFT
            HoloClick.SHIFT_RIGHT -> HoloClick.RIGHT
            else -> null
        }
        plain?.let { map[it] }?.takeIf { it.isNotEmpty() }?.let { return it }
        return map[HoloClick.ANY].orEmpty()
    }

    fun with(click: HoloClick, commands: List<String>): HoloActions {
        val next = LinkedHashMap(map)
        if (commands.isEmpty()) next.remove(click) else next[click] = commands
        return HoloActions(next)
    }

    companion object {
        val NONE = HoloActions()
    }
}
