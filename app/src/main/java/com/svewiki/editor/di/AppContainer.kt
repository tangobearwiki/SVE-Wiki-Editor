package com.svewiki.editor.di

import android.content.Context
import com.svewiki.editor.api.SveWikiApi
import com.svewiki.editor.data.LocalStorageManager
import com.svewiki.editor.data.Preferences
import com.svewiki.editor.sync.SyncEngine

/**
 * 应用级依赖容器（手写轻量 DI）。
 *
 * 统一管理所有单例服务的创建与生命周期，
 * 替代原先 MainActivity 中的裸 lateinit + 层层参数透传。
 *
 * 通过 [android.app.Application] 持有一个全局实例，
 * ViewModel 通过 Factory 从这里取用依赖。
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val prefs: Preferences by lazy { Preferences(appContext) }

    val api: SveWikiApi by lazy { SveWikiApi(prefs.baseUrl) }

    val storage: LocalStorageManager by lazy {
        LocalStorageManager(appContext).also { it.initStorage() }
    }

    val syncEngine: SyncEngine by lazy { SyncEngine(api, storage) }
}
