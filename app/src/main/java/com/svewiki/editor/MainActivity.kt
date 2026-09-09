package com.svewiki.editor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.svewiki.editor.ui.AppNav
import com.svewiki.editor.ui.theme.SveWikiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 单 Activity + 纯 Compose 入口。
 * 依赖统一从 [SveWikiApp.container] 获取，不再层层透传。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        autoLogin()

        setContent {
            SveWikiTheme {
                AppNav()
            }
        }
    }

    /** 自动登录（异步，绑定生命周期，不阻塞 UI） */
    private fun autoLogin() {
        val prefs = (application as SveWikiApp).container.prefs
        val api = (application as SveWikiApp).container.api
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