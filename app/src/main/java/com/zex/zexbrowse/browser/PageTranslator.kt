package com.zex.zexbrowse.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 页面翻译接口：把源文本批量译成中文。
 *
 * 不再内置代理，所有请求直连。依次兜底：Microsoft Edge 接口 → Google 公开端点 →
 * 腾讯交互翻译（国内直连，作为受限网络下的兜底）。
 *
 * 返回值与入参严格同序；失败的段以空串占位，内容脚本会保留原文。
 */
object PageTranslator {

    private val mediaJson = "application/json; charset=utf-8".toMediaType()
    // 并发压低到 4：Google gtx 端点对短时间高频请求会回 429，长页面整页翻译时更稳
    // Google 批量端点单请求很小（~45 段/0.1-0.2 秒），并发可以给到 8，整页批数越多越划算
    private val concurrency = Semaphore(8)
    // 微软逐段请求，单独放宽到 5，配合连接复用能把整批压到 1~2 轮
    private val msConcurrency = Semaphore(5)

    /** 调试用日志出口（由 BrowserViewModel 注入，写 App 私有诊断文件） */
    internal var debugLog: ((String) -> Unit)? = null

    private fun newClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    /** 复用一份 client：连接池 keep-alive，省掉每段一次的 TLS 握手 */
    private val directClient: OkHttpClient by lazy { newClient() }

    /** 取 Edge token 专用：部分 CDN 对 HTTP/2 的这条路径回 404，强制退到 HTTP/1.1 再试 */
    private val edgeClient: OkHttpClient by lazy {
        newClient().newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()
    }

    /**
     * 批量翻译，返回与入参同序的译文列表（失败的段为空串）；所有源都不可用时返回 null。
     *
     * 顺序说明：
     *  1) 微软批量端点（Edge 同款 api-edge，一次翻几十段）——需要 Edge token，而 edge.microsoft.com
     *     目前直连回 404（根路径还回 400「services aren't available」），基本拿不到，只在能拿到时顺手用；
     *  2) Google 批量端点（一次 GET 带多个 q，几十段一个往返）——质量最好，受限网络下可能失败；
     *  3) 腾讯一次批量（直连）——前两者不通时的兜底；
     *  4) 剩下漏网的空槽用微软逐段补（通常 0 段，不产生额外耗时）。
     */
    suspend fun translate(texts: List<String>, target: String = "zh-Hans"): List<String>? {
        if (texts.isEmpty()) return emptyList()
        // 1) 能拿到 Edge token 就走微软批量端点（和 Edge 浏览器同一条路）
        val token = edgeToken()
        if (token != null) {
            val batch = edgeBatch(texts, target, token)
            if (batch != null && batch.any { it.isNotEmpty() }) return batch
        }
        // 2) Google 批量（质量最好，一次请求多段）
        val google = googleTranslate(texts, target)
        if (google != null && google.any { it.isNotBlank() }) return google
        // 3) 腾讯一次批量（兜底）
        val tencent = tencentTranslate(texts, target)
        if (tencent != null) {
            val results = tencent.toMutableList()
            val missing = results.indices.filter { results[it].isNullOrBlank() }
            if (missing.isNotEmpty()) {
                val ms = microsoftTranslate(missing.map { texts[it] }, target)
                if (ms != null) {
                    missing.forEachIndexed { i, idx ->
                        ms.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { results[idx] = it }
                    }
                }
                debugLog?.invoke("ms fill ok=${results.count { !it.isNullOrBlank() }}/${texts.size}")
            }
            if (results.any { it.isNotEmpty() }) return results
        }
        return null
    }

    // ---------------- Microsoft 批量（Edge 同款） ----------------

    @Volatile
    private var edgeTokenCache: String? = null

    @Volatile
    private var edgeTokenAt: Long = 0L

    /** 端点不可用的时间戳：一旦确认 404/400，就 10 分钟内不再白跑（否则每批都白等 1 秒） */
    @Volatile
    private var edgeDeadAt: Long = 0L

    /** 取 Edge 的免费翻译 token（10 分钟有效）；拿不到返回 null，不抛错、不阻塞主流程。 */
    private fun edgeToken(): String? {
        val now = System.currentTimeMillis()
        edgeTokenCache?.let { if (now - edgeTokenAt < 9 * 60 * 1000L) return it }
        if (now - edgeDeadAt < 10 * 60 * 1000L) return null
        return runCatching {
            val request = Request.Builder()
                .url("https://edge.microsoft.com/translate/auth")
                .header("User-Agent", MS_UA)
                .header("Accept", "*/*")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Origin", "https://edge.microsoft.com")
                .header("Referer", "https://edge.microsoft.com/")
                .get()
                .build()
            edgeClient.newCall(request).execute().use { response ->
                debugLog?.invoke("edge auth http=${response.code}")
                if (!response.isSuccessful) {
                    edgeDeadAt = System.currentTimeMillis()
                    return@use null
                }
                val token = response.body?.string()?.trim().orEmpty()
                if (token.isEmpty() || token.startsWith("<")) null
                else token.also { edgeTokenCache = it; edgeTokenAt = System.currentTimeMillis() }
            }
        }.getOrNull()
    }

