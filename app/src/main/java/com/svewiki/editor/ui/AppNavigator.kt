package com.svewiki.editor.ui

import com.svewiki.editor.data.LocalPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 跨屏幕导航 / 通信状态。
 *
 * 使用 StateFlow 保存待打开页面，而不是一次性 SharedFlow 事件：
 * 管理页切换到编辑器时，编辑器会稍后才开始订阅，StateFlow 可以可靠保留页面，避免事件丢失。
 */
object AppNavigator {

    /** 当前选中的底部导航 tab 索引 */
    private val _currentTab = MutableStateFlow(0)
    val currentTab: StateFlow<Int> = _currentTab.asStateFlow()

    /** 待打开页面；消费后由编辑器显式清除 */
    private val _pendingOpenPage = MutableStateFlow<LocalPage?>(null)
    val pendingOpenPage: StateFlow<LocalPage?> = _pendingOpenPage.asStateFlow()

    fun selectTab(index: Int) {
        _currentTab.value = index
    }

    /** 请求在编辑器中打开指定页面，并切到编辑 tab */
    fun requestOpenPage(page: LocalPage) {
        // 先写入目标页面，再切换 tab，确保编辑器创建后不会丢失导航数据。
        _pendingOpenPage.value = page
        _currentTab.value = 0
    }

    /** 编辑器成功消费页面后清除待处理请求。 */
    fun consumeOpenPage() {
        _pendingOpenPage.value = null
    }
}
