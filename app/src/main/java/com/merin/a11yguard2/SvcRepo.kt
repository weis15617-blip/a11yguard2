package com.merin.a11yguard2

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.util.LruCache
import android.view.accessibility.AccessibilityManager

data class SvcRow(
    val comp: String,
    val label: String,
    val pkg: String,
    val enabled: Boolean,
    val bound: Boolean,
    val icon: Drawable? = null
)

/** 只读枚举系统无障碍服务；bound 来自 AccessibilityManager，crashed 系统不对外开放（不可得）。 */
object SvcRepo {

    /** 按组件缓存原始应用图标，滚动/重建不重复解码。 */
    private val iconCache = LruCache<String, Drawable>(64)

    fun clearIconCache() = iconCache.evictAll()

    private fun iconFor(ctx: Context, comp: String, pkg: String, fromResolve: Drawable?): Drawable? {
        iconCache.get(comp)?.let { return it }
        val d = fromResolve ?: runCatching { ctx.packageManager.getApplicationIcon(pkg) }.getOrNull()
        if (d != null) {
            iconCache.put(comp, d)
            Diag.log("icon", "load comp=$comp pkg=$pkg type=${d.javaClass.simpleName}")
        } else {
            Diag.log("icon", "fail comp=$comp pkg=$pkg 无图标，用首字占位")
        }
        return d
    }

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("a11yguard2", Context.MODE_PRIVATE)

    fun normalize(comp: String): String {
        val t = comp.trim()
        if (t.isEmpty() || !t.contains('/')) return t
        val pkg = t.substringBefore('/')
        var cls = t.substringAfter('/')
        if (cls.startsWith('.')) cls = pkg + cls
        return "$pkg/$cls"
    }

    fun enabledComps(ctx: Context): Set<String> {
        val raw = runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull().orEmpty()
        return raw.split(':').map { normalize(it) }.filter { it.isNotEmpty() }.toSet()
    }

    fun isEnabled(ctx: Context, comp: String): Boolean = enabledComps(ctx).contains(normalize(comp))

    fun all(ctx: Context, userOnly: Boolean = AppSettings.onlyUserApps(ctx)): List<SvcRow> {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val installed = runCatching { am.getInstalledAccessibilityServiceList() }.getOrElse { emptyList() }
        val bound = runCatching {
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .mapNotNull { it.id }
                .map { normalize(it) }
                .toSet()
        }.getOrElse { emptySet() }
        val enabled = enabledComps(ctx)
        val pm = ctx.packageManager
        val seen = LinkedHashMap<String, SvcRow>()

        fun put(comp: String, label: String, pkg: String, resolveIcon: Drawable?) {
            val key = normalize(comp)
            if (key.isEmpty() || !key.contains('/')) return
            if (seen.containsKey(key)) return
            val fixed = label.ifBlank { appLabel(ctx, pkg) }
            seen[key] = SvcRow(
                key, fixed, pkg, enabled.contains(key), bound.contains(key),
                iconFor(ctx, key, pkg, resolveIcon)
            )
        }

        // 1) 首选数据源：AccessibilityManager（自带 resolveInfo，标签与服务元数据最准）
        installed.forEach { info ->
            val si = info.resolveInfo.serviceInfo
            val label = runCatching { info.resolveInfo.loadLabel(pm).toString() }
                .getOrElse { si.name }
            val icon = runCatching { info.resolveInfo.loadIcon(pm) }.getOrNull()
                ?: runCatching { si.loadIcon(pm) }.getOrNull()
            put(si.packageName + "/" + si.name, label, si.packageName, icon)
        }
        // 2) 兜底补充：PackageManager 按 action 查，补上 AccessibilityManager 偶发遗漏的组件
        val queried = runCatching {
            pm.queryIntentServices(
                Intent("android.accessibilityservice.AccessibilityService"),
                PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS
            )
        }.getOrElse { emptyList() }
        queried.forEach { ri ->
            val si = ri.serviceInfo ?: return@forEach
            if (si.permission != Manifest.permission.BIND_ACCESSIBILITY_SERVICE) return@forEach
            val label = runCatching { ri.loadLabel(pm).toString() }.getOrElse { si.name }
            val icon = runCatching { ri.loadIcon(pm) }.getOrNull()
                ?: runCatching { si.loadIcon(pm) }.getOrNull()
            put(si.packageName + "/" + si.name, label, si.packageName, icon)
        }

        Diag.log(
            "svcrepo",
            "installed=${installed.size} queried=${queried.size} merged=${seen.size} " +
                "comps=[${seen.keys.joinToString("|")}]"
        )

        return seen.values
            // 占位空壳只给第三方看，不在本 App 的服务列表/触发目标里出现，避免被加锁或置顶
            .filter { it.comp != normalize(TalkBackPlace.SHELL_COMP) }
            .filter { !userOnly || !isSystemApp(ctx, it.pkg) }
            .sortedWith(compareByDescending<SvcRow> { it.enabled }.thenBy { it.label })
    }

    /** 服务自身 label 为空时退回应用名，再退回包名，避免“有服务但被当成无标签丢掉”。 */
    private fun appLabel(ctx: Context, pkg: String): String = runCatching {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        ctx.packageManager.getApplicationLabel(ai).toString()
    }.getOrNull()?.ifBlank { pkg } ?: pkg

    /** FLAG_SYSTEM 与 FLAG_UPDATED_SYSTEM_APP 分开判断：更新过的系统应用按用户应用看待。 */
    fun isSystemApp(ctx: Context, pkg: String): Boolean = runCatching {
        val flags = ctx.packageManager.getApplicationInfo(pkg, 0).flags
        val system = flags and ApplicationInfo.FLAG_SYSTEM != 0
        val updatedSystem = flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        system && !updatedSystem
    }.getOrDefault(false)

    fun labelOf(ctx: Context, comp: String): String =
        if (normalize(comp) == normalize(TalkBackPlace.SHELL_COMP)) {
            ctx.getString(R.string.tb_placeholder_label)
        } else {
            all(ctx).firstOrNull { normalize(it.comp) == normalize(comp) }?.label ?: comp
        }
}
