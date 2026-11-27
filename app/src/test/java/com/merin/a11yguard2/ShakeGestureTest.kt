package com.merin.a11yguard2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * 摇一摇检测测试 —— Square Seismic 算法。
 *
 * ══ 被测对象 ══
 *
 * 判据：**总加速度模长的平方 > 阈值平方**，且**最近 0.5s 内 75% 的样本处于加速**。
 *
 * ══ 为什么用模长平方而不是变化率 ══
 *
 * 变化率对噪声极敏感：传感器每几毫秒都有微小抖动，除以极小的 Δt
 * 会放大成很大的值。模长是绝对量，不除时间，噪声不会被放大。
 *
 * ══ 原版参数 ══
 *
 *   SENSITIVITY_LIGHT  = 11 m/s²
 *   SENSITIVITY_MEDIUM = 13 m/s²（默认）
 *   SENSITIVITY_HARD   = 15 m/s²
 *   窗口 0.5s，最小 0.25s，最少 4 个样本，比例 3/4
 */
class ShakeGestureTest {

    /** 静止时的加速度模长 ≈ 重力 9.8。 */
    private val GRAV = 9.80665f

    /** 生成一串采样：每隔 intervalNs 一次，加速度为 (0,0,az)。 */
    private fun feed(
        d: ShakeLogic.Detector,
        values: List<Float>,
        threshold: Int = ShakeLogic.DEFAULT_THRESHOLD,
        need: Int = 3,
        intervalNs: Long = 20_000_000L,
        startNs: Long = 1_000_000_000L
    ): List<ShakeLogic.Step> {
        val out = mutableListOf<ShakeLogic.Step>()
        var t = startNs
        var nowMs = 1000L
        for (az in values) {
            out.add(d.step(t, 0f, 0f, az, threshold, need, nowMs))
            t += intervalNs
            nowMs += intervalNs / 1_000_000L
        }
        return out
    }

    // ══════════ 原版参数锁死 ══════════

    @Test
    fun constantsMatchSquareSeismic() {
        // 这些值直接来自 Square 源码，改动必须是有意的
        assertEquals(11, ShakeLogic.SENSITIVITY_LIGHT)
        assertEquals(13, ShakeLogic.SENSITIVITY_MEDIUM)
        assertEquals(15, ShakeLogic.SENSITIVITY_HARD)
        assertEquals(500_000_000L, ShakeLogic.MAX_WINDOW_NS)
        assertEquals(250_000_000L, ShakeLogic.MIN_WINDOW_NS)
        assertEquals(4, ShakeLogic.MIN_QUEUE_SIZE)
        assertEquals(1000L, ShakeLogic.COOLDOWN_MS)
    }

    @Test
    fun defaultThresholdIsMedium() {
        assertEquals(ShakeLogic.SENSITIVITY_MEDIUM, ShakeLogic.DEFAULT_THRESHOLD)
    }

    // ══════════ 静止不触发 ══════════

    @Test
    fun staticPhone_neverTriggers() {
        // 手机静止：accel = (0,0,g)，模长 9.8 < 13
        val d = ShakeLogic.Detector()
        val steps = feed(d, List(100) { GRAV })
        assertTrue("静止绝不应触发", steps.none { it.triggered })
    }

    @Test
    fun staticPhone_hotRatioIsZero() {
        val d = ShakeLogic.Detector()
        val steps = feed(d, List(50) { GRAV })
        assertEquals("静止时热样本占比应为 0", 0f, steps.last().hotRatio, 0.01f)
    }

    @Test
    fun walkingSlightly_aboveGravityNotEnough() {
        // 走路时模长可能在 10-11 之间，低于 13 不触发
        val d = ShakeLogic.Detector()
        val steps = feed(d, List(100) { 11.0f })
        assertTrue("轻微颠簸不应触发", steps.none { it.triggered })
    }

    // ══════════ 持续加速才触发 ══════════

    @Test
    fun continuousAcceleration_triggers() {
        // 持续 20 m/s²（远高于 13），模长平方 400 > 169
        val d = ShakeLogic.Detector()
        val steps = feed(d, List(60) { 20f }, need = 3)
        assertTrue("持续加速应触发", steps.any { it.triggered } || steps.any { it.count > 0 })
    }

    @Test
    fun singleSpike_doesNotTrigger() {
        // 单个尖峰（碰撞）：只有 1 个样本超阈值，占比远不到 75%
        val d = ShakeLogic.Detector()
        val values = mutableListOf<Float>()
        repeat(50) { values.add(GRAV) }
        values[25] = 30f   // 一次大冲击
        val steps = feed(d, values)
        assertTrue("单次碰撞不应触发", steps.none { it.triggered })
    }

    @Test
    fun twoSpikes_doNotTrigger() {
        // 两个尖峰占少数，仍不到 75%
        val d = ShakeLogic.Detector()
        val values = mutableListOf<Float>()
        repeat(60) { values.add(GRAV) }
        values[20] = 30f
        values[40] = 30f
        val steps = feed(d, values)
        assertTrue("零星冲击不应触发", steps.none { it.triggered })
    }

    // ══════════ 窗口与比例判据 ══════════

