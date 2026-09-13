package com.svewiki.editor.highlight

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

enum class SyntaxMode(val displayName: String, val description: String) {
    WIKITEXT("WikiText", "MediaWiki 语法高亮"),
    MARKDOWN("Markdown", "Markdown 语法高亮"),
    PLAIN_TEXT("纯文本", "无高亮"),
    JSON("JSON", "JSON 语法高亮"),
    CSS("CSS", "CSS 语法高亮"),
    LUA("Lua", "Lua 语法高亮");

    companion object {
        fun fromNamespace(namespace: Int): SyntaxMode = when (namespace) {
            8 -> PLAIN_TEXT
            828, 829 -> LUA
            else -> WIKITEXT
        }
    }
}

/**
 * MediaWiki wikitext 语法高亮。
 *
 * 与旧版「全局 regex 叠加」不同，这里是手写单遍扫描器：
 * - O(n) 单遍扫描，不随正则数量劣化，也不会出现正则互相覆盖染色
 * - 正确处理嵌套：{{ {{ }} }}、[[link|{{tpl}}]] 等
 * - `<!-- -->`、<nowiki>...</nowiki> 内部不再被二次着色
 * - 模板内部细分：模板名 / 参数名 / 参数值 三色
 * - 链接内部细分：页面名 / 显示文本
 * - 表格标记 {| | |- |} 与单元格分隔符单独着色
 */
object WikiTextHighlighter {

    // ---- 调色板（VS Code wikitext 风格，浅底适配） ----
    private val colorComment   = Color(0xFF8A8F8A)
    private val colorTag       = Color(0xFFC05621)
    private val colorTemplate  = Color(0xFF6A4C93)
    private val colorTplName   = Color(0xFF4B3A75)
    private val colorParamName = Color(0xFF9C6D1E)
    private val colorLink      = Color(0xFF2A7A6E)
    private val colorExtLink   = Color(0xFF4A6FA5)
    private val colorHeading   = Color(0xFFB45309)
    private val colorList      = Color(0xFF3E7C4F)
    private val colorBold      = Color(0xFF8C4A2F)
    private val colorItalic    = Color(0xFF6B5B73)
    private val colorMagic     = Color(0xFFB0246B)
    private val colorTable     = Color(0xFF7A5C00)
    private val colorHr        = Color(0xFF999999)
    private val colorEntity    = Color(0xFF556B2F)

    // 内部区间标记（不随主题变的逻辑类型）
    private enum class Kind {
        COMMENT, TAG, TEMPLATE_BRACE, TEMPLATE_NAME, PARAM_NAME, PIPE,
        LINK_BRACKET, LINK_TARGET, EXT_LINK, HEADING, LIST,
        BOLD_TICKS, ITALIC_TICKS, MAGIC, TABLE, HR, ENTITY
    }

    private data class Span(val start: Int, val end: Int, val kind: Kind)

    fun highlight(text: String, mode: SyntaxMode = SyntaxMode.WIKITEXT): AnnotatedString {
        if (mode == SyntaxMode.PLAIN_TEXT || text.isEmpty()) return AnnotatedString(text)
        return when (mode) {
            SyntaxMode.JSON -> highlightJson(text)
            SyntaxMode.LUA -> highlightLua(text)
            SyntaxMode.MARKDOWN -> highlightMarkdown(text)
            else -> highlightWikitext(text)
        }
    }

    // ====================================================================
    // Wikitext 单遍扫描器
    // ====================================================================

