package com.svewiki.editor.sync

import com.svewiki.editor.api.SveWikiApi
import com.svewiki.editor.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SVE Wiki 全站同步引擎
 */
class SyncEngine(
    private val api: SveWikiApi,
    private val storage: LocalStorageManager
) {
    private val _progress = MutableStateFlow(SyncProgress())
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

    private var job: Job? = null

    /**
     * 一键拉取全站所有命名空间的页面
     * 先拉取标题列表（实时显示），再批量拉取内容
     */
    suspend fun pullAllPages(
        onNamespaceProgress: ((String, Int, Int) -> Unit)? = null,
        onPageFetched: ((String) -> Unit)? = null,
        overwriteLocal: Boolean = false
    ) {
        _progress.value = SyncProgress(status = SyncStatus.FETCHING_NAMESPACES, message = "获取命名空间列表...")

        // 1. 获取命名空间列表
        val namespacesResult = api.getNamespaces()
        if (namespacesResult.isFailure) {
            _progress.value = SyncProgress(status = SyncStatus.ERROR, message = "获取命名空间失败: ${namespacesResult.exceptionOrNull()?.message}")
            return
        }
        val namespaces = namespacesResult.getOrThrow()
        val targetNamespaces = namespaces.filter { it.id in listOf(0, 2, 4, 6, 8, 10, 12, 14, 828) }

        var totalProcessed = 0
        var totalPages = 0

        // 2. 逐命名空间拉取
        for (ns in targetNamespaces) {
            val nsName = WikiNamespaces.getDisplayName(ns.id)
            _progress.value = SyncProgress(
                status = SyncStatus.FETCHING_PAGES,
                currentNamespace = nsName,
                message = "正在拉取：$nsName ...",
                processedPages = totalProcessed
            )
            onNamespaceProgress?.invoke(nsName, 0, 0)

            // 获取该命名空间的所有页面标题
            val titlesResult = api.listPagesInNamespace(ns.id)
            if (titlesResult.isFailure) {
                // 失败时仍继续下一个命名空间
                _progress.value = _progress.value.copy(
                    message = "拉取 $nsName 列表失败: ${titlesResult.exceptionOrNull()?.message}"
                )
                continue
            }
            val titles = titlesResult.getOrThrow()
            if (titles.isEmpty()) continue

            totalPages += titles.size
            _progress.value = _progress.value.copy(totalPages = totalPages)

            // ★ 关键：拉取到标题后立即回调，让列表先显示出来
            titles.forEach { title ->
                onPageFetched?.invoke(title)
            }

            // 批量拉取页面内容，逐个保存
            val contentResult = api.fetchPagesContent(titles)
            if (contentResult.isSuccess) {
                val pages = contentResult.getOrThrow()
                // 存储到本地
                pages.forEach { page ->
                    val local = storage.loadPage(page.title, page.namespace)
                    if (local != null && local.isModified && !overwriteLocal) {
                        // 本地有未推送修改且不覆盖，保留本地版本
                        storage.savePage(local.copy(
                            revisionId = page.revisionId,
                            lastSyncTime = System.currentTimeMillis(),
                            touched = page.touched
                        ))
                    } else {
                        storage.savePage(page)
                    }
                }
                totalProcessed += pages.size
                _progress.value = _progress.value.copy(
                    processedPages = totalProcessed,
                    message = "已拉取 $nsName：${pages.size} 页"
                )
                onNamespaceProgress?.invoke(nsName, pages.size, titles.size)
            } else {
                // 内容拉取失败，但标题已经通过回调显示了
                _progress.value = _progress.value.copy(
                    message = "拉取 $nsName 内容失败: ${contentResult.exceptionOrNull()?.message}"
                )
                // 仍然标记命名空间已完成（标题已显示，只是内容未存）
                totalProcessed += titles.size
                onNamespaceProgress?.invoke(nsName, titles.size, titles.size)
            }
        }

        // 3. 保存元数据
        storage.saveMetadata(SyncMetadata(
            lastSyncTime = System.currentTimeMillis(),
            totalPages = totalProcessed
        ))
        storage.appendLog("全站拉取", "完成，共 $totalProcessed 页")

        _progress.value = SyncProgress(
            status = SyncStatus.COMPLETED,
            message = "全站拉取完成！共 $totalProcessed 页",
            totalPages = totalPages,
            processedPages = totalProcessed
        )
    }

    /**
     * 一键推送所有已修改的页面
     */
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

        val result = PushResult()
        var processed = 0

        for (page in modifiedPages) {
            _progress.value = _progress.value.copy(
                message = "推送中：${page.title}",
                processedPages = processed + 1,
                totalPages = modifiedPages.size
            )
            onPageProgress?.invoke(page.title, false)

            try {
                val editResult = api.editPage(page.title, page.content, summary)
                if (editResult.isSuccess) {
                    result.success.add(page.title)
                    storage.markPushed(page.title, page.namespace)
                    onPageProgress?.invoke(page.title, true)
                } else {
                    result.failed.add(
                        page.title to (editResult.exceptionOrNull()?.message ?: "编辑失败")
                    )
                    onPageProgress?.invoke(page.title, false)
                }
            } catch (e: Exception) {
                result.failed.add(page.title to (e.message ?: "未知错误"))
                onPageProgress?.invoke(page.title, false)
            }
            processed++
        }

        _progress.value = SyncProgress(
            status = SyncStatus.COMPLETED,
            message = "推送完成：成功 ${result.success.size}，失败 ${result.failed.size}，跳过 ${result.skipped.size}",
            processedPages = processed,
            totalPages = modifiedPages.size
        )
        storage.appendLog("推送修改", "成功 ${result.success.size}，失败 ${result.failed.size}")

        return result
    }

    /**
     * 推送指定页面列表
     */
    suspend fun pushPages(
        pages: List<LocalPage>,
        summary: String = "SVE Wiki 编辑器自动推送",
        onPageProgress: ((String, Boolean) -> Unit)? = null
    ): PushResult {
        _progress.value = SyncProgress(status = SyncStatus.PUSHING, message = "准备推送...")

        val result = PushResult()
        var processed = 0

        for (page in pages) {
            _progress.value = _progress.value.copy(
                message = "推送中：${page.title}",
                processedPages = processed + 1,
                totalPages = pages.size
            )
            onPageProgress?.invoke(page.title, false)

            try {
                val editResult = api.editPage(page.title, page.content, summary)
                if (editResult.isSuccess) {
                    result.success.add(page.title)
                    storage.markPushed(page.title, page.namespace)
                    onPageProgress?.invoke(page.title, true)
                } else {
                    result.failed.add(
                        page.title to (editResult.exceptionOrNull()?.message ?: "编辑失败")
                    )
                    onPageProgress?.invoke(page.title, false)
                }
            } catch (e: Exception) {
                result.failed.add(page.title to (e.message ?: "未知错误"))
                onPageProgress?.invoke(page.title, false)
            }
            processed++
        }

        storage.appendLog("推送选中", "成功 ${result.success.size}，失败 ${result.failed.size}")
        return result
    }

    fun getOverview(): SyncOverview {
        val metadata = storage.getMetadata()
        val totalPages = storage.loadAllPages().size
        val modifiedCount = storage.getModifiedCount()
        val namespaceOverview = storage.getNamespaceOverview()
        val totalSize = storage.getTotalSize()

        return SyncOverview(
            totalPages = totalPages,
            modifiedCount = modifiedCount,
            lastSyncTime = metadata.lastSyncTime,
            namespaces = namespaceOverview,
            totalSizeBytes = totalSize
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
            val mb = kb / 1024.0
            return "%.1fMB".format(mb)
        }
}