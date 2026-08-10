package com.svewiki.editor.highlight

import android.graphics.Typeface
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.BackgroundColorSpan

/**
 * 语法高亮模式
 */
enum class SyntaxMode(val displayName: String, val description: String) {
    WIKITEXT("WikiText", "MediaWiki 语法高亮"),
    MARKDOWN("Markdown", "Markdown 语法高亮"),
    PLAIN_TEXT("纯文本", "无高亮"),
    JSON("JSON", "JSON 语法高亮"),
    CSS("CSS", "CSS 语法高亮"),
    LUA("Lua", "Lua 语法高亮");

    companion object {
        fun fromNamespace(namespace: Int): SyntaxMode = when (namespace) {
            0, 1, 2, 3, 4, 5, 6, 7, 12, 13, 14, 15 -> WIKITEXT
            8 -> PLAIN_TEXT
            10, 11 -> WIKITEXT
            828, 829 -> LUA
            else -> WIKITEXT
        }
    }
}
/**
 * WikiText 语法高亮引擎
 * 用 SpannableStringBuilder 给 MediaWiki 语法元素着色
 */
object WikiTextHighlighter {

    // 颜色常量
    private const val COLOR_TEMPLATE = 0xFF7B1FA2.toInt()       // 紫色 - 模板 {{}}
    private const val COLOR_LINK = 0xFF00897B.toInt()            // 青色 - 链接 [[]]
    private const val COLOR_HEADING = 0xFFE65100.toInt()         // 橙色 - 标题 =====
    private const val COLOR_COMMENT = 0xFF9E9E9E.toInt()         // 灰色 - 注释 <!-- -->
    private const val COLOR_PARAM = 0xFFC62828.toInt()           // 红色 - 参数 {{{}}}
    private const val COLOR_MAGIC = 0xFF6A1B9A.toInt()           // 深紫 - 魔术字 __TOC__
    private const val COLOR_LIST = 0xFF2E7D32.toInt()            // 绿色 - 列表 * # ;
    private const val COLOR_TABLE = 0xFF00695C.toInt()           // 深青 - 表格 {| |}
    private const val COLOR_URL = 0xFF1565C0.toInt()             // 蓝色 - 外部链接
    private const val COLOR_HR = 0xFF757575.toInt()              // 灰 - 水平线 ----
    private const val COLOR_TAG = 0xFFE65100.toInt()             // 橙色 - HTML标签
    private const val COLOR_NOWIKI = 0xFFBDBDBD.toInt()          // 浅灰 - nowiki/pre
    private const val COLOR_BOLD = 0xFF424242.toInt()            // 深灰 - 粗体
    private const val COLOR_ITALIC = 0xFF616161.toInt()           // 中灰 - 斜体
    private const val BG_NOWIKI = 0x1A000000.toInt()             // 半透明背景

