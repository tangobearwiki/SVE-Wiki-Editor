package com.svewiki.editor.data

/**
 * MediaWiki 命名空间信息
 */
data class NamespaceInfo(
    val id: Int,
    val name: String,
    val localizedName: String = "",
    val pageCount: Int = 0
)

/**
 * Wiki 命名空间常量
 */
object WikiNamespaces {
    const val MAIN = 0
    const val TALK = 1
    const val USER = 2
    const val USER_TALK = 3
    const val PROJECT = 4
    const val PROJECT_TALK = 5
    const val FILE = 6
    const val FILE_TALK = 7
    const val MEDIAWIKI = 8
    const val MEDIAWIKI_TALK = 9
    const val TEMPLATE = 10
    const val TEMPLATE_TALK = 11
    const val HELP = 12
    const val HELP_TALK = 13
    const val CATEGORY = 14
    const val CATEGORY_TALK = 15
    const val MODULE = 828
    const val MODULE_TALK = 829

    /** 同步默认覆盖的内容命名空间（不含讨论页） */
    val SYNC_IDS: List<Int> = listOf(
        MAIN, USER, PROJECT, FILE, MEDIAWIKI, TEMPLATE, HELP, CATEGORY, MODULE
    )

    fun getDisplayName(id: Int): String = when (id) {
        MAIN -> "主空间"
        TALK -> "讨论"
        USER -> "用户"
        USER_TALK -> "用户讨论"
        FILE -> "文件"
        FILE_TALK -> "文件讨论"
        TEMPLATE -> "模板"
        TEMPLATE_TALK -> "模板讨论"
        CATEGORY -> "分类"
        CATEGORY_TALK -> "分类讨论"
        HELP -> "帮助"
        HELP_TALK -> "帮助讨论"
        MEDIAWIKI -> "MediaWiki"
        MEDIAWIKI_TALK -> "MediaWiki讨论"
        PROJECT -> "站务"
        PROJECT_TALK -> "站务讨论"
        MODULE -> "模块"
        MODULE_TALK -> "模块讨论"
        else -> "命名空间 $id"
    }

    fun getPrefix(id: Int): String = when (id) {
        MAIN -> ""
        TALK -> "讨论:"
        USER -> "用户:"
        USER_TALK -> "用户讨论:"
        FILE -> "文件:"
        FILE_TALK -> "文件讨论:"
        TEMPLATE -> "模板:"
        TEMPLATE_TALK -> "模板讨论:"
        CATEGORY -> "分类:"
        CATEGORY_TALK -> "分类讨论:"
        HELP -> "帮助:"
        HELP_TALK -> "帮助讨论:"
        MEDIAWIKI -> "MediaWiki:"
        MEDIAWIKI_TALK -> "MediaWiki讨论:"
        PROJECT -> "站务:"
        PROJECT_TALK -> "站务讨论:"
        MODULE -> "模块:"
        MODULE_TALK -> "模块讨论:"
        else -> "Ns$id:"
    }

    /**
     * 根据完整标题推断命名空间。
     * 长前缀优先，兼容中英两种 MediaWiki 前缀。
     */
    fun detectFromTitle(title: String): Int {
        val prefixes = listOf(
            "用户讨论:" to USER_TALK, "User talk:" to USER_TALK,
            "模板讨论:" to TEMPLATE_TALK, "Template talk:" to TEMPLATE_TALK,
            "分类讨论:" to CATEGORY_TALK, "Category talk:" to CATEGORY_TALK,
            "文件讨论:" to FILE_TALK, "File talk:" to FILE_TALK,
            "帮助讨论:" to HELP_TALK, "Help talk:" to HELP_TALK,
            "站务讨论:" to PROJECT_TALK, "Project talk:" to PROJECT_TALK,
            "MediaWiki讨论:" to MEDIAWIKI_TALK, "MediaWiki talk:" to MEDIAWIKI_TALK,
            "模块讨论:" to MODULE_TALK, "Module talk:" to MODULE_TALK,
            "用户:" to USER, "User:" to USER,
            "模板:" to TEMPLATE, "Template:" to TEMPLATE,
            "分类:" to CATEGORY, "Category:" to CATEGORY,
            "文件:" to FILE, "File:" to FILE, "Image:" to FILE,
            "帮助:" to HELP, "Help:" to HELP,
            "站务:" to PROJECT, "Project:" to PROJECT,
            "MediaWiki:" to MEDIAWIKI,
            "模块:" to MODULE, "Module:" to MODULE,
            "讨论:" to TALK, "Talk:" to TALK
        )
        for ((prefix, id) in prefixes) {
            if (title.startsWith(prefix, ignoreCase = true)) return id
        }
        return MAIN
    }
}

