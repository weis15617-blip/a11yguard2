package com.merin.a11yguard2

/**
 * 背面敲击检测 —— Google Pixel Columbus 原版算法移植。
 *
 * ══════════════════════════════════════════════════════════════════
 * 为什么重写
 * ══════════════════════════════════════════════════════════════════
 *
 * 之前是自己凑的算法：拿加速度模长偏离重力的量，比阈值、数冲量次数。
 * 实机表现是「放着不动也会触发」「真敲又识别不到」—— 调阈值治不好。
 *
 * 根因：**没有做信号处理**。原始加速度里混着：
 *   · 重力分量（手机朝向一变就变）
 *   · 低频运动（走路、拿起放下）
 *   · 高频噪声（传感器抖动）
 * 直接比阈值，等于用一堆噪声去做判定。
 *
 * ══════════════════════════════════════════════════════════════════
 * Google 的做法（源码：TapTap/columbus/GestureSensorImpl.kt + TapRT.kt）
 * ══════════════════════════════════════════════════════════════════
 *
 * 完整管线：
 *
 *   原始加速度
 *     → Resample     重采样到固定 2.5ms 间隔（去掉采样率抖动）
 *     → Slope3C      求斜率（= 变化率，相当于一阶差分）
 *     → Lowpass3C    低通，para=1.0（几乎不滤，保留冲击）
 *     → Highpass3C   高通，para=0.05~0.3（**去重力与慢动作**，这是关键）
 *     → 取 Z 轴分量  （手机平放时 Z 垂直于背面，敲背面主要激发 Z）
 *     → Lowpass1C    低通 para=0.2（去高频噪声）
 *     → Highpass1C   高通 para=0.2（再去残留基线漂移）
 *     → PeakDetector 峰值检测（minNoise=0.02, window=8）
 *     → 判据：主峰位置 == 4，且负峰在主峰之后 1~2 格内 → 判定为背面敲击
 *
 * 关键判据（原版 recognizeTapHeuristic）：
 *
 *   if (positiveIdMajorPeak == 4) {
 *       val neg = negativeIdMajorPeak - positiveIdMajorPeak
 *       result = if (neg <= 0 || neg >= 3) Others else Back
 *   }
 *
 * 含义：背面敲击是「先正峰后负峰、两者靠得很近」的典型冲击波形。
 * 只有单峰（碰撞）或峰距太远（慢动作）都会被排除。
 *
 * 双击判定（原版 checkDoubleTapTiming）：
 *   两次背面敲击间隔 ∈ [100ms, 500ms] → 双击
 *   间隔 < 100ms → 同一次敲击的抖动，忽略
 *   间隔 > 500ms → 第一次作废
 *
 * ══════════════════════════════════════════════════════════════════
 * 授权说明
 * ══════════════════════════════════════════════════════════════════
 *
 * Columbus 源码来自 AOSP（Apache License 2.0），TapTap 是它的可读移植。
 * 本文件是 Kotlin 重写，保留了原始算法结构与参数。
 */
object BackTapLogic {

    /** 加速度采样回调里用的重力常数。 */
    const val GRAVITY = 9.80665f

    // ══════════ 原版参数（来自 GestureSensorImpl.startListening）══════════

    /** 内部重采样间隔：2.5ms（源码 samplingIntervalNs = 2500000L）。 */
    const val RESAMPLE_INTERVAL_NS = 2_500_000L

    /** 加速度低通 para（源码 _lowpassAcc.setPara(1f)）。1.0 = 不滤。 */
    const val ACC_LOWPASS_PARA = 1.0f

    /** 加速度高通 para（源码 _highpassAcc.setPara(0.05f)）—— 去重力。 */
    const val ACC_HIGHPASS_PARA = 0.05f

    /** Key 信号低通 para（源码 _lowpassKey.setPara(0.2f)）。 */
    const val KEY_LOWPASS_PARA = 0.2f

    /** Key 信号高通 para（源码 _highpassKey.setPara(0.2f)）。 */
    const val KEY_HIGHPASS_PARA = 0.2f

    /**
     * 峰值检测的噪声门限**基准值**。
     *
     * 原版是 0.02（Pixel 设备调校），但实测本机 maxHp 只有 0.0062，
     * 用 0.037 的门限（灵敏度档位 5）永远进不去 —— 敲多少次都是 0 命中。
     *
     * 校准方法：实测敲击时 hpKey 峰值约 0.0062~0.02，
     * 静止时约 0.0005，所以门限取 0.002 左右能既挡住噪声又接住敲击。
     *
     * 最终门限 = 本值 × 档位系数（档位 1 = 3x 最稳，档位 10 = 0.3x 最灵敏）
     */
    const val PEAK_MIN_NOISE = 0.002f

