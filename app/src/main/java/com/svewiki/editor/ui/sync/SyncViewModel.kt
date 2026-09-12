package com.svewiki.editor.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.svewiki.editor.data.Preferences
import com.svewiki.editor.data.SyncProgress
import com.svewiki.editor.sync.SyncEngine
import com.svewiki.editor.sync.SyncOverview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SyncUiState(
    val overview: SyncOverview? = null,
    val isWorking: Boolean = false,
    val statusText: String = "",
    val lastError: Boolean = false
)

class SyncViewModel(
    private val syncEngine: SyncEngine,
    private val prefs: Preferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    val progress: StateFlow<SyncProgress> = syncEngine.progress.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SyncProgress()
    )

    init {
        refreshOverview()
    }

    fun refreshOverview() {
        viewModelScope.launch {
            val overview = withContext(Dispatchers.IO) { syncEngine.getOverview() }
            _uiState.update { it.copy(overview = overview) }
        }
    }

    fun pullAll() {
        if (_uiState.value.isWorking) return
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true, statusText = "拉取全站中...", lastError = false) }
            syncEngine.pullAllPages(
                onNamespaceProgress = { ns, done, total ->
                    _uiState.update { it.copy(statusText = "拉取 $ns：$done/$total") }
                },
                overwriteLocal = prefs.overwriteLocal
            )
            val overview = withContext(Dispatchers.IO) { syncEngine.getOverview() }
            _uiState.update {
                it.copy(overview = overview, isWorking = false, statusText = "全站拉取完成")
            }
        }
    }

    fun syncRecent() {
        if (_uiState.value.isWorking) return
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true, statusText = "增量同步中...", lastError = false) }
            val result = syncEngine.syncRecentChanges(
                onProgress = { _, done, total ->
                    _uiState.update { it.copy(statusText = "增量同步：$done/$total") }
                }
            )
            val overview = withContext(Dispatchers.IO) { syncEngine.getOverview() }
            val failed = result.error != null
            _uiState.update {
                it.copy(
                    overview = overview,
                    isWorking = false,
                    statusText = result.error ?: result.message,
                    lastError = failed
                )
            }
        }
    }

    fun pushModified() {
        if (_uiState.value.isWorking) return
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true, statusText = "推送本地修改...", lastError = false) }
            val result = withContext(Dispatchers.IO) {
                syncEngine.pushModifiedPages(summary = prefs.defaultSummary)
            }
            val overview = withContext(Dispatchers.IO) { syncEngine.getOverview() }
            val failed = result.failed.isNotEmpty()
            val msg = when {
                result.success.isEmpty() && result.failed.isEmpty() -> "没有需要推送的页面"
                failed -> "推送完成：成功 ${result.success.size}，失败 ${result.failed.size}"
                else -> "已推送 ${result.success.size} 页"
            }
            _uiState.update {
                it.copy(overview = overview, isWorking = false, statusText = msg, lastError = failed)
            }
        }
    }

    fun cancel() {
        syncEngine.cancel()
        _uiState.update { it.copy(isWorking = false, statusText = "已取消") }
    }
}
