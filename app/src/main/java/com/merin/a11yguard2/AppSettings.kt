package com.merin.a11yguard2

import android.content.Context
import android.content.SharedPreferences

/**
 * 手势 / 音量键 / 保活参数的统一读写入口。
 *
 * 键名与旧版 com.merin.a11ytool（7.3.0）完全一致：
 * volup_target / voldown_target / trigger_window_ms / gen_vib /
 * lock_action / unlock_action / repair_interval / fast_guard / stats_notify / only_user_apps
 * 默认值同样照旧版代码（KeyService$Companion.get*、MainActivity renderKeys）。
 */
object AppSettings {

    // ══════════════════ 语音控制 ══════════════════

    /**
     * 「助手界面触发」开关。
     *
     * 开启后，本服务会读取语音助手界面上的文字，匹配关键词后执行动作。
     * 这是国内环境下最实用的方案 —— 不需要识别器可用，只要你手机的
     * 语音助手能正常用。
     */
    fun assistantBridgeOn(ctx: Context): Boolean =
        p(ctx).getBoolean("assistant_bridge_on", true)

    fun setAssistantBridgeOn(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("assistant_bridge_on", on).apply()
    }

    /**
     * 监听的助手包名（换行分隔，用户可改）。空 = 用动态扫描结果。
     *
     * 动态扫描会查本机真正响应 ACTION_ASSIST / ACTION_VOICE_COMMAND 的应用，
     * 再补上已知包名表。这样不用猜厂商包名，换机型也能用。
     */
    fun assistantPkgsRaw(ctx: Context): String {
        val saved = p(ctx).getString("assistant_pkgs", "").orEmpty()
        if (saved.isNotEmpty()) return saved
        return runCatching {
            AssistantBridge.discoverAssistantPkgs(ctx).joinToString("\n")
        }.getOrElse {
            AssistantBridge.KNOWN_ASSISTANT_PKGS.joinToString("\n")
        }
    }

    fun setAssistantPkgsRaw(ctx: Context, raw: String) {
        p(ctx).edit().putString("assistant_pkgs", raw).apply()
    }

    fun assistantPkgs(ctx: Context): List<String> =
        assistantPkgsRaw(ctx).lines().map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 指定的语音识别器组件（形如 `pkg/pkg.Class`）。空 = 用系统默认。
     *
     * 为什么允许手选：不同识别器差异极大 ——
     *   · Google TTS 的识别器需要联网，国内常报 onError 11
     *   · 厂商自带识别器多数离线可用
     *   · 有的识别器只支持特定语言
     * 不提供切换的话，默认那个不能用就等于功能废了。
     */
    fun voiceRecognizer(ctx: Context): String =
        p(ctx).getString("voice_recognizer", "").orEmpty()

    fun setVoiceRecognizer(ctx: Context, comp: String) {
        p(ctx).edit().putString("voice_recognizer", comp.trim()).apply()
    }


    /**
     * 语音关键词表。格式：「关键词|动作」每行一条。
     *
     * 动作格式与 Triggers.runAction 一致：
     *   com.pkg/com.pkg.Service    切换该服务
     *   ensure:com.pkg/...         确保开启
     *   master                     切换守护服务
     *
     * 存成单个字符串而不是 Set/Map，是为了**保持顺序**：
     * 匹配时长关键词优先，否则「解说」会抢在「开启解说」之前命中。
     */
    fun voiceKeywordsRaw(ctx: Context): String {
        val saved = p(ctx).getString("voice_keywords", "").orEmpty()
        if (saved.isNotEmpty()) return saved

        // 首次使用：为手机里每个无障碍服务自动生成关键词，并落盘。
        //
        // 不在这里直接返回生成结果，而是写入 SharedPreferences ——
        // 否则用户手动删掉不想要的条目后，下次读取又会重新生成。
        val generated = generateKeywordsForInstalledServices(ctx)
        if (generated.isNotEmpty()) {
            p(ctx).edit().putString("voice_keywords", generated).apply()
            Diag.log("voice", "已为 ${generated.lines().size / 2} 个服务自动生成关键词")
            return generated
        }
        return DEFAULT_VOICE_KEYWORDS
    }

