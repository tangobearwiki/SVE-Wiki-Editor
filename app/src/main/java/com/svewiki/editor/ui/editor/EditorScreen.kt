package com.svewiki.editor.ui.editor

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.svewiki.editor.ui.AppNavigator
import com.svewiki.editor.ui.AppViewModelFactory
import com.svewiki.editor.ui.components.SectionTitle
import kotlinx.coroutines.flow.collectLatest

@Composable
fun EditorScreen(
    modifier: Modifier = Modifier,
    viewModel: EditorViewModel = viewModel(factory = AppViewModelFactory)
) {
    val ui by viewModel.uiState.collectAsState()

    // 订阅跨屏「打开页面」请求
    LaunchedEffect(Unit) {
        AppNavigator.openPageRequests.collectLatest { page ->
            viewModel.openPage(page)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SectionTitle("页面编辑")
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = ui.title,
            onValueChange = viewModel::onTitleChange,
            label = { Text("页面标题") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = ui.summary,
            onValueChange = viewModel::onSummaryChange,
            label = { Text("编辑摘要") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        // 内容编辑区（等宽字体）
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            BasicTextField(
                value = ui.content,
                onValueChange = viewModel::onContentChange,
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
                onClick = viewModel::readPage,
                enabled = ui.title.isNotBlank() && !ui.isWorking,
                modifier = Modifier.weight(1f)
            ) { Text("读取") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = viewModel::saveDraft,
                enabled = ui.title.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) { Text("保存草稿") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = viewModel::pushPage,
                enabled = ui.title.isNotBlank() && !ui.isWorking,
                modifier = Modifier.weight(1f)
            ) { Text("推送") }
        }

        Spacer(Modifier.height(8.dp))

        // 撤销/重做 + 状态
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = viewModel::undo, enabled = ui.canUndo) {
                Icon(Icons.Default.Undo, contentDescription = "撤销")
            }
            IconButton(onClick = viewModel::redo, enabled = ui.canRedo) {
                Icon(Icons.Default.Redo, contentDescription = "重做")
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = ui.status,
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    ui.status.startsWith("✅") || ui.status.startsWith("已") ->
                        MaterialTheme.colorScheme.primary
                    ui.status.startsWith("❌") -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}