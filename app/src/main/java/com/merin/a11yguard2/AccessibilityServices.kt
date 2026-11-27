package com.merin.a11yguard2

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

object Keepalive {
    const val CHANNEL = "guard_keepalive"
    const val NOTIFY_ID = 1001
    const val ALARM_REQ = 2001
    const val CHECK_REQ = 2002
    const val TASK_REQ = 2003
    const val EXTRA_BOOT = "boot_repair"
    const val EXTRA_CHECK = "self_check"
    const val CHECK_INTERVAL_MS = 15 * 60 * 1000L

    fun start(ctx: Context, boot: Boolean = false) {
        runCatching {
            val i = Intent(ctx, GuardService::class.java)
            if (boot) i.putExtra(EXTRA_BOOT, true)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private fun pending(ctx: Context, req: Int, extra: String, value: Boolean) =
        android.app.PendingIntent.getForegroundService(
            ctx, req, Intent(ctx, GuardService::class.java).putExtra(extra, value),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** 15 分钟一次自检：用 AlarmManager（ColorOS 会冻结 Handler），每次触发后由服务再排下一次。 */
    fun scheduleCheck(ctx: Context) {
        runCatching {
            val prefs = SvcRepo.prefs(ctx)
            val now = SystemClock.elapsedRealtime()
            val armedFor = prefs.getLong("check_armed_at", 0L)
            if (armedFor > now) {
                Diag.log("alarm", "已有自检闹钟，剩 ${(armedFor - now) / 1000}s，不重复排")
                return
            }
            val am = ctx.getSystemService(android.app.AlarmManager::class.java)
            val target = now + CHECK_INTERVAL_MS
            val pi = pending(ctx, CHECK_REQ, EXTRA_CHECK, true)
            // 精确闹钟需要 SCHEDULE_EXACT_ALARM；没有授权就退回 allowWhileIdle（不精确但必达）
            val exact = runCatching {
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, target, pi)
            }.isSuccess
            if (!exact) {
                am.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, target, pi)
            }
            prefs.edit().putLong("check_armed_at", target).apply()
            Diag.log("alarm", "next self-check in 15min exact=$exact")
        }
    }

    /** 任务被移除后 5 秒拉一次（onTaskRemoved）。 */
    fun scheduleTaskRestart(ctx: Context) {
        runCatching {
            val am = ctx.getSystemService(android.app.AlarmManager::class.java)
            am.setAndAllowWhileIdle(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 5_000L,
                pending(ctx, TASK_REQ, EXTRA_CHECK, true)
            )
            Diag.log("alarm", "task removed → restart in 5s")
        }
    }

    /**
     * 服务被销毁后请求补回。
     *
     * 关键：不能立刻修。
     *  1) 系统销毁无障碍服务后往往自己就会重建，抢在它之前写设置反而打断重建；
     *  2) guard 和 tapagent 常常同一时刻一起销毁，会连着触发 4 次 requestRepair，
     *     每次都走一遍 Shizuku（耗时数秒），互相叠加、越修越乱，还刷出一堆失败。
     * 所以这里合并请求：延迟一小段，期间新的请求只更新时间戳，最后只跑一次。
     */
    private val repairLock = Any()
    private var repairAt = 0L
    private var repairThread: Thread? = null

    fun requestRepair(ctx: Context, reason: String) {
        Diag.log("keepalive", "requestRepair reason=$reason")
        Keepalive.start(ctx)
        synchronized(repairLock) {
            val now = SystemClock.uptimeMillis()
            // 已经排上了：只记录，不重复执行
            if (repairThread != null && now < repairAt + REPAIR_DEBOUNCE_MS) {
                Diag.log("keepalive", "debounced reason=$reason")
                repairAt = now
                return
            }
            repairAt = now
            if (repairThread != null) return
            val t = Thread {
                try {
                    // 等系统自己的重建先尝试，避免和它抢
                    while (true) {
                        val left = repairAt + REPAIR_DEBOUNCE_MS - SystemClock.uptimeMillis()
                        if (left <= 0) break
                        Thread.sleep(minOf(left, 500L))
                    }
                    Diag.log("keepalive", "repair now after debounce")
                    SelfHeal.sync(ctx, "debounced")
                } catch (_: Throwable) {
                } finally {
                    synchronized(repairLock) { repairThread = null }
                }
            }
            repairThread = t
            t.start()
        }
    }

    /** 合并窗口：销毁后先等这么久，让系统自己的重建跑完 */
    private const val REPAIR_DEBOUNCE_MS = 3_000L

    /** 只在开机/异常退出时排一次，不自我续期。 */
    fun scheduleBootOnce(ctx: Context) {
        runCatching {
            val am = ctx.getSystemService(android.app.AlarmManager::class.java)
            val pi = android.app.PendingIntent.getForegroundService(
                ctx, ALARM_REQ, Intent(ctx, GuardService::class.java).putExtra(EXTRA_BOOT, true),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            am.setAndAllowWhileIdle(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 30_000L,
                pi
            )
            Diag.log("boot", "single setAndAllowWhileIdle +30s, no rearm")
        }
    }
}

class TapAgentService : AccessibilityService() {

    companion object {
        const val COMP = "com.merin.a11yguard2/com.merin.a11yguard2.TapAgentService"

        @Volatile
        var instance: TapAgentService? = null
            private set
    }

    private var shake: ShakeWatcher? = null
    private var screenReceiver: BroadcastReceiver? = null

    private var upCount = 0
    private var upFirst = 0L      // 手势序列第一下（窗口起点，按"总时长"语义）
    private var upLast = 0L
    private var upRapid = 0       // 快速连按次数（用于"调音量"判定，独立窗口）
    private var upRapidFirst = 0L
    private var downCount = 0
    private var downFirst = 0L
    private var downLast = 0L
    private var downRapid = 0
    private var downRapidFirst = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Diag.init(this)
        instance = this
        Diag.log("tapagent", "connected")
        SvcRepo.prefs(this).edit()
            .putBoolean("tap_ever_on", true)
            .putBoolean("tap_desired_on", true)
            .apply()
        // 助手桥接需要 context 读配置
        AssistantBridge.appContext = applicationContext

        resetGestureCounters()
        BackTapRuntime.acquire(this, BackTapRuntime.OWNER_TAPAGENT)
        registerScreenReceiver()
        // 启动摇一摇监听。
        // 早期这里漏了这行 —— startShake() 只在 reloadShake() 里被调，
        // 而 reloadShake 只有用户去设置页改摇一摇参数时才触发。
        // 结果：开机后摇一摇完全不工作，除非手动开关一次。
        startShake()
    }

    /** 设置页改动摇一摇参数后调用。 */
    fun reloadShake() {
        shake?.stop()
        shake = null
        startShake()
    }

    private fun startShake() {
        shake?.stop()
        shake = null
        val w = ShakeWatcher(this) {
            Triggers.runAction(this, AppSettings.shakeTarget(this), "shake")
        }
        shake = w
        w.start()
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        // 熄屏后可能隔很久才亮屏，旧计数必须先清零，避免亮屏后一次按键就“凑成双击”
                        resetGestureCounters()
                        Triggers.runAction(this@TapAgentService, AppSettings.lockAction(this@TapAgentService), "lock")
                    }
                    Intent.ACTION_SCREEN_ON ->
                        Triggers.runAction(this@TapAgentService, AppSettings.unlockAction(this@TapAgentService), "unlock")
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(r, f)
            }
        }.onFailure { Diag.log("screen", "register failed: ${it.message}") }
        screenReceiver = r
    }

    /**
     * 音量键触发：只走 AccessibilityService.onKeyEvent（唯一零权限路径）。
     * 不使用 MediaSession（需要本应用持有媒体会话并争夺媒体按钮焦点，后台会被抢走），
     * 也不用 Shizuku/root 的 input keyevent 注入（属于静默提权）。
     * 始终 return false，不消费按键 —— 系统音量照常调节。
     *
     * 触发不是「第二下立即执行」，而是进入确认期后再执行：
     * 用户调音量时通常连按 3–5 下，若第二下就触发，几乎每次调音量都会误触发手势。
     * 现在按到第 3 下即判定为调音量并取消；只有恰好两下才在确认期后触发。
     */
    /**
     * 音量键判定。
     *
     * ══ 与「调音量」的冲突，靠三层机制隔离 ══
     *
     * 1. **上下键互斥**：同一序列里混按上/下 = 用户在调音量（想大声又嫌太大声），
     *    两个序列一起作废。真手势不会上下混按。
     *
     * 2. **连按上限**：按到第 4 下判为调音量，整串作废（见 KeyPressLogic.MAX_COMBO）。
     *
     * 3. **确认期**：第 2 下不立即执行，等 win 毫秒看有没有第 3 下。
     *    调音量的人通常会在确认期内继续按第 4 下 → 被第 2 条拦掉。
     *
     * 另外始终 `return false`，不消费按键 —— 系统音量照常调节，
     * 手势只是在"顺手"判断，绝不阻止用户改音量。
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        // 总开关关闭时：不仅不响应，还要清掉可能残留的计数与待触发任务。
        // 否则「双击一下 → 立刻关开关」的情况下，已排队的确认任务仍会延迟执行。
        if (!AppSettings.volKeyOn(this)) {
            if (upCount != 0 || downCount != 0) resetGestureCounters()
            return false
        }
        val now = SystemClock.uptimeMillis()
        val win = AppSettings.volWindowMs(this)

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                // 只要「双击或三击」任一目标有效就响应。
                // 早期版本要求双击目标必须非空，导致「只配了三击」时完全不响应。
                val dbl = AppSettings.volUpTarget(this)
                val triple = AppSettings.volUpTripleTarget(this)
                if ((dbl.isEmpty() || sameComp(dbl)) && (triple.isEmpty() || sameComp(triple)))
                    return false
                // ↓ 互斥：下键序列还在进行中就说明在调音量
                if (downCount > 0 && now - downFirst < KeyPressLogic.VOLUME_WINDOW_MS) {
                    Diag.log("volkey", "上下混按 → 判为调音量，两个序列都作废")
                    resetGestureCounters()
                    return false
                }
                val r = KeyPressLogic.press(
                    upCount, upFirst, upLast, now, win, upRapid, upRapidFirst
                )
                upCount = r.count
                upFirst = r.firstTs
                upLast = r.lastTs
                upRapid = r.rapidCount
                upRapidFirst = r.rapidFirst
                if (r.cancelled) {
                    Diag.log("volkey", "UP 连按达 ${KeyPressLogic.MAX_COMBO} 次 → 判为调音量，取消")
                    cancelPending(Pending.UP)
                } else if (r.pendingFire) {
                    // 等待时间 = 窗口剩余量（保证整串总耗时不超过设定值）
                    scheduleConfirm(Pending.UP, dbl, triple, r.waitMs)
                }
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                val dbl = AppSettings.volDownTarget(this)
                val triple = AppSettings.volDownTripleTarget(this)
                if ((dbl.isEmpty() || sameComp(dbl)) && (triple.isEmpty() || sameComp(triple)))
                    return false
                // ↓ 互斥：上键序列还在进行中就说明在调音量
                if (upCount > 0 && now - upFirst < KeyPressLogic.VOLUME_WINDOW_MS) {
                    Diag.log("volkey", "上下混按 → 判为调音量，两个序列都作废")
                    resetGestureCounters()
                    return false
                }
                val r = KeyPressLogic.press(
                    downCount, downFirst, downLast, now, win, downRapid, downRapidFirst
                )
                downCount = r.count
                downFirst = r.firstTs
                downLast = r.lastTs
                downRapid = r.rapidCount
                downRapidFirst = r.rapidFirst
                if (r.cancelled) {
                    Diag.log("volkey", "DOWN 连按达 ${KeyPressLogic.MAX_COMBO} 次 → 判为调音量，取消")
                    cancelPending(Pending.DOWN)
                } else if (r.pendingFire) {
                    scheduleConfirm(Pending.DOWN, dbl, triple, r.waitMs)
                }
            }
        }
        return false
    }

    /**
     * 音量键开关状态变化时调用（设置页切换开关后）。
     * 关闭时清空计数与待触发任务，确保不会残留一次「幽灵触发」。
     */
    fun onVolKeySwitchChanged() {
        if (!AppSettings.volKeyOn(this)) {
            resetGestureCounters()
            Diag.log("volkey", "开关关闭 → 已清空计数与待触发")
        }
    }

    /** 待确认的触发起源。 */
    private enum class Pending { UP, DOWN }

    private val pendingRunnable = HashMap<Pending, Runnable>()

    /**
     * 安排一次「确认期后判定」。
     *
     * 到期时读取当时的 count：
     *   2 → 执行双击目标
     *   3 → 执行三击目标（若未配置三击目标则退回双击目标，避免「按三下没反应」）
     */
    private fun scheduleConfirm(
        which: Pending,
        dblTarget: String,
        tripleTarget: String,
        waitMs: Long
    ) {
        cancelPending(which)
        val r = Runnable {
            pendingRunnable.remove(which)
            val cur = if (which == Pending.UP) {
                KeyPressLogic.Result(upCount, upFirst, upLast, true, false, 0, upRapid, upRapidFirst)
            } else {
                KeyPressLogic.Result(downCount, downFirst, downLast, true, false, 0, downRapid, downRapidFirst)
            }
            val taps = KeyPressLogic.resolve(cur)
            if (which == Pending.UP) { upCount = 0; upRapid = 0; upRapidFirst = 0L }
            else { downCount = 0; downRapid = 0; downRapidFirst = 0L }
            if (taps == 0) return@Runnable

            // 选目标：三击优先用三击目标，回落到双击；双击则用双击目标，回落到三击
            val target = if (taps == 3) {
                if (tripleTarget.isNotEmpty() && !sameComp(tripleTarget)) tripleTarget
                else if (dblTarget.isNotEmpty() && !sameComp(dblTarget)) dblTarget
                else ""
            } else {
                if (dblTarget.isNotEmpty() && !sameComp(dblTarget)) dblTarget
                else if (tripleTarget.isNotEmpty() && !sameComp(tripleTarget)) tripleTarget
                else ""
            }
            if (target.isEmpty()) {
                Diag.log("volkey", "${taps}连 但没有可用目标")
                return@Runnable
            }
            val src = "vol" + (if (which == Pending.UP) "up" else "down")
            Diag.log("volkey", "${taps}连 ${if (which == Pending.UP) "UP" else "DOWN"} → $target")
            Triggers.runAction(this, target, src)
        }
        pendingRunnable[which] = r
        // 只等「窗口剩余时间」：这样整串手势的总耗时严格不超过用户设定的窗口值
        mainHandler.postDelayed(r, waitMs)
    }

    private fun cancelPending(which: Pending) {
        pendingRunnable.remove(which)?.let { mainHandler.removeCallbacks(it) }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 服务重连 / 熄屏 / 销毁时清零，确保复活不会带着旧计数误触发。 */
    private fun resetGestureCounters() {
        upCount = 0
        upFirst = 0L
        upLast = 0L
        upRapid = 0
        upRapidFirst = 0L
        downCount = 0
        downFirst = 0L
        downLast = 0L
        downRapid = 0
        downRapidFirst = 0L
        cancelPending(Pending.UP)
        cancelPending(Pending.DOWN)
    }

    private fun sameComp(target: String): Boolean =
        SvcRepo.normalize(target) == SvcRepo.normalize(COMP)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // ── 助手界面触发 ──
        //
        // Android 不允许第三方拿助手的语音识别结果（安全边界）。
        // 但助手必须把结果显示给用户看，无障碍服务能读到界面上出现的文字。
        // 于是：用户对助手说「关闭解说」→ 助手显示文字 → 本服务读到 → 执行。
        //
        // 只在配置的助手包名里生效，否则全网文字都会被扫，误触发且耗电。
        if (event == null) return
        AssistantBridge.appContext = applicationContext
        AssistantBridge.onEvent(
            event,
            AppSettings.assistantBridgeOn(this),
            AppSettings.assistantPkgs(this),
            windowText = {
                // 兜底：事件文字没命中时，读整个活动窗口的文字。
                // 助手把识别结果显示在对话框/列表项里时，event.text 是空的。
                runCatching {
                    AssistantBridge.collectText(rootInActiveWindow)
                }.getOrDefault("")
            }
        ) { keyword, action ->
            Diag.log("assist", "命中「$keyword」→ $action")
            Triggers.runAction(this, action, "assist")
        }
    }

    override fun onInterrupt() {
        resetGestureCounters()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Diag.log("tapagent", "unbind")
        release()
        Keepalive.requestRepair(this, "tapagent_unbind")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        release()
        Diag.log("tapagent", "onDestroy -> requestRepair")
        Keepalive.requestRepair(this, "tapagent_destroy")
        super.onDestroy()
    }

    private fun release() {
        instance = null
        resetGestureCounters()
        BackTapRuntime.release(this, BackTapRuntime.OWNER_TAPAGENT)
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
    }
}

