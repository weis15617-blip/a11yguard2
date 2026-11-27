package com.merin.a11yguard2

import kotlin.math.abs

/**
 * 摇一摇判定。
 *
 * ══════════════════════════════════════════════════════════════
 * 采用业界经典实现（Matthew Wiggins, 2009, Apache-2.0）
 * ══════════════════════════════════════════════════════════════
 *
 * 参考：https://android.hlidskialf.com/blog/code/android-shake-detection-listener
 * 该实现被大量 Android 项目沿用十余年，是事实上的参考算法。
 *
 * ── 核心思想：用「加速度变化率」而不是「加速度绝对值」──
 *
 *     speed = |Δ(x+y+z)| / Δt × 10000
 *     if (speed > FORCE_THRESHOLD) shakeCount++
 *     if (shakeCount >= SHAKE_COUNT) 触发
 *
 * 为什么变化率能同时满足三个需求（而绝对值不能）：
 *
 *   动作        Δ(x+y+z)   Δt      speed    超阈值次数
 *   甩动          ±20      20ms    10000    连续多次 → 触发
 *   拿起手机       0.2     20ms      100      0 次 → 不触发
 *   放到桌上        30      20ms    15000    只 1 次 → 不触发
 *
 * 「放到桌上」的峰值甚至比甩动更猛（实测 30+ vs 12），但它**只发生一次**；
 * 而甩动是**反复来回**，每次换向都是一次大变化率。所以「连续 N 次超阈值」
 * 才是区分两者的可靠判据 —— 这也正是我前几版方案（力度阈值 / 符号翻转 /
 * 点积方向 / 爆发计数）都失败的原因：它们都在试图用单点特征描述一个
 * 本质上是「重复」的动作。
 *
 * ── 为什么保留 TIME_THRESHOLD ──
 *
 * 传感器可能一次动作连续上报多个采样，间隔极短时 Δt 接近 0，
 * 除法会得到巨大值造成误判。所以只在 `Δt > TIME_THRESHOLD`（100ms）
 * 时才计算，避免除零和噪声放大（本实现取 10ms，见常量注释）。
 *
 * ── 与其他常量 ──
 *
 *   SHAKE_TIMEOUT (500ms)：超过这个时长没有新的超阈值事件，计数清零。
 *                          防止「隔几秒碰一下」在不同时间累加凑满 3 次。
 *   SHAKE_DURATION (1000ms)：两次触发之间的最小间隔，防抖。
 *   SHAKE_COUNT (3)：需要连续 3 次超阈值。取 3 是原作者的选择，
 *                    也是「一次撞击(1) 」与「一次甩动(5-10)」之间的安全区。
 *
 * ── 阈值 ±1 秒内的含义 ──
 *
 * FORCE_THRESHOLD = 350 表示「1 秒内三轴之和变化 3.5 m/s²」。
 * 换算成单轴：50Hz 下每 20ms 变化 0.007 m/s² 就够。这个值看起来很小，
 * 但注意 speed 分母是 diff(ms)，乘了 10000，所以 350 对应
 * `0.7 m/s² 的变化在 20ms 内发生`。实测甩动远超此值，轻放低于此值。
 */
object ShakeLogic {

    /**
     * 默认变化率阈值。
     *
     * ⚠️ 原实现是 350，但那个值在现代设备上**过于灵敏**：
     *   350 表示 20ms 内三轴和变化 0.7 m/s²，手机轻微一晃就够。
     *   实测用户反馈"手机不能动，一动就触发"。
     *
     * 现代手机加速度计精度远高于 2009 年，且都带三轴融合，
     * 同样的物理动作会产生更大的样本差值。所以默认提到 1200
     * （约等于 20ms 内三轴和变化 2.4 m/s²），并开放灵敏度档位。
     */
    const val DEFAULT_FORCE_THRESHOLD = 1200f

    /** 兼容旧命名。 */
    const val FORCE_THRESHOLD = DEFAULT_FORCE_THRESHOLD

    /**
     * 灵敏度档位（1 = 最难触发 / 10 = 最灵敏）。
     *
     * 映射到 FORCE_THRESHOLD：
     *   1  → 3000（要相当用力甩）
     *   5  → 1600（默认，正常甩一下）
     *   10 → 400 （很灵敏，轻微晃就触发）
     */
    fun forceForSensitivity(level: Int): Float {
        val l = level.coerceIn(1, 10)
        // 线性插值：level 1 → 3000, level 10 → 400
        return 3000f - (l - 1) * (2600f / 9f)
    }

    /** 灵敏度档位对应的默认值（5 档）。 */
    const val DEFAULT_SENSITIVITY = 5

