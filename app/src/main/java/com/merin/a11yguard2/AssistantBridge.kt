package com.merin.a11yguard2

import android.view.accessibility.AccessibilityEvent

/**
 * 从第三方语音助手的界面上读文字，匹配关键词后执行动作。
 *
 * ══════════════════════════════════════════════════════════════════
 * 为什么这样做
 * ══════════════════════════════════════════════════════════════════
 *
 * Android **没有 API** 能把其他 App 的语音识别结果交给第三方 ——
 * 那是安全边界，任何应用（含 root）都无法绕过。
 *
 * 但语音助手有个绕不开的特征：**它必须把识别结果显示给用户看**。
 * 本应用是无障碍服务，能读取界面上出现的文字。于是：
 *
 *     用户说「关闭解说」
 *       → 助手识别 → 结果文字出现在界面
 *       → 本服务读到 → 匹配关键词 → 执行切换
 *
 * ══════════════════════════════════════════════════════════════════
 * 关键约束
 * ══════════════════════════════════════════════════════════════════
 *
 * **必须限制包名**。无障碍事件能拿到全系统的文字，不限包名的话：
 *   · 任何 App 里出现「关闭解说」四个字都会触发
 *   · 耗电显著增加（每次界面变化都要扫文字）
 *
 * 所以只监听配置里列出的助手包名，默认覆盖国内常见助手。
 */
object AssistantBridge {

    /**
     * 触发后的冷却时间（毫秒）。
     *
     * 实测：小布界面从"开始解析"到"指令解析结束"会刷新 6 次，
     * 每次文字都不同。若只按"文字相同"去重，同一句话会执行 6 次切换，
     * 最终状态回到原样 —— 用户以为没反应。
     *
     * 4 秒足够覆盖整个界面刷新周期，又不会影响下一句话。
     */
    private const val COOLDOWN_MS = 800L

    /** 只处理不超过这个长度的文本（助手的识别结果通常很短）。 */
    private const val MAX_TEXT_LEN = 120

    /** 上次执行时刻（毫秒）。冷却期内完全不响应。 */
    private var lastFireAt = 0L

    /** 上次执行的关键词，用于日志对照。 */
    private var lastKeyword = ""

    /** 上次处理的原始文本（诊断用）。 */
    private var lastText = ""

    /**
     * 上一帧文本里出现过的关键词集合。
     *
     * 判据核心：只有当关键词**从无到有**时才响应。
     * 助手界面会把同一句话显示很多帧（"开始解析"→"解析结束"→"暂不支持"），
     * 那些帧里关键词一直都在，不该重复触发。
     */
    private var seenKeywords: Set<String> = emptySet()

    /** 当前正在观察的助手包名，换了就重建基线。 */
    private var currentPkg = ""

    /** 上一次收到事件的时间，间隙过长说明界面已关，需重建基线。 */
    private var lastEventAt = 0L

    /** 是否已经为当前会话建立过基线。 */
    private var baselineReady = false

    /** 事件间隙超过这个值，认为助手界面已关闭，下次重新建基线。 */
    private const val SESSION_GAP_MS = 10_000L

