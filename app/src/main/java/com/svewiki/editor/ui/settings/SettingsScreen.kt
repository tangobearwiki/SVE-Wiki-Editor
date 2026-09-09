package com.svewiki.editor.ui.settings

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.svewiki.editor.ui.AppViewModelFactory
import com.svewiki.editor.ui.components.SectionTitle

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelFactory)
) {
    val ui by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SectionTitle("设置")
        Spacer(Modifier.height(16.dp))

        // 账号区
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "账号",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(12.dp))
                if (ui.isLoggedIn) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "✅ 已登录：${ui.username}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = viewModel::logout) { Text("退出") }
                    }
                } else {
                    OutlinedTextField(
                        value = ui.username,
                        onValueChange = viewModel::onUsernameChange,
                        label = { Text("用户名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = ui.password,
                        onValueChange = viewModel::onPasswordChange,
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = viewModel::login,
                        enabled = !ui.isLoggingIn,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (ui.isLoggingIn) "登录中..." else "登录") }
                    if (ui.loginStatus.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = ui.loginStatus,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (ui.loginStatus.startsWith("✅"))
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // 常规设置区
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "常规",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(8.dp))
                SettingSwitchRow(
                    title = "自动保存草稿",
                    subtitle = "编辑时每 30 秒自动保存到本地",
                    checked = ui.autoSaveDraft,
                    onCheckedChange = viewModel::setAutoSaveDraft
                )
                SettingSwitchRow(
                    title = "同步时覆盖本地修改",
                    subtitle = "拉取时以服务器版本为准",
                    checked = ui.overwriteLocal,
                    onCheckedChange = viewModel::setOverwriteLocal
                )
                SettingSwitchRow(
                    title = "自动推送",
                    subtitle = "保存后自动推送修改到云端",
                    checked = ui.autoPush,
                    onCheckedChange = viewModel::setAutoPush
                )
                SettingSwitchRow(
                    title = "深色模式",
                    subtitle = "使用深夜森林主题",
                    checked = ui.darkMode,
                    onCheckedChange = viewModel::setDarkMode
                )
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}