    /**
     * 为手机里所有无障碍服务生成「开启X / 关闭X」关键词。
     *
     * 命名优先级：
     *   1. 用户起的别名（aliasOf）—— 短中文名最好喊
     *   2. 服务自带的 label —— 通常已经是中文（如"解说Max"）
     *   3. 包名最后一段 —— 英文名兜底，用户之后可自己改成中文
     *
     * 每个服务两条：
     *   开启X | ensure:<comp>     （只开不关，更安全）
     *   关闭X | <comp>            （切换语义，当前是开就会关）
     */
    private fun generateKeywordsForInstalledServices(ctx: Context): String {
        val rows: List<SvcRow> = runCatching { SvcRepo.all(ctx) }
            .getOrDefault(emptyList<SvcRow>())
            .filter { row -> row.comp.isNotEmpty() }
        if (rows.isEmpty()) return ""

        val sb = StringBuilder()
        val used = mutableSetOf<String>()
        var skipped = 0

        rows.forEach { row ->
            val raw = aliasOf(ctx, row.comp, "").ifEmpty { row.label }
            if (raw.isEmpty()) {
                skipped++
                return@forEach
            }

            // 每个服务生成多种名字变体，覆盖用户的真实说法。
            //
            // 实测问题：服务名叫「解说Max」，只生成「开启解说Max」时，
            // 用户说「开启解说」完全匹配不到 —— 少了 "Max"。
            //
            // 变体策略：
            //   1. 清洗后的全名（解说Max）
            //   2. 去掉版本尾缀（解说）—— Max/Pro/Plus/HD/+/2 等
            //   3. 去掉常见修饰前缀（解说）—— "无障碍"、"系统"
            val full = cleanServiceName(raw)
            if (full.isEmpty()) {
                skipped++
                return@forEach
            }

            val variants = LinkedHashSet<String>()
            variants.add(full)
            stripVersionSuffix(full)?.let { variants.add(it) }
            stripCommonPrefix(full)?.let { variants.add(it) }

            val comp = SvcRepo.normalize(row.comp)

            variants.forEach { variant ->
                if (variant.isEmpty()) return@forEach
                // 中文和英文都生成：TalkBack 这类英文用户会说
                var name = variant
                if (used.contains(name)) {
                    var n = 2
                    while (used.contains("$name$n")) n++
                    name = "$name$n"
                }
                used.add(name)

                // 四种说法全部用**幂等**语义：
                //   开启/打开 → ensure:（只开不关）
                //   关闭/关掉 → off:   （只关不开）
                //
                // 用切换语义（裸组件名）的后果：界面派发多次 → 切两次回到原位，
                // 用户看到「关了又立刻开」。
                sb.append("开启").append(name).append('|').append("ensure:").append(comp).append('\n')
                sb.append("打开").append(name).append('|').append("ensure:").append(comp).append('\n')
                sb.append("关闭").append(name).append('|').append("off:").append(comp).append('\n')
                sb.append("关掉").append(name).append('|').append("off:").append(comp).append('\n')
            }
        }

        if (skipped > 0) {
            Diag.log("voice", "跳过 $skipped 个无名称服务")
        }
        return sb.toString().trimEnd()
    }

    /**
     * 去掉版本尾缀，得到更短的名字。
     *
     * 「解说Max」→「解说」，「TalkBack 2」→「TalkBack」
     * 用户口头上很少带版本号。
     */
    private fun stripVersionSuffix(s: String): String? {
        val suffixes = listOf(
            "Max", "MAX", "max", "Pro", "PRO", "pro",
            "Plus", "PLUS", "plus", "HD", "Ultra", "Lite",
            "+", "＋", " 2", " 3", "2", "3"
        )
        for (suf in suffixes) {
            if (s.endsWith(suf) && s.length > suf.length + 1) {
                val r = s.removeSuffix(suf).trim()
                if (r.isNotEmpty() && r != s) return r
            }
        }
        return null
    }

    /** 去掉常见修饰前缀（「无障碍守护」→「守护」）。 */
    private fun stripCommonPrefix(s: String): String? {
        val prefixes = listOf("无障碍", "系统")
        for (pre in prefixes) {
            if (s.startsWith(pre) && s.length > pre.length + 1) {
                val r = s.removePrefix(pre).trim()
                if (r.isNotEmpty() && r != s) return r
            }
        }
        return null
    }

    /**
     * 清洗服务名，让它能被人喊出来。
     *
     * 原始 label 常见形态：
     *   "GKD伴生服务（请勿手动开启）"      → "GKD伴生服务"
     *   "无障碍守护·点按代理"              → "点按代理"
     *   "解说辅助服务"                     → "解说辅助服务"
     *   "Car+车联"                        → "车联"
     *   "AI写作"                          → "AI写作"
     */
    private fun cleanServiceName(raw: String): String {
        var s = raw.trim()

        // 1. 去掉括号内容（全角 + 半角）
        s = s.replace(Regex("[（(][^）)]*[）)]"), "")

        // 2. 去掉常见的系统提示词
        listOf("伴生服务", "请勿手动开启", "请勿开启", "系统服务", "占位", "placeholder")
            .forEach { s = s.replace(it, "", ignoreCase = true) }

        // 3. "·" 之后是修饰，取主体
        if (s.contains('·')) {
            val parts = s.split('·').filter { it.isNotBlank() }
            // 取最后一个有中文的部分（"无障碍守护·点按代理" → "点按代理"）
            s = parts.lastOrNull { p -> p.any { it.code in 0x4E00..0x9FFF } } ?: parts.last()
        }

        // 4. 去掉特殊符号和空白
        s = s.replace(" ", "").replace("|", "")
        s = s.replace(Regex("""[+ /\[\]{}<>#@!$%^&*_=~`｜、，,。.]"""), "")

        // 5. 太长就截断（语音识别对小段落更准）
        if (s.length > 8) s = s.take(8)

        return s.trim()
    }

