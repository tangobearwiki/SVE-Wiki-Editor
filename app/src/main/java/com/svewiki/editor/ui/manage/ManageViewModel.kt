package com.svewiki.editor.ui.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.svewiki.editor.data.LocalStorageManager
import com.svewiki.editor.data.PageMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ManageUiState(
    val metas: List<PageMeta> = emptyList(),
    val searchQuery: String = "",
    val nsFilter: Int = -1,
    val selectedKeys: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val isDeleting: Boolean = false
) {
    val filtered: List<PageMeta>
        get() = metas.filter { meta ->
            (nsFilter == -1 || meta.namespace == nsFilter) &&
                (searchQuery.isBlank() || meta.title.contains(searchQuery, ignoreCase = true))
        }
}

class ManageViewModel(
    private val storage: LocalStorageManager
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

    /** 删除选中页面（目前实现：删本地；云端删除预留扩展） */
    fun deleteSelected() {
        val s = _uiState.value
        val toDelete = s.filtered.filter { it.key in s.selectedKeys }
        if (toDelete.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true) }
            withContext(Dispatchers.IO) {
                storage.deleteLocalPages(toDelete.map { it.title to it.namespace })
            }
            val metas = withContext(Dispatchers.IO) { storage.loadAllMetas() }
            _uiState.update {
                it.copy(metas = metas, selectedKeys = emptySet(), isDeleting = false)
            }
        }
    }
}