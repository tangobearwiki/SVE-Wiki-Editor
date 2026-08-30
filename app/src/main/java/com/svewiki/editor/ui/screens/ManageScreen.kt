package com.svewiki.editor.ui.screens

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.svewiki.editor.data.LocalPage
import com.svewiki.editor.data.LocalStorageManager
import com.svewiki.editor.data.PageMeta
import com.svewiki.editor.data.WikiNamespaces
import com.svewiki.editor.ui.components.EmptyState
import com.svewiki.editor.ui.components.SectionTitle
import com.svewiki.editor.ui.theme.BerryRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 命名空间筛选选项 */
private val nsFilterOptions = listOf(-1 to "全部") +
    listOf(0, 2, 4, 6, 8, 10, 12, 14, 828).map { it to WikiNamespaces.getDisplayName(it) }

@Composable
fun ManageScreen(
    modifier: Modifier = Modifier,
    storage: LocalStorageManager? = null
) {
    var metas by remember { mutableStateOf<List<PageMeta>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var nsFilter by remember { mutableStateOf(-1) }
    var selectedKeys by remember { mutableStateOf(setOf<String>()) }
    var showConfirm by remember { mutableStateOf(false) }
    var deleteMode by remember { mutableStateOf(2) } // 0=云 1=本地 2=全部
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 加载所有页面元数据（轻量）
    LaunchedEffect(Unit) {
        val s = storage ?: return@LaunchedEffect
        metas = withContext(Dispatchers.IO) { s.loadAllMetas() }
    }

    val filtered = metas.filter { meta ->
        (nsFilter == -1 || meta.namespace == nsFilter) &&
            (searchQuery.isBlank() || meta.title.contains(searchQuery, ignoreCase = true))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        SectionTitle("页面管理")
        Spacer(Modifier.height(12.dp))

        // 搜索框
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("搜索页面") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        // 命名空间筛选 chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            nsFilterOptions.take(5).forEach { (id, name) ->
                FilterChip(
                    selected = nsFilter == id,
                    onClick = { nsFilter = id },
                    label = { Text(name) },
                    leadingIcon = if (nsFilter == id) {
                        { androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.Default.Check,
                            contentDescription = null
                        ) }
                    } else null
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = "共 ${filtered.size} 页，已选 ${selectedKeys.size}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))

        if (filtered.isEmpty()) {
            EmptyState(
                icon = "📄",
                title = "暂无页面",
                subtitle = if (searchQuery.isBlank()) "去同步页拉取全站吧" else "换个关键词试试"
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filtered, key = { it.key }) { meta ->
                    ManagePageRow(
                        meta = meta,
                        selected = selectedKeys.contains(meta.key),
                        onToggle = {
                            selectedKeys = if (selectedKeys.contains(meta.key)) {
                                selectedKeys - meta.key
                            } else {
                                selectedKeys + meta.key
                            }
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 底部操作栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { if (selectedKeys.isNotEmpty()) showConfirm = true },
                enabled = selectedKeys.isNotEmpty() && !deleting,
                modifier = Modifier.weight(1f)
            ) { Text("删除选中") }
            TextButton(
                onClick = { selectedKeys = emptySet() },
                enabled = selectedKeys.isNotEmpty()
            ) { Text("清空选择") }
        }
    }

    // 删除确认对话框
    if (showConfirm) {
        val selectedPages = filtered.filter { selectedKeys.contains(it.key) }
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("确认删除 ${selectedPages.size} 个页面？") },
            text = {
                Column {
                    Text("选择删除方式：", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    listOf(
                        Triple("仅删除本地", 1, "本地文件"),
                        Triple("仅删除云端", 0, "云端页面"),
                        Triple("本地 + 云端", 2, "全部删除")
                    ).forEach { (label, mode, desc) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = deleteMode == mode,
                                onCheckedChange = { if (it == true) deleteMode = mode }
                            )
                            Text("$label（$desc）")
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirm = false
                        deleting = true
                        val s = storage ?: return@Button
                        scope.launch {
                            val pairs = selectedPages.map { it.title to it.namespace }
                            withContext(Dispatchers.IO) {
                                s.deleteLocalPages(pairs)
                            }
                            metas = withContext(Dispatchers.IO) { s.loadAllMetas() }
                            selectedKeys = emptySet()
                            deleting = false
                        }
                    }
                ) { Text("确认删除") }
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