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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.svewiki.editor.api.SveWikiApi
import com.svewiki.editor.data.Preferences
import com.svewiki.editor.ui.components.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    prefs: Preferences? = null,
    api: SveWikiApi? = null,
    onLoginStateChange: (() -> Unit)? = null
) {
    var username by remember { mutableStateOf(prefs?.username ?: "") }
    var password by remember { mutableStateOf(prefs?.password ?: "") }
    var isLoggedIn by remember { mutableStateOf(prefs?.isLoggedIn ?: false) }
    var loginStatus by remember { mutableStateOf("") }
    var isLoggingIn by remember { mutableStateOf(false) }

    // 设置项
    var autoSaveDraft by remember { mutableStateOf(prefs?.autoSaveDraft ?: false) }
    var overwriteLocal by remember { mutableStateOf(prefs?.overwriteLocal ?: false) }
    var autoPush by remember { mutableStateOf(prefs?.autoPushEnabled ?: false) }
    var darkMode by remember { mutableStateOf(prefs?.darkMode ?: false) }

    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SectionTitle("设置")
        Spacer(Modifier.height(16.dp))

        // ===== 账号区 =====
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

                if (isLoggedIn) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "✅ 已登录：$username",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                prefs?.clearLogin()
                                isLoggedIn = false
                                onLoginStateChange?.invoke()
                            }
                        ) { Text("退出") }
                    }
                } else {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("用户名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (username.isBlank() || password.isBlank()) {
                                loginStatus = "请输入用户名和密码"
                                return@Button
                            }
                            isLoggingIn = true
                            loginStatus = "登录中..."
                            val apiRef = api ?: return@Button
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    apiRef.login(username.trim(), password)
                                }
                                isLoggingIn = false
                                if (result.isSuccess) {
                                    prefs?.username = username.trim()
                                    prefs?.password = password
                                    prefs?.isLoggedIn = true
                                    isLoggedIn = true
                                    loginStatus = "✅ 登录成功"
                                } else {
                                    loginStatus = "❌ 登录失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                                }
                            }
                        },
                        enabled = !isLoggingIn,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (isLoggingIn) "登录中..." else "登录") }
                    if (loginStatus.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            loginStatus,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (loginStatus.startsWith("✅")) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ===== 常规设置区 =====
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
                    checked = autoSaveDraft,
                    onCheckedChange = {
                        autoSaveDraft = it
                        prefs?.autoSaveDraft = it
                    }
                )

                SettingSwitchRow(
                    title = "同步时覆盖本地修改",
                    subtitle = "拉取时以服务器版本为准",
                    checked = overwriteLocal,
                    onCheckedChange = {
                        overwriteLocal = it
                        prefs?.overwriteLocal = it
                    }
                )

                SettingSwitchRow(
                    title = "自动推送",
                    subtitle = "保存后自动推送修改到云端",
                    checked = autoPush,
                    onCheckedChange = {
                        autoPush = it
                        prefs?.autoPushEnabled = it
                    }
                )

                SettingSwitchRow(
                    title = "深色模式",
                    subtitle = "使用深夜森林主题",
                    checked = darkMode,
                    onCheckedChange = {
                        darkMode = it
                        prefs?.darkMode = it
                    }
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
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}