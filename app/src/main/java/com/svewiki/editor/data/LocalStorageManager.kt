package com.svewiki.editor.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地文件系统存储管理器
 * 按命名空间分文件夹，每个页面存为单独文件
 *
 * 目录结构：
 * SVE_WIKI_DATA/
 *   ├── metadata.json          (同步元数据)
 *   ├── Main/                  (命名空间 0: 主空间)
 *   │   ├── 页面标题.json
 *   │   └── ...
 *   ├── Template/              (命名空间 10: 模板)
 *   │   └── ...
 *   ├── Category/              (命名空间 14: 分类)
 *   └── ...
 */
class LocalStorageManager(private val context: Context) {
    private val gson = Gson()
    private val dataDir: File
        get() = File(context.filesDir, "sve_wiki_data")

    private fun namespaceDir(namespace: Int): File {
        val dirName = WikiNamespaces.getDisplayName(namespace).replace(" ", "_")
        return File(dataDir, dirName)
    }

    private fun pageFile(title: String, namespace: Int): File {
        // 对标 WiGit EscapeUtils：用 %XX 转义非法字符，防止路径注入
        val safeName = escapeFilename(title)
        return File(namespaceDir(namespace), "$safeName.json")
    }

    /**
     * 文件名安全转义（对标 WiGit EscapeUtils.escape_filename）
     * 将路径分隔符和 Windows 禁止字符转为 %XX
     */
    private fun escapeFilename(filename: String): String {
        val escapeMap = linkedMapOf(
            "%" to "%25",  // 先转义百分号
            "/" to "%2F",
            "\\" to "%5C",
            "\"" to "%22",
            "*" to "%2A",
            ":" to "%3A",
            "<" to "%3C",
            ">" to "%3E",
            "?" to "%3F",
            "|" to "%7C"
        )
        var result = filename
        for ((char, escaped) in escapeMap) {
            result = result.replace(char, escaped)
        }
        return result
    }

    /**
     * 文件名反转义（对标 WiGit EscapeUtils.unescape_filename）
     */
    private fun unescapeFilename(filename: String): String {
        val escapeMap = linkedMapOf(
            "%" to "%25",
            "/" to "%2F",
            "\\" to "%5C",
            "\"" to "%22",
            "*" to "%2A",
            ":" to "%3A",
            "<" to "%3C",
            ">" to "%3E",
            "?" to "%3F",
            "|" to "%7C"
        )
        var result = filename
        // 反转义：逆序替换
        for ((char, escaped) in escapeMap.toList().reversed()) {
            result = result.replace(escaped, char)
        }
        return result
    }

    private fun metadataFile(): File = File(dataDir, "metadata.json")

    /**
     * 初始化存储目录
     */
    fun initStorage() {
        if (!dataDir.exists()) dataDir.mkdirs()
        metadataFile().parentFile?.mkdirs()
    }

    /**
     * 保存页面到本地文件系统
     */
    fun savePage(page: LocalPage) {
        val dir = namespaceDir(page.namespace)
        if (!dir.exists()) dir.mkdirs()

        val file = pageFile(page.title, page.namespace)
        val json = gson.toJson(page)
        file.writeText(json, Charsets.UTF_8)
    }

    /**
     * 批量保存页面
     */
    fun savePages(pages: List<LocalPage>) {
        pages.forEach { savePage(it) }
    }