    /**
     * 对文本应用 WikiText 高亮（返回新 SpannableStringBuilder）
     */
    fun highlight(text: String): SpannableStringBuilder {
        val sb = SpannableStringBuilder(text)
        val len = text.length
        var i = 0

        while (i < len) {
            when {
                // 注释 <!-- -->（支持跨行）
                i + 3 < len && text[i] == '<' && text[i+1] == '!' &&
                text[i+2] == '-' && text[i+3] == '-' -> {
                    val end = text.indexOf("-->", i + 4)
                    if (end != -1) {
                        span(sb, i, end + 3, COLOR_COMMENT, Typeface.ITALIC)
                        i = end + 3
                    } else { i++ }
                }

                // HTML 标签（含属性高亮，优先于 nowiki 判断）
                text[i] == '<' && i + 1 < len && (text[i+1].isLetter() || text[i+1] == '/') -> {
                    val tagEnd = text.indexOf('>', i + 1)
                    if (tagEnd != -1) {
                        val tagContent = text.substring(i, tagEnd + 1)
                        if (tagContent.contains("nowiki") || tagContent.contains("pre") ||
                            tagContent.contains("source") || tagContent.contains("syntaxhighlight")) {
                            i++ // 交给下面的专用分支处理
                        } else {
                            // 高亮整个标签 + 属性值
                            span(sb, i, tagEnd + 1, COLOR_TAG)
                            // 属性值字符串高亮
                            highlightTagAttributes(sb, text, i, tagEnd)
                            i = tagEnd + 1
                        }
                    } else { i++ }
                }

                // nowiki
                text.regionMatches(i, "<nowiki>", 0, 8) -> {
                    val end = text.indexOf("</nowiki>", i + 8)
                    if (end != -1) {
                        span(sb, i, end + 9, COLOR_NOWIKI, bgColor = BG_NOWIKI)
                        i = end + 9
                    } else { i++ }
                }

                // pre
                text.regionMatches(i, "<pre>", 0, 5) -> {
                    val end = text.indexOf("</pre>", i + 5)
                    if (end != -1) {
                        span(sb, i, end + 6, COLOR_NOWIKI, bgColor = BG_NOWIKI)
                        i = end + 6
                    } else { i++ }
                }

                // source/syntaxhighlight
                (text.regionMatches(i, "<source", 0, 7) ||
                 text.regionMatches(i, "<syntaxhighlight", 0, 16)) -> {
                    val closeTag = text.indexOf('>', i)
                    if (closeTag != -1) {
                        val tagName = if (text.regionMatches(i, "<syntaxhighlight", 0, 16)) "syntaxhighlight" else "source"
                        val end = text.indexOf("</$tagName>", closeTag)
                        if (end != -1) {
                            span(sb, i, end + tagName.length + 3, COLOR_NOWIKI, bgColor = BG_NOWIKI)
                            i = end + tagName.length + 3
                        } else { i++ }
                    } else { i++ }
                }

                // 魔术字 __TOC__
                text[i] == '_' && i + 1 < len && text[i+1] == '_' -> {
                    val end = text.indexOf("__", i + 2)
                    if (end != -1) {
                        span(sb, i, end + 2, COLOR_MAGIC, Typeface.BOLD)
                        i = end + 2
                    } else { i++ }
                }

                // 参数 {{{}}}（支持嵌套）
                i + 2 < len && text[i] == '{' && text[i+1] == '{' && text[i+2] == '{' -> {
                    val end = findTripleCurlyEnd(text, i + 3)
                    if (end != -1) {
                        span(sb, i, end + 3, COLOR_PARAM, Typeface.BOLD)
                        i = end + 3
                    } else { i++ }
                }

                // 模板 {{}}（支持嵌套）
                text[i] == '{' && text[i+1] == '{' -> {
                    val end = findTemplateEnd(text, i + 2)
                    if (end != -1 && end > i + 1) {
                        span(sb, i, end + 2, COLOR_TEMPLATE, Typeface.BOLD)
                        // 模板名高亮
                        val pipeIdx = text.indexOf('|', i + 2)
                        if (pipeIdx != -1 && pipeIdx < end) {
                            span(sb, i + 2, pipeIdx, COLOR_TEMPLATE, Typeface.BOLD)
                        }
                        i = end + 2
                    } else { i++ }
                }

                // 内部链接 [[]]（支持锚点 # 和管道 |）
                text[i] == '[' && text[i+1] == '[' -> {
                    val end = text.indexOf("]]", i + 2)
                    if (end != -1) {
                        span(sb, i, end + 2, COLOR_LINK)
                        // 链接目标加粗
                        val pipeIdx = findLinkPipe(text, i + 2, end)
                        if (pipeIdx != -1 && pipeIdx < end) {
                            span(sb, i + 2, pipeIdx, COLOR_LINK, Typeface.BOLD)
                        } else {
                            span(sb, i + 2, end, COLOR_LINK, Typeface.BOLD)
                        }
                        i = end + 2
                    } else { i++ }
                }

                // 外部链接 [url]
                text[i] == '[' && i + 1 < len && (text[i+1] == 'h' || text[i+1] == 'f') -> {
                    val end = text.indexOf(']', i + 1)
                    if (end != -1 && text.substring(i+1, end).contains("://")) {
                        span(sb, i, end + 1, COLOR_URL)
                        i = end + 1
                    } else { i++ }
                }

                // 标题 =====
                text[i] == '=' -> {
                    var count = 0
                    var j = i
                    while (j < len && text[j] == '=') { count++; j++ }
                    if (count in 1..6 && j < len && text[j] == ' ') {
                        val endIdx = text.indexOf("=".repeat(count), j)
                        if (endIdx != -1) {
                            span(sb, i, endIdx + count, COLOR_HEADING, Typeface.BOLD)
                            i = endIdx + count
                        } else { i++ }
                    } else { i++ }
                }

                // 水平线 ----
                i + 3 < len && text[i] == '-' && text[i+1] == '-' &&
                text[i+2] == '-' && text[i+3] == '-' -> {
                    span(sb, i, i + 4, COLOR_HR)
                    i += 4
                }

                // 表格 {| |}
                text[i] == '{' && text[i+1] == '|' -> {
                    val end = text.indexOf("|}", i + 2)
                    if (end != -1) {
                        span(sb, i, end + 2, COLOR_TABLE, Typeface.BOLD)
                        i = end + 2
                    } else { i++ }
                }

                // 列表符号 * # ; :
                (text[i] == '*' || text[i] == '#' || text[i] == ';' || text[i] == ':') &&
                (i == 0 || text[i-1] == '\n') -> {
                    var j = i
                    while (j < len && (text[j] == '*' || text[j] == '#' || text[j] == ';' || text[j] == ':')) j++
                    if (j > i) {
                        span(sb, i, j, COLOR_LIST, Typeface.BOLD)
                        i = j
                    } else { i++ }
                }

                // 粗体 '''text'''
                i + 2 < len && text[i] == '\'' && text[i+1] == '\'' && text[i+2] == '\'' -> {
                    val end = text.indexOf("'''", i + 3)
                    if (end != -1) {
                        span(sb, i, end + 3, COLOR_BOLD, Typeface.BOLD)
                        i = end + 3
                    } else { i++ }
                }

                // 斜体 ''text''
                text[i] == '\'' && text[i+1] == '\'' -> {
                    val end = text.indexOf("''", i + 2)
                    if (end != -1 && !(end + 2 < len && text[end+2] == '\'')) {
                        span(sb, i, end + 2, COLOR_ITALIC, Typeface.ITALIC)
                        i = end + 2
                    } else { i++ }
                }

                else -> i++
            }
        }
        return sb
    }

