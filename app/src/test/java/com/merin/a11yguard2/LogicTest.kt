package com.merin.a11yguard2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {

    // ---------- 加锁拦截 ----------

    private val guardLocked = listOf("com.merin.a11yguard2/com.merin.a11yguard2.GuardA11yService")

    @Test
    fun lock_deniesCloseWhenLocked() {
        assertTrue(LockLogic.denyClose("com.merin.a11yguard2/.GuardA11yService", false, guardLocked))
    }

    @Test
    fun lock_allowsCloseAfterUnlock() {
        // 解除加锁后（名单里没有它）同一个关闭请求必须放行
        assertTrue(!LockLogic.denyClose("com.merin.a11yguard2/.GuardA11yService", false, emptyList()))
        assertTrue(!LockLogic.denyClose(
            "com.merin.a11yguard2/.GuardA11yService", false,
            listOf("com.merin.a11yguard2/com.merin.a11yguard2.TapAgentService")
        ))
    }

    @Test
    fun lock_neverBlocksEnable() {
        assertTrue(!LockLogic.denyClose("com.merin.a11yguard2/.GuardA11yService", true, guardLocked))
    }

    @Test
    fun lock_doesNotBlockOtherComponents() {
        assertTrue(!LockLogic.denyClose("com.nirenr.talkman/.TalkManAccessibilityService", false, guardLocked))
    }

    @Test
    fun lock_matchesBothNotations() {
        // 名单里存短写法、请求传全限定，或反过来，都要命中
        assertTrue(LockLogic.isLocked(
            "com.merin.a11yguard2/com.merin.a11yguard2.GuardA11yService",
            listOf("com.merin.a11yguard2/.GuardA11yService")
        ))
        assertTrue(LockLogic.denyClose(
            "com.merin.a11yguard2/.GuardA11yService", false,
            listOf("com.merin.a11yguard2/.GuardA11yService")
        ))
    }

    @Test
    fun lock_denyMessageIsExplicit() {
        assertEquals("已加锁：需先在长按菜单解除加锁", LockLogic.DENY_MSG)
    }

    // ---------- 旧数据格式兼容 ----------

    @Test
    fun normalizeComp_dropsResidualEntryWithoutSlash() {
        // 旧版 locked_comps 里就存在这种残缺项：com.merin.a11ytool.KeyService
        assertNull(LegacyCodec.normalizeComp("com.merin.a11ytool.KeyService"))
    }

    @Test
    fun normalizeComp_keepsFullyQualified() {
        assertEquals(
            "com.merin.a11ytool/com.merin.a11ytool.KeyService",
            LegacyCodec.normalizeComp("com.merin.a11ytool/com.merin.a11ytool.KeyService")
        )
    }

    @Test
    fun normalizeComp_expandsLeadingDot() {
        assertEquals(
            "com.nirenr.talkman/.TalkManAccessibilityService".replace("/.", "/com.nirenr.talkman."),
            LegacyCodec.normalizeComp("com.nirenr.talkman/.TalkManAccessibilityService")
        )
    }

    @Test
    fun splitAndJoinRoundTripWithPipe() {
        val raw = "com.a/.A:com.b/.B"
        val locked = "pkg1/Cls1|pkg2/Cls2"
        assertEquals(listOf("pkg1/Cls1", "pkg2/Cls2"), LegacyCodec.split(locked))
        assertEquals("pkg1/Cls1|pkg2/Cls2", LegacyCodec.join(LegacyCodec.split(locked)))
        assertEquals(listOf("com.a/.A:com.b/.B"), LegacyCodec.split(raw))
    }

    @Test
    fun parseXml_roundTrip() {
        val xml = "<map>\n" +
            "    <string name=\"locked_comps\">com.merin.a11ytool/com.merin.a11ytool.KeyService|com.nirenr.talkman/.TalkManAccessibilityService</string>\n" +
            "    <string name=\"pinned_pkgs\">com.nirenr.talkman|roro.stellar.manager</string>\n" +
            "</map>\n"
        val data = LegacyCodec.parseXml(xml)
        assertEquals(2, data.locked.size)
        assertEquals(2, data.pinned.size)
        val rebuilt = LegacyCodec.buildXml(data.locked, data.pinned)
        val again = LegacyCodec.parseXml(rebuilt)
        assertEquals(data.locked, again.locked)
        assertEquals(data.pinned, again.pinned)
    }

    @Test
    fun parseXml_dropsResidualLockedEntry() {
        val xml = "<map><string name=\"locked_comps\">com.merin.a11ytool.KeyService|com.a/com.a.B</string></map>"
        val data = LegacyCodec.parseXml(xml)
        assertEquals(listOf("com.a/com.a.B"), data.locked)
    }

    // ---------- 路由回落 ----------

    @Test
    fun auto_fallsBackToTapWhenShizukuNotRunning() {
        val order = RouteLogic.order(
            RouteLogic.MODE_AUTO, RouteLogic.SHIZUKU_NOT_RUNNING, tapAvailable = true
        )
        assertEquals(listOf(RouteLogic.ROUTE_TAP), order)
        assertTrue(RouteLogic.describe(RouteLogic.MODE_AUTO, RouteLogic.SHIZUKU_NOT_RUNNING, true)
            .contains("点按"))
    }

    @Test
    fun auto_prefersShizukuWhenAvailable() {
        val order = RouteLogic.order(
            RouteLogic.MODE_AUTO, RouteLogic.SHIZUKU_AVAILABLE, tapAvailable = true
        )
        assertEquals(listOf(RouteLogic.ROUTE_SHIZUKU, RouteLogic.ROUTE_TAP), order)
    }

    @Test
    fun auto_prefersSecureSettingsAboveShizukuAndTap() {
        val order = RouteLogic.order(
            RouteLogic.MODE_AUTO,
            RouteLogic.SHIZUKU_AVAILABLE,
            tapAvailable = true,
            secureAvailable = true
        )
        assertEquals(
            listOf(RouteLogic.ROUTE_SECURE, RouteLogic.ROUTE_SHIZUKU, RouteLogic.ROUTE_TAP),
            order
        )
    }

    @Test
    fun auto_withSecureOnlyUsesSecureEvenWithoutTapAgent() {
        val order = RouteLogic.order(
            RouteLogic.MODE_AUTO,
            RouteLogic.SHIZUKU_NOT_RUNNING,
            tapAvailable = false,
            secureAvailable = true
        )
        assertEquals(listOf(RouteLogic.ROUTE_SECURE), order)
    }

    @Test
    fun secureMode_withoutPermissionYieldsNoRoute() {
        assertTrue(
            RouteLogic.order(RouteLogic.MODE_SECURE, RouteLogic.SHIZUKU_AVAILABLE, true, false).isEmpty()
        )
    }

    @Test
    fun describe_reportsSecureGrantStatus() {
        val granted = RouteLogic.describe(RouteLogic.MODE_AUTO, RouteLogic.SHIZUKU_NOT_RUNNING, true, true)
        assertTrue(granted.contains("直写"))
        assertTrue(granted.contains("重启仍有效"))
        val denied = RouteLogic.describe(RouteLogic.MODE_SECURE, RouteLogic.SHIZUKU_NOT_RUNNING, true, false)
        assertTrue(denied.contains("直写未授权"))
    }

    // ---------- enabled_accessibility_services 列表增删 ----------

    @Test
    fun secureList_addsTargetAndKeepsOthers() {
        val raw = "com.a/com.a.A:com.b/com.b.B"
        val out = SecureList.apply(raw, "com.c/.C", true)
        assertEquals("com.a/com.a.A:com.b/com.b.B:com.c/com.c.C", out)
    }

    @Test
    fun secureList_removesTargetOnlyWhenDisabling() {
        val raw = "com.a/com.a.A:com.c/com.c.C:com.b/com.b.B"
        val out = SecureList.apply(raw, "com.c/.C", false)
        assertEquals("com.a/com.a.A:com.b/com.b.B", out)
    }

    @Test
    fun secureList_isIdempotentWhenAlreadyEnabled() {
        val raw = "com.a/com.a.A:com.c/com.c.C"
        assertEquals(raw, SecureList.apply(raw, "com.c/com.c.C", true))
        assertEquals("", SecureList.apply("", "com.c/com.c.C", false))
    }

    @Test
    fun tapMode_withoutPermissionYieldsNoRoute() {
        assertTrue(RouteLogic.order(RouteLogic.MODE_TAP, RouteLogic.SHIZUKU_AVAILABLE, false).isEmpty())
    }

    @Test
    fun shizukuMode_withoutShizukuYieldsNoRoute() {
        assertTrue(RouteLogic.order(RouteLogic.MODE_SHIZUKU, RouteLogic.SHIZUKU_NOT_RUNNING, true).isEmpty())
    }

    @Test
    fun shizukuNotInstalled_isDistinctFromNotRunning() {
        // 管理端没装 与 服务端没启动 是两种状态，文案必须能区分，且都不产生 Shizuku 路由
        assertTrue(RouteLogic.SHIZUKU_NOT_INSTALLED != RouteLogic.SHIZUKU_NOT_RUNNING)
        assertTrue(RouteLogic.order(RouteLogic.MODE_SHIZUKU, RouteLogic.SHIZUKU_NOT_INSTALLED, true).isEmpty())
        val s = RouteLogic.describe(RouteLogic.MODE_AUTO, RouteLogic.SHIZUKU_NOT_INSTALLED, true)
        assertTrue(s.contains("Shizuku 未安装"))
        assertTrue(!s.contains("Shizuku 未运行"))
    }

    @Test
    fun describe_reportsNoPermissionForTap() {
        val s = RouteLogic.describe(RouteLogic.MODE_TAP, RouteLogic.SHIZUKU_NOT_RUNNING, false)
        assertTrue(s.contains("无障碍授权未开"))
    }

    // ---------- 菜单文案 ----------

    @Test
    fun menuTitles_reflectState() {
        assertEquals("加锁", MenuTitles.lock(false))
        assertEquals("解除加锁（当前：已加锁）", MenuTitles.lock(true))
        assertEquals("置顶（当前：未置顶）", MenuTitles.pin(false))
        assertEquals("取消置顶（当前：已置顶）", MenuTitles.pin(true))
    }

    // ---------- 结果模型 ----------

    @Test
    fun toggleResult_okRequiresVerify() {
        val r = ToggleResult("tap", "pkg/Cls", true, true, false, "未变化")
        assertEquals(false, r.ok)
        assertTrue(r.logLine().contains("route=tap"))
        assertTrue(r.logLine().contains("verify=false"))
    }

}
