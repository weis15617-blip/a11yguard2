package com.merin.a11yguard2

import android.content.Context
import android.provider.Settings

/**
 * root 权限执行层。
 *
 * ── 为什么需要它 ──
 *
 * 无障碍服务的「自动切换」在 Android 上没有公开 API，可行路径只有这几条：
 *
 *   | 方式 | 前提 | 适用人群 |
 *   |------|------|---------|
 *   | WRITE_SECURE_SETTINGS 直写 | 需 adb 授权一次 | 会用电脑的人 |
 *   | Shizuku | 需装 Shizuku，且每次重启后要重新启动它 | 已装 Shizuku 的人 |
 *   | 无障碍点按 | 需开无障碍，且要点进系统设置界面 | 通用，但慢且需看得见屏幕 |
 *   | **root（本类）** | **手机已 root 且 su 已授权** | **已 root 的人** |
 *
 * 已 root 的设备上 root 最直接可靠：不需要 adb、不需要装额外应用、
 * 不受「Shizuku 重启后要重新启动」的影响，直接用 uid 0 执行
 * `settings put secure enabled_accessibility_services ...`。
 *
 * ── 安全考量（重要）──
 *
 * 1. **只在用户明确选择 root 方式时才调用 su**，不放进 auto 探测链。
 *    探测 su 本身会触发 root 管理器弹窗，属于打扰用户。
 * 2. 只执行固定形态的两类命令：读 id、写 enabled_accessibility_services（及配套的
 *    accessibility_enabled 标志）。不接受任意命令，避免变成「通用 root 执行器」。
 * 3. 写入值做白名单校验：只允许 `包名/类名` 用冒号分隔，禁止空格、分号、引号，
 *    防止拼接注入到 shell。
 */
class RootProvider : ToggleProvider {

    override val route: String = RouteLogic.ROUTE_ROOT

    /**
     * 探测 su 是否可用且已授权。
     *
     * 可能触发 root 管理器弹窗，所以由调用方决定何时调用
     * （用户选择 root 方式时、或点「检测」按钮时）。
     */
    override fun status(ctx: Context): String {
        return try {
            val out = exec("id -u")
            when {
                out == null -> RouteLogic.ROOT_NO_SU
                out.trim() == "0" -> RouteLogic.ROOT_AVAILABLE
                else -> RouteLogic.ROOT_DENIED
            }
        } catch (e: Exception) {
            Diag.log("root", "status failed: ${e.message}")
            RouteLogic.ROOT_UNKNOWN
        }
    }

    override fun toggle(ctx: Context, comp: String, want: Boolean): ToggleResult {
        val target = SvcRepo.normalize(comp)

        // 先读当前值（用系统 API 读，不需要 root）
        val raw = readEnabled(ctx) ?: ""
        val items = SecureList.split(raw)
        val present = items.any { SvcRepo.normalize(it) == target }

        if (want && present) {
            return ToggleResult(route, target, true, false, true, "already-on")
        }
        if (!want && !present) {
            return ToggleResult(route, target, true, false, true, "already-off")
        }

        val newList = SecureList.apply(raw, comp, want)
        if (!isSafeServiceList(newList)) {
            return ToggleResult(route, target, true, false, false, "值含非法字符，已拒绝写入")
        }

        val putOk = writeEnabled(newList)
        if (!putOk) {
            return ToggleResult(route, target, true, false, false, "su 执行失败")
        }
        // 配套把总开关置位：全部关闭时置 0，否则 1
        writeEnabledFlag(newList.isNotEmpty())

        val ok = pollVerify(ctx, target, want, 3000)
        Diag.log("root", "put=$putOk verify=$ok list=${newList.take(100)}")
        return ToggleResult(route, target, true, putOk, ok, if (ok) null else "写入后回读未变化")
    }

    /** 读当前 enabled_accessibility_services（普通 API，不需 root）。 */
    private fun readEnabled(ctx: Context): String? = runCatching {
        Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
    }.getOrNull().orEmpty()

    /** 轮询确认写入真的生效（系统异步应用，需要等一下）。 */
    private fun pollVerify(ctx: Context, target: String, want: Boolean, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val cur = readEnabled(ctx) ?: ""
            val nowPresent = SecureList.split(cur).any { SvcRepo.normalize(it) == target }
            if (nowPresent == want) return true
            try {
                Thread.sleep(80)
            } catch (e: InterruptedException) {
                return false
            }
        }
        return false
    }

    // ---------- su 执行 ----------

    fun writeEnabled(value: String): Boolean {
        if (!isSafeServiceList(value)) {
            Diag.log("root", "reject unsafe value: ${value.take(60)}")
            return false
        }
        val cmd = "settings put secure enabled_accessibility_services \"$value\""
        val out = exec(cmd)
        if (out == null) {
            Diag.log("root", "write failed")
            return false
        }
        return true
    }

    fun writeEnabledFlag(on: Boolean): Boolean {
        val cmd = "settings put secure accessibility_enabled ${if (on) 1 else 0}"
        return exec(cmd) != null
    }

    /**
     * 白名单校验：允许空串；否则只允许字母数字与 `. _ / : -`。
     *
     * 这是防注入的关键 —— su 会把字符串交给 shell，
     * 任何 `; | & $ \` " ' 换行` 都可能导致命令拼接。
     */
    fun isSafeServiceList(value: String): Boolean {
        if (value.isEmpty()) return true
        if (value.length > 4000) return false
        return value.all { c ->
            c.isLetterOrDigit() || c == '.' || c == '_' || c == '/' || c == ':' || c == '-'
        }
    }

    /**
     * 执行一条 shell 命令（经 su），返回 stdout；失败返回 null。
     *
     * 用 `su -c` 而非 `su` + stdin：不同 su 实现对 stdin 处理不一致。
     */
    private fun exec(cmd: String): String? {
        return try {
            val p = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().use { it.readText() }
            val code = p.waitFor()
            if (code == 0) out else {
                Diag.log("root", "cmd exit=$code out=${out.take(120)}")
                null
            }
        } catch (e: Exception) {
            // su 不存在会抛 IOException
            null
        }
    }
}
