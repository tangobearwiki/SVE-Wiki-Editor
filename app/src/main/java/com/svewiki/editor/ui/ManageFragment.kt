package com.svewiki.editor.ui

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.svewiki.editor.MainActivity
import com.svewiki.editor.R
import com.svewiki.editor.api.SveWikiApi
import com.svewiki.editor.data.LocalPage
import com.svewiki.editor.data.LocalStorageManager
import com.svewiki.editor.data.Preferences
import com.svewiki.editor.data.WikiNamespaces
import com.svewiki.editor.sync.SyncEngine
import kotlinx.coroutines.*

class ManageFragment : Fragment() {
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tabHost: RadioGroup

    // Delete
    private lateinit var deleteLayout: View
    private lateinit var deletePageList: LinearLayout
    private lateinit var btnDeleteExecute: Button
    private lateinit var etDeleteSearch: EditText
    private lateinit var spinnerDeleteNamespace: Spinner
    private lateinit var btnDeleteSelectAll: Button
    private lateinit var btnDeleteClear: Button
    private val selectedDeletePages = mutableSetOf<String>()
    private var deleteMode = 2 // 0=云, 1=本地, 2=全部
    private var deleteNamespaceFilter = -1 // -1=全部
    private var deleteSearchQuery = ""

    private val namespaceOptions = listOf(
        0 to "主空间", 2 to "用户", 4 to "站务", 6 to "文件", 8 to "MediaWiki",
        10 to "模板", 12 to "帮助", 14 to "分类", 828 to "模块"
    )

    // Move
    private lateinit var moveLayout: View
    private lateinit var etMoveFrom: EditText
    private lateinit var etMoveTo: EditText
    private lateinit var etMoveReason: EditText
    private lateinit var cbMoveTalk: CheckBox
    private lateinit var cbMoveSubpages: CheckBox
    private lateinit var btnMoveExecute: Button

    // ReplaceText
    private lateinit var replaceLayout: View
    private lateinit var etReplaceFind: EditText
    private lateinit var etReplaceWith: EditText
    private lateinit var etReplaceSummary: EditText
    private lateinit var cbReplaceRegex: CheckBox
    private lateinit var cbReplaceCase: CheckBox
    private lateinit var btnReplaceSearch: Button
    private lateinit var btnReplaceExecute: Button
    private lateinit var replaceResultList: LinearLayout
    private var replaceSearchResults = mutableListOf<String>()
    private val selectedReplacePages = mutableSetOf<String>()

