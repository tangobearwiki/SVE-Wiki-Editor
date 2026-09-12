package com.svewiki.editor.di

import android.content.Context
import com.svewiki.editor.api.SveWikiApi
import com.svewiki.editor.data.LocalStorageManager
import com.svewiki.editor.data.Preferences
import com.svewiki.editor.sync.SyncEngine
import com.svewiki.editor.ui.theme.ThemeController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val prefs: Preferences by lazy { Preferences(appContext) }

    val theme: ThemeController by lazy { ThemeController(prefs) }

    val api: SveWikiApi by lazy { SveWikiApi(prefs.baseUrl) }

    val storage: LocalStorageManager by lazy {
        LocalStorageManager(appContext).also { it.initStorage() }
    }

    val syncEngine: SyncEngine by lazy { SyncEngine(api, storage) }

    private val _loggedIn = MutableStateFlow(prefs.isLoggedIn)
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    fun setLoggedIn(value: Boolean, clearCredentials: Boolean = false) {
        prefs.isLoggedIn = value
        _loggedIn.value = value
        if (!value) {
            api.clearSession()
            if (clearCredentials) prefs.clearLogin()
        }
    }
}