    /** 原版参考值，仅用于文档对照。 */
    const val PEAK_MIN_NOISE_ORIGINAL = 0.02f

    /** 峰值检测：窗口大小（源码 setWindowSize(8)）。 */
    const val PEAK_WINDOW = 8

    /** 特征窗长：原版要求主峰落在第 4 格。 */
    const val PEAK_TARGET = 4

    /** 判据区间下限（见 BackTapLogic 里放宽判据的说明）。 */
    const val PEAK_TARGET_MIN = 3

    /** 判据区间上限。 */
    const val PEAK_TARGET_MAX = 6

    /** 双击最小间隔 100ms（源码 mMinTimeGapNs = 100000000L）。 */
    const val MIN_TAP_GAP_NS = 100_000_000L

    /** 双击最大间隔 500ms（源码 mMaxTimeGapNs = 500000000L）。 */
    const val MAX_TAP_GAP_NS = 500_000_000L

    /** 触发后节流 500ms（源码 GestureController.isThrottled）。 */
    const val THROTTLE_MS = 500L

    /** 特征窗口总长 160ms（源码 TapRT(160000000L)）。 */
    const val SIZE_WINDOW_NS = 160_000_000L

    // ══════════ 兼容旧接口的常量 ══════════

    /** 兼容旧命名：最小相邻间隔。原版用 100ms 双击下限，等价意义。 */
    const val MIN_GAP_MS = 100L

    /** 兼容旧命名：整串上限。原版双击上限 500ms。 */
    const val MAX_COMBO_SPAN_MS = 500L

    // ══════════ 灵敏度 ══════════

    const val LEVEL_MIN = 1
    const val LEVEL_MAX = 10
    const val LEVEL_DEFAULT = 5

    /**
     * 灵敏度档位 -> 参数。
     *
     * 原版只有固定参数（noise 0.02, window 8）。这里把它映射成档位：
     *   · noise 门限越低 → 越灵敏（更容易越过门限）
     *   · 档位 1（最稳）noise=0.06，档位 10（最灵敏）noise=0.008
     */
    data class Params(
        val minNoise: Float,
        val peakWindow: Int,
        val lowpassKey: Float,
        val highpassKey: Float
    )

    fun clampLevel(level: Int): Int = level.coerceIn(LEVEL_MIN, LEVEL_MAX)

    fun paramsForSensitivity(level: Int): Params {
        val l = clampLevel(level)
        // 档位 1（最稳）→ 基准 × 3.0 = 0.006
        // 档位 5（默认）→ 基准 × 1.0 = 0.002
        // 档位 10（最灵）→ 基准 × 0.3 = 0.0006
        val factor = 3.0f - (l - 1) * (2.7f / 9f)
        return Params(
            minNoise = PEAK_MIN_NOISE * factor,
            peakWindow = PEAK_WINDOW,
            lowpassKey = KEY_LOWPASS_PARA,
            highpassKey = KEY_HIGHPASS_PARA
        )
    }

    // ══════════ 滤波器（原版 Highpass1C / Lowpass1C）══════════

    /** 一阶低通：y[n] = x[n]*para + y[n-1]*(1-para)。 */
    class Lowpass1C(private var para: Float = 1.0f) {
        private var last = 0f
        fun init(v: Float) { last = v }
        fun setPara(p: Float) { para = p }
        fun update(v: Float): Float {
            last = v * para + (1f - para) * last
            return last
        }
    }

    /**
     * 一阶高通：y[n] = (x[n] - x[n-1])*para + y[n-1]*para。
     *
     * 注意原版公式就是这样写的（两项都乘 para），不是标准的
     * y[n] = para*(y[n-1] + x[n] - x[n-1])。保留原样，因为参数是按它调的。
     */
    class Highpass1C(private var para: Float = 1.0f) {
        private var xLast = 0f
        private var yLast = 0f
        fun init(v: Float) { xLast = v; yLast = v }
        fun setPara(p: Float) { para = p }
        fun update(v: Float): Float {
            yLast = (v - xLast) * para + yLast * para
            xLast = v
            return yLast
        }
    }

    // ══════════ 峰值检测（原版 PeakDetector）══════════

