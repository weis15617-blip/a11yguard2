package com.merin.a11yguard2

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * 推荐执行层：WRITE_SECURE_SETTINGS 直写 Settings.Secure。
 *
 * 该权限是 signature|privileged 权限，普通安装拿不到，但可以用一次 ADB 授权：
 *   adb shell pm grant com.merin.a11yguard2 android.permission.WRITE_SECURE_SETTINGS
 * 授权结果由系统按包持久化：重启手机仍有效，不需要 Shizuku 常驻，也不需要本 App 的无障碍服务在运行。
 * 只有卸载重装（包被移除）后才需要重新授权。本 App 不绕过权限、不静默提权，只检测并使用用户显式授予的权限。
 */
class SecureSettingsProvider : ToggleProvider {
    override val route: String = RouteLogic.ROUTE_SECURE

    override fun status(ctx: Context): String =
        if (hasPermission(ctx)) RouteLogic.SECURE_AVAILABLE else RouteLogic.SECURE_NO_PERMISSION

    override fun toggle(ctx: Context, comp: String, want: Boolean): ToggleResult {
        if (!hasPermission(ctx)) {
            return ToggleResult(route, comp, false, false, false, "WRITE_SECURE_SETTINGS 未授权")
        }
        val raw = runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull().orEmpty()
        val newList = SecureList.apply(raw, comp, want)
        return try {
            val putOk = Settings.Secure.putString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                newList
            )
            Settings.Secure.putString(
                ctx.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                if (newList.isEmpty()) "0" else "1"
            )
            val ok = pollVerify(ctx, comp, want, 3000)
            Diag.log("secure", "put=$putOk verify=$ok list=$newList")
            ToggleResult(route, comp, true, putOk, ok, if (ok) null else "写入后回读未变化")
        } catch (t: Throwable) {
            ToggleResult(route, comp, false, false, false, "直写失败：" + (t.message ?: t.javaClass.simpleName))
        }
    }

    companion object {
        const val PERMISSION = Manifest.permission.WRITE_SECURE_SETTINGS
        const val GRANT_COMMAND =
            "adb shell pm grant com.merin.a11yguard2 android.permission.WRITE_SECURE_SETTINGS"

        fun hasPermission(ctx: Context): Boolean = runCatching {
            ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }
}

/**
 * enabled_accessibility_services 的增删逻辑（纯逻辑，可单测）。
 * 保留其它服务的原始写法，只增删目标组件，避免影响 TalkBack / TalkMan 等既有服务。
 */
object SecureList {
    fun split(raw: String): List<String> =
        raw.split(':').map { it.trim() }.filter { it.isNotEmpty() }

    fun apply(raw: String, comp: String, want: Boolean): String {
        val items = split(raw).toMutableList()
        val target = SvcRepo.normalize(comp)
        val present = items.any { SvcRepo.normalize(it) == target }
        if (want && !present) items.add(target)
        if (!want) items.removeAll { SvcRepo.normalize(it) == target }
        return items.joinToString(":")
    }
}
