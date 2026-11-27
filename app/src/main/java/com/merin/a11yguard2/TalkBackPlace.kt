package com.merin.a11yguard2

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * TalkBack 占位（默认关闭）：让“只检查 TalkBack 是否开启”的第三方应用看到期望的信号。
 *
 * 两层信号：
 *  1) 本包内真实声明的空壳无障碍服务 [SHELL_COMP]（label=TalkBack）：会出现在无障碍列表里，
 *     组件是合法的，系统不会把它当脏数据清掉；
 *  2) 仅当设备未安装真 TalkBack 时，额外把 TalkBack 组件串写进 enabled_accessibility_services，
 *     骗过直接读 Settings.Secure 的应用。真 TalkBack 已安装时绝不写它、也绝不动它原有条目。
 *
 * 明确做不到（见报表）：让 PackageManager 认为 com.google.android.marvin.talkback 已安装、
 * 伪造 TalkBack 版本号 / 它自己的 SharedPreferences、伪造读屏播报本身。
 */
object TalkBackPlace {
    const val PREF = "tb_placeholder"
    const val SHELL_COMP =
        "com.merin.a11yguard2/com.google.android.marvin.talkback.TalkBackService"
    const val TB_PKG = "com.google.android.marvin.talkback"
    const val TB_COMP =
        "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"

    fun isOn(ctx: Context): Boolean = SvcRepo.prefs(ctx).getBoolean(PREF, false)

    /** 只有能写系统设置的通道（直写授权 / Shizuku）才允许开启，保证关闭时一定撤得掉。 */
    fun canWrite(ctx: Context): Boolean {
        val order = RouteLogic.order(
            ToggleRouter.mode(ctx),
            ToggleRouter.shizukuStatus(ctx),
            ToggleRouter.tapAvailable(),
            ToggleRouter.secureAvailable(ctx)
        )
        return order.any { it == RouteLogic.ROUTE_SECURE || it == RouteLogic.ROUTE_SHIZUKU }
    }

    fun talkBackInstalled(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getApplicationInfo(TB_PKG, 0) }.isSuccess

    fun raw(ctx: Context): String = runCatching {
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    }.getOrNull().orEmpty()

    fun has(raw: String, comp: String): Boolean {
        val target = SvcRepo.normalize(comp)
        return SecureList.split(raw).any { SvcRepo.normalize(it) == target }
    }

    /**
     * 纯逻辑：算出目标 enabled_accessibility_services 串，其它服务条目原样保留、顺序不变。
     * - 未装真 TalkBack：开 = 壳服务 + 真身组件串；关 = 两者都删（该串不可能是合法用户项）。
     * - 已装真 TalkBack：只增删壳服务；真身组件串原样保留（可能是用户自己开的读屏）。
     */
    fun plan(raw: String, on: Boolean, installed: Boolean): String {
        val shell = SvcRepo.normalize(SHELL_COMP)
        val tb = SvcRepo.normalize(TB_COMP)
        val keep = SecureList.split(raw).filter { c ->
            val n = SvcRepo.normalize(c)
            when {
                n == shell -> false
                n == tb -> installed
                else -> true
            }
        }.toMutableList()
        if (on) {
            keep.add(shell)
            if (!installed) keep.add(tb)
        }
        return keep.joinToString(":")
    }

    fun verify(raw: String, on: Boolean, installed: Boolean): Boolean =
        if (on) has(raw, SHELL_COMP) && (installed || has(raw, TB_COMP))
        else !has(raw, SHELL_COMP) && (installed || !has(raw, TB_COMP))

    /**
     * 用户拨动开关：先写设置再落偏好。
     *
     * 这里不能「验证失败就把开关回滚」：Shizuku 通道的执行是异步的，
     * 命令 exit=0 但系统落库要几秒，验证失败只说明「还没确认」，
     * 不代表「没成功」。一律回滚会让用户看到开关自己弹回去。
     *
     * 所以只要写入动作真的发出去了就记住用户的选择，验证结果写进日志；
     * 之后每次打开 App / 保活启动时 reconcile() 会再对齐一次。
     */
    fun setEnabled(ctx: Context, on: Boolean, reason: String): Boolean {
        if (on && !canWrite(ctx)) {
            Diag.log("tbplace", "on rejected reason=$reason no_write_route")
            return false
        }
        val ok = apply(ctx, on, reason)
        // 开启：写入动作完成就保留用户选择（系统生效可能滞后）
        // 关闭：必须确认真的撤销了，否则下一轮 reconcile 会把残留又打开
        val remember = if (on) true else ok
        SvcRepo.prefs(ctx).edit().putBoolean(PREF, remember).apply()
        return ok
    }

    /** 开机 / 打开 App / 保活启动时对齐一次；关闭状态下顺带清理残留。 */
    fun reconcile(ctx: Context, reason: String): Boolean = apply(ctx, isOn(ctx), reason)

