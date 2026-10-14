package com.inmc.essentials.mail

import kr.inmc.core.util.Text
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags

/**
 * 편지 글 → 조각. 색이 있는 편지(관리자 색 권한 · CMI 에서 가져온 편지)도 **색·꾸밈·그라데이션만** 살린다 — 클릭·호버·글자 넣기 태그는 글자 그대로.
 * CMI 시절 플레이어가 쓴 `<click:run_command:…>` 가 가져온 뒤 읽는 사람의 권한으로 도는 버튼이 되지 않게(chat 의 `ChatText` 와 같은 규칙 —
 * 기능끼리 import 하지 않아 따로 둔다). PAPI 는 풀지 않는다(읽는 사람 기준으로 풀리면 보낸 사람이 남의 정보를 띄운다).
 */
object MailText {

    private val COLOR_ONLY: MiniMessage = MiniMessage.builder()
        .tags(TagResolver.resolver(StandardTags.color(), StandardTags.decorations(), StandardTags.gradient(), StandardTags.rainbow(), StandardTags.reset()))
        .build()

    fun body(raw: String, rich: Boolean): Component =
        if (rich) COLOR_ONLY.deserialize(Text.legacyToMiniMessage(raw)) else Component.text(raw)
}
