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
    private val onTrigger: () -> Unit,
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
    private var screenReceiver: BroadcastReceiver? = null
    private var screenOn = true
    private var allowScreenOff = false
    private var wakeLock: PowerManager.WakeLock? = null

    private var vibMs = 35

    fun reload() {
        val sp = AppSettings
        val params = BackTapLogic.paramsForSensitivity(sp.backTapSensitivity(ctx))
        detector.configure(params, sp.backTapCount(ctx))
        vibMs = sp.sensorVibMs(ctx)
        val allow = sp.backTapScreenOff(ctx)
        val changed = allow != allowScreenOff
        allowScreenOff = allow
        if (changed) applyRegistration()
        Diag.log(
            "backtap",
            "reload lv=${sp.backTapSensitivity(ctx)} th=%.2f gap=%d conf=%.2f need=%d off=%s"
                .format(params.ampThreshold, params.maxGapMs, params.minConfidence, sp.backTapCount(ctx), allow)
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
        if (!screenOn && allowScreenOff) acquireWakeLock() else releaseWakeLock()
    }

    private fun unregister() {
        if (!registered) return
        runCatching { sm?.unregisterListener(this) }
        registered = false
        currentPeriodUs = 0
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

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        val v = e.values
        val deviation = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) - BackTapLogic.GRAVITY
        val ev = detector.feed(SystemClock.elapsedRealtime(), deviation) ?: return
        onEvent(ev)
        if (ev.accepted && !ev.triggered && vibMs > 0) {
            Triggers.vibrate(ctx, vibMs)
        }
        if (ev.triggered) {
            Diag.log(
                "backtap",
                "triggered need=${ev.need} peak=%.2f conf=%.2f".format(ev.peak, ev.confidence)
            )
            onTrigger()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
