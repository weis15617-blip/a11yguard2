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
        val threshold: Float,
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
    private var state = ShakeLogic.State()

    /** 当前生效的变化率阈值（由灵敏度档位算出）。 */
    private var forceThreshold = ShakeLogic.DEFAULT_FORCE_THRESHOLD

    /** 当前生效的摇动次数。 */
    private var needCount = ShakeLogic.SHAKE_COUNT

    fun reload() {
        vibMs = AppSettings.sensorVibMs(ctx)
        val level = AppSettings.shakeSensitivity(ctx)
        forceThreshold = ShakeLogic.forceForSensitivity(level)
        needCount = AppSettings.shakeCount(ctx)
        resetCounts()
        Diag.log("shake", "reload 灵敏度=$level 阈值=%.0f 次数=$needCount".format(forceThreshold))
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
        state = ShakeLogic.State()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        if (e.values.size < 3) return
        val x = e.values[0]
        val y = e.values[1]
        val z = e.values[2]
        val now = SystemClock.elapsedRealtime()

        val r = ShakeLogic.step(state, now, x, y, z, forceThreshold, needCount)
        state = r.state

        runCatching {
            testListener?.invoke(
                Sample(
                    speed = r.speed,
                    threshold = forceThreshold,
                    count = r.count,
                    need = needCount,
                    verdict = r.verdict,
                    peakSpeed = state.peakSpeed,
                    triggered = r.triggered
                )
            )
        }

        when {
            r.triggered -> {
                Diag.log("shake", "triggered speed=%.0f peak=%.0f".format(r.speed, state.peakSpeed))
                Triggers.vibrate(ctx, vibMs, AppSettings.VIB_SHAKE)
                onTrigger()
            }
            r.verdict == ShakeLogic.Verdict.COUNTING ->
                Diag.log("shake", "计数 %d/%d speed=%.0f".format(r.count, needCount, r.speed))
            r.verdict == ShakeLogic.Verdict.COOLDOWN ->
                Diag.log("shake", "冷却中（上次触发 <${ShakeLogic.SHAKE_DURATION}ms）")
            else -> Unit
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
