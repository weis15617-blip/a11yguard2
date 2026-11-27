package com.merin.a11yguard2

import kotlin.math.abs

/**
 * 摇一摇检测 —— Square Seismic 原版算法移植。
 *
 * ══════════════════════════════════════════════════════════════════
 * 为什么用 Square Seismic
 * ══════════════════════════════════════════════════════════════════
 *
 * Square（美国支付公司）2010 年开源，是 Android 上被引用最多的摇一摇实现。
 * 核心洞察写在源码注释里：
 *
 *   "If more than 75% of the samples taken in the past 0.5s are
 *    accelerating, the device is a) shaking, or b) free falling 1.84m"
 *
 * 即：**摇动的本质是「持续加速」**，而不是"某几个瞬间值大"。
 *
 * ══════════════════════════════════════════════════════════════════
 * 之前的错误
 * ══════════════════════════════════════════════════════════════════
 *
 * 我先用了「三轴和的变化率 > 350」，后来又改成「> 1200」——
 * 都是自己拍的值。实测表现是「手机放着不动也会触发」。
 *
 * 根因：用**变化率**做判据。变化率对噪声极其敏感：传感器每隔几毫秒
 * 就会有微小抖动，除上极小的 Δt 后放大成很大的值。
 *
 * Square 用的是**总加速度模长的平方**——这是绝对量，不除时间，
 * 噪声不会被放大。
 *
 * ══════════════════════════════════════════════════════════════════
 * 原版参数（源码 ShakeDetector.java）
 * ══════════════════════════════════════════════════════════════════
 *
 *   SENSITIVITY_LIGHT  = 11   11 m/s²
 *   SENSITIVITY_MEDIUM = 13   13 m/s²
 *   SENSITIVITY_HARD   = 15   15 m/s²
 *
 *   静止时 |a| ≈ g ≈ 9.8 m/s²
 *   所以 11 只比静止高一点（轻微加速），15 要明显用力
 *
 *   窗口：0.5s（MAX_WINDOW_SIZE = 500000000 ns）
 *   最小窗口：0.25s（MIN_WINDOW_SIZE = MAX >> 1）
 *   最少样本：4（MIN_QUEUE_SIZE，兼容采样率低的设备）
 *   判据：acceleratingCount >= sampleCount*3/4
 *        （源码写法：acceleratingCount >= (sampleCount>>1) + (sampleCount>>2)）
 *
 * ══════════════════════════════════════════════════════════════════
 * 授权
 * ══════════════════════════════════════════════════════════════════
 *
 * Square Seismic 为 Apache License 2.0。本文件是 Kotlin 重写，
 * 保留原始算法结构与参数。
 */
object ShakeLogic {

    // ══════════ 原版灵敏度常量 ══════════

    /** 轻微摇动（11 m/s²）。 */
    const val SENSITIVITY_LIGHT = 11

    /** 中等摇动（13 m/s²，原版默认）。 */
    const val SENSITIVITY_MEDIUM = 13

    /** 用力摇动（15 m/s²）。 */
    const val SENSITIVITY_HARD = 15

    /** 默认阈值 = 中等。 */
    const val DEFAULT_THRESHOLD = SENSITIVITY_MEDIUM

    /** 窗口上限 0.5s（纳秒）。 */
    const val MAX_WINDOW_NS = 500_000_000L

    /** 窗口下限 0.25s（纳秒）。 */
    const val MIN_WINDOW_NS = MAX_WINDOW_NS / 2

    /** 最少样本数：设备采样率低时也要能判定。 */
    const val MIN_QUEUE_SIZE = 4

    /** 触发后冷却，避免一次摇动里反复触发。 */
    const val COOLDOWN_MS = 1000L

    // ══════════ 兼容旧接口的常量（值已按新算法校正）══════════

    /** 兼容旧命名。 */
    const val DEFAULT_FORCE_THRESHOLD = DEFAULT_THRESHOLD.toFloat()

    /** 兼容旧命名。 */
    const val FORCE_THRESHOLD = DEFAULT_FORCE_THRESHOLD

    /** 兼容旧命名：默认摇动次数。 */
    const val SHAKE_COUNT = 3

    /** 兼容旧命名：连续计数上限（新算法用比例判据，此值仅作 UI 参考）。 */
    const val RATIO_WINDOW_MS = 500L

