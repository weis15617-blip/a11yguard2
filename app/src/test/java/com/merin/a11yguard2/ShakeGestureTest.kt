package com.merin.a11yguard2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 摇一摇判定测试。
 *
 * 采用业界经典实现（Matthew Wiggins 2009, Apache-2.0）：
 *
 *     speed = |Δ(x+y+z)| / Δt × 10000
 *     if (speed > 350) shakeCount++
 *     if (shakeCount >= 3) 触发
 *
 * 核心洞察：**用「变化率 + 连续计数」区分甩动与撞击**。
 *
 *   动作        Δ(x+y+z)  Δt     speed    超阈值次数
 *   甩动          ±20     20ms   10000    连续多次 → 触发
 *   拿起手机       0.2    20ms     100     0 次 → 不触发
 *   放到桌上        30     20ms   15000    只 1 次 → 不触发
 *
 * 「放到桌上」峰值比甩动更猛，但只发生一次 —— 这正是我前几版方案
 * （力度阈值/符号翻转/点积方向/爆发计数）全部失败的原因：
 * 它们都在用单点特征描述一个本质上是「重复」的动作。
 */
class ShakeGestureTest {

    private val G = 9.8f

    private fun run(
        samples: List<Triple<Long, Float, Float>>,
        need: Int = ShakeLogic.SHAKE_COUNT
    ): Pair<Int, ShakeLogic.State> {
        var st = ShakeLogic.State()
        var trig = 0
        for ((now, x, z) in samples) {
            val r = ShakeLogic.step(st, now, x, 0f, z)
            st = r.state
            if (r.triggered) trig++
        }
        return trig to st
    }

    /**
     * 真摇：三轴之和快速来回变化。
     * 每 20ms 一个采样（50Hz），交替 ±amp，Δ 约为 2×amp。
     */
    private fun shake(startAt: Long, cycles: Int = 6, amp: Float = 12f, step: Long = 20):
        List<Triple<Long, Float, Float>> {
        val out = mutableListOf<Triple<Long, Float, Float>>()
        var t = startAt
        // 首采样只建基准
        out.add(Triple(t, 0f, G)); t += step
        repeat(cycles) {
            out.add(Triple(t, amp, G)); t += step
            out.add(Triple(t, -amp, G)); t += step
        }
        return out
    }

    /**
     * 拿起手机：平滑单向加速，Δ 很小。
     */
    private fun liftPhone(startAt: Long, points: Int = 20):
        List<Triple<Long, Float, Float>> {
        val out = mutableListOf<Triple<Long, Float, Float>>()
        var t = startAt
        out.add(Triple(t, 0f, G)); t += 20
        // 平滑加速：每 20ms 只变 0.05，speed = 0.05/20*10000 = 25 << 350
        for (i in 0 until points) {
            out.add(Triple(t, i * 0.05f, G))
            t += 20
        }
        return out
    }

    /**
     * 放到桌上：一次大冲击（Δ=30），然后静止。
     * speed 很高，但只有 1 次，不够 SHAKE_COUNT=3。
     */
    private fun putDownPhone(startAt: Long, impact: Float = 30f):
        List<Triple<Long, Float, Float>> {
        val out = mutableListOf<Triple<Long, Float, Float>>()
        var t = startAt
        out.add(Triple(t, 0f, G)); t += 20
        out.add(Triple(t, impact, G)); t += 20    // 唯一一次冲击
        repeat(10) { out.add(Triple(t, impact, G)); t += 20 }  // 之后保持不变 → speed=0
        return out
    }

    // ══════════ 核心：必须能触发 ══════════

    @Test
    fun realShake_triggers() {
        val (n, _) = run(shake(1000))
        assertTrue("反复甩动必须触发", n >= 1)
    }

    @Test
    fun gentleShake_alsoTriggers() {
        // 轻摇：幅度小但仍有足够的变化率
        val (n, _) = run(shake(1000, amp = 4f))
        assertTrue("轻摇也应能触发", n >= 1)
    }

