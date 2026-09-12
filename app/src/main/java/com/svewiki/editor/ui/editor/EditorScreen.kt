package com.svewiki.editor.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.svewiki.editor.highlight.WikiTextHighlighter
import com.svewiki.editor.ui.AppNavigator
import com.svewiki.editor.ui.AppViewModelFactory
import com.svewiki.editor.ui.components.StatusChip
import com.svewiki.editor.ui.theme.BerryRed
import com.svewiki.editor.ui.theme.ForestGreen
import com.svewiki.editor.util.DiffUtil

@Composable
fun EditorScreen(
    modifier: Modifier = Modifier,
    viewModel: EditorViewModel = viewModel(factory = AppViewModelFactory)
) {
    val ui by viewModel.uiState.collectAsState()
    val pendingOpenPage by AppNavigator.pendingOpenPage.collectAsState()

    // 使用持久化的 StateFlow 接收管理页的打开请求。
    // 管理页切换到编辑器时，EditorScreen 才刚刚创建；SharedFlow 会丢事件，StateFlow 不会。
    LaunchedEffect(pendingOpenPage) {
        pendingOpenPage?.let { page ->
            viewModel.openPage(page)
            AppNavigator.consumeOpenPage()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text("页面编辑", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = ui.title,
            onValueChange = viewModel::onTitleChange,
            label = { Text("页面标题") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = ui.summary,
            onValueChange = viewModel::onSummaryChange,
            label = { Text("编辑摘要") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            key(ui.title) {
                EditorPane(
                    content = ui.content,
                    highlight = ui.highlightEnabled,
                    syntaxMode = ui.syntaxMode,
                    onContentChange = viewModel::onContentChange,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = viewModel::undo, enabled = ui.canUndo) {
                Icon(Icons.Outlined.Undo, contentDescription = "撤销")
            }
            IconButton(onClick = viewModel::redo, enabled = ui.canRedo) {
                Icon(Icons.Outlined.Redo, contentDescription = "重做")
            }
            IconButton(onClick = viewModel::toggleHighlight) {
                Icon(
                    Icons.Outlined.Highlight,
                    contentDescription = "语法高亮",
                    tint = if (ui.highlightEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = viewModel::compareWithServer,
                enabled = ui.title.isNotBlank() && !ui.isWorking
            ) {
                Icon(Icons.Outlined.Compare, contentDescription = "与服务器比对")
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "${ui.lineCount} 行 · ${ui.charCount} 字",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = viewModel::readPage,
                enabled = ui.title.isNotBlank() && !ui.isWorking,
                modifier = Modifier.weight(1f)
            ) { Text("读取") }
            OutlinedButton(
                onClick = viewModel::saveDraft,
                enabled = ui.title.isNotBlank() && !ui.isWorking,
                modifier = Modifier.weight(1f)
            ) { Text("保存") }
            Button(
                onClick = viewModel::pushPage,
                enabled = ui.title.isNotBlank() && !ui.isWorking,
                modifier = Modifier.weight(1f)
            ) { Text("推送") }
        }
        Spacer(Modifier.height(8.dp))
        StatusChip(
            text = ui.status,
            color = when (ui.statusKind) {
                StatusKind.SUCCESS -> ForestGreen
                StatusKind.ERROR -> BerryRed
                StatusKind.WORKING -> MaterialTheme.colorScheme.tertiary
                StatusKind.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }

    if (ui.showDiff) {
        DiffDialog(
            lines = ui.diffLines,
            onDismiss = viewModel::dismissDiff
        )
    }
}

@Composable
private fun EditorPane(
    content: String,
    highlight: Boolean,
    syntaxMode: com.svewiki.editor.highlight.SyntaxMode,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val annotated = remember(content, highlight, syntaxMode) {
        if (highlight) WikiTextHighlighter.highlight(content, syntaxMode)
        else androidx.compose.ui.text.AnnotatedString(content)
    }
    var selection by remember { mutableStateOf(TextRange(content.length)) }
    val fieldValue = TextFieldValue(annotatedString = annotated, selection = selection)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                RoundedCornerShape(16.dp)
            )
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp)
    ) {
        BasicTextField(
            value = fieldValue,
            onValueChange = { next ->
                selection = next.selection
                onContentChange(next.text)
            },
            textStyle = TextStyle(
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 22.sp
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        )
        if (content.isEmpty()) {
            Text(
                "在此编写 wikitext…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun DiffDialog(
    lines: List<DiffUtil.DiffLine>,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "与服务器差异",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (lines.isEmpty()) {
                    Text("内容无差异", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp)
                    ) {
                        itemsIndexed(lines) { _, line ->
                            val (bg, prefix, text) = when (line.type) {
                                DiffUtil.LineType.EQUAL -> Triple(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    "  ",
                                    line.oldLine
                                )
                                DiffUtil.LineType.DELETE -> Triple(
                                    BerryRed.copy(alpha = 0.14f),
                                    "- ",
                                    line.oldLine
                                )
                                DiffUtil.LineType.INSERT -> Triple(
                                    ForestGreen.copy(alpha = 0.14f),
                                    "+ ",
                                    line.newLine
                                )
                                DiffUtil.LineType.CHANGE -> Triple(
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f),
                                    "~ ",
                                    line.newLine
                                )
                            }
                            Text(
                                text = prefix + text,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(bg)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("关闭")
                }
            }
        }
    }
}
