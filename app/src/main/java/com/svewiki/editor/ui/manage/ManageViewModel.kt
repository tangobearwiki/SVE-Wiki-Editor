package com.svewiki.editor.ui.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.svewiki.editor.data.LocalStorageManager
import com.svewiki.editor.data.PageMeta
import com.svewiki.editor.sync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 删除模式：0=仅云端 1=仅本地 2=全部 */
enum class DeleteMode(val code: Int) { CLOUD(0), LOCAL(1), BOTH(2) }

data class ManageUiState(
    val metas: List<PageMeta> = emptyList(),
    val searchQuery: String = "",
    val nsFilter: Int = -1,
    val selectedKeys: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val isDeleting: Boolean = false,
    val deleteMessage: String = ""
) {
    val filtered: List<PageMeta>
        get() = metas.filter { meta ->
            (nsFilter == -1 || meta.namespace == nsFilter) &&
                (searchQuery.isBlank() || meta.title.contains(searchQuery, ignoreCase = true))
        }
}

class ManageViewModel(
    private val storage: LocalStorageManager,
    private val syncEngine: SyncEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(ManageUiState())
    val uiState: StateFlow<ManageUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val metas = withContext(Dispatchers.IO) { storage.loadAllMetas() }
            _uiState.update { it.copy(metas = metas, isLoading = false) }
        }
    }

    fun onSearchChange(q: String) = _uiState.update { it.copy(searchQuery = q) }
    fun onNsFilterChange(ns: Int) = _uiState.update { it.copy(nsFilter = ns) }

    fun onToggleSelect(key: String) = _uiState.update { s ->
        s.copy(
            selectedKeys = if (key in s.selectedKeys) s.selectedKeys - key
            else s.selectedKeys + key
        )
    }

    fun clearSelection() = _uiState.update { it.copy(selectedKeys = emptySet()) }

    /**
     * 删除选中页面，支持 仅本地 / 仅云端 / 全部 三种模式。
     * 云端删除走 SyncEngine.deletePages（带 API 调用与日志）。
     */
    fun deleteSelected(mode: DeleteMode) {
        val s = _uiState.value
        val toDelete = s.filtered.filter { it.key in s.selectedKeys }
        if (toDelete.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true, deleteMessage = "") }
            val pairs = toDelete.map { it.title to it.namespace }
            val result = withContext(Dispatchers.IO) {
                syncEngine.deletePages(pairs, deleteMode = mode.code)
            }
            val metas = withContext(Dispatchers.IO) { storage.loadAllMetas() }
            val msg = buildString {
                append("删除完成：成功 ${result.success.size}")
                if (result.failed.isNotEmpty()) append("，失败 ${result.failed.size}")
            }
            _uiState.update {
                it.copy(
                    metas = metas,
                    selectedKeys = emptySet(),
                    isDeleting = false,
                    deleteMessage = msg
                )
            }
        }
    }
}