    /**
     * 默认监听的助手包名。
     *
     * 覆盖国内主流 + Google：
     *   com.heytap.speechassist   OPPO 小布
     *   com.oplus.ovoicemanager   OPPO 语音引擎
     *   com.miui.voiceassist      小米小爱
     *   com.vivo.abe              vivo 语音助手
     *   com.google.android.googlequicksearchbox  Google 助手
     *   com.huawei.vassistant     华为小艺
     */
    /**
     * 已知助手包名（静态兜底）。
     *
     * 覆盖国内主流厂商 + Google。这些只是**兜底**：
     * 真正生效的列表由 [discoverAssistantPkgs] 动态扫描本机得出，
     * 静态表用来补上那些不声明 ACTION_ASSIST / ACTION_VOICE_COMMAND 的助手。
     */
    val KNOWN_ASSISTANT_PKGS: List<String> = listOf(
        // ── OPPO / 一加 / realme（ColorOS）──
        "com.heytap.speechassist",
        "com.oplus.ovoicemanager",
        "com.oplus.ovoicemanager.wakeup",
        "com.oplus.aiunit",
        "com.coloros.assistantscreen",
        "com.coloros.floatassistant",
        "com.oplus.voice.crs",

        // ── 小米 / 红米（MIUI / HyperOS）──
        "com.miui.voiceassist",
        "com.xiaomi.voiceassistant",
        "com.miui.voiceassistant",
        "com.xiaomi.aiasst.service",
        "com.xiaomi.aiasst.vision",

        // ── vivo / iQOO（OriginOS）──
        "com.vivo.abe",
        "com.vivo.ai",
        "com.vivo.voiceassist",
        "com.bbk.voiceassist",

        // ── 华为（HarmonyOS / EMUI）──
        "com.huawei.vassistant",
        "com.huawei.aiassistant",
        "com.huawei.ohos.celia",

        // ── 荣耀（MagicOS）──
        "com.hihonor.vassistant",
        "com.hihonor.aiassistant",

        // ── 三星（One UI）──
        "com.samsung.android.bixby.agent",
        "com.samsung.android.bixby.wakeup",
        "com.samsung.android.app.sbrowser.bixby",

        // ── 魅族（Flyme）──
        "com.meizu.voiceassist",
        "com.meizu.flyme.voiceassist",

        // ── 联想 / 其他 ──
        "com.lenovo.voiceassist",
        "com.zui.voiceassist",

        // ── 通用 / 第三方 ──
        "com.iflytek.inputmethod",
        "com.baidu.input",
        "com.sohu.inputmethod.sogou",
        "com.tencent.qqpinyin",

        // ── Google ──
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
     * 这样不管厂商包名怎么变、机型怎么不同，只要它被系统认作助手就能扫到。
     * 静态表作为补充（有些助手不声明这两个 Intent）。
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
        // 系统默认助手的包名
        runCatching {
            val def = android.provider.Settings.Secure.getString(
                ctx.contentResolver, "assistant"
            )
            if (!def.isNullOrEmpty() && def.contains('/')) {
                found.add(def.substringBefore('/'))
            }
        }

        // 已知但扫描不到的，也加进来（不影响，只是多几条监听）
        found.addAll(KNOWN_ASSISTANT_PKGS)

        return found.toList()
    }

    /**
     * 处理一个无障碍事件。由 TapAgentService / GuardA11yService 调用。
     *
     * @param enabled    功能总开关（用户可在设置页关掉）
     * @param pkgList    要监听的包名（用户可改）
     */
    fun onEvent(
        event: AccessibilityEvent,
        enabled: Boolean,
        pkgList: List<String>,
        windowText: () -> String?,
        onAction: (String, String) -> Unit
    ) {
        if (!enabled) return
        if (pkgList.isEmpty()) return

        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isEmpty()) return

        // 排除自己：本应用也是无障碍服务，界面会被自己的事件扫到，
        // 导致在 App 内操作时误触发关键词。
        val self = appContext?.packageName.orEmpty()
        if (self.isNotEmpty() && (pkg == self || pkg.startsWith(self + "."))) return

        if (pkgList.none { pkg == it || pkg.startsWith(it + ".") }) return

        // ── 会话边界检测 ──
        //
        // 助手界面刚打开时，屏幕上可能还残留着上次的识别结果。
        // 若直接判定"新出现"，会立刻误触发。
        // 所以首次见到某助手界面时只建立基线，不执行动作。
        val nowMs = System.currentTimeMillis()
        val pkgChanged = pkg != currentPkg
        val longGap = lastEventAt > 0 && nowMs - lastEventAt > SESSION_GAP_MS
        if (pkgChanged || longGap) {
            currentPkg = pkg
            baselineReady = false
            seenKeywords = emptySet()
            Diag.log("assist", "新会话（$pkg），重建基线")
        }
        lastEventAt = nowMs

        // ── 合并文本 ──
        //
        // 必须合并后**只处理一次**。分成两次调用的话，第一次可能建立了基线，
        // 第二次立刻按"新出现"判定命中 —— 实测会误触发一次。
        val sb = StringBuilder()
        event.text?.forEach { cs -> if (cs != null) sb.append(cs).append(' ') }
        event.contentDescription?.toString()?.let { sb.append(it) }
        var merged = sb.toString().trim()

