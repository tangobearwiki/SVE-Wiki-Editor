package com.svewiki.editor.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.svewiki.editor.R
import com.svewiki.editor.api.SveWikiApi
import com.svewiki.editor.data.Preferences
import com.svewiki.editor.data.UserGroups
import kotlinx.coroutines.*

class SettingsFragment : Fragment() {
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var tvLoginStatus: TextView
    private lateinit var cardUserInfo: MaterialCardView
    private lateinit var tvUserEditCount: TextView
    private lateinit var tvUserRegistration: TextView
    private lateinit var llUserGroups: LinearLayout
    private lateinit var switchOverwrite: Switch
    private lateinit var switchAutoSave: Switch
    private lateinit var switchDarkMode: Switch
    private lateinit var etDefaultSummary: EditText
    private lateinit var btnViewLog: Button

    private var api: SveWikiApi? = null
    private var prefs: Preferences? = null
    private var job: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)
        etUsername = view.findViewById(R.id.et_username)
        etPassword = view.findViewById(R.id.et_password)
        btnLogin = view.findViewById(R.id.btn_login)
        tvLoginStatus = view.findViewById(R.id.tv_login_status)
        cardUserInfo = view.findViewById(R.id.card_user_info)
        tvUserEditCount = view.findViewById(R.id.tv_user_edit_count)
        tvUserRegistration = view.findViewById(R.id.tv_user_registration)
        llUserGroups = view.findViewById(R.id.ll_user_groups)
        switchOverwrite = view.findViewById(R.id.switch_overwrite)
        switchAutoSave = view.findViewById(R.id.switch_auto_save)
        switchDarkMode = view.findViewById(R.id.switch_dark_mode)
        etDefaultSummary = view.findViewById(R.id.et_default_summary)
        btnViewLog = view.findViewById(R.id.btn_view_log)

        val activity = requireActivity()
        if (activity is com.svewiki.editor.MainActivity) {
            api = activity.api
            prefs = activity.prefs
        }

        loadSettings()

        btnLogin.setOnClickListener { loginOrRefresh() }
        switchOverwrite.setOnCheckedChangeListener { _, isChecked ->
            prefs?.overwriteLocal = isChecked
        }
        switchAutoSave.setOnCheckedChangeListener { _, isChecked ->
            prefs?.autoSaveDraft = isChecked
        }
        switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            prefs?.darkMode = isChecked
            if (isChecked) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }
        btnViewLog.setOnClickListener { showLogDialog() }

        return view
    }

    private fun loadSettings() {
        prefs?.let { p ->
            etUsername.setText(p.username)
            etPassword.setText(p.password)
            switchOverwrite.isChecked = p.overwriteLocal
            switchAutoSave.isChecked = p.autoSaveDraft
            switchDarkMode.isChecked = p.darkMode
            etDefaultSummary.setText(p.defaultSummary)
            tvLoginStatus.text = if (p.isLoggedIn) "已登录：${p.username}" else "未登录"
            btnLogin.text = if (p.isLoggedIn) "刷新用户信息" else "登录"

            if (p.isLoggedIn && p.username.isNotEmpty()) {
                fetchUserInfo(p.username)
            }
        }
    }

    private fun loginOrRefresh() {
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString().trim()
        if (username.isEmpty() || password.isEmpty()) {
            tvLoginStatus.text = "请输入用户名和密码"
            return
        }

        job = viewLifecycleOwner.lifecycleScope.launch {
            tvLoginStatus.text = "登录中..."
            btnLogin.isEnabled = false

            val loginResult = withContext(Dispatchers.IO) {
                api?.login(username, password)
            }

            loginResult?.onSuccess {
                prefs?.username = username
                prefs?.password = password
                prefs?.isLoggedIn = true
                tvLoginStatus.text = "登录成功：$username"
                btnLogin.text = "刷新用户信息"
                Toast.makeText(requireContext(), "登录成功", Toast.LENGTH_SHORT).show()
                fetchUserInfo(username)
            }?.onFailure { e ->
                tvLoginStatus.text = "登录失败：${e.message}"
                Toast.makeText(requireContext(), "登录失败：${e.message}", Toast.LENGTH_LONG).show()
            }

            btnLogin.isEnabled = true
        }
    }

    private fun fetchUserInfo(username: String) {
        job = viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                api?.getCurrentUserInfo()
            }

            result?.onSuccess { userInfo ->
                if (userInfo.isLoggedIn) {
                    cardUserInfo.visibility = View.VISIBLE
                    tvUserEditCount.text = "编辑次数：${userInfo.editCount} 次"
                    tvUserRegistration.text = "注册时间：${userInfo.registration.take(10)}"

                    llUserGroups.removeAllViews()
                    if (userInfo.groups.isEmpty()) {
                        val tv = TextView(requireContext())
                        tv.text = "无特殊身份组"
                        tv.textSize = 13f
                        tv.setTextColor(resources.getColor(R.color.text_secondary, null))
                        llUserGroups.addView(tv)
                    } else {
                        userInfo.groups.forEach { group ->
                            llUserGroups.addView(createGroupChip(group))
                        }
                    }
                } else {
                    cardUserInfo.visibility = View.GONE
                }
            }?.onFailure {
                cardUserInfo.visibility = View.GONE
            }
        }
    }

    private fun createGroupChip(group: String): View {
        val row = LinearLayout(requireContext())
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(4, 4, 4, 4)

        val dot = TextView(requireContext())
        dot.text = "● "
        dot.textSize = 12f
        dot.setTextColor(resources.getColor(R.color.primary, null))

        val name = TextView(requireContext())
        name.text = UserGroups.getDisplayName(group)
        name.textSize = 13f
        name.setTextColor(resources.getColor(R.color.text_primary, null))

        row.addView(dot)
        row.addView(name)
        return row
    }

    /**
     * 查看操作日志（从本地读取）
     */
    private fun showLogDialog() {
        val activity = requireActivity()
        val logText = if (activity is com.svewiki.editor.MainActivity) {
            activity.storage.readLog()
        } else {
            "日志不可用"
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("操作日志")
            .setMessage(if (logText.isEmpty()) "暂无日志" else logText)
            .setPositiveButton("确定", null)
            .create()
        dialog.show()
    }

    override fun onPause() {
        super.onPause()
        // 在 onPause 安全保存设置（此时 view 仍存在）
        prefs?.let { p ->
            p.defaultSummary = etDefaultSummary.text.toString().trim().ifEmpty { "自动编辑" }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        job?.cancel()
    }
}