/**
 * 本地存储的页面。content 放在最后，新写入的 JSON 便于只读元数据。
 */
data class LocalPage(
    val title: String,
    val namespace: Int = 0,
    val revisionId: Long = 0,
    val lastSyncTime: Long = 0,
    val lastModifiedTime: Long = 0,
    val isModified: Boolean = false,
    val pageId: Long = 0,
    val touched: String = "",
    val content: String = ""
)

/**
 * 页面轻量元数据（不含 content）
 */
data class PageMeta(
    val title: String,
    val namespace: Int = 0,
    val pageId: Long = 0,
    val revisionId: Long = 0,
    val lastSyncTime: Long = 0,
    val lastModifiedTime: Long = 0,
    val isModified: Boolean = false,
    val touched: String = "",
    val sizeBytes: Long = 0
) {
    val key: String get() = "$namespace:$title"
}

enum class SyncStatus {
    IDLE, LOGIN, FETCHING_NAMESPACES, FETCHING_PAGES, PUSHING, COMPLETED, ERROR
}

data class SyncProgress(
    val status: SyncStatus = SyncStatus.IDLE,
    val currentNamespace: String = "",
    val currentPage: String = "",
    val totalPages: Int = 0,
    val processedPages: Int = 0,
    val message: String = ""
) {
    val fraction: Float
        get() = if (totalPages <= 0) 0f else (processedPages.toFloat() / totalPages).coerceIn(0f, 1f)
}

data class UserInfo(
    val name: String = "",
    val editCount: Int = 0,
    val registration: String = "",
    val groups: List<String> = emptyList(),
    val isLoggedIn: Boolean = false
)

object UserGroups {
    val displayNames = mapOf(
        "bot" to "机器人",
        "sysop" to "管理员",
        "bureaucrat" to "行政员",
        "interface-admin" to "界面管理员",
        "suppress" to "监督员",
        "checkuser" to "查核员",
        "autoreview" to "自动巡查员",
        "patroller" to "巡查员",
        "editor" to "编辑者",
        "autoconfirmed" to "自动确认用户",
        "user" to "用户",
        "*" to "所有用户"
    )

    fun getDisplayName(group: String): String = displayNames[group] ?: group
}

data class PushResult(
    val success: MutableList<String> = mutableListOf(),
    val failed: MutableList<Pair<String, String>> = mutableListOf(),
    val skipped: MutableList<String> = mutableListOf()
)

data class LoginTokens(
    val loginToken: String = "",
    val csrfToken: String = ""
)

data class WikiPage(
    val title: String,
    val content: String,
    val revisionId: Long = 0,
    val touched: String = ""
)

data class BatchReplaceRule(
    val find: String,
    val replace: String,
    val ignoreCase: Boolean = false,
    val regex: Boolean = false
)

data class PushJob(
    val pageTitle: String,
    val summary: String,
    val content: String,
    val isAuto: Boolean = false
)

data class ServerMessage(
    val success: Boolean,
    val message: String
)

data class SearchResult(
    val title: String,
    val snippet: String
)

/** 本地库一次扫描得到的统计，避免多次全量遍历 */
data class StorageStats(
    val totalPages: Int = 0,
    val modifiedCount: Int = 0,
    val totalSizeBytes: Long = 0,
    val namespaces: List<Pair<String, Int>> = emptyList()
)