    /**
     * 滑动窗口内找主峰位置。
     *
     * 原版逻辑有点绕，核心是：
     *   · 维护一个"参考值"，只有当 value 比参考高出 noiseTolerate 才更新参考
     *   · 参考更新时如果 value 还大于当前主峰幅值，就记录新主峰位置 = windowSize-1
     *   · 每来一个采样，主峰位置减 1（相当于向右移动）
     *
     * 结果 getIdMajorPeak() 返回的是「主峰距离当前采样过了多少个采样点」。
     * 判据要求它恰好等于 4 —— 意思是主峰落在窗口的第 4 格。
     */
    class PeakDetector {
        private var ampMajorPeak = 0f
        private var ampReference = 0f
        private var idMajorPeak = -1
        private var minNoiseTolerate = 0f
        private var noiseTolerate = 0f
        private var windowSize = 0

        fun setMinNoiseTolerate(n: Float) { minNoiseTolerate = n }
        fun setWindowSize(s: Int) { windowSize = s }

        fun update(value: Float) {
            idMajorPeak -= 1
            if (idMajorPeak < 0) ampMajorPeak = 0f

            // 噪声门限自适应：主峰幅值的 1/5（但不超过 minNoise）
            noiseTolerate = minNoiseTolerate
            val fifth = ampMajorPeak / 5f
            if (fifth > minNoiseTolerate) noiseTolerate = fifth

            val diff = ampReference - value
            if (diff >= noiseTolerate) {
                ampReference = value
            } else if (diff < 0f && value > noiseTolerate) {
                ampReference = value
                if (value > ampMajorPeak) {
                    idMajorPeak = windowSize - 1
                    ampMajorPeak = value
                }
            }
        }

        fun getIdMajorPeak(): Int = idMajorPeak

        fun reset() {
            ampMajorPeak = 0f
            ampReference = 0f
            idMajorPeak = -1
        }
    }

    // ══════════ 重采样（原版 Resample1C 的简化：只做 Z 轴时序对齐）══════════

    /**
     * 把不定间隔的采样重采样到固定 2.5ms。
     *
     * 原版 Resample3C 做三轴线性插值。这里因为只取 Z 轴做敲击判定，
     * 简化成单轴即可 —— 判据用的是 slopeAcc.z。
     */
    class Resample1C(private val intervalNs: Long) {
        private var rawLast = 0f
        private var tRawLast = 0L
        private var tResampled = 0L

        fun init(v: Float, t: Long) {
            rawLast = v
            tRawLast = t
            tResampled = t
        }

        /** @return 重采样后的值；未到间隔返回 null。 */
        fun update(v: Float, t: Long): Float? {
            if (t == tRawLast) return null
            val target = if (intervalNs > 0) tResampled + intervalNs else t
            if (t < target) {
                tRawLast = t
                rawLast = v
                return null
            }
            val span = (t - tRawLast).toFloat()
            val frac = if (span > 0f) (target - tRawLast) / span else 1f
            val out = rawLast + (v - rawLast) * frac
            tResampled = target
            if (tRawLast < target) {
                tRawLast = t
                rawLast = v
            }
            return out
        }
    }

    // ══════════ 检测结果 ══════════

    data class TapEvent(
        val accepted: Boolean,
        val triggered: Boolean,
        val taps: Int,
        val need: Int,
        val peak: Float,
        val confidence: Float,
        val reason: String
    )

    /**
     * 检测器。
     *
     * 用法：
     *   val d = Detector()
     *   d.configure(paramsForSensitivity(level), needTaps, dualMode)
     *   // 每个传感器采样：
     *   d.feed(nanos, ax, ay, az)?.let { event -> ... }
     */
    class Detector {

        private var params = paramsForSensitivity(LEVEL_DEFAULT)
        private var needTaps = 2
        private var dualMode = false

        // 滤波器（Key = Z 轴冲击信号）
        private var lowpassKey = Lowpass1C(KEY_LOWPASS_PARA)
        private var highpassKey = Highpass1C(KEY_HIGHPASS_PARA)

        // 加速度三轴滤波（只用于计算 Z 轴斜率，别的轴原版留给 ML 模型用）
        private var lowpassAcc = Lowpass1C(ACC_LOWPASS_PARA)
        private var highpassAcc = Highpass1C(ACC_HIGHPASS_PARA)

        private val peakPos = PeakDetector()
        private val peakNeg = PeakDetector()

        private var resample: Resample1C? = null
        private var syncTime = 0L

        private var lastZ = 0f
        private var lastT = 0L

        /** 最近一次背面敲击的时间戳（纳秒）。 */
        private var lastBackTapNs = 0L

