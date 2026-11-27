package com.merin.a11yguard2

import kotlin.math.abs

/**
 * 背面敲击判定（纯 JVM 逻辑，无 Android 依赖，便于单测）。
 *
 * 数据来源：TYPE_ACCELEROMETER 的模长。用模长而不是单轴，是因为握持角度任意，
 * 模长与设备姿态无关；重力恒为 9.8，敲击是一次几毫秒到几十毫秒的冲量，
 * 表现为 ‖a‖ 瞬间偏离 9.8、随后快速回落。
 *
 * 单次敲击判据（全部满足才算一次）：
 *  1) 峰值 |‖a‖-g| ≥ ampThreshold（由灵敏度档位映射）；
 *  2) 冲量持续时长 ≤ MAX_PULSE_MS —— 按住/放下手机是持续压力，会被拒绝；
 *  3) 峰值前 RISE_WINDOW_MS 内存在低于释放阈值的安静样本 —— 排除缓慢倾斜；
 *  4) 合成置信度 ≥ minConfidence（幅值、上升沿陡峭度、回落干净程度）。
 *
 * 多击判据：
 *  - 相邻两次敲击间隔 ∈ [MIN_GAP_MS, maxGapMs]；
 *  - 两次峰值比 ∈ [RATIO_MIN, RATIO_MAX] —— 摔落/桌面回弹的第二次冲量通常远小于第一次；
 *  - 达到 need 次触发一次，之后进入 COOLDOWN_MS 冷却，避免一次三击被拆成“双击+单击”。
 *
 * 误触抑制：
 *  - 运动否决：约 0.5s 滑动的平均偏离超过 MOTION_VETO 时视为走路/口袋摩擦，清空连击；
 *  - 上升沿判据：冲击前必须有真正的安静期，慢速抬腕/旋转不会形成合格的上升沿。
 */
object BackTapLogic {

    const val GRAVITY = 9.8f

    /**
     * 同一串连击允许的最小相邻间隔。
     *
     * 低于此值视为「同一次冲击的回弹」（手机磕桌面会弹跳），不计入新的一次。
     * 取 100ms：人正常敲击的最快节奏约 120–150ms，100ms 已经很快；
     * 再快基本是回弹或抖动。
     */
    const val MIN_GAP_MS = 100

    /** 单次冲量允许的最长持续时间。 */
    const val MAX_PULSE_MS = 45

    /** 冲击开始前必须存在的安静窗口。 */
    const val RISE_WINDOW_MS = 40

    /** 释放阈值 = ampThreshold * RELEASE_RATIO，用于判断冲量结束。 */
    const val RELEASE_RATIO = 0.35f

    /** 触发后的冷却时间。 */
    const val COOLDOWN_MS = 350L

    /**
     * 整串连击的**总时长上限**。
     *
     * ── 为什么需要它（"不能累积"）──
     *
     * 只靠相邻间隔（maxGapMs）判断是不够的：用户可能
     *   敲 1 下 → 等 250ms → 敲 1 下 → 等 250ms → 敲 1 下
     * 每一步都在 maxGapMs 之内，于是被凑成"三击" —— 但那其实是三次
     * 无关的随手敲击（比如把手机放到桌上分三次），不是有意连敲。
     *
     * 真正有意的连击，从第一下到最后一下通常不超过 800ms
     * （两下约 150–300ms，三下约 300–600ms）。
     * 超过这个跨度就应当作废重计，而不是继续累积。
     */
    const val MAX_COMBO_SPAN_MS = 800L

    /** 持续运动否决阈值（m/s²，滑动平均）。走路约 1.5~3，静止约 0.05~0.3。 */
    const val MOTION_VETO = 1.1f

    /** 运动均值的一阶低通系数：200Hz 下约 0.5s 时间常数。 */
    const val MOTION_ALPHA = 0.01f

    /** 相邻两次敲击的峰值比允许范围。 */
    const val RATIO_MIN = 0.35f
    const val RATIO_MAX = 2.8f

    const val LEVEL_MIN = 1
    const val LEVEL_MAX = 10
    const val LEVEL_DEFAULT = 7

    data class Params(
        val ampThreshold: Float,
        val maxGapMs: Int,
        val minConfidence: Float
    )

    data class TapEvent(
        /** 事件时间（elapsedRealtime，毫秒）。 */
        val at: Long,
        /** 本次冲量峰值 |‖a‖-g|。 */
        val peak: Float,
        val confidence: Float,
        /** 是否计入连击。 */
        val accepted: Boolean,
        /** 未计入原因；计入时为“有效/重新计数”。 */
        val reason: String,
        /** 当前已累计次数（计入后的值）。 */
        val count: Int,
        val need: Int,
        /** 本次是否凑满并触发。 */
        val triggered: Boolean,
        /** 触发时的敲击次数：2 = 双击，3 = 三击（未触发时为 0）。 */
        val taps: Int = 0
    )