    private fun highlightWikitext(text: String): AnnotatedString {
        // 大文本保护：超过阈值直接返回，避免卡顿
        if (text.length > 120_000) return AnnotatedString(text)

        val spans = ArrayList<Span>(text.length / 24)
        val n = text.length
        var i = 0
        var lineStart = true

        // 模板/链接嵌套栈：'{' = 模板, '[' = 内部链接
        val stack = ArrayDeque<Char>()
        // 模板内状态：| 之后是否处于「参数名」位置（= 之前）
        val tplState = ArrayDeque<Boolean>() // true = 期待参数名

        fun at(s: String, pos: Int): Boolean =
            pos + s.length <= n && text.regionMatches(pos, s, 0, s.length)
        fun count(ch: Char, pos: Int): Int {
            var c = 0
            while (pos + c < n && text[pos + c] == ch) c++
            return c
        }
        /** 跳过到行尾或 depth 回到 0 的闭括号；返回内容结束位置（不含闭括号） */
        fun skipEntity(open: String, close: String, pos: Int): Int {
            var p = pos + open.length
            var depth = 1
            while (p < n) {
                when {
                    at(close, p) -> { depth--; if (depth == 0) return p; p += close.length }
                    at(open, p)  -> { depth++; p += open.length }
                    else -> p++
                }
            }
            return n
        }

        while (i < n) {
            val c = text[i]

            // ---- 1. HTML 注释 <!-- ... --> ----
            if (at("<!--", i)) {
                val end = text.indexOf("-->", i + 4).let { if (it < 0) n else it + 3 }
                spans += Span(i, end, Kind.COMMENT)
                i = end
                lineStart = false
                continue
            }

            // ---- 2. nowiki / pre：内容完全不着色 ----
            if (c == '<' && (at("<nowiki", i) || at("<pre", i))) {
                val tagEnd = text.indexOf('>', i)
                if (tagEnd >= 0) {
                    val closeTag = if (at("<nowiki", i)) "</nowiki>" else "</pre>"
                    val closeIdx = text.indexOf(closeTag, tagEnd + 1, ignoreCase = true)
                        .let { if (it < 0) n else it + closeTag.length }
                    spans += Span(i, tagEnd + 1, Kind.TAG)
                    if (closeIdx < n) spans += Span(closeIdx - closeTag.length, closeIdx, Kind.TAG)
                    i = closeIdx
                    lineStart = false
                    continue
                }
            }

            // ---- 3. 行首语法 ----
            if (lineStart) {
                when {
                    c == '=' -> {
                        val eqs = count('=', i).coerceAtMost(6)
                        var eol = i
                        while (eol < n && text[eol] != '\n') eol++
                        // 行尾要有对称的 = 才算标题
                        var tail = eol - 1
                        var tailEqs = 0
                        while (tail >= i && text[tail] == '=') { tailEqs++; tail-- }
                        if (tailEqs >= eqs && eol - i > eqs * 2) {
                            spans += Span(i, eol, Kind.HEADING)
                            i = eol
                            continue
                        }
                    }
                    c == '*' || c == '#' || c == ':' || c == ';' -> {
                        val m = count(c, i)
                        spans += Span(i, i + m, Kind.LIST)
                        i += m
                        lineStart = false
                        continue
                    }
                    c == '-' && at("----", i) -> {
                        val m = count('-', i)
                        spans += Span(i, i + m, Kind.HR)
                        i += m
                        lineStart = false
                        continue
                    }
                    at("{|", i) || at("|}", i) -> {
                        spans += Span(i, i + 2, Kind.TABLE)
                        i += 2
                        lineStart = false
                        continue
                    }
                    c == '!' || c == '|' -> {
                        // 表头分隔 / 单元格 / 行分隔 |-
                        val m = if (at("|-", i)) 2 else if (at("||", i) || at("!!", i)) 2 else 1
                        spans += Span(i, i + m, Kind.TABLE)
                        i += m
                        lineStart = false
                        continue
                    }
                }
                if (at("__", i)) {
                    val end = text.indexOf("__", i + 2)
                    if (end > 0) {
                        spans += Span(i, end + 2, Kind.MAGIC)
                        i = end + 2
                        lineStart = false
                        continue
                    }
                }
            }

            // ---- 4. 行内 HTML 标签 ----
            if (c == '<') {
                val gt = text.indexOf('>', i)
                if (gt > 0 && (i + 1 < n && (text[i + 1].isLetter() || text[i + 1] == '/'))) {
                    spans += Span(i, gt + 1, Kind.TAG)
                    i = gt + 1
                    lineStart = false
                    continue
                }
            }

            // ---- 5. 模板 {{ ... }}（支持嵌套 + 参数名着色） ----
            if (at("{{", i)) {
                spans += Span(i, i + 2, Kind.TEMPLATE_BRACE)
                stack.addLast('{')
                tplState.addLast(true) // | 之前是模板名位置
                i += 2
                lineStart = false
                continue
            }
            if (at("}}", i) && stack.lastOrNull() == '{') {
                spans += Span(i, i + 2, Kind.TEMPLATE_BRACE)
                stack.removeLast()
                tplState.removeLast()
                i += 2
                lineStart = false
                continue
            }

            // ---- 6. 内部链接 [[ ... ]]（支持嵌套） ----
            if (at("[[", i)) {
                spans += Span(i, i + 2, Kind.LINK_BRACKET)
                stack.addLast('[')
                // 目标段着色到 | 或 ]]
                var p = i + 2
                var d = 1
                while (p < n && text[p] != '|' && text[p] != '\n') {
                    if (at("]]", p)) break
                    if (at("[[", p)) { d++; p += 2; continue }
                    p++
                }
                if (p > i + 2) spans += Span(i + 2, p, Kind.LINK_TARGET)
                i += 2
                lineStart = false
                continue
            }
            if (at("]]", i) && stack.lastOrNull() == '[') {
                spans += Span(i, i + 2, Kind.LINK_BRACKET)
                stack.removeLast()
                i += 2
                lineStart = false
                continue
            }

            // ---- 7. 外部链接 [url 文字] ----
            if (c == '[' && !at("[[", i)) {
                val close = text.indexOf(']', i + 1)
                val eol = text.indexOf('\n', i + 1).let { if (it < 0) n else it }
                if (close in (i + 1) until eol) {
                    spans += Span(i, i + 1, Kind.LINK_BRACKET)
                    val urlEnd = run {
                        var p = i + 1
                        while (p < close && !text[p].isWhitespace()) p++
                        p
                    }
                    if (urlEnd > i + 1) spans += Span(i + 1, urlEnd, Kind.EXT_LINK)
                    spans += Span(close, close + 1, Kind.LINK_BRACKET)
                    i = close + 1
                    lineStart = false
                    continue
                }
            }

            // ---- 8. 模板/表格内的管道与参数名 ----
            if (c == '|' && !lineStart) {
                if (stack.lastOrNull() == '{') {
                    spans += Span(i, i + 1, Kind.PIPE)
                    tplState.removeLast()
                    tplState.addLast(true)
                    i++
                    continue
                }
                if (stack.lastOrNull() == '[') {
                    spans += Span(i, i + 1, Kind.PIPE)
                    i++
                    continue
                }
            }
            if (c == '=' && stack.lastOrNull() == '{' && tplState.lastOrNull() == true) {
                // 向前找本段起点（最近的 | 或 {{）作为参数名范围
                var p = i - 1
                while (p >= 0 && text[p] != '|' && text[p] != '{' && text[p] != '\n') p--
                val nameStart = p + 1
                // 参数名含空白或无内容则不算命名参数
                val candidate = text.substring(nameStart, i).trim()
                if (candidate.isNotEmpty() && !candidate.contains(' ') && !candidate.contains('=')) {
                    spans += Span(nameStart, i, Kind.PARAM_NAME)
                }
                tplState.removeLast()
                tplState.addLast(false)
                i++
                continue
            }

            // ---- 9. 模板名着色：{{ 之后到 | 或 }} 的第一段 ----
            if (stack.lastOrNull() == '{' && tplState.lastOrNull() == true) {
                // 位于模板名/参数名区域，遇到空白前快速前进（参数名由 '=' 分支回追着色）
            }

            // ---- 10. 粗体 / 斜体 ----
            if (c == '\'') {
                val ticks = count('\'', i)
                if (ticks >= 5) { // ''''' 粗斜
                    spans += Span(i, i + 5, Kind.BOLD_TICKS)
                    i += 5
                    lineStart = false
                    continue
                }
                if (ticks == 3) {
                    spans += Span(i, i + 3, Kind.BOLD_TICKS)
                    i += 3
                    lineStart = false
                    continue
                }
                if (ticks == 2) {
                    spans += Span(i, i + 2, Kind.ITALIC_TICKS)
                    i += 2
                    lineStart = false
                    continue
                }
            }

            // ---- 11. HTML 实体 &amp; &#123; ----
            if (c == '&') {
                val semi = text.indexOf(';', i + 1)
                if (semi in (i + 1)..(i + 12).coerceAtMost(n - 1)) {
                    val body = text.substring(i + 1, semi)
                    if (body.matches(Regex("#?[A-Za-z0-9]+"))) {
                        spans += Span(i, semi + 1, Kind.ENTITY)
                        i = semi + 1
                        lineStart = false
                        continue
                    }
                }
            }

            // ---- 12. 行首魔术词（不在行首也可能出现的 __TOC__ 等） ----
            if (!lineStart && at("__", i)) {
                val end = text.indexOf("__", i + 2)
                if (end > 0 && end - i <= 24) {
                    spans += Span(i, end + 2, Kind.MAGIC)
                    i = end + 2
                    continue
                }
            }

            lineStart = c == '\n'
            i++
        }

        // ---- 二次扫描：为每个模板渲染「模板名」（{{ 后到 |/}} 的文本） ----
        // 用简单状态机：遇到 {{ 时若 spans 无冲突，找其后第一段
        // （为保持简单高效，这里用独立小栈再扫一遍模板名）
        run {
            var p = 0
            val d = ArrayDeque<Int>() // 每个模板名的开始位置
            while (p < n) {
                when {
                    text.regionMatches(p, "{{", 0, 2) -> {
                        d.addLast(p + 2)
                        p += 2
                    }
                    text.regionMatches(p, "}}", 0, 2) -> {
                        if (d.isNotEmpty()) d.removeLast()
                        p += 2
                    }
                    d.isNotEmpty() && (text[p] == '|' || text[p] == '\n') -> {
                        val s = d.removeLast()
                        if (p > s) {
                            val name = text.substring(s, p)
                            // 模板名内不应含其他括号开头
                            if (!name.contains("{{") && !name.contains("[[") && name.isNotBlank()) {
                                spans += Span(s, p, Kind.TEMPLATE_NAME)
                            }
                        }
                        p++
                    }
                    else -> p++
                }
            }
        }

        return render(text, spans)
    }

