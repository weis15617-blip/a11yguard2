package com.merin.a11yguard2

/**
 * 音量键「双击 / 三击」判定（纯逻辑，可单测）。
 *
 * ══════════════════════════════════════════════════════════
 * 一、窗口语义：整串总时长
 * ══════════════════════════════════════════════════════════
 *
 * **窗口 = 从第一下按下到最后一下按下的总时间上限。**
 *
 *   ❌ 相邻间隔：设 200ms 时三下可能跨 400ms（与用户预期不符）
 *   ✅ 总时长：设 200ms 就要求三下在 200ms 内按完
 *
 * ══════════════════════════════════════════════════════════
 * 二、两个独立的"窗口"，不能混用一个
 * ══════════════════════════════════════════════════════════
 *
 * 这里有个容易踩的坑：**「手势窗口」和「调音量判定窗口」不能是同一个**。
 *
 * 手势窗口很短（用户设 200ms），而调音量是快速连按 4 下 ——
 * 4 下通常要 300–600ms。如果用 200ms 的手势窗口判「4 下 = 调音量」，
 * 第 4 下早就超窗、被当成新序列的第 1 下，于是永远数不到 4，
 * 结果**调音量时每 2 下就触发一次双击**，非常糟糕。
 *
 * 所以分两个：
 *   · 手势窗口 = 用户设定值（100–600ms）→ 判定双击/三击
 *   · 调音量窗口 = 固定 900ms → 判定"按了 4 下以上"
 *
 * ══════════════════════════════════════════════════════════
 * 三、判定流程
 * ══════════════════════════════════════════════════════════
 *
 *   每次按下：
 *     1. 先累计"快速连按次数"（900ms 内）
 *        达到 4 次 → 判定调音量，整个序列作废
 *     2. 再按手势窗口累计"手势次数"
 *        超出手势窗口 → 重开序列（从 1 计）
 *     3. 手势次数 ≥ 2 → 进入等待期，等窗口结束再定双击还是三击
 *
 * ══════════════════════════════════════════════════════════
 * 四、为什么第 2 下不立即执行
 * ══════════════════════════════════════════════════════════
 *
 * 双击与三击要绑不同服务，第 2 下按下时无法知道用户还要不要按第 3 下。
 * 所以必须等到窗口结束：
 *   窗口内来了第 3 下 → 三击
 *   窗口结束仍只有 2 下 → 双击
 *
 * 等待时间 = 窗口剩余量，保证**总耗时不超过用户设定值**。
 */
object KeyPressLogic {

    /** 调音量判定：900ms 内按到这么多次即视为调音量。 */
    const val VOLUME_WINDOW_MS = 900L

    /** 调音量判定：达到这个次数即作废。 */
    const val VOLUME_COMBO = 4

    /** 默认手势窗口。 */
    const val DEFAULT_WINDOW_MS = 300

    /** 最小手势窗口。 */
    const val MIN_WINDOW_MS = 100

    /** 兼容旧命名。 */
    const val MAX_COMBO = VOLUME_COMBO

    /** 简单计数结果（保留兼容旧测试）。 */
    data class Step(val count: Int, val lastTs: Long, val triggered: Boolean)

    /**
     * 状态机输出。
     *
     * @param count       手势已按次数（0 = 无进行中序列）
     * @param firstTs     手势序列第一下时刻（窗口起点）
     * @param lastTs      上次按下时刻
     * @param pendingFire true = 正在等窗口结束以判定双击/三击
     * @param cancelled   true = 判定为调音量，本次序列作废
     * @param waitMs      还需等多少毫秒到窗口结束
     * @param rapidCount  快速连按累计次数（用于调音量判定）
     * @param rapidFirst  快速连按序列起点
     */
    data class Result(
        val count: Int,
        val firstTs: Long,
        val lastTs: Long,
        val pendingFire: Boolean,
        val cancelled: Boolean,
        val waitMs: Long,
        val rapidCount: Int = 0,
        val rapidFirst: Long = 0L
    )

    /**
     * 处理一次按下。
     *
     * @param count     当前手势次数
     * @param firstTs   手势序列第一下时刻（count==0 时无意义）
     * @param lastTs    上次按下时刻
     * @param now       本次按下时刻
     * @param windowMs  手势窗口（会被钳到 >= MIN_WINDOW_MS）
     * @param rapidCount 已累计的快速连按次数
     * @param rapidFirst 快速连按序列起点
     */
    fun press(
        count: Int,
        firstTs: Long,
        lastTs: Long,
        now: Long,
        windowMs: Int,
        rapidCount: Int = 0,
        rapidFirst: Long = 0L
    ): Result {
        val win = windowMs.coerceAtLeast(MIN_WINDOW_MS).toLong()

        // ── 第 1 步：调音量判定（独立窗口，固定 900ms）──
        // 注意只凭 count > 0 判断，不用 firstTs > 0 —— 时刻 0 也是合法值，
        // 用 0 当哨兵会让"第一下恰好在 t=0"的场景失效（踩过）。
        val rapidWithin = rapidCount > 0 && now - rapidFirst < VOLUME_WINDOW_MS
        val nextRapid = if (rapidWithin) rapidCount + 1 else 1
        val newRapidFirst = if (rapidWithin) rapidFirst else now

        if (nextRapid >= VOLUME_COMBO) {
            return Result(0, 0L, now, pendingFire = false, cancelled = true,
                waitMs = 0, rapidCount = 0, rapidFirst = 0L)
        }

        // ── 第 2 步：手势窗口累计 ──
        val within = count > 0 && now - firstTs < win
        val next = if (within) count + 1 else 1
        val newFirst = if (within) firstTs else now
        val remain = (win - (now - newFirst)).coerceAtLeast(0L)

        // ── 第 3 步：判定 ──
        if (next >= 2) {
            return Result(next, newFirst, now, pendingFire = true, cancelled = false,
                waitMs = remain, rapidCount = nextRapid, rapidFirst = newRapidFirst)
        }
        return Result(next, newFirst, now, pendingFire = false, cancelled = false,
            waitMs = 0, rapidCount = nextRapid, rapidFirst = newRapidFirst)
    }

    /**
     * 窗口到期时判定：2 = 双击，3 = 三击，0 = 不触发。
     */
    fun resolve(state: Result): Int {
        if (!state.pendingFire) return 0
        return when (state.count) {
            2 -> 2
            3 -> 3
            else -> 0
        }
    }

    /** 兼容旧接口：是否为双击。 */
    fun confirm(state: Result): Boolean = resolve(state) == 2

    /**
     * 兼容旧调用点：两下即触发的简单判定（不带确认期、不做调音量排除）。
     */
    @Deprecated("Use press() + resolve() instead")
    fun step(count: Int, lastTs: Long, now: Long, windowMs: Int): Step {
        val within = count > 0 && now - lastTs < windowMs.toLong()
        val next = if (within) count + 1 else 1
        val fire = next >= 2
        return Step(if (fire) 0 else next, now, fire)
    }
}
