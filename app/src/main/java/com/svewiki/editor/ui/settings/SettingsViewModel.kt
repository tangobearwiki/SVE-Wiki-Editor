package com.svewiki.editor.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.svewiki.editor.api.SveWikiApi
import com.svewiki.editor.data.Preferences
import com.svewiki.editor.data.UserGroups
import com.svewiki.editor.data.UserInfo
import com.svewiki.editor.di.AppContainer
import com.svewiki.editor.ui.theme.ThemeController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val username: String = "",
    val password: String = "",
    val isLoggedIn: Boolean = false,
    val loginStatus: String = "",
    val loginOk: Boolean = true,
    val isLoggingIn: Boolean = false,
    val autoSaveDraft: Boolean = false,
    val overwriteLocal: Boolean = false,
    val autoPush: Boolean = false,
    val darkMode: Boolean = false,
    val userInfo: UserInfo? = null
)

class SettingsViewModel(
    private val prefs: Preferences,
    private val api: SveWikiApi,
    private val theme: ThemeController,
    private val container: AppContainer
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            username = prefs.username,
            password = prefs.password,
            isLoggedIn = prefs.isLoggedIn,
            autoSaveDraft = prefs.autoSaveDraft,
            overwriteLocal = prefs.overwriteLocal,
            autoPush = prefs.autoPushEnabled,
            darkMode = prefs.darkMode
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            container.loggedIn.collect { logged ->
                _uiState.update { it.copy(isLoggedIn = logged) }
                if (logged) refreshUserInfo()
            }
        }
        if (prefs.isLoggedIn) refreshUserInfo()
    }

    fun onUsernameChange(v: String) = _uiState.update { it.copy(username = v) }
    fun onPasswordChange(v: String) = _uiState.update { it.copy(password = v) }

    fun login() {
        val s = _uiState.value
        if (s.username.isBlank() || s.password.isBlank()) {
            _uiState.update { it.copy(loginStatus = "请输入用户名和密码", loginOk = false) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoggingIn = true, loginStatus = "登录中...") }
            val result = withContext(Dispatchers.IO) {
                api.login(s.username.trim(), s.password)
            }
            if (result.isSuccess) {
                prefs.username = s.username.trim()
                prefs.password = s.password
                container.setLoggedIn(true)
                _uiState.update {
                    it.copy(
                        isLoggingIn = false,
                        isLoggedIn = true,
                        loginStatus = "登录成功",
                        loginOk = true
                    )
                }
                refreshUserInfo()
            } else {
                _uiState.update {
                    it.copy(
                        isLoggingIn = false,
                        loginStatus = "登录失败：${result.exceptionOrNull()?.message ?: "未知错误"}",
                        loginOk = false
                    )
                }
            }
        }
    }

    fun logout() {
        container.setLoggedIn(false, clearCredentials = true)
        _uiState.update {
            it.copy(
                isLoggedIn = false,
                loginStatus = "",
                password = "",
                userInfo = null
            )
        }
    }

    fun setAutoSaveDraft(v: Boolean) {
        prefs.autoSaveDraft = v
        _uiState.update { it.copy(autoSaveDraft = v) }
    }

    fun setOverwriteLocal(v: Boolean) {
        prefs.overwriteLocal = v
        _uiState.update { it.copy(overwriteLocal = v) }
    }

    fun setAutoPush(v: Boolean) {
        prefs.autoPushEnabled = v
        _uiState.update { it.copy(autoPush = v) }
    }

    fun setDarkMode(v: Boolean) {
        theme.setDarkMode(v)
        _uiState.update { it.copy(darkMode = v) }
    }

    private fun refreshUserInfo() {
        val name = prefs.username
        if (name.isBlank()) return
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { api.getCurrentUserInfo() }.getOrNull()
            if (info != null) _uiState.update { it.copy(userInfo = info) }
        }
    }

    fun groupLabel(info: UserInfo): String {
        val meaningful = info.groups.filter { it != "*" && it != "user" }
        return if (meaningful.isEmpty()) "用户"
        else meaningful.joinToString(" · ") { UserGroups.getDisplayName(it) }
    }
}
