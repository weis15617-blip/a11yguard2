package com.merin.a11yguard2

/**
 * 加锁判定（纯逻辑，便于单测）。
 * 加锁 = 硬拒绝关闭：只有在 want=false 且目标组件在加锁名单里时才拒绝；
 * 开启（want=true）永远放行，避免加锁把服务“锁死到关不上也开不了”的反向问题。
 */
object LockLogic {

    const val DENY_MSG = "已加锁：需先在长按菜单解除加锁"

    /** 目标是否在加锁名单里（两边都规范化，兼容 /.Cls 与全限定写法）。 */
    fun isLocked(comp: String, locked: List<String>): Boolean {
        if (comp.isBlank()) return false
        val target = SvcRepo.normalize(comp)
        return locked.any { SvcRepo.normalize(it) == target }
    }

    /** 本次切换是否应被拒绝（仅关闭请求会被拦）。 */
    fun denyClose(comp: String, want: Boolean, locked: List<String>): Boolean =
        !want && isLocked(comp, locked)
}
