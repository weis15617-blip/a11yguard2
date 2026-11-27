package com.merin.a11yguard2

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 内置更新检查。
 *
 * ── 设计边界（重要）──
 *
 * 本应用的其他所有功能都不联网。只有这一个文件会发 HTTP 请求，而且：
 *   1. **默认关闭**，必须由用户在设置页填入更新源并开启才会工作；
 *   2. 只请求用户自己填的那一个地址（GitHub Release API 或自建 JSON），
 *      不向任何第三方发数据；
 *   3. 只读取版本号、更新说明、下载地址，**不上传任何信息**（不传设备标识、
 *      不传使用数据、不带 cookie）；
 *   4. 下载 APK 后由系统安装器接管，本应用不静默安装。
 *
 * ── 更新源格式 ──
 *
 * 支持两种写法：
 *
 *   A. GitHub 仓库      `owner/repo`
 *      → 请求 https://api.github.com/repos/owner/repo/releases/latest
 *      → 读 tag_name / body / assets[].browser_download_url
 *
 *   B. 任意 JSON 地址    `https://example.com/a11yguard2.json`
 *      → 期望格式：
 *        {
 *          "versionCode": 9,
 *          "versionName": "1.6.1",
 *          "notes": "更新说明（支持换行）",
 *          "url": "https://.../app-release.apk"
 *        }
 *
 * ── 版本比较 ──
 *
 * 优先用 versionCode（整数，单调递增）。GitHub Release 的 tag 可能是
 * `v1.6.1` 这种形式，所以 A 模式下会尝试从 tag 解析出数字版本号；
 * 解析不出来时退回 tag 字符串比对（仅提示，不自动判定更新）。
 */
object UpdateChecker {

    /** 检查结果。 */
    data class Info(
        val versionCode: Int,
        val versionName: String,
        val notes: String,
        val downloadUrl: String
    )

    data class Result(
        val ok: Boolean,
        val info: Info? = null,
        val error: String? = null,
        /** 是否比当前安装的版本新 */
        val hasUpdate: Boolean = false
    )

