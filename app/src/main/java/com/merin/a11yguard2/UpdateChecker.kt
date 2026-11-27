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

        val json = try {
            when {
                // 完整 URL
                src.startsWith("http://") || src.startsWith("https://") -> fetchJson(src)
                // owner/repo 形式
                src.count { it == '/' } == 1 -> {
                    val api = "https://api.github.com/repos/$src/releases/latest"
                    fetchJson(api, github = true)
                }
                else -> return Result(false, error = "更新源格式不对（应填 owner/repo 或完整 URL）")
            }
        } catch (e: Exception) {
            Diag.log("update", "fetch failed: ${e.javaClass.simpleName}: ${e.message}")
            return Result(false, error = "网络请求失败：" + (e.message ?: e.javaClass.simpleName))
        }

        val info = try {
            parse(json, src.count { it == '/' } == 1)
        } catch (e: Exception) {
            return Result(false, error = "解析更新信息失败：" + (e.message ?: "格式不符"))
        }

        val cur = currentVersionCode(ctx)
        val hasUpdate = info.versionCode > 0 && info.versionCode.toLong() > cur
        Diag.log("update", "remote=${info.versionName}(${info.versionCode}) local=$cur hasUpdate=$hasUpdate")
        return Result(true, info, null, hasUpdate)
    }

    // ---------- 网络 ----------

    private fun fetchJson(urlStr: String, github: Boolean = false): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept", if (github) "application/vnd.github+json" else "application/json")
            setRequestProperty("User-Agent", "a11yguard2-updater")
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code != 200) {
                throw RuntimeException("HTTP $code")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
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