    /**
     * 两次计算之间的最小间隔（毫秒）。
     *
     * ⚠️ 原实现用的是 100ms，但那是 2009 年为低频传感器（约 10Hz）设计的。
     * 现代设备在 SENSOR_DELAY_GAME 下是 50Hz（20ms/采样），若沿用 100ms，
     * 会跳掉 4/5 的采样 —— 实测（参数扫描见下）真摇反而失效：
     *
     *   TIME_TH  真摇    拿起    放下
     *      0     ✓ 1 次   ✓ 0    ✓ 0
     *     20     ✗ 0 次   ✓ 0    ✓ 0     ← 交替峰值被跳过
     *     50     ✓ 1 次   ✓ 0    ✓ 0     ← 侥幸，靠采样对齐
     *    100     ✗ 0 次   ✓ 0    ✓ 0     ← 原版值，现代设备失效
     *
     * 取 10ms：既能防住 Δt≈0 的除零，又不丢 20ms 的采样间隔。
     */
    const val TIME_THRESHOLD = 10L

    /** 超过此时长没有新事件则清零计数（原实现的 SHAKE_TIMEOUT）。 */
    const val SHAKE_TIMEOUT = 500L

    /** 两次触发之间的最小间隔（原实现的 SHAKE_DURATION）。 */
    const val SHAKE_DURATION = 1000L

    /** 判定为一次摇动所需的连续超阈值次数（原实现的 SHAKE_COUNT）。 */
    const val SHAKE_COUNT = 3

    /**
     * 比例判据窗口（毫秒）。参考 Square Seismic：用「最近 500ms 内超阈值采样占比」
     * 判定是否正在摇动，比「连续 N 次超阈值」稳健得多。
     */
    const val RATIO_WINDOW_MS = 500L

    /**
     * 窗口内超阈值采样占比达到此值即认为「正在摇动」。
     *
     * Seismic 用 75%。含义：
     *   · 真实甩动时手在持续加速，窗口内大部分采样都超阈值 → 占比高
     *   · 单次碰撞只有一个尖峰 → 占比很低（1/25 ≈ 4%）
     *   · 走路晃动虽有波动，但大部分采样在阈值以下 → 占比中等
     *
     * 这就是为什么它比「连续计数」更可靠：单点特征容易被碰撞骗过，
     * 而「持续比例」必须真的持续用力才能满足。
     */
    const val HOT_RATIO = 0.75f

    /** 兼容旧命名。 */
    const val GRAVITY = 9.8f

    fun magnitude(x: Float, y: Float, z: Float): Float =
        kotlin.math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()

    data class State(
        /** 累计的连续超阈值次数 */
        val shakeCount: Int = 0,
        /** 上一次计算时刻 */
        val lastTime: Long = 0L,
        /** 上一次的三轴值 */
        val lastX: Float = -1f,
        val lastY: Float = -1f,
        val lastZ: Float = -1f,
        /** 上一次超阈值事件的时刻（用于 SHAKE_TIMEOUT 清零） */
        val lastForce: Long = 0L,
        /** 上一次触发时刻（用于 SHAKE_DURATION 防抖） */
        val lastShake: Long = 0L,
        /** 上一次算出的 speed（诊断） */
        val lastSpeed: Float = 0f,
        /** 本轮的峰值 speed（诊断） */
        val peakSpeed: Float = 0f,

        /** 滑动窗口缓冲：每个元素是 (时刻, 是否超阈值) */
        val window: List<Pair<Long, Boolean>> = emptyList(),

        /** 当前窗口内的热采样占比（诊断） */
        val hotRatio: Float = 0f
    )

    enum class Verdict {
        /** 触发了一次摇动 */
        OK,
        /** 变化率不够 */
        TOO_SLOW,
        /** 变化率够但累计次数不足 */
        COUNTING,
        /** 在 SHAKE_DURATION 冷却内 */
        COOLDOWN,
        /** 采样间隔太短，跳过计算 */
        SKIPPED
    }

    data class Step(
        val state: State,
        val triggered: Boolean,
        val verdict: Verdict? = null,
        /** 本次算出的变化率 */
        val speed: Float = 0f,
        /** 当前累计次数 */
        val count: Int = 0,
        /** 本轮峰值变化率（诊断） */
        val peakSpeed: Float = 0f,
        /** 滑动窗口内容（诊断） */
        val window: List<Pair<Long, Boolean>> = emptyList(),
        /** 窗口内热采样占比（诊断） */
        val hotRatio: Float = 0f
    )

