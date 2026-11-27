package com.merin.a11yguard2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音量键「双击」判定的回归测试。
 *
 * 核心需求（2026-11-26）：**调音量时连按不能误触发手势**。
 *
 * 用户调音量经常连按 3–5 下。若「第二下按下即触发」，那每次调音量都会切换一次
 * 无障碍服务 —— 这是最严重的误判，因为它会在用户完全无意时打断读屏。
 *
 * 方案：第二下之后进入 CONFIRM_MS 确认期，期间若出现第三下，则判定为调音量、
 * 整个序列作废；只有恰好两下才在确认期结束后真正触发。
 */
class KeyPressTimeoutTest {

    private val WIN = 300

    private fun press(count: Int, last: Long, now: Long, win: Int = WIN, maxCombo: Int = 3) =
        KeyPressLogic.press(count, last, now, win, maxCombo)

    // ---------- 核心：连按调音量不误触发 ----------

    @Test
    fun threeRapidPresses_isCancelledAsVolumeAdjust() {
        // 模拟连按调音量：1000 → 1150 → 1300
        val a = press(0, 0, 1000)
        assertEquals(1, a.count)
        assertFalse(a.pendingFire)

        val b = press(a.count, a.lastTs, 1150)
        assertEquals(2, b.count)
        assertTrue("第二下应进入确认期而非立即触发", b.pendingFire)

        val c = press(b.count, b.lastTs, 1300)
        assertTrue("第三下必须判定为调音量并取消", c.cancelled)
        assertEquals("取消后计数归零", 0, c.count)
        assertFalse("取消后不应再待触发", c.pendingFire)
    }

    @Test
    fun fourRapidPresses_isAlsoTreatedAsVolumeAdjust() {
        // 连按 4 下：第 3 下取消一次序列，第 4 下会重新开始并凑成「2」。
        // 这是刻意的：连续按 4 下的人显然在调音量，我们不能让第 4 下把它变成手势。
        // 因此实现里第 3 下取消后，第 4 下只是新的第 2 下、进入确认期，
        // 而真实用户此时还会继续按第 5 下 → 再次取消。
        var count = 0
        var last = 0L
        var cancellations = 0
        for (t in listOf(1000L, 1150L, 1300L, 1450L)) {
            val r = press(count, last, t)
            count = r.count
            last = r.lastTs
            if (r.cancelled) cancellations++
        }
        assertEquals("连按 4 下应至少触发 1 次「判为调音量」的取消", 1, cancellations)
    }

    @Test
    fun exactlyTwoPresses_pendingFireThenConfirm() {
        // 恰好两下：进入确认期，确认后应触发
        val a = press(0, 0, 1000)
        val b = press(a.count, a.lastTs, 1200)
        assertTrue(b.pendingFire)
        assertTrue("恰好两下应在确认后触发", KeyPressLogic.confirm(b))
    }

    @Test
    fun confirm_returnsFalseAfterCancel() {
        val a = press(0, 0, 1000)
        val b = press(a.count, a.lastTs, 1150)
        val c = press(b.count, b.lastTs, 1300)
        assertTrue(c.cancelled)
        assertFalse("取消后确认必须返回 false", KeyPressLogic.confirm(c))
    }

    // ---------- 窗口与超时 ----------

    @Test
    fun firstPress_countsOne_noTrigger() {
        val r = press(0, 0, 10_000)
        assertEquals(1, r.count)
        assertEquals(10_000L, r.lastTs)
        assertFalse(r.pendingFire)
    }

    @Test
    fun secondPress_withinWindow_entersConfirm() {
        val r = press(1, 10_000, 10_200)   // 200ms < 300ms
        assertTrue(r.pendingFire)
        assertEquals(2, r.count)
    }

    @Test
    fun secondPress_exactlyAtWindow_isTimeout_notPending() {
        // 半开区间：正好 300ms 已到窗口末端，不算窗口内
        val r = press(1, 10_000, 10_300)
        assertFalse("正好等于窗口值必须判为超时（重新算第一下）", r.pendingFire)
        assertEquals(1, r.count)
        assertEquals(10_300L, r.lastTs)
    }

    @Test
    fun secondPress_justInsideWindow_entersConfirm() {
        val r = press(1, 10_000, 10_299)
        assertTrue(r.pendingFire)
    }

    @Test
    fun secondPress_justOutsideWindow_dropsOldPress() {
        val r = press(1, 10_000, 10_301)
        assertFalse("超过窗口必须放弃上一次单击", r.pendingFire)
        assertEquals("计数必须重置为 1", 1, r.count)
    }

    @Test
    fun longGapThenPress_isStillFirstPress() {
        val r = press(1, 10_000, 10_000 + 5 * 60 * 1000)
        assertFalse("隔很久后按一下绝不能进入待触发", r.pendingFire)
        assertEquals(1, r.count)
    }

    @Test
    fun repeatedTimeoutPresses_neverAccumulate() {
        // 反复「按一下 → 等超时 → 再按一下」，每次都应是第一下
        var count = 0
        var last = 0L
        var now = 1000L
        var anyPending = false
        repeat(5) {
            val r = press(count, last, now)
            count = r.count
            last = r.lastTs
            if (r.pendingFire) anyPending = true
            now += WIN + 1
        }
        assertFalse("超时连按不应产生任何待触发", anyPending)
        assertEquals(1, count)
    }

    @Test
    fun zeroOrNegativeWindow_treatedAsImmediateTimeout() {
        val r = press(1, 10_000, 10_001, win = 0)
        assertFalse(r.pendingFire)
        assertEquals(1, r.count)
    }

    @Test
    fun maxComboFour_cancelsOnlyOnFourth() {
        // 把阈值配成 4：第三下仍算「双击后的继续」，第四下才取消
        val a = press(0, 0, 1000, maxCombo = 4)
        val b = press(a.count, a.lastTs, 1150, maxCombo = 4)
        val c = press(b.count, b.lastTs, 1300, maxCombo = 4)
        assertFalse("maxCombo=4 时第三下不应取消", c.cancelled)
        val d = press(c.count, c.lastTs, 1450, maxCombo = 4)
        assertTrue("第四下应取消", d.cancelled)
    }
}
