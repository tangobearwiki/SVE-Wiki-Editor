package com.svewiki.editor.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.svewiki.editor.MainActivity
import com.svewiki.editor.R
import com.svewiki.editor.api.SveWikiApi
import com.svewiki.editor.data.LocalPage
import com.svewiki.editor.data.LocalStorageManager
import com.svewiki.editor.data.PageMeta
import com.svewiki.editor.data.Preferences
import com.svewiki.editor.data.WikiNamespaces
import com.svewiki.editor.sync.SyncEngine
import com.svewiki.editor.util.DiffUtil
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SyncFragment : Fragment() {
    private lateinit var tvTotalPages: TextView
    private lateinit var tvModifiedCount: TextView
    private lateinit var tvTotalSize: TextView
    private lateinit var tvLastSync: TextView
    private lateinit var btnPullAll: Button
    private lateinit var btnSyncRecent: Button
    private lateinit var btnPushSelected: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var llFetchedPages: LinearLayout
    private lateinit var llModifiedPages: LinearLayout
    private lateinit var tvModifiedSummary: TextView
    private lateinit var etSearch: EditText
    private lateinit var btnSearch: Button
    private lateinit var btnDeletePages: Button
    private lateinit var spinnerNamespace: Spinner

    private var api: SveWikiApi? = null
    private var prefs: Preferences? = null
    private var storage: LocalStorageManager? = null
    private var syncEngine: SyncEngine? = null
    private var job: Job? = null
    private var fetchedCount = 0

    // 命名空间筛选
    private val namespaceOptions = listOf(
        0 to "主空间", 2 to "用户", 4 to "站务", 6 to "文件", 8 to "MediaWiki",
        10 to "模板", 12 to "帮助", 14 to "分类", 828 to "模块"
    )
    private var currentNamespaceFilter = 0

    // 已选中的待推送页面（用 "namespace:title" 作为唯一 Key）
    private val selectedPages = mutableSetOf<String>()

    private fun pageKey(page: LocalPage): String = "${page.namespace}:${page.title}"
    private fun pageKey(ns: Int, title: String): String = "$ns:$title"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_sync, container, false)

        tvTotalPages = view.findViewById(R.id.tv_total_pages)
        tvModifiedCount = view.findViewById(R.id.tv_modified_count)
        tvTotalSize = view.findViewById(R.id.tv_total_size)
        tvLastSync = view.findViewById(R.id.tv_last_sync)
        btnPullAll = view.findViewById(R.id.btn_pull_all)
        btnSyncRecent = view.findViewById(R.id.btn_sync_recent)
        btnPushSelected = view.findViewById(R.id.btn_push_selected)
        progressBar = view.findViewById(R.id.progress_bar)
        tvStatus = view.findViewById(R.id.tv_status)
        llFetchedPages = view.findViewById(R.id.ll_fetched_pages)
        llModifiedPages = view.findViewById(R.id.ll_modified_pages)
        tvModifiedSummary = view.findViewById(R.id.tv_modified_summary)
        etSearch = view.findViewById(R.id.et_search)
        btnSearch = view.findViewById(R.id.btn_search)
        spinnerNamespace = view.findViewById(R.id.spinner_namespace)
        btnDeletePages = view.findViewById(R.id.btn_delete_pages)

        val activity = requireActivity()
        if (activity is MainActivity) {
            api = activity.api
            prefs = activity.prefs
            storage = activity.storage
            syncEngine = activity.syncEngine
        }

        btnPullAll.setOnClickListener { pullAll() }
        btnSyncRecent.setOnClickListener { syncRecent() }
        btnPushSelected.setOnClickListener { pushSelected() }
        btnSearch.setOnClickListener { searchLocalPages() }
        btnDeletePages.setOnClickListener { showDeleteDialog() }

        setupNamespaceSpinner()

        refreshOverview()
        refreshModifiedList()
        refreshLocalPageList()

        return view
    }

    override fun onResume() {
        super.onResume()
        refreshOverview()
        refreshModifiedList()
        refreshLocalPageList()
    }

    private fun setupNamespaceSpinner() {
        val names = namespaceOptions.map { it.second }.toTypedArray()
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerNamespace.adapter = adapter
        spinnerNamespace.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentNamespaceFilter = namespaceOptions[position].first
                refreshLocalPageList()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun refreshOverview() {
        val engine = syncEngine ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val overview = withContext(Dispatchers.IO) { engine.getOverview() }

            tvTotalPages.text = overview.totalPages.toString()
            tvModifiedCount.text = overview.modifiedCount.toString()
            tvTotalSize.text = overview.totalSizeFormatted

            tvLastSync.text = if (overview.lastSyncTime > 0) {
                val date = Date(overview.lastSyncTime)
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                "上次同步：${sdf.format(date)}"
            } else {
                "上次同步：从未"
            }
        }
    }

    /** 刷新本地页面列表（默认主空间，按筛选器），IO 线程加载轻量元数据 */
    private fun refreshLocalPageList() {
        val s = storage ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val metas = withContext(Dispatchers.IO) {
                s.loadAllMetas().filter { it.namespace == currentNamespaceFilter }
                    .sortedByDescending {
                        // 已修改的按修改时间排，未修改的按同步时间排
                        if (it.lastModifiedTime > 0) it.lastModifiedTime else it.lastSyncTime
                    }
            }
            llFetchedPages.removeAllViews()
            if (metas.isEmpty()) {
                val tv = TextView(requireContext())
                tv.text = "当前空间暂无页面，点击「一键拉取全站」开始"
                tv.textSize = 13f
                tv.setTextColor(resources.getColor(R.color.text_secondary, null))
                tv.setPadding(8, 16, 8, 16)
                llFetchedPages.addView(tv)
            } else {
                metas.forEach { meta -> llFetchedPages.addView(createMetaRow(meta)) }
            }
        }
    }

    /** 创建带时间信息的行（本地页面列表，轻量文本行，不加载全量 LocalPage） */
    private fun createMetaRow(meta: PageMeta): View {
        val row = LinearLayout(requireContext())
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(12, 10, 12, 10)
        row.isClickable = true
        row.isFocusable = true
        row.background = android.graphics.drawable.ColorDrawable(
            resources.getColor(R.color.surface, null))

        val titleRow = LinearLayout(requireContext())
        titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = android.view.Gravity.CENTER_VERTICAL

        val icon = TextView(requireContext())
        icon.text = if (meta.isModified) "✏️ " else "📄 "
        icon.textSize = 14f

        val nameTv = TextView(requireContext())
        nameTv.text = meta.title
        nameTv.textSize = 14f
        nameTv.setTextColor(if (meta.isModified) resources.getColor(R.color.accent_orange, null)
            else resources.getColor(R.color.text_primary, null))
        nameTv.maxLines = 1
        nameTv.ellipsize = android.text.TextUtils.TruncateAt.END
        nameTv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        titleRow.addView(icon)
        titleRow.addView(nameTv)

        val timeRow = LinearLayout(requireContext())
        timeRow.orientation = LinearLayout.HORIZONTAL
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        val syncTv = TextView(requireContext())
        syncTv.text = "拉取:${if (meta.lastSyncTime > 0) sdf.format(Date(meta.lastSyncTime)) else "-"}"
        syncTv.textSize = 10f
        syncTv.setTextColor(resources.getColor(R.color.text_secondary, null))

        val modTv = TextView(requireContext())
        modTv.text = " 修改:${if (meta.lastModifiedTime > 0) sdf.format(Date(meta.lastModifiedTime)) else "-"}"
        modTv.textSize = 10f
        modTv.setTextColor(if (meta.isModified) resources.getColor(R.color.accent_orange, null)
            else resources.getColor(R.color.text_secondary, null))

        timeRow.addView(syncTv)
        timeRow.addView(modTv)

        // 只有已修改的页面才显示 Diff 按钮（点击时再加载全量页面）
        if (meta.isModified) {
            val diffBtn = Button(requireContext())
            diffBtn.text = "Diff"
            diffBtn.textSize = 10f
            diffBtn.minHeight = 0
            diffBtn.minimumHeight = 0
            diffBtn.setPadding(8, 2, 8, 2)
            diffBtn.setOnClickListener {
                val page = storage?.loadPage(meta.title, meta.namespace)
                if (page != null) showDiff(page)
            }
            timeRow.addView(diffBtn)
        }

        row.addView(titleRow)
        row.addView(timeRow)

        // 点击打开编辑器（加载全量页面）
        row.setOnClickListener {
            val page = storage?.loadPage(meta.title, meta.namespace)
            if (page != null) openPageInEditor(page)
        }

        // 分割线
        val divider = View(requireContext())
        divider.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1)
        divider.setBackgroundColor(resources.getColor(R.color.divider, null))
        row.addView(divider)

        return row
    }

    private fun refreshModifiedList() {
        val s = storage ?: return
        tvModifiedSummary.text = "已修改页面：${s.loadModifiedMetas().size} 个（已选 ${selectedPages.size}）"
        llModifiedPages.removeAllViews()
        val modifiedMetas = s.loadModifiedMetas()
        if (modifiedMetas.isEmpty()) {
            val tv = TextView(requireContext())
            tv.text = "暂无已修改页面"
            tv.textSize = 13f
            tv.setTextColor(resources.getColor(R.color.text_secondary, null))
            tv.setPadding(8, 8, 8, 8)
            llModifiedPages.addView(tv)
        } else {
            modifiedMetas.forEach { meta ->
                val row = createModifiedRow(meta)
                llModifiedPages.addView(row)
            }
        }
    }

    private fun createModifiedRow(meta: PageMeta): View {
        val row = LinearLayout(requireContext())
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(12, 12, 12, 12)
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        row.background = android.graphics.drawable.ColorDrawable(
            resources.getColor(R.color.surface, null))

        val cb = CheckBox(requireContext())
        cb.isChecked = selectedPages.contains(pageKey(meta.namespace, meta.title))
        cb.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) selectedPages.add(pageKey(meta.namespace, meta.title)) else selectedPages.remove(pageKey(meta.namespace, meta.title))
            refreshModifiedList()
        }

        val icon = TextView(requireContext())
        icon.text = "✏️ "
        icon.textSize = 14f

        val nameTv = TextView(requireContext())
        nameTv.text = meta.title
        nameTv.textSize = 13f
        nameTv.setTextColor(resources.getColor(R.color.accent_orange, null))
        nameTv.maxLines = 1
        nameTv.ellipsize = android.text.TextUtils.TruncateAt.END
        nameTv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        val diffBtn = Button(requireContext())
        diffBtn.text = "Diff"
        diffBtn.textSize = 10f
        diffBtn.minHeight = 0
        diffBtn.minimumHeight = 0
        diffBtn.setPadding(8, 2, 8, 2)
        diffBtn.setOnClickListener {
            val page = storage?.loadPage(meta.title, meta.namespace)
            if (page != null) showDiff(page)
        }

        row.addView(cb)
        row.addView(icon)
        row.addView(nameTv)
        row.addView(diffBtn)
        return row
    }

    /** 查看 Diff（本地 vs 服务器最新） */
    private fun showDiff(page: LocalPage) {
        val apiRef = api ?: return
        job = viewLifecycleOwner.lifecycleScope.launch {
            tvStatus.text = "获取服务器版本..."
            val serverResult = withContext(Dispatchers.IO) {
                apiRef.fetchPageForDiff(page.title)
            }
            serverResult.onSuccess { serverPage ->
                // 生成 HTML 左右分栏 diff
                val diffHtml = DiffUtil.diffHtml(serverPage.content, page.content)

                // 用 WebView 对话框显示
                val webView = WebView(requireContext())
                webView.settings.apply {
                    builtInZoomControls = true
                    displayZoomControls = false
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    defaultTextEncodingName = "utf-8"
                }
                webView.loadDataWithBaseURL(null, diffHtml, "text/html", "utf-8", null)

                AlertDialog.Builder(requireContext())
                    .setTitle("Diff - ${page.title}")
                    .setView(webView)
                    .setPositiveButton("关闭", null)
                    .setNegativeButton("复制文本") { _, _ ->
                        val plainText = DiffUtil.diff(serverPage.content, page.content)
                        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("diff", plainText))
                        Toast.makeText(requireContext(), "差异文本已复制", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }.onFailure { e ->
                tvStatus.text = "获取服务器版本失败：${e.message}"
            }
        }
    }

    private fun openPageInEditor(page: LocalPage) {
        val activity = requireActivity()
        if (activity is MainActivity) activity.openEditorWithPage(page)
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
        else -> 0
    }

    /** 搜索本地页面（跨所有命名空间，IO 线程 + 轻量元数据） */
    private fun searchLocalPages() {
        val query = etSearch.text.toString().trim()
        if (query.isEmpty()) {
            Toast.makeText(requireContext(), "请输入搜索关键词", Toast.LENGTH_SHORT).show()
            return
        }
        val s = storage ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                s.loadAllMetas().filter { it.title.contains(query, ignoreCase = true) }
                    .sortedByDescending { it.lastModifiedTime }
                    .map { meta -> s.loadPage(meta.title, meta.namespace) }
                    .filterNotNull()
            }

            tvStatus.text = "搜索到 ${results.size} 个页面"
            llFetchedPages.removeAllViews()
            if (results.isEmpty()) {
                val tv = TextView(requireContext())
                tv.text = "未找到匹配「$query」的页面"
                tv.textSize = 13f
                tv.setTextColor(resources.getColor(R.color.text_secondary, null))
                tv.setPadding(8, 16, 8, 16)
                llFetchedPages.addView(tv)
            } else {
                // 搜索结果要求展示完整页面（含修改状态），用全量 LocalPage 行
                results.forEach { page ->
                    llFetchedPages.addView(createMetaRow(pageToMeta(page)))
                }
                Toast.makeText(requireContext(), "找到 ${results.size} 个页面", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun pageToMeta(page: LocalPage): PageMeta = PageMeta(
        title = page.title,
        namespace = page.namespace,
        pageId = page.pageId,
        revisionId = page.revisionId,
        lastSyncTime = page.lastSyncTime,
        lastModifiedTime = page.lastModifiedTime,
        isModified = page.isModified,
        touched = page.touched,
        sizeBytes = page.content.length.toLong()
    )

    private fun pullAll() {
        val engine = syncEngine ?: run { tvStatus.text = "syncEngine 未初始化"; return }
        val loginPrefs = prefs ?: run { tvStatus.text = "prefs 未初始化"; return }
        if (!loginPrefs.isLoggedIn) {
            tvStatus.text = "请先在「设置」中登录"
            Toast.makeText(requireContext(), "请先登录", Toast.LENGTH_SHORT).show()
            return
        }

        job = viewLifecycleOwner.lifecycleScope.launch {
            btnPullAll.isEnabled = false
            btnPushSelected.isEnabled = false
            progressBar.visibility = View.VISIBLE
            progressBar.isIndeterminate = true
            tvStatus.text = "开始拉取全站..."

            try {
                withContext(Dispatchers.IO) {
                    engine.pullAllPages(
                        overwriteLocal = prefs?.overwriteLocal ?: false,
                        onNamespaceProgress = { nsName, done, total ->
                            lifecycleScope.launch {
                                tvStatus.text = "拉取 $nsName：$done/$total 页"
                                progressBar.isIndeterminate = false
                                progressBar.max = total
                                progressBar.progress = done
                            }
                        },
                        onPageFetched = { /* 标题已通过标题列表回调 */ }
                    )
                }
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    btnPullAll.isEnabled = true
                    btnPushSelected.isEnabled = true
                    refreshOverview()
                    refreshModifiedList()
                    refreshLocalPageList()
                    tvStatus.text = "全站拉取完成！"
                    Toast.makeText(requireContext(), "全站拉取完成", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                btnPullAll.isEnabled = true
                btnPushSelected.isEnabled = true
                tvStatus.text = "拉取异常：${e.message}"
                Log.e("SyncFragment", "拉取异常", e)
            }
        }
    }

    /** 增量同步：只拉取自上次同步以来变更的页面 */
    private fun syncRecent() {
        val engine = syncEngine ?: run { tvStatus.text = "syncEngine 未初始化"; return }
        val loginPrefs = prefs ?: run { tvStatus.text = "prefs 未初始化"; return }
        if (!loginPrefs.isLoggedIn) {
            tvStatus.text = "请先在「设置」中登录"
            Toast.makeText(requireContext(), "请先登录", Toast.LENGTH_SHORT).show()
            return
        }

        job = viewLifecycleOwner.lifecycleScope.launch {
            btnSyncRecent.isEnabled = false
            btnPullAll.isEnabled = false
            btnPushSelected.isEnabled = false
            progressBar.visibility = View.VISIBLE
            progressBar.isIndeterminate = true
            tvStatus.text = "开始增量同步..."

            try {
                val result = withContext(Dispatchers.IO) {
                    engine.syncRecentChanges(
                        onProgress = { _, done, total ->
                            lifecycleScope.launch {
                                tvStatus.text = "增量同步：$done/$total 页"
                                progressBar.isIndeterminate = false
                                progressBar.max = total
                                progressBar.progress = done
                            }
                        },
                        onConflict = { title ->
                            lifecycleScope.launch {
                                tvStatus.text = "⚠️ 本地有修改冲突：$title"
                            }
                        }
                    )
                }
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    btnSyncRecent.isEnabled = true
                    btnPullAll.isEnabled = true
                    btnPushSelected.isEnabled = true
                    refreshOverview()
                    refreshModifiedList()
                    refreshLocalPageList()
                    tvStatus.text = result.message
                    if (result.error != null) {
                        Toast.makeText(requireContext(), "增量同步失败：${result.error}", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                btnSyncRecent.isEnabled = true
                btnPullAll.isEnabled = true
                btnPushSelected.isEnabled = true
                tvStatus.text = "增量同步异常：${e.message}"
                Log.e("SyncFragment", "增量同步异常", e)
            }
        }
    }

    /** 推送选中的页面 */
    private fun pushSelected() {
        val engine = syncEngine ?: return
        val loginPrefs = prefs ?: return
        if (!loginPrefs.isLoggedIn) {
            tvStatus.text = "请先在「设置」中登录"
            Toast.makeText(requireContext(), "请先登录", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedPages.isEmpty()) {
            Toast.makeText(requireContext(), "请先勾选要推送的页面", Toast.LENGTH_SHORT).show()
            return
        }

        val pagesToPush = storage?.loadModifiedPages()?.filter { selectedPages.contains(pageKey(it)) } ?: emptyList()
        if (pagesToPush.isEmpty()) {
            Toast.makeText(requireContext(), "选中的页面没有可推送的修改", Toast.LENGTH_SHORT).show()
            return
        }

        job = viewLifecycleOwner.lifecycleScope.launch {
            btnPushSelected.isEnabled = false
            btnPullAll.isEnabled = false
            progressBar.visibility = View.VISIBLE
            progressBar.isIndeterminate = true
            tvStatus.text = "开始推送选中..."

            val result = withContext(Dispatchers.IO) {
                engine.pushPages(
                    pagesToPush,
                    prefs?.defaultSummary ?: "SVE Wiki 编辑器自动推送",
                    onPageProgress = { title, success ->
                        lifecycleScope.launch {
                            tvStatus.text = if (success) "已推送：$title" else "失败：$title"
                        }
                    },
                    checkConflict = true
                )
            }

            progressBar.visibility = View.GONE
            btnPushSelected.isEnabled = true
            btnPullAll.isEnabled = true
            selectedPages.clear()
            refreshOverview()
            refreshModifiedList()
            refreshLocalPageList()

            val msg = "推送完成：成功 ${result.success.size}，失败 ${result.failed.size}"
            tvStatus.text = msg
            if (result.failed.isNotEmpty()) {
                val sb = StringBuilder()
                result.failed.forEach { (title, reason) -> sb.appendLine("$title: $reason") }
                Toast.makeText(requireContext(), sb.toString(), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        job?.cancel()
    }

    /** 显示批量删除对话框：选择本地页面 + 删除模式 */
    private fun showDeleteDialog() {
        if (prefs?.isLoggedIn != true) {
            tvStatus.text = "请先在「设置」中登录"
            Toast.makeText(requireContext(), "请先登录", Toast.LENGTH_SHORT).show()
            return
        }

        val allPages = storage?.loadAllPages() ?: emptyList()
        if (allPages.isEmpty()) {
            Toast.makeText(requireContext(), "本地没有可删除的页面", Toast.LENGTH_SHORT).show()
            return
        }

        // 用多选对话框列出本地页面（当前筛选：${WikiNamespaces.getDisplayName(currentNamespaceFilter)}）
        val titles = allPages.map { "${WikiNamespaces.getDisplayName(it.namespace)} · ${it.title}" }
            .toTypedArray()
        val checked = BooleanArray(allPages.size)

        AlertDialog.Builder(requireContext())
            .setTitle("选择要删除的页面（可多选）")
            .setMultiChoiceItems(titles, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("下一步") { _, _ ->
                val selected = allPages.filterIndexed { index, _ -> checked[index] }
                if (selected.isEmpty()) {
                    Toast.makeText(requireContext(), "未选择任何页面", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                showDeleteModeDialog(selected)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 选择删除模式：仅本地 / 仅云端 / 全部，并确认执行 */
    private fun showDeleteModeDialog(pages: List<LocalPage>) {
        val modes = arrayOf("仅删除本地", "仅删除云端", "删除本地 + 云端")
        AlertDialog.Builder(requireContext())
            .setTitle("选择删除方式（${pages.size} 个页面）")
            .setItems(modes) { _, which ->
                val mode = when (which) {
                    0 -> 1
                    1 -> 0
                    else -> 2
                }
                confirmDelete(pages, mode)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 二次确认后执行批量删除 */
    private fun confirmDelete(pages: List<LocalPage>, mode: Int) {
        val modeName = when (mode) {
            0 -> "仅删除云端"
            1 -> "仅删除本地"
            else -> "删除本地 + 云端"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("确认删除")
            .setMessage("即将${modeName} ${pages.size} 个页面，此操作不可恢复！\n\n${pages.joinToString("\n") { it.title }}")
            .setPositiveButton("确认删除") { _, _ -> executeDelete(pages, mode) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 执行批量删除 */
    private fun executeDelete(pages: List<LocalPage>, mode: Int) {
        val engine = syncEngine ?: return
        job = viewLifecycleOwner.lifecycleScope.launch {
            btnDeletePages.isEnabled = false
            btnPullAll.isEnabled = false
            btnPushSelected.isEnabled = false
            progressBar.visibility = View.VISIBLE
            progressBar.isIndeterminate = true
            tvStatus.text = "开始删除..."

            val pagePairs = pages.map { it.title to it.namespace }
            val result = withContext(Dispatchers.IO) {
                engine.deletePages(
                    pages = pagePairs,
                    reason = prefs?.defaultSummary ?: "批量删除",
                    deleteMode = mode
                ) { title, success ->
                    lifecycleScope.launch {
                        tvStatus.text = if (success) "已删除：$title" else "失败：$title"
                    }
                }
            }

            progressBar.visibility = View.GONE
            btnDeletePages.isEnabled = true
            btnPullAll.isEnabled = true
            btnPushSelected.isEnabled = true
            selectedPages.clear()
            refreshOverview()
            refreshModifiedList()
            refreshLocalPageList()

            val msg = "删除完成：成功 ${result.success.size}，失败 ${result.failed.size}"
            tvStatus.text = msg
            if (result.failed.isNotEmpty()) {
                val sb = StringBuilder()
                result.failed.forEach { (title, reason) -> sb.appendLine("$title: $reason") }
                Toast.makeText(requireContext(), sb.toString(), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
        }
    }
}