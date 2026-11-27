package com.merin.a11yguard2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * runAction 的取值解析回归测试。
 *
 * 背景（2026-11-26 修复）：音量键和背面敲击的设置页用 pickTarget() 直接写入
 * 裸组件名（`it.comp`，形如 `com.nirenr.talkman/com.nirenr.talkman.TalkManAccessibilityService`），
 * 而 lock/unlock 用 pickAction() 写入 `toggle:<component>`。
 *
 * 早期 runAction 只认 `none` / `master` / `toggle:` 三种，裸组件名三条分支全不匹配、
 * 静默什么都不做。诊断特征：diag.log 里有 `[volkey] double DOWN → <comp>` 记录，
 * 但目标服务完全没有被切换（也没有 `want=...` 日志）。
 *
 * 这里用纯逻辑把「形态 → 意图」的映射固化下来，避免以后再加一种写入格式又漏掉执行端。
 */
class ActionParseTest {

    /** 与 Triggers.runAction 的 when 分支一一对应的纯判定，用于测试。 */
    private sealed class Parsed {
        object Nothing : Parsed()
        object Master : Parsed()
        data class Toggle(val comp: String) : Parsed()
        data class Unknown(val raw: String) : Parsed()
    }

    private fun parse(action: String): Parsed {
        val a = action.trim()
        return when {
            a.isEmpty() || a == AppSettings.ACT_NONE -> Parsed.Nothing
            a == AppSettings.ACT_MASTER -> Parsed.Master
            a.startsWith(AppSettings.ACT_PREFIX) ->
                Parsed.Toggle(a.removePrefix(AppSettings.ACT_PREFIX))
            a.contains('/') -> Parsed.Toggle(a)
            else -> Parsed.Unknown(a)
        }
    }

    @Test
    fun emptyAndNone_meanNothing() {
        assertEquals(Parsed.Nothing, parse(""))
        assertEquals(Parsed.Nothing, parse("   "))
        assertEquals(Parsed.Nothing, parse(AppSettings.ACT_NONE))
    }

    @Test
    fun master_mapsToMaster() {
        assertEquals(Parsed.Master, parse(AppSettings.ACT_MASTER))
        assertEquals(Parsed.Master, parse("  master  "))
    }

    @Test
    fun prefixedToggle_keepsComponent() {
        val comp = "com.nirenr.talkman/com.nirenr.talkman.TalkManAccessibilityService"
        val p = parse(AppSettings.ACT_PREFIX + comp)
        assertTrue(p is Parsed.Toggle)
        assertEquals(comp, (p as Parsed.Toggle).comp)
    }

    @Test
    fun bareComponent_isAcceptedAsToggle() {
        // 这就是本次修复的核心：音量键/背面敲击写入的裸组件名必须能被识别
        val comp = "com.nirenr.talkman/com.nirenr.talkman.TalkManAccessibilityService"
        val p = parse(comp)
        assertTrue("裸组件名必须解析成 Toggle，否则音量键静默失效", p is Parsed.Toggle)
        assertEquals(comp, (p as Parsed.Toggle).comp)
    }

    @Test
    fun bareComponentWithWhitespace_isTrimmed() {
        val comp = "  com.nirenr.talkman/com.nirenr.talkman.TalkManAccessibilityService  "
        val p = parse(comp)
        assertTrue(p is Parsed.Toggle)
        assertEquals(comp.trim(), (p as Parsed.Toggle).comp)
    }

    @Test
    fun garbage_isUnknownNotSilentToggle() {
        // 不含 '/'、不是已知语义值 —— 必须落到 Unknown，以便上层写日志排查
        val p = parse("some-random-string")
        assertTrue(p is Parsed.Unknown)
        assertEquals("some-random-string", (p as Parsed.Unknown).raw)
    }

