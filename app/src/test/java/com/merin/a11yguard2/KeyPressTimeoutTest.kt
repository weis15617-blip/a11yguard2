package com.merin.a11yguard2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音量键「双击 / 三击 分开服务」的判定测试。
 *
 * ══ 两个独立窗口（关键设计）══
 *
 *   手势窗口：用户设定 100–600ms → 判定双击/三击（总时长语义）
 *   调音量窗口：固定 900ms → 判定"按了 4 下以上"
 *
 * 为什么必须分开：若用 200ms 手势窗口去判"4 下 = 调音量"，
 * 第 4 下早超窗被当成新序列第 1 下，永远数不到 4 →
 * 调音量时每 2 下就误触发一次双击。
 */
class KeyPressTimeoutTest {

    private val WIN = 300

    /** 完整序列：按下 → 等窗口结束 → 判定。返回 0/2/3。 */
    private fun sequence(times: List<Long>, win: Int = WIN): Int {
        if (times.isEmpty()) return 0
        var count = 0
        var first = 0L
        var last = 0L
        var rapid = 0
        var rapidFirst = 0L
        var pending = false
        for (t in times) {
            val r = KeyPressLogic.press(count, first, last, t, win, rapid, rapidFirst)
            if (r.cancelled) return 0
            count = r.count
            first = r.firstTs
            last = r.lastTs
            rapid = r.rapidCount
            rapidFirst = r.rapidFirst
            pending = r.pendingFire
        }
        if (!pending) return 0
        val elapsed = last - first
        return if (elapsed < win.coerceAtLeast(KeyPressLogic.MIN_WINDOW_MS)) {
            KeyPressLogic.resolve(KeyPressLogic.Result(count, first, last, true, false, 0))
        } else 0
    }

    // ══════════ 窗口是总时长 ══════════

    @Test
    fun windowIsTotalSpan_notAdjacentGap() {
        // 三下 0/80/160：相邻都是 80ms，总跨度 160ms < 200ms → 三击
        assertEquals(3, sequence(listOf(0L, 80L, 160L), win = 200))
    }

    @Test
    fun totalSpanExceedsWindow_restarts() {
        // 三下 0/100/250：相邻 <200 但总跨度 250 > 200 → 第三下开新序列
        assertEquals("总跨度超窗，第三下应重开（不触发）", 0, sequence(listOf(0L, 100L, 250L), win = 200))
    }

    @Test
    fun waitMsEqualsRemainingWindow() {
        val a = KeyPressLogic.press(0, 0, 0, 0, 300)
        val b = KeyPressLogic.press(a.count, a.firstTs, a.lastTs, 100, 300)
        assertTrue(b.pendingFire)
        assertEquals("等待量 = 窗口剩余", 200L, b.waitMs)
    }

    @Test
    fun waitMsOnNewSequence_isZero() {
        // 超窗后重开：count 回到 1，不在等待期，waitMs 无意义（0）
        val a = KeyPressLogic.press(0, 0, 0, 0, 300)
        val b = KeyPressLogic.press(a.count, a.firstTs, a.lastTs, 400, 300)
        assertEquals("重开后 count=1", 1, b.count)
        assertFalse("重开后不在等待期", b.pendingFire)
        assertEquals("不需要等待", 0L, b.waitMs)
    }

    // ══════════ 双击 vs 三击 ══════════

    @Test
    fun twoPresses_resolvesToDouble() {
        assertEquals(2, sequence(listOf(1000L, 1200L)))
    }

    @Test
    fun threePresses_resolvesToTriple() {
        assertEquals(3, sequence(listOf(1000L, 1100L, 1200L)))
    }

    @Test
    fun secondPress_neverFiresImmediately() {
        val a = KeyPressLogic.press(0, 0, 0, 1000, WIN)
        val b = KeyPressLogic.press(a.count, a.firstTs, a.lastTs, 1200, WIN)
        assertTrue("第 2 下必须等待，不能立即触发", b.pendingFire)
    }

    // ══════════ 调音量判定（独立窗口）══════════

    @Test
    fun fourPressesWithSlowRhythm_cancelled() {
        // 4 下，每 200ms 一下：手势窗口 300ms 会重开序列，
        // 但调音量窗口 900ms 能数到 4 → 作废
        assertEquals("4 下应判调音量", 0, sequence(listOf(1000L, 1200L, 1400L, 1600L), win = 300))
    }

    @Test
    fun fourFastPresses_cancelled() {
        assertEquals(0, sequence(listOf(1000L, 1100L, 1200L, 1300L), win = 300))
    }

    @Test
    fun fivePresses_cancelled() {
        assertEquals(0, sequence(listOf(1000L, 1080L, 1160L, 1240L, 1320L), win = 300))
    }

    @Test
    fun fourPressesSpreadBeyond900ms_notVolume() {
        // 每 400ms 一下，总跨 1200ms > 900ms → 不算调音量
        val r = sequence(listOf(0L, 400L, 800L, 1200L), win = 300)
        // 每次都在新序列里，且间隔 400 > 300 窗口 → 每下都是第 1 下 → 不触发
        assertEquals("间隔过大，每下都是新序列，不触发", 0, r)
    }

