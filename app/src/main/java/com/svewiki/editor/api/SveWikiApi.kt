package com.svewiki.editor.api

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.svewiki.editor.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder

class SveWikiApi(private val baseUrl: String = "https://sve.p1.wiki") {
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .cookieJar(CookieJar.NO_COOKIES)
        .build()
    private val gson = Gson()
    private val formMediaType = "application/x-www-form-urlencoded".toMediaType()

    // 用 MANUAL cookie 管理
    private val cookieStore = mutableMapOf<String, String>()

    private fun getApiUrl(): String = "$baseUrl/api.php"

    private fun buildForm(vararg params: Pair<String, String>): String {
        return params.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
    }

    private fun getHeaders(): Headers = Headers.Builder()
        .add("User-Agent", "SveWikiEditor/1.0 (Android)")
        .add("Accept", "application/json, text/plain, */*")
        .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        .apply {
            cookieStore.forEach { (name, value) ->
                if (name == "cookie") add("Cookie", value)
            }
        }
        .build()

    private fun updateCookies(response: Response) {
        response.headers("Set-Cookie").forEach { cookieStr ->
            // 解析 Set-Cookie 头，取 name=value
            val parts = cookieStr.split(";")
            if (parts.isNotEmpty()) {
                val cookiePair = parts[0].trim()
                if (cookiePair.contains("=")) {
                    val existing = cookieStore["cookie"] ?: ""
                    val name = cookiePair.substringBefore("=")
                    // 移除旧的同名 cookie
                    val cleaned = existing.split(";")
                        .filter { !it.trimStart().startsWith("$name=") }
                        .joinToString(";")
                    val newCookies = if (cleaned.isEmpty()) cookiePair else "$cleaned;$cookiePair"
                    cookieStore["cookie"] = newCookies
                }
            }
        }
    }

    // ============ 登录流程 ============