    fun setVoiceKeywordsRaw(ctx: Context, raw: String) {
        p(ctx).edit().putString("voice_keywords", raw).apply()
    }

    /**
     * 解析后的关键词缓存。
     *
     * 为什么必须缓存：192 条关键词，每次解析要做 192 次 trim/split，
     * 匹配时还要对每条做 replace+lowercase（约 400 次字符串操作）。
     * 而 onAccessibilityEvent 每秒被调几十次 —— 这是"语音不流畅"的原因之一。
     *
     * 缓存的是「(归一化关键词, 原始关键词, 动作)」三元组，
     * 归一化好的字符串直接用于 contains 判断。
     */
    @Volatile
    private var kwCache: List<Triple<String, String, String>>? = null

    @Volatile
    private var kwCacheSig: String? = null

    /** 取归一化好的关键词表（带缓存）。 */
    fun voiceKeywordsNormalized(ctx: Context): List<Triple<String, String, String>> {
        val raw = voiceKeywordsRaw(ctx)
        val c = kwCache
        if (c != null && kwCacheSig == raw) return c

        val fresh = raw.lines().mapNotNull { line ->
            val s = line.trim()
            if (s.isEmpty() || !s.contains('|')) return@mapNotNull null
            val k = s.substringBefore('|').trim()
            val v = s.substringAfter('|').trim()
            if (k.isEmpty() || v.isEmpty()) null
            else Triple(k.replace(" ", "").lowercase(), k, v)
        }
        kwCache = fresh
        kwCacheSig = raw
        return fresh
    }

    /** 用户改关键词后清缓存。 */
    fun invalidateKeywordCache() {
        kwCache = null
        kwCacheSig = null
    }

    /** 解析成 (关键词, 动作) 列表。格式错误的行会被忽略。 */
    fun voiceKeywords(ctx: Context): List<Pair<String, String>> =
        voiceKeywordsRaw(ctx)
            .lines()
            .mapNotNull { line ->
                val s = line.trim()
                if (s.isEmpty() || !s.contains('|')) return@mapNotNull null
                val k = s.substringBefore('|').trim()
                val v = s.substringAfter('|').trim()
                if (k.isEmpty() || v.isEmpty()) null else k to v
            }

    /**
     * 默认关键词。
     *
     * 为什么给「开启解说」和「打开解说」都配：语音识别对口语说法不固定，
     * 同一个意图可能有多种表达，多给几个同义词命中率会高很多。
     */
    val DEFAULT_VOICE_KEYWORDS: String = listOf(
        "开启解说" to "ensure:com.nirenr.talkman/com.nirenr.talkman.TalkManAccessibilityService",
        "打开解说" to "ensure:com.nirenr.talkman/com.nirenr.talkman.TalkManAccessibilityService",
        "启动解说" to "ensure:com.nirenr.talkman/com.nirenr.talkman.TalkManAccessibilityService",
        "关闭解说" to "com.nirenr.talkman/com.nirenr.talkman.TalkManAccessibilityService",
        "关掉解说" to "com.nirenr.talkman/com.nirenr.talkman.TalkManAccessibilityService",
        "停止解说" to "com.nirenr.talkman/com.nirenr.talkman.TalkManAccessibilityService",
        "开启守护" to "ensure:master",
        "关闭守护" to "master"
    ).joinToString("\n") { it.first + "|" + it.second }

    const val ACT_NONE = "none"
    const val ACT_MASTER = "master"

    /** 切换（取反）：开着就关、关着就开。 */
    const val ACT_PREFIX = "toggle:"

    /**
     * **确保开启**：已开则什么也不做；未开才打开。绝不关闭。
     *
     * ── 为什么需要这个动作 ──
     *
     * 锁屏/解锁这类"周期性触发"的场景，用「切换」是错的：
     * 服务正常运行时，锁屏一次就把它关掉，解锁一次又打开 ——
     * 用户会看到读屏反复中断。
     *
     * 正确的语义是**自愈**：屏幕状态变化时检查一次，
     *   服务在跑 → 不动（用户可能正在听书）
     *   服务崩了/被关了 → 拉起来
     *
     * 所以锁屏/解锁的推荐配置就是 `ensure:<component>`。
     */
    const val ACT_ENSURE_PREFIX = "ensure:"

