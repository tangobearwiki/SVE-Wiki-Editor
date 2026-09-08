package com.svewiki.editor.ui

import com.svewiki.editor.data.LocalPage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 跨屏幕导航 / 通信事件总线。
 *
 * 替代原先的 AppState 全局可变单例：
 * - 当前 tab 用 [StateFlow] 暴露，UI 可观察
 * - 「打开页面」请求用一次性 [SharedFlow] 事件，避免状态残留
 */
object AppNavigator {

    /** 当前选中的底部导航 tab 索引 */
    private val _currentTab = MutableStateFlow(0)
    val currentTab: StateFlow<Int> = _currentTab.asStateFlow()

    /** 待打开页面的一次性事件（编辑器订阅消费） */
    private val _openPageRequests = MutableSharedFlow<LocalPage>(extraBufferCapacity = 1)
    val openPageRequests: SharedFlow<LocalPage> = _openPageRequests.asSharedFlow()

    fun selectTab(index: Int) {
        _currentTab.value = index
    }

    /** 请求在编辑器中打开某页面，并切到编辑 tab */
    fun requestOpenPage(page: LocalPage) {
        _openPageRequests.tryEmit(page)
        _currentTab.value = 0
    }
}