        if (merged.isEmpty()) {
            // 事件没带文字：读整个活动窗口
            val win = runCatching { windowText() }.getOrNull().orEmpty()
            if (win.isNotEmpty()) merged = win
        }

        if (merged.isEmpty()) return
        if (merged.length > MAX_TEXT_LEN * 4) return

        fire(merged, onAction)
    }



    /** 匹配并执行；命中返回 true。 */
    private fun fire(
        raw: String,
        onAction: (String, String) -> Unit
    ): Boolean {
        if (raw.isEmpty() || raw.length > MAX_TEXT_LEN * 4) return false

        val now = System.currentTimeMillis()

        // 当前帧里出现了哪些关键词
        val present = matchAll(raw)

        // ══ 核心判据：新出现 ══
        //
        // 助手界面会把同一句话显示很多帧，每帧文字都不同
        // （"开始解析" → "指令解析结束" → "该应用暂不支持"）。
        // 若按"文字相同"去重，同一句话会执行好几次，最终状态回到原样。
        //
        // 改成判断关键词是否"从无到有"：
        //   present - seenKeywords 非空 → 这一帧新出现了关键词 → 响应
        //   present ⊆ seenKeywords     → 都是旧文字 → 忽略
        // 基线未建立：只记录，不执行
        if (!baselineReady) {
            seenKeywords = present
            baselineReady = true
            if (present.isNotEmpty()) {
                Diag.log("assist", "基线建立，已见关键词：${present.joinToString()}")
            }
            return false
        }

        val fresh = present - seenKeywords
        seenKeywords = present

        if (fresh.isEmpty()) return false

        // 短冷却兜底：防止界面清空重建导致"消失又出现"被算两次
        if (now - lastFireAt < COOLDOWN_MS) {
            Diag.log("assist", "冷却中，忽略新出现的「${fresh.first()}」")
            return false
        }

        // 取最长的那个（避免「解说」抢在「开启解说」之前）
        val keyword = fresh.maxByOrNull { it.length } ?: return false
        val action = matchAction(raw, keyword) ?: return false

        lastFireAt = now
        lastKeyword = keyword
        lastText = raw
        Diag.log("assist", "命中「$keyword」→ $action")
        onAction(keyword, action)
        return true
    }

    /** 当前文本里出现的所有关键词。 */
    private fun matchAll(raw: String): Set<String> {
        val ctx = appContext ?: return emptySet()
        val text = raw.replace(" ", "").lowercase()
        return AppSettings.voiceKeywords(ctx)
            .map { it.first }
            .filter { it.isNotEmpty() && text.contains(it.replace(" ", "").lowercase()) }
            .toSet()
    }

    /** 查某个关键词对应的动作。 */
    private fun matchAction(raw: String, keyword: String): String? {
        val ctx = appContext ?: return null
        return AppSettings.voiceKeywords(ctx)
            .firstOrNull { it.first == keyword }
            ?.second
    }

    /**
     * 遍历一棵无障碍节点树，收集所有文字。
     *
     * 深度限制 8 层：助手界面不会更深，也防止某些异常树导致递归爆栈。
     * 总长度限制 2000 字：只要够匹配关键词即可，不必把整屏内容都拼出来。
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

    /** 在关键词表里找最长的命中项。 */
    private fun matchKeyword(raw: String): Pair<String, String>? {
        val ctx = appContext ?: return null
        val text = raw.replace(" ", "").lowercase()
        return AppSettings.voiceKeywords(ctx)
            .filter { it.first.isNotEmpty() && text.contains(it.first.replace(" ", "").lowercase()) }
            .maxByOrNull { it.first.length }
    }

    /**
     * 清空"已见关键词"记录。
     *
     * 何时调用：
     *   · 用户切换助手 / 关闭再打开功能
     *   · 助手界面彻底关闭时（下次打开重新算"新出现"）
     */
    fun resetSeen() {
        seenKeywords = emptySet()
        lastFireAt = 0L
        lastKeyword = ""
        lastText = ""
        currentPkg = ""
        lastEventAt = 0L
        baselineReady = false
    }

        /** 由服务在 onCreate 时注入，用于读配置。 */
    @Volatile
    var appContext: android.content.Context? = null
}
