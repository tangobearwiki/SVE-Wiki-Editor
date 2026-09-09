package com.svewiki.editor

import android.app.Application
import com.svewiki.editor.di.AppContainer

/**
 * 应用入口：持有全局唯一的 [AppContainer]。
 */
class SveWikiApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}