    private fun apply(ctx: Context, on: Boolean, reason: String): Boolean {
        val installed = talkBackInstalled(ctx)
        var shellOk = true
        var fakeOk = true

        // 开启前先把组件启用，否则系统解析不到这个组件；关闭后按“是否仍在开启列表”决定是否禁用
        if (on) {
            setShellComponent(ctx, true)
            // 组件启用状态写入是异步生效的：立即去 toggle 时系统可能还解析不到这个组件，
            // 于是写进去的条目被当成脏数据丢掉，下一秒判定失败又把组件禁用 ——
            // 表现就是「打开一会儿自己关了」。这里给它一点时间真正生效。
            waitComponentReady(ctx, true)
        }
        if (has(raw(ctx), SHELL_COMP) != on) {
            shellOk = ToggleRouter.toggle(ctx, SHELL_COMP, on).ok
        }
        // 真 TalkBack 已安装时完全不碰该组件串，避免把用户真实读屏关掉
        if (!installed) {
            if (has(raw(ctx), TB_COMP) != on) {
                fakeOk = ToggleRouter.toggle(ctx, TB_COMP, on).ok
            }
        }

        // toggle 内部已经做过轮询验证；这里再读一次是为了拿到最终状态写日志。
        // 必须等系统把设置真正落下来，否则立刻读回旧值会误判失败。
        val ok = pollTalkBack(ctx, raw(ctx), on, installed)

        // 关闭且撤销成功 → 禁用组件，系统“已安装的服务”列表里也不留痕迹。
        // 开启状态下绝不能禁用组件：那会把刚加进去的条目直接变成脏数据。
        val stillListed = has(raw(ctx), SHELL_COMP)
        val componentEnabled = on || stillListed
        if (!componentEnabled) setShellComponent(ctx, false)
        Diag.log(
            "tbplace",
            "${if (on) "on" else "off"} reason=$reason shell=$shellOk fake=$fakeOk " +
                "realTb=$installed verify=$ok component=${if (componentEnabled) "enabled" else "disabled"} " +
                "list=${raw(ctx)}"
        )
        return ok
    }

    /**
     * 等组件启用状态真正生效（最多 ~1s）。
     * setComponentEnabledSetting 只是提交请求，系统侧解析要一点时间；
     * 紧接着去写无障碍列表时，组件若还没被识别，写进去的条目会被当脏数据清掉。
     */
    private fun waitComponentReady(ctx: Context, want: Boolean) {
        val cn = ComponentName(ctx, com.google.android.marvin.talkback.TalkBackService::class.java)
        val end = android.os.SystemClock.uptimeMillis() + 1_000L
        while (android.os.SystemClock.uptimeMillis() < end) {
            val cur = runCatching { ctx.packageManager.getComponentEnabledSetting(cn) }.getOrNull()
            val ready = when (cur) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> want
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> !want
                // DEFAULT：按清单声明，通常是启用
                else -> want
            }
            if (ready) return
            android.os.SystemClock.sleep(80)
        }
    }

    /**
     * 轮询等待 enabled_accessibility_services 里的目标条目达到期望状态。
     *
     * 原来直接读一次就判定，系统还没落盘时必然读到旧值 → verify=false →
     * 关闭分支把组件禁用 → 刚开的占位立刻失效，表现为「打开一会儿自己关了」。
     */
    /**
     * 轮询等待 enabled_accessibility_services 里的目标条目达到期望状态。
     *
     * 原来直接读一次就判定，系统还没落盘时必然读到旧值 → verify=false →
     * 关闭分支把组件禁用 → 刚开的占位立刻失效，表现为「打开一会儿自己关了」。
     *
     * Shizuku 通道是「起一个 shell 去 settings put」，命令返回 exit=0 只代表
     * 命令发出去了，系统把值落库并重新解析无障碍服务还要额外时间，实测会超过 3 秒。
     * 所以这里给到 8 秒，期间每 200ms 重读一次。
     */
    private fun pollTalkBack(ctx: Context, rawInit: String, on: Boolean, installed: Boolean): Boolean {
        if (verify(rawInit, on, installed)) return true
        val end = android.os.SystemClock.uptimeMillis() + 8_000L
        while (android.os.SystemClock.uptimeMillis() < end) {
            if (verify(raw(ctx), on, installed)) return true
            android.os.SystemClock.sleep(200)
        }
        return verify(raw(ctx), on, installed)
    }

    /** 组件默认启用（清单声明），关闭占位时禁用，避免在系统无障碍设置的“已安装服务”里留下一条空壳。 */
    private fun setShellComponent(ctx: Context, enabled: Boolean) = runCatching {
        val cn = ComponentName(ctx, com.google.android.marvin.talkback.TalkBackService::class.java)
        val cur = ctx.packageManager.getComponentEnabledSetting(cn)
        val enabledNow = cur == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            cur == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        // 状态一致就不重复写：每次写组件状态都会触发包变更，可能打断无障碍绑定
        if (enabledNow == enabled) return@runCatching
        ctx.packageManager.setComponentEnabledSetting(
            cn,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
        Diag.log("tbplace", "component ${if (enabled) "enabled" else "disabled"}")
    }.onFailure { Diag.log("tbplace", "component switch failed: ${it.message}") }
}
