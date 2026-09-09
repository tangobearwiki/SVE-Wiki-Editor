package com.svewiki.editor.ui.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.svewiki.editor.data.PageMeta
import com.svewiki.editor.data.WikiNamespaces
import com.svewiki.editor.ui.AppViewModelFactory
import com.svewiki.editor.ui.components.EmptyState
import com.svewiki.editor.ui.components.SectionTitle

private val nsFilterOptions = listOf(-1 to "全部") +
    listOf(0, 2, 4, 6, 8, 10, 12, 14, 828).map { it to WikiNamespaces.getDisplayName(it) }

@Composable
fun ManageScreen(
    modifier: Modifier = Modifier,
    viewModel: ManageViewModel = viewModel(factory = AppViewModelFactory)
) {
    val ui by viewModel.uiState.collectAsState()
    var showConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        SectionTitle("页面管理")
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = ui.searchQuery,
            onValueChange = viewModel::onSearchChange,
            label = { Text("搜索页面") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            nsFilterOptions.take(5).forEach { (id, name) ->
                FilterChip(
                    selected = ui.nsFilter == id,
                    onClick = { viewModel.onNsFilterChange(id) },
                    label = { Text(name) },
                    leadingIcon = if (ui.nsFilter == id) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else null
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        Text(
            text = "共 ${ui.filtered.size} 页，已选 ${ui.selectedKeys.size}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        if (ui.filtered.isEmpty()) {
            EmptyState(
                icon = "📄",
                title = if (ui.isLoading) "加载中..." else "暂无页面",
                subtitle = if (ui.searchQuery.isBlank()) "去同步页拉取全站吧" else "换个关键词试试"
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(ui.filtered, key = { it.key }) { meta ->
                    ManagePageRow(
                        meta = meta,
                        selected = meta.key in ui.selectedKeys,
                        onToggle = { viewModel.onToggleSelect(meta.key) }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { if (ui.selectedKeys.isNotEmpty()) showConfirm = true },
                enabled = ui.selectedKeys.isNotEmpty() && !ui.isDeleting,
                modifier = Modifier.weight(1f)
            ) { Text(if (ui.isDeleting) "删除中..." else "删除选中") }
            TextButton(
                onClick = viewModel::clearSelection,
                enabled = ui.selectedKeys.isNotEmpty()
            ) { Text("清空选择") }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("确认删除 ${ui.selectedKeys.size} 个页面？") },
            text = { Text("将从本地存储移除选中页面。") },
            confirmButton = {
                Button(onClick = {
                    showConfirm = false
                    viewModel.deleteSelected()
                }) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ManagePageRow(
    meta: PageMeta,
    selected: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                Text(
                    text = meta.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (meta.isModified) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1
                )
                Text(
                    text = "${WikiNamespaces.getDisplayName(meta.namespace)} · " +
                        (if (meta.isModified) "已修改" else "已同步"),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (meta.isModified) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}