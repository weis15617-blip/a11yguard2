package com.merin.a11yguard2

import android.view.accessibility.AccessibilityEvent

/**
 * 从第三方语音助手界面上读文字，匹配关键词后执行动作。
 *
 * ══════════════════════════════════════════════════════════════════
 * 为什么这样做（技术边界）
 * ══════════════════════════════════════════════════════════════════
 *
 * Android **没有 API** 能把其他 App 的语音识别结果交给第三方 ——
 * 那是安全边界，任何应用都无法绕过。
 *
 * 但语音助手有个绕不开的特征：**它必须把识别结果显示给用户看**。
 * 本应用是无障碍服务，能读取界面上出现的文字。于是：
 *
 *     用户说「关闭解说」
 *       → 助手识别 → 结果文字出现在界面
 *       → 本服务读到 → 匹配关键词 → 执行切换
 *
 * ══════════════════════════════════════════════════════════════════
 * 去重设计（这是稳定性的核心）
 * ══════════════════════════════════════════════════════════════════
 *
 * 早期版本用过三种方案，都有问题：
 *
 *   ① 「文字相同才去重」—— 助手界面每帧文字都变，去重完全失效，
 *      同一句话执行 6 次切换，最终状态回到原样（用户以为没反应）。
 *
 *   ② 「会话基线」（首帧只记录不执行）—— 多服务同时产生事件时
 *      界面归属抖动（小布悬浮窗 + 解说读屏），基线被反复重建，
 *      用户那一次的话就被当成"已见"吃掉。
 *
 *   ③ 「排除装有无障碍服务的包」—— 小布自己就装了 AnalogSkillService，
 *      被误删导致语音完全失效（零事件）。
 *
 * **现在改为纯时间窗去重，无任何会话状态**：
 *
 *   · 同一关键词 6 秒内只执行一次
 *   · 不同关键词之间至少间隔 1.2 秒
 *   · 多个关键词同时出现时取最长的
 *
 * 这样不管界面怎么抖动、几个服务同时产生事件，结果都稳定：
 * 用户说一句 → 恰好执行一次。
 *
 * ══════════════════════════════════════════════════════════════════
 * 性能
 * ══════════════════════════════════════════════════════════════════
 *
 * 包名列表和关键词表都带缓存。实测不做缓存时，助手界面每秒几十个事件，
 * 每个事件要做 3 次 PackageManager 查询 + 解析 192 行关键词字符串
 * （约 400 次字符串操作），界面明显卡顿。
 */
object AssistantBridge {

    /** 只处理不超过这个长度的文本（助手的识别结果通常很短）。 */
    private const val MAX_TEXT_LEN = 120

    // ══════════ 去重参数 ══════════

    /** 同一关键词的最小执行间隔。界面刷新会持续 2~4 秒，6 秒足够。 */
    private const val SAME_KEYWORD_COOLDOWN_MS = 6000L

    /** 不同关键词之间的最小间隔。用户连说两句通常间隔 1 秒以上。 */
    private const val CROSS_KEYWORD_GAP_MS = 1200L

    /** 关键词记忆有效期，超过就清理，避免 Map 无限增长。 */
    private const val KEYWORD_MEMORY_MS = 60_000L

    // ══════════ 去重状态（纯时间戳，无会话机）══════════

    /** 每个关键词上次执行的时刻（毫秒）。 */
    private val keywordLastAt = HashMap<String, Long>()

    /** 上次任意执行的时刻。 */
    private var lastAnyFireAt = 0L

    /** 上次执行的关键词（诊断用）。 */
    private var lastKeyword = ""

    // ══════════ 缓存 ══════════

    /** 包名列表缓存：避免每个事件都做 PackageManager 查询。 */
    @Volatile
    private var cachedPkgs: List<String>? = null

    @Volatile
    private var cachedPkgSig: String? = null

    /** 由服务注入，用于读配置。 */
    @Volatile
    var appContext: android.content.Context? = null

    // ══════════ 助手包名 ══════════

