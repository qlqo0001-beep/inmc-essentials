package com.inmc.essentials.server

/**
 * 커스텀 텍스트 한 편(`plugins/inmc-essentials/texts/<이름>.txt`, 순수). 글은 MiniMessage(+ `&` 색 코드, PlaceholderAPI, `{player}`).
 *
 * CMI CustomText 의 표식을 그대로 받는다(줄 하나에 그것만 있을 때):
 * `<NextPage>` 쪽 나눔 · `<AutoPage>` 설정 줄 수로 자동 쪽 나눔 · `<Hidden>` 목록에서 숨김 · `<ReqPermission>` `inmcessentials.server.text.<이름>` 필요 ·
 * `<AutoAlias>` `/<이름>` 명령으로도. 줄 안의 CMI 상호작용 표식(`<T>글</T><H>호버</H><C>/명령</C>` …)은 [CmiText] 가 MiniMessage 로 바꾼다.
 */
data class TextDoc(
    val name: String,
    val pages: List<List<String>>,
    val hidden: Boolean = false,
    val requiresPermission: Boolean = false,
    val autoAlias: Boolean = false,
) {
    companion object {

        private val FLAG = Regex("""^\s*<(NextPage|AutoPage|Hidden|ReqPermission|AutoAlias)>\s*$""", RegexOption.IGNORE_CASE)

        fun parse(name: String, text: String, linesPerPage: Int): TextDoc {
            var hidden = false
            var perm = false
            var alias = false
            var auto = false
            val pages = mutableListOf<MutableList<String>>(mutableListOf())
            for (raw in text.replace("\r", "").removePrefix("﻿").split('\n')) {
                val flag = FLAG.matchEntire(raw)?.groupValues?.get(1)?.lowercase()
                when (flag) {
                    "nextpage" -> pages += mutableListOf<String>()
                    "autopage" -> auto = true
                    "hidden" -> hidden = true
                    "reqpermission" -> perm = true
                    "autoalias" -> alias = true
                    else -> pages.last() += CmiText.toMiniMessage(raw)
                }
            }
            // 끝의 빈 줄 · 빈 쪽은 버린다.
            for (p in pages) while (p.isNotEmpty() && p.last().isBlank()) p.removeAt(p.lastIndex)
            var result: List<List<String>> = pages.filter { it.isNotEmpty() }
            if (auto) result = result.flatMap { it.chunked(linesPerPage) }
            return TextDoc(name, result.ifEmpty { listOf(emptyList()) }, hidden, perm, alias)
        }

        /** 파일 이름으로 쓸 수 있나(경로를 벗어나지 않게). */
        fun validName(name: String): Boolean = name.isNotBlank() && name.length <= 48 && name.none { it == '/' || it == '\\' || it == '.' || it == ':' || it.isWhitespace() }
    }
}

/**
 * CMI 의 채팅 표식 → MiniMessage(순수). `<T>글</T>` 뒤에 붙는 `<H>호버</H>` · `<C>/명령</C>`(누르면 실행) · `<SC>/명령</SC>`(입력창에 넣기) ·
 * `<URL>주소</URL>` · `<CC>`·`<CCI>`(콘솔 명령 — **지원하지 않음**: 누른 사람의 명령으로 바꾸면 권한이 달라지므로 버린다) · `{#rrggbb}` 색.
 */
object CmiText {

    private val BLOCK = Regex("""<T>(.*?)</T>((?:\s*<(?:H|C|SC|SI|URL|CC|CCI)>.*?</(?:H|C|SC|SI|URL|CC|CCI)>)*)""", RegexOption.IGNORE_CASE)
    private val PART = Regex("""<(H|C|SC|SI|URL|CC|CCI)>(.*?)</\1>""", RegexOption.IGNORE_CASE)
    private val HEX = Regex("""\{#([0-9a-fA-F]{6})\}""")

    /** 한 줄. CMI 표식이 없으면 `{#hex}` 만 바꿔 그대로. */
    fun toMiniMessage(line: String): String {
        var text = HEX.replace(line) { "<#${it.groupValues[1]}>" }
        if (!text.contains("<T>", ignoreCase = true)) return text
        text = BLOCK.replace(text) { m ->
            var open = ""
            var close = ""
            for (p in PART.findAll(m.groupValues[2])) {
                val arg = quote(p.groupValues[2])
                when (p.groupValues[1].uppercase()) {
                    "H" -> {
                        open += "<hover:show_text:$arg>"
                        close = "</hover>$close"
                    }
                    "C" -> {
                        open += "<click:run_command:${quote(slash(p.groupValues[2]))}>"
                        close = "</click>$close"
                    }
                    "SC", "SI" -> {
                        open += "<click:suggest_command:${quote(p.groupValues[2])}>"
                        close = "</click>$close"
                    }
                    "URL" -> {
                        open += "<click:open_url:$arg>"
                        close = "</click>$close"
                    }
                    else -> {}
                }
            }
            open + m.groupValues[1] + close
        }
        return text
    }

    private fun slash(cmd: String): String = cmd.trim().let { if (it.startsWith("/")) it else "/$it" }

    /** MiniMessage 인자 따옴표 — 안의 `'` 와 `\` 는 이스케이프. */
    private fun quote(s: String): String = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"
}
