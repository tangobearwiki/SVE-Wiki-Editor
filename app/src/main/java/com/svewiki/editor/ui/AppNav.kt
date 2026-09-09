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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.svewiki.editor.ui.editor.EditorScreen
import com.svewiki.editor.ui.manage.ManageScreen
import com.svewiki.editor.ui.settings.SettingsScreen
import com.svewiki.editor.ui.sync.SyncScreen

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

/**
 * 应用主导航。
 * 各屏幕通过各自的 ViewModel 获取依赖（见 [AppViewModelFactory]），
 * 不再从导航层透传 api/storage/prefs/syncEngine。
 */
@Composable
fun AppNav() {
    val currentTab by AppNavigator.currentTab.collectAsState()

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                NavTab.entries.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = currentTab == index,
                        onClick = { AppNavigator.selectTab(index) },
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
            NavTab.EDITOR -> EditorScreen(modifier = contentModifier)
            NavTab.SYNC -> SyncScreen(modifier = contentModifier)
            NavTab.MANAGE -> ManageScreen(modifier = contentModifier)
            NavTab.SETTINGS -> SettingsScreen(modifier = contentModifier)
        }
    }
}