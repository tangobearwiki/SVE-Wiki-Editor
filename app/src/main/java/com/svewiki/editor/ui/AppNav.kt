package com.svewiki.editor.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.svewiki.editor.api.SveWikiApi
import com.svewiki.editor.data.LocalStorageManager
import com.svewiki.editor.data.Preferences
import com.svewiki.editor.sync.SyncEngine
import com.svewiki.editor.ui.screens.EditorScreen
import com.svewiki.editor.ui.screens.ManageScreen
import com.svewiki.editor.ui.screens.SettingsScreen
import com.svewiki.editor.ui.screens.SyncScreen

// 底部导航项定义：清晰分类，不超 5 项
enum class NavTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    EDITOR("编辑", Icons.Filled.Edit, Icons.Outlined.Edit),
    SYNC("同步", Icons.Filled.Sync, Icons.Outlined.Sync),
    MANAGE("管理", Icons.Filled.FolderOpen, Icons.Outlined.FolderOpen),
    SETTINGS("设置", Icons.Filled.Settings, Icons.Outlined.Settings)
}

@Composable
fun AppNav(
    api: SveWikiApi? = null,
    storage: LocalStorageManager? = null,
    prefs: Preferences? = null,
    syncEngine: SyncEngine? = null,
    onPageOpen: (String, Int, String, Long) -> Unit = { _, _, _, _ -> }
) {
    val currentTab = AppState.currentTab

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                NavTab.entries.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = currentTab == index,
                        onClick = { AppState.currentTab = index },
                        icon = {
                            Icon(
                                imageVector = if (currentTab == index) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = tab.label
                            )
                        },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        val contentModifier = Modifier.padding(innerPadding)
        when (NavTab.entries[currentTab]) {
            NavTab.EDITOR -> EditorScreen(
                modifier = contentModifier,
                api = api,
                storage = storage,
                prefs = prefs,
                syncEngine = syncEngine,
                onPageOpen = onPageOpen
            )
            NavTab.SYNC -> SyncScreen(
                modifier = contentModifier,
                syncEngine = syncEngine,
                storage = storage,
                prefs = prefs,
                onPageOpen = onPageOpen
            )
            NavTab.MANAGE -> ManageScreen(
                modifier = contentModifier,
                storage = storage
            )
            NavTab.SETTINGS -> SettingsScreen(
                modifier = contentModifier,
                prefs = prefs,
                api = api
            )
        }
    }
}