class GuardA11yService : AccessibilityService() {
    companion object {
        const val COMP = "com.merin.a11yguard2/com.merin.a11yguard2.GuardA11yService"

        @Volatile
        var instance: GuardA11yService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Diag.init(this)
        instance = this
        Diag.log("guard", "connected")
        if (SvcRepo.prefs(this).getBoolean("desired_on", false)) Keepalive.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // ── 助手界面触发 ──
        //
        // Android 不允许第三方拿助手的语音识别结果（安全边界）。
        // 但助手必须把结果显示给用户看，无障碍服务能读到界面上出现的文字。
        // 于是：用户对助手说「关闭解说」→ 助手显示文字 → 本服务读到 → 执行。
        //
        // 只在配置的助手包名里生效，否则全网文字都会被扫，误触发且耗电。
        if (event == null) return
        AssistantBridge.appContext = applicationContext
        AssistantBridge.onEvent(
            event,
            AppSettings.assistantBridgeOn(this),
            AppSettings.assistantPkgs(this),
            windowText = {
                // 兜底：事件文字没命中时，读整个活动窗口的文字。
                // 助手把识别结果显示在对话框/列表项里时，event.text 是空的。
                runCatching {
                    AssistantBridge.collectText(rootInActiveWindow)
                }.getOrDefault("")
            }
        ) { keyword, action ->
            Diag.log("assist", "命中「$keyword」→ $action")
            Triggers.runAction(this, action, "assist")
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Diag.log("guard", "unbind -> requestRepair")
        Keepalive.requestRepair(this, "guard_unbind")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        Diag.log("guard", "onDestroy -> requestRepair")
        Keepalive.requestRepair(this, "guard_destroy")
        super.onDestroy()
    }
}

/**
 * 唯一补回入口：按用户意图（desired_on / 加锁项）把缺失的服务写回 enabled_accessibility_services。
 * 打开 App、服务 unbind/onDestroy、15 分钟自检、开机都走这里。
 */
object SelfHeal {
    /** 返回补回成功的条数；只补缺失项，不动其它服务条目。 */
    fun sync(ctx: Context, reason: String): Int {
        val prefs = SvcRepo.prefs(ctx)
        val locked = AppSettings.lockedComps(ctx)
        val want = buildList {
            if (prefs.getBoolean("desired_on", false)) {
                add(GuardA11yService.COMP)
                if (AppSettings.tapDesiredOn(ctx)) add(TapAgentService.COMP)
            }
            addAll(locked)
        }.map { SvcRepo.normalize(it) }.distinct().filter { !SvcRepo.isEnabled(ctx, it) }

        if (want.isEmpty()) {
            Diag.log("assess", "reason=$reason 无需补回")
            return 0
        }
        var ok = 0
        want.forEach { comp ->
            val res = ToggleRouter.toggle(ctx, comp, true)
            if (res.ok) ok++
            Diag.log("keep", "restore reason=$reason target=$comp ok=${res.ok}")
            if (locked.contains(comp)) AppSettings.markLockBroken(ctx, comp, !res.ok)
        }
        AppSettings.markHeal(ctx, reason, "$ok/${want.size}")
        Diag.log("assess", "reason=$reason restored=$ok/${want.size}")
        return ok
    }
}