    /** 兼容旧命名：热采样占比要求。 */
    const val HOT_RATIO = 0.75f

    /** 兼容旧命名。 */
    const val TIME_THRESHOLD = 10L

    /** 兼容旧命名。 */
    const val SHAKE_TIMEOUT = 500L

    /** 兼容旧命名。 */
    const val SHAKE_DURATION = 1000L

    /** 兼容旧命名。 */
    const val DEFAULT_SENSITIVITY = 5

    // ══════════ 灵敏度档位 ══════════

    /**
     * 档位 1-10 映射到加速度阈值。
     *
     * 原版只有三档（11/13/15）。这里扩展成 10 档，但**覆盖同样的物理范围**：
     *   1  → 16.0 m/s²（最难，要用力甩）
     *   5  → 13.0 m/s²（默认，等同原版 MEDIUM）
     *   10 → 9.5  m/s²（最灵敏，略高于重力）
     *
     * 注意：低于 9.8 会把"静止"判成加速，所以下限取 9.5。
     */
    fun thresholdForSensitivity(level: Int): Int {
        val l = level.coerceIn(1, 10)
        // 16.0 -> 9.5，共 6.5 的跨度，9 个间隔
        val v = 16.0f - (l - 1) * (6.5f / 9f)
        return Math.round(v)
    }

    /** 兼容旧命名。 */
    fun forceForSensitivity(level: Int): Float = thresholdForSensitivity(level).toFloat()

    // ══════════ 采样队列（原版 SampleQueue）══════════

    /**
     * 滑动窗口 + 运行平均值。
     *
     * 原版用链表 + 对象池避免 GC。Kotlin 里用 ArrayDeque 更简洁，
     * 语义等价：入队时剔除过期样本，同时维护加速样本计数。
     */
    class SampleQueue {

        /** 每个样本：(时间戳纳秒, 是否处于加速)。 */
        private val samples = ArrayDeque<Pair<Long, Boolean>>()
        private var acceleratingCount = 0

        val size: Int get() = samples.size
        val hotCount: Int get() = acceleratingCount

        fun add(timestampNs: Long, accelerating: Boolean) {
            // 剔除窗口外的旧样本（保留至少 MIN_QUEUE_SIZE 个，兼容低采样率）
            purge(timestampNs - MAX_WINDOW_NS)

            samples.addLast(timestampNs to accelerating)
            if (accelerating) acceleratingCount++
        }

        private fun purge(cutoff: Long) {
            while (samples.size >= MIN_QUEUE_SIZE) {
                val oldest = samples.firstOrNull() ?: break
                if (cutoff - oldest.first <= 0) break
                samples.removeFirst()
                if (oldest.second) acceleratingCount--
            }
        }

        fun clear() {
            samples.clear()
            acceleratingCount = 0
        }

        /**
         * 是否正在摇动（原版 isShaking）。
         *
         * 三个条件同时满足：
         *   1. 队列非空
         *   2. 跨度 >= 0.25s（窗口太短说明刚启动，数据不足）
         *   3. 加速样本占比 >= 3/4
         */
        fun isShaking(): Boolean {
            if (samples.isEmpty()) return false
            val newest = samples.last()
            val oldest = samples.first()
            if (newest.first - oldest.first < MIN_WINDOW_NS) return false
            // 原版写法：(sampleCount >> 1) + (sampleCount >> 2) = 0.75 * n
            val need = (samples.size shr 1) + (samples.size shr 2)
            return acceleratingCount >= need
        }

        /** 当前热样本占比（诊断用）。 */
        fun hotRatio(): Float =
            if (samples.isEmpty()) 0f
            else acceleratingCount.toFloat() / samples.size
    }

    // ══════════ 判定结果 ══════════

    enum class Verdict {
        /** 采样间隔太短被跳过。 */
        SKIPPED,
        /** 未达阈值。 */
        TOO_SLOW,
        /** 正在累计（比例未到）。 */
        COUNTING,
        /** 冷却中。 */
        COOLDOWN,
        /** 触发。 */
        OK
    }

    data class State(
        /** 已连续摇动次数（用于"摇 N 次触发"）。 */
        val shakeCount: Int = 0,
        /** 上次触发时刻（毫秒）。 */
        val lastShakeMs: Long = 0L,
        /** 本次采样算出的模长平方。 */
        val lastMagSq: Float = 0f,
        /** 峰值模长平方（诊断）。 */
        val peakMagSq: Float = 0f,
        /** 上一次样本的热占比（诊断）。 */
        val hotRatio: Float = 0f,
        /** 窗口内样本数（诊断）。 */
        val windowSize: Int = 0
    )

