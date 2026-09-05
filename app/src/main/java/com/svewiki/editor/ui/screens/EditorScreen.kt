package com.svewiki.editor.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.svewiki.editor.api.SveWikiApi
import com.svewiki.editor.data.LocalPage
import com.svewiki.editor.data.LocalStorageManager
import com.svewiki.editor.data.Preferences
import com.svewiki.editor.sync.SyncEngine
import com.svewiki.editor.ui.components.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun EditorScreen(
    modifier: Modifier = Modifier,
    api: SveWikiApi? = null,
    storage: LocalStorageManager? = null,
    prefs: Preferences? = null,
    syncEngine: SyncEngine? = null,
    onPageOpen: (String, Int, String, Long) -> Unit = { _, _, _, _ -> }
) {
    var title by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf(prefs?.defaultSummary ?: "自动编辑") }
    var content by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("就绪") }
    var isWorking by remember { mutableStateOf(false) }

    // 撤销/重做
    val undoStack = remember { ArrayDeque<String>() }
    val redoStack = remember { ArrayDeque<String>() }

    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SectionTitle("页面编辑")
        Spacer(Modifier.height(12.dp))

        // 标题输入
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("页面标题") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        // 摘要输入
        OutlinedTextField(
            value = summary,
            onValueChange = { summary = it },
            label = { Text("编辑摘要") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        // 内容编辑区（等宽字体，适合 wikitext）
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            BasicTextField(
                value = content,
                onValueChange = {
                    if (it != content) {
                        undoStack.addLast(content)
                        redoStack.clear()
                    }
                    content = it
                },
                textStyle = TextStyle(
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 20.sp
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            )
        }

        Spacer(Modifier.height(12.dp))

        // 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    if (title.isBlank()) { status = "请输入标题"; return@Button }
                    val apiRef = api ?: return@Button
                    isWorking = true
                    status = "读取中..."
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            apiRef.readPage(title.trim())
                        }
                        isWorking = false
                        result.onSuccess { page ->
                            content = page.content
                            status = "已读取：${page.title}"
                        }.onFailure { e ->
                            status = "❌ 读取失败：${e.message}"
                        }
                    }
                },
                enabled = title.isNotBlank() && !isWorking,
                modifier = Modifier.weight(1f)
            ) { Text("读取") }

            Spacer(Modifier.width(8.dp))

            OutlinedButton(
                onClick = {
                    if (title.isBlank()) { status = "请输入标题"; return@OutlinedButton }
                    storage?.markModified(title.trim(), detectNamespace(title), content)
                    status = "已保存到本地：$title（待推送）"
                },
                enabled = title.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) { Text("保存草稿") }

            Spacer(Modifier.width(8.dp))

            Button(
                onClick = {
                    if (title.isBlank()) { status = "请输入标题"; return@Button }
                    isWorking = true
                    status = "推送中..."
                    val engine = syncEngine
                    if (engine == null) {
                        isWorking = false
                        status = "❌ 同步服务不可用"
                        return@Button
                    }
                    scope.launch {
                        val pageTitle = title.trim()
                        val namespace = detectNamespace(pageTitle)
                        val page = storage?.loadPage(pageTitle, namespace)?.copy(
                            content = content,
                            isModified = true
                        ) ?: LocalPage(
                            title = pageTitle,
                            namespace = namespace,
                            content = content,
                            isModified = true
                        )
                        val result = withContext(Dispatchers.IO) {
                            engine.pushPages(
                                pages = listOf(page),
                                summary = summary.ifBlank { "自动编辑" }
                            )
                        }
                        isWorking = false
                        if (result.success.contains(pageTitle)) {
                            status = "✅ 推送成功：$pageTitle"
                        } else {
                            status = "❌ 推送失败：${result.failed.firstOrNull()?.second ?: "未知"}"
                        }
                    }
                },
                enabled = title.isNotBlank() && !isWorking,
                modifier = Modifier.weight(1f)
            ) { Text("推送") }
        }

        Spacer(Modifier.height(8.dp))

        // 撤销/重做 + 状态
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    if (undoStack.isNotEmpty()) {
                        redoStack.addLast(content)
                        content = undoStack.removeLast()
                    }
                },
                enabled = undoStack.isNotEmpty()
            ) {
                Icon(Icons.Default.Undo, contentDescription = "撤销")
            }
            IconButton(
                onClick = {
                    if (redoStack.isNotEmpty()) {
                        undoStack.addLast(content)
                        content = redoStack.removeLast()
                    }
                },
                enabled = redoStack.isNotEmpty()
            ) {
                Icon(Icons.Default.Redo, contentDescription = "重做")
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (status.startsWith("✅") || status.startsWith("已")) {
                    MaterialTheme.colorScheme.primary
                } else if (status.startsWith("❌")) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
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