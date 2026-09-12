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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.svewiki.editor.ui.components.StatusChip
import com.svewiki.editor.ui.components.SurfaceCard
import com.svewiki.editor.ui.theme.BerryRed
import com.svewiki.editor.ui.theme.ForestGreen

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
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        SurfaceCard {
            Column {
                Text("账号", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                if (ui.isLoggedIn) {
                    Text(
                        ui.username,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    val info = ui.userInfo
                    if (info != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${viewModel.groupLabel(info)}  ·  ${info.editCount} 次编辑",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = viewModel::logout, modifier = Modifier.fillMaxWidth()) {
                        Text("退出登录")
                    }
                } else {
                    OutlinedTextField(
                        value = ui.username,
                        onValueChange = viewModel::onUsernameChange,
                        label = { Text("用户名") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = ui.password,
                        onValueChange = viewModel::onPasswordChange,
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = RoundedCornerShape(14.dp),
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
                        StatusChip(
                            text = ui.loginStatus,
                            color = if (ui.loginOk) ForestGreen else BerryRed
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        SurfaceCard {
            Column {
                Text("常规", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                SettingSwitchRow(
                    title = "自动保存草稿",
                    subtitle = "编辑停顿 30 秒后写入本地",
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
                    subtitle = "保存草稿后立刻推送到云端",
                    checked = ui.autoPush,
                    onCheckedChange = viewModel::setAutoPush
                )
                SettingSwitchRow(
                    title = "深色模式",
                    subtitle = "深夜森林主题，立即生效",
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
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