    @Test
    fun oneShake_triggersOnlyOnce() {
        // 一次连续甩动不应触发多次（SHAKE_DURATION 防抖）
        val (n, _) = run(shake(1000, cycles = 12))
        assertEquals("连续甩动只应触发一次", 1, n)
    }

    // ══════════ 误判抑制 ══════════

    @Test
    fun liftPhone_doesNotTrigger() {
        val (n, _) = run(liftPhone(1000))
        assertEquals("拿起手机不能触发", 0, n)
    }

    @Test
    fun putDownPhone_doesNotTrigger() {
        // 单次冲击：speed 超阈值但只 1 次 < 3
        val (n, _) = run(putDownPhone(1000))
        assertEquals("放下手机不能触发", 0, n)
    }

    @Test
    fun highImpactPutDown_doesNotTrigger() {
        // 撞击力再大也只有 1 次超阈值
        val (n, _) = run(putDownPhone(1000, impact = 80f))
        assertEquals("强冲击的放下动作不能触发", 0, n)
    }

    @Test
    fun liftThenPutDown_doesNotTrigger() {
        val (n, _) = run(liftPhone(1000) + putDownPhone(2500))
        assertEquals("拿起+放下不能凑成摇动", 0, n)
    }

    @Test
    fun repeatedPutDown_neverTriggers() {
        // 反复放桌上：每次都是孤立冲击，中间有 SHAKE_TIMEOUT 清零
        val samples = mutableListOf<Triple<Long, Float, Float>>()
        var t = 1000L
        repeat(5) {
            samples.addAll(putDownPhone(t))
            t += 3000
        }
        val (n, _) = run(samples)
        assertEquals("反复放下不应触发", 0, n)
    }

    @Test
    fun twoImpactsWithinTimeout_notEnough() {
        // 两次冲击间隔 300ms（< SHAKE_TIMEOUT）：count 累到 2，但仍不够 3
        val out = mutableListOf<Triple<Long, Float, Float>>()
        var t = 1000L
        repeat(2) { out.add(Triple(t, 0f, G)); t += 100 }
        out.add(Triple(t, 30f, G)); t += 300
        out.add(Triple(t, 60f, G)); t += 100
        repeat(2) { out.add(Triple(t, 60f, G)); t += 100 }
        val (n, _) = run(out)
        assertEquals("两次冲击不够三次，不应触发", 0, n)
    }

    @Test
    fun threeImpacts_withinTimeout_triggers() {
        // 三次冲击（模拟连甩）：一次甩动本来就会产生多次大变化率
        val out = mutableListOf<Triple<Long, Float, Float>>()
        var t = 1000L
        repeat(2) { out.add(Triple(t, 0f, G)); t += 100 }
        // 连续三次大变化，每次间隔 150ms（> TIME_THRESHOLD 100ms）
        for (i in 0 until 3) {
            val v = if (i % 2 == 0) 20f else -20f
            out.add(Triple(t, v, G)); t += 150
        }
        repeat(2) { out.add(Triple(t, 0f, G)); t += 100 }
        val (n, _) = run(out)
        assertTrue("连续三次大变化应触发", n >= 1)
    }

    // ══════════ 边界 ══════════

    @Test
    fun timeThreshold_skipsTooFrequentSamples() {
        // 采样间隔 <= TIME_THRESHOLD(10ms) 时跳过，避免 Δt≈0 导致除法爆炸
        var st = ShakeLogic.State()
        st = ShakeLogic.step(st, 1000, 0f, 0f, G).state
        val r = ShakeLogic.step(st, 1008, 20f, 0f, G)   // 8ms < 10ms
        assertEquals(ShakeLogic.Verdict.SKIPPED, r.verdict)
        assertEquals(0f, r.speed, 0.01f)
    }

    @Test
    fun allStill_neverTriggers() {
        val samples = (1..100).map { Triple(it * 100L, 0f, G) }
        val (n, _) = run(samples)
        assertEquals(0, n)
    }

    @Test
    fun firstSample_onlyBaseline() {
        var st = ShakeLogic.State()
        val r = ShakeLogic.step(st, 1000, 5f, 3f, G)
        assertEquals("首次采样只建基准，不判定", null, r.verdict)
        assertFalse(r.triggered)
        assertEquals(0, r.state.shakeCount)
    }