    /**
     * 已知助手包名（静态兜底）。
     *
     * 真正生效的列表由 [discoverAssistantPkgs] 动态扫描得出，
     * 静态表用来补上那些不声明 ACTION_ASSIST 的助手。
     */
    val KNOWN_ASSISTANT_PKGS: List<String> = listOf(
        // OPPO / 一加 / realme
        "com.heytap.speechassist",
        "com.oplus.ovoicemanager",
        "com.oplus.ovoicemanager.wakeup",
        "com.oplus.aiunit",
        "com.coloros.assistantscreen",
        "com.coloros.floatassistant",
        "com.oplus.voice.crs",
        // 小米 / 红米
        "com.miui.voiceassist",
        "com.xiaomi.voiceassistant",
        "com.miui.voiceassistant",
        "com.xiaomi.aiasst.service",
        "com.xiaomi.aiasst.vision",
        // vivo / iQOO
        "com.vivo.abe",
        "com.vivo.ai",
        "com.vivo.voiceassist",
        "com.bbk.voiceassist",
        // 华为
        "com.huawei.vassistant",
        "com.huawei.aiassistant",
        "com.huawei.ohos.celia",
        // 荣耀
        "com.hihonor.vassistant",
        "com.hihonor.aiassistant",
        // 三星
        "com.samsung.android.bixby.agent",
        "com.samsung.android.bixby.wakeup",
        "com.samsung.android.app.sbrowser.bixby",
        // 魅族
        "com.meizu.voiceassist",
        "com.meizu.flyme.voiceassist",
        // 联想
        "com.lenovo.voiceassist",
        "com.zui.voiceassist",
        // 输入法（语音输入文字会停在输入框，最容易读）
        "com.iflytek.inputmethod",
        "com.baidu.input",
        "com.baidu.input_oppo",
        "com.sohu.inputmethod.sogou",
        "com.tencent.qqpinyin",
        "com.bytedance.android.doubaoime",
        // 第三方 AI
        "com.aliyun.tongyi",
        "com.hewuqi.xinghuo",
        // Google
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.search.assistant",
        "com.google.android.voicesearch"
    )

    /**
     * 动态扫描本机真正存在的助手包名。
     *
     * 三种来源合并去重：
     *   1. 响应 ACTION_ASSIST 的应用
     *   2. 响应 ACTION_VOICE_COMMAND 的应用
     *   3. 系统当前设置的默认助手
     *
     * **注意：只排除自己，不排除"装有无障碍服务的包"。**
     * 曾经在这里 `removeAll(managedPkgs)`，结果小布（自己装了
     * AnalogSkillService）被一起删掉，语音完全失效（实测零事件）。
     */
    fun discoverAssistantPkgs(ctx: android.content.Context): List<String> {
        val found = LinkedHashSet<String>()
        val pm = ctx.packageManager

        runCatching {
            pm.queryIntentActivities(
                android.content.Intent(android.content.Intent.ACTION_ASSIST), 0
            ).forEach { ri -> ri.activityInfo?.packageName?.let { found.add(it) } }
        }
        runCatching {
            pm.queryIntentActivities(
                android.content.Intent(android.content.Intent.ACTION_VOICE_COMMAND), 0
            ).forEach { ri -> ri.activityInfo?.packageName?.let { found.add(it) } }
        }
        runCatching {
            val def = android.provider.Settings.Secure.getString(
                ctx.contentResolver, "assistant"
            )
            if (!def.isNullOrEmpty() && def.contains('/')) {
                found.add(def.substringBefore('/'))
            }
        }

        found.addAll(KNOWN_ASSISTANT_PKGS)

        // 只排除自己
        found.remove(ctx.packageName)

        return found.toList()
    }

    /** 取监听包名列表（带缓存）。 */
    private fun pkgListCached(ctx: android.content.Context): List<String> {
        val sig = AppSettings.assistantPkgsRaw(ctx)
        val c = cachedPkgs
        if (c != null && cachedPkgSig == sig) return c
        val fresh = runCatching { discoverAssistantPkgs(ctx) }
            .getOrDefault(KNOWN_ASSISTANT_PKGS)
        cachedPkgs = fresh
        cachedPkgSig = sig
        Diag.log("assist", "监听助手 ${fresh.size} 个（已缓存）")
        return fresh
    }

    /** 用户改配置后清缓存。 */
    fun invalidatePkgCache() {
        cachedPkgs = null
        cachedPkgSig = null
    }

    // ══════════ 事件入口 ══════════

    /**
     * 处理一个无障碍事件。由 TapAgentService 调用。
     */
    fun onEvent(
        event: AccessibilityEvent,
        enabled: Boolean,
        pkgList: List<String>,
        windowText: () -> String?,
        onAction: (String, String) -> Unit
    ) {
        if (!enabled) return

        val ctx0 = appContext
        val effectivePkgs = if (ctx0 != null) pkgListCached(ctx0) else pkgList
        if (effectivePkgs.isEmpty()) return

        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isEmpty()) return