    /**
     * 直接在 Editable 上应用高亮（不重建文本）
     * 解决 setText() 导致的光标抽搐和滚动失效问题
     * @param editable 已有的 Editable（EditText 的 text）
     */
    fun highlightEditable(editable: Editable) {
        val text = editable.toString()
        val len = text.length
        if (len == 0) return

        // 先清除旧的 ForegroundColorMarker 和 StyleMarker，避免重叠
        clearSpans(editable)

        var i = 0
        while (i < len) {
            when {
                // 注释 <!-- -->
                i + 3 < len && text[i] == '<' && text[i+1] == '!' &&
                text[i+2] == '-' && text[i+3] == '-' -> {
                    val end = text.indexOf("-->", i + 4)
                    if (end != -1) {
                        spanEditable(editable, i, end + 3, COLOR_COMMENT, Typeface.ITALIC)
                        i = end + 3
                    } else { i++ }
                }

                // HTML 标签
                text[i] == '<' && i + 1 < len && (text[i+1].isLetter() || text[i+1] == '/') -> {
                    val tagEnd = text.indexOf('>', i + 1)
                    if (tagEnd != -1) {
                        val tagContent = text.substring(i, tagEnd + 1)
                        if (tagContent.contains("nowiki") || tagContent.contains("pre") ||
                            tagContent.contains("source") || tagContent.contains("syntaxhighlight")) {
                            i++
                        } else {
                            spanEditable(editable, i, tagEnd + 1, COLOR_TAG)
                            i = tagEnd + 1
                        }
                    } else { i++ }
                }

                // nowiki
                text.regionMatches(i, "<nowiki>", 0, 8) -> {
                    val end = text.indexOf("</nowiki>", i + 8)
                    if (end != -1) {
                        spanEditable(editable, i, end + 9, COLOR_NOWIKI, bgColor = BG_NOWIKI)
                        i = end + 9
                    } else { i++ }
                }

                // pre
                text.regionMatches(i, "<pre>", 0, 5) -> {
                    val end = text.indexOf("</pre>", i + 5)
                    if (end != -1) {
                        spanEditable(editable, i, end + 6, COLOR_NOWIKI, bgColor = BG_NOWIKI)
                        i = end + 6
                    } else { i++ }
                }

                // source/syntaxhighlight
                (text.regionMatches(i, "<source", 0, 7) ||
                 text.regionMatches(i, "<syntaxhighlight", 0, 16)) -> {
                    val closeTag = text.indexOf('>', i)
                    if (closeTag != -1) {
                        val tagName = if (text.regionMatches(i, "<syntaxhighlight", 0, 16)) "syntaxhighlight" else "source"
                        val end = text.indexOf("</$tagName>", closeTag)
                        if (end != -1) {
                            spanEditable(editable, i, end + tagName.length + 3, COLOR_NOWIKI, bgColor = BG_NOWIKI)
                            i = end + tagName.length + 3
                        } else { i++ }
                    } else { i++ }
                }

                // 魔术字 __TOC__
                text[i] == '_' && i + 1 < len && text[i+1] == '_' -> {
                    val end = text.indexOf("__", i + 2)
                    if (end != -1) {
                        spanEditable(editable, i, end + 2, COLOR_MAGIC, Typeface.BOLD)
                        i = end + 2
                    } else { i++ }
                }

                // 参数 {{{}}}（支持嵌套）
                i + 2 < len && text[i] == '{' && text[i+1] == '{' && text[i+2] == '{' -> {
                    val end = findTripleCurlyEnd(text, i + 3)
                    if (end != -1) {
                        spanEditable(editable, i, end + 3, COLOR_PARAM, Typeface.BOLD)
                        i = end + 3
                    } else { i++ }
                }

                // 模板 {{}}（支持嵌套）
                text[i] == '{' && text[i+1] == '{' -> {
                    val end = findTemplateEnd(text, i + 2)
                    if (end != -1 && end > i + 1) {
                        spanEditable(editable, i, end + 2, COLOR_TEMPLATE, Typeface.BOLD)
                        val pipeIdx = text.indexOf('|', i + 2)
                        if (pipeIdx != -1 && pipeIdx < end) {
                            spanEditable(editable, i + 2, pipeIdx, COLOR_TEMPLATE, Typeface.BOLD)
                        }
                        i = end + 2
                    } else { i++ }
                }

                // 内部链接 [[]]
                text[i] == '[' && text[i+1] == '[' -> {
                    val end = text.indexOf("]]", i + 2)
                    if (end != -1) {
                        spanEditable(editable, i, end + 2, COLOR_LINK)
                        val pipeIdx = findLinkPipe(text, i + 2, end)
                        if (pipeIdx != -1 && pipeIdx < end) {
                            spanEditable(editable, i + 2, pipeIdx, COLOR_LINK, Typeface.BOLD)
                        } else {
                            spanEditable(editable, i + 2, end, COLOR_LINK, Typeface.BOLD)
                        }
                        i = end + 2
                    } else { i++ }
                }

                // 外部链接 [url]
                text[i] == '[' && i + 1 < len && (text[i+1] == 'h' || text[i+1] == 'f') -> {
                    val end = text.indexOf(']', i + 1)
                    if (end != -1 && text.substring(i+1, end).contains("://")) {
                        spanEditable(editable, i, end + 1, COLOR_URL)
                        i = end + 1
                    } else { i++ }
                }

                // 标题 =====
                text[i] == '=' -> {
                    var count = 0
                    var j = i
                    while (j < len && text[j] == '=') { count++; j++ }
                    if (count in 1..6 && j < len && text[j] == ' ') {
                        val endIdx = text.indexOf("=".repeat(count), j)
                        if (endIdx != -1) {
                            spanEditable(editable, i, endIdx + count, COLOR_HEADING, Typeface.BOLD)
                            i = endIdx + count
                        } else { i++ }
                    } else { i++ }
                }

                // 水平线 ----
                i + 3 < len && text[i] == '-' && text[i+1] == '-' &&
                text[i+2] == '-' && text[i+3] == '-' -> {
                    spanEditable(editable, i, i + 4, COLOR_HR)
                    i += 4
                }

                // 表格 {| |}
                text[i] == '{' && text[i+1] == '|' -> {
                    val end = text.indexOf("|}", i + 2)
                    if (end != -1) {
                        spanEditable(editable, i, end + 2, COLOR_TABLE, Typeface.BOLD)
                        i = end + 2
                    } else { i++ }
                }

                // 列表符号 * # ; :
                (text[i] == '*' || text[i] == '#' || text[i] == ';' || text[i] == ':') &&
                (i == 0 || text[i-1] == '\n') -> {
                    var j = i
                    while (j < len && (text[j] == '*' || text[j] == '#' || text[j] == ';' || text[j] == ':')) j++
                    if (j > i) {
                        spanEditable(editable, i, j, COLOR_LIST, Typeface.BOLD)
                        i = j
                    } else { i++ }
                }

                // 粗体 '''text'''
                i + 2 < len && text[i] == '\'' && text[i+1] == '\'' && text[i+2] == '\'' -> {
                    val end = text.indexOf("'''", i + 3)
                    if (end != -1) {
                        spanEditable(editable, i, end + 3, COLOR_BOLD, Typeface.BOLD)
                        i = end + 3
                    } else { i++ }
                }

                // 斜体 ''text''
                text[i] == '\'' && text[i+1] == '\'' -> {
                    val end = text.indexOf("''", i + 2)
                    if (end != -1 && !(end + 2 < len && text[end+2] == '\'')) {
                        spanEditable(editable, i, end + 2, COLOR_ITALIC, Typeface.ITALIC)
                        i = end + 2
                    } else { i++ }
                }

                else -> i++
            }
        }
    }

