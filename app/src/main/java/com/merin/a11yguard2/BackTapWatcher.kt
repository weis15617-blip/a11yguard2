package com.merin.a11yguard2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * 背面敲击监听。
 *
 * 采样率：亮屏 200Hz（5ms），熄屏且用户允许继续检测时 100Hz（10ms）。
 * Android 12+ 只有“高于 200Hz”才需要 HIGH_SAMPLING_RATE_SENSORS，200Hz 恰好不需要该权限。
 *
 * 功耗：传感器本身是 Non-wakeup 类型，设备进入深度睡眠后不会被它唤醒；
 * 因此“熄屏继续检测”需要额外持有 PARTIAL_WAKE_LOCK，这是该功能的真实代价，
 * 由设置项 backtap_screen_off 控制，默认关闭。
 */
class BackTapWatcher(
    private val ctx: Context,
    private val onTrigger: (BackTapLogic.TapEvent) -> Unit,
    private val onEvent: (BackTapLogic.TapEvent) -> Unit
) : SensorEventListener {

    companion object {
        private const val PERIOD_SCREEN_ON_US = 5_000      // 200 Hz
        private const val PERIOD_SCREEN_OFF_US = 10_000    // 100 Hz
        private const val WAKE_TAG = "a11yguard2:backtap"
    }

    private val sm: SensorManager? = ctx.getSystemService(SensorManager::class.java)
    private val power: PowerManager? = ctx.getSystemService(PowerManager::class.java)
    private val detector = BackTapLogic.Detector()

    private var registered = false
    private var currentPeriodUs = 0

    /** 陀螺仪（用于否决"推/碰"，参考 Google Columbus 的双传感器设计）。 */
    private var gyro: Sensor? = null
    private var gyroRegistered = false

    /** 最近一次角速度模长（rad/s），由陀螺仪回调写入。 */
    @Volatile
    private var lastGyroMag = 0f
    private var screenReceiver: BroadcastReceiver? = null
    private var screenOn = true
    private var allowScreenOff = false
    private var wakeLock: PowerManager.WakeLock? = null

    private var vibMs = 35

    /** 诊断：距离上次打日志累计的采样数。 */
    private var debugSamples = 0

    fun reload() {
        val sp = AppSettings
        val params = BackTapLogic.paramsForSensitivity(sp.backTapSensitivity(ctx))
        // 三击目标非空 → 启用双模式（敲 2 下先等一拍，看是否要敲第 3 下）
        detector.configure(params, sp.backTapCount(ctx), sp.backTapDualMode(ctx))
        vibMs = sp.sensorVibMs(ctx)
        val allow = sp.backTapScreenOff(ctx)
        val changed = allow != allowScreenOff
        allowScreenOff = allow
        if (changed) applyRegistration()
        Diag.log(
            "backtap",
            "reload lv=${sp.backTapSensitivity(ctx)} noise=%.3f win=%d need=%d off=%s"
                .format(params.minNoise, params.peakWindow, sp.backTapCount(ctx), allow)
        )
    }

    fun start() {
        reload()
        registerScreenReceiver()
        applyRegistration()
        Diag.log("backtap", "start registered=$registered period=${currentPeriodUs}us")
    }

    fun stop() {
        unregister()
        screenReceiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        screenReceiver = null
        releaseWakeLock()
        detector.reset()
        Diag.log("backtap", "stop")
    }

    fun isRunning(): Boolean = registered

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        screenOn = true
                        detector.reset()
                        applyRegistration()
                        Diag.log("backtap", "screen on → period=${currentPeriodUs}us")
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        screenOn = false
                        detector.reset()
                        applyRegistration()
                        Diag.log("backtap", "screen off → period=${currentPeriodUs}us off=${allowScreenOff}")
                    }
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                ctx.registerReceiver(r, f)
            }
        }.onFailure { Diag.log("backtap", "screen receiver failed: ${it.message}") }
        screenReceiver = r
    }

    private fun applyRegistration() {
        // ⚠️ 必须先检查总开关：早期版本只看 screenOn/allowScreenOff，
        // 导致用户关掉「背面敲击」后传感器仍在注册、敲击仍会触发服务切换。
        // 这是状态泄漏，必须在注册前拦住。
        if (!AppSettings.backTapOn(ctx)) {
            unregister()
            releaseWakeLock()
            return
        }
        val want = screenOn || allowScreenOff
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (!want || sensor == null) {
            unregister()
            releaseWakeLock()
            if (sensor == null) Diag.log("backtap", "no accelerometer")
            return
        }
        val period = if (screenOn) PERIOD_SCREEN_ON_US else PERIOD_SCREEN_OFF_US
        if (registered && period == currentPeriodUs) {
            if (!screenOn && allowScreenOff) acquireWakeLock() else releaseWakeLock()
            return
        }
        unregister()
        registered = runCatching {
            sm?.registerListener(this, sensor, period) ?: false
        }.getOrDefault(false)
        currentPeriodUs = if (registered) period else 0

        // 陀螺仪：与加速度计同时注册（Google Columbus 的做法）。
        // 它只用于"否决"，不参与置信度计算，所以采样率可以低一些。
        registerGyro(period)

        if (!screenOn && allowScreenOff) acquireWakeLock() else releaseWakeLock()
    }

    /**
     * 注册陀螺仪。
     *
     * 有则启用角速度否决，没有则静默降级（gyroAvailable=false，
     * 判定逻辑自动跳过否决），不会影响基本功能。
     */
    private fun registerGyro(periodUs: Int) {
        if (gyroRegistered) return
        val g = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: run {
            detector.setGyroAvailable(false)
            Diag.log("backtap", "无陀螺仪 → 跳过角速度否决（仅用加速度计）")
            return
        }
        gyro = g
        gyroRegistered = runCatching {
            sm?.registerListener(this, g, periodUs) ?: false
        }.getOrDefault(false)
        detector.setGyroAvailable(gyroRegistered)
        Diag.log("backtap", "陀螺仪 registered=$gyroRegistered（Columbus 算法不需要它，仅诊断）")
    }

    private fun unregister() {
        if (!registered && !gyroRegistered) return
        // 同一个 SensorEventListener 注册了两个传感器，一次注销即可全部移除
        runCatching { sm?.unregisterListener(this) }
        registered = false
        gyroRegistered = false
        currentPeriodUs = 0
        lastGyroMag = 0f
    }

    private fun acquireWakeLock() {
        val p = power ?: return
        if (wakeLock?.isHeld == true) return
        val wl = wakeLock ?: p.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).also {
            it.setReferenceCounted(false)
            wakeLock = it
        }
        runCatching { wl.acquire() }
        Diag.log("backtap", "wake lock acquired（熄屏检测）")
    }

    private fun releaseWakeLock() {
        val wl = wakeLock ?: return
        if (wl.isHeld) {
            runCatching { wl.release() }
            Diag.log("backtap", "wake lock released")
        }
    }

    /** 双模式下的判定定时器。 */
    private val flushHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var flushTask: Runnable? = null

    /**
     * 双模式：敲完 2 下后安排一次「延迟判定」。
     *
     * 若在延迟到期前又敲了第 3 下，Detector 会直接返回三击事件并清空连击，
     * 这里的 flush 就查不到待判定连击（hasPendingCombo() 为 false），自动放弃。
     */
    private fun scheduleFlush() {
        flushTask?.let { flushHandler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                val ev = detector.flush(System.nanoTime())
                if (ev != null) {
                    Diag.log(
                        "backtap",
                        "triggered ${ev.reason} taps=${ev.taps} peak=%.2f".format(ev.peak)
                    )
                    onEvent(ev)
                    onTrigger(ev)
                }
            }
        }
        flushTask = r
        // 等的时长比「允许的最大敲击间隔」再宽一点，确保第 3 下有时间到达
        flushHandler.postDelayed(r, (lengthGapMs() + 120).toLong())
    }

    /**
     * 等待第 3 下的时长（毫秒）。
     *
     * 原版双击上限 500ms。用户敲完第 2 下后，要等这么久才能确定
     * 「是不是还要敲第 3 下」。取 500ms 是 Google 的实测值。
     */
    private fun lengthGapMs(): Int = (BackTapLogic.MAX_TAP_GAP_NS / 1_000_000L).toInt()

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        val v = e.values

        // 陀螺仪事件：只记录角速度，不参与冲量判定
        if (e.sensor.type == Sensor.TYPE_GYROSCOPE) {
            lastGyroMag = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            return
        }
        if (e.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        // 新版检测器沿用 Google Columbus 的完整信号处理管线：
        //   重采样 → 求斜率 → 低通 → 高通(去重力) → 峰值检测 → 正负峰配对判据
        // 所以必须传**原始三轴值**和**纳秒时间戳**，不能只传模长偏离。
        // 只给模长的话，滤波器拿不到方向信息，重力也去不掉。
        val ev = detector.feed(e.timestamp, v[0], v[1], v[2], lastGyroMag)

        // 诊断：每 500 个采样打一次内部状态。
        // 没有这个的话，"没反应"时完全无法判断是算法没识别、还是识别了但目标为空。
        debugSamples++
        if (debugSamples >= 500) {
            debugSamples = 0
            Diag.log(
                "backtap",
                "采样=${detector.totalSamples} 敲击=${detector.totalTaps} " +
                    "区间=${detector.peakInRange} 配对=${detector.pairedCount} " +
                    "信号[maxZ=%.2f maxSlope=%.2f maxHp=%.4f 门限=%.3f]"
                        .format(
                            detector.maxZDev, detector.maxSlope,
                            detector.maxHpKey,
                            AppSettings.backTapSensitivity(ctx).let {
                                BackTapLogic.paramsForSensitivity(it).minNoise
                            }
                        )
            )
        }
        if (ev == null) return
        onEvent(ev)
        // 计数中的反馈：固定用羽毛级（TICK_AMPLITUDE），不用用户的档位。
        // 否则每计一次数都很重，整个敲击过程都在被强震打断。
        if (ev.accepted && !ev.triggered) {
            Triggers.vibrate(
                ctx,
                AppSettings.TICK_MS,
                AppSettings.VIB_TAP,
                AppSettings.TICK_AMPLITUDE
            )
        }
        if (ev.triggered) {
            Diag.log(
                "backtap",
                "triggered taps=${ev.taps} need=${ev.need} peak=%.2f conf=%.2f"
                    .format(ev.peak, ev.confidence)
            )
            // 触发成功：**在这里震一次，且只震一次**。
            // 执行层（Triggers）已改成静默，避免"咚咚两下"。
            if (AppSettings.vibOnSuccess(ctx)) {
                Triggers.vibrate(
                    ctx,
                    AppSettings.vibMs(ctx, AppSettings.VIB_TAP, 14),
                    AppSettings.VIB_TAP,
                    AppSettings.vibAmp(ctx, AppSettings.VIB_TAP)
                )
            }
            onTrigger(ev)
            return
        }
        // 双模式：敲到 2 下时挂一个延迟判定，等第 3 下或超时定双击
        if (!ev.triggered && detector.hasPendingCombo()) {
            scheduleFlush()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