    // ====================================================================
    // 渲染：区间去重、排序、应用样式
    // ====================================================================

    private fun render(text: String, spans: List<Span>): AnnotatedString = buildAnnotatedString {
        append(text)
        // 先按 start 排序；同 start 时区间短（更具体）的优先
        val sorted = spans
            .filter { it.end > it.start && it.start >= 0 && it.end <= text.length }
            .sortedWith(compareBy({ it.start }, { it.end }))

        // 贪心去重：已覆盖区间不再重复染色（优先级靠排序保证）
        var coveredUntil = -1
        for (s in sorted) {
            if (s.start < coveredUntil) continue
            addStyle(styleFor(s.kind), s.start, s.end)
            coveredUntil = s.end
        }
    }

    private fun styleFor(kind: Kind): SpanStyle = when (kind) {
        Kind.COMMENT        -> SpanStyle(color = colorComment, fontStyle = FontStyle.Italic)
        Kind.TAG            -> SpanStyle(color = colorTag)
        Kind.TEMPLATE_BRACE -> SpanStyle(color = colorTemplate, fontWeight = FontWeight.SemiBold)
        Kind.TEMPLATE_NAME  -> SpanStyle(color = colorTplName, fontWeight = FontWeight.Medium)
        Kind.PARAM_NAME     -> SpanStyle(color = colorParamName)
        Kind.PIPE           -> SpanStyle(color = colorTemplate)
        Kind.LINK_BRACKET   -> SpanStyle(color = colorLink, fontWeight = FontWeight.Medium)
        Kind.LINK_TARGET    -> SpanStyle(color = colorLink)
        Kind.EXT_LINK       -> SpanStyle(color = colorExtLink)
        Kind.HEADING        -> SpanStyle(color = colorHeading, fontWeight = FontWeight.SemiBold)
        Kind.LIST           -> SpanStyle(color = colorList, fontWeight = FontWeight.Medium)
        Kind.BOLD_TICKS     -> SpanStyle(color = colorBold, fontWeight = FontWeight.Bold)
        Kind.ITALIC_TICKS   -> SpanStyle(color = colorItalic, fontStyle = FontStyle.Italic)
        Kind.MAGIC          -> SpanStyle(color = colorMagic, fontWeight = FontWeight.Medium)
        Kind.TABLE          -> SpanStyle(color = colorTable, fontWeight = FontWeight.Medium)
        Kind.HR             -> SpanStyle(color = colorHr)
        Kind.ENTITY         -> SpanStyle(color = colorEntity)
    }

