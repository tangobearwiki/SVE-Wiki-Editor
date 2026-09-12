package com.svewiki.editor.util

/**
 * WikiDiff2 风格左右分栏差异对比工具
 */
object DiffUtil {

    enum class LineType { EQUAL, INSERT, DELETE, CHANGE }

    data class DiffLine(
        val type: LineType,
        val oldLine: String = "",
        val newLine: String = "",
        val oldSegments: List<Segment> = emptyList(),
        val newSegments: List<Segment> = emptyList()
    )

    data class Segment(
        val text: String,
        val isChanged: Boolean
    )

    fun compute(oldText: String, newText: String): List<DiffLine> {
        if (oldText == newText) return emptyList()
        val oldLines = oldText.split("\n")
        val newLines = newText.split("\n")
        return computeDiff(oldLines, newLines).map { line ->
            if (line.type == LineType.CHANGE) {
                val wordDiff = wordLevelDiff(line.oldLine, line.newLine)
                line.copy(oldSegments = wordDiff.first, newSegments = wordDiff.second)
            } else line
        }
    }

    fun diff(oldText: String, newText: String): String {
        if (oldText == newText) return "无差异"
        val sb = StringBuilder()
        for (line in compute(oldText, newText)) {
            when (line.type) {
                LineType.EQUAL -> sb.append("  ${line.oldLine}\n")
                LineType.INSERT -> sb.append("+ ${line.newLine}\n")
                LineType.DELETE -> sb.append("- ${line.oldLine}\n")
                LineType.CHANGE -> {
                    sb.append("- ${line.oldLine}\n")
                    sb.append("+ ${line.newLine}\n")
                }
            }
        }
        return sb.toString().trim()
    }

    fun diffHtml(oldText: String, newText: String, numContextLines: Int = 3): String {
        if (oldText == newText) {
            return "<div style='padding:16px;color:#666;text-align:center;font-size:14px'>内容无差异</div>"
        }
        val result = compute(oldText, newText)
        return buildDiffTable(result)
    }

    private fun computeDiff(old: List<String>, new: List<String>): List<DiffLine> {
        if (old.isEmpty() && new.isEmpty()) return emptyList()
        if (old.isEmpty()) return new.map { DiffLine(LineType.INSERT, newLine = it) }
        if (new.isEmpty()) return old.map { DiffLine(LineType.DELETE, oldLine = it) }

        val nFrom = old.size
        val nTo = new.size
        val xchanged = BooleanArray(nFrom) { true }
        val ychanged = BooleanArray(nTo) { true }

        var skip = 0
        while (skip < nFrom && skip < nTo && old[skip] == new[skip]) {
            xchanged[skip] = false
            ychanged[skip] = false
            skip++
        }

        var endskip = 0
        var xi = nFrom - 1
        var yi = nTo - 1
        while (xi > skip && yi > skip && old[xi] == new[yi]) {
            xchanged[xi] = false
            ychanged[yi] = false
            xi--
            yi--
            endskip++
        }

        if (skip < nFrom - endskip && skip < nTo - endskip) {
            computeLCS(old, new, skip, nFrom - endskip, skip, nTo - endskip, xchanged, ychanged)
        }

        val result = mutableListOf<DiffLine>()
        var i = 0
        var j = 0
        while (i < nFrom || j < nTo) {
            if (i < nFrom && j < nTo && !xchanged[i] && !ychanged[j]) {
                result.add(DiffLine(LineType.EQUAL, oldLine = old[i], newLine = new[j]))
                i++; j++
                continue
            }
            val dels = mutableListOf<String>()
            while (i < nFrom && xchanged[i]) {
                dels.add(old[i]); i++
            }
            val adds = mutableListOf<String>()
            while (j < nTo && ychanged[j]) {
                adds.add(new[j]); j++
            }
            when {
                dels.isNotEmpty() && adds.isNotEmpty() -> {
                    val pairCount = minOf(dels.size, adds.size)
                    for (k in 0 until pairCount) {
                        result.add(DiffLine(LineType.CHANGE, oldLine = dels[k], newLine = adds[k]))
                    }
                    for (k in pairCount until dels.size) {
                        result.add(DiffLine(LineType.DELETE, oldLine = dels[k]))
                    }
                    for (k in pairCount until adds.size) {
                        result.add(DiffLine(LineType.INSERT, newLine = adds[k]))
                    }
                }
                dels.isNotEmpty() -> dels.forEach { result.add(DiffLine(LineType.DELETE, oldLine = it)) }
                adds.isNotEmpty() -> adds.forEach { result.add(DiffLine(LineType.INSERT, newLine = it)) }
            }
        }
        return result
    }

