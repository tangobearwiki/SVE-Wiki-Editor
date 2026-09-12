package com.svewiki.editor.sync

import com.svewiki.editor.api.SveWikiApi
import com.svewiki.editor.data.LocalPage
import com.svewiki.editor.data.LocalStorageManager
import com.svewiki.editor.data.PushResult
import com.svewiki.editor.data.SyncMetadata
import com.svewiki.editor.data.SyncProgress
import com.svewiki.editor.data.SyncStatus
import com.svewiki.editor.data.WikiNamespaces
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive

class SyncEngine(
    private val api: SveWikiApi,
    private val storage: LocalStorageManager
) {
    private val _progress = MutableStateFlow(SyncProgress())
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

    @Volatile
    private var job: Job? = null

    suspend fun pullAllPages(
        onNamespaceProgress: ((String, Int, Int) -> Unit)? = null,
        onPageFetched: ((String) -> Unit)? = null,
        overwriteLocal: Boolean = false
    ) = coroutineScope {
        job = coroutineContext[Job]
        _progress.value = SyncProgress(status = SyncStatus.FETCHING_NAMESPACES, message = "获取命名空间列表...")

        val namespacesResult = api.getNamespaces()
        if (namespacesResult.isFailure) {
            _progress.value = SyncProgress(
                status = SyncStatus.ERROR,
                message = "获取命名空间失败: ${namespacesResult.exceptionOrNull()?.message}"
            )
            return@coroutineScope
        }
        val targetNamespaces = namespacesResult.getOrThrow().filter { it.id in WikiNamespaces.SYNC_IDS }

        var totalProcessed = 0
        var totalPages = 0
        val tracker = storage.getRevisionTracker()

        for (ns in targetNamespaces) {
            if (!isActive) break
            val nsName = WikiNamespaces.getDisplayName(ns.id)
            _progress.value = SyncProgress(
                status = SyncStatus.FETCHING_PAGES,
                currentNamespace = nsName,
                message = "正在拉取：$nsName ...",
                processedPages = totalProcessed,
                totalPages = totalPages
            )
            onNamespaceProgress?.invoke(nsName, 0, 0)

            val titlesResult = api.listPagesInNamespace(ns.id)
            if (titlesResult.isFailure) {
                _progress.value = _progress.value.copy(
                    message = "拉取 $nsName 列表失败: ${titlesResult.exceptionOrNull()?.message}"
                )
                continue
            }
            val titles = titlesResult.getOrThrow()
            if (titles.isEmpty()) continue

            totalPages += titles.size
            _progress.value = _progress.value.copy(totalPages = totalPages)
            titles.forEach { onPageFetched?.invoke(it) }

            val contentResult = api.fetchPagesContent(titles)
            if (contentResult.isSuccess) {
                val pages = contentResult.getOrThrow()
                pages.forEach { page ->
                    val local = storage.loadPage(page.title, page.namespace)
                    if (local != null && local.isModified && !overwriteLocal) {
                        storage.savePage(
                            local.copy(
                                revisionId = page.revisionId,
                                lastSyncTime = System.currentTimeMillis(),
                                touched = page.touched
                            )
                        )
                    } else {
                        storage.savePage(page)
                    }
                    tracker.update(page.title, page.revisionId)
                }
                totalProcessed += pages.size
                _progress.value = _progress.value.copy(
                    processedPages = totalProcessed,
                    message = "已拉取 $nsName：${pages.size} 页"
                )
                onNamespaceProgress?.invoke(nsName, pages.size, titles.size)
            } else {
                _progress.value = _progress.value.copy(
                    message = "拉取 $nsName 内容失败: ${contentResult.exceptionOrNull()?.message}"
                )
                onNamespaceProgress?.invoke(nsName, 0, titles.size)
            }
        }

        storage.saveMetadata(
            SyncMetadata(lastSyncTime = System.currentTimeMillis(), totalPages = totalProcessed)
        )
        tracker.lastUpdateTime = LocalStorageManager.nowIsoUtc()
        storage.saveRevisionTracker(tracker)
        storage.appendLog("全站拉取", "完成，共 $totalProcessed 页")

        _progress.value = SyncProgress(
            status = SyncStatus.COMPLETED,
            message = "全站拉取完成，共 $totalProcessed 页",
            totalPages = totalPages,
            processedPages = totalProcessed
        )
    }

    suspend fun syncRecentChanges(
        onProgress: ((String, Int, Int) -> Unit)? = null,
        onConflict: ((String) -> Unit)? = null
    ): SyncResult = coroutineScope {
        job = coroutineContext[Job]
        val tracker = storage.getRevisionTracker()
        val sinceTimestamp = tracker.lastUpdateTime

        if (sinceTimestamp.isEmpty()) {
            return@coroutineScope SyncResult(message = "从未同步，请先全量拉取")
        }

        _progress.value = SyncProgress(status = SyncStatus.FETCHING_PAGES, message = "获取最近变更...")

        val changesResult = api.getRecentChanges(sinceTimestamp)
        if (changesResult.isFailure) {
            val err = changesResult.exceptionOrNull()?.message
            _progress.value = SyncProgress(status = SyncStatus.ERROR, message = "获取变更失败: $err")
            return@coroutineScope SyncResult(error = err)
        }
        val changedTitles = changesResult.getOrThrow().toMutableSet()

        val logsResult = api.getRecentLogs(sinceTimestamp)
        if (logsResult.isSuccess) {
            for ((fromTitle, toTitle) in logsResult.getOrThrow()) {
                if (fromTitle != null) {
                    storage.deleteLocalPage(fromTitle, WikiNamespaces.detectFromTitle(fromTitle))
                    storage.removeRevision(fromTitle)
                }
                if (toTitle != null && fromTitle != toTitle) {
                    changedTitles.add(toTitle)
                }
            }
        }

        if (changedTitles.isEmpty()) {
            tracker.lastUpdateTime = LocalStorageManager.nowIsoUtc()
            storage.saveRevisionTracker(tracker)
            _progress.value = SyncProgress(status = SyncStatus.COMPLETED, message = "无变更")
            return@coroutineScope SyncResult(message = "无变更", totalProcessed = 0)
        }

        _progress.value = _progress.value.copy(
            message = "拉取 ${changedTitles.size} 个变更页面...",
            totalPages = changedTitles.size
        )
        onProgress?.invoke("变更页面", 0, changedTitles.size)

        val contentResult = api.fetchPagesContent(changedTitles.toList())
        var processed = 0
        if (contentResult.isSuccess) {
            for (page in contentResult.getOrThrow()) {
                val local = storage.loadPage(page.title, page.namespace)
                if (local != null && local.isModified) {
                    storage.savePage(
                        local.copy(
                            revisionId = page.revisionId,
                            lastSyncTime = System.currentTimeMillis(),
                            touched = page.touched
                        )
                    )
                    onConflict?.invoke(page.title)
                } else {
                    storage.savePage(page)
                }
                storage.updateRevision(page.title, page.revisionId)
                processed++
                onProgress?.invoke("变更页面", processed, changedTitles.size)
                _progress.value = _progress.value.copy(processedPages = processed)
            }
        }

        tracker.lastUpdateTime = LocalStorageManager.nowIsoUtc()
        storage.saveRevisionTracker(tracker)
        val stats = storage.loadStats()
        storage.saveMetadata(
            SyncMetadata(lastSyncTime = System.currentTimeMillis(), totalPages = stats.totalPages)
        )
        storage.appendLog("增量同步", "完成，共更新 $processed 页")

        _progress.value = SyncProgress(
            status = SyncStatus.COMPLETED,
            message = "增量同步完成，更新 $processed 页",
            processedPages = processed,
            totalPages = changedTitles.size
        )
        SyncResult(message = "更新 $processed 页", totalProcessed = processed)
    }

    suspend fun pushPages(
        pages: List<LocalPage>,
        summary: String = "SVE Wiki 编辑器自动推送",
        onPageProgress: ((String, Boolean) -> Unit)? = null,
        checkConflict: Boolean = true
    ): PushResult {
        _progress.value = SyncProgress(status = SyncStatus.PUSHING, message = "准备推送...", totalPages = pages.size)
        val result = PushResult()
        var processed = 0

        for (page in pages) {
            _progress.value = _progress.value.copy(
                message = "推送中：${page.title}",
                processedPages = processed + 1
            )
            onPageProgress?.invoke(page.title, false)
            try {
                if (checkConflict) {
                    val localRevId = storage.getRevision(page.title)
                    if (localRevId != null && localRevId > 0) {
                        val remoteRevResult = api.getRemoteRevisionId(page.title)
                        if (remoteRevResult.isSuccess) {
                            val remoteRevId = remoteRevResult.getOrThrow()
                            if (remoteRevId != localRevId) {
                                result.failed.add(
                                    page.title to "远程有更新（本地 rev=$localRevId, 远程 rev=$remoteRevId），请先同步"
                                )
                                onPageProgress?.invoke(page.title, false)
                                processed++
                                continue
                            }
                        }
                    }
                }

                val editResult = api.editPageWithRevisionId(page.title, page.content, summary)
                if (editResult.isSuccess) {
                    val newRevId = editResult.getOrThrow()
                    result.success.add(page.title)
                    storage.markPushed(page.title, page.namespace, newRevId)
                    storage.updateRevision(page.title, newRevId)
                    onPageProgress?.invoke(page.title, true)
                } else {
                    result.failed.add(page.title to (editResult.exceptionOrNull()?.message ?: "编辑失败"))
                    onPageProgress?.invoke(page.title, false)
                }
            } catch (e: Exception) {
                result.failed.add(page.title to (e.message ?: "未知错误"))
                onPageProgress?.invoke(page.title, false)
            }
            processed++
        }

        storage.appendLog("推送选中", "成功 ${result.success.size}，失败 ${result.failed.size}")
        _progress.value = SyncProgress(
            status = SyncStatus.COMPLETED,
            message = "推送完成：成功 ${result.success.size}，失败 ${result.failed.size}",
            processedPages = processed,
            totalPages = pages.size
        )
        return result
    }

    suspend fun pushModifiedPages(
        summary: String = "SVE Wiki 编辑器自动推送",
        onPageProgress: ((String, Boolean) -> Unit)? = null
    ): PushResult {
        _progress.value = SyncProgress(status = SyncStatus.PUSHING, message = "准备推送...")
        val modifiedPages = storage.loadModifiedPages()
        if (modifiedPages.isEmpty()) {
            _progress.value = SyncProgress(status = SyncStatus.COMPLETED, message = "没有需要推送的页面")
            return PushResult()
        }
        return pushPages(modifiedPages, summary, onPageProgress, checkConflict = true)
    }

    suspend fun deletePages(
        pages: List<Pair<String, Int>>,
        reason: String = "批量删除",
        deleteMode: Int = 2,
        onPageProgress: (suspend (String, Boolean) -> Unit)? = null
    ): PushResult {
        val result = PushResult()
        var processed = 0
        _progress.value = SyncProgress(status = SyncStatus.PUSHING, message = "开始删除...", totalPages = pages.size)

        for ((title, ns) in pages) {
            _progress.value = _progress.value.copy(
                message = "删除中：$title",
                processedPages = processed + 1
            )
            try {
                var cloudSuccess = true
                var localSuccess = true
                if (deleteMode == 0 || deleteMode == 2) {
                    val deleteResult = api.deletePage(title, reason)
                    if (deleteResult.isFailure) {
                        cloudSuccess = false
                        result.failed.add(title to (deleteResult.exceptionOrNull()?.message ?: "云端删除失败"))
                    }
                }
                if (deleteMode == 1 || deleteMode == 2) {
                    localSuccess = storage.deleteLocalPage(title, ns)
                    if (localSuccess) storage.removeRevision(title)
                }
                if (cloudSuccess && localSuccess) {
                    result.success.add(title)
                    onPageProgress?.invoke(title, true)
                } else if (!cloudSuccess) {
                    onPageProgress?.invoke(title, false)
                } else {
                    result.failed.add(title to "本地文件删除失败")
                    onPageProgress?.invoke(title, false)
                }
            } catch (e: Exception) {
                result.failed.add(title to (e.message ?: "未知错误"))
                onPageProgress?.invoke(title, false)
            }
            processed++
        }

        storage.appendLog("批量删除", "成功 ${result.success.size}，失败 ${result.failed.size}")
        _progress.value = SyncProgress(
            status = SyncStatus.COMPLETED,
            message = "删除完成：成功 ${result.success.size}，失败 ${result.failed.size}",
            processedPages = processed,
            totalPages = pages.size
        )
        return result
    }

    fun getOverview(): SyncOverview {
        val metadata = storage.getMetadata()
        val stats = storage.loadStats()
        return SyncOverview(
            totalPages = stats.totalPages,
            modifiedCount = stats.modifiedCount,
            lastSyncTime = metadata.lastSyncTime,
            namespaces = stats.namespaces,
            totalSizeBytes = stats.totalSizeBytes
        )
    }

    fun cancel() {
        job?.cancel()
    }
}

data class SyncOverview(
    val totalPages: Int = 0,
    val modifiedCount: Int = 0,
    val lastSyncTime: Long = 0,
    val namespaces: List<Pair<String, Int>> = emptyList(),
    val totalSizeBytes: Long = 0
) {
    val totalSizeFormatted: String
        get() {
            if (totalSizeBytes < 1024) return "$totalSizeBytes B"
            val kb = totalSizeBytes / 1024
            if (kb < 1024) return "${kb}KB"
            return "%.1fMB".format(kb / 1024.0)
        }
}

data class SyncResult(
    val message: String = "",
    val totalProcessed: Int = 0,
    val error: String? = null
)
