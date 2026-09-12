package com.svewiki.editor.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 本地文件系统存储管理器
 * 按命名空间分文件夹，每个页面存为单独文件
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
        val safeName = escapeFilename(title)
        return File(namespaceDir(namespace), "$safeName.json")
    }

    private fun escapeFilename(filename: String): String {
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
        for ((char, escaped) in escapeMap) {
            result = result.replace(char, escaped)
        }
        return result
    }

    private fun metadataFile(): File = File(dataDir, "metadata.json")

    private fun writeAtomically(file: File, content: String) {
        file.parentFile?.mkdirs()
        val temporaryFile = File(file.parentFile, "${file.name}.tmp")
        temporaryFile.writeText(content, Charsets.UTF_8)
        if (!temporaryFile.renameTo(file)) {
            if (file.exists() && !file.delete()) {
                throw java.io.IOException("Unable to replace ${file.name}")
            }
            if (!temporaryFile.renameTo(file)) {
                throw java.io.IOException("Unable to write ${file.name}")
            }
        }
    }

    fun initStorage() {
        if (!dataDir.exists()) dataDir.mkdirs()
        metadataFile().parentFile?.mkdirs()
    }

    fun savePage(page: LocalPage) {
        val dir = namespaceDir(page.namespace)
        if (!dir.exists()) dir.mkdirs()
        writeAtomically(pageFile(page.title, page.namespace), gson.toJson(page))
    }

    fun savePages(pages: List<LocalPage>) {
        pages.forEach { savePage(it) }
    }

    fun loadPage(title: String, namespace: Int): LocalPage? {
        val file = pageFile(title, namespace)
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(Charsets.UTF_8), LocalPage::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun loadPageMeta(title: String, namespace: Int): PageMeta? {
        val file = pageFile(title, namespace)
        if (!file.exists()) return null
        return try {
            parseMetaFast(file)
        } catch (_: Exception) {
            null
        }
    }

    fun loadPagesByNamespace(namespace: Int): List<LocalPage> {
        val dir = namespaceDir(namespace)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    gson.fromJson(file.readText(Charsets.UTF_8), LocalPage::class.java)
                } catch (_: Exception) {
                    null
                }
            }
            .orEmpty()
    }

    fun loadModifiedPages(): List<LocalPage> {
        val result = mutableListOf<LocalPage>()
        WikiNamespaces.SYNC_IDS.forEach { ns ->
            result.addAll(loadPagesByNamespace(ns).filter { it.isModified })
        }
        return result
    }

    fun loadModifiedMetas(): List<PageMeta> = loadAllMetas().filter { it.isModified }

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
                        } catch (_: Exception) {
                        }
                    }
            }
        return result
    }

    fun loadAllMetas(): List<PageMeta> {
        val result = mutableListOf<PageMeta>()
        forEachPageFile { file ->
            try {
                parseMetaFast(file)?.let { result.add(it) }
            } catch (_: Exception) {
            }
        }
        return result
    }

    /**
     * 一次遍历得到统计，避免 getOverview 多次全量读盘。
     */
    fun loadStats(): StorageStats {
        val counts = linkedMapOf<Int, Int>()
        var total = 0
        var modified = 0
        var size = 0L
        forEachPageFile { file ->
            size += file.length()
            val meta = try {
                parseMetaFast(file)
            } catch (_: Exception) {
                null
            } ?: return@forEachPageFile
            total++
            if (meta.isModified) modified++
            counts[meta.namespace] = (counts[meta.namespace] ?: 0) + 1
        }
        return StorageStats(
            totalPages = total,
            modifiedCount = modified,
            totalSizeBytes = size,
            namespaces = counts.entries
                .sortedBy { it.key }
                .map { WikiNamespaces.getDisplayName(it.key) to it.value }
        )
    }

    private fun forEachPageFile(block: (File) -> Unit) {
        if (!dataDir.exists()) return
        dataDir.listFiles { f -> f.isDirectory }
            ?.forEach { dir ->
                dir.listFiles { f -> f.extension == "json" }
                    ?.forEach(block)
            }
    }

    /**
     * 用 JsonReader 跳过 content 字段，避免把全文读进内存。
     */
    private fun parseMetaFast(file: File): PageMeta? {
        JsonReader(file.bufferedReader(Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            var title: String? = null
            var namespace = 0
            var pageId = 0L
            var revisionId = 0L
            var lastSyncTime = 0L
            var lastModifiedTime = 0L
            var isModified = false
            var touched = ""
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "title" -> title = reader.nextString()
                    "namespace" -> namespace = reader.nextInt()
                    "pageId" -> pageId = reader.nextLong()
                    "revisionId" -> revisionId = reader.nextLong()
                    "lastSyncTime" -> lastSyncTime = reader.nextLong()
                    "lastModifiedTime" -> lastModifiedTime = reader.nextLong()
                    "isModified" -> isModified = reader.nextBoolean()
                    "touched" -> touched = reader.nextString()
                    "content" -> reader.skipValue()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            val t = title ?: return null
            return PageMeta(
                title = t,
                namespace = namespace,
                pageId = pageId,
                revisionId = revisionId,
                lastSyncTime = lastSyncTime,
                lastModifiedTime = lastModifiedTime,
                isModified = isModified,
                touched = touched,
                sizeBytes = file.length()
            )
        }
    }

    fun markModified(title: String, namespace: Int, newContent: String) {
        val page = loadPage(title, namespace) ?: LocalPage(
            title = title, namespace = namespace, content = newContent
        )
        savePage(
            page.copy(
                content = newContent,
                isModified = true,
                lastModifiedTime = System.currentTimeMillis()
            )
        )
    }

    fun markPushed(title: String, namespace: Int, newRevisionId: Long = -1) {
        val page = loadPage(title, namespace) ?: return
        savePage(
            page.copy(
                isModified = false,
                lastSyncTime = System.currentTimeMillis(),
                revisionId = if (newRevisionId >= 0) newRevisionId else page.revisionId
            )
        )
    }

    fun getPageCount(namespace: Int): Int {
        val dir = namespaceDir(namespace)
        if (!dir.exists()) return 0
        return dir.listFiles { f -> f.extension == "json" }?.size ?: 0
    }

    fun getNamespaceOverview(): List<Pair<String, Int>> = loadStats().namespaces

    fun getModifiedCount(): Int = loadStats().modifiedCount

    fun getMetadata(): SyncMetadata {
        val file = metadataFile()
        if (!file.exists()) return SyncMetadata()
        return try {
            gson.fromJson(file.readText(Charsets.UTF_8), SyncMetadata::class.java)
        } catch (_: Exception) {
            SyncMetadata()
        }
    }

    fun saveMetadata(metadata: SyncMetadata) {
        writeAtomically(metadataFile(), gson.toJson(metadata))
    }

    private fun revisionFile(): File = File(dataDir, "revision.json")

    fun getRevisionTracker(): RevisionTracker {
        val file = revisionFile()
        if (!file.exists()) return RevisionTracker()
        return try {
            gson.fromJson(file.readText(Charsets.UTF_8), RevisionTracker::class.java)
                ?: RevisionTracker()
        } catch (_: Exception) {
            RevisionTracker()
        }
    }

    fun saveRevisionTracker(tracker: RevisionTracker) {
        writeAtomically(revisionFile(), gson.toJson(tracker))
    }

    fun updateRevision(pageTitle: String, revisionId: Long) {
        val tracker = getRevisionTracker()
        tracker.update(pageTitle, revisionId)
        saveRevisionTracker(tracker)
    }

    fun getRevision(pageTitle: String): Long? = getRevisionTracker().getRevision(pageTitle)

    fun removeRevision(pageTitle: String) {
        val tracker = getRevisionTracker()
        tracker.remove(pageTitle)
        saveRevisionTracker(tracker)
    }

    fun getTotalSize(): Long = loadStats().totalSizeBytes

    fun deleteLocalPage(title: String, namespace: Int): Boolean {
        val file = pageFile(title, namespace)
        return if (file.exists()) file.delete() else false
    }

    fun deleteLocalPages(pages: List<Pair<String, Int>>): Int {
        var count = 0
        pages.forEach { (title, ns) ->
            if (deleteLocalPage(title, ns)) count++
        }
        if (count > 0) appendLog("批量删除本地", "删除了 $count 个页面")
        return count
    }

    fun clearAll() {
        if (dataDir.exists()) dataDir.deleteRecursively()
        initStorage()
    }

    fun appendLog(action: String, detail: String = "") {
        val logFile = File(dataDir, "operation_log.txt")
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$time] $action${if (detail.isNotEmpty()) " - $detail" else ""}\n"
        logFile.appendText(line)
    }

    fun readLog(): String {
        val logFile = File(dataDir, "operation_log.txt")
        if (!logFile.exists()) return ""
        return logFile.readLines().takeLast(50).joinToString("\n")
    }

    companion object {
        fun nowIsoUtc(): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            return sdf.format(Date())
        }
    }
}

data class SyncMetadata(
    val lastSyncTime: Long = 0,
    val totalPages: Int = 0,
    val version: Int = 1
)

data class RevisionTracker(
    var lastUpdateTime: String = "",
    var lastRevisionId: Long = -1,
    var revisions: MutableMap<String, Long> = mutableMapOf()
) {
    fun update(pageTitle: String, revisionId: Long) {
        revisions[pageTitle] = revisionId
        if (revisionId > lastRevisionId) lastRevisionId = revisionId
    }

    fun getRevision(pageTitle: String): Long? = revisions[pageTitle]

    fun isUpToDate(remoteRevisionId: Long): Boolean = lastRevisionId == remoteRevisionId

    fun remove(pageTitle: String) {
        revisions.remove(pageTitle)
    }
}

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
        828 -> "scribunto"
        else -> "wikitext"
    }
}