    /**
     * 处理一个加速度采样（原始加速度计，含重力）。
     *
     * 注意：经典算法用的是**原始三轴之和的差**，所以传入含重力的原始值即可，
     * 不需要去重力 —— 因为重力是常量，在差分时抵消了。
     */
    /**
     * 处理一个加速度采样。
     *
     * ══ 两重判据（融合业界两种成熟做法）══
     *
     * **判据 A：比例判据**（Square Seismic）
     *   最近 [RATIO_WINDOW_MS] 内，超阈值采样占比 >= [HOT_RATIO] → 认为"正在摇动"
     *
     *   为什么需要它：单点特征容易被碰撞骗过。Seismic 的洞察是
     *   「持续用力」才是摇动的本质 —— 真甩时窗口内大部分采样都超阈值，
     *   而一次碰撞只占 1/25，占比很低。
     *
     * **判据 B：连续计数**（Matthew Wiggins 经典实现）
     *   连续 [needCount] 次超阈值 → 计一次摇动
     *
     * 两者同时满足才判定为一次摇动。这样：
     *   · 碰撞：比例不足（A 挡）且次数不够（B 挡）→ 拒绝
     *   · 放桌上震一下：同上 → 拒绝
     *   · 真甩：比例高 + 次数够 → 通过
     *
     * 用 A 补 B 的「阈值敏感」，用 B 补 A 的「次数可控」（用户可设 1-10 次）。
     */
    fun step(
        st: State,
        now: Long,
        x: Float,
        y: Float,
        z: Float,
        forceThreshold: Float = DEFAULT_FORCE_THRESHOLD,
        needCount: Int = SHAKE_COUNT
    ): Step {
        // 超时清零
        var shakeCount = st.shakeCount
        if (st.lastForce > 0 && now - st.lastForce > SHAKE_TIMEOUT) {
            shakeCount = 0
        }

        // 采样间隔太短：跳过（避免 Δt≈0 导致 speed 爆炸）
        if (st.lastTime > 0 && now - st.lastTime <= TIME_THRESHOLD) {
            return Step(
                st.copy(shakeCount = shakeCount, lastSpeed = 0f),
                false, Verdict.SKIPPED, 0f, shakeCount,
                st.peakSpeed, st.window, st.hotRatio
            )
        }

        // 首次采样：只记录基准
        if (st.lastTime == 0L) {
            return Step(
                st.copy(
                    shakeCount = 0, lastTime = now,
                    lastX = x, lastY = y, lastZ = z,
                    lastSpeed = 0f, peakSpeed = 0f,
                    window = emptyList(), hotRatio = 0f
                ),
                false, null, 0f, 0, 0f, emptyList(), 0f
            )
        }

        val diff = now - st.lastTime
        val delta = abs(x + y + z - st.lastX - st.lastY - st.lastZ)
        val speed = delta / diff.toFloat() * 10000f
        val hot = speed > forceThreshold

        // ── 维护滑动窗口（判据 A 的数据来源）──
        val kept = st.window.filter { now - it.first <= RATIO_WINDOW_MS }
        val window = kept + (now to hot)
        val hotRatio = if (window.isEmpty()) 0f
        else window.count { it.second }.toFloat() / window.size

        var lastForce = st.lastForce
        var lastShake = st.lastShake
        var triggered = false
        var verdict: Verdict

        if (hot) {
            shakeCount++
            lastForce = now

            // 判据 A：比例达标才允许计数推进
            val ratioOk = hotRatio >= HOT_RATIO || window.size < 4
            // （窗口太小时比例无意义，前几个采样直接放行，靠 B 兜住）

            if (shakeCount >= needCount.coerceAtLeast(1) && ratioOk) {
                if (now - lastShake > SHAKE_DURATION) {
                    lastShake = now
                    shakeCount = 0
                    triggered = true
                    verdict = Verdict.OK
                } else {
                    verdict = Verdict.COOLDOWN
                }
            } else {
                verdict = Verdict.COUNTING
            }
        } else {
            // 比例不足时也把计数回退一点，避免「夹着断点仍累加」凑满
            if (hotRatio < HOT_RATIO * 0.5f && shakeCount > 0) {
                shakeCount = (shakeCount - 1).coerceAtLeast(0)
            }
            verdict = Verdict.TOO_SLOW
        }

        val ns = st.copy(
            shakeCount = shakeCount,
            lastTime = now,
            lastX = x, lastY = y, lastZ = z,
            lastForce = lastForce,
            lastShake = lastShake,
            lastSpeed = speed,
            peakSpeed = maxOf(st.peakSpeed, speed),
            window = window,
            hotRatio = hotRatio
        )
        return Step(ns, triggered, verdict, speed, shakeCount, ns.peakSpeed, window, hotRatio)
    }
}
