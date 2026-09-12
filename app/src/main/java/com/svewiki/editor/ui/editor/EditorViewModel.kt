package com.svewiki.editor.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.svewiki.editor.api.SveWikiApi
import com.svewiki.editor.data.LocalPage
import com.svewiki.editor.data.LocalStorageManager
import com.svewiki.editor.data.Preferences
import com.svewiki.editor.data.WikiNamespaces
import com.svewiki.editor.highlight.SyntaxMode
import com.svewiki.editor.sync.SyncEngine
import com.svewiki.editor.util.DiffUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EditorUiState(
    val title: String = "",
    val summary: String = "",
    val content: String = "",
    val status: String = "就绪",
    val statusKind: StatusKind = StatusKind.IDLE,
    val isWorking: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val showDiff: Boolean = false,
    val diffLines: List<DiffUtil.DiffLine> = emptyList(),
    val syntaxMode: SyntaxMode = SyntaxMode.WIKITEXT,
    val highlightEnabled: Boolean = true
) {
    val charCount: Int get() = content.length
    val lineCount: Int get() = if (content.isEmpty()) 0 else content.count { it == '\n' } + 1
}

enum class StatusKind { IDLE, WORKING, SUCCESS, ERROR }

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

    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private var lastUndoAt = 0L
    private var autoSaveJob: Job? = null
    private var openedNamespace: Int = 0

    fun onTitleChange(value: String) {
        _uiState.update {
            it.copy(
                title = value,
                syntaxMode = SyntaxMode.fromNamespace(WikiNamespaces.detectFromTitle(value))
            )
        }
    }

    fun onSummaryChange(value: String) = _uiState.update { it.copy(summary = value) }

    fun toggleHighlight() = _uiState.update { it.copy(highlightEnabled = !it.highlightEnabled) }

    fun dismissDiff() = _uiState.update { it.copy(showDiff = false, diffLines = emptyList()) }

    fun onContentChange(value: String) {
        val current = _uiState.value.content
        if (value == current) return
        val now = System.currentTimeMillis()
        val coalescible = (now - lastUndoAt) < 400 &&
            kotlin.math.abs(value.length - current.length) <= 1 &&
            undoStack.isNotEmpty()
        if (!coalescible) {
            undoStack.addLast(current)
            while (undoStack.size > 80) undoStack.removeFirst()
        }
        lastUndoAt = now
        redoStack.clear()
        _uiState.update {
            it.copy(
                content = value,
                canUndo = undoStack.isNotEmpty(),
                canRedo = false
            )
        }
        scheduleAutoSave()
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

    fun openPage(page: LocalPage) {
        undoStack.clear()
        redoStack.clear()
        openedNamespace = page.namespace
        _uiState.update {
            it.copy(
                title = page.title,
                content = page.content,
                status = "已打开：${page.title}",
                statusKind = StatusKind.SUCCESS,
                canUndo = false,
                canRedo = false,
                showDiff = false,
                syntaxMode = SyntaxMode.fromNamespace(page.namespace)
            )
        }
    }

    fun readPage() {
        val title = _uiState.value.title.trim()
        if (title.isBlank()) {
            setStatus("请输入标题", StatusKind.ERROR)
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true, status = "读取中...", statusKind = StatusKind.WORKING) }
            val result = withContext(Dispatchers.IO) { api.readPage(title) }
            result.onSuccess { page ->
                undoStack.clear()
                redoStack.clear()
                openedNamespace = WikiNamespaces.detectFromTitle(page.title)
                _uiState.update {
                    it.copy(
                        isWorking = false,
                        content = page.content,
                        title = page.title,
                        status = "已读取：${page.title}",
                        statusKind = StatusKind.SUCCESS,
                        canUndo = false,
                        canRedo = false,
                        syntaxMode = SyntaxMode.fromNamespace(openedNamespace)
                    )
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(isWorking = false, status = "读取失败：${e.message}", statusKind = StatusKind.ERROR)
                }
            }
        }
    }

    fun saveDraft() {
        val title = _uiState.value.title.trim()
        if (title.isBlank()) {
            setStatus("请输入标题", StatusKind.ERROR)
            return
        }
        viewModelScope.launch {
            persistDraft()
            if (prefs.autoPushEnabled) {
                pushPage()
            } else {
                setStatus("已保存到本地：$title（待推送）", StatusKind.SUCCESS)
            }
        }
    }

    fun pushPage() {
        val s = _uiState.value
        val title = s.title.trim()
        if (title.isBlank()) {
            setStatus("请输入标题", StatusKind.ERROR)
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true, status = "推送中...", statusKind = StatusKind.WORKING) }
            persistDraft()
            val result = withContext(Dispatchers.IO) {
                val ns = namespaceOf(title)
                val page = storage.loadPage(title, ns)?.copy(
                    content = s.content, isModified = true
                ) ?: LocalPage(
                    title = title, namespace = ns, content = s.content, isModified = true
                )
                syncEngine.pushPages(
                    pages = listOf(page),
                    summary = s.summary.ifBlank { prefs.defaultSummary }
                )
            }
            val ok = result.success.contains(title)
            _uiState.update {
                it.copy(
                    isWorking = false,
                    status = if (ok) "推送成功：$title"
                    else "推送失败：${result.failed.firstOrNull()?.second ?: "未知"}",
                    statusKind = if (ok) StatusKind.SUCCESS else StatusKind.ERROR
                )
            }
        }
    }

    fun compareWithServer() {
        val title = _uiState.value.title.trim()
        if (title.isBlank()) {
            setStatus("请输入标题", StatusKind.ERROR)
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true, status = "比对中...", statusKind = StatusKind.WORKING) }
            val result = withContext(Dispatchers.IO) { api.fetchPageForDiff(title) }
            result.onSuccess { remote ->
                val lines = DiffUtil.compute(remote.content, _uiState.value.content)
                _uiState.update {
                    it.copy(
                        isWorking = false,
                        showDiff = true,
                        diffLines = lines,
                        status = if (lines.isEmpty()) "与服务器一致" else "发现 ${lines.count { d -> d.type != DiffUtil.LineType.EQUAL }} 处差异",
                        statusKind = StatusKind.SUCCESS
                    )
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(isWorking = false, status = "比对失败：${e.message}", statusKind = StatusKind.ERROR)
                }
            }
        }
    }

    private suspend fun persistDraft() {
        val s = _uiState.value
        val title = s.title.trim()
        withContext(Dispatchers.IO) {
            storage.markModified(title, namespaceOf(title), s.content)
        }
    }

    private fun scheduleAutoSave() {
        if (!prefs.autoSaveDraft) return
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(30_000)
            val title = _uiState.value.title.trim()
            if (title.isNotBlank()) {
                persistDraft()
                setStatus("已自动保存草稿", StatusKind.SUCCESS)
            }
        }
    }

    private fun namespaceOf(title: String): Int {
        val detected = WikiNamespaces.detectFromTitle(title)
        return if (detected != WikiNamespaces.MAIN) detected else openedNamespace
    }

    private fun setStatus(msg: String, kind: StatusKind) =
        _uiState.update { it.copy(status = msg, statusKind = kind) }
}
