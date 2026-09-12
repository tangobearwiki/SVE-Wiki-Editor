package com.svewiki.editor.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.svewiki.editor.ui.AppViewModelFactory
import com.svewiki.editor.ui.components.StatCard
import com.svewiki.editor.ui.components.SurfaceCard
import com.svewiki.editor.ui.theme.SunGold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SyncScreen(
    modifier: Modifier = Modifier,
    viewModel: SyncViewModel = viewModel(factory = AppViewModelFactory)
) {
    val ui by viewModel.uiState.collectAsState()
    val progress by viewModel.progress.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text("同步中心", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatCard(
                value = ui.overview?.totalPages?.toString() ?: "—",
                label = "本地页面",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                value = ui.overview?.modifiedCount?.toString() ?: "—",
                label = "待推送",
                modifier = Modifier.weight(1f),
                valueColor = SunGold
            )
            StatCard(
                value = ui.overview?.totalSizeFormatted ?: "—",
                label = "占用空间",
                modifier = Modifier.weight(1f),
                valueColor = MaterialTheme.colorScheme.tertiary
            )
        }
        Spacer(Modifier.height(10.dp))

        Text(
            text = "上次同步：${ui.overview?.lastSyncTime?.let { t ->
                if (t > 0) SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(t))
                else "从未"
            } ?: "从未"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
        )

        val namespaces = ui.overview?.namespaces.orEmpty()
        if (namespaces.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = namespaces.joinToString("  ·  ") { "${it.first} ${it.second}" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
        Spacer(Modifier.height(20.dp))

        SurfaceCard {
            Column {
                Text("操作", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = viewModel::pullAll,
                    enabled = !ui.isWorking,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("一键拉取全站") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = viewModel::syncRecent,
                    enabled = !ui.isWorking,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("增量同步") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = viewModel::pushModified,
                    enabled = !ui.isWorking && (ui.overview?.modifiedCount ?: 0) > 0,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("推送待提交修改") }

                if (ui.isWorking) {
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = progress.fraction,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = ui.statusText.ifBlank { progress.message },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = viewModel::cancel) { Text("取消") }
                    }
                } else if (ui.statusText.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = ui.statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (ui.lastError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