    private fun computeLCS(
        old: List<String>, new: List<String>,
        xoff: Int, xlim: Int, yoff: Int, ylim: Int,
        xchanged: BooleanArray, ychanged: BooleanArray
    ) {
        val m = xlim - xoff
        val n = ylim - yoff
        if (m == 0 || n == 0) return

        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) {
            for (j in 0..n) {
                dp[i][j] = when {
                    i == 0 -> j
                    j == 0 -> i
                    old[xoff + i - 1] == new[yoff + j - 1] -> dp[i - 1][j - 1]
                    else -> 1 + minOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        var i = m
        var j = n
        while (i > 0 && j > 0) {
            when {
                old[xoff + i - 1] == new[yoff + j - 1] -> {
                    xchanged[xoff + i - 1] = false
                    ychanged[yoff + j - 1] = false
                    i--; j--
                }
                dp[i - 1][j] <= dp[i][j - 1] -> {
                    xchanged[xoff + i - 1] = true
                    i--
                }
                else -> {
                    ychanged[yoff + j - 1] = true
                    j--
                }
            }
        }
        while (i > 0) { xchanged[xoff + i - 1] = true; i-- }
        while (j > 0) { ychanged[yoff + j - 1] = true; j-- }
    }

    private fun wordLevelDiff(oldLine: String, newLine: String): Pair<List<Segment>, List<Segment>> {
        if (oldLine == newLine) {
            return Pair(listOf(Segment(oldLine, false)), listOf(Segment(newLine, false)))
        }
        val wordPattern = Regex("""([\w\p{L}]+|[^\w\p{L}\s])|(\s+)""")
        val oldWords = wordPattern.findAll(oldLine).map { it.value }.toList()
        val newWords = wordPattern.findAll(newLine).map { it.value }.toList()
        if (oldWords.isEmpty() && newWords.isEmpty()) return Pair(emptyList(), emptyList())
        if (oldWords.isEmpty()) return Pair(emptyList(), newWords.map { Segment(it, true) })
        if (newWords.isEmpty()) return Pair(oldWords.map { Segment(it, true) }, emptyList())

        val m = oldWords.size
        val n = newWords.size
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) {
            for (j in 0..n) {
                dp[i][j] = when {
                    i == 0 -> j
                    j == 0 -> i
                    oldWords[i - 1] == newWords[j - 1] -> dp[i - 1][j - 1]
                    else -> 1 + minOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        val oldSegs = mutableListOf<Segment>()
        val newSegs = mutableListOf<Segment>()
        var i = m
        var j = n
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && oldWords[i - 1] == newWords[j - 1] -> {
                    oldSegs.add(Segment(oldWords[i - 1], false))
                    newSegs.add(Segment(newWords[j - 1], false))
                    i--; j--
                }
                j > 0 && (i == 0 || dp[i][j - 1] < dp[i - 1][j]) -> {
                    newSegs.add(Segment(newWords[j - 1], true))
                    j--
                }
                i > 0 -> {
                    oldSegs.add(Segment(oldWords[i - 1], true))
                    i--
                }
            }
        }
        oldSegs.reverse()
        newSegs.reverse()
        return Pair(oldSegs, newSegs)
    }

    private fun buildDiffTable(lines: List<DiffLine>): String {
        val sb = StringBuilder()
        sb.append("<table style='width:100%;border-collapse:collapse;font-family:ui-monospace,monospace;font-size:12px'>")
        for (line in lines) {
            val (bgOld, bgNew) = when (line.type) {
                LineType.EQUAL -> "#fafafa" to "#fafafa"
                LineType.DELETE -> "#fdecea" to "#fafafa"
                LineType.INSERT -> "#fafafa" to "#eaf7ee"
                LineType.CHANGE -> "#fdecea" to "#eaf7ee"
            }
            sb.append("<tr>")
            sb.append("<td style='background:$bgOld;padding:2px 8px;width:50%;white-space:pre-wrap'>")
            sb.append(escape(line.oldLine))
            sb.append("</td>")
            sb.append("<td style='background:$bgNew;padding:2px 8px;width:50%;white-space:pre-wrap'>")
            sb.append(escape(line.newLine))
            sb.append("</td></tr>")
        }
        sb.append("</table>")
        return sb.toString()
    }

    private fun escape(s: String): String = s
        .replace("&", "&")
        .replace("<", "<")
        .replace(">", ">")
}
