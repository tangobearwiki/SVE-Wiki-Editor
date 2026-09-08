package com.svewiki.editor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.svewiki.editor.SveWikiApp
import com.svewiki.editor.di.AppContainer
import com.svewiki.editor.ui.editor.EditorViewModel
import com.svewiki.editor.ui.manage.ManageViewModel
import com.svewiki.editor.ui.settings.SettingsViewModel
import com.svewiki.editor.ui.sync.SyncViewModel

/**
 * 统一的 ViewModel 工厂：从 [SveWikiApp.container] 取依赖注入。
 *
 * 在 Composable 中通过
 * `viewModel(factory = AppViewModelFactory)` 使用。
 */
object AppViewModelFactory : ViewModelProvider.Factory {

    private fun container(extras: CreationExtras): AppContainer {
        val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
            as SveWikiApp
        return app.container
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val c = container(extras)
        return when {
            modelClass.isAssignableFrom(EditorViewModel::class.java) ->
                EditorViewModel(c.api, c.storage, c.prefs, c.syncEngine) as T
            modelClass.isAssignableFrom(ManageViewModel::class.java) ->
                ManageViewModel(c.storage) as T
            modelClass.isAssignableFrom(SyncViewModel::class.java) ->
                SyncViewModel(c.syncEngine, c.prefs) as T
            modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
                SettingsViewModel(c.prefs, c.api) as T
            else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}