    @Test
    fun timeout_resetsCount() {
        var st = ShakeLogic.State()
        st = ShakeLogic.step(st, 1000, 0f, 0f, G).state
        // 两次超阈值
        st = ShakeLogic.step(st, 1200, 20f, 0f, G).state
        st = ShakeLogic.step(st, 1400, -20f, 0f, G).state
        val before = st.shakeCount
        assertTrue("应有累计", before > 0)
        // 隔 1 秒（> SHAKE_TIMEOUT 500ms）再来
        st = ShakeLogic.step(st, 2400, 0f, 0f, G).state
        assertEquals("超时后计数应清零", 0, st.shakeCount)
    }

    @Test
    fun shakeDuration_blocksRetrigger() {
        // 触发后 1 秒内又凑满 3 次 → 应被判为 COOLDOWN，不重复触发
        var st = ShakeLogic.State()
        var t = 1000L
        st = ShakeLogic.step(st, t, 0f, 0f, G).state
        var triggered = 0
        var cooldown = 0
        // 20ms 采样、交替 ±30：每 3 次超阈值凑满一轮。
        // 60 个采样 = 1200ms，跨过 1000ms 冷却期，所以会触发 2 次（1060 和 2080）——
        // 这里验证的是「冷却期内（前 1000ms）只有 1 次触发」。
        repeat(60) {
            t += 20
            val v = if (it % 2 == 0) 30f else -30f
            val r = ShakeLogic.step(st, t, v, 0f, G)
            st = r.state
            if (r.triggered) triggered++
            if (r.verdict == ShakeLogic.Verdict.COOLDOWN) cooldown++
        }
        assertEquals("1200ms 内应触发 2 次（冷却期 1000ms）", 2, triggered)
        assertTrue("应出现冷却判定（说明防抖生效）", cooldown > 0)
    }

    // ══════════ 常量守护 ══════════

    @Test
    fun constantsMatchModernTuning() {
        // 时间相关常量沿用经典实现
        assertEquals(10L, ShakeLogic.TIME_THRESHOLD)
        assertEquals(500L, ShakeLogic.SHAKE_TIMEOUT)
        assertEquals(1000L, ShakeLogic.SHAKE_DURATION)
        // 但阈值必须高于原版 350：现代设备精度高，350 会让「手机一动就触发」
        assertTrue(
            "默认阈值必须明显高于原版 350，否则过于灵敏",
            ShakeLogic.DEFAULT_FORCE_THRESHOLD > 800f
        )
    }

    @Test
    fun sensitivityMapping_isMonotonic() {
        val t1 = ShakeLogic.forceForSensitivity(1)
        val t5 = ShakeLogic.forceForSensitivity(5)
        val t10 = ShakeLogic.forceForSensitivity(10)
        assertTrue("档位 1 最难触发（阈值最大）", t1 > t5)
        assertTrue("档位 10 最灵敏（阈值最小）", t5 > t10)
        assertTrue("最低档阈值应 >= 2500", t1 >= 2500f)
        assertTrue("最高档阈值应 <= 500", t10 <= 500f)
    }

    @Test
    fun sensitivity_clampsOutOfRange() {
        assertEquals(ShakeLogic.forceForSensitivity(1), ShakeLogic.forceForSensitivity(0), 0.01f)
        assertEquals(ShakeLogic.forceForSensitivity(10), ShakeLogic.forceForSensitivity(99), 0.01f)
    }

    @Test
    fun sensitivity5_isDefault() {
        assertEquals(5, ShakeLogic.DEFAULT_SENSITIVITY)
    }

    @Test
    fun speedFormula_isCorrect() {
        // Δ(x+y+z)=20, Δt=100ms → speed = 20/100*10000 = 2000
        var st = ShakeLogic.State()
        st = ShakeLogic.step(st, 1000, 0f, 0f, 0f).state
        val r = ShakeLogic.step(st, 1100, 20f, 0f, 0f)
        assertEquals(2000f, r.speed, 1f)
    }
}
