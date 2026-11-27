package com.merin.a11yguard2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置更新的逻辑测试（不联网，只测纯函数）。
 *
 * 关键点：
 * 1. versionCode 与 tag 的映射必须和 build.gradle 的约定一致（1.6.0 → 10600）；
 * 2. versionCode 解析失败必须返回 0（而不是猜一个值），否则会误报「有新版本」；
 * 3. 服务列表白名单校验必须拒绝 shell 元字符（这是防注入的唯一屏障）。
 */
class UpdateLogicTest {

    // ---------- tag ↔ versionCode ----------

    @Test
    fun tagToCode_standard() {
        assertEquals(10600, UpdateChecker.versionCodeFromTag("v1.6.0"))
        assertEquals(10700, UpdateChecker.versionCodeFromTag("v1.7.0"))
        assertEquals(20000, UpdateChecker.versionCodeFromTag("v2.0.0"))
    }

    @Test
    fun tagToCode_withoutV() {
        assertEquals(10601, UpdateChecker.versionCodeFromTag("1.6.1"))
    }

    @Test
    fun tagToCode_twoParts_defaultsPatchZero() {
        assertEquals(10600, UpdateChecker.versionCodeFromTag("v1.6"))
    }

    @Test
    fun tagToCode_stripsPrereleaseSuffix() {
        assertEquals(10600, UpdateChecker.versionCodeFromTag("v1.6.0-beta1"))
    }

    @Test
    fun tagToCode_garbageReturnsZero() {
        // 解析不出来必须返回 0（= 不判定有更新），不能猜
        assertEquals(0, UpdateChecker.versionCodeFromTag(""))
        assertEquals(0, UpdateChecker.versionCodeFromTag("latest"))
        assertEquals(0, UpdateChecker.versionCodeFromTag("v"))
        assertEquals(0, UpdateChecker.versionCodeFromTag("release-2026"))
    }

    @Test
    fun tagToCode_negativeOrOdd() {
        // 负数不被当作有效版本
        assertEquals(0, UpdateChecker.versionCodeFromTag("v-1.6.0"))
    }

    @Test
    fun codeToTag_roundTrip() {
        assertEquals("1.6.0", UpdateChecker.tagFromVersionCode(10600))
        assertEquals("1.7.0", UpdateChecker.tagFromVersionCode(10700))
        assertEquals("2.0.0", UpdateChecker.tagFromVersionCode(20000))
        assertEquals("1.6.1", UpdateChecker.tagFromVersionCode(10601))
    }

    @Test
    fun roundTrip_isConsistent() {
        for (code in listOf(10000, 10100, 10600, 19999, 20000, 12345)) {
            val tag = "v" + UpdateChecker.tagFromVersionCode(code)
            assertEquals("$tag 应还原为 $code", code, UpdateChecker.versionCodeFromTag(tag))
        }
    }

    // ---------- 版本比较语义 ----------

    @Test
    fun newerVersion_detectedAsUpdate() {
        val remote = UpdateChecker.versionCodeFromTag("v1.7.0")
        val local = 10600
        assertTrue("1.7.0 应被判定为比 1.6.0 新", remote > local)
    }

    @Test
    fun sameVersion_notAnUpdate() {
        val remote = UpdateChecker.versionCodeFromTag("v1.6.0")
        val local = 10600
        assertFalse("同版本不算更新", remote > local)
    }

    @Test
    fun olderVersion_notAnUpdate() {
        val remote = UpdateChecker.versionCodeFromTag("v1.5.0")
        val local = 10600
        assertFalse("旧版本不算更新", remote > local)
    }

    @Test
    fun unparsableTag_neverClaimsUpdate() {
        // 这是关键：tag 解析失败时 versionCode=0，绝不能触发更新提示
        val remote = UpdateChecker.versionCodeFromTag("nightly")
        assertFalse("解析失败不能报更新", remote > 0)
    }

    // ---------- root 服务列表白名单（防注入）----------

