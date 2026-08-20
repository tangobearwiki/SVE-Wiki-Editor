package com.svewiki.editor

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationView
import com.google.android.material.appbar.MaterialToolbar
import com.svewiki.editor.api.SveWikiApi
import com.svewiki.editor.data.LocalStorageManager
import com.svewiki.editor.data.Preferences
import com.svewiki.editor.sync.SyncEngine
import com.svewiki.editor.ui.EditorFragment
import com.svewiki.editor.ui.ManageFragment
import com.svewiki.editor.ui.SettingsFragment
import com.svewiki.editor.ui.SyncFragment
import com.svewiki.editor.ui.ViewPagerAdapter
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {
    lateinit var prefs: Preferences
    lateinit var api: SveWikiApi
    lateinit var storage: LocalStorageManager
    lateinit var syncEngine: SyncEngine

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var navView: NavigationView

    // 持有 fragment 引用
    private var editorFragment: EditorFragment? = null
    private var syncFragment: SyncFragment? = null
    private var manageFragment: ManageFragment? = null
    private var settingsFragment: SettingsFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // 根据暗色模式设置选择主题
        val darkPrefs = Preferences(this)
        if (darkPrefs.darkMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
        super.onCreate(savedInstanceState)

        // 先显示布局再初始化其他（提升启动感知速度）
        setContentView(R.layout.activity_main)

        // 快速初始化
        prefs = Preferences(this)
        api = SveWikiApi(prefs.baseUrl)
        storage = LocalStorageManager(this)
        storage.initStorage()
        syncEngine = SyncEngine(api, storage)

        drawerLayout = findViewById(R.id.drawer_layout)
        viewPager = findViewById(R.id.view_pager)
        bottomNav = findViewById(R.id.bottom_nav)
        toolbar = findViewById(R.id.toolbar)
        navView = findViewById(R.id.nav_view)

        // 自动登录（异步，不阻塞 UI）
        autoLogin()

        // 设置 ViewPager（4 个 tab）
        editorFragment = EditorFragment()
        syncFragment = SyncFragment()
        manageFragment = ManageFragment()
        settingsFragment = SettingsFragment()
        val fragments = listOf(
            editorFragment!!,
            syncFragment!!,
            manageFragment!!,
            settingsFragment!!
        )
        val adapter = ViewPagerAdapter(this, fragments)
        viewPager.adapter = adapter
        viewPager.isUserInputEnabled = false

        // 底部导航切换
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_editor -> {
                    viewPager.currentItem = 0
                    toolbar.title = "页面编辑"
                    true
                }
                R.id.nav_sync -> {
                    viewPager.currentItem = 1
                    toolbar.title = "全站同步"
                    true
                }
                R.id.nav_manage -> {
                    viewPager.currentItem = 2
                    toolbar.title = "页面管理"
                    true
                }
                R.id.nav_settings -> {
                    viewPager.currentItem = 3
                    toolbar.title = "设置"
                    true
                }
                else -> false
            }
        }

        // 侧边栏
        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_editor -> viewPager.currentItem = 0
                R.id.nav_sync -> viewPager.currentItem = 1
                R.id.nav_manage -> viewPager.currentItem = 2
                R.id.nav_settings -> viewPager.currentItem = 3
            }
            drawerLayout.closeDrawers()
            true
        }

        toolbar.setNavigationOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    /**
     * 打开一个本地页面到编辑器 Tab 进行编辑
     */
    fun openEditorWithPage(page: com.svewiki.editor.data.LocalPage) {
        viewPager.currentItem = 0
        toolbar.title = "页面编辑"
        bottomNav.selectedItemId = R.id.nav_editor
        editorFragment?.openLocalPage(page.title, page.namespace, page.content, page.revisionId)
    }

    /** 自动登录（异步，不阻塞 UI，绑定 Activity 生命周期） */
    private fun autoLogin() {
        // 仅当本地有登录标记且账号密码非空时才尝试自动登录
        if (prefs.isLoggedIn && prefs.username.isNotEmpty() && prefs.password.isNotEmpty()) {
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    api.login(prefs.username, prefs.password)
                }
                if (result.isFailure) {
                    // 登录失败：清除登录标记，避免"假登录"导致推送/删除静默失败
                    prefs.isLoggedIn = false
                }
            }
        }
    }
}