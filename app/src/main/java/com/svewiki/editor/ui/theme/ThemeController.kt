package com.svewiki.editor.ui.theme

import com.svewiki.editor.data.Preferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 深色模式单一数据源。设置页写入后主题立刻响应。
 */
class ThemeController(private val prefs: Preferences) {
    private val _darkMode = MutableStateFlow(prefs.darkMode)
    val darkMode: StateFlow<Boolean> = _darkMode.asStateFlow()

    fun setDarkMode(enabled: Boolean) {
        prefs.darkMode = enabled
        _darkMode.value = enabled
    }
}
