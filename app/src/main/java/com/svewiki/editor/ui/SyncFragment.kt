package com.svewiki.editor.ui

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.svewiki.editor.MainActivity
import com.svewiki.editor.R
import com.svewiki.editor.api.SveWikiApi
import com.svewiki.editor.data.LocalPage
import com.svewiki.editor.data.LocalStorageManager
import com.svewiki.editor.data.Preferences
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
    private lateinit var btnPushSelected: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var llFetchedPages: LinearLayout
    private lateinit var llModifiedPages: LinearLayout
    private lateinit var tvModifiedSummary: TextView
    private lateinit var etSearch: EditText
    private lateinit var btnSearch: Button
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
        btnPushSelected = view.findViewById(R.id.btn_push_selected)
        progressBar = view.findViewById(R.id.progress_bar)
        tvStatus = view.findViewById(R.id.tv_status)
        llFetchedPages = view.findViewById(R.id.ll_fetched_pages)
        llModifiedPages = view.findViewById(R.id.ll_modified_pages)
        tvModifiedSummary = view.findViewById(R.id.tv_modified_summary)
        etSearch = view.findViewById(R.id.et_search)
        btnSearch = view.findViewById(R.id.btn_search)
        spinnerNamespace = view.findViewById(R.id.spinner_namespace)

        val activity = requireActivity()
        if (activity is MainActivity) {
            api = activity.api
            prefs = activity.prefs
            storage = activity.storage
            syncEngine = activity.syncEngine
        }

        btnPullAll.setOnClickListener { pullAll() }
        btnPushSelected.setOnClickListener { pushSelected() }
        btnSearch.setOnClickListener { searchLocalPages() }

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
        val overview = engine.getOverview()

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

    /** 刷新本地页面列表（默认主空间，按筛选器） */
    private fun refreshLocalPageList() {
        val allPages = storage?.loadAllPages()?.filter { it.namespace == currentNamespaceFilter }
            ?.sortedByDescending { it.lastModifiedTime } ?: emptyList()
        llFetchedPages.removeAllViews()
        if (allPages.isEmpty()) {
            val tv = TextView(requireContext())
            tv.text = "当前空间暂无页面，点击「一键拉取全站」开始"
            tv.textSize = 13f
            tv.setTextColor(resources.getColor(R.color.text_secondary, null))
            tv.setPadding(8, 16, 8, 16)
            llFetchedPages.addView(tv)
        } else {
            allPages.forEach { page -> llFetchedPages.addView(createTimeRow(page)) }
        }
    }

    /** 创建带时间信息的行 */
    private fun createTimeRow(page: LocalPage): View {
        val row = LinearLayout(requireContext())
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(8, 6, 8, 6)
        row.isClickable = true
        row.isFocusable = true

        val titleRow = LinearLayout(requireContext())
        titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = android.view.Gravity.CENTER_VERTICAL

        val icon = TextView(requireContext())
        icon.text = if (page.isModified) "✏️ " else "📄 "
        icon.textSize = 14f

        val nameTv = TextView(requireContext())
        nameTv.text = page.title
        nameTv.textSize = 14f
        nameTv.setTextColor(if (page.isModified) resources.getColor(R.color.accent_orange, null)
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
        syncTv.text = "拉取:${if (page.lastSyncTime > 0) sdf.format(Date(page.lastSyncTime)) else "-"}"
        syncTv.textSize = 10f
        syncTv.setTextColor(resources.getColor(R.color.text_secondary, null))

        val modTv = TextView(requireContext())
        modTv.text = " 修改:${if (page.lastModifiedTime > 0) sdf.format(Date(page.lastModifiedTime)) else "-"}"
        modTv.textSize = 10f
        modTv.setTextColor(if (page.isModified) resources.getColor(R.color.accent_orange, null)
            else resources.getColor(R.color.text_secondary, null))

        timeRow.addView(syncTv)
        timeRow.addView(modTv)

        // 只有已修改的页面才显示 Diff 按钮
        if (page.isModified) {
            val diffBtn = Button(requireContext())
            diffBtn.text = "Diff"
            diffBtn.textSize = 10f
            diffBtn.setOnClickListener { showDiff(page) }
            timeRow.addView(diffBtn)
        }

        row.addView(titleRow)
        row.addView(timeRow)

        // 点击打开编辑器
        row.setOnClickListener { openPageInEditor(page) }

        // 分割线
        val divider = View(requireContext())
        divider.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1)
        divider.setBackgroundColor(resources.getColor(R.color.divider, null))
        row.addView(divider)

        return row
    }

    private fun refreshModifiedList() {
        val modifiedPages = storage?.loadModifiedPages() ?: emptyList()
        tvModifiedSummary.text = "已修改页面：${modifiedPages.size} 个（已选 ${selectedPages.size}）"
        llModifiedPages.removeAllViews()
        if (modifiedPages.isEmpty()) {
            val tv = TextView(requireContext())
            tv.text = "暂无已修改页面"
            tv.textSize = 13f
            tv.setTextColor(resources.getColor(R.color.text_secondary, null))
            tv.setPadding(8, 8, 8, 8)
            llModifiedPages.addView(tv)
        } else {
            modifiedPages.forEach { page ->
                val row = createModifiedRow(page)
                llModifiedPages.addView(row)
            }
        }
    }

    private fun createModifiedRow(page: LocalPage): View {
        val row = LinearLayout(requireContext())
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(8, 8, 8, 8)
        row.gravity = android.view.Gravity.CENTER_VERTICAL

        val cb = CheckBox(requireContext())
        cb.isChecked = selectedPages.contains(pageKey(page))
        cb.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) selectedPages.add(pageKey(page)) else selectedPages.remove(pageKey(page))
            refreshModifiedList()
        }

        val icon = TextView(requireContext())
        icon.text = "✏️ "
        icon.textSize = 14f

        val nameTv = TextView(requireContext())
        nameTv.text = page.title
        nameTv.textSize = 13f
        nameTv.setTextColor(resources.getColor(R.color.accent_orange, null))
        nameTv.maxLines = 1
        nameTv.ellipsize = android.text.TextUtils.TruncateAt.END
        nameTv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        val diffBtn = Button(requireContext())
        diffBtn.text = "Diff"
        diffBtn.textSize = 10f
        diffBtn.setOnClickListener { showDiff(page) }

        row.addView(cb)
        row.addView(icon)
        row.addView(nameTv)
        row.addView(diffBtn)
        return row
    }

    /** 查看 Diff（本地 vs 服务器最新） */
    private fun showDiff(page: LocalPage) {
        val apiRef = api ?: return
        job = CoroutineScope(Dispatchers.Main).launch {
            tvStatus.text = "获取服务器版本..."
            val serverResult = withContext(Dispatchers.IO) {
                apiRef.fetchPageForDiff(page.title)
            }
            serverResult.onSuccess { serverPage ->
                val diffText = DiffUtil.diff(serverPage.content, page.content)
                AlertDialog.Builder(requireContext())
                    .setTitle("Diff - ${page.title}")
                    .setMessage(diffText)
                    .setPositiveButton("确定", null)
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

    /** 搜索本地页面（跨所有命名空间） */
    private fun searchLocalPages() {
        val query = etSearch.text.toString().trim()
        if (query.isEmpty()) {
            Toast.makeText(requireContext(), "请输入搜索关键词", Toast.LENGTH_SHORT).show()
            return
        }
        val allPages = storage?.loadAllPages() ?: emptyList()
        val results = allPages.filter { it.title.contains(query, ignoreCase = true) }
            .sortedByDescending { it.lastModifiedTime }

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
            results.forEach { page -> llFetchedPages.addView(createTimeRow(page)) }
            Toast.makeText(requireContext(), "找到 ${results.size} 个页面", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pullAll() {
        val engine = syncEngine ?: run { tvStatus.text = "syncEngine 未初始化"; return }
        val loginPrefs = prefs ?: run { tvStatus.text = "prefs 未初始化"; return }
        if (!loginPrefs.isLoggedIn) {
            tvStatus.text = "请先在「设置」中登录"
            Toast.makeText(requireContext(), "请先登录", Toast.LENGTH_SHORT).show()
            return
        }

        job = CoroutineScope(Dispatchers.Main).launch {
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
                            requireActivity().runOnUiThread {
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

        job = CoroutineScope(Dispatchers.Main).launch {
            btnPushSelected.isEnabled = false
            btnPullAll.isEnabled = false
            progressBar.visibility = View.VISIBLE
            progressBar.isIndeterminate = true
            tvStatus.text = "开始推送选中..."

            val result = withContext(Dispatchers.IO) {
                engine.pushPages(pagesToPush, prefs?.defaultSummary ?: "SVE Wiki 编辑器自动推送") { title, success ->
                    requireActivity().runOnUiThread {
                        tvStatus.text = if (success) "已推送：$title" else "失败：$title"
                    }
                }
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
}