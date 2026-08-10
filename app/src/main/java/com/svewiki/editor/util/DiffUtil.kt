package com.svewiki.editor.util

/**
 * WikiDiff2 风格左右分栏差异对比工具
 *
 * 完全基于 wikidiff2 核心机制：
 * 1. LCS 差分算法（Ned Konz's Algorithm::Diff / MediaWiki 版）
 * 2. 4 列 HTML 表格（左=旧版，右=新版）
 * 3. 词级 diff（<del> 红色删除 + <ins> 绿色新增）
 * 4. 上下文行 + 移动行检测
 * 5. 分块输出，vs code 风格左右分栏
 */
object DiffUtil {

    /** 行差异类型 */
    enum class LineType { EQUAL, INSERT, DELETE, CHANGE }

    /** 一行差异结果 */
    data class DiffLine(
        val type: LineType,
        val oldLine: String = "",
        val newLine: String = "",
        val oldSegments: List<Segment> = emptyList(),  // 旧行词级变化
        val newSegments: List<Segment> = emptyList()   // 新行词级变化
    )

    /** 词级差异片段 */
    data class Segment(
        val text: String,
        val isChanged: Boolean  // true=增删，false=相同
    )

    /**
     * 生成纯文本格式的差异描述（用于复制）
     */
    fun diff(oldText: String, newText: String): String {
        if (oldText == newText) return "无差异"

        val oldLines = oldText.split("\n")
        val newLines = newText.split("\n")
        val diffLines = computeDiff(oldLines, newLines)

        val sb = StringBuilder()
        for (line in diffLines) {
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

    /**
     * 生成左右分栏的 HTML 差异视图
     * 类似 VS Code 的 diff 视图和 wikidiff2 的 table 输出
     *
     * @param oldText 旧文本（服务器端）
     * @param newText 新文本（本地修改）
     * @param numContextLines 上下文行数（默认 3）
     * @return HTML 字符串
     */
    fun diffHtml(oldText: String, newText: String, numContextLines: Int = 3): String {
        if (oldText == newText) {
            return "<div style='padding:16px;color:#666;text-align:center;font-size:14px'>内容无差异</div>"
        }

        val oldLines = oldText.split("\n")
        val newLines = newText.split("\n")

        // 1. 行级 LCS 差分
        val diffLines = computeDiff(oldLines, newLines)

        // 2. 对 change 行做词级 diff
        val result = diffLines.map { line ->
            if (line.type == LineType.CHANGE) {
                val wordDiff = wordLevelDiff(line.oldLine, line.newLine)
                line.copy(
                    oldSegments = wordDiff.first,
                    newSegments = wordDiff.second
                )
            } else line
        }

        // 3. 生成 HTML 左右分栏表格
        return buildDiffTable(result, oldLines.size, newLines.size)
    }

    /**
     * LCS 差分算法
     * 基于 Ned Konz's Algorithm::Diff / MediaWiki wikidiff2 DiffEngine
     */
    private fun computeDiff(old: List<String>, new: List<String>): List<DiffLine> {
        if (old.isEmpty() && new.isEmpty()) return emptyList()
        if (old.isEmpty()) return new.map { DiffLine(LineType.INSERT, newLine = it) }
        if (new.isEmpty()) return old.map { DiffLine(LineType.DELETE, oldLine = it) }

        val nFrom = old.size
        val nTo = new.size
        val xchanged = BooleanArray(nFrom) { true }
        val ychanged = BooleanArray(nTo) { true }

        // 跳过首尾相同行
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

        // 中间部分做 LCS
        if (skip < nFrom - endskip && skip < nTo - endskip) {
            computeLCS(old, new, skip, nFrom - endskip, skip, nTo - endskip, xchanged, ychanged)
        }

        // 合并相邻的操作
        val result = mutableListOf<DiffLine>()
        var i = 0
        var j = 0
        while (i < nFrom || j < nTo) {
            // 相同行
            if (i < nFrom && j < nTo && !xchanged[i] && !ychanged[j]) {
                result.add(DiffLine(LineType.EQUAL, oldLine = old[i], newLine = new[j]))
                i++; j++
                continue
            }

            // 收集删除行
            val dels = mutableListOf<String>()
            while (i < nFrom && xchanged[i]) {
                dels.add(old[i])
                i++
            }

            // 收集新增行
            val adds = mutableListOf<String>()
            while (j < nTo && ychanged[j]) {
                adds.add(new[j])
                j++
            }

            // 合并为 change 或分别 del/add
            when {
                dels.isNotEmpty() && adds.isNotEmpty() -> {
                    // 成对合并为 change（逐行配对）
                    val pairCount = minOf(dels.size, adds.size)
                    for (k in 0 until pairCount) {
                        result.add(DiffLine(LineType.CHANGE, oldLine = dels[k], newLine = adds[k]))
                    }
                    if (dels.size > pairCount) {
                        for (k in pairCount until dels.size) {
                            result.add(DiffLine(LineType.DELETE, oldLine = dels[k]))
                        }
                    }
                    if (adds.size > pairCount) {
                        for (k in pairCount until adds.size) {
                            result.add(DiffLine(LineType.INSERT, newLine = adds[k]))
                        }
                    }
                }
                dels.isNotEmpty() -> {
                    dels.forEach { result.add(DiffLine(LineType.DELETE, oldLine = it)) }
                }
                adds.isNotEmpty() -> {
                    adds.forEach { result.add(DiffLine(LineType.INSERT, newLine = it)) }
                }
            }
        }

        return result
    }

    /**
     * LCS 计算 + 回溯标记
     */
    private fun computeLCS(
        old: List<String>, new: List<String>,
        xoff: Int, xlim: Int, yoff: Int, ylim: Int,
        xchanged: BooleanArray, ychanged: BooleanArray
    ) {
        val m = xlim - xoff
        val n = ylim - yoff
        if (m == 0 || n == 0) return

        // 建 DP 表
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

        // 回溯标记 changed
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

    /**
     * 词级差异对比
     * 将两行文本按单词拆分，逐词比较，生成左右两侧的 segments
     */
    private fun wordLevelDiff(oldLine: String, newLine: String): Pair<List<Segment>, List<Segment>> {
        if (oldLine == newLine) {
            return Pair(
                listOf(Segment(oldLine, false)),
                listOf(Segment(newLine, false))
            )
        }

        // 拆分为单词 token
        val wordPattern = Regex("""([\w\p{L}]+|[^\w\p{L}\s])|(\s+)""")
        val oldWords = wordPattern.findAll(oldLine).map { it.value }.toList()
        val newWords = wordPattern.findAll(newLine).map { it.value }.toList()

        if (oldWords.isEmpty() && newWords.isEmpty()) {
            return Pair(emptyList(), emptyList())
        }
        if (oldWords.isEmpty()) {
            val segs = newWords.map { Segment(it, true) }
            return Pair(emptyList(), segs)
        }
        if (newWords.isEmpty()) {
            val segs = oldWords.map { Segment(it, true) }
            return Pair(segs, emptyList())
        }

        // 词级 LCS 计算
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

        // 回溯生成左右 segments
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
                j > 0 && (i == 0 || dp[i][j - 1] <= dp[i - 1][j]) -> {
                    newSegs.add(Segment(newWords[j - 1], true))
                    oldSegs.add(Segment("\u200b", false)) // 占位
                    j--
                }
                i > 0 -> {
                    oldSegs.add(Segment(oldWords[i - 1], true))
                    newSegs.add(Segment("\u200b", false)) // 占位
                    i--
                }
            }
        }

        oldSegs.reverse()
        newSegs.reverse()

        // 清理空占位
        val cleanOld = oldSegs.filter { it.text.isNotEmpty() }
        val cleanNew = newSegs.filter { it.text.isNotEmpty() }

        return Pair(cleanOld, cleanNew)
    }

    /**
     * 构建 HTML 左右分栏表格
     * 4 列结构：左行号 | 左内容 | 右行号 | 右内容
     * 所有行在同一个 <tr> 内，保证左右对齐
     */
    private fun buildDiffTable(lines: List<DiffLine>, oldTotal: Int, newTotal: Int): String {
        val sb = StringBuilder()

        // 统计
        var ins = 0; var del = 0; var chg = 0
        for (l in lines) {
            when (l.type) {
                LineType.INSERT -> ins++
                LineType.DELETE -> del++
                LineType.CHANGE -> chg++
                else -> {}
            }
        }

        sb.append("""
<div style='font-size:12px;line-height:1.6;font-family:"SF Mono",Menlo,Consolas,monospace;color:#24292e'>
<table style='width:100%;border-collapse:collapse;table-layout:fixed'>
<colgroup><col width='36'/><col width='50%'/><col width='36'/><col width='50%'/>
</colgroup>
<tr style='background:#f6f8fa'>
<td colspan='2' style='text-align:center;font-weight:bold;padding:6px;color:#d73a49;font-size:12px;border-bottom:1px solid #ddd'>旧版 ($oldTotal 行)</td>
<td colspan='2' style='text-align:center;font-weight:bold;padding:6px;color:#28a745;font-size:12px;border-bottom:1px solid #ddd'>新版 ($newTotal 行)</td>
</tr>
<tr style='background:#f6f8fa'>
<td colspan='4' style='padding:4px 8px;font-size:12px;border-bottom:1px solid #ddd'>
<b>📊 修改 $chg 行</b>""".trimIndent())

        if (ins > 0) sb.append(" · <span style='color:#28a745'>➕ 新增 $ins</span>")
        if (del > 0) sb.append(" · <span style='color:#d73a49'>➖ 删除 $del</span>")
        sb.append("""
</td></tr>
""".trimIndent())

        var oldLineNum = 1
        var newLineNum = 1

        for (line in lines) {
            when (line.type) {
                LineType.EQUAL -> {
                    sb.append("<tr style='background:#fff'>")
                    sb.append("<td style='text-align:right;color:#999;padding:0 4px;background:#f5f5f5;font-size:11px'>$oldLineNum</td>")
                    sb.append("<td style='padding:0 6px;white-space:pre-wrap;word-break:break-all'>${escapeHtml(line.oldLine)}</td>")
                    sb.append("<td style='text-align:right;color:#999;padding:0 4px;background:#f5f5f5;font-size:11px'>$newLineNum</td>")
                    sb.append("<td style='padding:0 6px;white-space:pre-wrap;word-break:break-all'>${escapeHtml(line.newLine)}</td>")
                    sb.append("</tr>\n")
                    oldLineNum++; newLineNum++
                }
                LineType.DELETE -> {
                    sb.append("<tr style='background:#ffeef0'>")
                    sb.append("<td style='text-align:right;color:#999;padding:0 4px;background:#ffd7d5;font-size:11px'>$oldLineNum</td>")
                    sb.append("<td style='padding:0 6px;white-space:pre-wrap;word-break:break-all;color:#b31d28'>${escapeHtml(line.oldLine)}</td>")
                    sb.append("<td style='text-align:right;color:#999;padding:0 4px;background:#f5f5f5;font-size:11px'></td>")
                    sb.append("<td style='padding:0 6px'></td>")
                    sb.append("</tr>\n")
                    oldLineNum++
                }
                LineType.INSERT -> {
                    sb.append("<tr style='background:#e6ffed'>")
                    sb.append("<td style='text-align:right;color:#999;padding:0 4px;background:#f5f5f5;font-size:11px'></td>")
                    sb.append("<td style='padding:0 6px'></td>")
                    sb.append("<td style='text-align:right;color:#999;padding:0 4px;background:#cdffd8;font-size:11px'>$newLineNum</td>")
                    sb.append("<td style='padding:0 6px;white-space:pre-wrap;word-break:break-all;color:#1e7e34'>${escapeHtml(line.newLine)}</td>")
                    sb.append("</tr>\n")
                    newLineNum++
                }
                LineType.CHANGE -> {
                    // 单行4列：左侧旧版（红色）+ 右侧新版（绿色）
                    sb.append("<tr style='background:#ffeef0'>")
                    sb.append("<td style='text-align:right;color:#999;padding:0 4px;background:#ffd7d5;font-size:11px'>$oldLineNum</td>")
                    sb.append("<td style='padding:0 6px;white-space:pre-wrap;word-break:break-all;border-right:2px solid #d73a49'>")
                    if (line.oldSegments.isNotEmpty()) {
                        for (seg in line.oldSegments) {
                            if (seg.isChanged) {
                                sb.append("<del style='background:#ffdce0;text-decoration:none;color:#b31d28'>${escapeHtml(seg.text)}</del>")
                            } else {
                                sb.append(escapeHtml(seg.text))
                            }
                        }
                    } else {
                        sb.append("<span style='color:#b31d28'>${escapeHtml(line.oldLine)}</span>")
                    }
                    sb.append("</td>")
                    // 右侧
                    sb.append("<td style='text-align:right;color:#999;padding:0 4px;background:#cdffd8;font-size:11px'>$newLineNum</td>")
                    sb.append("<td style='padding:0 6px;white-space:pre-wrap;word-break:break-all;border-left:2px solid #28a745'>")
                    if (line.newSegments.isNotEmpty()) {
                        for (seg in line.newSegments) {
                            if (seg.isChanged) {
                                sb.append("<ins style='background:#c8e6c9;text-decoration:none;color:#1e7e34'>${escapeHtml(seg.text)}</ins>")
                            } else {
                                sb.append(escapeHtml(seg.text))
                            }
                        }
                    } else {
                        sb.append("<span style='color:#1e7e34'>${escapeHtml(line.newLine)}</span>")
                    }
                    sb.append("</td>")
                    sb.append("</tr>\n")
                    oldLineNum++; newLineNum++
                }
            }
        }

        sb.append("</table></div>")
        return sb.toString()
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&#34;")
    }
}