    /**
     * 读取单个页面
     */
    fun loadPage(title: String, namespace: Int): LocalPage? {
        val file = pageFile(title, namespace)
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(Charsets.UTF_8), LocalPage::class.java)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取某命名空间下的所有页面
     */
    fun loadPagesByNamespace(namespace: Int): List<LocalPage> {
        val dir = namespaceDir(namespace)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    gson.fromJson(file.readText(Charsets.UTF_8), LocalPage::class.java)
                } catch (e: Exception) { null }
            }
            ?: emptyList()
    }

    /**
     * 获取所有已修改（未推送）的页面
     */
    fun loadModifiedPages(): List<LocalPage> {
        val result = mutableListOf<LocalPage>()
        // 包含所有支持同步的命名空间，含模块(828)
        val namespaces = listOf(0, 2, 4, 6, 8, 10, 12, 14, 828)
        namespaces.forEach { ns ->
            result.addAll(loadPagesByNamespace(ns).filter { it.isModified })
        }
        return result
    }

    /**
     * 获取所有已存储的页面（逐命名空间遍历）
     */
    fun loadAllPages(): List<LocalPage> {
        val result = mutableListOf<LocalPage>()
        if (!dataDir.exists()) return result
        dataDir.listFiles { f -> f.isDirectory }
            ?.forEach { dir ->
                dir.listFiles { f -> f.extension == "json" }
                    ?.forEach { file ->
                        try {
                            gson.fromJson(file.readText(Charsets.UTF_8), LocalPage::class.java)
                                ?.let { result.add(it) }
                        } catch (_: Exception) {}
                    }
            }
        return result
    }

    /**
     * 标记页面为已修改
     */
    fun markModified(title: String, namespace: Int, newContent: String) {
        val page = loadPage(title, namespace) ?: LocalPage(
            title = title, namespace = namespace, content = newContent
        )
        val updated = page.copy(
            content = newContent,
            isModified = true,
            lastModifiedTime = System.currentTimeMillis()
        )
        savePage(updated)
    }

    /**
     * 标记页面为已推送（重置修改标记）
     * @param newRevisionId 服务器返回的新版本号，不传则用本地值
     */
    fun markPushed(title: String, namespace: Int, newRevisionId: Long = -1) {
        val page = loadPage(title, namespace) ?: return
        val updated = page.copy(
            isModified = false,
            lastSyncTime = System.currentTimeMillis(),
            revisionId = if (newRevisionId >= 0) newRevisionId else page.revisionId
        )
        savePage(updated)
    }

    /**
     * 获取某个命名空间的页面数量
     */
    fun getPageCount(namespace: Int): Int {
        val dir = namespaceDir(namespace)
        if (!dir.exists()) return 0
        return dir.listFiles { f -> f.extension == "json" }?.size ?: 0
    }

    /**
     * 获取所有命名空间的概览
     */
    fun getNamespaceOverview(): List<Pair<String, Int>> {
        val result = mutableListOf<Pair<String, Int>>()
        val namespaces = listOf(0, 2, 4, 6, 8, 10, 12, 14, 828)
        namespaces.forEach { ns ->
            val count = getPageCount(ns)
            if (count > 0) {
                result.add(WikiNamespaces.getDisplayName(ns) to count)
            }
        }
        return result
    }

    /**
     * 获取已修改页面数量
     */
    fun getModifiedCount(): Int {
        return loadAllPages().count { it.isModified }
    }

    /**
     * 获取同步元数据
     */
    fun getMetadata(): SyncMetadata {
        val file = metadataFile()
        if (!file.exists()) return SyncMetadata()
        return try {
            gson.fromJson(file.readText(Charsets.UTF_8), SyncMetadata::class.java)
        } catch (e: Exception) {
            SyncMetadata()
        }
    }

    /**
     * 保存同步元数据
     */
    fun saveMetadata(metadata: SyncMetadata) {
        metadataFile().writeText(gson.toJson(metadata), Charsets.UTF_8)
    }

    // ============ 修订版本追踪 ============

    private fun revisionFile(): File = File(dataDir, "revision.json")

    /**
     * 获取修订版本追踪数据
     */
    fun getRevisionTracker(): RevisionTracker {
        val file = revisionFile()
        if (!file.exists()) return RevisionTracker()
        return try {
            gson.fromJson(file.readText(Charsets.UTF_8), RevisionTracker::class.java)
                ?: RevisionTracker()
        } catch (e: Exception) {
            RevisionTracker()
        }
    }

    /**
     * 保存修订版本追踪数据
     */
    fun saveRevisionTracker(tracker: RevisionTracker) {
        revisionFile().writeText(gson.toJson(tracker), Charsets.UTF_8)
    }

    /**
     * 更新单页修订号
     */
    fun updateRevision(pageTitle: String, revisionId: Long) {
        val tracker = getRevisionTracker()
        tracker.update(pageTitle, revisionId)
        saveRevisionTracker(tracker)
    }

    /**
     * 获取单页修订号
     */
    fun getRevision(pageTitle: String): Long? {
        return getRevisionTracker().getRevision(pageTitle)
    }

    /**
     * 移除单页修订记录（页面被删除时）
     */
    fun removeRevision(pageTitle: String) {
        val tracker = getRevisionTracker()
        tracker.remove(pageTitle)
        saveRevisionTracker(tracker)
    }

    /**
     * 获取数据总大小
     */
    fun getTotalSize(): Long {
        var size = 0L
        if (!dataDir.exists()) return 0
        dataDir.listFiles { f -> f.isDirectory }
            ?.forEach { dir ->
                dir.listFiles { f -> f.extension == "json" }
                    ?.forEach { size += it.length() }
            }
        return size
    }

    /**
     * 删除单个本地页面文件
     */
    fun deleteLocalPage(title: String, namespace: Int): Boolean {
        val file = pageFile(title, namespace)
        return if (file.exists()) file.delete() else false
    }

    /**
     * 批量删除本地页面
     */
    fun deleteLocalPages(pages: List<Pair<String, Int>>): Int {
        var count = 0
        pages.forEach { (title, ns) ->
            if (deleteLocalPage(title, ns)) count++
        }
        if (count > 0) {
            appendLog("批量删除本地", "删除了 $count 个页面")
        }
        return count
    }

    /**
     * 清除所有数据
     */
    fun clearAll() {
        if (dataDir.exists()) {
            dataDir.deleteRecursively()
        }
        initStorage()
    }

    /**
     * 追加操作日志
     */
    fun appendLog(action: String, detail: String = "") {
        val logFile = File(dataDir, "operation_log.txt")
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$time] $action${if (detail.isNotEmpty()) " - $detail" else ""}\n"
        logFile.appendText(line)
    }

    /**
     * 读取操作日志（最新 50 条）
     */
    fun readLog(): String {
        val logFile = File(dataDir, "operation_log.txt")
        if (!logFile.exists()) return ""
        val lines = logFile.readLines()
        // 取最后 50 条
        return lines.takeLast(50).joinToString("\n")
    }
}

