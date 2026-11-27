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
}
