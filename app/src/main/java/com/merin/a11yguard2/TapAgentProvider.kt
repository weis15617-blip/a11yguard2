package com.merin.a11yguard2

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 默认执行层：只依赖无障碍授权，不需要 Shizuku / ADB / root。
 * 打开系统无障碍设置 → 点目标服务行 → 点开关 → （首次启用时点“允许”）→ 回读校验。
 * 任何一步失败都返回失败，由 UI 提示手动操作，绝不假装成功。
 */
class TapAgentProvider : ToggleProvider {
    override val route: String = RouteLogic.ROUTE_TAP

    override fun status(ctx: Context): String =
        if (TapAgentService.instance != null) "available" else "no_permission"

    override fun toggle(ctx: Context, comp: String, want: Boolean): ToggleResult {
        val svc = TapAgentService.instance
            ?: return ToggleResult(route, comp, false, false, false, "无障碍授权未开启（点按代理未连接）")
        val label = SvcRepo.labelOf(ctx, comp)
        Diag.log("tap", "start target=$comp label=$label want=$want")
        // 无动画打开：避免设置页"滑出"的视觉冲击。
        // 点按模式本来就要借系统设置页，动画越少越不像在跳转。
        val started = runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            )
        }.isSuccess
        if (!started) return ToggleResult(route, comp, false, false, false, "无法打开系统无障碍设置")

        val root = waitRoot(svc, 3000)
            ?: return ToggleResult(route, comp, false, false, false, "未进入无障碍设置页")
        val row = findByName(root, label)
            ?: return ToggleResult(route, comp, false, false, false, "未找到服务行：$label")
        if (!clickNode(row)) {
            return ToggleResult(route, comp, true, false, false, "服务行不可点击")
        }
        SystemClock.sleep(600)

        val page = waitRoot(svc, 2000)
            ?: return ToggleResult(route, comp, true, false, false, "未进入服务详情页")
        val sw = findSwitch(page)
            ?: return ToggleResult(route, comp, true, false, false, "未找到开关控件")
        val checked = runCatching { sw.isChecked }.getOrDefault(false)
        if (checked == want) {
            return ToggleResult(route, comp, true, false, true, "已是目标状态（want=$want），无需点击")
        }
        if (!clickNode(sw)) {
            return ToggleResult(route, comp, true, false, false, "开关点击失败")
        }
        SystemClock.sleep(600)
        if (want) confirmDialog(svc)
        val ok = pollVerify(ctx, comp, want, 3000)
        Diag.log("tap", "done verify=$ok")

        // 切换完成后模拟返回，把系统设置页退掉，回到用户原来的界面。
        // 无障碍服务不能直接 finish 别人的 Activity，但 performGlobalAction(BACK)
        // 是标准能力，效果等价且不依赖 Activity 上下文。
        if (ok) {
            val back = runCatching {
                svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            }.getOrDefault(false)
            Diag.log("tap", "auto-back=$back")
            // 再退一次：有些机型设置页是两层（列表 + 详情），要退两下
            if (back) {
                SystemClock.sleep(300)
                runCatching {
                    svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                }
            }
        }

        return ToggleResult(route, comp, true, true, ok, if (ok) null else "回读设置未变化")
    }

    private fun waitRoot(svc: TapAgentService, timeoutMs: Long): AccessibilityNodeInfo? {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            val r = runCatching { svc.rootInActiveWindow }.getOrNull()
            val pkg = r?.packageName?.toString().orEmpty()
            // 不绑定具体厂商包名：系统设置无论是 AOSP（com.android.settings）还是厂商（如 com.oplus.settings）都匹配
            if (r != null && pkg.contains("settings")) {
                return r
            }
            SystemClock.sleep(200)
        }
        return runCatching { svc.rootInActiveWindow }.getOrNull()
    }

    /**
     * 在无障碍设置页里找目标服务那一行。
     *
     * 三级降级，因为不同厂商的文案差异很大：
     *   1. 精确匹配
     *   2. 包含匹配
     *   3. 去掉空格/标点后匹配（"解说 Max" vs "解说Max"）
     *
     * 全都不中时把页面上的候选行 dump 到日志 —— 用户反馈时能直接看出
     * 这台手机实际显示的是什么，不用让他描述。
     */
    private fun findByName(root: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        // 1. 精确
        bfs(root) { it.text?.toString()?.trim() == label }?.let { return it }

        // 2. 包含
        bfs(root) { it.text?.toString()?.trim()?.contains(label) == true }?.let { return it }

        // 3. 归一化后比较（去空格、去标点、小写）
        val normLabel = normalizeLabel(label)
        if (normLabel.isNotEmpty()) {
            bfs(root) { n ->
                val t = normalizeLabel(n.text?.toString().orEmpty())
                t.isNotEmpty() && (t == normLabel || t.contains(normLabel) || normLabel.contains(t))
            }?.let { return it }
        }

        // 全不中：dump 候选行，便于诊断
        runCatching {
            val candidates = mutableListOf<String>()
            val q = ArrayDeque<AccessibilityNodeInfo>()
            q.add(root)
            var n = 0
            while (q.isNotEmpty() && n < 400) {
                val node = q.removeFirst()
                n++
                val txt = node.text?.toString()?.trim().orEmpty()
                // 无障碍设置页的服务行通常不是按钮，长度适中
                if (txt.length in 2..40 && node.isClickable) candidates.add(txt)
                for (i in 0 until runCatching { node.childCount }.getOrDefault(0)) {
                    runCatching { node.getChild(i) }.getOrNull()?.let { q.add(it) }
                }
            }
            Diag.log("tap", "未找到「$label」，页面可点文本：${candidates.take(20).joinToString(" / ")}")
        }

        return null
    }

    /** 归一化：去空格、去常见标点、转小写。 */
    private fun normalizeLabel(s: String): String =
        s.replace(" ", "").replace("·", "").replace("—", "")
            .replace("（", "(").replace("）", ")")
            .replace("　", "")
            .trim()
            .lowercase()

    private fun findSwitch(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        bfs(root) {
            val id = it.viewIdResourceName.orEmpty()
            id.endsWith("switch_widget")
        } ?: bfs(root) { n ->
            n.className?.toString()?.contains("Switch") == true || n.isCheckable
        }

    /**
     * 自动确认系统弹出的「无障碍权限」提示框。
     *
     * 典型弹窗：
     *   检测到「无障碍守护」获取无障碍权限
     *   无障碍权限允许应用查看屏幕内容并自动操作设备…
     *   [关闭无障碍]        ← 危险按钮，绝不能点
     *   [保持开启]          ← 要点这个
     *
     * ── 为什么之前点不到 ──
     *
     * 1. 文案不匹配：旧代码只找「允许/确定/好/Allow/OK」，
     *    而这台手机显示的是「保持开启」，一个都不命中。
     *
     * 2. 只用 ACTION_CLICK：用户实测「带着读屏软件时点不了」。
     *    读屏会拦截 ACTION_CLICK 到部分界面树，
     *    必须用 dispatchGesture 注入真实触摸事件才能穿透。
     */
    private fun confirmDialog(svc: TapAgentService) {
        // 「保持开启」及各种等价说法。注意「关闭无障碍」绝不在其中。
        val okTexts = listOf(
            "保持开启", "保持启用", "保持打开",
            "保留", "继续", "启用", "开启",
            "允许", "确定", "好", "同意",
            "Allow", "OK", "Keep on", "Enable", "Continue"
        )
        // 危险文案：出现这些的节点绝不点击
        val dangerTexts = listOf("关闭无障碍", "关闭", "停用", "禁用", "Turn off", "Disable")

        repeat(10) {
            val r = runCatching { svc.rootInActiveWindow }.getOrNull()
            if (r == null) {
                SystemClock.sleep(250)
                return@repeat
            }

            // 先找精确匹配的确认按钮
            val target = okTexts.firstNotNullOfOrNull { txt ->
                bfs(r) { n ->
                    val t = n.text?.toString().orEmpty().trim()
                    t == txt
                }
            } ?: okTexts.firstNotNullOfOrNull { txt ->
                bfs(r) { n ->
                    val t = n.text?.toString().orEmpty().trim()
                    t.contains(txt) && dangerTexts.none { d -> t.contains(d) }
                }
            }

            if (target != null) {
                if (clickNodeSmart(svc, target)) {
                    Diag.log("tap", "confirmed permission dialog（文案=${target.text}）")
                    SystemClock.sleep(400)
                    return
                }
            }
            SystemClock.sleep(250)
        }
        Diag.log("tap", "permission dialog 未出现或未能确认")
    }

    /**
     * 三级点击降级。
     *
     * 1. ACTION_CLICK  —— 最快，对普通对话框有效
     * 2. 父节点 CLICK   —— 很多对话框的真实可点节点是父容器
     * 3. dispatchGesture 模拟点击 —— 绕过读屏对 ACTION_CLICK 的拦截
     *
     * 用户的实测结论："普通点击点击不了，搞个模拟点击就能点击过去"。
     * 所以第 3 级是这台设备上的**实际生效路径**，不是可有可无的兜底。
     */
    private fun clickNodeSmart(svc: TapAgentService, node: AccessibilityNodeInfo): Boolean {
        // 1. 直接 CLICK
        if (runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                .getOrDefault(false)
        ) return true

        // 2. 向上找 5 层父节点
        var p = node.parent
        var depth = 0
        while (p != null && depth < 5) {
            val parent = p
            if (runCatching { parent.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                    .getOrDefault(false)
            ) return true
            p = parent.parent
            depth++
        }

        // 3. 模拟点击（注入真实触摸事件）
        val rect = android.graphics.Rect()
        runCatching { node.getBoundsInScreen(rect) }
        if (rect.width() > 0 && rect.height() > 0) {
            val x = rect.centerX().toFloat()
            val y = rect.centerY().toFloat()
            val ok = svc.tapByGesture(x, y)
            Diag.log("tap", "dispatchGesture @(${x.toInt()},${y.toInt()}) ok=$ok")
            return ok
        }
        return false
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) return true
        var p = node.parent
        var depth = 0
        while (p != null && depth < 5) {
            if (runCatching { p!!.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) return true
            p = p.parent
            depth++
        }
        return false
    }

    private fun bfs(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 300) {
            val n = queue.removeFirst()
            visited++
            if (predicate(n)) return n
            for (i in 0 until runCatching { n.childCount }.getOrDefault(0)) {
                runCatching { n.getChild(i) }.getOrNull()?.let { queue.add(it) }
            }
        }
        return null
    }
}

