package com.merin.a11yguard2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BackTapLogicTest {

    private fun detector(level: Int = BackTapLogic.LEVEL_DEFAULT, need: Int = 2): BackTapLogic.Detector =
        BackTapLogic.Detector().apply {
            configure(BackTapLogic.paramsForSensitivity(level), need)
        }

    /**
     * 合成一次敲击波形：0.2 → 峰值 → 半峰 → 释放 → 0（5ms 采样）。
     * 返回释放时刻，作为这一击的组合时间戳。
     */
    private fun tap(
        d: BackTapLogic.Detector,
        start: Long,
        peak: Float = 4.0f,
        out: MutableList<BackTapLogic.TapEvent>
    ): Long {
        d.feed(start, 0.2f)?.let { out.add(it) }
        d.feed(start + 5, peak)?.let { out.add(it) }
        d.feed(start + 10, peak * 0.5f)?.let { out.add(it) }
        d.feed(start + 15, 0.3f)?.let { out.add(it) }
        d.feed(start + 20, 0f)?.let { out.add(it) }
        return start + 15
    }

    private fun idle(d: BackTapLogic.Detector, from: Long, to: Long, out: MutableList<BackTapLogic.TapEvent>) {
        var t = from
        while (t <= to) {
            d.feed(t, 0f)?.let { out.add(it) }
            t += 5
        }
    }

    @Test
    fun sensitivity_mappingIsMonotonic() {
        val low = BackTapLogic.paramsForSensitivity(1)
        val high = BackTapLogic.paramsForSensitivity(10)
        // 越灵敏：振幅阈值越低、允许间隔越宽、置信度门槛越低
        assertTrue(high.ampThreshold < low.ampThreshold)
        assertTrue(high.maxGapMs > low.maxGapMs)
        assertTrue(high.minConfidence < low.minConfidence)
        assertEquals(4.2f, low.ampThreshold, 0.001f)
        assertEquals(1.5f, high.ampThreshold, 0.001f)
        // 越界档位被夹紧
        assertEquals(BackTapLogic.paramsForSensitivity(1), BackTapLogic.paramsForSensitivity(-5))
        assertEquals(BackTapLogic.paramsForSensitivity(10), BackTapLogic.paramsForSensitivity(99))
    }

    @Test
    fun singleTap_isAcceptedButNotTriggered() {
        val d = detector(need = 2)
        val events = mutableListOf<BackTapLogic.TapEvent>()
        tap(d, 1_000, out = events)
        assertEquals(1, events.size)
        val ev = events.first()
        assertTrue(ev.accepted)
        assertFalse(ev.triggered)
        assertEquals(1, ev.count)
        assertEquals(0, d.triggers)
    }

    @Test
    fun doubleTap_withinWindow_triggers() {
        val d = detector(level = 7, need = 2)
        val events = mutableListOf<BackTapLogic.TapEvent>()
        val t1 = tap(d, 1_000, out = events)
        idle(d, t1 + 5, 1_150, events)
        tap(d, 1_150, out = events)
        assertEquals(1, d.triggers)
        assertTrue(events.last().triggered)
        assertEquals(2, events.last().need)
    }

    @Test
    fun doubleTap_tooSlow_isRestartNotTrigger() {
        val d = detector(level = 7, need = 2)
        val events = mutableListOf<BackTapLogic.TapEvent>()
        val t1 = tap(d, 1_000, out = events)
        idle(d, t1 + 5, 1_500, events)
        tap(d, 1_500, out = events)
        // 超出 maxGapMs（默认档 332ms）后重新计数，不应触发
        assertEquals(0, d.triggers)
        assertTrue(events.last().accepted)
        assertEquals(1, events.last().count)
        assertEquals("重新计数", events.last().reason)
    }

    @Test
    fun tripleTap_triggersOnlyOnThird() {
        val d = detector(level = 7, need = 3)
        val events = mutableListOf<BackTapLogic.TapEvent>()
        val t1 = tap(d, 1_000, out = events)
        idle(d, t1 + 5, 1_150, events)
        val t2 = tap(d, 1_150, out = events)
        assertEquals(0, d.triggers)
        idle(d, t2 + 5, 1_300, events)
        tap(d, 1_300, out = events)
        assertEquals(1, d.triggers)
        assertTrue(events.last().triggered)
        assertEquals(3, events.last().count)
    }

    @Test
    fun pressAndHold_isRejected() {
        val d = detector(need = 2)
        val events = mutableListOf<BackTapLogic.TapEvent>()
        d.feed(1_000, 0.5f)
        var t = 1_005L
        var n = 0
        while (t <= 1_060) {
            d.feed(t, 4.0f)?.let { events.add(it) }
            t += 5
            n++
        }
        assertEquals(12, n)
        assertEquals(0, d.acceptedTaps)
        assertEquals(0, d.triggers)
        assertTrue(events.isEmpty())
    }

    @Test
    fun longPush_releasedAfterLimit_isReportedNotAccepted() {
        val d = detector(need = 2)
        val out = mutableListOf<BackTapLogic.TapEvent>()
        d.feed(2_000, 0.5f)?.let { out.add(it) }
        d.feed(2_005, 4.0f)?.let { out.add(it) }
        d.feed(2_055, 0.3f)?.let { out.add(it) }   // 持续 50ms > MAX_PULSE_MS
        assertEquals(1, out.size)
        assertFalse(out.first().accepted)
        assertEquals("按压过久", out.first().reason)
        assertEquals(0, d.acceptedTaps)
        assertEquals(0, d.triggers)
    }

    @Test
    fun amplitudeRatioTooLarge_isRejected() {
        // 10 档阈值 1.5，第二击 1.6 刚过阈值；峰值比 1.6/6.0 = 0.27 < 0.35
        val d = detector(level = 10, need = 2)
        val events = mutableListOf<BackTapLogic.TapEvent>()
        val t1 = tap(d, 1_000, peak = 6.0f, out = events)
        idle(d, t1 + 5, 1_150, events)
        tap(d, 1_150, peak = 1.6f, out = events)
        assertEquals(0, d.triggers)
        assertFalse(events.last().accepted)
        assertEquals("幅值差异过大", events.last().reason)
    }

    @Test
    fun walkingMotion_isVetoed() {
        val d = detector(level = 10, need = 2)
        val events = mutableListOf<BackTapLogic.TapEvent>()
        // 2Hz、±3 m/s² 的“走路”正弦，跑 4 秒
        var t = 0L
        while (t <= 4_000) {
            val phase = 2.0 * Math.PI * 2.0 * (t / 1000.0)
            d.feed(t, (3.0 * kotlin.math.sin(phase)).toFloat())?.let { events.add(it) }
            t += 5
        }
        assertEquals(0, d.triggers)
        assertEquals(0, d.acceptedTaps)
    }

    @Test
    fun reset_clearsComboWithoutTriggering() {
        val d = detector(need = 2)
        val events = mutableListOf<BackTapLogic.TapEvent>()
        val t1 = tap(d, 1_000, out = events)
        idle(d, t1 + 5, 1_100, events)
        d.reset()
        tap(d, 1_120, out = events)
        assertEquals(0, d.triggers)
        assertEquals(1, events.last().count)
    }

    @Test
    fun weakTap_belowThreshold_isSilent() {
        val d = detector(level = 7)   // 阈值 2.4
        var t = 0L
        while (t <= 500) {
            assertNull(d.feed(t, 1.2f))
            t += 5
        }
        assertEquals(0, d.totalImpulses)
        assertEquals(0, d.acceptedTaps)
    }

    @Test
    fun peakReported_isAbsoluteDeviation() {
        val d = detector(level = 7)
        val events = mutableListOf<BackTapLogic.TapEvent>()
        val t1 = tap(d, 1_000, peak = 4.0f, out = events)
        assertEquals(4.0f, events.first().peak, 0.001f)
        assertTrue(abs(t1 - 1_015) < 1)
    }
}
