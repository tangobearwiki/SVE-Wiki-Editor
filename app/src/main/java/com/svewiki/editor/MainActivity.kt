package com.svewiki.editor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.svewiki.editor.api.SveWikiApi
import com.svewiki.editor.data.LocalStorageManager
import com.svewiki.editor.data.Preferences
import com.svewiki.editor.sync.SyncEngine
import com.svewiki.editor.ui.AppNav
import com.svewiki.editor.ui.theme.SveWikiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 单 Activity + Compose：UI 全面重构后的入口
 */
class MainActivity : ComponentActivity() {

    lateinit var prefs: Preferences
    lateinit var api: SveWikiApi
    lateinit var storage: LocalStorageManager
    lateinit var syncEngine: SyncEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // 初始化核心服务（轻量，不阻塞）
        prefs = Preferences(this)
        api = SveWikiApi(prefs.baseUrl)
        storage = LocalStorageManager(this)
        storage.initStorage()
        syncEngine = SyncEngine(api, storage)

        // 自动登录（后台，不阻塞 UI）
        autoLogin()

        setContent {
            SveWikiTheme {
                AppNav(
                    api = api,
                    storage = storage,
                    prefs = prefs,
                    syncEngine = syncEngine
                )
            }
        }
    }

    /** 自动登录（异步，不阻塞 UI，绑定生命周期） */
    private fun autoLogin() {
        if (prefs.isLoggedIn && prefs.username.isNotEmpty() && prefs.password.isNotEmpty()) {
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    api.login(prefs.username, prefs.password)
                }
                if (result.isFailure) {
                    prefs.isLoggedIn = false
                }
            }
        }
    }
}