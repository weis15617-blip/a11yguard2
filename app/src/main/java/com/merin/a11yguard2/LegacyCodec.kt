package com.merin.a11yguard2

/**
 * 与旧版 com.merin.a11ytool 的 shared_prefs 完全兼容：
 * - locked_comps：`|` 分隔，元素形如 `packageName/fully.qualified.Service`
 * - pinned_pkgs：`|` 分隔，元素为包名
 *
 * 旧数据里的“残缺项”（没有 `/` 或类名不含 `.`）按既定策略直接丢弃。
 */
object LegacyCodec {
    const val SEP = "|"

    fun split(raw: String?): List<String> =
        raw.orEmpty().split(SEP).map { it.trim() }.filter { it.isNotEmpty() }

    fun join(items: List<String>): String = items.distinct().joinToString(SEP)

    fun normalizeComp(raw: String): String? {
        val t = raw.trim()
        if (!t.contains('/')) return null
        val pkg = t.substringBefore('/').trim()
        var cls = t.substringAfter('/').trim()
        if (pkg.isEmpty() || cls.isEmpty()) return null
        if (!cls.contains('.')) return null
        if (cls.startsWith('.')) cls = pkg + cls
        return "$pkg/$cls"
    }

    fun normalizeComps(raw: String?): List<String> =
        split(raw).mapNotNull { normalizeComp(it) }.distinct()

    fun normalizePkgs(raw: String?): List<String> =
        split(raw).filter { it.contains('.') && !it.contains('/') }.distinct()

    fun parseXml(text: String): LegacyData {
        val locked = Regex("<string\\s+name=\"locked_comps\">([^<]*)</string>")
            .find(text)?.groupValues?.get(1).orEmpty()
        val pinned = Regex("<string\\s+name=\"pinned_pkgs\">([^<]*)</string>")
            .find(text)?.groupValues?.get(1).orEmpty()
        val pkgLegacy = Regex("<string\\s+name=\"locked_packages\">([^<]*)</string>")
            .find(text)?.groupValues?.get(1).orEmpty()
        val mergedLocked = normalizeComps(unescape(locked))
        val mergedPkgs = (normalizePkgs(unescape(pinned)) + normalizePkgs(unescape(pkgLegacy))).distinct()
        return LegacyData(mergedLocked, mergedPkgs)
    }

    fun buildXml(locked: List<String>, pinned: List<String>): String {
        val l = join(locked.mapNotNull { normalizeComp(it) })
        val p = join(pinned.map { it.trim() }.filter { it.isNotEmpty() })
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?>\n")
            append("<map>\n")
            append("    <string name=\"locked_comps\">").append(escape(l)).append("</string>\n")
            append("    <string name=\"pinned_pkgs\">").append(escape(p)).append("</string>\n")
            append("</map>\n")
        }
    }

    fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&#1;", "\u0001").replace("&#10;", "\n")
        .replace("&amp;", "&")
}

data class LegacyData(val locked: List<String>, val pinned: List<String>)
