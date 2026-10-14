package com.inmc.essentials.decor

import kr.inmc.core.util.Text
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

/**
 * 플레이어가 친 글 → 표지판·이름의 글(순수 — `DecorLogicTest`).
 *
 * 색 권한(`inmcessentials.decor.color`)이면 `&a`·`&#rrggbb`·MiniMessage 의 **색·꾸밈만**(색·굵게·기울임·그라데이션·무지개) 읽는다 —
 * `<click>`·`<hover>`·`<insert>` 는 그대로 글자로 남는다. 표지판의 클릭 사건은 **누른 사람의 권한으로 명령을 실행**하므로 아무에게나 열면
 * 안 된다. 그것까지 쓰는 것은 서식 권한(`inmcessentials.decor.format`, 기본 op)뿐이다.
 */
object DecorText {

    private val SAFE: MiniMessage = MiniMessage.builder()
        .tags(TagResolver.resolver(StandardTags.color(), StandardTags.decorations(), StandardTags.gradient(), StandardTags.rainbow(), StandardTags.reset()))
        .build()

    private val FULL: MiniMessage = MiniMessage.miniMessage()

    private val PLAIN = PlainTextComponentSerializer.plainText()

    enum class Level { PLAIN, COLOR, FORMAT }

    /** 친 글 하나 → 글 조각. */
    fun parse(raw: String, level: Level): Component = when (level) {
        Level.PLAIN -> Component.text(raw)
        Level.COLOR -> SAFE.deserialize(Text.legacyToMiniMessage(raw))
        Level.FORMAT -> FULL.deserialize(Text.legacyToMiniMessage(raw))
    }

    /** 색·서식 표시가 들어 있나 — 없으면 손대지 않는다(상점 표지판 `[Shop]` 같은 줄을 바꾸지 않게). */
    fun hasMarkup(raw: String): Boolean = raw.contains('&') || raw.contains('<') || raw.contains('§')

    fun plain(component: Component): String = PLAIN.serialize(component)

    /** 클릭 사건이 들어 있나(자식까지) — 바닐라는 이런 면을 우클릭하면 명령을 실행하고 편집 화면을 열지 않는다. */
    fun hasClick(component: Component): Boolean = component.clickEvent() != null || component.children().any { hasClick(it) }

    /** 클릭 사건을 모두 벗긴다(자식까지). 색·꾸밈·마우스 올리기는 그대로. */
    fun stripClicks(component: Component): Component =
        component.clickEvent(null).children(component.children().map { stripClicks(it) })

    /**
     * 관리자가 쓴 원문 [raw] 에 `<click` 이 없는데 렌더 결과에 클릭 사건이 있으면 — 플레이스홀더 값이 넣은 것 — 벗긴다(동적 표지판 공용 모드).
     * 원문이 직접 `<click…>` 을 쓴 줄은 그대로(`/동적표지판` 은 관리자 권한).
     */
    fun withoutInjectedClicks(raw: String, rendered: Component): Component =
        if (raw.contains("<click", ignoreCase = true) || !hasClick(rendered)) rendered else stripClicks(rendered)

    /** 글 조각 → 다시 고칠 수 있는 MiniMessage(표지판을 입력창으로 열 때 · 동적 표지판으로 바꿀 때). */
    fun toMini(component: Component): String = FULL.serialize(component)

    /**
     * `/표지판편집 <줄> <글>` — `\n` 으로 다음 줄들까지. [line] 은 1..4. 돌려주는 것은 (줄 번호 0..3, 글) — 4줄을 넘는 것은 버린다.
     * 글이 비면 그 줄을 지운다.
     */
    fun signLines(line: Int, text: String): List<Pair<Int, String>> {
        val parts = text.split("\\n")
        return parts.mapIndexedNotNull { i, part -> (line - 1 + i).takeIf { it in 0..3 }?.let { it to part } }
    }
}

/**
 * 동적 표지판의 한 장면(순수). 줄이 넷 넘으면 한 칸씩 위로 굴리고(CMI: 4줄 넘으면 자동 스크롤), `{스크롤}` 로 시작하는 줄은 글자가 옆으로 흐른다.
 */
object SignFrames {

    val MARQUEE_MARKERS = listOf("{스크롤}", "{scroll}")

    /** [frame] 번째 장면의 네 줄(원문). 넷 이하이면 그대로(모자라면 빈 줄). */
    fun window(lines: List<String>, frame: Long): List<String> {
        if (lines.size <= 4) return lines + List(4 - lines.size) { "" }
        val start = Math.floorMod(frame, lines.size.toLong()).toInt()
        return List(4) { lines[(start + it) % lines.size] }
    }

    fun isMarquee(line: String): Boolean = MARQUEE_MARKERS.any { line.startsWith(it) }

    fun stripMarker(line: String): String = MARQUEE_MARKERS.fold(line) { acc, m -> acc.removePrefix(m) }

    /** 흐르는 글 — [plain] 이 [width] 보다 길면 빈칸 셋을 끼워 돌린 창. */
    fun marquee(plain: String, width: Int, frame: Long): String {
        if (plain.length <= width) return plain
        val loop = "$plain   "
        val start = Math.floorMod(frame, loop.length.toLong()).toInt()
        val sb = StringBuilder(width)
        for (i in 0 until width) sb.append(loop[(start + i) % loop.length])
        return sb.toString()
    }

    /** 줄 앞의 MiniMessage 여는 태그들(`<gold><bold>`) — 흐르는 글에 같은 색을 다시 입힌다. */
    fun leadingTags(line: String): String = Regex("""^(<[^<>/]+>)*""").find(line)?.value.orEmpty()
}

/** 갑옷 거치대 자세 계산(순수 — 도 단위). */
object Pose {

    /** -180..180 로 감는다. */
    fun wrap(degrees: Double): Double {
        var d = degrees % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return Math.round(d * 1000.0) / 1000.0
    }

    /** 왼클릭 +, 오른클릭 -, 웅크림 ×10(CMI). */
    fun delta(step: Double, right: Boolean, shift: Boolean): Double = step * (if (right) -1 else 1) * (if (shift) 10 else 1)

    /** 크기 — [min]..[max] 로 자르고 0.01 단위로. */
    fun scale(value: Double, min: Double, max: Double): Double = Math.round(value.coerceIn(min, max) * 100.0) / 100.0

    /** 시작 자리에서 [maxMove] 블록을 넘지 않게 — 넘으면 null(못 옮김). */
    fun withinMove(dx: Double, dy: Double, dz: Double, maxMove: Double): Boolean = dx * dx + dy * dy + dz * dz <= maxMove * maxMove + 1e-9
}
