package com.merin.a11yguard2

import android.content.Context

/**
 * 背面敲击的进程级托管：同一个进程里可能同时存在「点按代理」无障碍服务和保活前台服务，
 * 两边都通过 acquire/release 引用计数共享同一个 [BackTapWatcher]，避免重复注册传感器导致一次敲击触发两次。
 *
 * owners：
 *  - "tapagent"  TapAgentService 连接期间
 *  - "fgs"       GuardService 存活期间
 *  - "app-test"  手势页测试区展开期间（即使两个服务都没开也能试手感）
 */
object BackTapRuntime {

    const val OWNER_TAPAGENT = "tapagent"
    const val OWNER_FGS = "fgs"
    const val OWNER_TEST = "app-test"

    private val lock = Any()
    private val owners = HashSet<String>()
    private var watcher: BackTapWatcher? = null
    private var ctxRef: Context? = null

    /** 手势页测试区回调；离开页面或 Activity 暂停时必须置空，避免持有已销毁的 View。 */
    @Volatile
    var testListener: ((BackTapLogic.TapEvent) -> Unit)? = null

    @Volatile
    var lastEvent: BackTapLogic.TapEvent? = null

    @Volatile
    var running: Boolean = false
        private set

    fun acquire(ctx: Context, owner: String) {
        synchronized(lock) {
            ctxRef = ctx.applicationContext
            val added = owners.add(owner)
            ensureWatcher()
            if (added) syncState()
        }
    }

    fun release(ctx: Context, owner: String) {
        synchronized(lock) {
            ctxRef = ctx.applicationContext
            if (!owners.remove(owner)) return
            syncState()
        }
    }

    /** 设置页改动（开关/次数/灵敏度/熄屏策略/震动）后调用，立即生效。 */
    fun reload() {
        synchronized(lock) {
            watcher?.reload()
            syncState()
        }
    }

    fun statusText(ctx: Context): String = when {
        !AppSettings.backTapOn(ctx) -> "已关闭"
        running -> "监听中"
        else -> "未监听（保活服务与点按代理均未运行）"
    }

    private fun ensureWatcher() {
        if (watcher != null) return
        val c = ctxRef ?: return
        watcher = BackTapWatcher(
            c,
            onTrigger = { ev -> handleTrigger(ev) },
            onEvent = { ev ->
                lastEvent = ev
                testListener?.invoke(ev)
            }
        )
    }

    private fun syncState() {
        val c = ctxRef ?: return
        val w = watcher ?: return
        if (AppSettings.backTapOn(c) && owners.isNotEmpty()) {
            w.start()
        } else {
            w.stop()
        }
        running = w.isRunning()
    }

    private fun handleTrigger() {
        val c = ctxRef ?: return
        val target = AppSettings.backTapTarget(c)
        if (target.isEmpty()) {
            // 不再震动：震动职责已经完全交给 BackTapWatcher，
            // 这里再震会与它的震动叠加，听感是一次短震 + 一声长嗡（像两次）。
            Diag.log("backtap", "trigger but target empty（设置里未选择目标服务）")
            return
        }
        Diag.log("backtap", "trigger → ${SvcRepo.normalize(target)}")
        Triggers.runAction(c, target, "backtap")
    }

    /**
     * 处理一次触发事件：按 taps 选择双击或三击目标。
     *
     * 三击目标未配置时退回双击目标 —— 否则用户敲三下会完全没反应，
     * 那比「两下三下切同一个服务」更让人困惑。
     */
    private fun handleTrigger(ev: BackTapLogic.TapEvent) {
        val c = ctxRef ?: return
        val dbl = AppSettings.backTapTarget(c)
        val triple = AppSettings.backTapTripleTarget(c)

        // 任一目标非空即可响应：早期版本要求双击目标必须存在，
        // 导致「只配了三击」时敲击识别成功却什么都不做。
        val target = when {
            ev.taps >= 3 && triple.isNotEmpty() -> triple
            dbl.isNotEmpty() -> dbl
            // 双击目标没配、但三击配了 → 双击也走三击目标（避免"没反应"）
            triple.isNotEmpty() -> triple
            else -> ""
        }
        if (target.isEmpty()) {
            // 同上：不震，避免与 Watcher 的震动叠加
            Diag.log("backtap", "trigger but no target（设置里未选择目标服务）")
            return
        }
        val kind = if (ev.taps >= 3) "三击" else "双击"
        Diag.log("backtap", "$kind → ${SvcRepo.normalize(target)}")
        Triggers.runAction(c, target, "backtap")
    }
}
