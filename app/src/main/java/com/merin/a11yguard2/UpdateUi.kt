package com.merin.a11yguard2

import android.provider.Settings
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
     * 已下载的更新包放在哪。
     *
     * 用 `filesDir/updates`，不用 `getExternalFilesDir`：
     * 后者位于外部存储，某些机型的清理/迁移策略会把它删掉，
     * 表现为「下载完去授权，回来安装包不见了」。
     *
     * 私有目录的代价是用户手动找不到，但我们提供「直接安装」入口，
     * 不需要他去找文件。
     */
    private fun updateDir(act: Activity): File =
        File(act.filesDir, "updates").apply { mkdirs() }

    /** 某个版本的 APK 路径。 */
    private fun apkFor(act: Activity, ver: String): File =
        File(updateDir(act), "update-$ver.apk")

    /** 已下载完成、可安装的 APK（若存在）。 */
    fun pendingApk(act: Activity, ver: String): File? {
        val f = apkFor(act, ver)
        return if (f.isFile && f.length() > 1024 * 100) f else null
    }

    /**
     * 清理旧版本残留的 APK。
     *
     * 下载新版本时调用，避免私有目录越积越大（也不会误删当前版本）。
     */
    private fun cleanupOldApks(act: Activity, keepVer: String) {
        runCatching {
            updateDir(act).listFiles()?.forEach { f ->
                if (f.isFile && !f.name.contains(keepVer)) f.delete()
            }
        }
    }

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

        // 已经有下载好的安装包？把主按钮文案换成「立即安装」，
        // 并把授权/下载都跳过 —— 用户不必再等一次 5 分钟。
        val cachedApk = pendingApk(act, info.versionName)
        val btnText = if (cachedApk != null) "立即安装（已下载）" else BTN_UPDATE

        val dlg = AlertDialog.Builder(act)
            .setTitle(TITLE)
            .setView(box)
            .setPositiveButton(btnText, null)
            .setNeutralButton(BTN_SKIP) { _, _ ->
                AppSettings.setSkippedUpdateCode(act, info.versionCode)
                toast(act, "已跳过 ${info.versionName}，之后不再提示（可手动检查）")
            }
            .setNegativeButton(BTN_LATER, null)
            .create()

        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                // 下载前先确认能安装 —— 否则下载完才发现缺权限，
                // 用户去授权回来还得重新下载（实测要等 5 分钟以上）。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && !act.packageManager.canRequestPackageInstalls()
                ) {
                    Diag.log("update", "缺「安装未知应用」权限，先引导授权再下载")
                    showInstallPermissionFirst(act, info)
                    return@setOnClickListener
                }

                // 已下载过：直接装，不联网
                if (cachedApk != null) {
                    Diag.log("update", "复用已下载的 ${cachedApk.name}")
                    dlg.dismiss()
                    installApk(act, cachedApk)
                    return@setOnClickListener
                }

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
    /**
     * 下载前先引导安装权限。
     *
     * 为什么放在下载前：授权要跳系统设置，回来时下载进度就断了。
     * 先授权再下载，用户只等一次。
     */
    private fun showInstallPermissionFirst(act: Activity, info: UpdateChecker.Info) {
        AlertDialog.Builder(act)
            .setTitle("需要先授权安装")
            .setMessage(
                "系统要求先允许「安装未知应用」，否则下载完也装不上。\n\n"
                    + "点「去授权」→ 找到「无障碍守护」→ 打开开关 → 回来点「下载并安装」。\n\n"
                    + "现在就授权的话，等下下载完可以直接装，不用重复下载。"
            )
            .setPositiveButton("去授权") { _, _ ->
                runCatching {
                    act.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                            .setData(Uri.parse("package:" + act.packageName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure {
                    runCatching {
                        act.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.onFailure { toast(act, "无法打开设置页，请手动到「设置 → 应用 → 特殊权限」开启") }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

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
            // 复用已有文件：中断后重下从断点继续，不从头来
            cleanupOldApks(act, ver)
            val dst = apkFor(act, ver)

            // 已完整下载过？直接装，不再联网
            val already = pendingApk(act, ver)
            if (already != null && already.length() >= 1024 * 100) {
                val header = runCatching {
                    already.inputStream().use { ins ->
                        val h = ByteArray(4); ins.read(h)
                        h[0] == 0x50.toByte() && h[1] == 0x4B.toByte()
                    }
                }.getOrDefault(false)
                if (header) {
                    main.post {
                        dlg.dismiss()
                        toast(act, "已有下载好的安装包，直接安装")
                        installApk(act, already)
                    }
                    return@Thread
                }
            }

            val resumeFrom = if (dst.isFile) dst.length() else 0L
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 60000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "a11yguard2-updater")
                    if (resumeFrom > 0) {
                        // 断点续传：告诉服务器从哪个字节开始
                        setRequestProperty("Range", "bytes=$resumeFrom-")
                    }
                }
                conn.connect()
                val code = conn.responseCode
                // 206 = 部分内容（续传成功）；200 = 服务器不支持 Range，从头来
                if (code != 200 && code != 206) throw RuntimeException("HTTP $code")
                val isResume = (code == 206)
                val total = if (isResume) {
                    conn.contentLengthLong + resumeFrom
                } else {
                    conn.contentLengthLong
                }
                var read = if (isResume) resumeFrom else 0L
                if (!isResume && resumeFrom > 0) {
                    // 服务器不支持续传，之前的半截文件作废
                    dst.delete()
                }

                conn.inputStream.use { input ->
                    FileOutputStream(dst, isResume).use { out ->
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
                                    label.text = if (isResume && resumeFrom > 0) {
                                        "继续下载 %.1f / %.1f MB（已省 %.1f MB）".format(
                                            read / 1048576.0, total / 1048576.0,
                                            resumeFrom / 1048576.0
                                        )
                                    } else {
                                        "已下载 %.1f / %.1f MB".format(
                                            read / 1048576.0, total / 1048576.0
                                        )
                                    }
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
    /**
     * 调起系统安装器。
     *
     * ── 为什么"点了没反应"很常见 ──
     *
     * 从 Android 8 开始，用 Intent 装 APK 需要两个前提：
     *   1. 清单里声明 REQUEST_INSTALL_PACKAGES（本应用早期漏了，导致完全调不起来）
     *   2. **用户手动**在系统设置里给本应用开「安装未知应用」权限
     *
     * 第 2 条无法用代码绕过（那是安全边界）。所以这里做三件事：
     *   · 先检查权限，没开就弹窗引导，并直接跳到那个设置页
     *   · 调起失败时区分"没权限"与"其它错误"，给不同提示
     *   · 保留 FileProvider URI 方式（不能直接用 file:// ，Android 7+ 禁止）
     */
    private fun installApk(act: Activity, apk: File) {
        // Android 8+ 需要"安装未知应用"权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pm = act.packageManager
            if (!pm.canRequestPackageInstalls()) {
                Diag.log("update", "缺少「安装未知应用」权限，引导用户开启")
                AlertDialog.Builder(act)
                    .setTitle("需要允许安装应用")
                    .setMessage(
                        "系统要求先授权「安装未知应用」，才能由本应用调起安装器。\n\n"
                            + "点「去开启」后，在列表里找到「无障碍守护」，打开开关，"
                            + "然后回来重新点「下载并安装」。"
                    )
                    .setPositiveButton("去开启") { _, _ ->
                        runCatching {
                            act.startActivity(
                                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                                    .setData(Uri.parse("package:" + act.packageName))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.onFailure {
                            // 部分 ROM 不认带 package 的 URI，退回总列表
                            runCatching {
                                act.startActivity(
                                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }.onFailure { toast(act, "无法打开设置页，请手动到「设置 → 应用 → 特殊权限」里开启") }
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return
            }
        }

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
            Diag.log("update", "install failed: ${it.javaClass.simpleName}: ${it.message}")
            val msg = it.message.orEmpty()
            toast(
                act,
                if (msg.contains("ActivityNotFound", true) || msg.contains("No Activity", true)) {
                    "找不到安装器。请到「设置 → 应用 → 特殊权限 → 安装未知应用」里允许本应用，或手动用文件管理器打开下载好的 APK。"
                } else {
                    "无法调起安装器：" + msg
                }
            )
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
