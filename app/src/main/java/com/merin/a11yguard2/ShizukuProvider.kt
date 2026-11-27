package com.merin.a11yguard2

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import rikka.shizuku.Shizuku

/**
 * 可选增强：Shizuku 可用时直写 enabled_accessibility_services。
 * 非 root 设备重启后 Shizuku 需要重新用 ADB/无线调试启动，因此本路径只是“更快更准”，不是必需。
 */
class ShizukuProvider : ToggleProvider {
    override val route: String = RouteLogic.ROUTE_SHIZUKU

    override fun status(ctx: Context): String = try {
        when {
            // pingBinder 为 false 有两种完全不同的原因：服务端没起，或本机根本没装 Shizuku 管理端
            !Shizuku.pingBinder() -> if (installed(ctx)) {
                RouteLogic.SHIZUKU_NOT_RUNNING
            } else {
                RouteLogic.SHIZUKU_NOT_INSTALLED
            }
            Shizuku.isPreV11() -> RouteLogic.SHIZUKU_NO_PERMISSION
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> RouteLogic.SHIZUKU_NO_PERMISSION
            else -> RouteLogic.SHIZUKU_AVAILABLE
        }
    } catch (t: Throwable) {
        RouteLogic.SHIZUKU_UNSUPPORTED
    }

    private fun installed(ctx: Context): Boolean = managerPackages(ctx).isNotEmpty()

    companion object {
        /** 官方 Shizuku 管理端包名（不是权限前缀 moe.shizuku.manager）。 */
        const val MANAGER_PKG = "moe.shizuku.privileged.api"

        /** 已知的改包名分支（如 Shevery），仅用于安装检测与拉起，不参与权限判断。 */
        val KNOWN_MANAGER_PKGS = listOf(MANAGER_PKG, "com.hamondev.shevery")

        private const val ACTION_REQUEST_AUTH =
            "moe.shizuku.privileged.api.intent.action.REQUEST_AUTHORIZATION"
        private const val ACTION_REQUEST_AUTH_ALT =
            "moe.shizuku.manager.intent.action.REQUEST_AUTHORIZATION"

        /** 本机实际存在的管理端包名（官方 + 改包名分支），按优先级排序。 */
        fun managerPackages(ctx: Context): List<String> {
            val pm = ctx.packageManager
            val found = LinkedHashSet<String>()
            KNOWN_MANAGER_PKGS.forEach { pkg ->
                if (runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess) found.add(pkg)
            }
            // 兜底：任何响应 Shizuku 授权 Intent 的包（不在已知列表里的改包名分支）
            listOf(ACTION_REQUEST_AUTH, ACTION_REQUEST_AUTH_ALT).forEach { action ->
                runCatching {
                    pm.queryIntentActivities(android.content.Intent(action), 0)
                }.getOrDefault(emptyList()).forEach { found.add(it.activityInfo.packageName) }
            }
            return found.toList()
        }
    }

    override fun toggle(ctx: Context, comp: String, want: Boolean): ToggleResult {
        val st = status(ctx)
        if (st != RouteLogic.SHIZUKU_AVAILABLE) {
            return ToggleResult(route, comp, false, false, false, "shizuku=" + st)
        }
        val raw = runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull().orEmpty()
        val items = raw.split(':').filter { it.isNotBlank() }.toMutableList()
        val target = SvcRepo.normalize(comp)
        val present = items.any { SvcRepo.normalize(it) == target }
        if (want && !present) items.add(target)
        if (!want) items.removeAll { SvcRepo.normalize(it) == target }
        val newList = items.joinToString(":")

        val enabled = if (items.isEmpty()) 0 else 1

        // 关键：先「移除」再「加回」，才能清掉系统的崩溃黑名单。

        // Android 的 AccessibilityManagerService 维护 mCrashedServices，

        // 服务一旦崩溃过，即使设置里仍启用、即使重新 put，系统也不会再绑定它，

        // 必须观察到一次「禁用 -> 启用」的状态跳变。

        // 实测：不做的话 Crashed services 一直挂着 3 个服务、全都绑不上；

        // 做一次移除再添加，Crashed services 立刻变空、服务正常绑定。

        val withoutTarget = items.filter { SvcRepo.normalize(it) != target }.joinToString(":")

        val cmd = if (want) {

            "settings put secure enabled_accessibility_services '" + withoutTarget + "'; sleep 0.25; " +

                "settings put secure enabled_accessibility_services '" + newList + "'; " +

                "settings put secure accessibility_enabled 1"

        } else {

            "settings put secure enabled_accessibility_services '" + newList + "'; " +

                "settings put secure accessibility_enabled " + enabled

        }
        return try {
            val code = runShell(cmd)
            // Shizuku 起 shell 执行 settings put 是异步的，而且系统重新绑定无障碍服务
            // 是个几百毫秒到数秒的过程。这段时间里服务可能短暂处于 crashing/binding，
            // 于是回读判定为失败 —— 但设置其实已经写进去了。
            //
            // 所以判定改成：命令 exit=0 就认为「写入成功」。回读结果只记进日志，
            // 不再决定成败。否则会出现「一直判失败 → 一直重试 → 系统反复重绑 →
            // 服务真被标记 crashed」的恶性循环（实测 30 分钟刷了 358 次失败）。
            // 判定必须以「服务真的绑上了」为准，不能只看 exit=0。

            // 之前的版本为了止住重试风暴改成只看 exit=0，结果是：

            // 设置写进去了、但服务在系统的崩溃黑名单里没绑上，模块却记成成功，

            // 于是不再重试 —— 表面「成功 559 次」，实际一个服务都没工作。

            val verified = pollVerify(ctx, comp, want, 8000)

            // 关闭操作：exit=0 即可（读屏不一定有绑定态可查）

            // 开启操作：必须回读确认已启用，否则如实标记失败，交给退避机制重试

            val ok = code == 0 && (!want || verified)

            Diag.log("shizuku", "exit=" + code + " verify=" + verified + " → 判定=" + ok)

            ToggleResult(route, comp, true, true, ok, "exit=" + code + " verify=" + verified)
        } catch (t: Throwable) {
            ToggleResult(route, comp, false, false, false, "shizuku 执行失败：" + (t.message ?: t.javaClass.simpleName))
        }
    }

    /** Shizuku.newProcess 在 13.1.5 里不是 public API，这里用反射调用。 */
    private fun runShell(cmd: String): Int {
        val clazz = Shizuku::class.java
        val strArr = Array<String>::class.java
        val m = clazz.getDeclaredMethod("newProcess", strArr, strArr, String::class.java)
        m.isAccessible = true
        val proc = m.invoke(null, arrayOf("sh", "-c", cmd), null, null)
        val wf = proc.javaClass.getMethod("waitFor")
        return wf.invoke(proc) as Int
    }
}