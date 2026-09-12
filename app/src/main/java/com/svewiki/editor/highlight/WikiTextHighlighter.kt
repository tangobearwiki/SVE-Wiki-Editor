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
 * Compose AnnotatedString 语法高亮，供编辑器直接使用。
 */
object WikiTextHighlighter {

    private val colorTemplate = Color(0xFF6A4C93)
    private val colorLink = Color(0xFF2A7A6E)
    private val colorHeading = Color(0xFFB45309)
    private val colorComment = Color(0xFF8A8F8A)
    private val colorList = Color(0xFF3E7C4F)
    private val colorTag = Color(0xFFC05621)
    private val colorMagic = Color(0xFF5B4B8A)

    fun highlight(text: String, mode: SyntaxMode = SyntaxMode.WIKITEXT): AnnotatedString {
        if (mode == SyntaxMode.PLAIN_TEXT || text.length > 80_000) {
            return AnnotatedString(text)
        }
        return when (mode) {
            SyntaxMode.JSON -> highlightJson(text)
            SyntaxMode.LUA -> highlightLua(text)
            else -> highlightWikitext(text)
        }
    }

    private fun highlightWikitext(text: String): AnnotatedString = buildAnnotatedString {
        append(text)
        fun paint(regex: Regex, style: SpanStyle) {
            regex.findAll(text).forEach { m ->
                addStyle(style, m.range.first, m.range.last + 1)
            }
        }
        paint(Regex("<!--[\\s\\S]*?-->"), SpanStyle(color = colorComment, fontStyle = FontStyle.Italic))
        paint(Regex("<[^>]+>"), SpanStyle(color = colorTag))
        paint(Regex("\\{\\{[^}]+\\}\\}"), SpanStyle(color = colorTemplate))
        paint(Regex("\\[\\[[^\\]]+\\]\\]"), SpanStyle(color = colorLink))
        paint(Regex("\\[[^\\]]+\\]"), SpanStyle(color = colorLink))
        paint(Regex("(?m)^={2,6}.+={2,6}\\s*$"), SpanStyle(color = colorHeading, fontWeight = FontWeight.SemiBold))
        paint(Regex("(?m)^[*#;:]+"), SpanStyle(color = colorList, fontWeight = FontWeight.Medium))
        paint(Regex("'''[^']+'''"), SpanStyle(fontWeight = FontWeight.Bold))
        paint(Regex("''[^']+''"), SpanStyle(fontStyle = FontStyle.Italic))
        paint(Regex("__(?:TOC|NOTOC|NOEDITSECTION)__"), SpanStyle(color = colorMagic))
        paint(Regex("(?m)^\\{\\|.*$"), SpanStyle(color = colorTag))
        paint(Regex("(?m)^\\|\\}.*$"), SpanStyle(color = colorTag))
    }

    private fun highlightJson(text: String): AnnotatedString = buildAnnotatedString {
        append(text)
        Regex("\"[^\"]+\"\\s*:").findAll(text).forEach {
            addStyle(SpanStyle(color = colorTemplate), it.range.first, it.range.last + 1)
        }
        Regex("\"[^\"]*\"").findAll(text).forEach {
            addStyle(SpanStyle(color = colorLink), it.range.first, it.range.last + 1)
        }
    }

    private fun highlightLua(text: String): AnnotatedString = buildAnnotatedString {
        append(text)
        Regex("(?m)--.*$").findAll(text).forEach {
            addStyle(SpanStyle(color = colorComment, fontStyle = FontStyle.Italic), it.range.first, it.range.last + 1)
        }
        Regex("\\b(local|function|end|if|then|else|elseif|return|for|while|do|in|and|or|not)\\b")
            .findAll(text).forEach {
                addStyle(SpanStyle(color = colorTemplate, fontWeight = FontWeight.Medium), it.range.first, it.range.last + 1)
            }
        Regex("\"[^\"]*\"|'[^']*'").findAll(text).forEach {
            addStyle(SpanStyle(color = colorLink), it.range.first, it.range.last + 1)
        }
    }
}
