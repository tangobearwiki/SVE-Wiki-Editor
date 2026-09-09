package com.svewiki.editor.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.svewiki.editor.data.LocalPage
import com.svewiki.editor.data.LocalStorageManager
import com.svewiki.editor.data.Preferences
import com.svewiki.editor.api.SveWikiApi
import com.svewiki.editor.sync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 编辑器状态 */
data class EditorUiState(
    val title: String = "",
    val summary: String = "",
    val content: String = "",
    val status: String = "就绪",
    val isWorking: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false
)

class EditorViewModel(
    private val api: SveWikiApi,
    private val storage: LocalStorageManager,
    private val prefs: Preferences,
    private val syncEngine: SyncEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        EditorUiState(summary = prefs.defaultSummary)
    )
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    // 撤销 / 重做栈（仅内容维度）
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()

    fun onTitleChange(value: String) = _uiState.update { it.copy(title = value) }
    fun onSummaryChange(value: String) = _uiState.update { it.copy(summary = value) }

    fun onContentChange(value: String) {
        val current = _uiState.value.content
        if (value != current) {
            undoStack.addLast(current)
            redoStack.clear()
        }
        _uiState.update {
            it.copy(
                content = value,
                canUndo = undoStack.isNotEmpty(),
                canRedo = false
            )
        }
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(_uiState.value.content)
        val restored = undoStack.removeLast()
        _uiState.update {
            it.copy(
                content = restored,
                canUndo = undoStack.isNotEmpty(),
                canRedo = true
            )
        }
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(_uiState.value.content)
        val restored = redoStack.removeLast()
        _uiState.update {
            it.copy(
                content = restored,
                canUndo = true,
                canRedo = redoStack.isNotEmpty()
            )
        }
    }

    /** 打开一个本地页面（来自跨屏导航） */
    fun openPage(page: LocalPage) {
        undoStack.clear()
        redoStack.clear()
        _uiState.update {
            it.copy(
                title = page.title,
                content = page.content,
                status = "已打开：${page.title}",
                canUndo = false,
                canRedo = false
            )
        }
    }

    /** 从服务器读取页面 */
    fun readPage() {
        val title = _uiState.value.title.trim()
        if (title.isBlank()) {
            setStatus("请输入标题")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true, status = "读取中...") }
            val result = withContext(Dispatchers.IO) { api.readPage(title) }
            result.onSuccess { page ->
                undoStack.clear()
                redoStack.clear()
                _uiState.update {
                    it.copy(
                        isWorking = false,
                        content = page.content,
                        status = "已读取：${page.title}",
                        canUndo = false,
                        canRedo = false
                    )
                }
            }.onFailure { e ->
                _uiState.update { it.copy(isWorking = false, status = "❌ 读取失败：${e.message}") }
            }
        }
    }

    /** 保存草稿到本地 */
    fun saveDraft() {
        val s = _uiState.value
        val title = s.title.trim()
        if (title.isBlank()) {
            setStatus("请输入标题")
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                storage.markModified(title, detectNamespace(title), s.content)
            }
            setStatus("已保存到本地：$title（待推送）")
        }
    }

    /** 推送当前页面到服务器 */
    fun pushPage() {
        val s = _uiState.value
        val title = s.title.trim()
        if (title.isBlank()) {
            setStatus("请输入标题")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true, status = "推送中...") }
            val result = withContext(Dispatchers.IO) {
                val ns = detectNamespace(title)
                val page = storage.loadPage(title, ns)?.copy(
                    content = s.content, isModified = true
                ) ?: LocalPage(
                    title = title, namespace = ns,
                    content = s.content, isModified = true
                )
                syncEngine.pushPages(
                    pages = listOf(page),
                    summary = s.summary.ifBlank { "自动编辑" }
                )
            }
            val status = if (result.success.contains(title)) {
                "✅ 推送成功：$title"
            } else {
                "❌ 推送失败：${result.failed.firstOrNull()?.second ?: "未知"}"
            }
            _uiState.update { it.copy(isWorking = false, status = status) }
        }
    }

    private fun setStatus(msg: String) = _uiState.update { it.copy(status = msg) }
}

/** 根据标题推断命名空间 */
fun detectNamespace(title: String): Int = when {
    title.startsWith("模板:") || title.startsWith("Template:") -> 10
    title.startsWith("分类:") || title.startsWith("Category:") -> 14
    title.startsWith("文件:") || title.startsWith("File:") -> 6
    title.startsWith("用户:") || title.startsWith("User:") -> 2
    title.startsWith("MediaWiki:") -> 8
    title.startsWith("帮助:") || title.startsWith("Help:") -> 12
    title.startsWith("站务:") || title.startsWith("Project:") -> 4
    title.startsWith("模块:") || title.startsWith("Module:") -> 828
    title.startsWith("模板讨论:") -> 11
    title.startsWith("帮助讨论:") -> 13
    title.startsWith("MediaWiki讨论:") -> 9
    title.startsWith("站务讨论:") -> 5
    title.startsWith("模块讨论:") -> 829
    else -> 0
}