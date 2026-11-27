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
            onTrigger = { handleTrigger() },
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
            Diag.log("backtap", "trigger but target empty（设置里未选择目标服务）")
            Triggers.vibrate(c, 160)
            return
        }
        Diag.log("backtap", "trigger → ${SvcRepo.normalize(target)}")
        Triggers.runAction(c, target, "backtap")
    }
}
