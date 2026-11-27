package com.merin.a11yguard2

/**
 * 路由选择与回落（纯逻辑，可单测）。
 *
 * 可用执行方式（按可靠性排序）：
 * - secure：WRITE_SECURE_SETTINGS 直写。adb 授权一次后随包持久化，重启仍有效。
 * - root  ：已 root 设备用 su 直写。无需 adb、无需额外应用，重启仍有效。
 * - shizuku：需装 Shizuku，且每次重启后要重新启动它。
 * - tap  ：无障碍点按，需要看得见屏幕去点系统设置界面。
 *
 * 模式：
 * - auto    ：secure → root → shizuku → tap 逐级降级
 * - secure  ：只用直写
 * - root    ：只用 root
 * - shizuku ：只用 Shizuku
 * - tap     ：只用点按
 *
 * 注意 root 不放进 auto 的默认首位：探测 su 会触发 root 管理器弹窗，
 * 未 root 的设备上每次检查都弹一次是不可接受的。所以 auto 里 root 排在
 * secure 之后、且只有用户已经显式授权过 root（状态为 available）才参与。
 */
object RouteLogic {
    const val MODE_AUTO = "auto"
    const val MODE_SECURE = "secure"
    const val MODE_ROOT = "root"
    const val MODE_TAP = "tap"
    const val MODE_SHIZUKU = "shizuku"

    const val ROUTE_SECURE = "secure"
    const val ROUTE_ROOT = "root"
    const val ROUTE_TAP = "tap"
    const val ROUTE_SHIZUKU = "shizuku"

    const val SECURE_AVAILABLE = "available"
    const val SECURE_NO_PERMISSION = "no_permission"

    const val ROOT_AVAILABLE = "available"
    const val ROOT_NO_SU = "no_su"
    const val ROOT_DENIED = "denied"
    const val ROOT_UNKNOWN = "unknown"

    const val SHIZUKU_AVAILABLE = "available"
    const val SHIZUKU_NOT_INSTALLED = "not_installed"
    const val SHIZUKU_NOT_RUNNING = "not_running"
    const val SHIZUKU_NO_PERMISSION = "no_permission"
    const val SHIZUKU_UNSUPPORTED = "unsupported"

    fun order(
        mode: String,
        shizuku: String,
        tapAvailable: Boolean,
        secureAvailable: Boolean = false,
        root: String = ROOT_UNKNOWN
    ): List<String> {
        val list = ArrayList<String>(4)
        val rootOk = root == ROOT_AVAILABLE
        when (mode) {
            MODE_SECURE -> if (secureAvailable) list.add(ROUTE_SECURE)
            MODE_ROOT -> if (rootOk) list.add(ROUTE_ROOT)
            MODE_TAP -> if (tapAvailable) list.add(ROUTE_TAP)
            MODE_SHIZUKU -> if (shizuku == SHIZUKU_AVAILABLE) list.add(ROUTE_SHIZUKU)
            else -> {
                // auto：直写优先（不弹窗），root 次之（已授权才参与），再 Shizuku，最后点按
                if (secureAvailable) list.add(ROUTE_SECURE)
                if (rootOk) list.add(ROUTE_ROOT)
                if (shizuku == SHIZUKU_AVAILABLE) list.add(ROUTE_SHIZUKU)
                if (tapAvailable) list.add(ROUTE_TAP)
            }
        }
        return list
    }

    fun describe(
        mode: String,
        shizuku: String,
        tapAvailable: Boolean,
        secureAvailable: Boolean = false,
        root: String = ROOT_UNKNOWN
    ): String {
        val secureText = if (secureAvailable) "直写已授权，重启仍有效" else "直写未授权（可用 adb 一次授权）"
        val rootText = when (root) {
            ROOT_AVAILABLE -> "root 可用"
            ROOT_NO_SU -> "root 不可用（未检测到 su）"
            ROOT_DENIED -> "root 未授权（请在 root 管理器里允许）"
            else -> "root 未检测"
        }
        val shizukuText = when (shizuku) {
            SHIZUKU_AVAILABLE -> "Shizuku 可用"
            SHIZUKU_NOT_INSTALLED -> "Shizuku 未安装"
            SHIZUKU_NOT_RUNNING -> "Shizuku 未运行"
            SHIZUKU_NO_PERMISSION -> "Shizuku 未授权"
            else -> "Shizuku 不可用"
        }
        val tapText = if (tapAvailable) "点按可用" else "点按不可用（无障碍授权未开）"
        val now = when (mode) {
            MODE_SECURE -> if (secureAvailable) "直写" else "无（直写未授权）"
            MODE_ROOT -> if (root == ROOT_AVAILABLE) "root" else "无（$rootText）"
            MODE_TAP -> if (tapAvailable) "点按" else "无（需先授予无障碍权限）"
            MODE_SHIZUKU -> if (shizuku == SHIZUKU_AVAILABLE) "Shizuku" else "无（$shizukuText）"
            else -> when {
                secureAvailable -> "直写"
                root == ROOT_AVAILABLE -> "root"
                shizuku == SHIZUKU_AVAILABLE -> "Shizuku"
                tapAvailable -> "点按"
                else -> "无（直写/root/Shizuku/点按均不可用）"
            }
        }
        return "当前方式：$now（$secureText；$rootText；$tapText；$shizukuText）"
    }
}
