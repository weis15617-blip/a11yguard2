package com.merin.a11yguard2

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 更新界面：检查、弹窗展示更新说明、下载并交给系统安装器。
 *
 * 这里集中处理「弹窗长什么样」和「下载到哪」，UpdateChecker 只管取数据。
 */
object UpdateUi {

    /** 弹窗里展示的样式与文案，一次写定，后续只需改这里。 */
    private const val TITLE = "发现新版本"
    private const val BTN_UPDATE = "下载并安装"
    private const val BTN_SKIP = "跳过此版本"
    private const val BTN_LATER = "以后再说"

    /** 自动检查的最小间隔：12 小时。 */
    private const val AUTO_INTERVAL_MS = 12L * 60 * 60 * 1000

    /**
     * 检查并按需弹窗。必须在线程里调用（内部切回 UI 线程弹窗）。
     *
     * @param manual true = 用户主动点「检查更新」，此时无论有没有更新都要给反馈
     */
    fun checkAndPrompt(act: Activity, manual: Boolean) {
        val src = AppSettings.updateSource(act)
        if (src.isEmpty()) {
            if (manual) toast(act, "还没设置更新源：请先在设置页填 owner/repo 或 JSON 地址")
            return
        }
        Thread {
            val r = UpdateChecker.check(act, src)
            AppSettings.setLastUpdateCheckAt(act, System.currentTimeMillis())
            Handler(Looper.getMainLooper()).post {
                when {
                    !r.ok -> if (manual) toast(act, r.error ?: "检查失败")
                    !r.hasUpdate -> if (manual) toast(act, "已是最新版本（${UpdateChecker.currentVersionName(act)}）")
                    else -> {
                        val info = r.info ?: return@post
                        // 用户跳过过这个版本且不是手动检查 → 不打扰
                        if (!manual && info.versionCode == AppSettings.skippedUpdateCode(act)) return@post
                        showDialog(act, info, manual)
                    }
                }
            }
        }.start()
    }

    /** 手动检查入口（设置页按钮用）。 */
    fun checkManual(act: Activity) {
        if (AppSettings.updateSource(act).isEmpty()) {
            toast(act, "请先填写更新源")
            return
        }
        toast(act, "正在检查更新…")
        checkAndPrompt(act, manual = true)
    }

    /**
     * 自动检查（应用启动时调用）。
     *
     * 触发条件（全部满足才检查，避免打扰和浪费流量）：
     *   1. 用户开启了「检查更新」开关
     *   2. 填了更新源
     *   3. 距上次检查 >= AUTO_INTERVAL_MS（默认 12 小时）
     *   4. 该版本没有被用户「跳过」
     *
     * 静默失败：自动检查不弹「已是最新」也不弹错误提示，
     * 只有真的发现新版本时才弹窗。用户主动检查才给反馈。
     */
    fun checkOnLaunch(act: Activity) {
        if (!AppSettings.updateCheckOn(act)) return
        if (AppSettings.updateSource(act).isEmpty()) return
        val last = AppSettings.lastUpdateCheckAt(act)
        if (last > 0 && System.currentTimeMillis() - last < AUTO_INTERVAL_MS) return

        Thread {
            val src = AppSettings.updateSource(act)
            val r = UpdateChecker.check(act, src)
            AppSettings.setLastUpdateCheckAt(act, System.currentTimeMillis())
            if (!r.ok) {
                Diag.log("update", "auto check failed: ${r.error}")
                return@Thread
            }
            val info = r.info ?: return@Thread
            if (!r.hasUpdate) {
                Diag.log("update", "auto check: 已是最新 ${UpdateChecker.currentVersionName(act)}")
                return@Thread
            }
            if (info.versionCode == AppSettings.skippedUpdateCode(act)) {
                Diag.log("update", "auto check: ${info.versionName} 已被用户跳过")
                return@Thread
            }
            Handler(Looper.getMainLooper()).post {
                if (act.isFinishing || act.isDestroyed) return@post
                showDialog(act, info, manual = false)
            }
        }.start()
    }



    // ---------- 弹窗 ----------