    /** 微软批量端点：一次 POST 数组，几十段一起翻（Edge 浏览器整页翻译就是这么做的）。 */
    private fun edgeBatch(texts: List<String>, target: String, token: String): List<String>? = runCatching {
        val body = JSONArray()
        texts.forEach { body.put(JSONObject().put("Text", it)) }
        val request = Request.Builder()
            .url("https://api-edge.cognitive.microsofttranslator.com/translate?api-version=3.0&to=${msTarget(target)}")
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json; charset=utf-8")
            .post(body.toString().toRequestBody(mediaJson))
            .build()
        directClient.newCall(request).execute().use { response ->
            debugLog?.invoke("edge batch http=${response.code} n=${texts.size}")
            if (!response.isSuccessful) return@use null
            val arr = JSONArray(response.body?.string() ?: return@use null)
            if (arr.length() != texts.size) return@use null
            (0 until arr.length()).map { i ->
                arr.getJSONObject(i).getJSONArray("translations").getJSONObject(0).getString("text")
            }
        }
    }.getOrNull()

    // ---------------- Google（批量端点，直连） ----------------

    /**
     * 批量端点：一次 GET 带多个 q 参数，几十段一起翻（Edge/Chrome 整页翻译的快就快在"批量"）。
     * 返回的数组形如 [["译文","en"],["译文","en"]]，单段时可能是 ["译文","en"]。
     */
    private fun googleBatchOnce(texts: List<String>, target: String): List<String>? = runCatching {
        val builder = "https://translate.googleapis.com/translate_a/t".toHttpUrl().newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", "auto")
            .addQueryParameter("tl", if (target.startsWith("zh")) "zh-CN" else target)
            .addQueryParameter("format", "html")
        texts.forEach { builder.addQueryParameter("q", it) }
        val request = Request.Builder()
            .url(builder.build())
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36")
            .get()
            .build()
        directClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val raw = response.body?.string() ?: return@use null
            val arr = JSONArray(raw)
            if (arr.length() == 0) return@use null
            if (arr.optJSONArray(0) == null) {
                // 单段返回 ["译文","en"]
                return@use listOf(arr.optString(0))
            }
            (0 until arr.length()).map { i ->
                val item = arr.optJSONArray(i) ?: return@use null
                item.optString(0)
            }
        }
    }.getOrNull()

    private suspend fun googleTranslate(texts: List<String>, target: String): List<String>? =
        withContext(Dispatchers.IO) {
            // 按累计字符切批：URL 太长会被 Google 拒（414/413），4800 字符一批仍安全
            val chunks = ArrayList<List<String>>()
            var cur = ArrayList<String>()
            var curLen = 0
            texts.forEach { t ->
                if (cur.isNotEmpty() && curLen + t.length > 4800) {
                    chunks.add(cur); cur = ArrayList(); curLen = 0
                }
                cur.add(t); curLen += t.length + 8
            }
            if (cur.isNotEmpty()) chunks.add(cur)
            val results = coroutineScope {
                chunks.map { chunk ->
                    async { concurrency.withPermit { googleBatchOnce(chunk, target) } }
                }.awaitAll()
            }
            val out = ArrayList<String>()
            results.forEachIndexed { idx, part ->
                val chunk = chunks[idx]
                if (part == null || part.size != chunk.size) {
                    // 该批整体失败：留空槽给后面的源；保证 out 长度与入参一致
                    repeat(chunk.size) { out.add("") }
                } else out.addAll(part)
            }
            val hits = out.count { it.isNotBlank() }
            debugLog?.invoke("google ok=$hits/${texts.size} batches=${chunks.size}")
            if (hits == 0) null else out
        }

    // ---------------- Microsoft（Bing / Edge 同源，国内直连） ----------------

    private const val MS_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0"

    private const val BING_PAGE = "https://cn.bing.com/translator"

    @Volatile
    private var bingIg: String? = null

    @Volatile
    private var bingToken: String? = null

    @Volatile
    private var bingKey: String? = null

    @Volatile
    private var bingAuthAt: Long = 0L

    private fun msTarget(target: String): String = if (target.startsWith("zh")) "zh-Hans" else target

    /** 从 Bing 翻译页抓 IG / token / key，缓存约 20 分钟；过期或强制时重新抓。 */
    @Synchronized
    private fun bingAuth(force: Boolean = false): Triple<String, String, String>? {
        val now = System.currentTimeMillis()
        if (!force) {
            val ig = bingIg
            val token = bingToken
            val key = bingKey
            if (ig != null && token != null && key != null && now - bingAuthAt < 20 * 60 * 1000L) {
                return Triple(ig, token, key)
            }
        }
        return runCatching {
            val request = Request.Builder()
                .url(BING_PAGE)
                .header("User-Agent", MS_UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .get()
                .build()
            directClient.newCall(request).execute().use { response ->
                debugLog?.invoke("bing auth http=${response.code}")
                if (!response.isSuccessful) return@use null
                val html = response.body?.string().orEmpty()
                val helper = Regex("params_AbusePreventionHelper\\s*=\\s*\\[(\\d+)\\s*,\\s*\"([^\"]+)\"")
                    .find(html) ?: return@use null
                val ig = Regex("IG:\"([^\"]+)\"").find(html)?.groupValues?.get(1) ?: return@use null
                val triple = Triple(ig, helper.groupValues[2], helper.groupValues[1])
                bingIg = triple.first
                bingToken = triple.second
                bingKey = triple.third
                bingAuthAt = System.currentTimeMillis()
                triple
            }
        }.getOrNull()
    }

    /** 单段翻译：ttranslatev3 一次只接受一段，靠外层并发提吞吐。 */
    private fun bingOne(text: String, target: String, auth: Triple<String, String, String>): String? = runCatching {
        val form = FormBody.Builder()
            .add("fromLang", "auto-detect")
            .add("to", msTarget(target))
            .add("text", text)
            .add("token", auth.second)
            .add("key", auth.third)
            .build()
        val request = Request.Builder()
            .url("https://cn.bing.com/ttranslatev3?isVertical=1&&IG=${auth.first}&IID=translator.5028.1")
            .header("User-Agent", MS_UA)
            .header("Referer", BING_PAGE)
            .post(form)
            .build()
        directClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val raw = response.body?.string().orEmpty()
            if (raw.isBlank() || raw.trimStart().startsWith("{") ) return@use null
            val arr = JSONArray(raw)
            val tr = arr.optJSONObject(0)?.optJSONArray("translations")?.optJSONObject(0)
                ?.optString("text").orEmpty()
            tr.ifEmpty { null }
        }
    }.getOrNull()

    private suspend fun microsoftTranslate(texts: List<String>, target: String): List<String?>? =
        withContext(Dispatchers.IO) {
            val auth = bingAuth() ?: return@withContext null
            var results = texts.map { t -> async { msConcurrency.withPermit { bingOne(t, target, auth) } } }
                .awaitAll().toMutableList()
            var hits = results.count { it != null }
            debugLog?.invoke("bing ok=$hits/${texts.size}")
            if (hits < texts.size) {
                // 少量漏网/被限流：补一次，失败的段后面交给腾讯批量补齐
                val retryIdx = results.indices.filter { results[it] == null }
                if (retryIdx.isNotEmpty()) {
                    val fresh = bingAuth(force = hits == 0)
                    if (fresh != null) {
                        val again = retryIdx.map { i -> async { msConcurrency.withPermit { bingOne(texts[i], target, fresh) } } }.awaitAll()
                        retryIdx.forEachIndexed { k, i -> results[i] = again[k] }
                        hits = results.count { it != null }
                        debugLog?.invoke("bing retry ok=$hits/${texts.size}")
                    }
                }
            }
            if (hits == 0) null else results
        }

    /**
     * 预热：页面 attach 后就在后台做两件事，把"首次翻译"的固定开销提前付掉。
     *  1) 摸一次 Edge token（能拿到就走微软批量）；
     *  2) 用一个极小的翻译请求把到 translate.googleapis.com 的 TLS/连接池建起来
     *     —— 实测首次请求光握手就要 1.1 秒，预热后首屏从 ~1.3 秒降到 ~0.3 秒。
     */
    suspend fun prewarm() = withContext(Dispatchers.IO) {
        runCatching { edgeToken() }
        runCatching { googleBatchOnce(listOf("ok"), "zh-Hans") }
        Unit
    }

    // ---------------- 腾讯 ----------------

    private const val TENClientKey =
        "browser-chrome-110.0.0-Windows-10.0.0-8f3a1c9d-0000-0000-0000-000000000000"

    private suspend fun tencentTranslate(texts: List<String>, target: String): List<String>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val textList = JSONArray()
                texts.forEach { textList.put(it) }
                val body = JSONObject()
                    .put("header", JSONObject().put("fn", "auto_translation").put("client_key", TENClientKey))
                    .put("type", "plain")
                    .put("model_category", "normal")
                    .put("text_domain", "general")
                    .put("source", JSONObject().put("lang", "auto").put("text_list", textList))
                    .put("target", JSONObject().put("lang", if (target.startsWith("zh")) "zh" else target))
                val request = Request.Builder()
                    .url("https://transmart.qq.com/api/imt")
                    .header("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(mediaJson))
                    .build()
                directClient.newCall(request).execute().use { response ->
                    debugLog?.invoke("tencent http=${response.code}")
                    val raw = response.body?.string().orEmpty()
                    debugLog?.invoke("tencent body=${raw.take(110)}")
                    if (raw.isEmpty()) return@use null
                    val json = JSONObject(raw)
                    val head = json.optJSONObject("header")
                    if (head != null && head.optString("ret_code") != "succ") return@use null
                    val arr = json.optJSONArray("auto_translation") ?: return@use null
                    val out = (0 until arr.length()).map { arr.optString(it) }
                    if (out.size != texts.size) null else out
                }
            }.onFailure {
                debugLog?.invoke("tencent err=${it.javaClass.simpleName}:${it.message}")
            }.getOrNull()
        }
}
