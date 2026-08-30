package com.svewiki.editor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.svewiki.editor.data.LocalPage

/**
 * Compose UI 全局状态
 * 用于跨屏幕通信（如管理页 -> 编辑页打开页面）
 */
object AppState {
    /** 待打开的本地页面（编辑页消费后置空） */
    var pendingOpenPage by mutableStateOf<LocalPage?>(null)

    /** 当前底部导航索引（供外部切换到指定 tab） */
    var currentTab by mutableStateOf(0)

    fun requestOpenPage(page: LocalPage) {
        pendingOpenPage = page
        currentTab = 0 // 切到编辑页
    }
}