    // ====================================================================
    // JSON / Lua / Markdown（保留原实现，微调 Markdown 支持）
    // ====================================================================

    private fun highlightJson(text: String): AnnotatedString = buildAnnotatedString {
        append(text)
        if (text.length > 120_000) return@buildAnnotatedString
        // 字符串键
        Regex(""""(?:[^"\\]|\\.)*"(?=\s*:)""").findAll(text).forEach {
            addStyle(SpanStyle(color = colorTplName), it.range.first, it.range.last + 1)
        }
        // 普通字符串
        Regex(""""(?:[^"\\]|\\.)*""").findAll(text).forEach {
            if (it.range.last + 1 >= text.length || text.getOrNull(it.range.last + 1) != ':') {
                addStyle(SpanStyle(color = colorLink), it.range.first, it.range.last + 1)
            }
        }
        // 数字 / 布尔 / null
        Regex("\b(true|false|null|-?\d+(\.\d+)?([eE][+-]?\d+)?)\b").findAll(text).forEach {
            addStyle(SpanStyle(color = colorParamName), it.range.first, it.range.last + 1)
        }
    }

    private fun highlightLua(text: String): AnnotatedString = buildAnnotatedString {
        append(text)
        if (text.length > 120_000) return@buildAnnotatedString
        Regex("(?m)--\[\[[\s\S]*?\]\]|--.*$").findAll(text).forEach {
            addStyle(SpanStyle(color = colorComment, fontStyle = FontStyle.Italic), it.range.first, it.range.last + 1)
        }
        Regex("\b(local|function|end|if|then|else|elseif|return|for|while|do|in|and|or|not|nil|true|false|repeat|until|break)\b")
            .findAll(text).forEach {
                addStyle(SpanStyle(color = colorTemplate, fontWeight = FontWeight.Medium), it.range.first, it.range.last + 1)
            }
        Regex(""""(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'""").findAll(text).forEach {
            addStyle(SpanStyle(color = colorLink), it.range.first, it.range.last + 1)
        }
        Regex("\b\d+(\.\d+)?\b").findAll(text).forEach {
            addStyle(SpanStyle(color = colorParamName), it.range.first, it.range.last + 1)
        }
    }

