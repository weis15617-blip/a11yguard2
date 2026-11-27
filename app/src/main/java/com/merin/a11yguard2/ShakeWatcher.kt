package com.merin.a11yguard2

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock

/**
 * 摇一摇监听。挂在无障碍服务（TapAgentService）上，不需要任何权限。
 *
 * 用**原始加速度计**（含重力）：经典算法靠三轴之和的**差分**判断变化率，
 * 重力是常量，在差分中自然抵消，不需要单独的线性加速度传感器。
 *
 * 采样率 SENSOR_DELAY_GAME（约 50Hz）：与经典实现一致。
 * UI 档（约 15Hz）会让 Δt 变成 66ms，变化率被平滑掉，灵敏度显著下降。
 */
class ShakeWatcher(private val ctx: Context, private val onTrigger: () -> Unit) : SensorEventListener {

    companion object {
        /** 设置页实时测试回调。 */
        @Volatile
        var testListener: ((Sample) -> Unit)? = null
    }

    /** 给设置页显示的采样快照。 */
    data class Sample(
        /** 本次算出的变化率 */
        val speed: Float,
        /** 阈值 */
        val threshold: Int,
        /** 当前累计次数 */
        val count: Int,
        /** 需要的次数 */
        val need: Int,
        /** 本次判定结果 */
        val verdict: ShakeLogic.Verdict?,
        /** 本轮峰值变化率 */
        val peakSpeed: Float,
        /** 是否触发 */
        val triggered: Boolean
    )

    private val sm: SensorManager? = ctx.getSystemService(SensorManager::class.java)
    private var sensor: Sensor? = null
    private var registered = false

    private var vibMs = 35

    /** 计次过程中的弱震动时长；0 = 不震。 */
    private var tickVibMs = 0

    /** 上次计数震动的时刻，用于限流。 */
    private var lastTickAt = 0L

    /** 上次计数震动时的 count（只在 count 变化时才震）。 */
    private var lastTickCount = -1

    /** Square Seismic 检测器（内部维护滑动窗口）。 */
    private var detector = ShakeLogic.Detector()

    /** 当前生效的变化率阈值（由灵敏度档位算出）。 */
    private var forceThreshold = ShakeLogic.DEFAULT_THRESHOLD

    /** 当前生效的摇动次数。 */
    private var needCount = ShakeLogic.SHAKE_COUNT

    fun reload() {
        vibMs = AppSettings.sensorVibMs(ctx)
        // 过程弱震动：开关关闭或总闸关闭时为 0（不震）
        tickVibMs = if (AppSettings.shakeTickVibOn(ctx)) AppSettings.shakeTickVibMs(ctx) else 0
        val level = AppSettings.shakeSensitivity(ctx)
        forceThreshold = ShakeLogic.thresholdForSensitivity(level)
        needCount = AppSettings.shakeCount(ctx)
        resetCounts()
        Diag.log("shake", "reload 灵敏度=$level 阈值=${forceThreshold}m/s² 次数=$needCount")
    }

    fun start() {
        Diag.log("shake", "start() called registered=$registered")
        if (registered) return
        reload()
        if (!AppSettings.shakeOn(ctx)) {
            Diag.log("shake", "skip start: shake_on=false")
            return
        }
        val target = AppSettings.shakeTarget(ctx)
        if (target.isEmpty() || target == AppSettings.ACT_NONE) {
            Diag.log("shake", "skip start: target empty")
            return
        }
        val s = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: run {
            Diag.log("shake", "skip start: no accelerometer")
            return
        }
        sensor = s
        registered = runCatching {
            sm?.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME) ?: false
        }.getOrDefault(false)
        Diag.log("shake", "start registered=$registered name=${s.name} rate=GAME")
    }

    fun stop() {
        // 无论标志如何都尝试注销，避免服务重连时累积多个监听器
        runCatching { sm?.unregisterListener(this) }
        registered = false
        resetCounts()
        Diag.log("shake", "stop")
    }

    fun resetCounts() {
        detector.reset()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        if (e.values.size < 3) return
        val x = e.values[0]
        val y = e.values[1]
        val z = e.values[2]
        val now = SystemClock.elapsedRealtime()

        // 新签名：直接传三轴原始值与纳秒时间戳（Square Seismic 用模长平方判定）
        val r = detector.step(
            timestampNs = e.timestamp,
            ax = x, ay = y, az = z,
            threshold = forceThreshold,
            needCount = needCount,
            nowMs = System.currentTimeMillis()
        )


        runCatching {
            testListener?.invoke(
                Sample(
                    speed = r.speed,
                    threshold = forceThreshold,
                    count = r.count,
                    need = needCount,
                    verdict = r.verdict,
                    peakSpeed = kotlin.math.sqrt(r.peakMagSq),
                    triggered = r.triggered
                )
            )
        }

        when {
            r.triggered -> {
                Diag.log("shake", "triggered speed=%.0f peak=%.0f hot=%.0f%%".format(r.speed, kotlin.math.sqrt(r.peakMagSq), r.hotRatio * 100))
                // 成功震动受总开关控制（默认开）
                if (AppSettings.vibOnSuccess(ctx)) {
                    Triggers.vibrate(
                        ctx, vibMs, AppSettings.VIB_SHAKE,
                        AppSettings.vibAmp(ctx, AppSettings.VIB_SHAKE)
                    )
                }
                // 触发后重置计数追踪，下次摇动重新开始
                lastTickCount = -1
                lastTickAt = 0L
                onTrigger()
            }
            r.verdict == ShakeLogic.Verdict.COUNTING -> {
                Diag.log("shake", "计数 %d/%d speed=%.0f".format(r.count, needCount, r.speed))
                // 过程弱震：让用户能数出"还差几次"。
                //
                // 限流有两个条件，缺一不可：
                //   1. count 必须变化（否则摇动中每个采样点都会震 → 变成连续嗡鸣）
                //   2. 距上次至少 180ms（避免快速连震听感糊成一片）
                val nowMs = SystemClock.elapsedRealtime()
                val countChanged = r.count != lastTickCount
                val cooled = nowMs - lastTickAt >= 180L
                if (tickVibMs > 0 && countChanged && cooled) {
                    lastTickCount = r.count
                    lastTickAt = nowMs
                    Triggers.vibrate(
                        ctx,
                        tickVibMs,
                        AppSettings.VIB_SHAKE,
                        AppSettings.TICK_AMPLITUDE
                    )
                }
            }
            r.verdict == ShakeLogic.Verdict.COOLDOWN ->
                Diag.log("shake", "冷却中（上次触发 <${ShakeLogic.SHAKE_DURATION}ms）")
            else -> Unit
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