/**
  * 同步元数据
  */
data class SyncMetadata(
    val lastSyncTime: Long = 0,
    val totalPages: Int = 0,
    val version: Int = 1
)

/**
 * 修订版本追踪（对标 WiGit Revision）
 * 记录每页的 revisionId，用于增量同步和冲突检测
 */
data class RevisionTracker(
    var lastUpdateTime: String = "",      // ISO8601 上次同步时间
    var lastRevisionId: Long = -1,        // 最后一次处理的修订号
    var revisions: MutableMap<String, Long> = mutableMapOf()  // pageTitle -> revisionId
) {
    /**
     * 更新某页的修订号
     */
    fun update(pageTitle: String, revisionId: Long) {
        revisions[pageTitle] = revisionId
        if (revisionId > lastRevisionId) {
            lastRevisionId = revisionId
        }
    }

    /**
     * 获取某页的修订号
     */
    fun getRevision(pageTitle: String): Long? = revisions[pageTitle]

    /**
     * 判断本地是否与远程同步
     */
    fun isUpToDate(remoteRevisionId: Long): Boolean {
        return lastRevisionId == remoteRevisionId
    }

    /**
     * 移除某页的修订记录（页面被删除时调用）
     */
    fun remove(pageTitle: String) {
        revisions.remove(pageTitle)
    }
}

/**
 * 内容模型映射（对标 WiGit get_content_model）
 * 根据内容模型名返回文件扩展名
 */
object ContentModel {
    private val modelToExt = mapOf(
        "wikitext" to "mediawiki",
        "json" to "json",
        "javascript" to "js",
        "css" to "css",
        "scribunto" to "lua"
    )

    fun getExtension(model: String): String = modelToExt[model.lowercase()] ?: "txt"

    fun getModelFromNamespace(namespace: Int): String = when (namespace) {
        828 -> "scribunto"  // 模块空间 → Lua
        else -> "wikitext"
    }
}