/**
 * 回读 Settings.Secure，确认目标状态在超时内稳定。
 *
 * 只回读设置列表不够：设置里写着"已启用"，但服务进程可能根本没连上
 * （被系统杀过、绑定时失败、权限异常）。那种情况下这里会误判成功，
 * 统计里的"成功"就是这样虚高的，实际用户那边是没声音的。
 *
 * 所以「开启」时除了列表里有，还要等到对应服务实例真的绑定上（bound）。
 * 关掉时不需要等绑定，列表里消失就算成功。
 */
fun pollVerify(ctx: Context, comp: String, want: Boolean, timeoutMs: Long): Boolean {
    val end = android.os.SystemClock.uptimeMillis() + timeoutMs
    while (android.os.SystemClock.uptimeMillis() < end) {
        val listed = SvcRepo.isEnabled(ctx, comp)
        if (listed == want) {
            // 关闭：列表里没了就算完成
            if (!want) return true
            // 开启：还要等到服务实例真的连上，否则设置写了也白写
            if (isBound(comp)) return true
        }
        android.os.SystemClock.sleep(250)
    }
    val listed = SvcRepo.isEnabled(ctx, comp)
    if (listed != want) return false
    return if (want) isBound(comp) else true
}

/**
 * 目标无障碍服务当前是否已真正绑定（进程活着、onServiceConnected 已回调）。
 * 这两个服务在本进程内，直接用它们的静态 instance 判断，不需要跨进程查询。
 *
 * 注意启动竞态：GuardService（前台服务）往往比无障碍服务先起来，
 * 这时 instance 还是 null，但系统马上就会回调 onServiceConnected。
 * 若此时判为「没绑定」，会白白触发一次修复并记一次失败 —— 开机时会连刷十几条。
 * 所以这里在「列表里有它、但实例还没到」时短暂等一下，而不是立刻判死。
 */
