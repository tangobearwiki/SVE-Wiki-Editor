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
        val tracker = storage.getRevisionTracker()

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
                    tracker.update(page.title, page.revisionId)
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
        tracker.lastUpdateTime = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .format(java.util.Date())
        storage.saveRevisionTracker(tracker)
        storage.appendLog("全站拉取", "完成，共 $totalProcessed 页")

        _progress.value = SyncProgress(
            status = SyncStatus.COMPLETED,
            message = "全站拉取完成！共 $totalProcessed 页",
            totalPages = totalPages,
            processedPages = totalProcessed
        )
    }

    /**
     * 增量同步：只拉取自上次同步以来变更的页面（对标 WiGit sync_recent_changes）
     * @param onProgress 进度回调
     * @param onConflict 冲突回调（本地 revisionId != 远程 revisionId）
     */
    suspend fun syncRecentChanges(
        onProgress: ((String, Int, Int) -> Unit)? = null,
        onConflict: ((String) -> Unit)? = null
    ): SyncResult {
        val tracker = storage.getRevisionTracker()
        val sinceTimestamp = tracker.lastUpdateTime

        if (sinceTimestamp.isEmpty()) {
            // 从未同步过，走全量
            return SyncResult(message = "从未同步，请先全量拉取")
        }

        _progress.value = SyncProgress(status = SyncStatus.FETCHING_PAGES, message = "获取最近变更...")

        // 1. 获取最近变更页面
        val changesResult = api.getRecentChanges(sinceTimestamp)
        if (changesResult.isFailure) {
            _progress.value = SyncProgress(status = SyncStatus.ERROR,
                message = "获取变更失败: ${changesResult.exceptionOrNull()?.message}")
            return SyncResult(error = changesResult.exceptionOrNull()?.message)
        }
        val changedTitles = changesResult.getOrThrow().toMutableSet()

        // 2. 获取日志事件（删除/移动/恢复）
        val logsResult = api.getRecentLogs(sinceTimestamp)
        if (logsResult.isSuccess) {
            val logs = logsResult.getOrThrow()
            for ((fromTitle, toTitle) in logs) {
                // 删除本地文件
                if (fromTitle != null) {
                    val ns = detectNamespaceFromTitle(fromTitle)
                    storage.deleteLocalPage(fromTitle, ns)
                    storage.removeRevision(fromTitle)
                }
                // 新页面加入更新列表
                if (toTitle != null && fromTitle != toTitle) {
                    changedTitles.add(toTitle)
                }
            }
        }

        if (changedTitles.isEmpty()) {
            val updateTime = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                .format(java.util.Date())
            tracker.lastUpdateTime = updateTime
            storage.saveRevisionTracker(tracker)
            _progress.value = SyncProgress(status = SyncStatus.COMPLETED, message = "无变更")
            return SyncResult(message = "无变更", totalProcessed = 0)
        }

        // 3. 拉取变更页面内容
        _progress.value = _progress.value.copy(message = "拉取 ${changedTitles.size} 个变更页面...",
            totalPages = changedTitles.size)
        onProgress?.invoke("变更页面", 0, changedTitles.size)

        val contentResult = api.fetchPagesContent(changedTitles.toList())
        var processed = 0
        if (contentResult.isSuccess) {
            val pages = contentResult.getOrThrow()
            for (page in pages) {
                val local = storage.loadPage(page.title, page.namespace)
                if (local != null && local.isModified) {
                    // 本地有未推送修改，保留本地版本但更新 revision 信息
                    storage.savePage(local.copy(
                        revisionId = page.revisionId,
                        lastSyncTime = System.currentTimeMillis(),
                        touched = page.touched
                    ))
                    onConflict?.invoke(page.title)
                } else {
                    storage.savePage(page)
                }
                // 更新修订追踪
                storage.updateRevision(page.title, page.revisionId)
                processed++
                onProgress?.invoke("变更页面", processed, changedTitles.size)
            }
        }

        // 4. 更新修订追踪时间
        val updateTime = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .format(java.util.Date())
        tracker.lastUpdateTime = updateTime
        storage.saveRevisionTracker(tracker)

        // 5. 更新元数据
        storage.saveMetadata(SyncMetadata(
            lastSyncTime = System.currentTimeMillis(),
            totalPages = storage.loadAllPages().size
        ))
        storage.appendLog("增量同步", "完成，共更新 $processed 页")

        _progress.value = SyncProgress(
            status = SyncStatus.COMPLETED,
            message = "增量同步完成！更新 $processed 页",
            processedPages = processed,
            totalPages = changedTitles.size
        )
        return SyncResult(message = "更新 $processed 页", totalProcessed = processed)
    }

    /**
     * 推送指定页面列表（带冲突检查，对标 WiGit push_changes_to_origin）
     */
    suspend fun pushPages(
        pages: List<LocalPage>,
        summary: String = "SVE Wiki 编辑器自动推送",
        onPageProgress: ((String, Boolean) -> Unit)? = null,
        checkConflict: Boolean = true
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
                // 冲突检查：比对远程 revisionId 和本地记录的 revisionId
                if (checkConflict) {
                    val localRevId = storage.getRevision(page.title)
                    if (localRevId != null && localRevId > 0) {
                        val remoteRevResult = api.getRemoteRevisionId(page.title)
                        if (remoteRevResult.isSuccess) {
                            val remoteRevId = remoteRevResult.getOrThrow()
                            if (remoteRevId != localRevId) {
                                // 远程有更新，冲突！
                                result.failed.add(page.title to "⚠️ 远程有更新（本地 rev=$localRevId, 远程 rev=$remoteRevId），请先同步")
                                onPageProgress?.invoke(page.title, false)
                                processed++
                                continue
                            }
                        }
                    }
                }

                // 使用 editPageWithRevisionId 推送并获取新修订号
                val editResult = api.editPageWithRevisionId(page.title, page.content, summary)
                if (editResult.isSuccess) {
                    val newRevId = editResult.getOrThrow()
                    result.success.add(page.title)
                    storage.markPushed(page.title, page.namespace, newRevId)
                    // 更新修订追踪
                    storage.updateRevision(page.title, newRevId)
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

    /**
     * 一键推送所有已修改的页面（带冲突检查）
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

        // 复用 pushPages 带冲突检查
        return pushPages(modifiedPages, summary, onPageProgress, checkConflict = true)
    }

    /**
     * 根据页面标题推断命名空间
     */
    private fun detectNamespaceFromTitle(title: String): Int = when {
        title.startsWith("讨论:") -> 1
        title.startsWith("用户讨论:") -> 3
        title.startsWith("用户:") -> 2
        title.startsWith("文件:") -> 6
        title.startsWith("分类:") -> 14
        title.startsWith("模板:") -> 10
        title.startsWith("模板讨论:") -> 11
        title.startsWith("帮助:") -> 12
        title.startsWith("帮助讨论:") -> 13
        title.startsWith("站务:") -> 4
        title.startsWith("站务讨论:") -> 5
        title.startsWith("MediaWiki:") -> 8
        title.startsWith("MediaWiki讨论:") -> 9
        title.startsWith("模块:") -> 828
        title.startsWith("模块讨论:") -> 829
        else -> 0
    }

    /**
     * 批量删除页面
     * @param pages 要删除的页面列表 (title, namespace)
     * @param reason 删除原因
     * @param deleteMode 删除模式：0=仅云端, 1=仅本地, 2=全部
     * @param onPageProgress 每页进度回调 (title, success)
     */
    suspend fun deletePages(
        pages: List<Pair<String, Int>>,
        reason: String = "批量删除",
        deleteMode: Int = 2,
        onPageProgress: (suspend (String, Boolean) -> Unit)? = null
    ): PushResult {
        val result = PushResult()
        var processed = 0

        _progress.value = SyncProgress(status = SyncStatus.PUSHING, message = "开始删除...")

        for ((title, ns) in pages) {
            _progress.value = _progress.value.copy(
                message = "删除中：$title",
                processedPages = processed + 1,
                totalPages = pages.size
            )

            try {
                var cloudSuccess = true
                var localSuccess = true

                // 云端删除
                if (deleteMode == 0 || deleteMode == 2) {
                    val deleteResult = api.deletePage(title, reason)
                    if (deleteResult.isFailure) {
                        cloudSuccess = false
                        result.failed.add(
                            title to (deleteResult.exceptionOrNull()?.message ?: "云端删除失败")
                        )
                    }
                }

                // 本地删除
                if (deleteMode == 1 || deleteMode == 2) {
                    localSuccess = storage.deleteLocalPage(title, ns)
                }

                if (cloudSuccess && localSuccess) {
                    result.success.add(title)
                    onPageProgress?.invoke(title, true)
                } else if (!cloudSuccess) {
                    // 云端失败但本地成功（云失败时已在 failed 添加）
                    onPageProgress?.invoke(title, false)
                } else {
                    // 本地失败
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

/**
 * 增量同步结果
 */
data class SyncResult(
    val message: String = "",
    val totalProcessed: Int = 0,
    val error: String? = null
)