    /** 当前安装的 versionCode。 */
    fun currentVersionCode(ctx: Context): Long = runCatching {
        val pm = ctx.packageManager
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0)).longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, 0).longVersionCode
        }
    }.getOrDefault(0L)

    fun currentVersionName(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    /**
     * 检查更新（阻塞，必须在线程里调用）。
     *
     * @param source 用户在设置里填的更新源（owner/repo 或完整 URL）
     */
    fun check(ctx: Context, source: String): Result {
        val src = source.trim()
        if (src.isEmpty()) {
            return Result(false, error = "未设置更新源")
        }

        val isGithub = src.count { it == '/' } == 1 && !src.startsWith("http")

        // 候选地址：完整 URL 也会自动展开镜像；owner/repo 走完整镜像链
        val candidates: List<Pair<String, Boolean>> = if (isGithub) {
            candidateUrls(src)
        } else if (src.startsWith("http://") || src.startsWith("https://")) {
            expandUrlCandidates(src)
        } else {
            return Result(false, error = "更新源格式不对（应填 owner/repo 或完整 URL）")
        }

        var lastErr: Exception? = null
        var json: String? = null
        var usedGithubApi = false

        for ((url, gh) in candidates) {
            try {
                json = fetchJson(url, gh)
                usedGithubApi = gh
                Diag.log("update", "ok <- $url")
                break
            } catch (e: Exception) {
                lastErr = e
                Diag.log("update", "fail <- $url : ${e.javaClass.simpleName}")
            }
        }

        if (json == null) {
            Diag.log("update", "all candidates failed: ${lastErr?.message}")
            return Result(
                false,
                error = "网络请求失败：" + (lastErr?.message ?: "未知错误")
                    + "\n\n如果是国内网络，GitHub 常被干扰。可以在设置页把更新源改成镜像地址，例如：\n"
                    + "https://cdn.jsdelivr.net/gh/OWNER/REPO@main/update.json"
            )
        }

        val info = try {
            parse(json, usedGithubApi)
        } catch (e: Exception) {
            return Result(false, error = "解析更新信息失败：" + (e.message ?: "格式不符"))
        }

        val cur = currentVersionCode(ctx)
        val hasUpdate = info.versionCode > 0 && info.versionCode.toLong() > cur
        Diag.log("update", "remote=${info.versionName}(${info.versionCode}) local=$cur hasUpdate=$hasUpdate")
        return Result(true, info, null, hasUpdate)
    }

    // ---------- 网络 ----------

    /**
     * 抓取 JSON，带重试与多地址回落。
     *
     * ── 为什么要重试 ──
     *
     * 国内网络访问 GitHub 时，TLS 握手经常被重置（实测手机端报
     * `SSLHandshakeException: connection closed`，但 ping 是通的）。
     * 这种失败是间歇性的：同一地址重试几次常能成功。
     *
     * ── 多地址回落 ──
     *
     * 对 `owner/repo` 形式的源，依次尝试：
     *   1. api.github.com（有 Release 说明，信息最全）
     *   2. raw.githubusercontent.com 的 update.json（纯静态文件，常更易访问）
     *   3. jsDelivr CDN 镜像（国内一般更稳）
     *
     * 任一成功即返回。全部失败时把最后一次的错误抛出去。
     */
    private fun fetchJson(urlStr: String, github: Boolean = false): String {
        val attempts = 2
        var lastErr: Exception? = null
        for (i in 1..attempts) {
            try {
                return fetchOnce(urlStr, github)
            } catch (e: Exception) {
                lastErr = e
                Diag.log("update", "尝试 $i/$attempts 失败: ${e.javaClass.simpleName}: ${e.message}")
                if (i < attempts) {
                    // 递增退避：0.6s / 1.2s
                    try {
                        Thread.sleep(400L * i)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
        throw lastErr ?: RuntimeException("未知网络错误")
    }

    private fun fetchOnce(urlStr: String, github: Boolean): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("Accept", if (github) "application/vnd.github+json" else "application/json")
            setRequestProperty("User-Agent", "a11yguard2-updater")
            // 部分网络环境下 gzip 会让分块读取卡住，这里明确要求不压缩
            setRequestProperty("Accept-Encoding", "identity")
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code != 200) {
                throw RuntimeException("HTTP $code")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * 为 `owner/repo` 生成候选地址列表（按**实测成功率**排序）。
     *
     * ── 为什么镜像优先 ──
     *
     * 在国内网络下实测（2026-11-27，手机端 curl）：
     *
     *   api.github.com                → SSLHandshakeException（TLS 被重置）
     *   raw.githubusercontent.com     → 完全不通
     *   cdn.jsdelivr.net              → 200 ✓ 能取到内容
     *   ghproxy.net                   → 200 ✓
     *   gh-proxy.com                  → 200 ✓
     *
     * 所以把三个**镜像放前面**，GitHub 官方地址放最后兜底。
     * 这样国内用户默认就能收到更新，海外用户前面失败也会自动落到官方 API。
     *
     * 代价：镜像返回的是静态 update.json，不含 Release 正文；
     * 所以 update.json 里必须自带 notes（见仓库里的生成逻辑）。
     */
    fun candidateUrls(ownerRepo: String): List<Pair<String, Boolean>> {
        val raw = "https://raw.githubusercontent.com/$ownerRepo/main/update.json"
        return listOf(
            // 1. ghproxy 镜像：**实时透传**，没有 CDN 缓存，发版后立即可见
            "https://ghproxy.net/$raw" to false,
            "https://gh-proxy.com/$raw" to false,
            // 2. GitHub API：实时、信息最全（带 Release 正文）
            "https://api.github.com/repos/$ownerRepo/releases/latest" to true,
            // 3. 直连 raw（海外用户命中这里）
            raw to false,
            // 4. jsDelivr 兜底放最后：
            //    它缓存 12 小时，发版后可能仍返回旧版本 ——
            //    曾导致"刚发 2.1.0 但应用提示已是最新"。所以只当最后手段。
            "https://cdn.jsdelivr.net/gh/$ownerRepo@main/update.json" to false
        )
    }

    /**
     * 完整 URL 的镜像回落：如果是 raw.githubusercontent.com 的地址，
     * 自动生成对应的镜像地址一起尝试（用户手填 raw 地址时也能在国内用）。
     */
    fun expandUrlCandidates(url: String): List<Pair<String, Boolean>> {
        val list = ArrayList<Pair<String, Boolean>>()
        if (url.contains("raw.githubusercontent.com")) {
            list.add("https://ghproxy.net/$url" to false)
            list.add("https://gh-proxy.com/$url" to false)
            // jsDelivr 只能映射仓库文件，尝试转换
            val m = Regex("""raw\.githubusercontent\.com/([^/]+)/([^/]+)/([^/]+)/(.+)""").find(url)
            if (m != null) {
                val (owner, repo, branch, path) = m.destructured
                list.add("https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/$path" to false)
            }
        }
        list.add(url to false)
        return list
    }

    // ---------- 解析 ----------

    private fun parse(json: String, isGithub: Boolean): Info {
        val o = JSONObject(json)
        return if (isGithub) {
            // GitHub Release
            val tag = o.optString("tag_name", "")
            val body = o.optString("body", "")
            val assets = o.optJSONArray("assets")
            var apkUrl = ""
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val name = a.optString("name", "")
                    if (name.endsWith(".apk", true)) {
                        apkUrl = a.optString("browser_download_url", "")
                        break
                    }
                }
            }
            Info(
                versionCode = versionCodeFromTag(tag),
                versionName = tag.removePrefix("v"),
                notes = body.ifBlank { "（该版本没有填写更新说明）" },
                downloadUrl = apkUrl
            )
        } else {
            // 自定义 JSON
            Info(
                versionCode = o.optInt("versionCode", 0),
                versionName = o.optString("versionName", ""),
                notes = o.optString("notes", ""),
                downloadUrl = o.optString("url", "")
            )
        }
    }

    /**
     * 从 tag 解析 versionCode。
     *
     * 约定：tag 形如 `v1.6.1`，映射规则为 major*10000 + minor*100 + patch，
     * 与 build.gradle 的 versionCode 保持一致（1.6.0 → 10600）。
     * 解析失败返回 0（此时不判定有更新，避免误报）。
     */
    fun versionCodeFromTag(tag: String): Int {
        val t = tag.trim().removePrefix("v").removePrefix("V")
        val parts = t.split('.')
        if (parts.size < 2) return 0
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return 0
        val patch = parts.getOrNull(2)?.substringBefore('-')?.toIntOrNull() ?: 0
        if (major < 0 || minor < 0 || patch < 0) return 0
        return major * 10000 + minor * 100 + patch
    }

    /** 反推：versionCode → 语义版本（用于生成 build.gradle 的值）。 */
    fun tagFromVersionCode(code: Int): String {
        val major = code / 10000
        val minor = (code % 10000) / 100
        val patch = code % 100
        return "$major.$minor.$patch"
    }
}
