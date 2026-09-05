package com.svewiki.editor.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class Preferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("svewiki_prefs", Context.MODE_PRIVATE)
    private val securePrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "svewiki_secure_prefs",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    init {
        val legacyPassword = prefs.getString("password", "") ?: ""
        if (securePrefs.getString("password", "").isNullOrEmpty() && legacyPassword.isNotEmpty()) {
            securePrefs.edit().putString("password", legacyPassword).apply()
            prefs.edit().remove("password").apply()
        }
    }

    val baseUrl: String
        get() = prefs.getString("base_url", "https://sve.p1.wiki") ?: "https://sve.p1.wiki"

    var username: String
        get() = prefs.getString("username", "") ?: ""
        set(value) = prefs.edit().putString("username", value).apply()

    var password: String
        get() = securePrefs.getString("password", "") ?: ""
        set(value) = securePrefs.edit().putString("password", value).apply()

    var isLoggedIn: Boolean
        get() = prefs.getBoolean("is_logged_in", false)
        set(value) = prefs.edit().putBoolean("is_logged_in", value).apply()

    var autoPushEnabled: Boolean
        get() = prefs.getBoolean("auto_push_enabled", false)
        set(value) = prefs.edit().putBoolean("auto_push_enabled", value).apply()

    var pushIntervalMinutes: Int
        get() = prefs.getInt("push_interval_minutes", 30)
        set(value) = prefs.edit().putInt("push_interval_minutes", value).apply()

    var defaultSummary: String
        get() = prefs.getString("default_summary", "自动编辑") ?: "自动编辑"
        set(value) = prefs.edit().putString("default_summary", value).apply()

    // 同步时覆盖本地修改
    var overwriteLocal: Boolean
        get() = prefs.getBoolean("overwrite_local", false)
        set(value) = prefs.edit().putBoolean("overwrite_local", value).apply()

    // 自动保存草稿
    var autoSaveDraft: Boolean
        get() = prefs.getBoolean("auto_save_draft", false)
        set(value) = prefs.edit().putBoolean("auto_save_draft", value).apply()

    // 暗色模式
    var darkMode: Boolean
        get() = prefs.getBoolean("dark_mode", false)
        set(value) = prefs.edit().putBoolean("dark_mode", value).apply()

    fun clearLogin() {
        prefs.edit().putBoolean("is_logged_in", false).remove("password").apply()
        securePrefs.edit().remove("password").apply()
    }
}