    data class Step(
        val state: State,
        val triggered: Boolean,
        val verdict: Verdict?,
        /** 本次模长（m/s²），诊断显示用。 */
        val speed: Float,
        /** 当前累计次数。 */
        val count: Int,
        /** 峰值模长平方。 */
        val peakMagSq: Float = 0f,
        /** 热样本占比。 */
        val hotRatio: Float = 0f
    )

    // ══════════ 检测器 ══════════

    class Detector {
        private val queue = SampleQueue()
        private var lastShakeMs = 0L
        private var shakeCount = 0
        private var peakMagSq = 0f

        /**
         * 当前这段连续摇动是否已计入过。
         *
         * 之前用 queue.clear() 表示"这段算完了"，但清空后要重新累积
         * 0.25s 的窗口才能再判定 —— 持续甩动时反复清空又填满，
         * 计数永远卡在 2/5、3/5 上不去（实测 speed 到 24 也不涨）。
         *
         * 改成标记位：一段连续摇动只计一次，摇动停止后窗口自然滑空，
         * 标记复位，下一段立刻能计入。
         */
        private var countedThisBurst = false

        /**
         * 处理一个加速度采样。
         *
         * @param timestampNs SensorEvent.timestamp（纳秒）
         * @param ax,ay,az    SensorEvent.values（含重力，m/s²）
         * @param threshold   加速度阈值（m/s²）
         * @param needCount   需要连续摇动几次才触发
         * @param nowMs       当前墙钟（毫秒），用于冷却判断
         */
        fun step(
            timestampNs: Long,
            ax: Float,
            ay: Float,
            az: Float,
            threshold: Int = DEFAULT_THRESHOLD,
            needCount: Int = SHAKE_COUNT,
            nowMs: Long = System.currentTimeMillis()
        ): Step {
            // ── 判据：模长平方 vs 阈值平方（原版为了避免 sqrt 用平方比较）──
            val magSq = ax * ax + ay * ay + az * az
            val thSq = (threshold * threshold).toFloat()
            val accelerating = magSq > thSq

            queue.add(timestampNs, accelerating)
            if (magSq > peakMagSq) peakMagSq = magSq

            val hotRatio = queue.hotRatio()
            val count = queue.hotCount

            var verdict: Verdict
            var triggered = false

            if (queue.isShaking()) {
                // 摇动成立
                if (countedThisBurst) {
                    // 这一段已经计过，不重复计，但也不清空窗口
                    verdict = Verdict.COUNTING
                } else if (nowMs - lastShakeMs < COOLDOWN_MS) {
                    verdict = Verdict.COOLDOWN
                } else {
                    countedThisBurst = true
                    shakeCount++
                    if (shakeCount >= needCount.coerceAtLeast(1)) {
                        lastShakeMs = nowMs
                        shakeCount = 0
                        triggered = true
                        verdict = Verdict.OK
                        peakMagSq = 0f
                        // 触发后才清空，等下一次摇动
                        queue.clear()
                        countedThisBurst = false
                    } else {
                        verdict = Verdict.COUNTING
                        // 注意：**不清空 queue**。清空会导致窗口重新累积 0.25s，
                        // 持续甩动时计数永远上不去。
                    }
                }
            } else {
                // 摇动停止：窗口滑空后允许下一段重新计入
                if (queue.size == 0 || !accelerating) {
                    countedThisBurst = false
                }
                verdict = if (accelerating) Verdict.COUNTING else Verdict.TOO_SLOW
            }

            val st = State(
                shakeCount = shakeCount,
                lastShakeMs = lastShakeMs,
                lastMagSq = magSq,
                peakMagSq = peakMagSq,
                hotRatio = hotRatio,
                windowSize = queue.size
            )
            return Step(
                state = st,
                triggered = triggered,
                verdict = verdict,
                speed = kotlin.math.sqrt(magSq),
                count = shakeCount,
                peakMagSq = peakMagSq,
                hotRatio = hotRatio
            )
        }

        fun reset() {
            queue.clear()
            lastShakeMs = 0L
            shakeCount = 0
            peakMagSq = 0f
            countedThisBurst = false
        }
    }
}