    private fun showDialog(act: Activity, info: UpdateChecker.Info, manual: Boolean) {
        val cur = UpdateChecker.currentVersionName(act)

        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(act, 20), dp(act, 8), dp(act, 20), dp(act, 4))
        }

        box.addView(TextView(act).apply {
            text = "当前版本 $cur　→　新版本 ${info.versionName}"
            textSize = 14f
            setTextColor(Color.parseColor("#9AA0A6"))
        })

        if (info.notes.isNotBlank()) {
            val notes = TextView(act).apply {
                text = info.notes.trim()
                textSize = 13f
                setTextColor(Color.parseColor("#E8EAED"))
                setPadding(0, dp(act, 10), 0, 0)
            }
            val sv = ScrollView(act).apply {
                addView(notes)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 240)
                )
            }
            box.addView(sv)
        } else {
            box.addView(TextView(act).apply {
                text = "（该版本没有填写更新说明）"
                textSize = 13f
                setTextColor(Color.parseColor("#9AA0A6"))
                setPadding(0, dp(act, 10), 0, 0)
            })
        }

        val dlg = AlertDialog.Builder(act)
            .setTitle(TITLE)
            .setView(box)
            .setPositiveButton(BTN_UPDATE, null)
            .setNeutralButton(BTN_SKIP) { _, _ ->
                AppSettings.setSkippedUpdateCode(act, info.versionCode)
                toast(act, "已跳过 ${info.versionName}，之后不再提示（可手动检查）")
            }
            .setNegativeButton(BTN_LATER, null)
            .create()

        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (info.downloadUrl.isBlank()) {
                    toast(act, "该版本没有提供 APK 下载地址")
                    return@setOnClickListener
                }
                dlg.dismiss()
                downloadAndInstall(act, info.downloadUrl, info.versionName)
            }
        }
        dlg.show()
    }

    // ---------- 下载 ----------

    /**
     * 下载 APK 并用系统安装器打开。
     *
     * 为什么要自己下载而不是直接把 URL 丢给浏览器：部分分发地址会返回
     * 需要 Referer 或直接给 HTML 页面，交给浏览器容易下载到错误文件。
     * 这里下载到应用私有目录、校验 ZIP 头，再用 FileProvider 交给系统安装器。
     */
    private fun downloadAndInstall(act: Activity, url: String, ver: String) {
        val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        val label = TextView(act).apply {
            text = "准备下载…"
            textSize = 13f
            setTextColor(Color.parseColor("#E8EAED"))
        }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(act, 20), dp(act, 16), dp(act, 20), dp(act, 8))
            addView(label)
            addView(bar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(act, 12) })
        }
        val dlg = AlertDialog.Builder(act)
            .setTitle("正在下载 $ver")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("取消", null)
            .create()
        dlg.show()

        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        dlg.setOnDismissListener { cancelled.set(true) }

        Thread {
            val main = Handler(Looper.getMainLooper())
            val dst = File(act.getExternalFilesDir(null) ?: act.filesDir, "update-$ver.apk")
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 60000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "a11yguard2-updater")
                }
                conn.connect()
                val code = conn.responseCode
                if (code != 200) throw RuntimeException("HTTP $code")
                val total = conn.contentLengthLong
                var read = 0L

                conn.inputStream.use { input ->
                    FileOutputStream(dst).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled.get()) return@Thread
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val pct = (read * 100 / total).toInt()
                                main.post {
                                    bar.progress = pct
                                    label.text = "已下载 %.1f / %.1f MB".format(
                                        read / 1048576.0, total / 1048576.0
                                    )
                                }
                            }
                        }
                    }
                }
                conn.disconnect()

                // 校验确实是 APK（ZIP 头 PK\x03\x04）
                val ok = runCatching {
                    dst.inputStream().use { ins ->
                        val h = ByteArray(4)
                        ins.read(h)
                        h[0] == 0x50.toByte() && h[1] == 0x4B.toByte()
                    }
                }.getOrDefault(false)

                main.post {
                    dlg.dismiss()
                    if (!ok) {
                        toast(act, "下载的文件不是有效 APK，已删除")
                        dst.delete()
                        return@post
                    }
                    installApk(act, dst)
                }
            } catch (e: Exception) {
                main.post {
                    dlg.dismiss()
                    toast(act, "下载失败：" + (e.message ?: e.javaClass.simpleName))
                    dst.delete()
                }
            }
        }.start()
    }

    /** 用系统安装器打开 APK（不静默安装）。 */
    private fun installApk(act: Activity, apk: File) {
        runCatching {
            val uri: Uri = ApkFileProvider.uriFor(act, apk)
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            act.startActivity(i)
            toast(act, "请在弹出的安装界面确认")
        }.onFailure {
            Diag.log("update", "install failed: ${it.message}")
            toast(act, "无法调起安装器：" + (it.message ?: ""))
        }
    }

    // ---------- 工具 ----------

    private fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
        ).toInt()

    private fun toast(ctx: Context, msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }
}