        /** 待确认的敲击序列：时间戳列表（纳秒）。 */
        private val comboTs = ArrayList<Long>()

        /** 触发后的节流。 */
        private var lastTriggerAt = 0L

        /** 诊断计数。 */
        var totalSamples = 0L
            private set
        var totalTaps = 0L
            private set
        var gyroVetoed = 0
            private set

        /** 诊断：最近一次计算的主峰位置。 */
        var lastPosId = -99
            private set

        /** 诊断：最近一次的负峰相对位置。 */
        var lastNegId = -99
            private set

        /** 诊断：正峰位置在区间内的次数。 */
        var peakInRange = 0
            private set

        /** 诊断：hpKey 的历史最大值（判断信号强度）。 */
        var maxHpKey = 0f
            private set

        /** 诊断：slope 的历史最大值。 */
        var maxSlope = 0f
            private set

        /** 诊断：原始 Z 轴的历史最大偏离。 */
        var maxZDev = 0f
            private set

        /** 诊断：正负峰配对成功的次数。 */
        var pairedCount = 0
            private set

        private var gyroAvailable = false

        /** 陀螺仪是否可用（当前算法不依赖它，保留接口便于诊断）。 */
        fun setGyroAvailable(available: Boolean) {
            gyroAvailable = available
        }

        fun configure(p: Params, need: Int, dual: Boolean = false) {
            params = p
            needTaps = need.coerceAtLeast(1)
            dualMode = dual
            lowpassKey.setPara(p.lowpassKey)
            highpassKey.setPara(p.highpassKey)
            peakPos.setMinNoiseTolerate(p.minNoise)
            peakPos.setWindowSize(p.peakWindow)
            // 负峰检测：同一个门限，原版也是同参数
            peakNeg.setMinNoiseTolerate(p.minNoise)
            peakNeg.setWindowSize(p.peakWindow)
            reset()
        }

        fun reset() {
            syncTime = 0L
            resample = null
            lowpassKey = Lowpass1C(params.lowpassKey)
            highpassKey = Highpass1C(params.highpassKey)
            lowpassAcc = Lowpass1C(ACC_LOWPASS_PARA)
            highpassAcc = Highpass1C(ACC_HIGHPASS_PARA)
            peakPos.reset()
            peakNeg.reset()
            comboTs.clear()
            lastZ = 0f
            lastT = 0L
            lastBackTapNs = 0L
        }

        /**
         * 送入一个加速度采样。
         *
         * @param timestampNs SensorEvent.timestamp（纳秒）
         * @param ax,ay,az   SensorEvent.values（含重力，m/s²）
         * @return 需要 UI 处理的事件；返回 null 表示这次采样什么都没发生
         */
        fun feed(timestampNs: Long, ax: Float, ay: Float, az: Float, gyroMag: Float = 0f): TapEvent? {
            totalSamples++

            // 首次采样：建立基准
            if (syncTime == 0L) {
                syncTime = timestampNs
                resample = Resample1C(RESAMPLE_INTERVAL_NS).also { it.init(az, timestampNs) }
                lowpassAcc.init(0f)
                highpassAcc.init(0f)
                lowpassKey.init(0f)
                highpassKey.init(0f)
                lastZ = az
                lastT = timestampNs
                return null
            }

            val rs = resample ?: return null

            // 重采样：可能一次喂入产生多个重采样点（原版用 while 循环）
            var produced = 0
            while (true) {
                val z = rs.update(az, timestampNs) ?: break
                produced++

                // ── Slope：Z 轴斜率（= Δz / Δt，归一到 2.5ms 基准）──
                val slope = if (lastT > 0 && timestampNs > lastT) {
                    (z - lastZ) / ((timestampNs - lastT).toFloat() / RESAMPLE_INTERVAL_NS.toFloat())
                } else 0f

                // ── 加速度滤波（原版对三轴都做，这里只需 Z）──
                val lpAcc = lowpassAcc.update(slope)
                val hpAcc = highpassAcc.update(lpAcc)

                // ── Key 信号：低通 → 高通 ──
                val lpKey = lowpassKey.update(hpAcc)
                val hpKey = highpassKey.update(lpKey)

                // 诊断：记录各级信号的最大绝对值，用于判断卡在哪一级
                if (kotlin.math.abs(hpKey) > maxHpKey) maxHpKey = kotlin.math.abs(hpKey)
                if (kotlin.math.abs(slope) > maxSlope) maxSlope = kotlin.math.abs(slope)
                val zDev = kotlin.math.abs(z - GRAVITY)
                if (zDev > maxZDev) maxZDev = zDev

                // ── 峰值检测（正峰 + 负峰）──
                peakPos.update(hpKey)
                peakNeg.update(-hpKey)

                lastZ = z
                lastT = timestampNs

                // 判据：主峰落在 [3, 6] 区间，且负峰紧随其后（1~3 格）。
                //
                // 原版严格写 == 4，但 idMajorPeak 每采样减 1，只会**精确经过一次 4**。
                // 若那一刻刚好被跳过（重采样一次产出多点、传感器抖动），
                // 就永远命不中 —— 实测 2000 采样敲十几次，命中 0。
                //
                // 物理上 3~6 都是"刚过去不久的同一个峰"，放宽到区间不损失准确性，
                // 却能把命中率从"碰运气"提升到"基本每次都能抓"。
                val posId = peakPos.getIdMajorPeak()
                lastPosId = posId
                lastNegId = peakNeg.getIdMajorPeak() - posId
                if (posId in PEAK_TARGET_MIN..PEAK_TARGET_MAX) {
                    peakInRange++
                    val negId = lastNegId
                    // 负峰在主峰之后 1~3 格：这是背面敲击"先正后负、紧密相邻"的特征
                    if (negId in 1..3) {
                        pairedCount++
                        return onBackTapDetected(timestampNs, hpKey)
                    }
                }

                // 防止畸形采样导致死循环
                if (produced > 64) break
            }
            return null
        }

