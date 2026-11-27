package com.merin.a11yguard2

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/** 手势 / 按键 的统一执行层：震动反馈 + 目标服务切换 + 锁屏动作解释。 */
object Triggers {

    /**
     * 触觉反馈（默认按「音量键/通用」类别判断开关）。
     *
     * 三个提前返回：
     *   1. ms <= 0：调用方主动要求不震
     *   2. 全局总开关关闭
     *   3. 该功能的独立开关关闭
     */
    fun vibrate(ctx: Context, ms: Int, kind: String = AppSettings.VIB_VOL) {
        if (ms <= 0) return
        if (!AppSettings.vibOn(ctx, kind)) return
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

    /** 屏幕是否处于锁屏状态（含熄屏）。 */
    private fun isScreenLocked(ctx: Context): Boolean = runCatching {
        ctx.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
    }.getOrDefault(false)

    /**
     * 把 source 映射到「锁屏开关」的类别，并返回是否允许锁屏执行。
     *
     * 映射规则：
     *   volup / voldown        → 音量键开关
     *   shake                  → 摇一摇开关
     *   backtap                → 背面敲击开关
     *   其它（lock/unlock 等） → 不拦截
     *
     * lock/unlock 动作必须放行：它们本身就是"锁屏时要做的事"，
     * 若被锁屏拦截就永远不生效了。
     */
    private fun allowWhenLocked(ctx: Context, source: String): Boolean {
        val kind = when {
            source.startsWith("vol") -> AppSettings.LOCK_VOL
            source == "shake" -> AppSettings.LOCK_SHAKE
            source == "backtap" -> AppSettings.LOCK_BACKTAP
            else -> return true
        }
        return AppSettings.allowWhenLocked(ctx, kind)
    }

    /**
     * **确保某个无障碍服务处于开启状态**：已开则立即返回，未开才去打开。
     *
     * 与 [toggleComp] 的区别：toggle 会"取反"，ensure 只会"补上"。
     * 锁屏/解锁这类周期性事件必须用 ensure —— 否则服务正常运行时
     * 每次亮屏熄屏都会把它关掉又打开，读屏反复中断。
     */
    fun ensureComp(ctx: Context, comp: String, source: String) {
        val target = SvcRepo.normalize(comp)
        if (target.isEmpty()) {
            Diag.log(source, "ensure skip: empty target")
            return
        }
        if (SvcRepo.isEnabled(ctx, target)) {
            // 已经在运行 —— 什么都不做（这是关键：不能关它）
            Diag.log(source, "ensure ok（已在运行，不做变更）: $target")
            return
        }
        Diag.log(source, "ensure 需要拉起: $target")
        Thread {
            val res = ToggleRouter.toggle(ctx, target, true)
            AppSettings.bumpStat(ctx, res.ok)
            Diag.log(source, "ensure ${res.logLine()}")
            if (res.ok) vibrate(ctx, AppSettings.genVibMs(ctx))
        }.start()
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

        // ── 锁屏拦截 ──
        // source 是各手势传入的来源标识（volup/voldown/shake/backtap/...），
        // 据此判断该手势是否允许在锁屏时执行。
        if (isScreenLocked(ctx) && !allowWhenLocked(ctx, source)) {
            Diag.log(source, "锁屏中且该手势未允许锁屏执行 → 跳过")
            return
        }

        when {
            a.isEmpty() || a == AppSettings.ACT_NONE -> Unit
            a == AppSettings.ACT_MASTER -> {
                if (!SvcRepo.isEnabled(ctx, GuardA11yService.COMP)) {
                    toggleComp(ctx, GuardA11yService.COMP, source)
                }
            }

            // 「确保开启」：已开则不动，未开才拉起来。绝不关闭。
            a == AppSettings.ACT_ENSURE_MASTER ->
                ensureComp(ctx, GuardA11yService.COMP, source)

            a.startsWith(AppSettings.ACT_ENSURE_PREFIX) -> {
                val target = a.removePrefix(AppSettings.ACT_ENSURE_PREFIX)
                if (target == "master") {
                    ensureComp(ctx, GuardA11yService.COMP, source)
                } else if (target.contains('/')) {
                    ensureComp(ctx, target, source)
                } else {
                    Diag.log(source, "unknown ensure target: $target")
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
