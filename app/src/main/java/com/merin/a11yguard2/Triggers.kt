package com.merin.a11yguard2

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/** 手势 / 按键 的统一执行层：震动反馈 + 目标服务切换 + 锁屏动作解释。 */
object Triggers {

    fun vibrate(ctx: Context, ms: Int) {
        if (ms <= 0) return
        runCatching {
            val v = ctx.getSystemService(Vibrator::class.java) ?: return
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms.toLong())
            }
        }
    }

    /** 按“取反当前状态”的方式切换一个无障碍服务；在线程里走 Router，失败也有日志。 */
    fun toggleComp(ctx: Context, comp: String, source: String) {
        if (comp.isEmpty()) {
            Diag.log(source, "skip: target empty")
            return
        }
        val want = !SvcRepo.isEnabled(ctx, comp)
        Thread {
            val res = ToggleRouter.toggle(ctx, comp, want)
            AppSettings.bumpStat(ctx, res.ok)
            Diag.log(source, "want=$want ${res.logLine()}")
            vibrate(ctx, AppSettings.genVibMs(ctx))
        }.start()
    }

    /**
     * lock_action / unlock_action / volup_target / voldown_target 的统一执行入口。
     *
     * 取值形态（历史遗留，两种必须都认）：
     * - "none"                什么也不做
     * - "master"              切换主开关（GuardA11yService）
     * - "toggle:<component>"  切换指定服务（锁屏/解锁动作走这个）
     * - "<component>"         裸组件名（音量键目标、背面敲击目标走这个）
     *
     * 这个兜底是必须的：音量键和背面敲击的设置页用 pickTarget() 直接写入
     * `it.comp`（见 MainActivity 的 pickTarget），不带 toggle: 前缀；而
     * lock/unlock 用 pickAction() 写入带前缀的值。早期 runAction 只认三种
     * 带语义的值，裸组件名会三条分支全不匹配、静默什么都不做 —— 表现为
     * 「音量键能检测到双击（diag 里有 double DOWN 记录），但目标服务没有任何
     * 反应」。所以这里把裸组件名当成 toggle:<component> 同样处理。
     */
    fun runAction(ctx: Context, action: String, source: String) {
        val a = action.trim()
        when {
            a.isEmpty() || a == AppSettings.ACT_NONE -> Unit
            a == AppSettings.ACT_MASTER -> {
                if (!SvcRepo.isEnabled(ctx, GuardA11yService.COMP)) {
                    toggleComp(ctx, GuardA11yService.COMP, source)
                }
            }

            a.startsWith(AppSettings.ACT_PREFIX) ->
                toggleComp(ctx, a.removePrefix(AppSettings.ACT_PREFIX), source)

            // 裸组件名（形如 pkg/pkg.Service）：按 toggle:<component> 处理
            a.contains('/') -> toggleComp(ctx, a, source)

            else -> Diag.log(source, "unknown action: $a")
        }
    }
}
