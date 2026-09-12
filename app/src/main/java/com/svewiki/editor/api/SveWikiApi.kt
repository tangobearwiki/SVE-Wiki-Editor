package com.svewiki.editor.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.svewiki.editor.data.BatchReplaceRule
import com.svewiki.editor.data.LocalPage
import com.svewiki.editor.data.NamespaceInfo
import com.svewiki.editor.data.UserInfo
import com.svewiki.editor.data.WikiNamespaces
import com.svewiki.editor.data.WikiPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class SveWikiApi(private val baseUrl: String = "https://sve.p1.wiki") {

    private val cookieJar = MemoryCookieJar()

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "SveWikiEditor/1.1 (Android)")
                .header("Accept", "application/json")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .build()
            chain.proceed(req)
        }
        .build()

    private fun apiUrl(): String = "$baseUrl/api.php"

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun getJson(url: String): JsonObject {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: throw IOException("Empty response")
            return JsonParser.parseString(body).asJsonObject
        }
    }

    private fun postForm(params: List<Pair<String, String>>): JsonObject {
        val form = FormBody.Builder().also { b ->
            params.forEach { (k, v) -> b.add(k, v) }
        }.build()
        val req = Request.Builder().url(apiUrl()).post(form).build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: throw IOException("Empty response")
            return JsonParser.parseString(body).asJsonObject
        }
    }

    private fun apiError(json: JsonObject): Exception? {
        val error = json.get("error") ?: return null
        val obj = error.asJsonObject
        val code = obj.get("code")?.asString ?: "unknown"
        val info = obj.get("info")?.asString ?: ""
        return Exception("API error: $code - $info")
    }

    private fun parseLocalPages(json: JsonObject): List<LocalPage> {
        val pages = json.getAsJsonObject("query")?.getAsJsonObject("pages") ?: return emptyList()
        val now = System.currentTimeMillis()
        val result = mutableListOf<LocalPage>()
        pages.entrySet().forEach { entry ->
            val page = entry.value.asJsonObject
            if (page.has("missing") || page.has("invalid")) return@forEach
            val pageId = page.get("pageid")?.asLong ?: return@forEach
            if (pageId <= 0) return@forEach
            val title = page.get("title")?.asString ?: return@forEach
            var content = ""
            var revId = 0L
            var touched = ""
            val revisions = page.getAsJsonArray("revisions")
            if (revisions != null && revisions.size() > 0) {
                val rev = revisions[0].asJsonObject
                content = rev.get("*")?.asString ?: ""
                revId = rev.get("revid")?.asLong ?: 0
                touched = rev.get("timestamp")?.asString ?: ""
            }
            result.add(
                LocalPage(
                    title = title,
                    namespace = WikiNamespaces.detectFromTitle(title),
                    revisionId = revId,
                    lastSyncTime = now,
                    pageId = pageId,
                    touched = touched,
                    content = content
                )
            )
        }
        return result
    }

    private fun parseWikiPage(json: JsonObject, fallbackTitle: String): WikiPage {
        val pages = json.getAsJsonObject("query")?.getAsJsonObject("pages")
            ?: throw IOException("页面不存在或无法读取")
        val entry = pages.entrySet().firstOrNull() ?: throw IOException("页面不存在或无法读取")
        val page = entry.value.asJsonObject
        if (page.has("missing") || page.has("invalid")) {
            throw IOException("页面不存在")
        }
        val title = page.get("title")?.asString ?: fallbackTitle
        var content = ""
        var revId = 0L
        var touched = ""
        val revisions = page.getAsJsonArray("revisions")
        if (revisions != null && revisions.size() > 0) {
            val rev = revisions[0].asJsonObject
            content = rev.get("*")?.asString ?: ""
            revId = rev.get("revid")?.asLong ?: 0
            touched = rev.get("timestamp")?.asString ?: ""
        }
        return WikiPage(title = title, content = content, revisionId = revId, touched = touched)
    }

    fun clearSession() {
        cookieJar.clear()
    }

    suspend fun login(username: String, password: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val tokenJson = getJson("${apiUrl()}?action=query&meta=tokens&type=login&format=json")
            val loginToken = tokenJson
                .getAsJsonObject("query")
                ?.getAsJsonObject("tokens")
                ?.get("logintoken")
                ?.asString ?: return@withContext Result.failure(Exception("Failed to get login token"))

            val loginJson = postForm(
                listOf(
                    "action" to "login",
                    "format" to "json",
                    "lgname" to username,
                    "lgpassword" to password,
                    "lgtoken" to loginToken
                )
            )
            val loginResult = loginJson.getAsJsonObject("login")?.get("result")?.asString
            when (loginResult) {
                "Success" -> Result.success(true)
                else -> Result.failure(Exception("Login failed: $loginResult"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getCsrfToken(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val json = getJson("${apiUrl()}?action=query&meta=tokens&format=json")
            val token = json
                .getAsJsonObject("query")
                ?.getAsJsonObject("tokens")
                ?.get("csrftoken")
                ?.asString ?: return@withContext Result.failure(Exception("No CSRF token"))
            Result.success(token)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getNamespaces(): Result<List<NamespaceInfo>> = withContext(Dispatchers.IO) {
        try {
            val json = getJson("${apiUrl()}?action=query&meta=siteinfo&siprop=namespaces&format=json")
            val namespacesObj = json.getAsJsonObject("query")?.getAsJsonObject("namespaces")
            val result = mutableListOf<NamespaceInfo>()
            namespacesObj?.entrySet()?.forEach { entry ->
                val ns = entry.value.asJsonObject
                val id = ns.get("id")?.asInt ?: 0
                val name = ns.get("*")?.asString ?: ""
                result.add(NamespaceInfo(id = id, name = name))
            }
            Result.success(result.filter { it.id >= 0 }.sortedBy { it.id })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUserInfo(username: String): Result<UserInfo> = withContext(Dispatchers.IO) {
        try {
            val json = getJson(
                "${apiUrl()}?action=query&list=users&ususers=${encode(username)}" +
                    "&usprop=groups%7Ceditcount%7Cregistration&format=json"
            )
            val users = json.getAsJsonObject("query")?.getAsJsonArray("users")
            val user = users?.firstOrNull()?.asJsonObject
            if (user == null || user.has("missing")) {
                Result.success(UserInfo(name = username))
            } else {
                val groups = user.getAsJsonArray("groups")?.map { it.asString }.orEmpty()
                Result.success(
                    UserInfo(
                        name = username,
                        editCount = user.get("editcount")?.asInt ?: 0,
                        registration = user.get("registration")?.asString ?: "",
                        groups = groups,
                        isLoggedIn = true
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getCurrentUserInfo(): Result<UserInfo> = withContext(Dispatchers.IO) {
        try {
            val json = getJson(
                "${apiUrl()}?action=query&meta=userinfo" +
                    "&uiprop=groups%7Ceditcount%7Cregistration%7Crights&format=json"
            )
            val userInfo = json.getAsJsonObject("query")?.getAsJsonObject("userinfo")
            if (userInfo == null) {
                Result.success(UserInfo())
            } else {
                val name = userInfo.get("name")?.asString ?: ""
                val anon = userInfo.has("anon")
                Result.success(
                    UserInfo(
                        name = name,
                        editCount = userInfo.get("editcount")?.asInt ?: 0,
                        registration = userInfo.get("registration")?.asString ?: "",
                        groups = userInfo.getAsJsonArray("groups")?.map { it.asString }.orEmpty(),
                        isLoggedIn = !anon && name.isNotBlank()
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listPagesInNamespace(namespace: Int, limit: Int = 500): Result<List<String>> =
        withContext(Dispatchers.IO) {
            try {
                val titles = mutableListOf<String>()
                var apcontinue: String? = null
                var totalFetched = 0
                val maxPages = 20_000
                while (totalFetched < maxPages) {
                    var url = "${apiUrl()}?action=query&list=allpages&apnamespace=$namespace" +
                        "&aplimit=$limit&format=json"
                    if (apcontinue != null) url += "&apcontinue=${encode(apcontinue)}"
                    val json = getJson(url)
                    val pages = json.getAsJsonObject("query")?.getAsJsonArray("allpages")
                    val pageCount = pages?.size() ?: 0
                    pages?.forEach { element ->
                        val title = element.asJsonObject.get("title")?.asString.orEmpty()
                        if (title.isNotEmpty()) titles.add(title)
                    }
                    totalFetched += pageCount
                    apcontinue = json.getAsJsonObject("continue")?.get("apcontinue")?.asString
                    if (apcontinue == null) break
                }
                Result.success(titles)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun fetchPagesContent(titles: List<String>): Result<List<LocalPage>> =
        withContext(Dispatchers.IO) {
            try {
                val result = mutableListOf<LocalPage>()
                val batchSize = 50
                for (i in titles.indices step batchSize) {
                    val batch = titles.subList(i, minOf(i + batchSize, titles.size))
                    val encodedTitles = batch.joinToString("%7C") { encode(it.replace(" ", "_")) }
                    val url = "${apiUrl()}?action=query&titles=$encodedTitles" +
                        "&prop=revisions%7Cinfo&rvprop=content%7Cids%7Ctimestamp&format=json"
                    result.addAll(parseLocalPages(getJson(url)))
                }
                Result.success(result)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun readPage(title: String): Result<WikiPage> = fetchWikiPage(title)

    suspend fun fetchPageForDiff(title: String): Result<WikiPage> = fetchWikiPage(title)

    private suspend fun fetchWikiPage(title: String): Result<WikiPage> = withContext(Dispatchers.IO) {
        try {
            val url = "${apiUrl()}?action=query&titles=${encode(title)}" +
                "&prop=revisions%7Cinfo&rvprop=content%7Cids%7Ctimestamp&format=json"
            Result.success(parseWikiPage(getJson(url), title))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun editPage(
        title: String,
        text: String,
        summary: String = "自动编辑",
        minor: Boolean = false
    ): Result<Boolean> = editPageWithRevisionId(title, text, summary, minor).map { true }

    suspend fun searchPages(query: String, limit: Int = 20): Result<List<String>> =
        withContext(Dispatchers.IO) {
            try {
                val json = getJson(
                    "${apiUrl()}?action=query&list=search&srsearch=${encode(query)}" +
                        "&srlimit=$limit&format=json"
                )
                val searchResults = json.getAsJsonObject("query")?.getAsJsonArray("search")
                val titles = mutableListOf<String>()
                searchResults?.forEach { element ->
                    val title = element.asJsonObject.get("title")?.asString.orEmpty()
                    if (title.isNotEmpty()) titles.add(title)
                }
                Result.success(titles)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun queryCategory(category: String, limit: Int = 50): Result<List<String>> =
        withContext(Dispatchers.IO) {
            try {
                val catTitle = if (
                    category.startsWith("Category:", ignoreCase = true) ||
                    category.startsWith("分类:")
                ) category else "Category:$category"
                val json = getJson(
                    "${apiUrl()}?action=query&list=categorymembers&cmtitle=${encode(catTitle)}" +
                        "&cmlimit=$limit&format=json"
                )
                val members = json.getAsJsonObject("query")?.getAsJsonArray("categorymembers")
                val titles = mutableListOf<String>()
                members?.forEach { element ->
                    val title = element.asJsonObject.get("title")?.asString.orEmpty()
                    if (title.isNotEmpty()) titles.add(title)
                }
                Result.success(titles)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun getRecentChanges(
        sinceTimestamp: String,
        namespaces: List<Int> = WikiNamespaces.SYNC_IDS
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val titles = linkedSetOf<String>()
            var rccontinue: String? = null
            var loops = 0
            while (loops++ < 50) {
                var url = "${apiUrl()}?action=query&list=recentchanges" +
                    "&rcend=${encode(sinceTimestamp)}" +
                    "&rcdir=older&rctype=edit%7Cnew&rctoponly=1" +
                    "&rcprop=title%7Cids%7Ctimestamp%7Ccomment&rclimit=200&format=json"
                if (rccontinue != null) url += "&rccontinue=${encode(rccontinue)}"
                val json = getJson(url)
                val changes = json.getAsJsonObject("query")?.getAsJsonArray("recentchanges")
                changes?.forEach { element ->
                    val obj = element.asJsonObject
                    val ns = obj.get("ns")?.asInt ?: 0
                    if (ns in namespaces) {
                        val title = obj.get("title")?.asString.orEmpty()
                        if (title.isNotEmpty()) titles.add(title)
                    }
                }
                rccontinue = json.getAsJsonObject("continue")?.get("rccontinue")?.asString
                if (rccontinue == null) break
            }
            Result.success(titles.toList())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getRecentLogs(
        sinceTimestamp: String,
        namespaces: List<Int> = WikiNamespaces.SYNC_IDS
    ): Result<List<Pair<String?, String?>>> = withContext(Dispatchers.IO) {
        try {
            val result = mutableListOf<Pair<String?, String?>>()
            val seenPages = mutableSetOf<String>()
            for (logType in listOf("delete", "move", "contentmodel")) {
                var lecontinue: String? = null
                var loops = 0
                while (loops++ < 50) {
                    var url = "${apiUrl()}?action=query&list=logevents" +
                        "&letype=$logType" +
                        "&leend=${encode(sinceTimestamp)}" +
                        "&ledir=older&lelimit=200&format=json"
                    if (lecontinue != null) url += "&lecontinue=${encode(lecontinue)}"
                    val json = getJson(url)
                    val events = json.getAsJsonObject("query")?.getAsJsonArray("logevents")
                    events?.forEach { element ->
                        val obj = element.asJsonObject
                        val ns = obj.get("ns")?.asInt ?: 0
                        val action = obj.get("action")?.asString ?: ""
                        val title = obj.get("title")?.asString
                        val logPage = obj.get("logpage")?.asString ?: title ?: return@forEach
                        if (logPage in seenPages) return@forEach
                        seenPages.add(logPage)
                        when (logType) {
                            "delete" -> {
                                if (ns in namespaces) {
                                    if (action == "delete") result.add(title to null)
                                    else if (action == "restore") result.add(null to title)
                                }
                            }
                            "move" -> {
                                val params = obj.getAsJsonObject("params")
                                val targetNs = params?.get("target_ns")?.asInt ?: -1
                                val targetTitle = params?.get("target_title")?.asString
                                val from = if (ns in namespaces) title else null
                                val to = if (targetNs in namespaces) targetTitle else null
                                if (from != null || to != null) result.add(from to to)
                            }
                            "contentmodel" -> {
                                if (ns in namespaces && title != null) result.add(title to title)
                            }
                        }
                    }
                    lecontinue = json.getAsJsonObject("continue")?.get("lecontinue")?.asString
                    if (lecontinue == null) break
                }
            }
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getRemoteRevisionId(title: String): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val json = getJson(
                "${apiUrl()}?action=query&titles=${encode(title)}&prop=revisions&rvprop=ids&format=json"
            )
            val pages = json.getAsJsonObject("query")?.getAsJsonObject("pages")
            val page = pages?.entrySet()?.firstOrNull()?.value?.asJsonObject
                ?: return@withContext Result.failure(Exception("No revision found"))
            if (page.has("missing") || page.has("invalid")) {
                return@withContext Result.failure(Exception("No revision found"))
            }
            val revId = page.getAsJsonArray("revisions")
                ?.get(0)?.asJsonObject?.get("revid")?.asLong ?: 0L
            if (revId <= 0) Result.failure(Exception("No revision found"))
            else Result.success(revId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun editPageWithRevisionId(
        title: String,
        text: String,
        summary: String = "自动编辑",
        minor: Boolean = false
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val csrfToken = getCsrfToken().getOrElse { return@withContext Result.failure(it) }
            val params = mutableListOf(
                "action" to "edit",
                "format" to "json",
                "title" to title,
                "text" to text,
                "summary" to summary,
                "token" to csrfToken
            )
            if (minor) params += "minor" to "1" else params += "notminor" to "1"
            val json = postForm(params)
            apiError(json)?.let { return@withContext Result.failure(it) }
            val edit = json.getAsJsonObject("edit")
            val result = edit?.get("result")?.asString
            if (result == "Success") {
                Result.success(edit.get("newrevid")?.asLong ?: 0L)
            } else {
                Result.failure(Exception("Edit failed: $result"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun batchReplace(
        rules: List<BatchReplaceRule>,
        pages: List<String>,
        summary: String = "批量替换",
        onProgress: (suspend (String, Boolean) -> Unit)? = null
    ): Result<BatchResult> = withContext(Dispatchers.IO) {
        val result = BatchResult()
        for (pageTitle in pages) {
            try {
                val pageResult = readPage(pageTitle)
                if (pageResult.isFailure) {
                    result.failed.add(pageTitle to (pageResult.exceptionOrNull()?.message ?: "读取失败"))
                    onProgress?.invoke(pageTitle, false)
                    continue
                }
                val page = pageResult.getOrThrow()
                var newContent = page.content
                for (rule in rules) {
                    newContent = if (rule.regex) {
                        val regex = if (rule.ignoreCase) Regex(rule.find, RegexOption.IGNORE_CASE)
                        else Regex(rule.find)
                        newContent.replace(regex, rule.replace)
                    } else if (rule.ignoreCase) {
                        newContent.replace(Regex(Regex.escape(rule.find), RegexOption.IGNORE_CASE), rule.replace)
                    } else {
                        newContent.replace(rule.find, rule.replace)
                    }
                }
                if (newContent != page.content) {
                    val editResult = editPage(pageTitle, newContent, summary)
                    if (editResult.isSuccess) {
                        result.success.add(pageTitle)
                        onProgress?.invoke(pageTitle, true)
                    } else {
                        result.failed.add(pageTitle to (editResult.exceptionOrNull()?.message ?: "编辑失败"))
                        onProgress?.invoke(pageTitle, false)
                    }
                } else {
                    result.skipped.add(pageTitle)
                    onProgress?.invoke(pageTitle, true)
                }
            } catch (e: Exception) {
                result.failed.add(pageTitle to (e.message ?: "未知错误"))
                onProgress?.invoke(pageTitle, false)
            }
        }
        Result.success(result)
    }

    suspend fun movePage(
        from: String,
        to: String,
        reason: String = "移动页面",
        moveTalk: Boolean = true,
        moveSubpages: Boolean = false
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val csrfToken = getCsrfToken().getOrElse { return@withContext Result.failure(it) }
            val params = mutableListOf(
                "action" to "move",
                "format" to "json",
                "from" to from,
                "to" to to,
                "reason" to reason,
                "token" to csrfToken
            )
            if (moveTalk) params += "movetalk" to "1"
            if (moveSubpages) params += "movesubpages" to "1"
            val json = postForm(params)
            apiError(json)?.let { return@withContext Result.failure(it) }
            if (json.getAsJsonObject("move") != null) Result.success(true)
            else Result.failure(Exception("Move failed: unexpected response"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deletePage(title: String, reason: String = "批量删除"): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val csrfToken = getCsrfToken().getOrElse { return@withContext Result.failure(it) }
                val json = postForm(
                    listOf(
                        "action" to "delete",
                        "format" to "json",
                        "title" to title,
                        "reason" to reason,
                        "token" to csrfToken
                    )
                )
                apiError(json)?.let { return@withContext Result.failure(it) }
                if (json.getAsJsonObject("delete") != null) Result.success(true)
                else Result.failure(Exception("Delete failed: unexpected response"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    data class BatchResult(
        val success: MutableList<String> = mutableListOf(),
        val failed: MutableList<Pair<String, String>> = mutableListOf(),
        val skipped: MutableList<String> = mutableListOf()
    )

    private class MemoryCookieJar : CookieJar {
        private val store = ConcurrentHashMap<String, Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { store[it.name] = it }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            store.entries.removeAll { it.value.expiresAt < now }
            return store.values.filter { it.matches(url) }
        }

        fun clear() = store.clear()
    }
}
