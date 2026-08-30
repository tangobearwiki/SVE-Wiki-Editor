package com.svewiki.editor.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.unit.dp
import com.svewiki.editor.data.LocalStorageManager
import com.svewiki.editor.data.Preferences
import com.svewiki.editor.sync.SyncEngine
import com.svewiki.editor.sync.SyncOverview
import com.svewiki.editor.ui.components.SectionTitle
import com.svewiki.editor.ui.components.StatCard
import com.svewiki.editor.ui.theme.SunGold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SyncScreen(
    modifier: Modifier = Modifier,
    syncEngine: SyncEngine? = null,
    storage: LocalStorageManager? = null,
    prefs: Preferences? = null,
    onPageOpen: (String, Int, String, Long) -> Unit = { _, _, _, _ -> }
) {
    var overview by remember { mutableStateOf<SyncOverview?>(null) }
    var isWorking by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // 初次加载概览
    LaunchedEffect(Unit) {
        val engine = syncEngine ?: return@LaunchedEffect
        overview = withContext(Dispatchers.IO) { engine.getOverview() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SectionTitle("同步中心")
        Spacer(Modifier.height(16.dp))

        // 概览统计卡片
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                value = overview?.totalPages?.toString() ?: "-",
                label = "本地页面",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                value = overview?.modifiedCount?.toString() ?: "-",
                label = "待推送",
                modifier = Modifier.weight(1f),
                valueColor = SunGold
            )
            StatCard(
                value = overview?.totalSizeFormatted ?: "-",
                label = "占用空间",
                modifier = Modifier.weight(1f),
                valueColor = MaterialTheme.colorScheme.tertiary
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "上次同步：${overview?.lastSyncTime?.let { t ->
                if (t > 0) SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(t)) else "从未"
            } ?: "从未"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
        )

        Spacer(Modifier.height(24.dp))

        // 操作区
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "操作",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = {
                        isWorking = true
                        statusText = "拉取全站中..."
                        val engine = syncEngine ?: return@Button
                        scope.launch {
                            engine.pullAllPages(
                                overwriteLocal = prefs?.overwriteLocal ?: false
                            ) { ns, done, total ->
                                statusText = "拉取 $ns：$done/$total"
                            }
                            overview = withContext(Dispatchers.IO) { engine.getOverview() }
                            isWorking = false
                            statusText = "全站拉取完成"
                        }
                    },
                    enabled = !isWorking && syncEngine != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("一键拉取全站")
                }

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        isWorking = true
                        statusText = "增量同步中..."
                        val engine = syncEngine ?: return@OutlinedButton
                        scope.launch {
                            val result = engine.syncRecentChanges(
                                onProgress = { _, done, total ->
                                    statusText = "增量同步：$done/$total"
                                }
                            )
                            overview = withContext(Dispatchers.IO) { engine.getOverview() }
                            isWorking = false
                            statusText = result.message
                        }
                    },
                    enabled = !isWorking && syncEngine != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("增量同步")
                }

                if (isWorking) {
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp).width(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.padding(start = 8.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}