    @Test
    fun safeList_acceptsNormalComponents() {
        val p = RootProvider()
        assertTrue(p.isSafeServiceList(""))
        assertTrue(p.isSafeServiceList("com.foo/.BarService"))
        assertTrue(p.isSafeServiceList("com.a/com.a.S:com.b/com.b.T"))
        assertTrue(p.isSafeServiceList("com.merin.a11yguard2/com.merin.a11yguard2.GuardA11yService"))
    }

    @Test
    fun safeList_rejectsShellMetacharacters() {
        val p = RootProvider()
        // 这些是 su -c 会解释的字符，必须无条件拒绝
        assertFalse(p.isSafeServiceList("com.foo/.Bar; reboot"))
        assertFalse(p.isSafeServiceList("com.foo/.Bar && rm -rf /"))
        assertFalse(p.isSafeServiceList("com.foo/.Bar | cat /etc/passwd"))
        assertFalse(p.isSafeServiceList("com.foo/.Bar `id`"))
        assertFalse(p.isSafeServiceList("com.foo/.Bar $(id)"))
        assertFalse(p.isSafeServiceList("com.foo/.Bar\"quote"))
        assertFalse(p.isSafeServiceList("com.foo/.Bar'quote"))
        assertFalse(p.isSafeServiceList("com.foo/.Bar\nreboot"))
        assertFalse(p.isSafeServiceList("com.foo/.Bar>out"))
    }

    @Test
    fun safeList_rejectsTooLong() {
        val p = RootProvider()
        val long = "a".repeat(4001)
        assertFalse("超长值应拒绝，防止命令行过长", p.isSafeServiceList(long))
    }

    // ---------- 路由顺序（root 参与规则）----------

    @Test
    fun autoMode_secureFirst_thenRoot() {
        val order = RouteLogic.order(
            RouteLogic.MODE_AUTO,
            RouteLogic.SHIZUKU_NOT_RUNNING,
            tapAvailable = true,
            secureAvailable = true,
            root = RouteLogic.ROOT_AVAILABLE
        )
        assertEquals(RouteLogic.ROUTE_SECURE, order[0])
        assertEquals(RouteLogic.ROUTE_ROOT, order[1])
    }

    @Test
    fun autoMode_rootSkippedWhenNotAvailable() {
        val order = RouteLogic.order(
            RouteLogic.MODE_AUTO,
            RouteLogic.SHIZUKU_NOT_RUNNING,
            tapAvailable = true,
            secureAvailable = false,
            root = RouteLogic.ROOT_NO_SU
        )
        assertFalse("未 root 时不能把 root 排进路由", order.contains(RouteLogic.ROUTE_ROOT))
        assertTrue(order.contains(RouteLogic.ROUTE_TAP))
    }

    @Test
    fun rootMode_onlyRoot() {
        val order = RouteLogic.order(
            RouteLogic.MODE_ROOT,
            RouteLogic.SHIZUKU_AVAILABLE,
            tapAvailable = true,
            secureAvailable = true,
            root = RouteLogic.ROOT_AVAILABLE
        )
        assertEquals(listOf(RouteLogic.ROUTE_ROOT), order)
    }

    @Test
    fun rootMode_emptyWhenDenied() {
        val order = RouteLogic.order(
            RouteLogic.MODE_ROOT,
            RouteLogic.SHIZUKU_AVAILABLE,
            tapAvailable = true,
            secureAvailable = true,
            root = RouteLogic.ROOT_DENIED
        )
        assertTrue("root 被拒绝时不应回落到其它方式（用户明确选了 root）", order.isEmpty())
    }

    @Test
    fun autoMode_neverProbesRootWithoutPermission() {
        // 默认 root 状态是 unknown → 不参与路由，避免未 root 设备每次弹授权窗
        val order = RouteLogic.order(
            RouteLogic.MODE_AUTO,
            RouteLogic.SHIZUKU_NOT_RUNNING,
            tapAvailable = true,
            secureAvailable = false,
            root = RouteLogic.ROOT_UNKNOWN
        )
        assertFalse(order.contains(RouteLogic.ROUTE_ROOT))
    }
}