    /** 「确保开启」守护服务本身（最常用的组合）。 */
    const val ACT_ENSURE_MASTER = "ensure:master"

    /**
     * **确保关闭**前缀：已关则什么也不做；开着才关。绝不开启。
     *
     * ── 为什么需要（踩过的坑）──
     *
     * 「关闭X」原本用裸组件名（切换语义：开→关，关→开）。
     * 但助手界面会把同一句话派发多次，导致 execute 跑两遍：
     *   第1次：开→关 ✓
     *   第2次：关→开 ✗（又开回来了）
     * 用户看到的是「关了又立刻开」，或者以为完全没反应。
     *
     * 改成 ensure-disabled 之后，执行 N 次结果都是"关"，**幂等**。
     */
    const val ACT_OFF_PREFIX = "off:"


    fun p(ctx: Context): SharedPreferences = SvcRepo.prefs(ctx)

    // ---------- 加锁 ----------

    fun lockedComps(ctx: Context): List<String> =
        LegacyCodec.normalizeComps(p(ctx).getString("locked_comps", "")).map { SvcRepo.normalize(it) }

    fun isLocked(ctx: Context, comp: String): Boolean = LockLogic.isLocked(comp, lockedComps(ctx))

    /** 补回失败（服务已被系统彻底禁用）的加锁项，供概览显示“⚠ 无法保持”。 */
    fun lockBroken(ctx: Context): List<String> =
        LegacyCodec.normalizeComps(p(ctx).getString("lock_failed_comps", ""))

    fun markLockBroken(ctx: Context, comp: String, broken: Boolean) {
        val target = SvcRepo.normalize(comp)
        val list = lockBroken(ctx).map { SvcRepo.normalize(it) }.toMutableList()
        if (broken) {
            if (!list.contains(target)) list.add(target)
        } else {
            list.removeAll { it == target }
        }
        p(ctx).edit().putString("lock_failed_comps", LegacyCodec.join(list)).apply()
    }

    // ---------- 自愈台账 ----------

