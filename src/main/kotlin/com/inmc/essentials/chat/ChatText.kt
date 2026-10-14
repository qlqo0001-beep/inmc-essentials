package com.inmc.essentials.chat

import com.inmc.essentials.util.Ph
import kr.inmc.core.util.Text
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags
import org.bukkit.entity.Player

/**
 * 사람이 친 글을 메시지 틀에 끼우는 곳.
 *
 * **사람의 글은 틀과 함께 렌더링하지 않는다** — 틀은 MiniMessage·PAPI 를 거치는데, 글을 거기 섞으면 `<click:run_command:…>` 나
 * `%player_ip%` 를 친 사람이 남의 화면에 명령 버튼·남의 정보를 띄운다. 그래서 틀에는 표식([SLOT])만 넣어 렌더링하고, 표식 자리에
 * 따로 만든 글 조각을 끼운다.
 */
object ChatText {

    /** 틀 안의 글 자리 표식(사용 영역 글자 — 사람이 칠 일이 없다). */
    private const val SLOT = "ess-chat-message"

    private val MM = MiniMessage.miniMessage()

    /** 색 권한이 있는 사람의 글 — 색·꾸밈·그라데이션만(클릭·호버·글자 바꾸기 태그는 못 쓴다). */
    private val COLOR_ONLY: MiniMessage = MiniMessage.builder()
        .tags(TagResolver.resolver(StandardTags.color(), StandardTags.decorations(), StandardTags.gradient(), StandardTags.rainbow(), StandardTags.reset()))
        .build()

    /** 사람이 친 글 → 조각. [colors] 면 `&a`·`<red>` 같은 색을 살린다. */
    fun body(text: String, colors: Boolean): Component =
        if (colors) COLOR_ONLY.deserialize(Text.legacyToMiniMessage(text)) else Component.text(text)

    /**
     * 틀([template])을 렌더링하고 `{메시지}`(`{message}`) 자리에 [body] 를 끼운다. 틀이 비면 null(그 메시지를 끈 것).
     * [viewer] 는 틀 속 PAPI 를 누구 기준으로 풀지.
     */
    fun render(template: String, ph: Ph, body: Component, viewer: Player? = null): Component? {
        if (template.isEmpty()) return null
        val rendered = Text.render(template, ph.raw("{메시지}", SLOT).raw("{message}", SLOT), viewer)
        return rendered.replaceText(TextReplacementConfig.builder().matchLiteral(SLOT).replacement(body).build())
    }

    /**
     * 글 속 주소를 짧은 링크로(CMI TranslateLink). [label] 은 MiniMessage 틀 — `{주소}` 가 주소. 누르면 주소가 열리고 올리면 주소가 보인다.
     * 정규식이 앞 빈칸을 같이 잡으면(CMI 기본 `(^| )`) 빈칸은 남긴다.
     */
    fun shortenLinks(message: Component, regex: Regex, label: String): Component {
        val pattern = regex.toPattern()
        return message.replaceText(
            TextReplacementConfig.builder().match(pattern).replacement { match, _ ->
                val whole = match.group()
                val lead = whole.takeWhile { it == ' ' }
                val url = whole.substring(lead.length)
                val href = if (url.contains("://")) url else "https://$url"
                val link = MM.deserialize(label.replace("{주소}", MM.escapeTags(url)).replace("{url}", MM.escapeTags(url)))
                    .clickEvent(ClickEvent.openUrl(href))
                    .hoverEvent(HoverEvent.showText(Component.text(href)))
                if (lead.isEmpty()) link else Component.text(lead).append(link)
            }.build(),
        )
    }

    fun plain(component: Component): String = Text.plain(component)
}
