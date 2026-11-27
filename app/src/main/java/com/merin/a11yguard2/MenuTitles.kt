package com.merin.a11yguard2

/** 长按菜单文案：必须反映当前状态（纯逻辑，可单测）。 */
object MenuTitles {
    fun lock(locked: Boolean): String =
        if (locked) "解除加锁（当前：已加锁）" else "加锁"

    fun pin(pinned: Boolean): String =
        if (pinned) "取消置顶（当前：已置顶）" else "置顶（当前：未置顶）"
}
