package com.merin.a11yguard2

import android.content.Context

object ToggleRouter {

    fun mode(ctx: Context): String =
        SvcRepo.prefs(ctx).getString("toggle_mode", RouteLogic.MODE_AUTO) ?: RouteLogic.MODE_AUTO

    /**
     * 一次性迁移：上一版设备上 toggle_mode 被设为 shizuku（Shizuku 未运行 → 点按钮必失败）。
     * 新版本首次启动统一改为「自动」，之后用户手动选择不再被覆盖。
     */
    fun ensureDefaultMode(ctx: Context) {
        val sp = SvcRepo.prefs(ctx)
        if (sp.getBoolean("mode_migrated_v2", false)) return
        val before = sp.getString("toggle_mode", RouteLogic.MODE_AUTO)
        sp.edit()
            .putString("toggle_mode", RouteLogic.MODE_AUTO)
            .putBoolean("mode_migrated_v2", true)
            .apply()
        Diag.log("setting", "toggle_mode migrate $before -> ${RouteLogic.MODE_AUTO}")
    }

    fun shizukuStatus(ctx: Context): String = ShizukuProvider().status(ctx)

    fun secureStatus(ctx: Context): String = SecureSettingsProvider().status(ctx)

    fun secureAvailable(ctx: Context): Boolean =
        secureStatus(ctx) == RouteLogic.SECURE_AVAILABLE

    /**
     * root 状态**缓存**。
     *
     * 为什么不每次现查：`su -c id -u` 在未 root 的设备上会走一遍失败路径，
     * 在已 root 设备上可能触发 root 管理器弹窗。每次刷新界面都探测是不可接受的。
     *
     * 策略：
     * - 用户显式选择 root 方式、或点「检测 root」按钮 → 强制现查并写缓存
     * - 其余情况用缓存（默认 unknown，即不参与 auto 路由）
     */
    @Volatile
    private var rootCache: String = RouteLogic.ROOT_UNKNOWN

    fun rootStatusCached(): String = rootCache

    fun probeRoot(ctx: Context): String {
        val s = RootProvider().status(ctx)
        rootCache = s
        Diag.log("root", "probe -> $s")
        return s
    }

    fun tapAvailable(): Boolean = TapAgentService.instance != null

    fun providerFor(route: String): ToggleProvider = when (route) {
        RouteLogic.ROUTE_SECURE -> SecureSettingsProvider()
        RouteLogic.ROUTE_ROOT -> RootProvider()
        RouteLogic.ROUTE_SHIZUKU -> ShizukuProvider()
        else -> TapAgentProvider()
    }

    fun describe(ctx: Context): String =
        RouteLogic.describe(
            mode(ctx), shizukuStatus(ctx), tapAvailable(), secureAvailable(ctx), rootCache
        )

    fun toggle(ctx: Context, comp: String, want: Boolean): ToggleResult {
        // 加锁 = 硬拒绝关闭：任何入口（UI / 自检 / 复活）都不能走到写 secure settings 这一步
        if (LockLogic.denyClose(comp, want, AppSettings.lockedComps(ctx))) {
            val res = ToggleResult(
                "locked", comp, false, false, false,
                LockLogic.DENY_MSG
            )
            Diag.log("lock", "deny close target=${SvcRepo.normalize(comp)} via=router")
            return res
        }
        val order = RouteLogic.order(
            mode(ctx), shizukuStatus(ctx), tapAvailable(), secureAvailable(ctx), rootCache
        )
        if (order.isEmpty()) {
            val res = ToggleResult(
                "none", comp, false, false, false,
                "无可用执行方式：请先完成 adb 授权、授予 root、或开启无障碍权限"
            )
            Diag.log("route", res.logLine())
            return res
        }
        var last: ToggleResult? = null
        for (route in order) {
            val res = providerFor(route).toggle(ctx, comp, want)
            Diag.log("route", "try ${res.logLine()}")
            if (res.ok) return res
            last = res
        }
        return last ?: ToggleResult("none", comp, false, false, false, "无可用执行方式")
    }
}
