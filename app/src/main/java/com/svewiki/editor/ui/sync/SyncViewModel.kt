package com.svewiki.editor.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.svewiki.editor.data.Preferences
import com.svewiki.editor.sync.SyncEngine
import com.svewiki.editor.sync.SyncOverview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SyncUiState(
    val overview: SyncOverview? = null,
    val isWorking: Boolean = false,
    val statusText: String = ""
)

class SyncViewModel(
    private val syncEngine: SyncEngine,
    private val prefs: Preferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

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
            _uiState.update { it.copy(isWorking = true, statusText = "拉取全站中...") }
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
            _uiState.update { it.copy(isWorking = true, statusText = "增量同步中...") }
            val result = syncEngine.syncRecentChanges(
                onProgress = { _, done, total ->
                    _uiState.update { it.copy(statusText = "增量同步：$done/$total") }
                }
            )
            val overview = withContext(Dispatchers.IO) { syncEngine.getOverview() }
            _uiState.update {
                it.copy(overview = overview, isWorking = false, statusText = result.message)
            }
        }
    }
}