    /** 灵敏度 1..10：越灵敏 → 阈值越低、允许间隔越宽、置信度门槛越低。 */
    fun clampLevel(level: Int): Int = level.coerceIn(LEVEL_MIN, LEVEL_MAX)

    fun paramsForSensitivity(level: Int): Params {
        val k = (clampLevel(level) - 1).toFloat()
        return Params(
            ampThreshold = 4.2f - 0.30f * k,     // 4.2 → 1.5 m/s²
            maxGapMs = (260 + 12 * k).toInt(),   // 260 → 368 ms
            minConfidence = 0.80f - 0.045f * k   // 0.80 → 0.40
        )
    }

    /**
     * 状态机。所有方法都在传感器回调线程内串行调用，无需额外同步。
     */
    class Detector {

        private var params = paramsForSensitivity(LEVEL_DEFAULT)
        private var need = 2

        /**
         * 双模式：双击/三击分开时置 true。
         * 此时达到 2 次不立即触发，而是等「有没有第 3 次」。
         */
        private var dualMode = false

        private var motionAvg = 0f
        private var quietSince = 0L

        private var inImpulse = false
        private var impulseStart = 0L
        private var impulsePeak = 0f
        private var impulsePeakAt = 0L

        private val combo = ArrayList<Long>()      // 时间戳
        private val comboPeak = ArrayList<Float>()
        private val comboConf = ArrayList<Float>()

        private var lastTriggerAt = 0L

        var totalImpulses = 0
            private set
        var acceptedTaps = 0
            private set
        var triggers = 0
            private set

        fun configure(params: Params, need: Int, dual: Boolean = false) {
            this.params = params
            this.need = need.coerceIn(2, 3)
            this.dualMode = dual
            combo.clear()
            comboPeak.clear()
            comboConf.clear()
            inImpulse = false
        }

        fun reset() {
            motionAvg = 0f
            quietSince = 0L
            inImpulse = false
            impulsePeak = 0f
            combo.clear()
            comboPeak.clear()
            comboConf.clear()
            lastTriggerAt = 0L
        }

