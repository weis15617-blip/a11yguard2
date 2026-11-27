package com.merin.a11yguard2

/**
 * 音量键双击判定（纯逻辑，可单测）。
 *
 * ── 为什么需要「确认期」 ──
 *
 * 最简单的双击判定是「第二下按下就立即触发」。但它有一个真实且高频的误判：
 *
 *   **用户调音量时会连续按 3–5 下**，第 2 下就触发了手势 —— 而用户此刻
 *   完全不是在用手势，只是在把音量从 20 调到 80。
 *
 * 解决思路是把「触发」延后一小段，用第三下来消歧：
 *
 *   第 1 下  → 计数 1
 *   第 2 下  → 不立即触发，进入 CONFIRM_MS 确认期（等待「这是不是连按」）
 *   确认期内有第 3 下 → 判定为「调音量」，取消本次手势
 *   确认期结束无第 3 下 → 真正触发
 *
 * 代价是触发延迟一个确认期（默认 220ms）。这个延迟对「切换无障碍服务」
 * 这类动作完全可接受，但换来的是「调音量绝不误触发」。
 *
 * ── 窗口语义 ──
 *
 * 窗口是半开区间 [上次按下, 上次按下 + windowMs)：
 * - 间隔 <  windowMs：算窗口内第二次 → 进入确认期；
 * - 间隔 >= windowMs：视为超时，计数清零并从 1 重新计
 *   （不会出现「按一次-等很久-再按=触发」）。
 */
object KeyPressLogic {

    /** 第二下之后等待多久再真正触发（用于排除「连按调音量」）。 */
    const val CONFIRM_MS = 220L

    /** 简单计数结果（保留兼容旧测试与旧调用点）。 */
    data class Step(val count: Int, val lastTs: Long, val triggered: Boolean)

    /**
     * 完整状态机的输出。
     *
     * @param count        当前已按次数（0 = 无进行中序列）
     * @param lastTs       上次按下时刻
     * @param pendingFire  true = 已达到双击条件，正在确认期中，等待 [CONFIRM_MS] 后触发
     * @param cancelled    true = 本次序列被判定为「调音量」而取消
     */
    data class Result(
        val count: Int,
        val lastTs: Long,
        val pendingFire: Boolean,
        val cancelled: Boolean
    )

    /**
     * 处理一次按下。
     *
     * @param maxCombo 允许的最大连按数：达到这个数即判定为「调音量」并取消。
     *                 默认 3，即按到第 3 下就取消。
     */
    fun press(
        count: Int,
        lastTs: Long,
        now: Long,
        windowMs: Int,
        maxCombo: Int = 3
    ): Result {
        val within = count > 0 && lastTs > 0 && now - lastTs < windowMs.toLong()
        val next = if (within) count + 1 else 1

        // 连按超限：判定为调音量，整个序列作废
        if (next >= maxCombo) {
            return Result(0, now, pendingFire = false, cancelled = true)
        }

        // 刚达到双击条件：进入确认期，不立即触发
        if (next == 2) {
            return Result(next, now, pendingFire = true, cancelled = false)
        }

        // 第一下
        return Result(next, now, pendingFire = false, cancelled = false)
    }

    /**
     * 确认期到期时调用：若仍处于 pendingFire，则真正触发。
     *
     * @return true = 应当执行手势
     */
    fun confirm(state: Result): Boolean = state.pendingFire && state.count == 2

    /**
     * 兼容旧调用点：只做「两下即触发」的简单判定，不带确认期。
     *
     * 新代码请用 [press] + [confirm]。
     */
    @Deprecated("Use press() + confirm() instead (确认期用于排除连按调音量)")
    fun step(count: Int, lastTs: Long, now: Long, windowMs: Int): Step {
        val within = count > 0 && lastTs > 0 && now - lastTs < windowMs.toLong()
        val next = if (within) count + 1 else 1
        val fire = next >= 2
        return Step(if (fire) 0 else next, now, fire)
    }
}