        // 排除自己：本应用也是无障碍服务，界面会被自己的事件扫到
        val self = appContext?.packageName.orEmpty()
        if (self.isNotEmpty() && (pkg == self || pkg.startsWith(self + "."))) return

        // 只处理监听列表里的包
        if (effectivePkgs.none { pkg == it || pkg.startsWith(it + ".") }) return

        // ── 合并文本，只处理一次 ──
        //
        // 分成两次调用（先事件文字、再窗口文字）会让同一帧被判定两次，
        // 早期踩过：基线刚建立就被第二次调用命中。
        val sb = StringBuilder()
        event.text?.forEach { cs -> if (cs != null) sb.append(cs).append(' ') }
        event.contentDescription?.toString()?.let { sb.append(it) }
        var merged = sb.toString().trim()

        if (merged.isEmpty()) {
            val win = runCatching { windowText() }.getOrNull().orEmpty()
            if (win.isNotEmpty()) merged = win
        }

        if (merged.isEmpty()) return
        if (merged.length > MAX_TEXT_LEN * 4) return

        fire(merged, onAction)
    }

    // ══════════ 匹配与执行 ══════════

    /**
     * 匹配并执行。命中返回 true。
     *
     * 三层时间窗去重，无会话状态：
     *   ① 同一关键词 6 秒内只执行一次
     *   ② 不同关键词之间至少 1.2 秒
     *   ③ 多个关键词同时出现时取最长的
     */
    private fun fire(
        raw: String,
        onAction: (String, String) -> Unit
    ): Boolean {
        if (raw.isEmpty() || raw.length > MAX_TEXT_LEN * 4) return false

        val now = System.currentTimeMillis()

        // 定期清理过期记录
        if (keywordLastAt.size > 64) {
            keywordLastAt.entries.removeAll { now - it.value > KEYWORD_MEMORY_MS }
        }

        val present = matchAll(raw)
        if (present.isEmpty()) return false

        // 取最长（避免「解说」抢在「开启解说」之前）
        val keyword = present.maxByOrNull { it.length } ?: return false
        val action = matchAction(raw, keyword) ?: return false

        // ① 同词冷却
        val lastSame = keywordLastAt[keyword] ?: 0L
        if (lastSame > 0 && now - lastSame < SAME_KEYWORD_COOLDOWN_MS) {
            val left = (SAME_KEYWORD_COOLDOWN_MS - (now - lastSame)) / 1000
            Diag.log("assist", "同词冷却 ${left}s：$keyword")
            return false
        }

        // ② 跨词间隔
        if (lastAnyFireAt > 0 && now - lastAnyFireAt < CROSS_KEYWORD_GAP_MS) {
            Diag.log("assist", "间隔 ${now - lastAnyFireAt}ms 过短，忽略：$keyword")
            return false
        }

        keywordLastAt[keyword] = now
        lastAnyFireAt = now
        lastKeyword = keyword
        Diag.log("assist", "命中「$keyword」→ $action")
        onAction(keyword, action)
        return true
    }

    /** 当前文本里出现的所有关键词（用缓存，避免每次重解析）。 */
    private fun matchAll(raw: String): Set<String> {
        val ctx = appContext ?: return emptySet()
        val text = raw.replace(" ", "").lowercase()
        return AppSettings.voiceKeywordsNormalized(ctx)
            .filter { it.first.isNotEmpty() && text.contains(it.first) }
            .map { it.second }
            .toSet()
    }

    /** 查某个关键词对应的动作。 */
    private fun matchAction(raw: String, keyword: String): String? {
        val ctx = appContext ?: return null
        return AppSettings.voiceKeywordsNormalized(ctx)
            .firstOrNull { it.second == keyword }
            ?.third
    }

    // ══════════ 工具 ══════════

    /**
     * 遍历一棵无障碍节点树，收集所有文字。
     *
     * 深度限制 8 层，总长度限制 2000 字。
     */
    fun collectText(
        node: android.view.accessibility.AccessibilityNodeInfo?,
        depth: Int = 0,
        sb: StringBuilder = StringBuilder()
    ): String {
        if (node == null || depth > 8 || sb.length > 2000) return sb.toString()

        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { sb.append(it).append(' ') }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let {
            sb.append(it).append(' ')
        }

        for (i in 0 until node.childCount) {
            if (sb.length > 2000) break
            collectText(node.getChild(i), depth + 1, sb)
        }
        return sb.toString().trim()
    }

    /** 清空去重状态（切换助手或关闭功能时调用）。 */
    fun resetSeen() {
        keywordLastAt.clear()
        lastAnyFireAt = 0L
        lastKeyword = ""
    }
}
