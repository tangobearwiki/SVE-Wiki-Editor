package com.svewiki.editor.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import com.svewiki.editor.highlight.SyntaxMode
import com.svewiki.editor.highlight.WikiTextHighlighter
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
    private lateinit var spinnerSyntaxMode: Spinner
    private lateinit var btnToggleHighlight: Button
    private lateinit var btnUndo: Button
    private lateinit var btnRedo: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvServerWarning: TextView

    private var api: SveWikiApi? = null
    private var prefs: Preferences? = null
    private var storage: LocalStorageManager? = null
    private var job: Job? = null
    private var autoSaveJob: Job? = null
    private var highlightJob: Job? = null
    private var currentNamespace: Int = 0
    private var localLastSyncTime: Long = 0
    private var localTouched: String = ""
    private var isHighlighting = false  // 防止高亮循环
    private var highlightEnabled = true  // 高亮开关
    private var currentSyntaxMode: SyntaxMode = SyntaxMode.WIKITEXT

    // 撤销/重做历史栈
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private var isRestoringHistory = false  // 防止历史恢复时触发监听
    private var lastCommittedText: String? = null

    /** 供外部直接打开一个本地页面进行编辑 */
    fun openLocalPage(title: String, namespace: Int, content: String, revisionId: Long = 0) {
        currentNamespace = namespace
        // 先重置，再从本地存储读取真实时间
        localLastSyncTime = 0
        localTouched = ""
        etTitle?.setText(title)
        etContent?.setText(content)
        tvStatus?.text = "已加载本地页面：$title"
        applyHighlight()
        // 从本地存储获取该页面的 lastSyncTime 和 touched
        val localPage = storage?.loadPage(title, namespace)
        if (localPage != null) {
            localLastSyncTime = localPage.lastSyncTime
            localTouched = localPage.touched
        }
        // 根据命名空间自动选择语法模式
        if (::spinnerSyntaxMode.isInitialized) {
            currentSyntaxMode = SyntaxMode.fromNamespace(namespace)
            spinnerSyntaxMode.setSelection(currentSyntaxMode.ordinal)
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
        spinnerSyntaxMode = view.findViewById(R.id.spinner_syntax_mode)
        btnToggleHighlight = view.findViewById(R.id.btn_toggle_highlight)
        btnUndo = view.findViewById(R.id.btn_undo)
        btnRedo = view.findViewById(R.id.btn_redo)
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

        setupSyntaxSpinner()

        // 撤销/重做按钮
        btnUndo.setOnClickListener { undo() }
        btnRedo.setOnClickListener { redo() }

        // 高亮开关
        btnToggleHighlight.setOnClickListener {
            highlightEnabled = !highlightEnabled
            btnToggleHighlight.text = if (highlightEnabled) "关闭高亮" else "开启高亮"
            btnToggleHighlight.backgroundTintList = if (highlightEnabled) {
                android.content.res.ColorStateList.valueOf(
                    resources.getColor(R.color.text_secondary, null))
            } else {
                android.content.res.ColorStateList.valueOf(
                    resources.getColor(R.color.accent_green, null))
            }
            if (highlightEnabled) {
                applyHighlight()
            } else {
                clearAllSpans()
            }
        }

        // 编辑内容语法高亮（延迟 500ms）+ 历史记录
        etContent.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isRestoringHistory) return // 历史恢复时不记录
                if (isHighlighting) return // 高亮时不记录

                val text = s?.toString() ?: return

                // 文本变化超过 3 秒间隔或内容变化较大时记录快照
                if (lastCommittedText != null) {
                    // 如果内容变化超过 50 字符或包含换行，记录快照
                    val diff = kotlin.math.abs(text.length - (lastCommittedText?.length ?: 0))
                    if (diff > 30 || text.count { it == '\n' } != (lastCommittedText?.count { it == '\n' } ?: 0)) {
                        commitSnapshot(text)
                    }
                } else {
                    lastCommittedText = text
                    undoStack.clear()
                    redoStack.clear()
                }

                // 高亮延迟
                highlightJob?.cancel()
                highlightJob = CoroutineScope(Dispatchers.Main).launch {
                    delay(500)
                    if (highlightEnabled) applyHighlight()
                }
            }
        })

        // 初始高亮
        applyHighlight()

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
                            applyHighlight()
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
        highlightJob?.cancel()
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

    /** 应用语法高亮到内容编辑框（基于现有 Editable，不重建文本，避免光标抽搐/滚动失效） */
    private fun applyHighlight() {
        if (isHighlighting || !highlightEnabled) return
        isHighlighting = true
        try {
            val editable = etContent.text as? Editable ?: return
            if (editable.isEmpty()) return
            WikiTextHighlighter.highlightByMode(editable, currentSyntaxMode)
        } catch (_: Exception) {
        } finally {
            isHighlighting = false
        }
    }

    /** 清除所有高亮 span */
    private fun clearAllSpans() {
        val editable = etContent.text as? Editable ?: return
        try {
            val spans = editable.getSpans(0, editable.length, Any::class.java)
            for (span in spans) {
                try {
                    if (span is android.text.style.ForegroundColorSpan ||
                        span is android.text.style.StyleSpan ||
                        span is android.text.style.BackgroundColorSpan) {
                        editable.removeSpan(span)
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /** 设置语法模式下拉框 */
    private fun setupSyntaxSpinner() {
        val modes = SyntaxMode.values().map { it.displayName }.toTypedArray()
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, modes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSyntaxMode.adapter = adapter

        // 默认根据命名空间选模式
        currentSyntaxMode = SyntaxMode.fromNamespace(currentNamespace)
        spinnerSyntaxMode.setSelection(currentSyntaxMode.ordinal)

        spinnerSyntaxMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentSyntaxMode = SyntaxMode.values()[position]
                if (highlightEnabled) {
                    applyHighlight()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** 提交当前文本快照到历史栈 */
    private fun commitSnapshot(text: String) {
        if (lastCommittedText != null && lastCommittedText == text) return
        if (lastCommittedText != null) {
            undoStack.addLast(lastCommittedText!!)
            // 最多保留 100 步
            if (undoStack.size > 100) undoStack.removeFirst()
        }
        redoStack.clear()
        lastCommittedText = text
        updateUndoRedoButtons()
    }

    /** 撤销 */
    private fun undo() {
        if (undoStack.isEmpty()) return
        if (isRestoringHistory) return
        isRestoringHistory = true
        try {
            val currentText = etContent.text?.toString() ?: return
            redoStack.addLast(currentText)
            val previousText = undoStack.removeLast()
            lastCommittedText = previousText
            etContent.setText(previousText)
            etContent.setSelection(etContent.text?.length ?: 0)
            if (highlightEnabled) applyHighlight()
        } finally {
            isRestoringHistory = false
        }
        updateUndoRedoButtons()
    }

    /** 重做 */
    private fun redo() {
        if (redoStack.isEmpty()) return
        if (isRestoringHistory) return
        isRestoringHistory = true
        try {
            val currentText = etContent.text?.toString() ?: return
            undoStack.addLast(currentText)
            val nextText = redoStack.removeLast()
            lastCommittedText = nextText
            etContent.setText(nextText)
            etContent.setSelection(etContent.text?.length ?: 0)
            if (highlightEnabled) applyHighlight()
        } finally {
            isRestoringHistory = false
        }
        updateUndoRedoButtons()
    }

    /** 更新撤销/重做按钮状态 */
    private fun updateUndoRedoButtons() {
        btnUndo.alpha = if (undoStack.isEmpty()) 0.3f else 1.0f
        btnUndo.isEnabled = undoStack.isNotEmpty()
        btnRedo.alpha = if (redoStack.isEmpty()) 0.3f else 1.0f
        btnRedo.isEnabled = redoStack.isNotEmpty()
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