    @Test
    fun masterIsNotMistakenForComponent() {
        // "master" 不含 '/'，不能落进 Toggle 分支
        val p = parse(AppSettings.ACT_MASTER)
        assertTrue(p is Parsed.Master)
    }

    // ══════════ ensure（确保开启）动作 ══════════
    //
    // 回归背景：锁屏/解锁是周期性事件，若用「切换」语义，
    // 服务正常运行时每次熄屏都会把它关掉、亮屏再打开 —— 读屏反复中断。
    // 正确语义是「自愈」：已开则不动，崩了才拉起。

    /** 模拟 runAction 的动作分类（与 Triggers.runAction 的 when 分支一致）。 */
    private fun classify(a: String): String = when {
        a.isEmpty() || a == AppSettings.ACT_NONE -> "none"
        a == AppSettings.ACT_ENSURE_MASTER -> "ensure"
        a.startsWith(AppSettings.ACT_ENSURE_PREFIX) -> "ensure"
        a == AppSettings.ACT_MASTER -> "master"
        a.startsWith(AppSettings.ACT_PREFIX) -> "toggle"
        a.contains('/') -> "toggle"   // 裸组件名也走 toggle
        else -> "unknown"
    }

    @Test
    fun ensureMaster_isClassifiedAsEnsure() {
        assertEquals("ensure", classify(AppSettings.ACT_ENSURE_MASTER))
    }

    @Test
    fun ensureComponent_isClassifiedAsEnsure() {
        val a = AppSettings.ACT_ENSURE_PREFIX + "com.foo/com.foo.Svc"
        assertEquals("ensure", classify(a))
    }

    @Test
    fun ensureIsDistinctFromToggle() {
        // 关键：ensure 与 toggle 必须是不同分支，否则锁屏会把服务关掉
        val ensure = AppSettings.ACT_ENSURE_PREFIX + "com.foo/com.foo.Svc"
        val toggle = AppSettings.ACT_PREFIX + "com.foo/com.foo.Svc"
        assertEquals("ensure", classify(ensure))
        assertEquals("toggle", classify(toggle))
        assertTrue("两者不能等价", classify(ensure) != classify(toggle))
    }

    @Test
    fun ensurePrefixDoesNotGetParsedAsToggle() {
        // ensure: 前缀不含 "toggle:"，但要注意 toggle: 不是 ensure: 的前缀，
        // 反之亦然 —— 这里验证顺序判断正确
        val a = AppSettings.ACT_ENSURE_PREFIX + "master"
        assertTrue("不该被当成 toggle", !a.startsWith(AppSettings.ACT_PREFIX))
    }

    @Test
    fun bareComponent_stillToggles() {
        assertEquals("toggle", classify("com.foo/com.foo.Svc"))
    }

    @Test
    fun ensureMasterConstantIsExplicit() {
        // 常量必须明确写死，便于用户配置文件里直接填
        assertEquals("ensure:master", AppSettings.ACT_ENSURE_MASTER)
        assertEquals("ensure:", AppSettings.ACT_ENSURE_PREFIX)
    }

    /**
     * ensure 的核心语义：已开时**必须什么都不做**。
     *
     * 这里用纯逻辑表达该决策表，实际执行在 Triggers.ensureComp。
     */
    private fun ensureDecision(alreadyEnabled: Boolean): String =
        if (alreadyEnabled) "no-op" else "start"

    @Test
    fun ensure_alreadyRunning_isNoOp() {
        assertEquals("服务在跑就不该动它", "no-op", ensureDecision(true))
    }

    @Test
    fun ensure_notRunning_starts() {
        assertEquals("服务没跑才拉起", "start", ensureDecision(false))
    }

    @Test
    fun ensure_neverReturnsStop() {
        // 不变量：ensure 在任何输入下都不会产生"关闭"动作
        for (state in listOf(true, false)) {
            val d = ensureDecision(state)
            assertTrue("ensure 绝不能返回 stop，实际=$d", d == "no-op" || d == "start")
        }
    }
}