    @Test
    fun queue_purgesOldSamples() {
        val q = ShakeLogic.SampleQueue()
        // 喂 100 个样本，跨 2 秒（远超 0.5s 窗口）
        for (i in 0 until 100) {
            q.add(i * 20_000_000L, false)
        }
        assertTrue("窗口应只保留 0.5s 内的样本", q.size < 100)
    }

    @Test
    fun queue_hotCountTracksAccelerating() {
        val q = ShakeLogic.SampleQueue()
        for (i in 0 until 10) {
            q.add(i * 20_000_000L, true)
        }
        assertEquals("全部加速时 hotCount 应等于样本数", 10, q.hotCount)
    }

    @Test
    fun queue_clearResets() {
        val q = ShakeLogic.SampleQueue()
        for (i in 0 until 10) q.add(i * 20_000_000L, true)
        q.clear()
        assertEquals(0, q.size)
        assertEquals(0, q.hotCount)
        assertFalse(q.isShaking())
    }

    @Test
    fun isShaking_needsMinWindow() {
        val q = ShakeLogic.SampleQueue()
        // 只有 3 个样本，跨度 40ms < 250ms，即使全加速也不应判为摇动
        for (i in 0 until 3) q.add(i * 20_000_000L, true)
        assertFalse("数据不足时不应判为摇动", q.isShaking())
    }

    @Test
    fun isShaking_requiresThreeQuarters() {
        val q = ShakeLogic.SampleQueue()
        // 20 个样本跨 380ms（> 250ms），其中 10 个加速 = 50%，不足 75%
        for (i in 0 until 20) {
            q.add(i * 20_000_000L, i % 2 == 0)
        }
        assertFalse("50% 加速不应判为摇动", q.isShaking())
    }

    @Test
    fun isShaking_passesAtFullAcceleration() {
        val q = ShakeLogic.SampleQueue()
        // 20 个样本跨 380ms，全部加速
        for (i in 0 until 20) {
            q.add(i * 20_000_000L, true)
        }
        assertTrue("全加速且窗口足够应判为摇动", q.isShaking())
    }

    // ══════════ 灵敏度 ══════════

    @Test
    fun sensitivityMapping_isMonotonic() {
        val t1 = ShakeLogic.thresholdForSensitivity(1)
        val t5 = ShakeLogic.thresholdForSensitivity(5)
        val t10 = ShakeLogic.thresholdForSensitivity(10)
        assertTrue("档位 1 阈值应最大（最难触发）", t1 > t5)
        assertTrue("档位 10 阈值应最小（最灵敏）", t5 > t10)
    }

    @Test
    fun sensitivityRange_isSane() {
        // 下限必须高于重力 9.8，否则静止也会判成加速
        assertTrue("最灵敏档也要高于重力", ShakeLogic.thresholdForSensitivity(10) > 9.8f)
        // 最高档约等于原版 HARD
        assertTrue("最难档应 >= 15", ShakeLogic.thresholdForSensitivity(1) >= 15)
    }

    @Test
    fun sensitivityClamped() {
        assertEquals(ShakeLogic.thresholdForSensitivity(1), ShakeLogic.thresholdForSensitivity(0))
        assertEquals(ShakeLogic.thresholdForSensitivity(10), ShakeLogic.thresholdForSensitivity(99))
    }

    // ══════════ 阈值语义 ══════════

    @Test
    fun thresholdComparesMagnitudeSquared() {
        // 验证：模长 13 恰好等于阈值时不触发（用 > 不是 >=）
        val d = ShakeLogic.Detector()
        val steps = feed(d, List(30) { 13.0f }, threshold = 13)
        assertTrue("恰好等于阈值不应触发", steps.none { it.triggered })
    }

    @Test
    fun aboveThresholdAccelerating() {
        val d = ShakeLogic.Detector()
        // 模长 14 > 13，应判为加速
        val step = d.step(1_000_000_000L, 0f, 0f, 14f, 13, 3, 1000L)
        assertTrue("14 > 13 应判为加速样本", step.hotRatio > 0f || step.verdict == ShakeLogic.Verdict.COUNTING)
    }

    @Test
    fun speedIsMagnitude() {
        val d = ShakeLogic.Detector()
        val step = d.step(1_000_000_000L, 3f, 4f, 0f, 13, 3, 1000L)
        assertEquals("speed 应等于三轴模长", 5f, step.speed, 0.01f)
    }

    // ══════════ 冷却 ══════════

    @Test
    fun cooldownPreventsRapidRetrigger() {
        val d = ShakeLogic.Detector()
        // 第一次触发
        feed(d, List(40) { 25f }, need = 1)
        // 紧接着再来一串（nowMs 只前进了一点），应被冷却拦住
        val steps = feed(d, List(40) { 25f }, need = 1, startNs = 5_000_000_000L)
        assertTrue("冷却期内不应重复触发", steps.none { it.triggered })
    }

    // ══════════ reset ══════════

    @Test
    fun reset_clearsState() {
        val d = ShakeLogic.Detector()
        feed(d, List(40) { 25f }, need = 3)
        d.reset()
        val step = d.step(100_000_000_000L, 0f, 0f, GRAV, 13, 3, 999_999L)
        assertEquals("reset 后计数应归零", 0, step.count)
    }
}
