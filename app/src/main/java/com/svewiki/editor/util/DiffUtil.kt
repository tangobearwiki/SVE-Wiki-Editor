package com.svewiki.editor.util

/**
 * 简单的文本 Diff 工具
 * 生成便于人类阅读的行级差异
 */
object DiffUtil {

    /**
     * 计算两段文本的行级差异
     * @param oldText 旧文本（服务器端）
     * @param newText 新文本（本地）
     * @return 差异描述字符串
     */
    fun diff(oldText: String, newText: String): String {
        val oldLines = oldText.split("\n")
        val newLines = newText.split("\n")

        val oldSet = oldLines.toSet()
        val newSet = newLines.toSet()

        val sb = StringBuilder()

        // 新增的行
        val added = newLineHandles(newLines, oldSet)
        if (added.isNotEmpty()) {
            sb.append("➕ 新增 ${added.size} 行:\n")
            added.forEach { sb.append("  + ").append(it).append("\n") }
            sb.append("\n")
        }

        // 删除的行
        val removed = oldLines.filter { it !in newSet }
        if (removed.isNotEmpty() && removed.size <= 20) {
            sb.append("➖ 删除 ${removed.size} 行:\n")
            removed.forEach { sb.append("  - ").append(it).append("\n") }
            sb.append("\n")
        }

        // 修改统计
        val changed = oldLines.size - oldLines.filter { it in newSet }.size
        if (changed > 0) {
            sb.append("✏️ 修改 $changed 行\n")
        }

        if (sb.isEmpty()) {
            return "无差异"
        }
        return sb.toString().trim()
    }

    private fun newLineHandles(newLines: List<String>, oldSet: Set<String>): List<String> {
        return newLines.filter { it !in oldSet }.take(20)
    }
}