    /** 清除之前的颜色和样式 span */
    private fun clearSpans(editable: Editable) {
        val spans = editable.getSpans(0, editable.length, Any::class.java)
        for (span in spans) {
            try {
                if (span is ForegroundColorSpan || span is StyleSpan || span is BackgroundColorSpan) {
                    editable.removeSpan(span)
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * 根据语法模式应用高亮
     */
    fun highlightByMode(editable: Editable, mode: SyntaxMode) {
        when (mode) {
            SyntaxMode.WIKITEXT -> highlightEditable(editable)
            SyntaxMode.LUA -> highlightLua(editable)
            SyntaxMode.CSS -> highlightCss(editable)
            SyntaxMode.JSON -> highlightJson(editable)
            SyntaxMode.MARKDOWN -> highlightMarkdown(editable)
            SyntaxMode.PLAIN_TEXT -> clearSpans(editable)
        }
    }

    /**
     * Lua 语法高亮
     */
    fun highlightLua(editable: Editable) {
        val text = editable.toString()
        val len = text.length
        if (len == 0) return
        clearSpans(editable)

        // Lua 关键字
        val keywords = setOf(
            "and", "break", "do", "else", "elseif", "end", "false", "for",
            "function", "if", "in", "local", "nil", "not", "or", "repeat",
            "return", "then", "true", "until", "while"
        )
        val constants = setOf("true", "false", "nil")

        var i = 0
        while (i < len) {
            val c = text[i]

            // 注释 -- 或 --[[ ]]
            if (c == '-' && i + 1 < len && text[i+1] == '-') {
                if (i + 2 < len && text[i+2] == '[' && i + 3 < len && text[i+3] == '[') {
                    val end = text.indexOf("]]", i + 4)
                    if (end != -1) {
                        spanEditable(editable, i, end + 2, COLOR_COMMENT, Typeface.ITALIC)
                        i = end + 2
                    } else { i++ }
                } else {
                    val end = text.indexOf('\n', i)
                    val endIdx = if (end != -1) end else len
                    spanEditable(editable, i, endIdx, COLOR_COMMENT, Typeface.ITALIC)
                    i = endIdx
                }
                continue
            }

            // 字符串 "..." 或 '...' 或 [[...]]
            if (c == '"' || c == '\'') {
                val quote = c
                val end = text.indexOf(quote, i + 1)
                if (end != -1) {
                    spanEditable(editable, i, end + 1, COLOR_URL, Typeface.ITALIC)
                    i = end + 1
                } else { i++ }
                continue
            }
            if (c == '[' && i + 1 < len && text[i+1] == '[') {
                val end = text.indexOf("]]", i + 2)
                if (end != -1) {
                    spanEditable(editable, i, end + 2, COLOR_URL, Typeface.ITALIC)
                    i = end + 2
                } else { i++ }
                continue
            }

            // 数字
            if (c.isDigit()) {
                var j = i
                while (j < len && (text[j].isDigit() || text[j] == '.' || text[j] == 'x' ||
                    (text[j] in 'a'..'f') || (text[j] in 'A'..'F'))) j++
                spanEditable(editable, i, j, COLOR_MAGIC)
                i = j
                continue
            }

            // 标识符/关键字
            if (c.isLetter() || c == '_') {
                var j = i
                while (j < len && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                val word = text.substring(i, j)
                if (word in keywords) {
                    if (word in constants) {
                        spanEditable(editable, i, j, COLOR_MAGIC, Typeface.BOLD)
                    } else {
                        spanEditable(editable, i, j, COLOR_TEMPLATE, Typeface.BOLD)
                    }
                }
                i = j
                continue
            }

            i++
        }
    }

    /**
     * CSS 语法高亮
     */
    fun highlightCss(editable: Editable) {
        val text = editable.toString()
        val len = text.length
        if (len == 0) return
        clearSpans(editable)

        var i = 0
        while (i < len) {
            val c = text[i]

            // 注释 /* */
            if (c == '/' && i + 1 < len && text[i+1] == '*') {
                val end = text.indexOf("*/", i + 2)
                if (end != -1) {
                    spanEditable(editable, i, end + 2, COLOR_COMMENT, Typeface.ITALIC)
                    i = end + 2
                } else { i++ }
                continue
            }

            // 字符串
            if (c == '"' || c == '\'') {
                val quote = c
                val end = text.indexOf(quote, i + 1)
                if (end != -1) {
                    spanEditable(editable, i, end + 1, COLOR_URL, Typeface.ITALIC)
                    i = end + 1
                } else { i++ }
                continue
            }

            // 数字（含单位）
            if (c.isDigit() || (c == '.' && i + 1 < len && text[i+1].isDigit())) {
                var j = i
                while (j < len && (text[j].isDigit() || text[j] == '.' || text[j] == '%' ||
                    text[j] == 'p' || text[j] == 'x' || text[j] == 'e' || text[j] == 'm' ||
                    text[j] == 'r' || text[j] == 'c' || text[j] == 'v' || text[j] == 'h' ||
                    text[j] == 's' || text[j] == 'd' || text[j] == 'f' || text[j] == 'h')) j++
                spanEditable(editable, i, j, COLOR_MAGIC)
                i = j
                continue
            }

            // 选择器/属性名（字母、-、_）
            if (c.isLetter() || c == '-' || c == '_' || c == '#') {
                var j = i
                while (j < len && (text[j].isLetterOrDigit() || text[j] == '-' || text[j] == '_' || text[j] == '#')) j++
                val word = text.substring(i, j)
                // 属性名（后面跟冒号）
                if (j < len && text[j] == ':') {
                    spanEditable(editable, i, j, COLOR_TAG)
                } else if (word.startsWith("#") || word.startsWith(".") || word.startsWith(":")) {
                    // 选择器
                    spanEditable(editable, i, j, COLOR_TEMPLATE, Typeface.BOLD)
                } else {
                    // 关键字值
                    val cssKeywords = setOf("red", "blue", "green", "white", "black", "transparent",
                        "solid", "dashed", "dotted", "none", "block", "inline", "flex", "bold",
                        "normal", "center", "left", "right", "auto", "inherit")
                    if (word in cssKeywords) {
                        spanEditable(editable, i, j, COLOR_MAGIC)
                    }
                }
                i = j
                continue
            }

            i++
        }
    }

    /**
     * JSON 语法高亮
     */
    fun highlightJson(editable: Editable) {
        val text = editable.toString()
        val len = text.length
        if (len == 0) return
        clearSpans(editable)

        var i = 0
        while (i < len) {
            val c = text[i]

            // 字符串
            if (c == '"') {
                val end = text.indexOf('"', i + 1)
                if (end != -1) {
                    // 判断是否为 key（后面跟冒号）
                    var j = end + 1
                    while (j < len && text[j].isWhitespace()) j++
                    if (j < len && text[j] == ':') {
                        spanEditable(editable, i, end + 1, COLOR_TEMPLATE, Typeface.BOLD)
                    } else {
                        spanEditable(editable, i, end + 1, COLOR_URL, Typeface.ITALIC)
                    }
                    i = end + 1
                } else { i++ }
                continue
            }

            // 数字
            if (c.isDigit() || c == '-') {
                var j = i
                while (j < len && (text[j].isDigit() || text[j] == '.' || text[j] == '-' || text[j] == 'e' || text[j] == 'E' || text[j] == '+')) j++
                spanEditable(editable, i, j, COLOR_MAGIC)
                i = j
                continue
            }

            // 布尔/null
            if (c.isLetter()) {
                var j = i
                while (j < len && text[j].isLetter()) j++
                val word = text.substring(i, j)
                if (word == "true" || word == "false" || word == "null") {
                    spanEditable(editable, i, j, COLOR_MAGIC, Typeface.BOLD)
                }
                i = j
                continue
            }

            i++
        }
    }

    /**
     * Markdown 语法高亮
     */
    fun highlightMarkdown(editable: Editable) {
        val text = editable.toString()
        val len = text.length
        if (len == 0) return
        clearSpans(editable)

        var i = 0
        while (i < len) {
            val c = text[i]

            // 标题 # 
            if (c == '#') {
                var j = i
                while (j < len && text[j] == '#') j++
                if (j < len && text[j] == ' ') {
                    val end = text.indexOf('\n', j)
                    val endIdx = if (end != -1) end else len
                    spanEditable(editable, i, endIdx, COLOR_HEADING, Typeface.BOLD)
                    i = endIdx
                } else { i++ }
                continue
            }

            // 粗体 **text** 或 __text__
            if ((c == '*' && i + 1 < len && text[i+1] == '*') ||
                (c == '_' && i + 1 < len && text[i+1] == '_')) {
                val marker = c.toString().repeat(2)
                val end = text.indexOf(marker, i + 2)
                if (end != -1) {
                    spanEditable(editable, i, end + 2, COLOR_BOLD, Typeface.BOLD)
                    i = end + 2
                } else { i++ }
                continue
            }

            // 斜体 *text* 或 _text_
            if (c == '*' || c == '_') {
                val end = text.indexOf(c, i + 1)
                if (end != -1) {
                    spanEditable(editable, i, end + 1, COLOR_ITALIC, Typeface.ITALIC)
                    i = end + 1
                } else { i++ }
                continue
            }

            // 链接 [text](url)
            if (c == '[') {
                val end = text.indexOf(']', i + 1)
                if (end != -1) {
                    spanEditable(editable, i, end + 1, COLOR_LINK)
                    if (end + 1 < len && text[end+1] == '(') {
                        val urlEnd = text.indexOf(')', end + 2)
                        if (urlEnd != -1) {
                            spanEditable(editable, end + 1, urlEnd + 1, COLOR_URL)
                            i = urlEnd + 1
                        } else { i = end + 2 }
                    } else { i = end + 1 }
                } else { i++ }
                continue
            }

            // 行内代码 `code`
            if (c == '`') {
                val end = text.indexOf('`', i + 1)
                if (end != -1) {
                    spanEditable(editable, i, end + 1, COLOR_NOWIKI, bgColor = BG_NOWIKI)
                    i = end + 1
                } else { i++ }
                continue
            }

            // 引用 > 
            if (c == '>') {
                var j = i
                while (j < len && text[j] == '>') j++
                spanEditable(editable, i, j, COLOR_LIST, Typeface.BOLD)
                i = j
                continue
            }

            // 列表 - * +
            if (c == '-' || c == '*' || c == '+') {
                if (i == 0 || text[i-1] == '\n') {
                    spanEditable(editable, i, i + 1, COLOR_LIST, Typeface.BOLD)
                }
            }

            i++
        }
    }

    /** 在 Editable 上应用 span */
    private fun spanEditable(editable: Editable, start: Int, end: Int,
                             color: Int, typeface: Int = Typeface.NORMAL, bgColor: Int? = null) {
        try {
            editable.setSpan(ForegroundColorSpan(color), start, end,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (typeface != Typeface.NORMAL) {
                editable.setSpan(StyleSpan(typeface), start, end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            bgColor?.let {
                editable.setSpan(BackgroundColorSpan(it), start, end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } catch (_: Exception) {}
    }

    private fun span(sb: SpannableStringBuilder, start: Int, end: Int,
                     color: Int, typeface: Int = Typeface.NORMAL, bgColor: Int? = null) {
        try {
            sb.setSpan(ForegroundColorSpan(color), start, end,
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (typeface != Typeface.NORMAL) {
                sb.setSpan(StyleSpan(typeface), start, end,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            bgColor?.let {
                sb.setSpan(BackgroundColorSpan(it), start, end,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } catch (_: Exception) {}
    }

    /**
     * 查找模板结束位置，支持嵌套
     */
    private fun findTemplateEnd(text: String, start: Int): Int {
        var depth = 1
        var i = start
        while (i < text.length) {
            when {
                i + 1 < text.length && text[i] == '{' && text[i+1] == '{' -> { depth++; i += 2 }
                i + 1 < text.length && text[i] == '}' && text[i+1] == '}' -> {
                    depth--
                    if (depth == 0) return i
                    i += 2
                }
                else -> i++
            }
        }
        return -1
    }

    /**
     * 查找三重花括号结束位置，支持嵌套
     */
    private fun findTripleCurlyEnd(text: String, start: Int): Int {
        val stack = mutableListOf<Char>()
        var i = start
        while (i < text.length) {
            when {
                i + 2 < text.length && text[i] == '{' && text[i+1] == '{' && text[i+2] == '{' -> {
                    stack.add('{')
                    i += 3
                }
                i + 2 < text.length && text[i] == '}' && text[i+1] == '}' && text[i+2] == '}' -> {
                    if (stack.isEmpty()) return i
                    stack.removeAt(stack.size - 1)
                    i += 3
                }
                else -> i++
            }
        }
        return -1
    }

    /**
     * 查找链接中的管道符位置，跳过锚点 #
     * [[目标页面|显示文字]]
     */
    private fun findLinkPipe(text: String, start: Int, end: Int): Int {
        var i = start
        while (i < end) {
            if (text[i] == '|') {
                // 确保不是表格的分隔符（在表格上下文中是 |）
                return i
            }
            i++
        }
        return -1
    }

    /**
     * 高亮 HTML 标签中的属性值字符串
     */
    private fun highlightTagAttributes(sb: SpannableStringBuilder, text: String, tagStart: Int, tagEnd: Int) {
        var i = tagStart
        while (i < tagEnd) {
            // 查找属性值 "xxx" 或 'xxx'
            if (text[i] == '"' || text[i] == '\'') {
                val quote = text[i]
                val end = text.indexOf(quote, i + 1)
                if (end != -1 && end <= tagEnd) {
                    span(sb, i, end + 1, COLOR_TAG)
                    i = end + 1
                } else { i++ }
            } else { i++ }
        }
    }
}