        /** 每个加速度样本调用一次；只有产生“可报告事件”时才返回非 null。 */
        fun feed(now: Long, deviation: Float): TapEvent? {
            val mag = abs(deviation)
            motionAvg += (mag - motionAvg) * MOTION_ALPHA
            val release = params.ampThreshold * RELEASE_RATIO

            // 运动否决：走路/口袋摩擦时清空连击，并且不开始新的冲量
            if (motionAvg > MOTION_VETO) {
                if (inImpulse) {
                    inImpulse = false
                    impulsePeak = 0f
                }
                combo.clear()
                comboPeak.clear()
                comboConf.clear()
                return null
            }

            if (mag <= release) quietSince = now

            if (!inImpulse) {
                if (mag >= params.ampThreshold && now - quietSince <= RISE_WINDOW_MS) {
                    inImpulse = true
                    impulseStart = now
                    impulsePeak = mag
                    impulsePeakAt = now
                }
                return null
            }

            if (mag > impulsePeak) {
                impulsePeak = mag
                impulsePeakAt = now
            }

            if (mag > release) {
                // 超过最长冲量时长：按住/放下，直接丢弃，不再等待释放
                if (now - impulseStart > MAX_PULSE_MS) {
                    inImpulse = false
                    impulsePeak = 0f
                }
                return null
            }

            // 冲量结束，评估这一次
            inImpulse = false
            val duration = now - impulseStart
            val peak = impulsePeak
            val riseMs = impulsePeakAt - impulseStart
            val decayMs = now - impulsePeakAt
            impulsePeak = 0f
            if (peak < params.ampThreshold) return null

            totalImpulses++
            if (duration > MAX_PULSE_MS) {
                return report(now, peak, 0f, false, "按压过久")
            }
            if (riseMs > 25 || decayMs !in 5..80) {
                return report(now, peak, 0f, false, "波形不符（非敲击）")
            }

            val ampScore = ((peak - params.ampThreshold) / (params.ampThreshold * 1.6f))
                .coerceIn(0f, 1f)
            // 冲击前的持续运动越强，越可能是走路/摩擦，干净度越低
            val cleanScore = (1f - (motionAvg / MOTION_VETO)).coerceIn(0f, 1f)
            val confidence = (0.45f + 0.40f * ampScore + 0.15f * cleanScore).coerceIn(0f, 1f)

            if (confidence < params.minConfidence) {
                return report(now, peak, confidence, false, "置信度不足")
            }
            if (lastTriggerAt > 0 && now - lastTriggerAt < COOLDOWN_MS) {
                return report(now, peak, confidence, false, "冷却中")
            }

            var restart = false
            var restartReason = ""
            if (combo.isNotEmpty()) {
                val gap = now - combo.last()
                val span = now - combo.first()
                val lastPeak = comboPeak.last()
                val ratio = if (lastPeak > 0f) peak / lastPeak else 1f
                when {
                    gap < MIN_GAP_MS ->
                        return report(now, peak, confidence, false, "间隔过短（<${MIN_GAP_MS}ms）")
                    // 相邻太远 → 重新开始
                    gap > params.maxGapMs -> {
                        restart = true
                        restartReason = "间隔过远"
                    }
                    // 整串跨度过长 → 重新开始（防止"隔一会儿敲一下"累积成连击）
                    span > MAX_COMBO_SPAN_MS -> {
                        restart = true
                        restartReason = "整串超时（>${MAX_COMBO_SPAN_MS}ms）"
                    }
                    ratio < RATIO_MIN || ratio > RATIO_MAX ->
                        return report(now, peak, confidence, false, "幅值差异过大")
                }
            }
            if (restart) {
                combo.clear()
                comboPeak.clear()
                comboConf.clear()
            }

            combo.add(now)
            comboPeak.add(peak)
            comboConf.add(confidence)
            acceptedTaps++

            // ── 双模式：敲 2 下不立即触发，等「有没有第 3 下」──
            //
            // 若立即在 2 下时触发，用户想敲 3 下就会被双击抢占（第 3 下到达时
            // 双击的服务已经切过去了）。所以双模式下 2 下只报告「计数」，
            // 真正的判定交给 flush()（由上层在一个 maxGap 之后调用）。
            if (dualMode) {
                if (combo.size >= 3) {
                    triggers++
                    lastTriggerAt = now
                    val ev = TapEvent(
                        at = now, peak = peak, confidence = confidence,
                        accepted = true, reason = "三击", count = 3, need = 3,
                        triggered = true, taps = 3
                    )
                    combo.clear(); comboPeak.clear(); comboConf.clear()
                    return ev
                }
                return report(
                    now, peak, confidence, true,
                    if (restart) "重新计数" else "有效", count = combo.size
                )
            }

            return if (combo.size >= need) {
                triggers++
                lastTriggerAt = now
                val event = TapEvent(
                    at = now, peak = peak, confidence = confidence,
                    accepted = true, reason = "有效", count = need, need = need,
                    triggered = true, taps = need
                )
                combo.clear()
                comboPeak.clear()
                comboConf.clear()
                event
            } else {
                report(
                    now, peak, confidence, true,
                    if (restart && restartReason.isNotEmpty()) "重新计数（$restartReason）" else "有效",
                    count = combo.size
                )
            }
        }

        /**
         * 双模式下由上层定时调用：确认期过去后，若还停在 2 次就判定为双击。
         *
         * @param now 当前时刻
         * @return 触发事件；不需要触发时返回 null
         */
        fun flush(now: Long): TapEvent? {
            if (!dualMode) return null
            if (combo.size < 2) return null
            // 距最后一次敲击还不够久 → 继续等（用户可能正在敲第 3 下）
            if (now - combo.last() < params.maxGapMs) return null

            val taps = combo.size
            val lastPeak = comboPeak.lastOrNull() ?: 0f
            val lastConf = comboConf.lastOrNull() ?: 0f
            val at = combo.last()
            combo.clear(); comboPeak.clear(); comboConf.clear()

            if (taps != 2) return null
            triggers++
            lastTriggerAt = now
            return TapEvent(
                at = at, peak = lastPeak, confidence = lastConf,
                accepted = true, reason = "双击", count = 2, need = 2,
                triggered = true, taps = 2
            )
        }

        /** 双模式下当前是否还挂着未判定的连击（用于上层决定是否继续轮询）。 */
        fun hasPendingCombo(): Boolean = combo.size >= 2

        private fun report(
            now: Long,
            peak: Float,
            confidence: Float,
            accepted: Boolean,
            reason: String,
            count: Int = combo.size
        ) = TapEvent(
            at = now,
            peak = peak,
            confidence = confidence,
            accepted = accepted,
            reason = reason,
            count = count,
            need = need,
            triggered = false
        )
    }
}