fun isBound(comp: String): Boolean {
    val n = SvcRepo.normalize(comp)
    val probe: () -> Any? = when (n) {
        SvcRepo.normalize(GuardA11yService.COMP) -> { { GuardA11yService.instance } }
        SvcRepo.normalize(TapAgentService.COMP) -> { { TapAgentService.instance } }
        // 不是本 App 的服务：只能信设置列表，无法直接观察绑定状态
        else -> return true
    }
    if (probe() != null) return true
    // 启动宽限：最多等 1.5s 让 onServiceConnected 回调到达
    val end = android.os.SystemClock.uptimeMillis() + 1_500L
    while (android.os.SystemClock.uptimeMillis() < end) {
        android.os.SystemClock.sleep(100)
        if (probe() != null) return true
    }
    return probe() != null
}

/**
 * 不等待的快速判断：只在「明确已知已绑定/不属于本包」时返回 true。
 * 用于真正常态巡检（此时服务若真没连上，早该连上了）。
 */
fun isBoundFast(comp: String): Boolean {
    val n = SvcRepo.normalize(comp)
    return when (n) {
        SvcRepo.normalize(GuardA11yService.COMP) -> GuardA11yService.instance != null
        SvcRepo.normalize(TapAgentService.COMP) -> TapAgentService.instance != null
        else -> true
    }
}
