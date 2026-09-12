package com.svewiki.editor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.svewiki.editor.ui.AppNav
import com.svewiki.editor.ui.theme.SveWikiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        autoLogin()

        setContent {
            val container = (application as SveWikiApp).container
            val dark by container.theme.darkMode.collectAsState()
            SveWikiTheme(darkTheme = dark) {
                AppNav()
            }
        }
    }

    private fun autoLogin() {
        val container = (application as SveWikiApp).container
        val prefs = container.prefs
        val api = container.api
        if (prefs.isLoggedIn && prefs.username.isNotEmpty() && prefs.password.isNotEmpty()) {
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    api.login(prefs.username, prefs.password)
                }
                container.setLoggedIn(result.isSuccess)
            }
        }
    }
}