    private fun highlightMarkdown(text: String): AnnotatedString = buildAnnotatedString {
        append(text)
        if (text.length > 120_000) return@buildAnnotatedString
        Regex("(?m)^#{1,6}\\s.*$").findAll(text).forEach {
            addStyle(SpanStyle(color = colorHeading, fontWeight = FontWeight.SemiBold), it.range.first, it.range.last + 1)
        }
        Regex("(?m)^\\s*([-*+] |\\d+\\. )").findAll(text).forEach {
            addStyle(SpanStyle(color = colorList, fontWeight = FontWeight.Medium), it.range.first, it.range.last + 1)
        }
        Regex("\\*\\*[^*]+\\*\\*").findAll(text).forEach {
            addStyle(SpanStyle(color = colorBold, fontWeight = FontWeight.Bold), it.range.first, it.range.last + 1)
        }
        Regex("\\*[^*]+\\*").findAll(text).forEach {
            addStyle(SpanStyle(color = colorItalic, fontStyle = FontStyle.Italic), it.range.first, it.range.last + 1)
        }
        Regex("`[^`]+`").findAll(text).forEach {
            addStyle(SpanStyle(color = colorTag), it.range.first, it.range.last + 1)
        }
        Regex("\\[[^]]+\\]\\([^)]+\\)").findAll(text).forEach {
            addStyle(SpanStyle(color = colorLink), it.range.first, it.range.last + 1)
        }
    }
}
