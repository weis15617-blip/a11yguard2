package com.merin.a11yguard2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 前台服务：事件驱动（监听 enabled_accessibility_services 变化 + 无障碍服务断开回调），
 * 不做空转轮询；只有在“期望开启”且实际被关闭时才尝试一次修复。
 */
class GuardService : Service() {

    private var observer: ContentObserver? = null
    private var tickScheduled = false
    private val bootRepairRunning = AtomicBoolean(false)

    /** 本次补回的原因，写进 [keep] restore 日志与自愈台账。 */
    @Volatile
    private var healReason: String = "unknown"

    override fun onCreate() {
        super.onCreate()
        // 只记录异常（默认开）：必须先设置过滤开关，再 init，
        // 否则 init 之后、赋值之前产生的日志会绕过过滤写进去。
        Diag.onlyAnomalies = AppSettings.logOnlyAnomalies(this)
        Diag.init(this)
        createChannel()
        // 统计启动次数；如果是「上次没走 onDestroy 就没了」则额外算一次重启
        runCatching {
            AppSettings.bumpStart(this)
            val sp = SvcRepo.prefs(this)
            val wasClean = sp.getBoolean("clean_exit", false)
            // 开机自启、覆盖安装都会重启进程，但那不是「异常退出」。
            // 只有在同一次开机周期内、上次又没走 onDestroy，才说明是被系统杀掉的。
            val bootId = readBootId()
            val lastBootId = sp.getString("last_boot_id", null)
            val sameBoot = lastBootId != null && lastBootId == bootId
            // 覆盖安装（adb install / 应用商店更新）也会强杀进程、也不走 onDestroy，
            // 但那不是「被系统杀死」。用 APK 的安装时间区分：上次运行时间早于本次安装时间，
            // 说明上次是被安装打断的，不计入重启。
            val nowWall = System.currentTimeMillis()
            val lastRunWall = sp.getLong("last_run_wall", 0L)
            val lastInstall = runCatching {
                packageManager.getPackageInfo(packageName, 0).lastUpdateTime
            }.getOrDefault(0L)
            val killedByInstall = lastInstall > 0 && lastRunWall > 0 && lastRunWall < lastInstall
            if (!wasClean && sameBoot && !killedByInstall && sp.getInt("stat_start", 1) > 1) {
                AppSettings.bumpRestart(this)
                Diag.log("fgs", "检测到被系统杀死后重启（同一开机周期），重启数 +1")
            } else if (killedByInstall) {
                Diag.log("fgs", "上次是本版本安装打断的，不计重启")
            }
            sp.edit()
                .putBoolean("clean_exit", false)
                .putString("last_boot_id", bootId)
                .putLong("last_run_wall", nowWall)
                .apply()
        }
        goForeground()
        BackTapRuntime.acquire(this, BackTapRuntime.OWNER_FGS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        val boot = intent?.getBooleanExtra(Keepalive.EXTRA_BOOT, false) == true
        val check = intent?.getBooleanExtra(Keepalive.EXTRA_CHECK, false) == true
        Diag.log("fgs", "onStartCommand startId=$startId boot=$boot check=$check")
        observe()
        // TalkBack 占位与守护/加锁无关：开关打开时只做一次对齐，关闭时由打开 App 的 reconcile 清理残留
        if (TalkBackPlace.isOn(this)) {
            Thread { TalkBackPlace.reconcile(this, "fgs") }.start()
        }
        when {
            boot -> {
                healReason = "boot"
                startBootRepair()
            }
            check -> {
                healReason = "selfcheck"
                selfCheck()
            }
            else -> {
                healReason = "app-start"
                startOnceIfNeeded()
            }
        }
        scheduleTick()
        Keepalive.scheduleCheck(this)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Diag.log("fgs", "onTaskRemoved → AlarmManager +5s")
        Keepalive.scheduleTaskRestart(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        BackTapRuntime.release(this, BackTapRuntime.OWNER_FGS)
        observer?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        observer = null
        val n = SvcRepo.prefs(this).getInt("destroy_count", 0) + 1
        // 标记「干净退出」：下次 onCreate 看到这个标记就不再算一次异常重启
        SvcRepo.prefs(this).edit()
            .putInt("destroy_count", n)
            .putBoolean("clean_exit", true)
            .apply()
        Diag.log("fgs", "onDestroy 第 $n 次（START_STICKY + alarm 兜底）")
        super.onDestroy()
    }

    /** 15 分钟定时自检：flag / 两条核心服务 / 绑定状态，缺了就补；全部写 diag.log。 */
    private fun selfCheck() {
        Thread {
            val flag = secureEnabledFlag()
            val has = SvcRepo.enabledComps(this)
            val needTap = AppSettings.tapDesiredOn(this)
            val boundGuard = GuardA11yService.instance != null
            val boundTap = !needTap || TapAgentService.instance != null
            val coreOk = has.containsAll(coreComps())
            Diag.log(
                "selfcheck",
                "flag=$flag coreOk=$coreOk boundGuard=$boundGuard boundTap=$boundTap list=${has.sorted()}"
            )
            val ok = repairMissingSync()
            AppSettings.markHeal(this, "selfcheck", if (ok) "成功" else "部分失败")
            Diag.log("selfcheck", "repair=$ok")
        }.start()
    }

    private fun goForeground() {
        val text = if (AppSettings.statsNotify(this)) {
            // 显示各项计数：成功/失败/启动/重启/拉活。
            // 这些都是「实际发生过的事」，不是推测值，所以能直接看出守护干没干活。
            AppSettings.statsLine(this)
        } else {
            "保活运行中（事件驱动）"
        }
        val builder = Notification.Builder(this, Keepalive.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("无障碍守护")
            .setContentText(text)
            .setOngoing(true)
        // 点通知直接进 App 主页，方便查看诊断和设置
        runCatching {
            val i = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pi = android.app.PendingIntent.getActivity(
                this, 0, i,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.setContentIntent(pi)
        }
        val n = builder.build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Keepalive.NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Keepalive.NOTIFY_ID, n)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(Keepalive.CHANNEL, "守护保活", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    private fun observe() {
        if (observer != null) return
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                Diag.log("fgs", "settings 变化 → 评估")
                healReason = "settings-change"
                assess()
            }
        }
        observer = o
        runCatching {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                o
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            tickScheduled = false
            assess()
            scheduleTick()
        }
    }

    private fun startOnceIfNeeded() {
        handler.postDelayed({ assess() }, 1500L)
    }

    /**
     * 开机路径：不依赖用户打开 App，也不依赖主线程 Handler（ColorOS 后台冻结时 Handler 可能不跑）。
     * 在后台线程按 0s / 5s / 15s / 40s 共评估 4 次，直到“两条服务都在 enabled 列表 + accessibility_enabled=1 + 两个服务已绑定”。
     */
    private fun startBootRepair() {
        if (!bootRepairRunning.compareAndSet(false, true)) return
        Thread {
            val offsets = longArrayOf(0L, 5_000L, 15_000L, 40_000L)
            var elapsed = 0L
            for ((i, target) in offsets.withIndex()) {
                val wait = target - elapsed
                if (wait > 0) runCatching { Thread.sleep(wait) }
                elapsed = target
                // 开机早期通道（Shizuku / 无障碍）常常还没就绪。此时 repairMissingSync
                // 必然全部 route=none，既刷失败数又浪费唤醒。探测一次，没通道就只等不修。
                if (!hasUsableRoute()) {
                    Diag.log("bootrepair", "attempt=${i + 1} t=${target}ms skip: no-route yet")
                    continue
                }
                val ok = repairMissingSync()
                val flag = secureEnabledFlag()
                val needTap = AppSettings.tapDesiredOn(this)
                val boundGuard = GuardA11yService.instance != null
                val boundTap = !needTap || TapAgentService.instance != null
                Diag.log(
                    "bootrepair",
                    "attempt=${i + 1} t=${target}ms verify=$ok flag=$flag boundGuard=$boundGuard " +
                        "boundTap=$boundTap list=${SvcRepo.enabledComps(this).sorted()}"
                )
                if (ok && flag == 1 && boundGuard && boundTap) {
                    Diag.log("bootrepair", "success attempt=${i + 1} t=${target}ms")
                    bootRepairRunning.set(false)
                    return@Thread
                }
            }
            bootRepairRunning.set(false)
            Diag.log("bootrepair", "give up attempts=${offsets.size}")
        }.start()
    }

    /** 读系统开机 ID：同一次开机内不变，重启后会变 */
    private fun readBootId(): String = runCatching {
        java.io.File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrDefault("")

    private fun secureEnabledFlag(): Int = runCatching {
        Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
    }.getOrDefault(0)

    /** 成功判据只认这两条核心服务。 */
    private fun coreComps(): List<String> = buildList {
        add(GuardA11yService.COMP)
        if (AppSettings.tapDesiredOn(this@GuardService)) add(TapAgentService.COMP)
    }.map { SvcRepo.normalize(it) }

    private fun requiredComps(): List<String> = buildList {
        add(GuardA11yService.COMP)
        if (AppSettings.tapDesiredOn(this@GuardService)) add(TapAgentService.COMP)
        addAll(
            LegacyCodec.split(
                SvcRepo.prefs(this@GuardService).getString("locked_comps", "").orEmpty()
            )
        )
    }.map { SvcRepo.normalize(it) }.distinct()

    /** 同步补齐缺失项，并回读 Settings + 绑定状态判定是否达成。加锁项即使守护关闭也照样补回。 */
    private fun repairMissingSync(): Boolean {
        val locked = AppSettings.lockedComps(this)
        val missingLocked = locked.filter { needsRepair(it) }
        if (!SvcRepo.prefs(this).getBoolean("desired_on", false)) {
            if (missingLocked.isEmpty()) {
                Diag.log("bootrepair", "desired_on=false，跳过")
                return false
            }
            // 守护本身关了，但用户加锁的服务必须保持开启
            missingLocked.forEach { comp -> restoreOne(comp, locked = true) }
            return false
        }
        requiredComps().filter { needsRepair(it) }.forEach { comp ->
            restoreOne(comp, locked = locked.contains(SvcRepo.normalize(comp)))
        }
        val enabled = SvcRepo.enabledComps(this)
        // 成功判据：无障碍总开关开着 + 核心服务都在列表里 + 它们真的连上了。
        // 少了最后一项，进程被杀但列表还在时会误判成功，统计就虚高。
        val ok = secureEnabledFlag() == 1 &&
            enabled.containsAll(coreComps()) &&
            coreComps().all { isBound(it) }
        val n = SvcRepo.prefs(this).getInt("heal_fail", 0)
        if (ok) {
            if (n > 0) SvcRepo.prefs(this).edit().putInt("heal_fail", 0).apply()
        } else {
            val next = n + 1
            SvcRepo.prefs(this).edit().putInt("heal_fail", next).apply()
            Diag.log("heal", "连续失败 n=$next")
            // 连续 3 次失败：全量重评估一次（重写 flag + 逐项补），并清零计数，避免无限重启循环
            if (next >= 3) {
                runCatching {
                    Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                }
                requiredComps().forEach { comp ->
                    if (!SvcRepo.isEnabled(this, comp)) {
                        restoreOne(comp, locked = AppSettings.isLocked(this, comp))
                    }
                }
                SvcRepo.prefs(this).edit().putInt("heal_fail", 0).apply()
                Diag.log("heal", "全量重评估完成（第 $next 次失败触发，计数清零）")
            }
        }
        return ok
    }

    /**
     * 当前是否有任何可用的执行通道（secure 直写 / shizuku / tapAgent）。
     *
     * 与 ToggleRouter.toggle 内部的 order 判断同源，但只做探测、不写设置，
     * 成本很低。用于在开机早期把「通道还没起来」和「通道在但修不好」区分开：
     * 前者不该计失败，也不该反复重试刷屏。
     */
    private fun hasUsableRoute(): Boolean = runCatching {
        val order = RouteLogic.order(
            ToggleRouter.mode(this),
            ToggleRouter.shizukuStatus(this),
            ToggleRouter.tapAvailable(),
            ToggleRouter.secureAvailable(this)
        )
        order.isNotEmpty()
    }.getOrDefault(false)

    /** 补回单个服务；加锁项额外记 [lock] restore / restore-fail，并维护“⚠ 无法保持”名单。 */
    private fun restoreOne(comp: String, locked: Boolean): Boolean {
        // 前置确认：列表里已经有它、而且服务确实连上了，就不必再写一次设置。
        // 这一步专门处理启动竞态——GuardService 常比无障碍服务先起来，
        // 巡检时 instance 还没赋值，误判为"没绑定"就去修，修完服务自己也连上了，
        // 于是每次都记一次失败。开机时这种假失败能刷十几条。
        if (SvcRepo.isEnabled(this, comp) && isBound(comp)) {
            Diag.log("keep", "skip already-bound target=${SvcRepo.normalize(comp)}")
            AppSettings.bumpStat(this, true)
            return true
        }
        // 开机早期常见：系统刚起来，Shizuku 还没拉起、无障碍通道尚未就绪，
        // 这时候任何修复都必然返回 route=none。原来照样调 toggle 并记一次失败，
        // 导致「手机重启后失败数暴涨」（实测 06:52 重启后 8 分钟内累积 67 次）。
        // 改成先探测通道，没有可用通道就安静跳过，把机会留给之后的自检。
        if (!hasUsableRoute()) {
            Diag.log("keep", "skip no-route target=${SvcRepo.normalize(comp)}")
            return false
        }
        // 连续失败要退避：同一个目标一直修不好时，1~2 秒就重试一次会形成风暴
        // （实测 30 分钟内把失败数从 89 刷到 447，同时让系统反复重绑、把服务标记成 crashed）。
        // 失败次数越多，下次尝试间隔越长，最多 5 分钟。
        val now = SystemClock.uptimeMillis()
        val failCount = consecutiveFails[comp] ?: 0
        if (failCount > 0) {
            val backoff = minOf(BACKOFF_BASE_MS shl (failCount - 1), BACKOFF_MAX_MS)
            val lastTry = lastTryAt[comp] ?: 0L
            if (now - lastTry < backoff) {
                Diag.log("keep", "skip backoff target=${SvcRepo.normalize(comp)} fail=$failCount wait=${backoff}ms")
                return false
            }
        }
        lastTryAt[comp] = now
        Diag.log("fgs", "修复 $comp")
        val res = ToggleRouter.toggle(this, comp, true)
        AppSettings.bumpStat(this, res.ok)
        // 只有真的执行了补回并且成功，才算一次「拉活」
        if (res.ok) {
            AppSettings.bumpRevive(this)
            consecutiveFails.remove(comp)
        } else {
            consecutiveFails[comp] = (consecutiveFails[comp] ?: 0) + 1
        }
        Diag.log("fgs", "repair ${res.logLine()}")
        if (locked) {
            if (res.ok) {
                AppSettings.markLockBroken(this, comp, false)
                Diag.log("lock", "restore target=${SvcRepo.normalize(comp)}")
            } else {
                AppSettings.markLockBroken(this, comp, true)
                Diag.log("lock", "restore-fail target=${SvcRepo.normalize(comp)} reason=${res.reason}")
            }
        }
        Diag.log("keep", "restore reason=$healReason target=${SvcRepo.normalize(comp)} ok=${res.ok}")
        return res.ok
    }

    /** 只在本服务存活期间做定时；不写 AlarmManager，因此不会出现“自我续期”的闹钟。 */
    private fun scheduleTick() {
        if (tickScheduled) return
        val next = when {
            AppSettings.fastGuard(this) -> 5_000L
            AppSettings.repairIntervalMs(this) > 0 -> AppSettings.repairIntervalMs(this)
            else -> 0L
        }
        if (next <= 0) return
        tickScheduled = true
        handler.postDelayed(tick, next)
    }

    private fun assess() {
        val desired = SvcRepo.prefs(this).getBoolean("desired_on", false)
        val locked = AppSettings.lockedComps(this)
        val want = buildList {
            if (desired) {
                add(GuardA11yService.COMP)
                if (AppSettings.tapDesiredOn(this@GuardService)) add(TapAgentService.COMP)
            }
            // 加锁项：即使守护关闭也立即补回（用户明确要求“加锁=不许被关”）
            addAll(locked)
        }.map { SvcRepo.normalize(it) }.distinct()
            .filter { needsRepair(it) }
        if (want.isEmpty()) return
        // 同一个服务在短时间内不重复修：写 enabled_accessibility_services 会让系统重新
        // 绑定服务，绑定过程本身又会触发 unbind/connect 回调 → 再评估 → 再修，
        // 形成抖动。这里按服务做 2 秒节流，把这类自我触发的重入挡掉。
        val now = SystemClock.uptimeMillis()
        val todo = want.filter { comp ->
            val last = lastRepairAt[comp] ?: 0L
            if (now - last < REPAIR_THROTTLE_MS) {
                Diag.log("assess", "skip throttled target=${SvcRepo.normalize(comp)}")
                false
            } else {
                lastRepairAt[comp] = now
                true
            }
        }
        if (todo.isEmpty()) return
        Thread {
            todo.forEach { comp -> restoreOne(comp, locked = locked.contains(comp)) }
            handler.post { goForeground() }
        }.start()
    }

    /** 每个目标上次真正执行修复的时刻，用于节流 */
    private val lastRepairAt = HashMap<String, Long>()

    /** 每个目标的连续失败次数，用于指数退避 */
    private val consecutiveFails = HashMap<String, Int>()
    private val lastTryAt = HashMap<String, Long>()

    private companion object {
        /** 同一目标两次修复的最小间隔 */
        const val REPAIR_THROTTLE_MS = 2_000L

        /** 退避基数：第 n 次连续失败等 BACKOFF_BASE shl (n-1) 毫秒 */
        const val BACKOFF_BASE_MS = 10_000L

        /** 退避上限 5 分钟 */
        const val BACKOFF_MAX_MS = 300_000L
    }

    /**
     * 这个服务是否需要补。
     *
     * 原来的判断只有「设置列表里没有它」。但服务进程被系统杀掉以后，
     * 名字还留在 enabled_accessibility_services 里，于是这里判定为正常、
     * 永远不会去拉活 —— 这正是「通知显示失败远多于成功」的原因之一。
     *
     * 现在两种都算需要补：
     *   1) 设置列表里没有
     *   2) 列表里有，但服务实例没连上（进程已死或绑定失败）
     *
     * 这里用不等候的快速判断：本方法在主线程巡检里被频繁调用，
     * 不能为了等绑定回调而阻塞。真正的「启动竞态」由 restoreOne 前的
     * 一次短等待处理（见 isBound 的宽限逻辑），避免开机时刷一串假失败。
     */
    private fun needsRepair(comp: String): Boolean {
        if (!SvcRepo.isEnabled(this, comp)) return true
        return !isBoundFast(comp)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