    private var api: SveWikiApi? = null
    private var prefs: Preferences? = null
    private var storage: LocalStorageManager? = null
    private var syncEngine: SyncEngine? = null
    private var job: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_manage, container, false)

        tvStatus = view.findViewById(R.id.tv_manage_status)
        progressBar = view.findViewById(R.id.progress_bar_manage)
        tabHost = view.findViewById(R.id.radio_group_tabs)
        deleteLayout = view.findViewById(R.id.layout_delete)
        deletePageList = view.findViewById(R.id.ll_delete_pages)
        btnDeleteExecute = view.findViewById(R.id.btn_delete_execute)
        etDeleteSearch = view.findViewById(R.id.et_delete_search)
        spinnerDeleteNamespace = view.findViewById(R.id.spinner_delete_namespace)
        btnDeleteSelectAll = view.findViewById(R.id.btn_delete_select_all)
        btnDeleteClear = view.findViewById(R.id.btn_delete_clear)
        moveLayout = view.findViewById(R.id.layout_move)
        etMoveFrom = view.findViewById(R.id.et_move_from)
        etMoveTo = view.findViewById(R.id.et_move_to)
        etMoveReason = view.findViewById(R.id.et_move_reason)
        cbMoveTalk = view.findViewById(R.id.cb_move_talk)
        cbMoveSubpages = view.findViewById(R.id.cb_move_subpages)
        btnMoveExecute = view.findViewById(R.id.btn_move_execute)
        replaceLayout = view.findViewById(R.id.layout_replace)
        etReplaceFind = view.findViewById(R.id.et_replace_find)
        etReplaceWith = view.findViewById(R.id.et_replace_with)
        etReplaceSummary = view.findViewById(R.id.et_replace_summary)
        cbReplaceRegex = view.findViewById(R.id.cb_replace_regex)
        cbReplaceCase = view.findViewById(R.id.cb_replace_case)
        btnReplaceSearch = view.findViewById(R.id.btn_replace_search)
        btnReplaceExecute = view.findViewById(R.id.btn_replace_execute)
        replaceResultList = view.findViewById(R.id.ll_replace_results)

        val activity = requireActivity()
        if (activity is MainActivity) {
            api = activity.api
            prefs = activity.prefs
            storage = activity.storage
            syncEngine = activity.syncEngine
        }

        // Tab 切换
        tabHost.setOnCheckedChangeListener { _, checkedId ->
            deleteLayout.visibility = if (checkedId == R.id.radio_delete) View.VISIBLE else View.GONE
            moveLayout.visibility = if (checkedId == R.id.radio_move) View.VISIBLE else View.GONE
            replaceLayout.visibility = if (checkedId == R.id.radio_replace) View.VISIBLE else View.GONE
            if (checkedId == R.id.radio_delete) refreshDeleteList()
        }

        // 删除空间筛选 Spinner
        val nsOptions = mutableListOf("全部空间")
        nsOptions.addAll(namespaceOptions.map { it.second })
        val nsAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, nsOptions)
        nsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerDeleteNamespace.adapter = nsAdapter
        spinnerDeleteNamespace.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                deleteNamespaceFilter = if (position == 0) -1 else namespaceOptions[position - 1].first
                refreshDeleteList()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 删除搜索监听
        etDeleteSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                deleteSearchQuery = s?.toString()?.trim() ?: ""
                refreshDeleteList()
            }
        })

        // 全选/清空
        btnDeleteSelectAll.setOnClickListener {
            val filtered = getFilteredDeletePages()
            selectedDeletePages.clear()
            filtered.forEach { selectedDeletePages.add(pageKey(it)) }
            refreshDeleteList()
        }
        btnDeleteClear.setOnClickListener {
            selectedDeletePages.clear()
            refreshDeleteList()
        }

        // 删除模式选择
        view.findViewById<RadioGroup>(R.id.radio_group_delete_mode).setOnCheckedChangeListener { _, id ->
            deleteMode = when (id) {
                R.id.radio_delete_local -> 1
                R.id.radio_delete_cloud -> 0
                else -> 2
            }
        }
        btnDeleteExecute.setOnClickListener { executeDelete() }

        // 移动
        btnMoveExecute.setOnClickListener { executeMove() }

        // 替换
        btnReplaceSearch.setOnClickListener { searchReplacePages() }
        btnReplaceExecute.setOnClickListener { executeReplace() }

        selectTab(R.id.radio_delete)
        return view
    }

    private fun selectTab(id: Int) {
        tabHost.check(id)
    }

    private fun checkLogin(): Boolean {
        if (prefs?.isLoggedIn != true) {
            tvStatus.text = "请先在设置中登录"
            Toast.makeText(requireContext(), "请先登录", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    // ==================== 删除 ====================

    override fun onResume() {
        super.onResume()
        if (deleteLayout.visibility == View.VISIBLE) refreshDeleteList()
    }

    private fun refreshDeleteList() {
        val filtered = getFilteredDeletePages()
        deletePageList.removeAllViews()
        if (filtered.isEmpty()) {
            deletePageList.addView(TextView(requireContext()).apply {
                text = if (deleteSearchQuery.isNotEmpty()) "未找到匹配「$deleteSearchQuery」的页面" else "本地暂无页面，请先拉取"
                textSize = 13f
                setTextColor(resources.getColor(R.color.text_secondary, null))
                setPadding(8, 16, 8, 16)
            })
            return
        }
        filtered.forEach { page ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(8, 6, 8, 6)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val cb = CheckBox(requireContext()).apply {
                isChecked = selectedDeletePages.contains(pageKey(page))
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedDeletePages.add(pageKey(page))
                    else selectedDeletePages.remove(pageKey(page))
                }
            }
            val tv = TextView(requireContext()).apply {
                text = "${WikiNamespaces.getDisplayName(page.namespace)} · ${page.title}"
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(cb)
            row.addView(tv)
            deletePageList.addView(row)
        }
        tvStatus.text = "共 ${filtered.size} 个页面，已选 ${selectedDeletePages.size}"
    }

    /** 获取经过搜索和空间筛选后的页面列表 */
    private fun getFilteredDeletePages(): List<LocalPage> {
        val allPages = storage?.loadAllPages() ?: return emptyList()
        return allPages.filter { page ->
            val nsMatch = deleteNamespaceFilter == -1 || page.namespace == deleteNamespaceFilter
            val searchMatch = deleteSearchQuery.isEmpty() ||
                page.title.contains(deleteSearchQuery, ignoreCase = true)
            nsMatch && searchMatch
        }
    }

    private fun executeDelete() {
        if (!checkLogin()) return
        if (selectedDeletePages.isEmpty()) {
            Toast.makeText(requireContext(), "请先选择要删除的页面", Toast.LENGTH_SHORT).show()
            return
        }
        val allPages = storage?.loadAllPages() ?: emptyList()
        val pagesToDelete = allPages.filter { selectedDeletePages.contains(pageKey(it)) }
        val modeName = when (deleteMode) { 0 -> "仅云端"; 1 -> "仅本地"; else -> "本地+云端" }

        // 第一级确认：基本信息
        AlertDialog.Builder(requireContext())
            .setTitle("⚠️ 确认删除 ${pagesToDelete.size} 个页面")
            .setMessage("模式：$modeName\n\n${pagesToDelete.joinToString("\n") { it.title }}\n\n此操作不可恢复！")
            .setPositiveButton("下一步") { _, _ -> showSecondConfirm(pagesToDelete) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 二级确认：输入 "confirm" 确认 */
    private fun showSecondConfirm(pagesToDelete: List<LocalPage>) {
        val input = EditText(requireContext())
        input.hint = "请输入 confirm 确认删除"
        input.textSize = 14f
        input.setPadding(16, 12, 16, 12)

        val modeName = when (deleteMode) { 0 -> "仅云端"; 1 -> "仅本地"; else -> "本地+云端" }

        AlertDialog.Builder(requireContext())
            .setTitle("⚠️ 二次确认（${pagesToDelete.size} 个页面）")
            .setMessage("模式：$modeName\n输入 confirm 后点击确认删除")
            .setView(input)
            .setPositiveButton("确认删除") { _, _ ->
                if (input.text.toString().trim() != "confirm") {
                    Toast.makeText(requireContext(), "请输入 confirm 确认", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val engine = syncEngine ?: return@setPositiveButton
                job = viewLifecycleOwner.lifecycleScope.launch {
                    setButtonsEnabled(false)
                    progressBar.visibility = View.VISIBLE
                    tvStatus.text = "删除中..."
                    val result = withContext(Dispatchers.IO) {
                        engine.deletePages(
                            pages = pagesToDelete.map { it.title to it.namespace },
                            deleteMode = deleteMode
                        ) { title, success ->
                            lifecycleScope.launch { tvStatus.text = if (success) "已删除：$title" else "失败：$title" }
                        }
                    }
                    progressBar.visibility = View.GONE
                    setButtonsEnabled(true)
                    selectedDeletePages.clear()
                    refreshDeleteList()
                    tvStatus.text = "删除完成：成功 ${result.success.size}，失败 ${result.failed.size}"
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 移动 ====================

    private fun executeMove() {
        if (!checkLogin()) return
        val from = etMoveFrom.text.toString().trim()
        val to = etMoveTo.text.toString().trim()
        if (from.isEmpty() || to.isEmpty()) {
            Toast.makeText(requireContext(), "请填写原页面名和新页面名", Toast.LENGTH_SHORT).show()
            return
        }
        val reason = etMoveReason.text.toString().trim().ifEmpty { "移动页面" }

        AlertDialog.Builder(requireContext())
            .setTitle("确认移动")
            .setMessage("将「$from」移动到「$to」\n原因：$reason")
            .setPositiveButton("确认移动") { _, _ ->
                job = viewLifecycleOwner.lifecycleScope.launch {
                    setButtonsEnabled(false)
                    progressBar.visibility = View.VISIBLE
                    tvStatus.text = "移动中..."
                    val result = withContext(Dispatchers.IO) {
                        api?.movePage(from, to, reason, cbMoveTalk.isChecked, cbMoveSubpages.isChecked)
                    }
                    progressBar.visibility = View.GONE
                    setButtonsEnabled(true)
                    result?.onSuccess {
                        tvStatus.text = "移动成功：$from → $to"
                        etMoveFrom.text.clear()
                        etMoveTo.text.clear()
                        Toast.makeText(requireContext(), "移动成功", Toast.LENGTH_SHORT).show()
                    }?.onFailure { e ->
                        tvStatus.text = "移动失败：${e.message}"
                        Toast.makeText(requireContext(), "移动失败：${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 替换文本 ====================

    private fun searchReplacePages() {
        val find = etReplaceFind.text.toString().trim()
        if (find.isEmpty()) {
            Toast.makeText(requireContext(), "请输入查找内容", Toast.LENGTH_SHORT).show()
            return
        }
        val allPages = storage?.loadAllPages() ?: emptyList()
        val regex = cbReplaceRegex.isChecked
        val ignoreCase = cbReplaceCase.isChecked

        replaceSearchResults.clear()
        selectedReplacePages.clear()
        replaceResultList.removeAllViews()
        tvStatus.text = "搜索中..."

        val pattern = try {
            if (regex) {
                if (ignoreCase) Regex(find, RegexOption.IGNORE_CASE) else Regex(find)
            } else null
        } catch (e: Exception) {
            tvStatus.text = "正则表达式错误：${e.message}"
            return
        }

        for (page in allPages) {
            val content = page.content
            val matched = if (regex) {
                pattern?.containsMatchIn(content) ?: false
            } else {
                if (ignoreCase) content.contains(find, ignoreCase = true) else content.contains(find)
            }
            if (matched) {
                replaceSearchResults.add(page.title)
            }
        }

        if (replaceSearchResults.isEmpty()) {
            replaceResultList.addView(TextView(requireContext()).apply {
                text = "未找到匹配的页面"
                textSize = 13f
                setTextColor(resources.getColor(R.color.text_secondary, null))
                setPadding(8, 8, 8, 8)
            })
            tvStatus.text = "搜索完成，未找到匹配"
            return
        }

        replaceSearchResults.forEach { title ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(8, 4, 8, 4)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val cb = CheckBox(requireContext()).apply {
                isChecked = true
                selectedReplacePages.add(title)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedReplacePages.add(title) else selectedReplacePages.remove(title)
                }
            }
            val tv = TextView(requireContext()).apply {
                text = title
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(cb)
            row.addView(tv)
            replaceResultList.addView(row)
        }
        tvStatus.text = "找到 ${replaceSearchResults.size} 个页面，已选 ${selectedReplacePages.size}"
        btnReplaceExecute.isEnabled = true
    }

    private fun executeReplace() {
        if (!checkLogin()) return
        val find = etReplaceFind.text.toString().trim()
        val replace = etReplaceWith.text.toString()
        val summary = etReplaceSummary.text.toString().trim().ifEmpty { "批量替换" }
        if (find.isEmpty() || selectedReplacePages.isEmpty()) {
            Toast.makeText(requireContext(), "请先搜索并选择页面", Toast.LENGTH_SHORT).show()
            return
        }

        val pages = selectedReplacePages.toList()
        val rule = com.svewiki.editor.data.BatchReplaceRule(
            find = find, replace = replace,
            regex = cbReplaceRegex.isChecked,
            ignoreCase = cbReplaceCase.isChecked
        )

        AlertDialog.Builder(requireContext())
            .setTitle("确认替换")
            .setMessage("查找「$find」→ 替换为「$replace」\n共 ${pages.size} 个页面")
            .setPositiveButton("确认替换") { _, _ ->
                job = viewLifecycleOwner.lifecycleScope.launch {
                    setButtonsEnabled(false)
                    progressBar.visibility = View.VISIBLE
                    tvStatus.text = "替换中..."
                    val result = withContext(Dispatchers.IO) {
                        api?.batchReplace(listOf(rule), pages, summary) { title, success ->
                            lifecycleScope.launch { tvStatus.text = if (success) "已替换：$title" else "失败：$title" }
                        }
                    }
                    progressBar.visibility = View.GONE
                    setButtonsEnabled(true)
                    result?.onSuccess { r ->
                        tvStatus.text = "替换完成：成功 ${r.success.size}，失败 ${r.failed.size}，跳过 ${r.skipped.size}"
                    }?.onFailure { e ->
                        tvStatus.text = "替换失败：${e.message}"
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        btnDeleteExecute.isEnabled = enabled
        btnMoveExecute.isEnabled = enabled
        btnReplaceSearch.isEnabled = enabled
        btnReplaceExecute.isEnabled = enabled
    }

    private fun pageKey(page: LocalPage): String = "${page.namespace}:${page.title}"

    override fun onDestroyView() {
        super.onDestroyView()
        job?.cancel()
    }
}