    @Test
    fun threeIsNotCancelled() {
        val a = KeyPressLogic.press(0, 0, 0, 1000, WIN)
        val b = KeyPressLogic.press(a.count, a.firstTs, a.lastTs, 1100, WIN, a.rapidCount, a.rapidFirst)
        val c = KeyPressLogic.press(b.count, b.firstTs, b.lastTs, 1200, WIN, b.rapidCount, b.rapidFirst)
        assertFalse("三击不应被取消", c.cancelled)
        assertEquals(3, c.count)
    }

    @Test
    fun volumeWindowIsIndependentOfGestureWindow() {
        // 关键回归：手势窗口 100ms（最快档），但 4 下慢按仍要判调音量
        assertEquals(
            "手势窗口 100ms 时 4 下慢按仍应判调音量",
            0,
            sequence(listOf(1000L, 1200L, 1400L, 1600L), win = 100)
        )
    }

    // ══════════ 窗口边界 ══════════

    @Test
    fun firstPress_startsNewSequence() {
        val r = KeyPressLogic.press(0, 0, 0, 10_000, WIN)
        assertEquals(1, r.count)
        assertEquals(10_000L, r.firstTs)
        assertFalse(r.pendingFire)
    }

    @Test
    fun exactlyAtWindow_isNewSequence() {
        val r = KeyPressLogic.press(1, 10_000, 10_000, 10_300, WIN, 1, 10_000)
        assertEquals("正好 300ms 应重开", 1, r.count)
        assertEquals(10_300L, r.firstTs)
    }

    @Test
    fun justInsideWindow_keepsCounting() {
        val r = KeyPressLogic.press(1, 10_000, 10_000, 10_299, WIN, 1, 10_000)
        assertEquals(2, r.count)
        assertTrue(r.pendingFire)
    }

    @Test
    fun timestampZero_worksAsFirstPress() {
        // 回归：早期用 firstTs>0 当哨兵，导致 t=0 的第一下被判为空序列
        val r = KeyPressLogic.press(0, 0, 0, 0, WIN)
        assertEquals(1, r.count)
        assertEquals(0L, r.firstTs)
    }

    // ══════════ 最快 100ms ══════════

    @Test
    fun window100_twoPressesIn80ms_works() {
        assertEquals(2, sequence(listOf(1000L, 1080L), win = 100))
    }

    @Test
    fun window100_twoPressesIn150ms_fails() {
        assertEquals("150ms > 100ms 窗口", 0, sequence(listOf(1000L, 1150L), win = 100))
    }

    @Test
    fun windowBelow100_isClamped() {
        assertEquals(2, sequence(listOf(1000L, 1080L), win = 50))
    }

    @Test
    fun minWindowConstantIs100() {
        assertEquals(100, KeyPressLogic.MIN_WINDOW_MS)
    }

    @Test
    fun defaultWindowIs300() {
        assertEquals(300, KeyPressLogic.DEFAULT_WINDOW_MS)
    }

    // ══════════ resolve 语义 ══════════

    @Test
    fun resolve_ignoresSinglePress() {
        assertEquals(0, KeyPressLogic.resolve(KeyPressLogic.Result(1, 0, 0, true, false, 0)))
    }

    @Test
    fun resolve_ignoresCancelled() {
        assertEquals(0, KeyPressLogic.resolve(KeyPressLogic.Result(2, 0, 0, false, false, 0)))
    }

    @Test
    fun resolve_fourIsNotAGesture() {
        assertEquals(0, KeyPressLogic.resolve(KeyPressLogic.Result(4, 0, 0, true, false, 0)))
    }

    @Test
    fun confirm_isDoubleOnly() {
        assertTrue(KeyPressLogic.confirm(KeyPressLogic.Result(2, 0, 0, true, false, 0)))
        assertFalse(KeyPressLogic.confirm(KeyPressLogic.Result(3, 0, 0, true, false, 0)))
    }

    // ══════════ 目标选择 ══════════

    private fun pickTarget(taps: Int, dbl: String, triple: String): String = when {
        taps == 3 -> when {
            triple.isNotEmpty() -> triple
            dbl.isNotEmpty() -> dbl
            else -> ""
        }
        else -> when {
            dbl.isNotEmpty() -> dbl
            triple.isNotEmpty() -> triple
            else -> ""
        }
    }

    @Test
    fun pickTarget_onlyTripleConfigured_stillWorks() {
        assertEquals("tm", pickTarget(3, dbl = "", triple = "tm"))
        assertEquals("tm", pickTarget(2, dbl = "", triple = "tm"))
    }

    @Test
    fun pickTarget_onlyDoubleConfigured() {
        assertEquals("tmm", pickTarget(2, dbl = "tmm", triple = ""))
        assertEquals("tmm", pickTarget(3, dbl = "tmm", triple = ""))
    }

    @Test
    fun pickTarget_bothConfigured_keepsThemSeparate() {
        assertEquals("dbl", pickTarget(2, dbl = "dbl", triple = "trl"))
        assertEquals("trl", pickTarget(3, dbl = "dbl", triple = "trl"))
    }

    @Test
    fun pickTarget_neverReturnsEmptyWhenSomethingConfigured() {
        for (dbl in listOf("", "d")) {
            for (triple in listOf("", "t")) {
                if (dbl.isEmpty() && triple.isEmpty()) continue
                assertTrue(pickTarget(2, dbl, triple).isNotEmpty())
                assertTrue(pickTarget(3, dbl, triple).isNotEmpty())
            }
        }
    }
}