    suspend fun login(username: String, password: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // Step 1: Get login token
            val tokenUrl = "${getApiUrl()}?action=query&meta=tokens&type=login&format=json"
            val tokenReq = Request.Builder().url(tokenUrl).headers(getHeaders()).get().build()
            val tokenResp = client.newCall(tokenReq).execute()
            updateCookies(tokenResp)

            val tokenBody = tokenResp.body?.string() ?: return@withContext Result.failure(Exception("Empty response"))
            val tokenJson = JsonParser.parseString(tokenBody).asJsonObject
            val loginToken = tokenJson
                .getAsJsonObject("query")
                ?.getAsJsonObject("tokens")
                ?.get("logintoken")
                ?.asString ?: return@withContext Result.failure(Exception("Failed to get login token"))

            // Step 2: Login
            val loginForm = buildForm(
                "action" to "login",
                "format" to "json",
                "lgname" to username,
                "lgpassword" to password,
                "lgtoken" to loginToken
            )
            val loginReq = Request.Builder()
                .url(getApiUrl())
                .headers(getHeaders())
                .post(loginForm.toRequestBody(formMediaType))
                .build()
            val loginResp = client.newCall(loginReq).execute()
            updateCookies(loginResp)

            val loginBody = loginResp.body?.string() ?: return@withContext Result.failure(Exception("Empty login response"))
            val loginJson = JsonParser.parseString(loginBody).asJsonObject
            val loginResult = loginJson.getAsJsonObject("login")?.get("result")?.asString

            when (loginResult) {
                "Success" -> Result.success(true)
                else -> Result.failure(Exception("Login failed: $loginResult"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============ 获取 CSRF Token ============

    suspend fun getCsrfToken(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = "${getApiUrl()}?action=query&meta=tokens&format=json"
            val req = Request.Builder().url(url).headers(getHeaders()).get().build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext Result.failure(Exception("Empty"))
            val json = JsonParser.parseString(body).asJsonObject
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

    // ============ 获取命名空间列表 ============

    suspend fun getNamespaces(): Result<List<NamespaceInfo>> = withContext(Dispatchers.IO) {
        try {
            val url = "${getApiUrl()}?action=query&meta=siteinfo&siprop=namespaces&format=json"
            val req = Request.Builder().url(url).headers(getHeaders()).get().build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext Result.failure(Exception("Empty"))
            val json = JsonParser.parseString(body).asJsonObject
            val namespacesObj = json.getAsJsonObject("query")?.getAsJsonObject("namespaces")

            val result = mutableListOf<NamespaceInfo>()
            namespacesObj?.entrySet()?.forEach { entry ->
                val ns = entry.value.asJsonObject
                val id = ns.get("id")?.asInt ?: 0
                val name = ns.get("*")?.asString ?: ""
                result.add(NamespaceInfo(id = id, name = name))
            }
            // 过滤掉 -1 特殊命名空间，按 id 排序
            Result.success(result.filter { it.id >= 0 }.sortedBy { it.id })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============ 获取用户信息（身份组 + 编辑数） ============

    suspend fun getUserInfo(username: String): Result<UserInfo> = withContext(Dispatchers.IO) {
        try {
            // 注意：| 必须编码为 %7C，否则 OkHttp 解析 URL 会失败
            val url = "${getApiUrl()}?action=query&list=users&ususers=${
                URLEncoder.encode(username, "UTF-8")
            }&usprop=groups%7Ceditcount%7Cregistration&format=json"
            val req = Request.Builder().url(url).headers(getHeaders()).get().build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext Result.failure(Exception("Empty"))
            val json = JsonParser.parseString(body).asJsonObject
            val users = json.getAsJsonObject("query")?.getAsJsonArray("users")

            val user = users?.firstOrNull()?.asJsonObject
            if (user == null) {
                Result.success(UserInfo(name = username))
            } else {
                val groups = user.getAsJsonArray("groups")?.map { it.asString }?.toList() ?: emptyList()
                val editCount = user.get("editcount")?.asInt ?: 0
                val registration = user.get("registration")?.asString ?: ""
                Result.success(UserInfo(
                    name = username,
                    editCount = editCount,
                    registration = registration,
                    groups = groups,
                    isLoggedIn = true
                ))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============ 获取当前登录用户信息 ============

    suspend fun getCurrentUserInfo(): Result<UserInfo> = withContext(Dispatchers.IO) {
        try {
            val url = "${getApiUrl()}?action=query&meta=userinfo&uiprop=groups%7Ceditcount%7Cregistration%7Crights&format=json"
            val req = Request.Builder().url(url).headers(getHeaders()).get().build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext Result.failure(Exception("Empty"))
            val json = JsonParser.parseString(body).asJsonObject
            val userInfo = json.getAsJsonObject("query")?.getAsJsonObject("userinfo")

            if (userInfo == null) {
                Result.success(UserInfo())
            } else {
                val name = userInfo.get("name")?.asString ?: ""
                val groups = userInfo.getAsJsonArray("groups")?.map { it.asString }?.toList() ?: emptyList()
                val editCount = userInfo.get("editcount")?.asInt ?: 0
                val registration = userInfo.get("registration")?.asString ?: ""
                Result.success(UserInfo(
                    name = name,
                    editCount = editCount,
                    registration = registration,
                    groups = groups,
                    isLoggedIn = true
                ))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============ 拉取某命名空间的所有页面 ============

    suspend fun listPagesInNamespace(namespace: Int, limit: Int = 500): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val titles = mutableListOf<String>()
            var apcontinue: String? = null
            var maxPages = 10000 // 死循环保护：最多拉取 10000 个页面
            var totalFetched = 0

            while (totalFetched < maxPages) {
                var url = "${getApiUrl()}?action=query&list=allpages&apnamespace=$namespace&aplimit=$limit&format=json"
                if (apcontinue != null) {
                    url += "&apcontinue=${URLEncoder.encode(apcontinue, "UTF-8")}"
                }
                val req = Request.Builder().url(url).headers(getHeaders()).get().build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string() ?: break
                val json = JsonParser.parseString(body).asJsonObject

                val pages = json.getAsJsonObject("query")?.getAsJsonArray("allpages")
                val pageCount = pages?.size() ?: 0
                pages?.forEach { element ->
                    val title = element.asJsonObject.get("title")?.asString ?: ""
                    if (title.isNotEmpty()) titles.add(title)
                }
                totalFetched += pageCount

                // 检查是否有下一页
                val continueObj = json.getAsJsonObject("continue")
                apcontinue = continueObj?.get("apcontinue")?.asString
                if (apcontinue == null) break
            }

            Result.success(titles)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============ 批量读取页面内容（revisions） ============

    suspend fun fetchPagesContent(titles: List<String>): Result<List<LocalPage>> = withContext(Dispatchers.IO) {
        try {
            val result = mutableListOf<LocalPage>()
            // MediaWiki 一次最多查 50 个标题，分批处理
            val batchSize = 50
            for (i in titles.indices step batchSize) {
                val batch = titles.subList(i, minOf(i + batchSize, titles.size))
                // 每个标题单独编码，用 %7C 作为分隔符（MediaWiki API 的 titles 参数需要）
                val encodedTitles = batch.joinToString("%7C") {
                    URLEncoder.encode(it.replace(" ", "_"), "UTF-8")
                }
                val url = "${getApiUrl()}?action=query&titles=$encodedTitles&prop=revisions%7Cinfo&rvprop=content%7Cids%7Ctimestamp&format=json"
                val req = Request.Builder().url(url).headers(getHeaders()).get().build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string() ?: continue
                val json = JsonParser.parseString(body).asJsonObject

                val pages = json.getAsJsonObject("query")?.getAsJsonObject("pages")
                pages?.entrySet()?.forEach { entry ->
                    // 跳过 -1（不存在的页面）
                    if (entry.key == "-1") return@forEach
                    val page = entry.value.asJsonObject
                    val title = page.get("title")?.asString ?: return@forEach
                    val pageId = page.get("pageid")?.asLong ?: 0
                    val missing = page.get("missing")?.asBoolean ?: false
                    if (missing) return@forEach

                    val revisions = page.getAsJsonArray("revisions")
                    var content = ""
                    var revId = 0L
                    var touched = ""
                    if (revisions != null && revisions.size() > 0) {
                        val rev = revisions[0].asJsonObject
                        content = rev.get("*")?.asString ?: ""
                        revId = rev.get("revid")?.asLong ?: 0
                        touched = rev.get("timestamp")?.asString ?: ""
                    }

                    // 根据标题解析命名空间
                    val ns = when {
                        title.startsWith("讨论:") -> 1
                        title.startsWith("用户讨论:") -> 3
                        title.startsWith("用户:") -> 2
                        title.startsWith("文件:") -> 6
                        title.startsWith("分类:") -> 14
                        title.startsWith("模板:") -> 10
                        title.startsWith("模板讨论:") -> 11
                        title.startsWith("帮助:") -> 12
                        title.startsWith("帮助讨论:") -> 13
                        title.startsWith("站务:") -> 4
                        title.startsWith("站务讨论:") -> 5
                        title.startsWith("MediaWiki:") -> 8
                        title.startsWith("MediaWiki讨论:") -> 9
                        title.startsWith("模块:") -> 828
                        title.startsWith("模块讨论:") -> 829
                        else -> 0
                    }

                    result.add(LocalPage(
                        title = title,
                        namespace = ns,
                        content = content,
                        revisionId = revId,
                        lastSyncTime = System.currentTimeMillis(),
                        pageId = pageId,
                        touched = touched
                    ))
                }
            }
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============ 读取页面 ============

    suspend fun readPage(title: String): Result<WikiPage> = withContext(Dispatchers.IO) {
        try {
            val url = "${getApiUrl()}?action=parse&page=${
                URLEncoder.encode(title, "UTF-8")
            }&prop=text&format=json"
            val req = Request.Builder().url(url).headers(getHeaders()).get().build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext Result.failure(Exception("Empty"))
            val json = JsonParser.parseString(body).asJsonObject
            val parse = json.getAsJsonObject("parse")
            val pageTitle = parse?.get("title")?.asString ?: title
            val text = parse?.getAsJsonObject("text")?.get("*")?.asString ?: ""

            // 获取原始 wikitext（需要另一个请求）
            val rawUrl = "${getApiUrl()}?action=query&titles=${
                URLEncoder.encode(title, "UTF-8")
            }&prop=revisions&rvprop=content&format=json"
            val rawReq = Request.Builder().url(rawUrl).headers(getHeaders()).get().build()
            val rawResp = client.newCall(rawReq).execute()
            val rawBody = rawResp.body?.string() ?: ""
            val rawJson = JsonParser.parseString(rawBody).asJsonObject

            val pages = rawJson.getAsJsonObject("query")?.getAsJsonObject("pages")
            var wikitext = ""
            pages?.entrySet()?.firstOrNull()?.let { entry ->
                val page = entry.value.asJsonObject
                val revisions = page.getAsJsonArray("revisions")
                if (revisions != null && revisions.size() > 0) {
                    wikitext = revisions[0].asJsonObject.get("*")?.asString ?: ""
                }
            }

            Result.success(WikiPage(title = pageTitle, content = wikitext))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============ 获取页面服务器最新内容（用于 Diff 和版本检测） ============

    suspend fun fetchPageForDiff(title: String): Result<WikiPage> = withContext(Dispatchers.IO) {
        try {
            val url = "${getApiUrl()}?action=query&titles=${
                URLEncoder.encode(title, "UTF-8")
            }&prop=revisions%7Cinfo&rvprop=content%7Cids%7Ctimestamp&format=json"
            val req = Request.Builder().url(url).headers(getHeaders()).get().build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext Result.failure(Exception("Empty"))
            val json = JsonParser.parseString(body).asJsonObject
            val pages = json.getAsJsonObject("query")?.getAsJsonObject("pages")
            var content = ""
            var revId = 0L
            var touched = ""
            pages?.entrySet()?.firstOrNull()?.let { entry ->
                val page = entry.value.asJsonObject
                val revisions = page.getAsJsonArray("revisions")
                if (revisions != null && revisions.size() > 0) {
                    val rev = revisions[0].asJsonObject
                    content = rev.get("*")?.asString ?: ""
                    revId = rev.get("revid")?.asLong ?: 0
                    touched = rev.get("timestamp")?.asString ?: ""
                }
            }
            Result.success(WikiPage(title = title, content = content, revisionId = revId, touched = touched))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============ 编辑页面 ============

    suspend fun editPage(
        title: String,
        text: String,
        summary: String = "自动编辑",
        minor: Boolean = false
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // 获取 CSRF token
            val csrfResult = getCsrfToken()
            if (csrfResult.isFailure) return@withContext Result.failure(csrfResult.exceptionOrNull()!!)
            val csrfToken = csrfResult.getOrThrow()

            val form = buildForm(
                "action" to "edit",
                "format" to "json",
                "title" to title,
                "text" to text,
                "summary" to summary,
                "token" to csrfToken,
                if (minor) "minor" to "1" else "notminor" to "1"
            )

            val req = Request.Builder()
                .url(getApiUrl())
                .headers(getHeaders())
                .post(form.toRequestBody(formMediaType))
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext Result.failure(Exception("Empty"))
            val json = JsonParser.parseString(body).asJsonObject
            val error = json.get("error")
            if (error != null) {
                val code = error.asJsonObject.get("code")?.asString ?: "unknown"
                val info = error.asJsonObject.get("info")?.asString ?: ""
                return@withContext Result.failure(Exception("API error: $code - $info"))
            }
            val edit = json.getAsJsonObject("edit")
            val result = edit?.get("result")?.asString
            if (result == "Success") {
                Result.success(true)
            } else {
                Result.failure(Exception("Edit failed: $result"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============ 搜索页面 ============

    suspend fun searchPages(query: String, limit: Int = 20): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val url = "${getApiUrl()}?action=query&list=search&srsearch=${
                URLEncoder.encode(query, "UTF-8")
            }&srlimit=$limit&format=json"
            val req = Request.Builder().url(url).headers(getHeaders()).get().build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext Result.failure(Exception("Empty"))
            val json = JsonParser.parseString(body).asJsonObject
            val searchResults = json
                .getAsJsonObject("query")
                ?.getAsJsonObject("search")
                ?.getAsJsonArray("results") ?: json
                .getAsJsonObject("query")
                ?.getAsJsonArray("search")

            val titles = mutableListOf<String>()
            searchResults?.forEach { element ->
                val obj = element.asJsonObject
                titles.add(obj.get("title")?.asString ?: "")
            }
            Result.success(titles)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============ 分类查询 ============

    suspend fun queryCategory(category: String, limit: Int = 50): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            // 兼容：若分类名已带 "Category:" 前缀则不再重复添加
            val catTitle = if (category.startsWith("Category:") || category.startsWith("分类:")) category
                else "Category:$category"
            val url = "${getApiUrl()}?action=query&list=categorymembers&cmtitle=${
                URLEncoder.encode(catTitle, "UTF-8")
            }&cmlimit=$limit&format=json"
            val req = Request.Builder().url(url).headers(getHeaders()).get().build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext Result.failure(Exception("Empty"))
            val json = JsonParser.parseString(body).asJsonObject
            val members = json
                .getAsJsonObject("query")
                ?.getAsJsonArray("categorymembers")

            val titles = mutableListOf<String>()
            members?.forEach { element ->
                titles.add(element.asJsonObject.get("title")?.asString ?: "")
            }
            Result.success(titles)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============ 增量同步：获取最近变更（对标 WiGit get_recent_changes） ============

    /**
     * 获取自指定时间以来的最近变更页面列表
     * @param sinceTimestamp ISO8601 格式，如 "2026-08-01T00:00:00Z"
     * @param namespaces 只获取这些命名空间的变更
     * @return 变更页面标题列表
     */
    suspend fun getRecentChanges(
        sinceTimestamp: String,
        namespaces: List<Int> = listOf(0, 2, 4, 6, 8, 10, 12, 14, 828)
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val titles = mutableSetOf<String>()
            var rccontinue: String? = null

            while (true) {
                var url = "${getApiUrl()}?action=query&list=recentchanges" +
                    "&rcend=${URLEncoder.encode(sinceTimestamp, "UTF-8")}" +
                    "&rcdir=older&rctype=edit%7Cnew&rctoponly=1&rcprop=title%7Cids%7Ctimestamp%7Ccomment&rclimit=200&format=json"
                if (rccontinue != null) {
                    url += "&rccontinue=${URLEncoder.encode(rccontinue, "UTF-8")}"
                }
                val req = Request.Builder().url(url).headers(getHeaders()).get().build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string() ?: break
                val json = JsonParser.parseString(body).asJsonObject

                val changes = json.getAsJsonObject("query")?.getAsJsonArray("recentchanges")
                changes?.forEach { element ->
                    val obj = element.asJsonObject
                    val ns = obj.get("ns")?.asInt ?: 0
                    if (ns in namespaces) {
                        val title = obj.get("title")?.asString ?: ""
                        if (title.isNotEmpty()) titles.add(title)
                    }
                }

                val continueObj = json.getAsJsonObject("continue")
                rccontinue = continueObj?.get("rccontinue")?.asString
                if (rccontinue == null) break
            }

            Result.success(titles.toList())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取最近的日志事件（删除/移动/恢复），对标 WiGit get_recent_logs
     * @param sinceTimestamp ISO8601 格式
     * @param namespaces 只获取这些命名空间的日志
     * @return 日志事件列表，每个元素是 (fromTitle, toTitle)，fromTitle 为 null 表示新增，toTitle 为 null 表示删除
     */
    suspend fun getRecentLogs(
        sinceTimestamp: String,
        namespaces: List<Int> = listOf(0, 2, 4, 6, 8, 10, 12, 14, 828)
    ): Result<List<Pair<String?, String?>>> = withContext(Dispatchers.IO) {
        try {
            val result = mutableListOf<Pair<String?, String?>>()
            val seenPages = mutableSetOf<String>()

            // 获取三类日志：删除、移动、内容模型
            for (logType in listOf("delete", "move", "contentmodel")) {
                var lecontinue: String? = null
                while (true) {
                    var url = "${getApiUrl()}?action=query&list=logevents" +
                        "&letype=$logType" +
                        "&leend=${URLEncoder.encode(sinceTimestamp, "UTF-8")}" +
                        "&ledir=older&lelimit=200&format=json"
                    if (lecontinue != null) {
                        url += "&lecontinue=${URLEncoder.encode(lecontinue, "UTF-8")}"
                    }
                    val req = Request.Builder().url(url).headers(getHeaders()).get().build()
                    val resp = client.newCall(req).execute()
                    val body = resp.body?.string() ?: break
                    val json = JsonParser.parseString(body).asJsonObject

                    val events = json.getAsJsonObject("query")?.getAsJsonArray("logevents")
                    events?.forEach { element ->
                        val obj = element.asJsonObject
                        val ns = obj.get("ns")?.asInt ?: 0
                        val action = obj.get("action")?.asString ?: ""
                        val title = obj.get("title")?.asString
                        val logPage = obj.get("logpage")?.asString ?: title ?: return@forEach

                        if (logPage !in seenPages) {
                            seenPages.add(logPage)
                            when (logType) {
                                "delete" -> {
                                    if (action == "delete" && ns in namespaces) {
                                        result.add(title to null)  // 删除
                                    } else if (action == "restore" && ns in namespaces) {
                                        result.add(null to title)  // 恢复
                                    }
                                }
                                "move" -> {
                                    val targetNs = obj.getAsJsonObject("params")?.get("target_ns")?.asInt ?: -1
                                    val targetTitle = obj.getAsJsonObject("params")?.get("target_title")?.asString
                                    val from = if (ns in namespaces) title else null
                                    val to = if (targetNs in namespaces) targetTitle else null
                                    if (from != null || to != null) {
                                        result.add(from to to)
                                    }
                                }
                                "contentmodel" -> {
                                    if (ns in namespaces && title != null) {
                                        result.add(title to title)  // 内容模型变更
                                    }
                                }
                            }
                        }
                    }

                    val continueObj = json.getAsJsonObject("continue")
                    lecontinue = continueObj?.get("lecontinue")?.asString
                    if (lecontinue == null) break
                }
            }

            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取页面的远程修订号（用于推送前冲突检查，对标 WiGit push_changes_to_origin 的冲突检测）
     * @param title 页面标题
     * @return 远程修订号，失败返回 null
     */
    suspend fun getRemoteRevisionId(title: String): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val url = "${getApiUrl()}?action=query&titles=${
                URLEncoder.encode(title, "UTF-8")
            }&prop=revisions&rvprop=ids&format=json"
            val req = Request.Builder().url(url).headers(getHeaders()).get().build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext Result.failure(Exception("Empty"))
            val json = JsonParser.parseString(body).asJsonObject
            val pages = json.getAsJsonObject("query")?.getAsJsonObject("pages")
            pages?.entrySet()?.firstOrNull()?.let { entry ->
                val page = entry.value.asJsonObject
                val revisions = page.getAsJsonArray("revisions")
                if (revisions != null && revisions.size() > 0) {
                    val revId = revisions[0].asJsonObject.get("revid")?.asLong ?: 0L
                    return@withContext Result.success(revId)
                }
            }
            Result.failure(Exception("No revision found"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 编辑页面并返回新的修订号（对标 WiGit page.edit + revision 追踪）
     * @return Result<Long> 新修订号，失败返回异常
     */
    suspend fun editPageWithRevisionId(
        title: String,
        text: String,
        summary: String = "自动编辑",
        minor: Boolean = false
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val csrfResult = getCsrfToken()
            if (csrfResult.isFailure) return@withContext Result.failure(csrfResult.exceptionOrNull()!!)
            val csrfToken = csrfResult.getOrThrow()

            val form = buildForm(
                "action" to "edit",
                "format" to "json",
                "title" to title,
                "text" to text,
                "summary" to summary,
                "token" to csrfToken,
                if (minor) "minor" to "1" else "notminor" to "1"
            )

            val req = Request.Builder()
                .url(getApiUrl())
                .headers(getHeaders())
                .post(form.toRequestBody(formMediaType))
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext Result.failure(Exception("Empty"))
            val json = JsonParser.parseString(body).asJsonObject
            val error = json.get("error")
            if (error != null) {
                val code = error.asJsonObject.get("code")?.asString ?: "unknown"
                val info = error.asJsonObject.get("info")?.asString ?: ""
                return@withContext Result.failure(Exception("API error: $code - $info"))
            }
            val edit = json.getAsJsonObject("edit")
            val result = edit?.get("result")?.asString
            if (result == "Success") {
                val newRevId = edit.get("newrevid")?.asLong ?: 0L
                Result.success(newRevId)
            } else {
                Result.failure(Exception("Edit failed: $result"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============ 批量替换页面内容 ============

    suspend fun batchReplace(
        rules: List<BatchReplaceRule>,
        pages: List<String>,
        summary: String = "批量替换",
        onProgress: (suspend (String, Boolean) -> Unit)? = null
    ): Result<BatchResult> = withContext(Dispatchers.IO) {
        val result = BatchResult()
        for (pageTitle in pages) {
            try {
                // 读页面
                val pageResult = readPage(pageTitle)
                if (pageResult.isFailure) {
                    result.failed.add(
                        pageTitle to (pageResult.exceptionOrNull()?.message ?: "读取失败")
                    )
                    onProgress?.invoke(pageTitle, false)
                    continue
                }
                val page = pageResult.getOrThrow()
                var newContent = page.content

                // 应用替换规则
                for (rule in rules) {
                    newContent = if (rule.regex) {
                        val regex = if (rule.ignoreCase)
                            Regex(rule.find, RegexOption.IGNORE_CASE)
                        else
                            Regex(rule.find)
                        newContent.replace(regex, rule.replace)
                    } else {
                        if (rule.ignoreCase)
                            newContent.replace(Regex(Regex.escape(rule.find), RegexOption.IGNORE_CASE), rule.replace)
                        else
                            newContent.replace(rule.find, rule.replace)
                    }
                }

                // 如果内容有变化，提交
                if (newContent != page.content) {
                    val editResult = editPage(pageTitle, newContent, summary)
                    if (editResult.isSuccess) {
                        result.success.add(pageTitle)
                        onProgress?.invoke(pageTitle, true)
                    } else {
                        result.failed.add(
                        pageTitle to (editResult.exceptionOrNull()?.message ?: "编辑失败")
                    )
                    onProgress?.invoke(pageTitle, false)
                    }
                } else {
                    result.skipped.add(pageTitle)
                    onProgress?.invoke(pageTitle, true)
                }
            } catch (e: Exception) {
                result.failed.add(
                        pageTitle to (e.message ?: "未知错误")
                    )
                    onProgress?.invoke(pageTitle, false)
            }
        }
        Result.success(result)
    }

    // ============ 移动页面 ============

    suspend fun movePage(
        from: String,
        to: String,
        reason: String = "移动页面",
        moveTalk: Boolean = true,
        moveSubpages: Boolean = false
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val csrfResult = getCsrfToken()
            if (csrfResult.isFailure) return@withContext Result.failure(csrfResult.exceptionOrNull()!!)
            val csrfToken = csrfResult.getOrThrow()

            val form = buildForm(
                "action" to "move",
                "format" to "json",
                "from" to from,
                "to" to to,
                "reason" to reason,
                "token" to csrfToken,
                if (moveTalk) "movetalk" to "1" else "notalk" to "1",
                if (moveSubpages) "movesubpages" to "1" else "nmsubpages" to "1"
            )

            val req = Request.Builder()
                .url(getApiUrl())
                .headers(getHeaders())
                .post(form.toRequestBody(formMediaType))
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext Result.failure(Exception("Empty response"))
            val json = JsonParser.parseString(body).asJsonObject
            val error = json.get("error")
            if (error != null) {
                val code = error.asJsonObject.get("code")?.asString ?: "unknown"
                val info = error.asJsonObject.get("info")?.asString ?: ""
                return@withContext Result.failure(Exception("API error: $code - $info"))
            }
            val move = json.getAsJsonObject("move")
            if (move != null) {
                Result.success(true)
            } else {
                Result.failure(Exception("Move failed: unexpected response"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============ 删除页面 ============

    suspend fun deletePage(
        title: String,
        reason: String = "批量删除"
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val csrfResult = getCsrfToken()
            if (csrfResult.isFailure) return@withContext Result.failure(csrfResult.exceptionOrNull()!!)
            val csrfToken = csrfResult.getOrThrow()

            val form = buildForm(
                "action" to "delete",
                "format" to "json",
                "title" to title,
                "reason" to reason,
                "token" to csrfToken
            )

            val req = Request.Builder()
                .url(getApiUrl())
                .headers(getHeaders())
                .post(form.toRequestBody(formMediaType))
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext Result.failure(Exception("Empty response"))
            val json = JsonParser.parseString(body).asJsonObject
            val error = json.get("error")
            if (error != null) {
                val code = error.asJsonObject.get("code")?.asString ?: "unknown"
                val info = error.asJsonObject.get("info")?.asString ?: ""
                return@withContext Result.failure(Exception("API error: $code - $info"))
            }
            val del = json.getAsJsonObject("delete")
            if (del != null) {
                Result.success(true)
            } else {
                Result.failure(Exception("Delete failed: unexpected response"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    data class BatchResult(
        val success: MutableList<String> = mutableListOf(),
        val failed: MutableList<Pair<String, String>> = mutableListOf(),
        val skipped: MutableList<String> = mutableListOf()
    )
}