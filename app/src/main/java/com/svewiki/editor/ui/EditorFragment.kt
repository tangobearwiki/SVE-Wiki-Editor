package com.svewiki.editor.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.svewiki.editor.MainActivity
import com.svewiki.editor.R
import com.svewiki.editor.api.SveWikiApi
import com.svewiki.editor.data.LocalStorageManager
import com.svewiki.editor.data.Preferences
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class EditorFragment : Fragment() {
    private lateinit var etTitle: EditText
    private lateinit var etSummary: EditText
    private lateinit var etContent: EditText
    private lateinit var btnRead: Button
    private lateinit var btnSaveLocal: Button
    private lateinit var btnPush: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvServerWarning: TextView

    private var api: SveWikiApi? = null
    private var prefs: Preferences? = null
    private var storage: LocalStorageManager? = null
    private var job: Job? = null
    private var autoSaveJob: Job? = null
    private var currentNamespace: Int = 0
    private var localLastSyncTime: Long = 0
    private var localTouched: String = ""

    /** 供外部直接打开一个本地页面进行编辑 */
    fun openLocalPage(title: String, namespace: Int, content: String, revisionId: Long = 0) {
        currentNamespace = namespace
        localLastSyncTime = revisionId  // 实际上传 lastSyncTime
        etTitle?.setText(title)
        etContent?.setText(content)
        tvStatus?.text = "已加载本地页面：$title"
        // 从本地存储获取该页面的 lastSyncTime 和 touched
        val localPage = storage?.loadPage(title, namespace)
        if (localPage != null) {
            localLastSyncTime = localPage.lastSyncTime
            localTouched = localPage.touched
        }
        checkServerVersion(title)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_editor, container, false)
        etTitle = view.findViewById(R.id.et_page_title)
        etSummary = view.findViewById(R.id.et_summary)
        etContent = view.findViewById(R.id.et_content)
        btnRead = view.findViewById(R.id.btn_read)
        btnSaveLocal = view.findViewById(R.id.btn_save_local)
        btnPush = view.findViewById(R.id.btn_push)
        progressBar = view.findViewById(R.id.progress_bar)
        tvStatus = view.findViewById(R.id.tv_status)
        tvServerWarning = view.findViewById(R.id.tv_server_warning)

        val activity = requireActivity()
        if (activity is MainActivity) {
            api = activity.api
            prefs = activity.prefs
            storage = activity.storage
        }

        btnRead.setOnClickListener { readPage() }
        btnSaveLocal.setOnClickListener { saveLocal() }
        btnPush.setOnClickListener { pushPage() }

        // 自动保存草稿
        startAutoSave()

        return view
    }

    private fun detectNamespace(title: String): Int = when {
        title.startsWith("模板:") || title.startsWith("Template:") -> 10
        title.startsWith("分类:") || title.startsWith("Category:") -> 14
        title.startsWith("文件:") || title.startsWith("File:") -> 6
        title.startsWith("用户:") || title.startsWith("User:") -> 2
        title.startsWith("MediaWiki:") -> 8
        title.startsWith("帮助:") || title.startsWith("Help:") -> 12
        title.startsWith("站务:") || title.startsWith("Project:") -> 4
        title.startsWith("模块:") || title.startsWith("Module:") -> 828
        title.startsWith("模板讨论:") -> 11
        title.startsWith("帮助讨论:") -> 13
        title.startsWith("MediaWiki讨论:") -> 9
        title.startsWith("站务讨论:") -> 5
        title.startsWith("模块讨论:") -> 829
        else -> 0
    }

    private fun readPage() {
        val title = etTitle.text.toString().trim()
        if (title.isEmpty()) { tvStatus.text = "请输入页面标题"; return }

        currentNamespace = detectNamespace(title)

        api?.let { api ->
            job = CoroutineScope(Dispatchers.Main).launch {
                progressBar.visibility = View.VISIBLE
                tvStatus.text = "读取中..."
                withContext(Dispatchers.IO) {
                    val result = api.readPage(title)
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = View.GONE
                        result.onSuccess { page ->
                            etContent.setText(page.content)
                            tvStatus.text = "已读取：${page.title}"
                        }.onFailure { e ->
                            tvStatus.text = "读取失败：${e.message}"
                        }
                    }
                }
            }
        }
    }

    /** 保存到本地（不推送），标记为已修改 */
    private fun saveLocal() {
        val title = etTitle.text.toString().trim()
        val content = etContent.text.toString().trim()
        if (title.isEmpty()) { tvStatus.text = "请输入页面标题"; return }

        storage?.markModified(title, currentNamespace, content)
        tvStatus.text = "已保存到本地：$title（待推送）"
        storage?.appendLog("保存本地", title)
        Toast.makeText(requireContext(), "已保存到本地，待推送", Toast.LENGTH_SHORT).show()
    }

    private fun pushPage() {
        val title = etTitle.text.toString().trim()
        val content = etContent.text.toString().trim()
        val summary = etSummary.text.toString().trim().ifEmpty { "自动编辑" }
        if (title.isEmpty()) { tvStatus.text = "请输入页面标题"; return }

        // 先保存到本地
        storage?.markModified(title, currentNamespace, content)

        api?.let { api ->
            job = CoroutineScope(Dispatchers.Main).launch {
                progressBar.visibility = View.VISIBLE
                tvStatus.text = "推送中..."
                withContext(Dispatchers.IO) {
                    val result = api.editPage(title, content, summary)
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = View.GONE
                        result.onSuccess {
                            tvStatus.text = "推送成功：$title"
                            storage?.markPushed(title, currentNamespace)
                            storage?.appendLog("推送", title)
                            Toast.makeText(requireContext(), "推送成功", Toast.LENGTH_SHORT).show()
                        }.onFailure { e ->
                            tvStatus.text = "推送失败：${e.message}"
                            Toast.makeText(requireContext(), "推送失败：${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        job?.cancel()
        autoSaveJob?.cancel()
    }

    /** 检测服务器端是否有更新（对比单个页面的编辑时间 vs 本地拉取时间） */
    private fun checkServerVersion(title: String) {
        val apiRef = api ?: return
        val titleRef = title.ifEmpty { etTitle.text.toString().trim() }
        if (titleRef.isEmpty()) return

        job = CoroutineScope(Dispatchers.Main).launch {
            tvServerWarning.visibility = View.GONE
            val result = withContext(Dispatchers.IO) {
                apiRef.fetchPageForDiff(titleRef)
            }
            result.onSuccess { serverPage ->
                // 解析服务器 touched 时间（ISO8601格式）
                val serverTouched = parseTouchedTime(serverPage.touched)
                if (serverTouched > 0 && localLastSyncTime > 0) {
                    // 服务器最后编辑时间 > 本地拉取时间 → 有更新
                    if (serverTouched > localLastSyncTime) {
                        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        val serverTime = sdf.format(Date(serverTouched))
                        val localTime = sdf.format(Date(localLastSyncTime))
                        tvServerWarning.text = "⚠️ 服务器端 ${serverTime} 有更新（您本地拉取于 $localTime），您正在编辑旧版本"
                        tvServerWarning.visibility = View.VISIBLE
                    }
                }
            }.onFailure {
                // 静默失败
            }
        }
    }

    /** 解析 ISO8601 时间字符串为毫秒时间戳 */
    private fun parseTouchedTime(isoTime: String): Long {
        if (isoTime.isEmpty()) return 0
        return try {
            // 例如 "2026-08-08T10:00:00Z"
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(isoTime)?.time ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /** 自动保存草稿 */
    private fun startAutoSave() {
        autoSaveJob?.cancel()
        val autoSaveEnabled = prefs?.autoSaveDraft ?: false
        if (!autoSaveEnabled) return

        autoSaveJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(30_000) // 每 30 秒
                val title = etTitle?.text?.toString()?.trim()
                val content = etContent?.text?.toString()?.trim()
                if (!title.isNullOrEmpty() && !content.isNullOrEmpty()) {
                    storage?.markModified(title, currentNamespace, content)
                }
            }
        }
    }
}