    fun markHeal(ctx: Context, reason: String, result: String) {
        val at = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA)
            .format(java.util.Date())
        p(ctx).edit()
            .putString("last_heal_at", at)
            .putString("last_heal_reason", reason)
            .putString("last_heal_result", result)
            .apply()
    }

    fun lastHealText(ctx: Context): String {
        val at = p(ctx).getString("last_heal_at", null) ?: return "无记录"
        val reason = p(ctx).getString("last_heal_reason", "-")
        val result = p(ctx).getString("last_heal_result", "-")
        return "$at · 原因 $reason · 补回 $result"
    }

    // ---------- 摇一摇 ----------
    //
    // 判定算法见 ShakeLogic（采用业界经典实现：变化率 + 连续计数）。

    /** 摇一摇总开关；默认关闭，避免新用户被动开启传感器。 */
    fun shakeOn(ctx: Context): Boolean = p(ctx).getBoolean("shake_on", false)

    /**
     * 摇一摇「计次过程中」是否给弱震动反馈。
     *
     * ── 为什么需要 ──
     *
     * 摇动次数可以设到 10 次，过程中用户完全不知道摇到第几下：
     *   · 给弱震动 → 每次计满一下就轻震一下，能数出来还差几次
     *   · 不给     → 完全静默，只有成功时震一次（有人喜欢这样，安静）
     *
     * 所以做成开关，默认**开启**（多数人需要知道进度）。
     * 强震动（触发成功）不受此开关影响，始终按「摇一摇震动」的时长执行。
     */
    fun shakeTickVibOn(ctx: Context): Boolean = p(ctx).getBoolean("shake_tick_vib", true)

    /**
     * 计次过程中的弱震动时长（毫秒）。默认 15ms（极轻）。
     *
     * 刻意做得很轻：如果和成功震动一样响，用户会分不清"累计中"和"已触发"。
     */
    fun shakeTickVibMs(ctx: Context): Int = p(ctx).getInt("shake_tick_vib_ms", 15)

    /** 摇一摇灵敏度档位 1-10（10 最灵敏）。 */
    fun shakeSensitivity(ctx: Context): Int =
        p(ctx).getInt("shake_sensitivity", ShakeLogic.DEFAULT_SENSITIVITY).coerceIn(1, 10)

    /** 摇动次数：摇几下触发（1-10）。 */
    fun shakeCount(ctx: Context): Int =
        p(ctx).getInt("shake_count_free", 3).coerceIn(1, 10)

    /** 摇一摇目标服务（裸组件名或 toggle: 前缀）。 */
    fun shakeTarget(ctx: Context): String = p(ctx).getString("shake_target", "").orEmpty()

    /**
     * 全局震动总开关。为 true 时所有震动都被跳过。
     *
     * 保留它是因为"开会 / 震动模块坏了"时需要一键全关；
     * 但日常细调用下面的 [vibOn] 按功能独立控制。
     */
    fun vibAllOff(ctx: Context): Boolean = p(ctx).getBoolean("vib_all_off", false)

    /**
     * 每个手势的震动**独立开关**。
     *
     * 三类震动分开控制，因为触发频率差异很大：
     *   · VOL   音量键 —— 手动触发，频率低
     *   · TAP   背面敲击 —— 包括"计入中间次数"的提示音，频率中等
     *   · SHAKE 摇一摇 —— 如果开着，甩动时会连续震，最吵
     *
     * 有人喜欢音量键有反馈但摇一摇安静，或反之；共用一个开关做不到。
     */
    const val VIB_VOL = "vol"
    const val VIB_TAP = "tap"
    const val VIB_SHAKE = "shake"

    /**
     * 每个手势的「锁屏时不执行」独立开关。
     *
     * ── 为什么每个手势要独立 ──
     *
     * 不同手势在锁屏时的使用意图完全不同：
     *
     *   · 音量键：锁屏时最常见的诉求是「调节音量」（听书、听歌），
     *     这时误触发手势会打断播放。所以很多人希望锁屏时禁用。
     *   · 背面敲击：锁屏时口袋摩擦/桌面震动容易误触，多数人也想禁用。
     *   · 摇一摇：锁屏时放着不动或走路都可能误触，最应禁用。
     *
     * 但也有人希望「锁屏时也能切换服务」（比如盲人在口袋里盲操作），
     * 所以不能一刀切 —— 必须逐个可配。
     *
     * 默认值：
     *   音量键 / 背面敲击 → **允许**（保持现有行为，不破坏已调好的设置）
     *   摇一摇 → **禁止**（锁屏时误触率最高）
     */
    const val LOCK_VOL = "vol"
    const val LOCK_TAP = "tap"
    const val LOCK_SHAKE = "shake"
    const val LOCK_BACKTAP = "backtap"

    /** 某手势在锁屏时是否允许执行（默认 true = 允许）。 */
    fun allowWhenLocked(ctx: Context, kind: String): Boolean {
        val def = kind != LOCK_SHAKE   // 摇一摇默认禁止
        return p(ctx).getBoolean("lock_allow_$kind", def)
    }

    fun setAllowWhenLocked(ctx: Context, kind: String, allow: Boolean) {
        p(ctx).edit().putBoolean("lock_allow_$kind", allow).apply()
    }

    /**
     * 默认震动幅度（0-255）。取 30 —— Android 幅度刻度里属于"轻触"。
     * 不是 255：那是"最大能力"，手感过硬。
     */
    const val DEFAULT_AMPLITUDE = 12

    /**
     * 某个功能的震动幅度（0-255）。
     *
     * 与时长**分开存**，因为用户可能想要"短而强"或"长而轻"，
     * 只绑一个维度表达不出来。
     */
    fun vibAmp(ctx: Context, kind: String): Int =
        p(ctx).getInt("vib_amp_$kind", DEFAULT_AMPLITUDE).coerceIn(0, 255)

    fun setVibAmp(ctx: Context, kind: String, amp: Int) {
        p(ctx).edit().putInt("vib_amp_$kind", amp.coerceIn(0, 255)).apply()
    }

    /**
     * 「触发成功时震动」总开关，默认开启。
     *
     * 独立于各手势档位：有人希望"过程完全静默、成功也不震"，
     * 只要把这一个开关关掉即可，不必逐个手势去调。
     *
     * 优先级：vibAllOff（全关）> 本开关（成功震动）> 手势档位。
     */
    fun vibOnSuccess(ctx: Context): Boolean = p(ctx).getBoolean("vib_on_success", true)

    /**
     * 过程（计数中）的震动幅度。
     *
     * ── 为什么必须与成功震动分开 ──
     *
     * 用户的原话："平时在摇的过程当中或者敲击的过程当中，震动都不要太强烈，
     * 都很弱，只有在触发成功的时候，这个震动可以强一点。"
     *
     * 早期两处共用同一个幅度，结果是每计一次数都"咚"一下很重，
     * 整个敲击/摇动过程都在被强震打断。
     *
     * 现在固定为 8（羽毛级）：只给"我收到了"的微弱提示，不打断手感。
     * 成功时才用用户设置的档位。
     */
    const val TICK_AMPLITUDE = 6

    /** 过程震动的默认时长（毫秒）。12ms 极短，配合小幅度才真正"轻"。 */
    const val TICK_MS = 10

    /** 某个功能的震动是否开启（默认全开）。总开关关闭时一律 false。 */
    fun vibOn(ctx: Context, kind: String): Boolean {
        if (vibAllOff(ctx)) return false
        return p(ctx).getBoolean("vib_$kind", true)
    }

    fun setVibOn(ctx: Context, kind: String, on: Boolean) {
        p(ctx).edit().putBoolean("vib_$kind", on).apply()
    }

    /** 每个功能的震动时长（毫秒），独立配置。0 = 不震。 */
    fun vibMs(ctx: Context, kind: String, fallback: Int = 14): Int =
        p(ctx).getInt("vib_ms_$kind", fallback)

    fun setVibMs(ctx: Context, kind: String, ms: Int) {
        p(ctx).edit().putInt("vib_ms_$kind", ms).apply()
    }

    /** 兼容旧调用点：摇一摇的震动时长。 */
    fun sensorVibMs(ctx: Context): Int = vibMs(ctx, VIB_SHAKE, 35)

    // ---------- 背面敲击 ----------

    /** 背面敲击总开关；新功能默认关闭，避免未了解功耗的用户被动开启传感器。 */
    fun backTapOn(ctx: Context): Boolean = p(ctx).getBoolean("backtap_on", false)

    /** 触发次数：2 或 3。 */
    fun backTapCount(ctx: Context): Int = p(ctx).getInt("backtap_count", 2).coerceIn(2, 3)

    /** 灵敏度档位 1..10（10 最灵敏），映射见 [BackTapLogic.paramsForSensitivity]。 */
    fun backTapSensitivity(ctx: Context): Int =
        p(ctx).getInt("backtap_sensitivity", BackTapLogic.LEVEL_DEFAULT)
            .coerceIn(BackTapLogic.LEVEL_MIN, BackTapLogic.LEVEL_MAX)

    /** 敲击后要切换的无障碍服务组件；空 = 未设置。 */
    fun backTapTarget(ctx: Context): String = p(ctx).getString("backtap_target", "").orEmpty()

    /**
     * 三击背面的目标；空 = 不响应三击（只认双击）。
     *
     * 与 [backTapCount] 的关系：backTapCount 决定「需要敲几下」，
     * 而 tripleTarget 决定「敲 3 下时切到哪个服务」。
     * 当 tripleTarget 非空时，判定逻辑会自动变成「敲 2 下等一拍、再决定」，
     * 否则双击会被三击抢占（详见 BackTapLogic 的说明）。
     */
    fun backTapTripleTarget(ctx: Context): String =
        p(ctx).getString("backtap_triple_target", "").orEmpty()

    /** 是否启用了「双击/三击分开」模式。 */
    fun backTapDualMode(ctx: Context): Boolean = backTapTripleTarget(ctx).isNotEmpty()

    /** 熄屏是否继续检测；开启会持有 CPU 唤醒锁，待机耗电明显增加。 */
    fun backTapScreenOff(ctx: Context): Boolean = p(ctx).getBoolean("backtap_screen_off", false)

    // ---------- 音量键 ----------

    /** 音量键触发总开关（旧版没有，默认开启以保持原行为）。 */
    fun volKeyOn(ctx: Context): Boolean = p(ctx).getBoolean("volkey_on", true)

    fun volUpTarget(ctx: Context): String = p(ctx).getString("volup_target", "").orEmpty()

    fun volDownTarget(ctx: Context): String = p(ctx).getString("voldown_target", "").orEmpty()

    // ── 三击目标（2026-11-27 新增）──
    //
    // 上/下键各自支持「双击一个服务、三击另一个服务」。
    // 判定流程见 KeyPressLogic：第 2 下进入 250ms 确认期，
    // 期内按第 3 下 → 三击；否则 → 双击。

    /** 三击上键的目标；空 = 不响应三击（只认双击）。 */
    fun volUpTripleTarget(ctx: Context): String =
        p(ctx).getString("volup_triple_target", "").orEmpty()

    /** 三击下键的目标；空 = 不响应三击（只认双击）。 */
    fun volDownTripleTarget(ctx: Context): String =
        p(ctx).getString("voldown_triple_target", "").orEmpty()

    /** 双击窗口（毫秒），旧版默认 500，可选 200/300/400/500/600。 */
    fun volWindowMs(ctx: Context): Int = p(ctx).getInt("trigger_window_ms", 300)

    /** 通用震动时长（毫秒），旧版默认 200；档位 轻60 / 中110 / 标准200 / 强250。 */
    fun genVibMs(ctx: Context): Int = p(ctx).getInt("gen_vib", 14)

    // ---------- 锁屏 / 解锁 ----------

    fun lockAction(ctx: Context): String = p(ctx).getString("lock_action", ACT_ENSURE_MASTER).orEmpty()

    fun unlockAction(ctx: Context): String = p(ctx).getString("unlock_action", ACT_ENSURE_MASTER).orEmpty()

    // ---------- 保活 ----------

    /** 自动修复间隔（毫秒），0 = 关闭；旧版选项 关闭 / 30 分钟 / 1 小时。 */
    fun repairIntervalMs(ctx: Context): Long = p(ctx).getLong("repair_interval", 0L)

    /** 快速检测：5 秒一轮；关闭时保持事件驱动（不轮询）。 */
    fun fastGuard(ctx: Context): Boolean = p(ctx).getBoolean("fast_guard", false)

    fun statsNotify(ctx: Context): Boolean = p(ctx).getBoolean("stats_notify", true)

    /** 诊断日志只记录异常（默认开启）：平稳运行的流水账不再落盘，只有出问题才写 */
    fun logOnlyAnomalies(ctx: Context): Boolean = p(ctx).getBoolean("log_only_anomalies", true)

    fun onlyUserApps(ctx: Context): Boolean = p(ctx).getBoolean("only_user_apps", false)

    /** 「点按代理」是否曾被用户开启过：用于 force-stop / 服务被系统移除后的自动恢复，不强制给未用过的人开启。 */
    fun tapEverOn(ctx: Context): Boolean = p(ctx).getBoolean("tap_ever_on", false)

    /** 用户当前是否希望「点按代理」保持开启；手动关闭后守护服务不得立刻把它加回来。 */
    fun tapDesiredOn(ctx: Context): Boolean =
        p(ctx).getBoolean("tap_desired_on", tapEverOn(ctx))

    // ---------- 统计 ----------

    fun statOk(ctx: Context): Int = p(ctx).getInt("stat_ok", 0)

    fun statFail(ctx: Context): Int = p(ctx).getInt("stat_fail", 0)

    /** 守护服务启动次数（onCreate / 开机拉起都算） */
    fun statStart(ctx: Context): Int = p(ctx).getInt("stat_start", 0)

    /** 本进程重启次数（onCreate 时读到的上次存活标记不匹配 → 说明之前被杀过） */
    fun statRestart(ctx: Context): Int = p(ctx).getInt("stat_restart", 0)

    /** 成功把目标无障碍服务拉回来的次数（真正执行了补回动作） */
    fun statRevive(ctx: Context): Int = p(ctx).getInt("stat_revive", 0)

    /** 失败次数（补回没成功） */
    fun statFailCount(ctx: Context): Int = statFail(ctx)

    fun bumpStat(ctx: Context, ok: Boolean) {
        val sp = p(ctx)
        val k = if (ok) "stat_ok" else "stat_fail"
        sp.edit().putInt(k, sp.getInt(k, 0) + 1).apply()
    }

    /** 累加某个统计项（内部用，键名集中在这里，避免各处手写字符串） */
    fun bump(ctx: Context, key: String) {
        val sp = p(ctx)
        sp.edit().putInt(key, sp.getInt(key, 0) + 1).apply()
    }

    fun bumpStart(ctx: Context) = bump(ctx, "stat_start")

    fun bumpRestart(ctx: Context) = bump(ctx, "stat_restart")

    fun bumpRevive(ctx: Context) = bump(ctx, "stat_revive")

    // ---------- 服务别名 ----------

    /**
     * 用户给无障碍服务起的别名。
     *
     * 为什么需要：很多服务名字是英文/拉丁字母（比如某个读屏的包名式标签），
     * 语音识别对英文单词的准确率远低于中文，说出来经常识别成别的东西。
     * 允许改成「一号」「二号」这种短中文名，语音匹配就稳得多。
     *
     * 存储格式：comp -> 别名，一行一条，用 \n 分隔，comp 与别名用 \u0001 分隔。
     * 用单条字符串存是因为 SharedPreferences 没有 map 类型，而条目数量很少。
     */
    // 分隔符必须用可见字符：\u0001 这类控制字符在 SharedPreferences 的 XML 里
    // 会被写成 &#1;，读回来就不是原来的字符了，解析必失败（实测踩过）。
    // 存储用 JSON：之前用 \u0001 和 \t 当分隔符，都被 SharedPreferences 的 XML
    // 转义成 &#1; / &#9;，读回来解析必失败（实测踩过两次）。JSON 里都是合法字符，最稳。
    private fun aliasJson(ctx: Context): org.json.JSONObject {
        val raw = p(ctx).getString("svc_aliases_json", "").orEmpty()
        if (raw.isBlank()) return org.json.JSONObject()
        return runCatching { org.json.JSONObject(raw) }.getOrDefault(org.json.JSONObject())
    }

    fun aliasMap(ctx: Context): MutableMap<String, String> {
        val m = LinkedHashMap<String, String>()
        val j = aliasJson(ctx)
        for (k in j.keys()) {
            val v = j.optString(k, "")
            if (v.isNotBlank()) m[k] = v
        }
        return m
    }

    /** 某个服务的显示名：有别名用别名，没有就用系统标签 */
    fun aliasOf(ctx: Context, comp: String, fallback: String): String {
        val a = aliasJson(ctx).optString(SvcRepo.normalize(comp), "")
        return if (a.isBlank()) fallback else a
    }

    fun setAlias(ctx: Context, comp: String, alias: String) {
        val key = SvcRepo.normalize(comp)
        val j = aliasJson(ctx)
        val tt = alias.trim()
        runCatching { if (tt.isEmpty()) j.remove(key) else j.put(key, tt) }
        p(ctx).edit().putString("svc_aliases_json", j.toString()).apply()
    }
    /** 通知栏要显示的一行摘要 */
    fun statsLine(ctx: Context): String {
        val ok = statOk(ctx)
        val fail = statFail(ctx)
        val start = statStart(ctx)
        val restart = statRestart(ctx)
        val revive = statRevive(ctx)
        return "成功 $ok · 失败 $fail · 启动 $start · 重启 $restart · 拉活 $revive"
    }

    // ---------- 更新检查 ----------
    //
    // 这是本应用唯一会联网的功能，且默认关闭（首次启动时若内置了默认源，
    // 会提示用户是否开启，而不是静默联网）。

    /**
     * 内置默认更新源（编译期常量）。
     *
     * ── 为什么要内置 ──
     *
     * 用户装完就能收到更新，不需要手动填地址。这是分发的正常预期：
     * 「装了你的应用，你发新版我该知道」。
     *
     * ── 二次修改者如何用 ──
     *
     * fork 本项目后，只需改这一行常量（或让构建脚本注入），
     * 之后的自动更新就会指向自己的仓库，无需改任何其它代码。
     * 同时用户在设置页仍可手动改成别的源，覆盖这里的默认值。
     *
     * 空字符串 = 不内置（用户必须自己填）。
     *
     * 格式：`owner/repo`（GitHub Release）或完整 https JSON 地址。
     *
     * ── 实现说明 ──
     *
     * 值来自 `BuildConfig`，由 `app/build.gradle.kts` 在编译期注入，
     * 因此二次开发可以完全不动这个 Kotlin 文件：
     *
     *   ./gradlew assembleRelease -PupdateSource=你的名字/a11yguard2
     *
     * 或设环境变量 `UPDATE_SOURCE`。
     */
    val DEFAULT_UPDATE_SOURCE: String
        get() = BuildConfig.DEFAULT_UPDATE_SOURCE

    /**
     * 生效的更新源：
     *   1. 用户手动设置过的优先（可覆盖内置默认）
     *   2. 否则用内置默认
     *   3. 都没有则为空（不联网）
     */
    fun updateSource(ctx: Context): String {
        val custom = p(ctx).getString("update_source", "").orEmpty().trim()
        if (custom.isNotEmpty()) return custom
        return DEFAULT_UPDATE_SOURCE.trim()
    }

    /** 用户是否手动改过更新源（用于界面显示「内置默认」还是「自定义」）。 */
    fun hasCustomUpdateSource(ctx: Context): Boolean =
        p(ctx).getString("update_source", "").orEmpty().trim().isNotEmpty()

    fun setUpdateSource(ctx: Context, src: String) {
        p(ctx).edit().putString("update_source", src.trim()).apply()
    }

    /** 恢复为内置默认源（清掉用户自定义值）。 */
    fun resetUpdateSource(ctx: Context) {
        p(ctx).edit().remove("update_source").apply()
    }

    /**
     * 总开关。
     *
     * 默认值：内置了默认源时默认 **开**（否则「装了却收不到更新」很反直觉），
     * 没内置时默认 **关**（用户没给地址，开着也没用）。
     */
    fun updateCheckOn(ctx: Context): Boolean =
        p(ctx).getBoolean("update_on", DEFAULT_UPDATE_SOURCE.isNotEmpty())

    fun setUpdateCheckOn(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("update_on", on).apply()
    }

    /** 上次检查的时间戳（毫秒），0 = 从未检查。 */
    fun lastUpdateCheckAt(ctx: Context): Long = p(ctx).getLong("update_last_at", 0L)

    fun setLastUpdateCheckAt(ctx: Context, at: Long) {
        p(ctx).edit().putLong("update_last_at", at).apply()
    }

    /** 被用户「跳过」的版本号（versionCode），避免同一版本反复弹窗。 */
    fun skippedUpdateCode(ctx: Context): Int = p(ctx).getInt("update_skip_code", 0)

    fun setSkippedUpdateCode(ctx: Context, code: Int) {
        p(ctx).edit().putInt("update_skip_code", code).apply()
    }
}
