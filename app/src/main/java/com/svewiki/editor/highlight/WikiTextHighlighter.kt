package com.svewiki.editor.highlight

import android.graphics.Typeface
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
    CSS("CSS", "CSS 语法高亮");

    companion object {
        fun fromNamespace(namespace: Int): SyntaxMode = when (namespace) {
            0, 1, 2, 3, 4, 5, 6, 7, 12, 13, 14, 15 -> WIKITEXT
            8 -> PLAIN_TEXT
            10, 11 -> WIKITEXT
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
     * 对文本应用 WikiText 高亮
     */
    fun highlight(text: String): SpannableStringBuilder {
        val sb = SpannableStringBuilder(text)
        val len = text.length
        var i = 0

        while (i < len) {
            when {
                // 注释 <!-- -->
                i + 3 < len && text[i] == '<' && text[i+1] == '!' &&
                text[i+2] == '-' && text[i+3] == '-' -> {
                    val end = text.indexOf("-->", i + 4)
                    if (end != -1) {
                        span(sb, i, end + 3, COLOR_COMMENT, Typeface.ITALIC)
                        i = end + 3
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
                        val end = text.indexOf("</" + (if (text[i+1]=='s') "syntaxhighlight" else "source") + ">", closeTag)
                        if (end != -1) {
                            span(sb, i, end + 3 + (if (text[i+1]=='s') 16 else 7), COLOR_NOWIKI, bgColor = BG_NOWIKI)
                            i = end + 3 + (if (text[i+1]=='s') 16 else 7)
                        } else { i++ }
                    } else { i++ }
                }

                // HTML 标签
                text[i] == '<' && i + 1 < len && text[i+1].isLetter() -> {
                    val end = text.indexOf('>', i + 1)
                    if (end != -1 && !text.substring(i, end+1).contains("nowiki") &&
                        !text.substring(i, end+1).contains("pre") &&
                        !text.substring(i, end+1).contains("source")) {
                        span(sb, i, end + 1, COLOR_TAG)
                        i = end + 1
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

                // 参数 {{{}}}
                i + 2 < len && text[i] == '{' && text[i+1] == '{' && text[i+2] == '{' -> {
                    val end = text.indexOf("}}}", i + 3)
                    if (end != -1) {
                        span(sb, i, end + 3, COLOR_PARAM, Typeface.BOLD)
                        i = end + 3
                    } else { i++ }
                }

                // 模板 {{}}
                text[i] == '{' && text[i+1] == '{' -> {
                    val end = findTemplateEnd(text, i + 2)
                    if (end != -1 && end > i + 1) {
                        span(sb, i, end + 2, COLOR_TEMPLATE, Typeface.BOLD)
                        val pipeIdx = text.indexOf('|', i + 2)
                        if (pipeIdx != -1 && pipeIdx < end) {
                            span(sb, pipeIdx, end + 2, COLOR_TEMPLATE)
                        }
                        i = end + 2
                    } else { i++ }
                }

                // 内部链接 [[]]
                text[i] == '[' && text[i+1] == '[' -> {
                    val end = text.indexOf("]]", i + 2)
                    if (end != -1) {
                        span(sb, i, end + 2, COLOR_LINK)
                        val pipeIdx = text.indexOf('|', i + 2)
                        if (pipeIdx != -1 && pipeIdx < end) {
                            span(sb, i + 2, pipeIdx, COLOR_LINK, Typeface.BOLD)
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
}