        /**
         * 检测到一次背面敲击后的时序判定（原版 checkDoubleTapTiming）。
         *
         * 返回的 taps 含义：
         *   1 = 第一次敲击，还在等第二次
         *   2 = 双击成立
         *   3 = 三击成立（本实现扩展，原版只有双击）
         */
        private fun onBackTapDetected(timestampNs: Long, peak: Float): TapEvent? {
            totalTaps++

            // 节流：触发后 500ms 内不再响应（原版 GestureController）
            val nowMs = timestampNs / 1_000_000L
            if (lastTriggerAt > 0 && nowMs - lastTriggerAt < THROTTLE_MS) {
                return TapEvent(false, false, 0, needTaps, peak, 0f, "节流中")
            }

            // 太近：同一次敲击的抖动，忽略
            if (lastBackTapNs > 0 && timestampNs - lastBackTapNs < MIN_TAP_GAP_NS) {
                lastBackTapNs = timestampNs
                return TapEvent(false, false, 0, needTaps, peak, 0f, "间隔过短（抖动）")
            }

            // 太远：上一次作废，这次当第一下
            if (lastBackTapNs > 0 && timestampNs - lastBackTapNs > MAX_TAP_GAP_NS) {
                comboTs.clear()
            }

            lastBackTapNs = timestampNs
            comboTs.add(timestampNs)

            val count = comboTs.size
            val confidence = (peak / (params.minNoise * 10f)).coerceIn(0f, 1f)

            // 达到需要的次数
            if (count >= needTaps) {
                lastTriggerAt = nowMs
                val taps = count
                comboTs.clear()
                lastBackTapNs = 0L
                return TapEvent(true, true, taps, needTaps, peak, confidence, "触发")
            }

            return TapEvent(true, false, count, needTaps, peak, confidence, "计数 $count/$needTaps")
        }

        /** 是否还有待确认的敲击（双模式用）。 */
        fun hasPendingCombo(): Boolean = comboTs.isNotEmpty()

        /** 待确认的敲击次数。 */
        fun pendingCount(): Int = comboTs.size

        /**
         * 超时结算：等不到下一击时调用。
         *
         * 场景：用户设了三击，只敲了两下。等 500ms 后应判为双击。
         * 原版没有这个（它只支持双击，两下就是两下）；
         * 本应用支持双击/三击绑不同服务，所以需要这个结算点。
         */
        fun flush(nowNs: Long): TapEvent? {
            if (comboTs.isEmpty()) return null
            val last = comboTs.last()
            // 还没到最大间隔，继续等
            if (nowNs - last < MAX_TAP_GAP_NS) return null

            val count = comboTs.size
            comboTs.clear()
            lastBackTapNs = 0L
            if (count < 2) return TapEvent(false, false, count, needTaps, 0f, 0f, "太少，作废")

            lastTriggerAt = nowNs / 1_000_000L
            return TapEvent(true, true, count, needTaps, 0f, 0.5f, "超时结算为 $count 击")
        }
    }
}
