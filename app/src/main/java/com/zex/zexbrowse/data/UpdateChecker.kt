/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.zex.zexbrowse.data

import com.zex.zexbrowse.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val version: String,
    val notes: String,
    val downloadUrl: String,
    /** 镜像站已同步该版本，可在浏览器中走高速通道下载 */
    val lemwoodAvailable: Boolean = false
)

data class Contributor(val name: String, val avatarUrl: String, val contributions: Int)

class UpdateChecker(
    private val client: OkHttpClient = defaultClient()
) {

    /**
     * 检查结果的三种情况。
     *
     * 与只返回 `ReleaseInfo?` 相比，关键是能区分「确实没有新版」与「网络不可达」：
     * 前者是真实结论，应该告诉用户「已是最新」；
     * 后者是检查失败，不能当成「已是最新」误导用户（这是本次修正的重点）。
     */
    data class CheckOutcome(
        /** 有新版本时的信息，未检出为 null */
        val release: ReleaseInfo?,
        /** 是否至少有一个来源成功响应（用于区分「无更新」与「断网」） */
        val anySourceOk: Boolean
    ) {
        /** 确实已是最新：检出无新版本，且至少一个源可用 */
        val isUpToDate: Boolean get() = release == null && anySourceOk
        /** 网络不可达：所有来源都失败，无法判断是否有更新 */
        val isUnreachable: Boolean get() = release == null && !anySourceOk
    }

    /**
     * 执行一次检查并报告来源可用性。
     *
     * 实现上与 [latest] 共用同一套聚合逻辑，但额外记录
     * 「是否有任意来源真的返回了结果」——这是区分断网与无更新的唯一依据。
     */
    suspend fun check(): CheckOutcome {
        val remote = MirrorChyan.latest(BuildConfig.VERSION_NAME)
        val mirrorOk = remote.code == 0
        val mirrorVersion = remote.version?.takeIf { remote.succeeded && isNewer(it) }

        val github = giteeLatest()
        val githubOk = github != null

        val lemwoodVersion = LemwoodMirror.latestVersion(client)
        val lemwood = lemwoodVersion?.takeIf { isNewer(it) }?.let { lemwoodRelease(it) }

        val anySourceOk = mirrorOk || githubOk || lemwoodVersion != null

        return CheckOutcome(release = aggregate(remote, mirrorVersion, github, lemwood), anySourceOk = anySourceOk)
    }

    /**
     * 检查是否有新版本。本地已是最新或所有来源都不可用时返回 null。
     *
     * 三个来源同时参与，各自贡献自己擅长的部分：
     * - Mirror酱：国内连通性最好，负责确认版本号与更新日志；
     * - GitHub：唯一能拿到 assets 直链的源，应用内下载依赖它；
     * - 柠枺镜像站：已同步该版本时标记可走浏览器高速通道。
     *
     * 三者并存而非短路：任一渠道出问题时另外两个还能用，
     * 弹窗里也会同时展示可用的下载入口，由用户自己选。
     */
    suspend fun latest(): ReleaseInfo? = check().release

    /**
     * 把各来源的结果合并成一个结论（取最高版本 + 优先用 GitHub 的说明与直链）。
     *
     * 拆出来供 [check] 调用：先把「是否有可达来源」判定完，再聚合版本信息。
     */
    private fun aggregate(
        remote: MirrorChyan.Result,
        mirrorVersion: String?,
        github: ReleaseInfo?,
        lemwood: ReleaseInfo?
    ): ReleaseInfo? {
        // 三方取最高版本，避免任何单一来源滞后掩盖真实的新版本。
        // 不能用拼接数字的方式比大小（段数不同的版本会得出错误结果），
        // 直接复用 isNewer 的逐段比较。
        val candidates = listOfNotNull(
            mirrorVersion?.let { ReleaseInfo(it, remote.releaseNote, "") },
            github,
            lemwood
        ).filter { isNewer(it.version) }

        if (candidates.isEmpty()) return null

        val best = candidates.reduce { a, b -> if (isNewer(b.version, a.version)) b else a }

        // 说明优先用 GitHub：它是唯一带完整 Markdown 正文的源；
        // Mirror酱 的 release_note 作次选，两者都空时用 best 自带的
        val notes = listOfNotNull(
            github?.notes?.takeIf { it.isNotBlank() },
            remote.releaseNote.takeIf { it.isNotBlank() && mirrorVersion != null },
            best.notes.takeIf { it.isNotBlank() }
        ).firstOrNull() ?: ""

        return best.copy(
            notes = notes,
            // 应用内下载只用 GitHub assets 直链：它是永久地址。
            // Mirror酱 的高速下载必须经网页完成，柠枺 的直链还要过 PoW 验证，
            // 两者都不能当作应用内下载地址。
            // 因此拿不到 GitHub 直链时留空，弹窗会把「下载」置灰，引导用户走浏览器通道。
            downloadUrl = github?.downloadUrl.orEmpty(),
            // 柠枺只要同步了该版本就标记，与最终选哪个来源无关
            lemwoodAvailable = lemwood != null
        )
    }

    /**
     * 按已知版本号构造镜像站结果。
     *
     * 下载必须经浏览器完成站点的 PoW 验证，因此这里只标记该版本可走高速通道，
     * 实际地址由界面拼成直链后交给浏览器打开。
     */
    private fun lemwoodRelease(version: String): ReleaseInfo {
        return ReleaseInfo(
            version = version,
            // 镜像站不提供更新说明，留空时界面显示「暂无更新说明」
            notes = "",
            downloadUrl = "",
            lemwoodAvailable = true
        )
    }

    /**
     * 主更新源：Gitee Releases（EnolaBrowser 仓库）。
     *
     * Gitee API v5 的 releases/latest 响应结构与 GitHub 同构（tag_name/body/assets），
     * 沿用原方案「REST API 单通道 + assets 直链做应用内下载」的解析方式。
     * 原 GitHub 的 Atom 订阅备用通道在 Gitee 上不存在，已移除；
     * 直连 Gitee 在大陆连通性良好，无需备用源。
     */
    private suspend fun giteeLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://gitee.com/api/v5/repos/pych2005/enola-browser/releases/latest")
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                // 404 表示仓库无 release；其余非 2xx 直接放弃本轮检查
                if (!response.isSuccessful) return@use null
                val responseBody = response.body ?: return@use null
                val json = JSONObject(responseBody.string())
                val tag = json.optString("tag_name").removePrefix("v")
                val assets = json.optJSONArray("assets")
                val apkAsset = assets?.let { array ->
                    (0 until array.length())
                        .map { array.getJSONObject(it) }
                        .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                }
                ReleaseInfo(
                    version = tag,
                    notes = json.optString("body"),
                    downloadUrl = apkAsset?.optString("browser_download_url").orEmpty()
                        // 部分 release 未上传 assets：按 tag 拼出 Gitee 的固定下载路径
                        .ifBlank {
                            if (tag.isNotBlank()) "https://gitee.com/pych2005/enola-browser/releases/download/v$tag/app-release.apk"
                            else ""
                        }
                )
            }
        }.getOrNull()
    }

    suspend fun contributors(): List<Contributor> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://api.github.com/repos/BaiXiaoTao520/ZexBrowse/contributors").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = response.body ?: error("empty body")
            val array = org.json.JSONArray(body.string())
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                Contributor(item.optString("login"), item.optString("avatar_url"), item.optInt("contributions"))
            }
        }
    }

    fun isNewer(remote: String, local: String = BuildConfig.VERSION_NAME): Boolean {
        val remoteParts = versionParts(remote)
        val localParts = versionParts(local)
        val count = maxOf(remoteParts.size, localParts.size)
        for (index in 0 until count) {
            val remotePart = remoteParts.getOrElse(index) { 0 }
            val localPart = localParts.getOrElse(index) { 0 }
            if (remotePart != localPart) return remotePart > localPart
        }
        return false
    }

    /**
     * 把版本号拆成数字段。
     *
     * 必须先去 v 前缀：Mirror酱 返回的 version_name 形如 "v1.1.0"，
     * GitHub 的 tag 也可能带 v。若不去，"v11".toIntOrNull() 为 null → 该段变成 0，
     * 导致 v11.1.7 被当成 0.1.7，永远比本地 11.x 小，新版本检测直接失效。
     */
    private fun versionParts(version: String): List<Int> =
        version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('+')
            .substringBefore('-')
            .split('.')
            .map { part -> part.toIntOrNull() ?: 0 }

    companion object {
        // 更新检查与镜像查询都需要等待网络，统一设置较短超时，避免长时间无响应
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
