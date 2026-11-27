package com.merin.a11yguard2

import android.app.Activity
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import rikka.shizuku.Shizuku
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PAGE_COUNT = 6

        // 颜色全部走 Material 3 主题（res/values/colors.xml），
        // 这里保留旧常量名作为"语义别名"，避免改动上百处调用点。
        // 取值改为解析主题属性，这样系统深色/浅色切换、动态取色都能跟上。
        private const val BG = "#0E1215"
        private const val CARD = "#161C21"
        private const val CARD2 = "#1C2329"
        private const val TEXT = "#E3E8EA"
        private const val SUB = "#9BAAAF"
        private const val GREEN = "#4CAF7D"
        private const val ORANGE = "#E8836B"
    }

    /** 从主题取颜色，避免硬编码（Material 3 组件要求解析后的 ARGB）。 */
    private fun themeColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        return try {
            ta.getColor(0, Color.WHITE)
        } finally {
            ta.recycle()
        }
    }

    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout
    private val tabs = ArrayList<LinearLayout>()
    private val pages = arrayOfNulls<View>(PAGE_COUNT)
    private var current = 0

    private lateinit var big: BigToggleView
    private lateinit var summary: TextView
    private lateinit var routeHome: TextView
    private lateinit var svcList: LinearLayout
    private lateinit var svcCount: TextView
    private lateinit var routeSetting: TextView
    private var secureRow: TextView? = null
    private var shizukuStateRow: TextView? = null
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            Diag.log(
                "shizuku",
                "grant requestCode=" + requestCode + " result=" + grantResult +
                    " status=" + ToggleRouter.shizukuStatus(this)
            )
            runOnUiThread { refreshState() }
        }
    private lateinit var modeButtons: MutableList<TextView>
    private lateinit var diagBody: TextView
    private var backTapTest: TextView? = null
    private var shakeTest: TextView? = null

    /** 反馈 QQ 群号。改这里一处，关于页自动同步。 */
    private val FEEDBACK_QQ_GROUP = "1067815429"

    /**
     * 震动五档：名称 to 时长（毫秒）。
     *
     * 为什么是五档而不是连续可调：
     *   · 用户真正需要的区分度只有“够轻不吵”到“够强能感知”，五档足够
     *   · 连续滑杆在小屏上难操作，微调 5ms 差别根本感觉不出来
     *   · 档位有名字（最弱/中等/最强）比数字直观
     */
    /**
     * 震动档位：名称 / 时长(ms) / 幅度(0-255)。
     *
     * ══ 为什么必须同时给幅度 ══
     *
     * 只用时长做"轻"是做不到的。VibrationEffect.createOneShot(ms, DEFAULT_AMPLITUDE)
     * 里 DEFAULT_AMPLITUDE 表示"用最大能力震 ms 毫秒"——时长砍到 15ms 依然是
     * 一记硬顿击，只是短了点。真正的轻柔必须压幅度。
     *
     * 幅度刻度参考 Android Amplitude Scale：
     *   1-20   羽毛级（贴着皮肤才察觉）
     *   21-60  轻触
     *   61-120 中等
     *   121-180 明显
     *   181-255 强烈
     *
     * 默认用「最弱」= 15ms / 18，接近羽毛感。
     */
    private data class VibTier(val name: String, val ms: Int, val amp: Int)

    private val VIB_TIERS: List<VibTier> = listOf(
        // 幅度刻度按"拿在手里能察觉"为下限来排：
        //   Android 的 amplitude 0-255 里，1-5 多数设备上几乎无感，
        //   6-12 是"握持状态能察觉、放桌上听不到"的区间 —— 这就是我们要的下限。
        VibTier("关闭", 0, 0),
        VibTier("极轻", 10, 6),      // 最低可感知：握着才有感觉
        VibTier("羽毛", 14, 12),     // 默认：清晰可察但不打扰
        VibTier("轻", 25, 30),       // 轻微
        VibTier("中等", 45, 75),     // 清晰
        VibTier("最强", 80, 160)     // 强调（没有放到 255，日常用不到那么猛）
    )

    /** 默认档位索引（最弱）。 */
    /**
     * 默认档位 = 「羽毛」（索引 1）。
     *
     * 用户明确要求："就要调到最低，拿在手里有感觉就行。"
     * 所以默认不用「最弱」，直接用最低那一档。
     */
    private val VIB_DEFAULT_TIER = 1

    /** 当前音量键（通用）震动时长。 */
    private fun genVibMsOf(): Int = AppSettings.genVibMs(this)

    /** 某个手势的震动幅度。 */
    private fun vibAmpOf(kind: String): Int = AppSettings.vibAmp(this, kind)

    /** 某个手势的震动时长。 */
    private fun vibMsOf(kind: String): Int = AppSettings.vibMs(this, kind, 15)

    /**
     * 把时长映射回档位名（取最接近的档）。
     *
     * 老版本存的是任意毫秒数（30/110/160/200/250），
     * 直接按档位索引会显示不出来，所以用最近邻映射。
     */
    private fun vibTierLabel(ms: Int, amp: Int): String {
        if (ms <= 0 || amp <= 0) return "关闭"
        // 按 (时长, 幅度) 的欧氏距离找最近档
        var best = VIB_TIERS[2]
        var bestDiff = Double.MAX_VALUE
        for (tier in VIB_TIERS) {
            if (tier.ms == 0) continue
            val dm = (tier.ms - ms).toDouble() / 120.0
            val da = (tier.amp - amp).toDouble() / 255.0
            val d = dm * dm + da * da
            if (d < bestDiff) { bestDiff = d; best = tier }
        }
        return best.name
    }

    private val titles = arrayOf("概览", "服务", "手势", "设置", "诊断", "关于")
    private val icons = intArrayOf(
        android.R.drawable.ic_menu_compass,
        android.R.drawable.ic_menu_sort_by_size,
        android.R.drawable.ic_menu_mylocation,
        android.R.drawable.ic_menu_manage,
        android.R.drawable.ic_menu_recent_history,
        android.R.drawable.ic_menu_info_details
    )

    private val prefs get() = SvcRepo.prefs(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diag.init(this)
        ToggleRouter.ensureDefaultMode(this)
        // 打开 App 先按用户意图补回（后台线程，避免主线程卡住），补完立刻刷新界面
        Thread {
            // 关闭状态下也要跑一次：清掉可能残留的占位条目
            runCatching { TalkBackPlace.reconcile(this, "app-open") }
            runCatching { SelfHeal.sync(this, "app-open") }
            runOnUiThread { runCatching { refreshState() } }
        }.start()

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor(BG))

        content = FrameLayout(this)
        root.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { bottomMargin = dp(80) }
        )

        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor(CARD))
        }
        root.addView(
            nav,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(80),
                Gravity.BOTTOM
            )
        )

        setContentView(root)
        buildNav()
        show(0)
        runCatching { Shizuku.addRequestPermissionResultListener(shizukuPermissionListener) }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 9)
        }
        if (prefs.getBoolean("desired_on", false)) Keepalive.start(this)
        Keepalive.scheduleCheck(this)

        // 启动时自动检查更新：仅在用户开启且距上次检查超过 12 小时时才联网，
        // 发现新版本才弹窗（静默失败，不打扰）。
        // 延迟 1.5 秒，避免和首屏渲染抢资源、也避免弹窗盖住刚打开的主界面。
        window.decorView.postDelayed({
            runCatching { UpdateUi.checkOnLaunch(this) }
        }, 1500)

        Diag.log("app", "onCreate " + runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrDefault("?"))
    }

    /**
     * 状态刷新的定时器。
     *
     * ── 为什么需要它 ──
     *
     * 用户可能在别的地方改变状态而我们收不到回调：
     *   · 在「系统无障碍设置」里开关了服务，返回应用
     *   · 服务被系统杀掉 / 被保活服务拉回来
     *   · 后台把服务切走了
     *
     * 原来只靠 onResume 刷一次，但页面是缓存复用的，页面上的每个开关与
     * 选择行只在创建时读过一次值，所以看起来"要退出重进才刷新"。
     *
     * 现在：页面可见时每 1.2 秒比对一次关键状态，变了就重建当前页。
     * 只在「有变化」时才重建，避免无谓的 View 重绘与焦点跳动（读屏最怕焦点乱跳）。
     */
    /**
     * 「选择行」的原地刷新注册表：标题 -> (值 TextView, 取值函数)。
     *
     * 为什么需要：selectorRow() 生成 View 时就把 value 写死了。值变了
     * 只能重建那一行。但重建整页会丢滚动位置 —— 用户滑到第 10 项点一下，
     * 界面跳回顶部，得重新滑下来。所以只更新那一行文字。
     */
    private val rowRefreshers = LinkedHashMap<String, () -> Unit>()

    /** 清空注册表（页面重建时调用，避免累积已销毁的 View 引用）。 */
    private fun clearRowRefreshers() = rowRefreshers.clear()

    /** 原地刷新所有选择行（不重建页面、不动滚动位置）。 */
    private fun refreshRowsInPlace() {
        if (rowRefreshers.isEmpty()) return
        rowRefreshers.values.forEach { it() }
    }

    private val stateWatcher = object : Runnable {
        override fun run() {
            if (!isResumed) return
            val sig = stateSignature()
            if (sig != lastStateSignature) {
                lastStateSignature = sig
                invalidateCurrentPage()
            }
            window.decorView.postDelayed(this, 1200)
        }
    }

    /** 当前所有"可能被外部改变"的状态指纹。 */
    private fun stateSignature(): String {
        val enabled = runCatching {
            Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
        }.getOrDefault("")
        val sb = StringBuilder(enabled)
        sb.append('|').append(ToggleRouter.mode(this))
        sb.append('|').append(if (TapAgentService.instance != null) 1 else 0)
        sb.append('|').append(if (BackTapRuntime.running) 1 else 0)
        return sb.toString()
    }

    private var lastStateSignature = ""
    private var isResumed = false

    /**
     * 用户是否正在交互（输入框有焦点 / 有对话框在场）。
     *
     * 重建页面会移除所有 View，若用户正在输入框里打字或在对话框里选择，
     * 重建会把输入内容与焦点一起丢掉。所以这些情况下跳过一次重建，
     * 等用户结束交互后的下一轮再刷。
     */
    private fun isUserInteracting(): Boolean {
        // 有对话框在前台
        if (currentFocus is android.widget.EditText) return true
        val root = window.decorView
        return hasFocusedEditText(root)
    }

    private fun hasFocusedEditText(v: View): Boolean {
        if (v is android.widget.EditText && v.hasFocus()) return true
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                if (hasFocusedEditText(v.getChildAt(i))) return true
            }
        }
        return false
    }

    /**
     * 重建当前页的全部 View。
     *
     * 为什么直接重建而不是逐个刷新控件：设置页有几十个开关与选择行，
     * 逐一更新需要为每个控件维护引用，容易遗漏（之前正是漏了才需要退出重进）。
     * 重建虽然"重"，但只有状态真的变化时才发生，实际开销可以接受，
     * 而且能保证 100% 一致。
     */
    private fun invalidateCurrentPage() {
        // 保护：如果用户正在跟某个输入框/对话框交互，不要重建（会打断输入与读屏焦点）
        if (isUserInteracting()) return

        // ══ 保存滚动位置 ══
        // 重建会生成新的 ScrollView，位置自然是 0 —— 用户滑到第 10 项点一下，
        // 界面直接弹回顶部，得重新滑下来（实测切换十几次就受不了）。
        // 所以重建后必须把位置写回去。
        val savedScroll = currentPageScrollY()

        pages[current] = null
        val page = buildPage(current).also { pages[current] = it }
        content.removeAllViews()
        content.addView(page)
        // 重建后重新挂测试监听（摇一摇/背面敲击的实时区域）
        if (current == 2) {
            bindBackTapTest()
        }
        if (::diagBody.isInitialized) runCatching { diagBody.text = Diag.dump() }
        // 恢复滚动位置：等一帧让布局完成，否则 scrollTo 会被后续 layout 覆盖
        if (savedScroll > 0) {
            val sv = currentScrollView()
            sv?.post { sv.scrollTo(0, savedScroll) }
        }
        Diag.log("ui", "页面 ${current} 重建（滚动恢复至 $savedScroll）")
    }

    /** 取当前页 ScrollView 的滚动位置；没有则 0。 */
    private fun currentPageScrollY(): Int {
        val sv = currentScrollView() ?: return 0
        return sv.scrollY
    }

    /** 找当前页最外层的 ScrollView。 */
    private fun currentScrollView(): ScrollView? {
        for (i in 0 until content.childCount) {
            val v = content.getChildAt(i)
            if (v is ScrollView) return v
            // 有些页面外面包了一层容器
            if (v is android.view.ViewGroup) {
                for (j in 0 until v.childCount) {
                    val c = v.getChildAt(j)
                    if (c is ScrollView) return c
                }
            }
        }
        return null
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        if (::summary.isInitialized) refreshState()
        // 从系统无障碍设置返回时，服务状态很可能已变 —— 立刻重建一次当前页
        lastStateSignature = stateSignature()
        invalidateCurrentPage()
        if (current == 2) {
            BackTapRuntime.acquire(this, BackTapRuntime.OWNER_TEST)
            bindBackTapTest()
        }
        window.decorView.removeCallbacks(stateWatcher)
        window.decorView.postDelayed(stateWatcher, 1200)
    }

    override fun onPause() {
        isResumed = false
        runCatching { window.decorView.removeCallbacks(stateWatcher) }
        BackTapRuntime.testListener = null
        BackTapRuntime.release(this, BackTapRuntime.OWNER_TEST)
        super.onPause()
    }

    // ---------- 导航 ----------

    /**
     * 底部导航：按 Material 3 NavigationBar 规范实现。
     *
     * 规范要点（与原来的土办法对比）：
     *   · 选中项：图标与文字用 primary 色 + 胶囊形高亮背景（32dp 高的 pill）
     *   · 未选中：用 onSurfaceVariant 色，无背景
     *   · 图标 24dp、文字 12sp、间距 4dp
     *   · 整条高 80dp，与内容区之间无阴影（M3 用色块分层而非阴影）
     *   · 触摸目标至少 48dp，读屏焦点明确
     */
    private fun buildNav() {
        // 只移除已脱离视图树的旧标签（正常情况下为空），保留真正在用的。
        // 若直接 clear() 会把正在显示的标签也丢掉，导致高亮失效。
        tabs.removeAll { it.parent == null }
        titles.forEachIndexed { i, title ->
            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = "底部导航-$title"
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { show(i) }
                setOnLongClickListener { show(i); true }
            }
            // 图标外面套一个胶囊容器，选中时上底色（M3 NavigationBar 的做法）
            val pill = FrameLayout(this).apply {
                setPadding(dp(18), dp(2), dp(18), dp(2))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(Color.TRANSPARENT)
                }
            }
            val ic = ImageView(this)
            ic.setImageResource(icons[i])
            ic.setColorFilter(Color.parseColor(SUB))
            ic.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            pill.addView(ic, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            tab.addView(pill, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)
            ))

            val tv = TextView(this).apply {
                text = title
                textSize = 11f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.parseColor(SUB))
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            tab.addView(tv)
            nav.addView(tab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            tabs.add(tab)
        }
    }

    private fun show(i: Int) {
        // 切页时把目标页作废重建：保证每次进入页面看到的都是最新配置，
        // 而不是"上次打开时缓存的旧值"。
        if (current != i) pages[i] = null
        val isSamePage = (current == i)
        val savedScroll2 = if (isSamePage) currentPageScrollY() else 0
        current = i
        val page = pages[i] ?: buildPage(i).also { pages[i] = it }
        content.removeAllViews()
        content.addView(page)
        if (savedScroll2 > 0) {
            currentScrollView()?.post { currentScrollView()?.scrollTo(0, savedScroll2) }
        }
        if (i == 2) {
            BackTapRuntime.acquire(this, BackTapRuntime.OWNER_TEST)
            bindBackTapTest()
        } else {
            BackTapRuntime.testListener = null
            BackTapRuntime.release(this, BackTapRuntime.OWNER_TEST)
        }
        val sel = Color.parseColor(GREEN)
        val unsel = Color.parseColor(SUB)
        // 每个标签使用**独立**的 Drawable 实例。
        // 共享同一个实例时，View 的 background 状态会互相影响（曾导致
        // 同时出现两个绿色胶囊）。用 Mutate 也不行 —— 必须各自 new。
        tabs.forEachIndexed { idx, tab ->
            val pill = tab.getChildAt(0) as FrameLayout
            val ic = pill.getChildAt(0) as ImageView
            val tv = tab.getChildAt(1) as TextView
            val on = idx == i
            tv.setTextColor(if (on) sel else unsel)
            ic.setColorFilter(if (on) sel else unsel)
            pill.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(if (on) (0x26 shl 24 or 0x4CAF7D) else Color.TRANSPARENT)
            }
            tab.isSelected = on
        }
        content.announceForAccessibility(titles[i])
    }

    private fun buildPage(i: Int): View = when (i) {
        0 -> pageHome()
        1 -> pageServices()
        2 -> pageGestures()
        3 -> pageSettings()
        4 -> pageDiag()
        else -> pageAbout()
    }

    // ---------- 概览 ----------

    private fun pageHome(): View {
        val ll = column()
        big = BigToggleView(this)
        big.on = SvcRepo.isEnabled(this, GuardA11yService.COMP)
        big.setOnClickListener { onBigClick() }
        big.setOnLongClickListener {
            showMoreMenu()
            true
        }
        ll.addView(big, LinearLayout.LayoutParams(dp(96), dp(96)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(28)
        })

        summary = TextView(this).apply {
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(TEXT))
            gravity = Gravity.CENTER
        }
        ll.addView(summary, wrap().apply { topMargin = dp(14) })

        routeHome = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor(SUB))
            gravity = Gravity.CENTER
        }
        ll.addView(routeHome, wrap().apply { topMargin = dp(6) })

        val hint = TextView(this).apply {
            text = "单击 开/关　·　长按 加锁/置顶（读屏用「更多操作」）"
            textSize = 12f
            setTextColor(Color.parseColor(SUB))
            gravity = Gravity.CENTER
        }
        ll.addView(hint, wrap().apply { topMargin = dp(18) })

        // ── 语音助手入口（放在首页大按钮下面，最显眼的位置）──
        // 直接点就能听一句话来开关服务，不需要先去设置里配快捷键。

        homeEnabledTitle = sectionTitle("已开启服务")
        ll.addView(homeEnabledTitle, wrap().apply { topMargin = dp(22) })
        homeEnabled = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ll.addView(homeEnabled)

        homePinnedTitle = sectionTitle("置顶")
        ll.addView(homePinnedTitle, wrap().apply { topMargin = dp(18) })
        homePinned = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ll.addView(homePinned)
        fillHomeServices()

        refreshState()
        return ScrollView(this).apply { addView(ll) }
    }

    private var homeEnabled: LinearLayout? = null
    private var homePinned: LinearLayout? = null
    private var homeEnabledTitle: View? = null
    private var homePinnedTitle: View? = null

    /** 概览上半区 = 已开启；下半区 = 置顶（已开启的不重复）；其余只在服务页。 */
    private fun fillHomeServices() {
        val enabledBox = homeEnabled ?: return
        val pinnedBox = homePinned
        val rows = SvcRepo.all(this, userOnly = false)
            .filter { it.label.isNotBlank() && it.comp.contains('/') }
        enabledBox.removeAllViews()
        pinnedBox?.removeAllViews()

        val enabled = rows.filter { it.enabled }.sortedBy { it.label.lowercase() }
        val pinned = rows.filter { !it.enabled && isPinned(it.comp, it.pkg) }.sortedBy { it.label.lowercase() }

        if (enabled.isEmpty() && pinned.isEmpty()) {
            enabledBox.addView(TextView(this).apply {
                text = "当前没有已开启或置顶的服务"
                textSize = 15f
                setTextColor(Color.parseColor(SUB))
                setPadding(0, dp(14), 0, dp(14))
            })
            homePinnedTitle?.visibility = View.GONE
            pinnedBox?.visibility = View.GONE
        } else {
            homePinnedTitle?.visibility = if (pinned.isEmpty()) View.GONE else View.VISIBLE
            pinnedBox?.visibility = if (pinned.isEmpty()) View.GONE else View.VISIBLE
            val broken = AppSettings.lockBroken(this).filter { it.contains('/') }
            if (broken.isNotEmpty()) {
                enabledBox.addView(TextView(this).apply {
                    text = "⚠ 已被系统禁用：" + broken.joinToString("、") { SvcRepo.labelOf(this@MainActivity, it) } + "（需重新授权）"
                    textSize = 14f
                    setTextColor(Color.parseColor("#FFB300"))
                    setPadding(0, 0, 0, dp(10))
                })
            }
            addServiceGrid(enabledBox, enabled, 96f)
            pinnedBox?.let { addServiceGrid(it, pinned, 96f) }
        }
        Diag.log("home", "enabled=${enabled.size} pinned=${pinned.size} " +
            "enabledItems=[${enabled.joinToString("|") { it.comp + "@" + it.label }}] " +
            "pinnedItems=[${pinned.joinToString("|") { it.comp + "@" + it.label }}]")
    }

    /** 服务条目左侧：应用原始图标（48dp 概览 / 40dp 服务页），无图标用首字圆形占位。 */
    private fun serviceIcon(label: String, icon: android.graphics.drawable.Drawable?, sizeDp: Int): View {
        val size = dp(sizeDp)
        val holder = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#1FFFFFFF"))
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            contentDescription = null
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
        if (icon != null) {
            holder.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(3), dp(3), dp(3), dp(3))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                contentDescription = null
                setImageDrawable(icon)
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
        } else {
            holder.addView(TextView(this).apply {
                text = label.take(1)
                textSize = if (sizeDp >= 48) 17f else 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor(TEXT))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                contentDescription = null
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        return holder
    }

    private fun serviceRow(
        name: String,
        comp: String,
        on: Boolean,
        bound: Boolean,
        iconDp: Float = 84f,
        appIcon: android.graphics.drawable.Drawable? = null
    ): View {
        val pinned = isPinned(comp, comp.substringBefore('/'))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor(CARD))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            isFocusable = true
            contentDescription = (if (on) "关闭 " else "开启 ") + name +
                "，当前" + (if (on) "已开启" else "未开启") + (if (pinned) "，已置顶" else "")
            setOnClickListener { toggleService(comp, !on, name) }
        }
        attachServiceMenu(row, name, comp)
        row.addView(serviceIcon(name, appIcon, 48), LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(10) })
        val icon = BigToggleView(this).apply {
            serviceName = name
            this.iconSizeDp = iconDp
            this.on = on
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(iconDp.toInt()), dp(iconDp.toInt())))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, 0, 0)
        }
        texts.addView(TextView(this).apply {
            text = name
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(TEXT))
        })
        texts.addView(TextView(this).apply {
            text = (if (on) "已开启 · " else "未开启 · ") + (if (bound) "已绑定" else "未绑定") +
                (if (pinned) " · 置顶" else "")
            textSize = 14f
            setTextColor(Color.parseColor(if (on) GREEN else SUB))
        })
        texts.addView(TextView(this).apply {
            text = comp.substringBefore('/')
            textSize = 12f
            setTextColor(Color.parseColor(SUB))
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    /** 长按 + 读屏“更多操作”都进入同一个服务菜单。 */
    private fun attachServiceMenu(view: View, label: String, comp: String) {
        view.isLongClickable = true
        view.setOnLongClickListener {
            showServiceMenu(view, label, comp)
            true
        }
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_more, "更多操作"))
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                if (action == R.id.action_more) {
                    showServiceMenu(host, label, comp)
                    return true
                }
                return super.performAccessibilityAction(host, action, args)
            }
        }
    }

    private fun showServiceMenu(anchor: View, label: String, comp: String) {
        val locked = lockedList().any { SvcRepo.normalize(it) == SvcRepo.normalize(comp) }
        val pkg = comp.substringBefore('/')
        val pinned = isPinned(comp, pkg)
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, MenuTitles.lock(locked))
        pm.menu.add(0, 2, 1, MenuTitles.pin(pinned))
        // 改名：很多服务名是英文的，语音识别很难命中，改成「一号」这种中文名更可靠
        pm.menu.add(0, 3, 2, "改名字（便于语音唤醒）")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    setLockedComp(comp, !locked)
                    true
                }
                2 -> {
                    setPinnedPkg(pkg, !pinned)
                    true
                }
                3 -> {
                    editAlias(comp, label)
                    true
                }
                else -> false
            }
        }
        pm.show()
        Diag.log("menu", "target=$comp label=$label shown locked=$locked pinned=$pinned")
    }

    /** 网格：每个服务 = 一个圆形按钮（图标即开关，外圈表示状态），名称在圆下单独一行。 */
    private fun addServiceGrid(container: LinearLayout, rows: List<SvcRow>, circleDp: Float) {
        if (rows.isEmpty()) return
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val perRow = if (widthDp >= circleDp * 3 + 48f) 3 else 2
        var line: LinearLayout? = null
        rows.forEachIndexed { i, r ->
            if (i % perRow == 0) {
                line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                container.addView(line, wrap().apply { topMargin = dp(14) })
            }
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val btn = ServiceToggleView(this).apply {
                serviceName = r.label
                this.circleDp = circleDp
                appIcon = r.icon
                on = r.enabled
                locked = AppSettings.isLocked(this@MainActivity, r.comp)
                onToggle = { toggleService(r.comp, !r.enabled, r.label) }
            }
            attachServiceMenu(btn, r.label, r.comp)
            cell.addView(btn, LinearLayout.LayoutParams(dp(circleDp.toInt()), dp(circleDp.toInt())))
            cell.addView(TextView(this).apply {
                text = r.label
                textSize = 16f
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(Color.parseColor(TEXT))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
            line!!.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun isPinned(comp: String, pkg: String): Boolean {
        val list = pinnedList().map { SvcRepo.normalize(it) }
        return list.any { it == SvcRepo.normalize(comp) || it == pkg }
    }

    private fun toggleService(comp: String, want: Boolean, label: String) {
        if (!want && AppSettings.isLocked(this, comp)) {
            denyClose(comp)
            return
        }
        if (comp == GuardA11yService.COMP) prefs.edit().putBoolean("desired_on", want).apply()
        if (comp == TapAgentService.COMP) prefs.edit().putBoolean("tap_desired_on", want).apply()
        if (::summary.isInitialized && comp == GuardA11yService.COMP) summary.text = "正在切换…"
        Thread {
            val res = ToggleRouter.toggle(this, comp, want)
            Diag.log("toggle", "target=$comp want=$want route=${res.route} clicked=${res.clicked} verify=${res.verify}")
            runOnUiThread { afterToggle(res, want, label) }
        }.start()
    }

    /** 加锁项的关闭入口：不写 secure settings、不动 desired_on，只提示 + 震动 + 记账。 */
    private fun denyClose(comp: String) {
        Diag.log("lock", "deny close target=${SvcRepo.normalize(comp)} via=ui")
        Toast.makeText(this, "已加锁：需先在长按菜单解除加锁", Toast.LENGTH_SHORT).show()
        Triggers.vibrate(this, AppSettings.genVibMs(this))
        refreshState()
    }

    private fun refreshState() {
        val on = SvcRepo.isEnabled(this, GuardA11yService.COMP)
        if (::big.isInitialized) big.on = on
        if (::big.isInitialized) big.locked = AppSettings.isLocked(this, GuardA11yService.COMP)
        if (::summary.isInitialized) summary.text = if (on) "无障碍守护 已开启" else "无障碍守护 已关闭"
        if (::routeHome.isInitialized) routeHome.text = ToggleRouter.describe(this)
        if (::routeSetting.isInitialized) routeSetting.text = ToggleRouter.describe(this)
        secureRow?.text = "授权状态：" + secureText()
        shizukuStateRow?.text = "Shizuku 状态：" + shizukuText()
        fillHomeServices()
        if (::svcList.isInitialized) fillServices()
    }

    private fun onBigClick() {
        if (SvcRepo.isEnabled(this, GuardA11yService.COMP) && AppSettings.isLocked(this, GuardA11yService.COMP)) {
            denyClose(GuardA11yService.COMP)
            return
        }
        if (prefs.getBoolean("confirm_before_toggle", false)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("确认切换")
                .setMessage(if (SvcRepo.isEnabled(this, GuardA11yService.COMP)) "将关闭无障碍守护" else "将开启无障碍守护")
                .setPositiveButton("确认") { _, _ -> doToggle() }
                .setNegativeButton("取消", null)
                .show()
        } else {
            doToggle()
        }
    }

    private fun doToggle() {
        val want = !SvcRepo.isEnabled(this, GuardA11yService.COMP)
        toggleService(GuardA11yService.COMP, want, "无障碍守护")
    }

    private fun afterToggle(res: ToggleResult, want: Boolean, label: String = "无障碍守护") {
        refreshState()
        if (res.ok) {
            Toast.makeText(this, "$label 已${if (want) "开启" else "关闭"}", Toast.LENGTH_SHORT).show()
        } else {
            Diag.log("toggle", "fail ${res.logLine()}")
            MaterialAlertDialogBuilder(this)
                .setTitle("切换失败")
                .setMessage(res.reason ?: "未完成")
                .setPositiveButton("打开系统无障碍设置") { _, _ -> openA11ySettings() }
                .setNegativeButton("关闭", null)
                .show()
        }
        if (::diagBody.isInitialized) diagBody.text = Diag.dump()
    }

    // ---------- 菜单（长按 与 读屏“更多操作”共用） ----------

    private fun showMoreMenu() {
        val locked = lockedList().contains(GuardA11yService.COMP)
        val pinned = pinnedList().contains(packageName)
        val pm = PopupMenu(this, big)
        pm.menu.add(0, 1, 0, MenuTitles.lock(locked))
        pm.menu.add(0, 2, 1, MenuTitles.pin(pinned))
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    setLocked(!locked)
                    true
                }
                2 -> {
                    setPinned(!pinned)
                    true
                }
                else -> false
            }
        }
        pm.show()
        Diag.log("menu", "shown locked=$locked pinned=$pinned")
    }

    private fun lockedList(): List<String> =
        LegacyCodec.normalizeComps(prefs.getString("locked_comps", ""))

    private fun pinnedList(): List<String> =
        LegacyCodec.normalizePkgs(prefs.getString("pinned_pkgs", ""))

    private fun setLocked(lock: Boolean) = setLockedComp(GuardA11yService.COMP, lock)

    private fun setPinned(pin: Boolean) = setPinnedPkg(packageName, pin)

    /** 任意服务加锁 / 解锁：写入 locked_comps，守护服务下次修复会把它加回列表。 */
    private fun setLockedComp(comp: String, lock: Boolean) {
        val target = SvcRepo.normalize(comp)
        val list = lockedList().map { SvcRepo.normalize(it) }.toMutableList()
        if (lock) {
            if (!list.contains(target)) list.add(target)
        } else {
            list.removeAll { it == target }
        }
        prefs.edit().putString("locked_comps", LegacyCodec.join(list)).apply()
        Toast.makeText(this, if (lock) "已加锁：${SvcRepo.labelOf(this, target)}" else "已解除加锁", Toast.LENGTH_SHORT).show()
        Diag.log("lock", "target=$target locked=$lock list=${LegacyCodec.join(list)}")
        refreshState()
    }

    /** 任意服务置顶 / 取消置顶：写入 pinned_pkgs，主界面置顶项固定排在最前。 */
    private fun setPinnedPkg(pkg: String, pin: Boolean) {
        val list = pinnedList().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (pin) {
            if (!list.contains(pkg)) list.add(pkg)
        } else {
            list.removeAll { it == pkg }
        }
        prefs.edit().putString("pinned_pkgs", LegacyCodec.join(list)).apply()
        val label = SvcRepo.all(this).firstOrNull { it.pkg == pkg }?.label ?: pkg
        Toast.makeText(this, if (pin) "已置顶：$label" else "已取消置顶：$label", Toast.LENGTH_SHORT).show()
        Diag.log("pin", "target=$pkg pinned=$pin list=${LegacyCodec.join(list)}")
        refreshState()
    }

    // ---------- 服务列表 ----------

    private fun pageServices(): View {
        val ll = column()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        svcCount = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor(SUB))
        }
        head.addView(svcCount, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(pill("刷新") { fillServices() })
        ll.addView(head, wrap().apply { topMargin = dp(8) })

        val onlyUser = MaterialSwitch(this).apply {
            text = "仅显示用户安装的应用"
            textSize = 14f
            setTextColor(Color.parseColor(TEXT))
            isChecked = AppSettings.onlyUserApps(this@MainActivity)
            isFocusable = true
            setOnCheckedChangeListener { _, v ->
                prefs.edit().putBoolean("only_user_apps", v).apply()
                Diag.log("setting", "only_user_apps=$v")
                // 改完设置立即重建当前页，保证显示与配置一致
                window.decorView.post { invalidateCurrentPage() }
                fillServices()
            }
        }
        ll.addView(cardWrap(onlyUser), wrap().apply { topMargin = dp(6) })

        svcList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ll.addView(svcList)
        fillServices()
        return ScrollView(this).apply { addView(ll) }
    }

    private fun fillServices() {
        if (!::svcList.isInitialized) return
        val rows = SvcRepo.all(this)
            .filter { it.label.isNotBlank() && it.comp.contains('/') }
            .sortedWith(
                compareByDescending<SvcRow> { isPinned(it.comp, it.pkg) }
                    .thenByDescending { it.enabled }
                    .thenBy { it.label.lowercase() }
            )
        svcList.removeAllViews()
        if (::svcCount.isInitialized) {
            svcCount.text = "共 ${rows.size} 个服务 · 已开启 ${rows.count { it.enabled }}"
        }
        if (rows.isEmpty()) {
            svcList.addView(hint("当前没有枚举到任何辅助功能服务"))
        }
        addServiceGrid(svcList, rows, 80f)
        Diag.log("svclist", "count=${rows.size} items=[${rows.joinToString("|") { it.comp + "@" + it.label + (if (it.enabled) "*" else "") }}]")
    }

    // ---------- 手势（音量键 / 摇一摇 / 锁屏动作） ----------

    private fun pageGestures(): View {
        val ll = column()

        ll.addView(sectionTitle("音量键 · 双击触发"))
        ll.addView(hint("需开启「点按代理」服务后才生效。"))

        val volSw = MaterialSwitch(this).apply {
            text = "音量键触发"
            textSize = 14f
            setTextColor(Color.parseColor(TEXT))
            isChecked = AppSettings.volKeyOn(this@MainActivity)
            isFocusable = true
            setOnCheckedChangeListener { _, v ->
                prefs.edit().putBoolean("volkey_on", v).apply()
                Diag.log("setting", "volkey_on=$v")
                // 改完设置立即重建当前页，保证显示与配置一致
                window.decorView.post { invalidateCurrentPage() }
                // 关闭时立刻清掉残留的按键计数与已排队的确认任务，
                // 否则「双击一下 → 马上关开关」时，那条 220ms 后执行的
                // 待触发任务仍会跑，表现为关掉后还切了一次服务。
                TapAgentService.instance?.onVolKeySwitchChanged()
                if (v && TapAgentService.instance == null) toast("需先开启「点按代理」服务，音量键才会生效")
            }
        }
        ll.addView(cardWrap(volSw))

        // 音量键依赖「点按代理」服务的 onKeyEvent —— 服务没开就完全收不到按键。
        // 这点很不直观（用户只会觉得"音量键失灵"），所以常驻显示状态。
        if (TapAgentService.instance == null) {
            ll.addView(warnCard(
                "⚠ 音量键当前不会生效",
                "「点按代理」服务未运行。音量键事件只能由无障碍服务接收，"
                    + "服务没开就完全收不到按键。"
            ))
            ll.addView(cardWrap(clickRow("去开启「点按代理」服务") { openA11ySettings() }))
        }

        ll.addView(cardWrap(selectorRow("双击上键", targetLabel(AppSettings.volUpTarget(this))) {
            pickTarget("选择双击上键的目标服务", AppSettings.volUpTarget(this)) { c ->
                prefs.edit().putString("volup_target", c).apply()
                show(2)
            }
        }))
        ll.addView(cardWrap(selectorRow("三击上键", tripleLabel(AppSettings.volUpTripleTarget(this))) {
            pickTarget("选择三击上键的目标服务（选「无」则三击不响应，只认双击）",
                AppSettings.volUpTripleTarget(this)) { c ->
                prefs.edit().putString("volup_triple_target", c).apply()
                show(2)
            }
        }))
        ll.addView(cardWrap(selectorRow("双击下键", targetLabel(AppSettings.volDownTarget(this))) {
            pickTarget("选择双击下键的目标服务", AppSettings.volDownTarget(this)) { c ->
                prefs.edit().putString("voldown_target", c).apply()
                show(2)
            }
        }))
        ll.addView(cardWrap(selectorRow("三击下键", tripleLabel(AppSettings.volDownTripleTarget(this))) {
            pickTarget("选择三击下键的目标服务（选「无」则三击不响应，只认双击）",
                AppSettings.volDownTripleTarget(this)) { c ->
                prefs.edit().putString("voldown_triple_target", c).apply()
                show(2)
            }
        }))
        ll.addView(hint(
            "双击和三击可以是不同服务。判定方式：按第 2 下后等 ${KeyPressLogic.DEFAULT_WINDOW_MS}ms —— "
                + "期间按了第 3 下就执行「三击」目标，否则执行「双击」目标。"
                + "连按 ${KeyPressLogic.MAX_COMBO} 下以上视为在调音量，不触发任何手势。"
        ))
        val volLockSw = MaterialSwitch(this).apply {
            text = "锁屏时也响应音量键"
            textSize = 14f
            setTextColor(Color.parseColor(TEXT))
            isChecked = AppSettings.allowWhenLocked(this@MainActivity, AppSettings.LOCK_VOL)
            isFocusable = true
            setOnCheckedChangeListener { _, v ->
                AppSettings.setAllowWhenLocked(this@MainActivity, AppSettings.LOCK_VOL, v)
                Diag.log("setting", "lock_allow_vol=$v")
                window.decorView.post { invalidateCurrentPage() }
            }
        }
        ll.addView(cardWrap(volLockSw))
        ll.addView(hint("关闭后，锁屏状态下双击/三击音量键不会切换服务（音量本身照常可调）。"))

        ll.addView(cardWrap(selectorRow("连击窗口", "${AppSettings.volWindowMs(this)} ms") {
            pickOption(
                "音量键连击间隔（超出即从 1 重计）",
                listOf(
                    "100" to "100ms（最快，需按得很快）",
                    "150" to "150ms",
                    "200" to "200ms",
                    "300" to "300ms（推荐）",
                    "400" to "400ms",
                    "500" to "500ms",
                    "600" to "600ms（慢手）"
                ),
                AppSettings.volWindowMs(this).toString()
            ) { v ->
                prefs.edit().putInt("trigger_window_ms", v.toInt()).apply()
                show(2)
            }
        }))
        // ══════════ 摇一摇 ══════════
        ll.addView(sectionTitle("摇一摇"))
        ll.addView(hint(
            "甩动手机触发。判定算法用业界经典实现（变化率 + 连续计数）："
                + "甩动是反复来回、每次换向都产生一次大变化率；"
                + "而「拿起/放下手机」虽然撞击更猛，但只发生一次，凑不满连续次数。"
        ))

        val shakeSw = MaterialSwitch(this).apply {
            text = "摇一摇触发"
            textSize = 14f
            setTextColor(Color.parseColor(TEXT))
            isChecked = AppSettings.shakeOn(this@MainActivity)
            isFocusable = true
            setOnCheckedChangeListener { _, v ->
                prefs.edit().putBoolean("shake_on", v).apply()
                Diag.log("setting", "shake_on=$v")
                TapAgentService.instance?.reloadShake()
                if (v && TapAgentService.instance == null) {
                    toast("需先开启「点按代理」服务，摇一摇才会生效")
                }
                window.decorView.post { invalidateCurrentPage() }
            }
        }
        ll.addView(cardWrap(shakeSw))

        // 注意：配置项**始终显示**，不因为开关关闭而隐藏。
        // 早期版本把整块配置放在 else 分支里，导致用户开着开关时配好了、
        // 关掉开关就再也看不到「摇动次数」「目标服务」——
        // 表现为「摇一摇次数不能设定」（其实是整个配置区被隐藏了）。
        if (!AppSettings.shakeOn(this)) {
            ll.addView(hint("摇一摇当前已关闭。下面的参数可以先调好，打开开关即生效。"))
        }
        run {
            ll.addView(cardWrap(selectorRow(
                "摇一摇灵敏度",
                shakeSensitivityLabel()
            ) {
                pickOption(
                    "灵敏度（1 = 最难触发，10 = 最灵敏）",
                    (1..10).map { lv ->
                        lv.toString() to "$lv / 10 · 阈值 %.0f".format(
                            ShakeLogic.forceForSensitivity(lv)
                        )
                    },
                    AppSettings.shakeSensitivity(this).toString()
                ) { v ->
                    prefs.edit().putInt("shake_sensitivity", v.toInt()).apply()
                    Diag.log("setting", "shake_sensitivity=$v")
                    TapAgentService.instance?.reloadShake()
                    show(2)
                }
            }))
            ll.addView(hint(
                "如果手机放着不动也会触发，把灵敏度调低（数字小）。"
                    + "如果甩得再用力也不响应，把灵敏度调高（数字大）。"
            ))

            ll.addView(cardWrap(selectorRow("摇动次数", "摇 ${AppSettings.shakeCount(this)} 次触发") {
                pickOption(
                    "摇动次数（1-10）",
                    (1..10).map { it.toString() to "摇 $it 次" },
                    AppSettings.shakeCount(this).toString()
                ) { v ->
                    prefs.edit().putInt("shake_count_free", v.toInt()).apply()
                    TapAgentService.instance?.reloadShake()
                    show(2)
                }
            }))
            ll.addView(cardWrap(selectorRow("摇一摇目标服务", targetLabel(AppSettings.shakeTarget(this))) {
                pickTarget("选择摇一摇的目标服务", AppSettings.shakeTarget(this)) { c ->
                    prefs.edit().putString("shake_target", c).apply()
                    TapAgentService.instance?.reloadShake()
                    show(2)
                }
            }))

            val shakeLockSw = MaterialSwitch(this).apply {
                text = "锁屏时也响应摇一摇"
                textSize = 14f
                setTextColor(Color.parseColor(TEXT))
                isChecked = AppSettings.allowWhenLocked(this@MainActivity, AppSettings.LOCK_SHAKE)
                isFocusable = true
                setOnCheckedChangeListener { _, v ->
                    AppSettings.setAllowWhenLocked(this@MainActivity, AppSettings.LOCK_SHAKE, v)
                    Diag.log("setting", "lock_allow_shake=$v")
                    window.decorView.post { invalidateCurrentPage() }
                }
            }
            ll.addView(cardWrap(shakeLockSw))
            ll.addView(hint(
                "摇一摇默认锁屏时不响应 —— 手机放口袋里走路、放桌上震动都可能误触发。"
            ))

            // ── 两段式震动：过程弱震 + 成功强震 ──
            ll.addView(sectionTitle("摇一摇震动反馈"))
            val tickSw = MaterialSwitch(this).apply {
                text = "摇动过程中给弱震动"
                textSize = 14f
                setTextColor(Color.parseColor(TEXT))
                isChecked = AppSettings.shakeTickVibOn(this@MainActivity)
                isFocusable = true
                setOnCheckedChangeListener { _, v ->
                    prefs.edit().putBoolean("shake_tick_vib", v).apply()
                    Diag.log("setting", "shake_tick_vib=$v")
                    TapAgentService.instance?.reloadShake()
                    window.decorView.post { invalidateCurrentPage() }
                }
            }
            ll.addView(cardWrap(tickSw))
            ll.addView(hint(
                "开启后：每累计满一次轻震一下（能数出还差几次），真正触发时按「摇一摇震动」的时长强震。"
                    + "关闭则全程静默，只有成功时震一次。"
            ))

            if (AppSettings.shakeTickVibOn(this)) {
                ll.addView(cardWrap(selectorRow(
                    "过程弱震时长",
                    "${AppSettings.shakeTickVibMs(this)} ms"
                ) {
                    pickOption(
                        "摇动过程中的弱震动时长（尽量短，才能与成功震动区分）",
                        listOf(
                            "8" to "毛羽（8ms）",
                            "12" to "很轻（12ms，默认）",
                            "18" to "轻（18ms）",
                            "28" to "中（28ms）"
                        ),
                        AppSettings.shakeTickVibMs(this).toString()
                    ) { v ->
                        prefs.edit().putInt("shake_tick_vib_ms", v.toInt()).apply()
                        TapAgentService.instance?.reloadShake()
                        show(2)
                    }
                }))
            }

            // 实时测试区
            ll.addView(sectionTitle("摇一摇 · 测试"))
            val shakeTestTv = TextView(this).apply {
                textSize = 13f
                setTextColor(Color.parseColor(TEXT))
                setBackgroundColor(Color.parseColor(CARD2))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                text = "等待传感器事件…\n（拿起手机甩一下试试）"
            }
            shakeTest = shakeTestTv
            ll.addView(cardWrap(shakeTestTv))
        }

        // ══════════ 震动 ══════════
        //
        // 用**折叠面板**而不是给每个手势来一行下拉：
        //   展开后三大类手势的档位一目了然，点一下就能换
        //   收起时只占一行，不把页面撑长
        //   默认档位是「最弱」，符合"有反馈但不吵"的默认期待
        ll.addView(sectionTitle("震动反馈"))

        // ── 总闸：一键全关 ──
        val vibAllSw = MaterialSwitch(this).apply {
            text = "关闭所有震动"
            textSize = 14f
            setTextColor(Color.parseColor(TEXT))
            isChecked = AppSettings.vibAllOff(this@MainActivity)
            isFocusable = true
            setOnCheckedChangeListener { _, v ->
                prefs.edit().putBoolean("vib_all_off", v).apply()
                Diag.log("setting", "vib_all_off=$v")
                window.decorView.post { invalidateCurrentPage() }
            }
        }
        ll.addView(cardWrap(vibAllSw))

        // 成功震动单独一个开关：有人只要过程反馈不要成功震动，或反之。
        // 默认开启 —— 触发成功给一声确认，这是绝大多数人的期待。
        val vibSuccessSw = MaterialSwitch(this).apply {
            text = "触发成功时震动"
            textSize = 14f
            setTextColor(Color.parseColor(if (AppSettings.vibAllOff(this@MainActivity)) SUB else TEXT))
            isChecked = AppSettings.vibOnSuccess(this@MainActivity)
            isEnabled = !AppSettings.vibAllOff(this@MainActivity)
            isFocusable = true
            setOnCheckedChangeListener { _, v ->
                prefs.edit().putBoolean("vib_on_success", v).apply()
                Diag.log("setting", "vib_on_success=$v")
                window.decorView.post { invalidateCurrentPage() }
            }
        }
        ll.addView(cardWrap(vibSuccessSw))

        val allOff = AppSettings.vibAllOff(this)

        // ── 折叠容器 ──
        val vibExpanded = prefs.getBoolean("ui_vib_expanded", false)
        val vibSummary = if (allOff) {
            "已全部关闭"
        } else {
            val s = mutableListOf<String>()
            if (AppSettings.vibOn(this, AppSettings.VIB_VOL)) s.add("音量键 " + vibTierLabel(genVibMsOf(), vibAmpOf(AppSettings.VIB_VOL)))
            if (AppSettings.vibOn(this, AppSettings.VIB_TAP)) s.add("敲击 " + vibTierLabel(vibMsOf(AppSettings.VIB_TAP), vibAmpOf(AppSettings.VIB_TAP)))
            if (AppSettings.vibOn(this, AppSettings.VIB_SHAKE)) s.add("摇一摇 " + vibTierLabel(vibMsOf(AppSettings.VIB_SHAKE), vibAmpOf(AppSettings.VIB_SHAKE)))
            if (s.isEmpty()) "全部关闭" else s.joinToString(" · ")
        }

        ll.addView(cardWrap(selectorRow(
            if (vibExpanded) "震动档位 ▲" else "震动档位 ▼",
            vibSummary
        ) {
            prefs.edit().putBoolean("ui_vib_expanded", !vibExpanded).apply()
            window.decorView.post { invalidateCurrentPage() }
        }))

        if (vibExpanded) {
            val triple = listOf(
                Triple(AppSettings.VIB_VOL, "音量键", "双击上/下键触发时"),
                Triple(AppSettings.VIB_TAP, "背面敲击", "敲击计数与触发时"),
                Triple(AppSettings.VIB_SHAKE, "摇一摇", "甩动触发时")
            )
            triple.forEach { (kind, label, desc) ->
                val curMs = if (kind == AppSettings.VIB_VOL) genVibMsOf() else vibMsOf(kind)
                val on = AppSettings.vibOn(this, kind) && !allOff

                // 开关
                val sw = MaterialSwitch(this).apply {
                    text = label + "　·　" + desc
                    textSize = 14f
                    setTextColor(Color.parseColor(if (allOff) SUB else TEXT))
                    isChecked = on
                    isEnabled = !allOff
                    isFocusable = true
                    setOnCheckedChangeListener { _, v ->
                        AppSettings.setVibOn(this@MainActivity, kind, v)
                        Diag.log("setting", "vib_$kind=$v")
                        window.decorView.post { invalidateCurrentPage() }
                    }
                }
                ll.addView(cardWrap(sw))

                // 五档按钮行
                val tierRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                }
                val curAmp = vibAmpOf(kind)
                VIB_TIERS.forEach { tier ->
                    val name = tier.name
                    val selected = (curMs == tier.ms && curAmp == tier.amp)
                    tierRow.addView(TextView(this).apply {
                        text = name
                        textSize = 12f
                        gravity = android.view.Gravity.CENTER
                        setTextColor(
                            Color.parseColor(if (selected) "#0E1215" else if (allOff || !on) SUB else TEXT)
                        )
                        typeface = Typeface.create(
                            if (selected) "sans-serif-medium" else "sans-serif", Typeface.NORMAL
                        )
                        val padH = dp(4); val padV = dp(7)
                        setPadding(padH, padV, padH, padV)
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = dp(10).toFloat()
                            setColor(
                                when {
                                    selected -> Color.parseColor(GREEN)
                                    else -> Color.parseColor(CARD2)
                                }
                            )
                        }
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            .apply { marginStart = dp(3); marginEnd = dp(3) }
                        isClickable = true
                        isFocusable = true
                        contentDescription = "$label 震动档位 $name"
                        setOnClickListener {
                            // 时长和幅度同时写：只写时长的话，"轻"只体现为"短"，手感依然硬
                            if (kind == AppSettings.VIB_VOL) {
                                prefs.edit().putInt("gen_vib", tier.ms).apply()
                            } else {
                                AppSettings.setVibMs(this@MainActivity, kind, tier.ms)
                            }
                            AppSettings.setVibAmp(this@MainActivity, kind, tier.amp)
                            AppSettings.setVibOn(this@MainActivity, kind, tier.ms > 0)
                            Diag.log("setting", "vib_$kind=${tier.ms}ms amp=${tier.amp}")
                            window.decorView.post { invalidateCurrentPage() }
                        }
                    })
                }
                ll.addView(cardWrap(tierRow))
            }
            ll.addView(hint(
                "档位说明：「最弱」几乎无感、「中等」清晰可辨、「最强」用力震动。"
                    + "每一类手势都可以单独设档，也可以单独关掉。"
            ))
        }

        ll.addView(sectionTitle("背面敲击"))
        val backTapSw = MaterialSwitch(this).apply {
            text = "背面敲击（双击 / 三击）"
            textSize = 14f
            setTextColor(Color.parseColor(TEXT))
            isChecked = AppSettings.backTapOn(this@MainActivity)
            isFocusable = true
            setOnCheckedChangeListener { _, v ->
                prefs.edit().putBoolean("backtap_on", v).apply()
                Diag.log("setting", "backtap_on=$v")
                // 改完设置立即重建当前页，保证显示与配置一致
                window.decorView.post { invalidateCurrentPage() }
                BackTapRuntime.reload()
                if (v) BackTapRuntime.acquire(this@MainActivity, BackTapRuntime.OWNER_TEST)
                renderBackTapTest(BackTapRuntime.lastEvent)
                if (v && AppSettings.backTapTarget(this@MainActivity).isEmpty()) {
                    toast("请先选择「目标服务」，否则敲击不会切换任何服务")
                }
            }
        }
        ll.addView(cardWrap(backTapSw))

        ll.addView(cardWrap(selectorRow(
            "敲击次数",
            "敲 ${AppSettings.backTapCount(this)} 次触发"
        ) {
            pickOption(
                "背面敲击次数",
                listOf("2" to "双击（2 次）", "3" to "三击（3 次）"),
                AppSettings.backTapCount(this).toString()
            ) { v ->
                prefs.edit().putInt("backtap_count", v.toInt()).apply()
                BackTapRuntime.reload()
                show(2)
            }
        }))

        ll.addView(cardWrap(selectorRow("灵敏度", backTapSensitivityLabel()) {
            backTapSensitivityDialog()
        }))

        ll.addView(cardWrap(selectorRow(
            "目标服务（双击）",
            targetLabel(AppSettings.backTapTarget(this))
        ) {
            pickTarget("选择背面双击要切换的服务", AppSettings.backTapTarget(this)) { c ->
                prefs.edit().putString("backtap_target", c).apply()
                renderBackTapTest(BackTapRuntime.lastEvent)
                show(2)
            }
        }))

        ll.addView(cardWrap(selectorRow(
            "目标服务（三击）",
            tripleLabel(AppSettings.backTapTripleTarget(this))
        ) {
            pickTarget(
                "选择背面三击要切换的服务（选「无」则三击不响应，敲 2 下即触发）",
                AppSettings.backTapTripleTarget(this)
            ) { c ->
                prefs.edit().putString("backtap_triple_target", c).apply()
                BackTapRuntime.reload()
                renderBackTapTest(BackTapRuntime.lastEvent)
                show(2)
            }
        }))
        ll.addView(hint(
            "配了三击目标后，敲 2 下会先等一拍（约 0.3–0.5 秒）再判定："
                + "期间又敲了第 3 下就切「三击」服务，否则切「双击」服务。"
                + "不配三击目标时保持原样，敲满即触发。"
        ))

        val tapLockSw = MaterialSwitch(this).apply {
            text = "锁屏时也响应敲击"
            textSize = 14f
            setTextColor(Color.parseColor(TEXT))
            isChecked = AppSettings.allowWhenLocked(this@MainActivity, AppSettings.LOCK_BACKTAP)
            isFocusable = true
            setOnCheckedChangeListener { _, v ->
                AppSettings.setAllowWhenLocked(this@MainActivity, AppSettings.LOCK_BACKTAP, v)
                Diag.log("setting", "lock_allow_backtap=$v")
                window.decorView.post { invalidateCurrentPage() }
            }
        }
        ll.addView(cardWrap(tapLockSw))

        val screenOffSw = MaterialSwitch(this).apply {
            text = "熄屏后继续检测（耗电增加）"
            textSize = 14f
            setTextColor(Color.parseColor(TEXT))
            isChecked = AppSettings.backTapScreenOff(this@MainActivity)
            isFocusable = true
            setOnCheckedChangeListener { _, v ->
                prefs.edit().putBoolean("backtap_screen_off", v).apply()
                Diag.log("setting", "backtap_screen_off=$v")
                // 改完设置立即重建当前页，保证显示与配置一致
                window.decorView.post { invalidateCurrentPage() }
                BackTapRuntime.reload()
            }
        }
        ll.addView(cardWrap(screenOffSw))
        ll.addView(hint(
            "本机加速度计是 Non-wakeup 类型：不开启上面这项时，系统深度睡眠期间收不到样本；" +
                "开启后需持有 CPU 唤醒锁维持采样，待机耗电会明显增加。"
        ))

        ll.addView(sectionTitle("背面敲击 · 测试"))
        val testTv = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor(TEXT))
            setBackgroundColor(Color.parseColor(CARD))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        backTapTest = testTv
        ll.addView(cardWrap(testTv))
        ll.addView(hint(
            "在此页试敲：能看到峰值强度、置信度与是否计入。测试区打开时即使两个服务都没开也会临时启动监听；" +
                "离开本页后只由「点按代理」或保活前台服务维持。"
        ))

        ll.addView(sectionTitle("锁屏 / 解锁动作"))
        ll.addView(cardWrap(selectorRow("锁屏时", actionLabel(AppSettings.lockAction(this))) {
            pickAction("锁屏时执行的动作", AppSettings.lockAction(this)) { a ->
                prefs.edit().putString("lock_action", a).apply()
                show(2)
            }
        }))
        ll.addView(cardWrap(selectorRow("解锁时", actionLabel(AppSettings.unlockAction(this))) {
            pickAction("解锁时执行的动作", AppSettings.unlockAction(this)) { a ->
                prefs.edit().putString("unlock_action", a).apply()
                show(2)
            }
        }))
        ll.addView(hint(
            "推荐选「确保守护服务开启」：锁屏/解锁时只检查一次 —— "
                + "服务在跑就不动（听书不中断），服务崩了或被关了才拉起来。"
        ))
        ll.addView(hint(
            "不要选「切换」：那样每次亮屏熄屏都会把服务关掉又打开，读屏会反复中断。"
        ))
        return ScrollView(this).apply { addView(ll) }
    }


    // ---------- 背面敲击 ----------

    /** 摇一摇测试区渲染。 */
    private fun renderShakeTest(s: ShakeWatcher.Sample?) {
        val tv = shakeTest ?: return
        if (s == null) {
            tv.text = "等待传感器事件…\n（拿起手机甩一下试试）"
            return
        }
        val sb = StringBuilder()
        sb.append("变化率 %.0f".format(s.speed))
        sb.append(" / 阈值 %.0f".format(s.threshold))
        sb.append("\n计数 %d / %d".format(s.count, s.need))
        when (s.verdict) {
            ShakeLogic.Verdict.OK -> sb.append("\n✓ 已触发")
            ShakeLogic.Verdict.COUNTING -> sb.append("\n… 累计中")
            ShakeLogic.Verdict.COOLDOWN -> sb.append("\n冷却中")
            else -> Unit
        }
        tv.text = sb.toString()
    }

    private fun bindShakeTest() {
        ShakeWatcher.testListener = { smp -> shakeTest?.post { renderShakeTest(smp) } }
    }

    private fun unbindShakeTest() {
        ShakeWatcher.testListener = null
    }

    private fun bindBackTapTest() {
        val tv = backTapTest ?: return
        BackTapRuntime.testListener = { ev -> tv.post { renderBackTapTest(ev) } }
        renderBackTapTest(BackTapRuntime.lastEvent)
    }

    private fun renderBackTapTest(ev: BackTapLogic.TapEvent?) {
        val tv = backTapTest ?: return
        val sb = StringBuilder()
        sb.append("监听状态：").append(BackTapRuntime.statusText(this))
        sb.append("　·　")
        sb.append(
            if (AppSettings.backTapOn(this)) "敲 ${AppSettings.backTapCount(this)} 次触发"
            else "开关已关闭"
        )
        if (ev == null) {
            sb.append("\n最近一次：暂无（双击 / 三击手机背面试试）")
        } else {
            val verdict = when {
                ev.triggered -> "判定成功，已触发切换"
                ev.accepted -> "计入 ${ev.taps}/${ev.need}"
                else -> "未计入（${ev.reason}）"
            }
            sb.append("\n最近一次：峰值 %.2f m/s² · %s".format(ev.peak, verdict))
            sb.append("\n置信度：%.2f".format(ev.confidence))
        }
        tv.text = sb.toString()
    }

    /** 摇一摇灵敏度的一行显示。 */
    private fun shakeSensitivityLabel(): String {
        val lv = AppSettings.shakeSensitivity(this)
        return "$lv / 10 · 阈值 %.0f".format(ShakeLogic.forceForSensitivity(lv))
    }

    private fun backTapSensitivityLabel(): String {
        val lv = AppSettings.backTapSensitivity(this)
        val p = BackTapLogic.paramsForSensitivity(lv)
        // 显示噪声门限而不是"振幅阈值"：Columbus 算法判的是**峰值形态**
        // （正峰位置 + 负峰配对），不是单一阈值。noise 是唯一可调的灵敏度旋钮。
        return "$lv / 10 · 噪声门限 %.3f".format(p.minNoise)
    }

    private fun backTapLevelText(level: Int): String {
        val p = BackTapLogic.paramsForSensitivity(level)
        return ("$level / 10 · 噪声门限 %.3f\n"
            + "双击间隔 %d–%dms · 峰值窗口 %d").format(
            p.minNoise,
            BackTapLogic.MIN_TAP_GAP_NS / 1_000_000L,
            BackTapLogic.MAX_TAP_GAP_NS / 1_000_000L,
            p.peakWindow
        )
    }

    private fun backTapSensitivityDialog() {
        val sb = SeekBar(this).apply {
            max = BackTapLogic.LEVEL_MAX - BackTapLogic.LEVEL_MIN
            progress = AppSettings.backTapSensitivity(this@MainActivity) - BackTapLogic.LEVEL_MIN
        }
        val tv = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor(TEXT))
            text = backTapLevelText(AppSettings.backTapSensitivity(this@MainActivity))
        }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tv.text = backTapLevelText(p + BackTapLogic.LEVEL_MIN)
            }

            override fun onStartTrackingTouch(s: SeekBar?) = Unit
            override fun onStopTrackingTouch(s: SeekBar?) = Unit
        })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            addView(tv)
            addView(sb)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("灵敏度（1 最稳 / 10 最灵敏）")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val lv = sb.progress + BackTapLogic.LEVEL_MIN
                prefs.edit().putInt("backtap_sensitivity", lv).apply()
                BackTapRuntime.reload()
                show(2)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 按功能类别显示震动时长（含"已关闭"与"全局关闭"两种状态）。 */
    private fun vibLabelFor(ms: Int, kind: String): String {
        if (AppSettings.vibAllOff(this)) return "已全局关闭"
        if (!AppSettings.vibOn(this, kind)) return "已关闭（此手势不震动）"
        return when {
            ms <= 0 -> "关闭（不震动）"
            ms <= 15 -> "极轻（${ms}ms）"
            ms <= 30 -> "很轻（${ms}ms）"
            ms <= 60 -> "轻（${ms}ms）"
            ms <= 110 -> "中（${ms}ms）"
            ms <= 160 -> "标准（${ms}ms）"
            ms <= 200 -> "较强（${ms}ms）"
            else -> "强（${ms}ms）"
        }
    }

    private fun vibLabel(ms: Int): String {
        if (AppSettings.vibAllOff(this)) return "已全局关闭"
        return when {
            ms <= 0 -> "关闭（不震动）"
            ms <= 20 -> "极轻（${ms}ms）"
            ms <= 40 -> "很轻（${ms}ms）"
            ms <= 60 -> "轻（${ms}ms）"
            ms <= 110 -> "中（${ms}ms）"
            ms <= 160 -> "标准（${ms}ms）"
            ms <= 200 -> "较强（${ms}ms）"
            else -> "强（${ms}ms）"
        }
    }

    private fun actionLabel(a: String): String = when {
        a.isEmpty() || a == AppSettings.ACT_NONE -> "无动作"
        a == AppSettings.ACT_ENSURE_MASTER -> "确保守护服务开启（崩了才拉起）"
        a.startsWith(AppSettings.ACT_ENSURE_PREFIX) -> {
            val t = a.removePrefix(AppSettings.ACT_ENSURE_PREFIX)
            if (t == "master") "确保守护服务开启（崩了才拉起）"
            else "确保开启：" + targetLabel(t)
        }
        a == AppSettings.ACT_MASTER -> "打开守护服务（总开关）"
        a.startsWith(AppSettings.ACT_PREFIX) ->
            "切换：" + targetLabel(a.removePrefix(AppSettings.ACT_PREFIX))
        else -> a
    }

    /**
     * 选一个"锁屏/解锁时要做什么"。
     *
     * 选项顺序经过刻意安排：**「确保开启」排在最前**，因为它是
     * 锁屏/解锁场景唯一正确的语义 —— 周期性事件必须做自愈，
     * 不能做切换（否则服务正常时也会被反复关开）。
     */
    private fun pickAction(title: String, current: String, onPick: (String) -> Unit) {
        val opts = mutableListOf(
            AppSettings.ACT_NONE to "无动作",
            AppSettings.ACT_ENSURE_MASTER to "★ 确保守护服务开启（已开则不动，崩了才拉起）"
        )
        // 逐个服务的「确保开启」
        SvcRepo.all(this).forEach {
            opts.add(AppSettings.ACT_ENSURE_PREFIX + it.comp to "确保开启：${it.label}")
        }
        // 切换类放在后面，标注"不推荐用于锁屏"
        opts.add(AppSettings.ACT_MASTER to "切换守护服务（不推荐用于锁屏）")
        SvcRepo.all(this).forEach {
            opts.add(AppSettings.ACT_PREFIX + it.comp to "切换：${it.label}（不推荐用于锁屏）")
        }
        pickOption(title, opts, current, onPick)
    }

    // ---------- 设置 ----------

    private fun pageSettings(): View {
        val ll = column()
        ll.addView(sectionTitle("执行方式"))
        modeButtons = ArrayList()
        listOf(
            RouteLogic.MODE_AUTO to "自动",
            RouteLogic.MODE_SECURE to "直写",
            RouteLogic.MODE_ROOT to "root",
            RouteLogic.MODE_SHIZUKU to "Shizuku",
            RouteLogic.MODE_TAP to "点按"
        ).chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { (mode, label) ->
                val b = pill(label) { setMode(mode) }
                modeButtons.add(b)
                row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                })
            }
            ll.addView(row, wrap().apply { topMargin = dp(4) })
        }
        syncModeButtons()

        // ---------- root 检测 ----------
        ll.addView(cardWrap(selectorRow("root 权限", rootLabel()) {
            probeRootNow()
        }))
        ll.addView(hint(
            "已 root 的设备可以用 root 方式切换：不需要 adb、不需要装 Shizuku，" +
                "重启后依然有效。点上面这一项会触发一次 su 探测（root 管理器可能弹窗询问）。" +
                "未 root 的设备请忽略此项。"
        ))

        routeSetting = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor(SUB))
        }
        ll.addView(routeSetting, wrap().apply { topMargin = dp(10) })
        routeSetting.text = ToggleRouter.describe(this)

        ll.addView(sectionTitle("直写权限 · WRITE_SECURE_SETTINGS"))
        secureRow = clickRow("授权状态：" + secureText()) { checkSecurePermission(true) } as TextView
        ll.addView(cardWrap(secureRow!!))
        val cmd = TextView(this).apply {
            text = SecureSettingsProvider.GRANT_COMMAND
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor(TEXT))
            setBackgroundColor(Color.parseColor(CARD))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextIsSelectable(true)
            contentDescription = "授权命令：${SecureSettingsProvider.GRANT_COMMAND}"
        }
        ll.addView(cardWrap(cmd))
        ll.addView(cardWrap(clickRow("复制授权命令") {
            copyText(SecureSettingsProvider.GRANT_COMMAND, "授权命令已复制到剪贴板")
        }))
        ll.addView(cardWrap(clickRow("我已执行，检测授权") { checkSecurePermission(true) }))

        ll.addView(sectionTitle("快捷触发（需「点按代理」服务运行）"))
        val volSw = MaterialSwitch(this).apply {
            text = "音量键启动（双击音量上/下键）"
            textSize = 14f
            setTextColor(Color.parseColor(TEXT))
            isChecked = AppSettings.volKeyOn(this@MainActivity)
            isFocusable = true
            setOnCheckedChangeListener { _, v ->
                prefs.edit().putBoolean("volkey_on", v).apply()
                Diag.log("setting", "volkey_on=$v")
                // 改完设置立即重建当前页，保证显示与配置一致
                window.decorView.post { invalidateCurrentPage() }
                TapAgentService.instance?.onVolKeySwitchChanged()
                if (v && TapAgentService.instance == null) toast("需先开启「点按代理」服务，音量键才会生效")
            }
        }
        ll.addView(cardWrap(volSw))

        ll.addView(sectionTitle("Shizuku（可选）"))
        shizukuStateRow = clickRow("Shizuku 状态：" + shizukuText()) { show(3) } as TextView
        ll.addView(cardWrap(shizukuStateRow!!))
        ll.addView(cardWrap(clickRow("申请 Shizuku 授权（弹系统确认框）") { requestShizuku() }))
        ll.addView(cardWrap(clickRow("打开 Shizuku 应用") { openShizukuApp() }))
        ll.addView(cardWrap(clickRow("一键保活（守护服务 + 加锁服务）") {
            Keepalive.start(this)
            toast("已请求保活；正在修复掉线服务")
        }))

        ll.addView(sectionTitle("保活参数"))
        ll.addView(cardWrap(selectorRow("自动修复间隔", repairLabel(AppSettings.repairIntervalMs(this))) {
            pickOption(
                "自动修复间隔（服务存活期间计时，不写系统闹钟）",
                listOf("0" to "关闭", "1800000" to "每 30 分钟", "3600000" to "每 1 小时"),
                AppSettings.repairIntervalMs(this).toString()
            ) { v ->
                prefs.edit().putLong("repair_interval", v.toLong()).apply()
                show(3)
            }
        }))
        ll.addView(cardWrap(selectorRow("检测频率", if (AppSettings.fastGuard(this)) "快速（5 秒一轮）" else "标准（事件驱动）") {
            pickOption(
                "检测频率",
                listOf("0" to "标准（事件驱动，不轮询）", "1" to "快速（5 秒一轮）"),
                if (AppSettings.fastGuard(this)) "1" else "0"
            ) { v ->
                prefs.edit().putBoolean("fast_guard", v == "1").apply()
                show(3)
            }
        }))
        val stats = MaterialSwitch(this).apply {
            text = "守护统计通知（保活通知里显示成功/失败次数）"
            textSize = 14f
            setTextColor(Color.parseColor(TEXT))
            isChecked = AppSettings.statsNotify(this@MainActivity)
            isFocusable = true
            setOnCheckedChangeListener { _, v ->
                prefs.edit().putBoolean("stats_notify", v).apply()
                Keepalive.start(this@MainActivity)
            }
        }
        ll.addView(cardWrap(stats))
        ll.addView(cardWrap(clickRow("统计：成功 ${AppSettings.statOk(this)} / 失败 ${AppSettings.statFail(this)}") {
            prefs.edit().putInt("stat_ok", 0).putInt("stat_fail", 0).apply()
            toast("统计已清零")
            show(3)
        }))
        ll.addView(cardWrap(clickRow("权限体检") { checkup() }))
        ll.addView(cardWrap(clickRow("最近一次自愈：" + AppSettings.lastHealText(this)) { show(4) }))
        ll.addView(cardWrap(clickRow("紧急恢复：关闭守护服务并解除全部加锁") { emergency() }))

        ll.addView(sectionTitle("TalkBack 占位（实验）"))
        var tbBusy = false
        val tbSw = MaterialSwitch(this).apply {
            text = "TalkBack 占位（让第三方认为已开启）"
            textSize = 14f
            setTextColor(Color.parseColor(TEXT))
            isChecked = TalkBackPlace.isOn(this@MainActivity)
            isFocusable = true
            setOnCheckedChangeListener { vw, v ->
                if (tbBusy) return@setOnCheckedChangeListener
                if (v && !TalkBackPlace.canWrite(this@MainActivity)) {
                    tbBusy = true
                    vw.isChecked = false
                    tbBusy = false
                    Diag.log("tbplace", "ui reject no_write_route")
                    toast("需先完成「直写授权」或连接 Shizuku，才能写入系统设置")
                    return@setOnCheckedChangeListener
                }
                Thread {
                    val ok = TalkBackPlace.setEnabled(this@MainActivity, v, "ui")
                    runOnUiThread {
                        if (!ok) {
                            tbBusy = true
                            vw.isChecked = !v
                            tbBusy = false
                            toast(if (v) "开启失败：见诊断页 [tbplace]" else "撤销未完成：见诊断页 [tbplace]")
                        } else {
                            toast(if (v) "占位已开启（只影响检测，不朗读）" else "占位已关闭，痕迹已清理")
                        }
                    }
                }.start()
            }
        }
        ll.addView(cardWrap(tbSw))
        ll.addView(hint("只影响“看起来是否开启”：不朗读、不接管触摸、不消费按键；关闭开关会撤销全部写入。"))

        ll.addView(sectionTitle("防误触"))
        val sw = MaterialSwitch(this).apply {
            text = "切换前二次确认"
            textSize = 14f
            setTextColor(Color.parseColor(TEXT))
            isChecked = prefs.getBoolean("confirm_before_toggle", false)
            isFocusable = true
            setOnCheckedChangeListener { _, v ->
                prefs.edit().putBoolean("confirm_before_toggle", v).apply()
                Diag.log("setting", "confirm_before_toggle=$v")
                // 改完设置立即重建当前页，保证显示与配置一致
                window.decorView.post { invalidateCurrentPage() }
            }
        }
        ll.addView(cardWrap(sw))

        ll.addView(sectionTitle("入口"))
        ll.addView(cardWrap(clickRow("打开系统无障碍设置") { openA11ySettings() }))
        ll.addView(cardWrap(clickRow("忽略电池优化") { requestIgnoreBattery() }))
        ll.addView(cardWrap(clickRow("后台保活：" + keepaliveStatus()) { showKeepaliveGuide() }))

        // ---------- 内置更新 ----------
        ll.addView(sectionTitle("更新"))
        val updateSw = MaterialSwitch(this).apply {
            text = "检查更新"
            textSize = 14f
            setTextColor(Color.parseColor(TEXT))
            isChecked = AppSettings.updateCheckOn(this@MainActivity)
            isFocusable = true
            setOnCheckedChangeListener { _, v ->
                AppSettings.setUpdateCheckOn(this@MainActivity, v)
                Diag.log("setting", "update_on=$v")
                // 改完设置立即重建当前页，保证显示与配置一致
                window.decorView.post { invalidateCurrentPage() }
                if (v && AppSettings.updateSource(this@MainActivity).isEmpty()) {
                    toast("请先填写更新源（owner/repo 或 JSON 地址）")
                }
            }
        }
        ll.addView(cardWrap(updateSw))

        // ══════════ 语音控制 ══════════
        ll.addView(sectionTitle("语音控制"))
        ll.addView(hint(
            "把本应用设为系统「辅助应用」后，长按 Home 键（或原来的语音助手手势）"
                + "就能唤起语音页，说关键词即可切换服务。"
        ))

        ll.addView(cardWrap(clickRow("打开语音控制页") {
            runCatching {
                startActivity(android.content.Intent(this, VoiceActivity::class.java))
            }.onFailure { toast("无法打开语音页：" + (it.message ?: "")) }
        }))
        ll.addView(cardWrap(clickRow("编辑语音关键词") { editVoiceKeywords() }))

        // ── 助手界面触发 ──
        //
        // 国内环境下这是最实用的一条路：不依赖识别器能否联网，
        // 只要手机的语音助手本身能用，就能通过它的界面读关键词。
        val assistSw = MaterialSwitch(this).apply {
            text = "用系统语音助手触发"
            textSize = 14f
            setTextColor(Color.parseColor(TEXT))
            isChecked = AppSettings.assistantBridgeOn(this@MainActivity)
            isFocusable = true
            setOnCheckedChangeListener { _, v ->
                AppSettings.setAssistantBridgeOn(this@MainActivity, v)
                Diag.log("setting", "assistant_bridge=$v")
                window.decorView.post { invalidateCurrentPage() }
            }
        }
        ll.addView(cardWrap(assistSw))
        ll.addView(hint(
            "开启后，对手机自带的语音助手说关键词（如「关闭解说」），"
                + "本应用会读取助手界面上出现的文字并执行切换。"
        ))
        ll.addView(hint(
            "原理：Android 不允许第三方直接拿助手的识别结果，"
                + "但助手必须把结果显示在屏幕上 —— 本应用是无障碍服务，能读到那段文字。"
        ))
        if (AppSettings.assistantBridgeOn(this)) {
            ll.addView(cardWrap(clickRow("编辑要监听的助手包名") { editAssistantPkgs() }))
            ll.addView(hint(
                "只在这些包名的界面里匹配关键词。默认已包含小布、小爱、vivo、"
                    + "Google 助手等。如果你的助手不在列表里，把它的包名加进去。"
            ))
        }
        ll.addView(hint(
            "格式：每行一条「关键词|动作」。动作可以是组件名（切换）"
                + "或 ensure:组件名（确保开启）。识别不准时多写几个同义词。"
        ))

        ll.addView(cardWrap(selectorRow("更新源", updateSourceLabel()) {
            editUpdateSource()
        }))
        ll.addView(cardWrap(clickRow("立即检查更新") { UpdateUi.checkManual(this@MainActivity) }))
        ll.addView(hint(
            "更新源可填 GitHub 仓库（如 someone/a11yguard2）或一个 JSON 地址。"
                + "填仓库时会读取最新 Release 的说明和 APK。"
                + "检查更新是本应用唯一联网的功能，可以完全关闭；不上传任何信息。"
        ))

        ll.addView(sectionTitle("旧数据兼容"))
        ll.addView(cardWrap(clickRow("导入旧版数据") { showImportDialog() }))
        ll.addView(cardWrap(clickRow("导出旧版数据") { showExportDialog() }))
        return ScrollView(this).apply { addView(ll) }
    }

    private fun setMode(mode: String) {
        prefs.edit().putString("toggle_mode", mode).apply()
        // 选择 root 方式时立刻探测一次：用户既然选了，就说明愿意接受弹窗授权
        if (mode == RouteLogic.MODE_ROOT) {
            Thread {
                val s = ToggleRouter.probeRoot(this)
                runOnUiThread {
                    syncModeButtons()
                    refreshState()
                    if (s != RouteLogic.ROOT_AVAILABLE) {
                        toast(
                            when (s) {
                                RouteLogic.ROOT_NO_SU -> "未检测到 su：这台设备没有 root"
                                RouteLogic.ROOT_DENIED -> "su 被拒绝：请在 root 管理器里允许本应用"
                                else -> "root 不可用（$s）"
                            }
                        )
                    }
                }
            }.start()
            return
        }
        syncModeButtons()
        refreshState()
    }

    private fun syncModeButtons() {
        if (!::modeButtons.isInitialized || modeButtons.isEmpty()) return
        val cur = ToggleRouter.mode(this)
        val modes = listOf(
            RouteLogic.MODE_AUTO,
            RouteLogic.MODE_SECURE,
            RouteLogic.MODE_ROOT,
            RouteLogic.MODE_SHIZUKU,
            RouteLogic.MODE_TAP
        )
        modeButtons.forEachIndexed { i, b ->
            val sel = i < modes.size && modes[i] == cur
            b.setTextColor(Color.parseColor(if (sel) "#FFFFFF" else SUB))
            b.setBackgroundColor(Color.parseColor(if (sel) GREEN else CARD2))
        }
    }

    private fun rootLabel(): String = when (ToggleRouter.rootStatusCached()) {
        RouteLogic.ROOT_AVAILABLE -> "可用 ✓"
        RouteLogic.ROOT_NO_SU -> "不可用（未检测到 su）"
        RouteLogic.ROOT_DENIED -> "未授权（请在 root 管理器允许）"
        else -> "未检测 · 点击检测"
    }

    /** 更新源的一行显示。 */
    private fun updateSourceLabel(): String {
        val s = AppSettings.updateSource(this)
        if (s.isEmpty()) return "未设置 · 点击填写"
        val kind = if (s.count { it == '/' } == 1 && !s.startsWith("http")) "GitHub" else "JSON"
        val origin = if (AppSettings.hasCustomUpdateSource(this)) "自定义" else "内置默认"
        return "$s\n$kind · $origin"
    }

    /** 编辑更新源。允许恢复内置默认。 */
    private fun editUpdateSource() {
        val builtIn = AppSettings.DEFAULT_UPDATE_SOURCE
        val et = EditText(this).apply {
            setText(if (AppSettings.hasCustomUpdateSource(this@MainActivity))
                AppSettings.updateSource(this@MainActivity) else "")
            hint = if (builtIn.isEmpty())
                "someone/a11yguard2 或 https://example.com/update.json"
            else
                "留空则使用内置默认：$builtIn"
            setTextColor(Color.parseColor(TEXT))
            setHintTextColor(Color.parseColor(SUB))
            minLines = 2
        }
        val msg = buildString {
            append("GitHub 仓库写法：owner/repo\n")
            append("（会读取最新 Release 的说明与其中的 .apk）\n\n")
            append("自定义 JSON 写法：完整 https 地址，内容形如\n")
            append("{\"versionCode\":10800,\"versionName\":\"1.8.0\",")
            append("\"notes\":\"更新说明\",\"url\":\"APK 地址\"}\n")
            if (builtIn.isNotEmpty()) {
                append("\n内置默认：").append(builtIn)
                append("\n留空保存 = 恢复内置默认。")
            }
            append("\n\n二次开发的用户：把 AppSettings.DEFAULT_UPDATE_SOURCE 改成自己的仓库，")
            append("就能让自己的发行版自动更新到自己那里。")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("更新源")
            .setMessage(msg)
            .setView(et)
            .setPositiveButton("保存") { _, _ ->
                val v = et.text.toString().trim()
                if (v.isEmpty() && builtIn.isNotEmpty()) {
                    AppSettings.resetUpdateSource(this)
                    Diag.log("setting", "update_source -> 恢复内置默认")
                    // 改完设置立即重建当前页，保证显示与配置一致
                    window.decorView.post { invalidateCurrentPage() }
                    toast("已恢复内置默认更新源")
                } else {
                    AppSettings.setUpdateSource(this, v)
                    Diag.log("setting", "update_source=${v.take(60)}")
                    // 改完设置立即重建当前页，保证显示与配置一致
                    window.decorView.post { invalidateCurrentPage() }
                }
                refreshState()
            }
            .setNeutralButton("恢复内置") { _, _ ->
                AppSettings.resetUpdateSource(this)
                Diag.log("setting", "update_source -> 恢复内置默认")
                // 改完设置立即重建当前页，保证显示与配置一致
                window.decorView.post { invalidateCurrentPage() }
                toast("已恢复内置默认更新源")
                refreshState()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 点一下检测 root：会真的执行一次 su。 */
    private fun probeRootNow() {
        toast("正在检测 root…")
        Thread {
            val s = ToggleRouter.probeRoot(this)
            runOnUiThread {
                refreshState()
                toast(
                    when (s) {
                        RouteLogic.ROOT_AVAILABLE -> "root 可用 ✓ 可把执行方式切到「root」"
                        RouteLogic.ROOT_NO_SU -> "未检测到 su（设备未 root）"
                        RouteLogic.ROOT_DENIED -> "su 被拒绝，请在 root 管理器里允许"
                        else -> "root 状态未知（$s）"
                    }
                )
            }
        }.start()
    }

    private fun showImportDialog() {
        val et = EditText(this).apply {
            hint = "粘贴旧版 a11ytool.xml 内容（含 locked_comps / pinned_pkgs）"
            setTextColor(Color.parseColor(TEXT))
            setHintTextColor(Color.parseColor(SUB))
            minLines = 6
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("导入旧格式")
            .setView(et)
            .setPositiveButton("导入") { _, _ ->
                val data = LegacyCodec.parseXml(et.text.toString())
                prefs.edit()
                    .putString("locked_comps", LegacyCodec.join(data.locked))
                    .putString("pinned_pkgs", LegacyCodec.join(data.pinned))
                    .apply()
                toast("已导入：locked=${data.locked.size}，pinned=${data.pinned.size}")
                Diag.log("import", "locked=${data.locked.size} pinned=${data.pinned.size}")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showExportDialog() {
        val xml = LegacyCodec.buildXml(lockedList(), pinnedList())
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("a11ytool-prefs", xml))
        MaterialAlertDialogBuilder(this)
            .setTitle("已复制到剪贴板")
            .setMessage(xml)
            .setPositiveButton("好", null)
            .show()
    }

    private fun clickRow(text: String, action: () -> Unit): View =
        row(text).apply {
            isClickable = true
            isFocusable = true
            contentDescription = text
            setOnClickListener { action() }
        }

    // ---------- 诊断 ----------

    private fun pageDiag(): View {
        val ll = column()

        // ---- 传感器数据记录（用于把「甩动」和「拿起放下」的真实波形录下来）----
        ll.addView(sectionTitle("诊断日志"))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(pill("刷新") { diagBody.text = Diag.dump() })
        bar.addView(pill("复制") {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("diag", Diag.dump()))
            toast("诊断日志已复制")
        })
        bar.addView(pill("清空") { Diag.clear(); diagBody.text = "" })
        ll.addView(bar)
        diagBody = TextView(this).apply {
            text = Diag.dump()
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor(SUB))
            setPadding(0, dp(10), 0, dp(10))
        }
        ll.addView(diagBody)
        return ScrollView(this).apply { addView(ll) }
    }


    // ---------- 关于 ----------

    private fun pageAbout(): View {
        val ll = column()
        ll.addView(sectionTitle("无障碍守护"))
        ll.addView(TextView(this).apply {
            // 版本号从 BuildConfig 读，别再写死 —— 之前写死的 1.5.0 一直没跟着
            // versionName 更新，用户看关于页会以为装的是旧版。
            text = "包名：com.merin.a11yguard2\n" +
                "版本：${BuildConfig.VERSION_NAME}（versionCode ${BuildConfig.VERSION_CODE}）\n" +
                "执行层：自动（WRITE_SECURE_SETTINGS 直写 → Shizuku → 点按）\n" +
                "保活：前台服务 + 15 分钟自检 + 开机补启"
            textSize = 13f
            setTextColor(Color.parseColor(TEXT))
        })

        // ── 反馈渠道 ──
        // 放在最前面而不是页面底部：用户遇到问题时最先想找的就是"去哪反馈"。
        ll.addView(sectionTitle("反馈与交流"))
        ll.addView(cardWrap(clickRow("加入 QQ 群反馈：${FEEDBACK_QQ_GROUP}") {
            copyText(FEEDBACK_QQ_GROUP, "群号已复制，打开 QQ 粘贴搜索即可加入")
        }))
        ll.addView(hint(
            "遇到问题请先到「诊断」页复制日志再反馈，日志里有关键运行信息，" +
                "能大幅加快定位速度。"
        ))
        ll.addView(sectionTitle("技术事实"))
        listOf(
            "WRITE_SECURE_SETTINGS 是 signature|privileged 权限，普通安装拿不到；用一次 adb shell pm grant 授予后由系统按包保存，重启手机仍有效，卸载重装后才需重新授权。",
            "拥有直写权限后，开关服务不依赖 Shizuku 常驻，也不依赖本 App 的无障碍服务在运行；它直接读写 Settings.Secure.enabled_accessibility_services。",
            "Shizuku 在非 root 设备每次重启后需用 ADB/无线调试重新启动，无法做到“授权一次永久”，因此只作为可选执行层。",
            "本 App 自身必须被手动开启一次（自举限制）；但直写路径可在服务被系统移除后直接把开关写回去。"
        ).forEach {
            ll.addView(TextView(this).apply {
                text = "· $it"
                textSize = 12f
                setTextColor(Color.parseColor(SUB))
                setPadding(0, dp(2), 0, dp(6))
            })
        }
        return ScrollView(this).apply { addView(ll) }
    }

    // ---------- 高权限与选择器 ----------

    private fun secureText(): String =
        if (ToggleRouter.secureAvailable(this)) "已授权（重启手机仍有效）"
        else "未授权（需执行下方 adb 命令一次）"

    private fun checkSecurePermission(showDialog: Boolean) {
        val ok = ToggleRouter.secureAvailable(this)
        Diag.log("perm", "WRITE_SECURE_SETTINGS granted=$ok")
        refreshState()
        if (!showDialog) return
        MaterialAlertDialogBuilder(this)
            .setTitle(if (ok) "直写权限：已授权" else "直写权限：未授权")
            .setMessage(
                if (ok) {
                    "WRITE_SECURE_SETTINGS 已生效。开关服务会直接读写系统设置，不再依赖 Shizuku，" +
                        "重启手机后依然有效。卸载重装后才需重新执行授权命令。"
                } else {
                    "尚未检测到授权。请确认已执行：\n\n" + SecureSettingsProvider.GRANT_COMMAND +
                        "\n\n执行后回到本页点「我已执行，检测授权」。若仍失败，请检查 adb 是否连的是本机。"
                }
            )
            .setPositiveButton("好", null)
            .show()
    }

    private fun copyText(text: String, tip: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("a11yguard2", text))
        toast(tip)
    }

    private fun shizukuText(): String = when (ToggleRouter.shizukuStatus(this)) {
        RouteLogic.SHIZUKU_AVAILABLE -> "可用（已授权）"
        RouteLogic.SHIZUKU_NO_PERMISSION -> "未授权（可点下方申请）"
        RouteLogic.SHIZUKU_NOT_INSTALLED -> "未安装（本机没有 Shizuku 管理端）"
        RouteLogic.SHIZUKU_NOT_RUNNING -> "未运行（需先启动 Shizuku）"
        else -> "未安装 / 不支持"
    }

    private fun repairLabel(ms: Long): String = when {
        ms <= 0 -> "关闭"
        ms < 3_600_000L -> "每 ${ms / 60_000L} 分钟"
        else -> "每 1 小时"
    }

    private fun requestShizuku() {
        Diag.log("shizuku", "request status=" + ToggleRouter.shizukuStatus(this) +
            " ping=" + runCatching { Shizuku.pingBinder() }.getOrDefault(false))
        when (ToggleRouter.shizukuStatus(this)) {
            RouteLogic.SHIZUKU_NOT_INSTALLED -> {
                toast("本机没有 Shizuku 管理端：先安装并启动 Shizuku，再回来申请授权")
                return
            }
            RouteLogic.SHIZUKU_NOT_RUNNING -> {
                toast("Shizuku 未运行：先打开 Shizuku 应用点「启动」，再回来申请授权")
                return
            }
        }
        runCatching { Shizuku.requestPermission(7) }
            .onFailure { toast("申请授权失败：${it.message}") }
    }

    private fun openShizukuApp() {
        // 官方包名可能被改包名分支替代，按实际存在的管理端逐个尝试
        val pkgs = ShizukuProvider.managerPackages(this)
        if (pkgs.isEmpty()) {
            toast("未检测到 Shizuku 管理端（官方包与已知改包名分支都不存在）")
            return
        }
        pkgs.forEach { pkg ->
            val i = packageManager.getLaunchIntentForPackage(pkg)
            if (i != null) {
                runCatching { startActivity(i) }
                    .onFailure { toast("无法打开 $pkg：${it.message}") }
                return
            }
        }
        toast("已安装 Shizuku 管理端，但没有可启动的入口：" + pkgs.joinToString())
    }

    private fun checkup() {
        val notif = if (Build.VERSION.SDK_INT >= 33) {
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
        val pm = getSystemService(android.os.PowerManager::class.java)
        val battery = runCatching { pm.isIgnoringBatteryOptimizations(packageName) }.getOrDefault(false)
        val tap = SvcRepo.isEnabled(this, TapAgentService.COMP)
        val lines = listOf(
            "通知权限：" + if (notif) "已开启" else "未开启",
            "电池优化：" + if (battery) "已忽略" else "未忽略（建议忽略）",
            "直写权限：" + secureText(),
            "当前执行方式：" + ToggleRouter.describe(this),
            "点按代理：" + if (tap) "已开启" else "未开启（音量键/摇一摇需要它）",
            "Shizuku：" + shizukuText(),
            "背面敲击：" + (if (AppSettings.backTapOn(this)) "开" else "关") +
                "，${AppSettings.backTapCount(this)} 击，灵敏度 ${AppSettings.backTapSensitivity(this)}/10" +
                "，熄屏 " + (if (AppSettings.backTapScreenOff(this)) "继续" else "暂停") +
                "，目标：" + targetLabel(AppSettings.backTapTarget(this)),
            "音量键启动：" + (if (AppSettings.volKeyOn(this)) "开" else "关") +
                "，上/下目标：" + targetLabel(AppSettings.volUpTarget(this)) + " / " +
                targetLabel(AppSettings.volDownTarget(this))
        )
        Diag.log("checkup", lines.joinToString(" | "))
        MaterialAlertDialogBuilder(this)
            .setTitle("权限体检")
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("好", null)
            .show()
    }

    private fun emergency() {
        MaterialAlertDialogBuilder(this)
            .setTitle("紧急恢复")
            .setMessage("将关闭守护服务、清空加锁/置顶名单，并停止保活。不会卸载应用、不会删除诊断日志。确定继续？")
            .setPositiveButton("确定") { _, _ ->
                prefs.edit()
                    .putBoolean("desired_on", false)
                    .putString("locked_comps", "")
                    .putString("pinned_pkgs", "")
                    .apply()
                Thread {
                    val r = ToggleRouter.toggle(this, GuardA11yService.COMP, false)
                    runOnUiThread {
                        toast("已关闭：verify=${r.verify}")
                        refreshState()
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 警告卡片：橙色描边 + 标题 + 说明，用于"某功能当前不会生效"这类提示。 */
    private fun warnCard(title: String, detail: String): MaterialCardView =
        MaterialCardView(this).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(Color.parseColor("#2A1F1A"))
            strokeWidth = dp(1)
            strokeColor = Color.parseColor(ORANGE)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                addView(TextView(this@MainActivity).apply {
                    text = title
                    textSize = 15f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(Color.parseColor(ORANGE))
                })
                addView(TextView(this@MainActivity).apply {
                    text = detail
                    textSize = 13f
                    setTextColor(Color.parseColor(SUB))
                    setPadding(0, dp(4), 0, 0)
                })
            })
        }

    /** 三击目标的一行显示（未配置时明确提示「不响应三击」）。 */
    private fun tripleLabel(comp: String): String =
        if (comp.isEmpty()) "未设置（三击不响应）" else targetLabel(comp)

    private fun targetLabel(comp: String): String =
        if (comp.isEmpty()) "未设置"
        else AppSettings.aliasOf(this, comp, SvcRepo.labelOf(this, comp))

    /**
     * 改名对话框。
     *
     * 给无障碍服务起中文别名（比如「一号」「二号」），因为语音识别对英文服务名
     * 的准确率很低，改成短中文名之后说「开启一号」就能稳定命中。
     */
    /**
     * 编辑语音关键词。
     *
     * 用多行文本框而不是逐条列表：关键词可能很多（每个服务至少四条），
     * 逐条增删太繁琐；一次性编辑整块文本最快，也方便复制备份。
     */
    /** 编辑要监听的助手包名。 */
    private fun editAssistantPkgs() {
        val box = EditText(this).apply {
            setText(AppSettings.assistantPkgsRaw(this@MainActivity))
            hint = "com.heytap.speechassist"
            setSingleLine(false)
            minLines = 6
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setPadding(dp(16), dp(12), dp(16), dp(12))
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("要监听的助手包名")
            .setMessage(
                "每行一个包名。留空并保存 = 恢复默认列表。\n\n"
                    + "常用：\n"
                    + "com.heytap.speechassist（OPPO 小布）\n"
                    + "com.miui.voiceassist（小米小爱）\n"
                    + "com.vivo.abe（vivo）\n"
                    + "com.google.android.googlequicksearchbox（Google）"
            )
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                AppSettings.setAssistantPkgsRaw(this, box.text.toString())
                toast("已保存 ${AppSettings.assistantPkgs(this).size} 个包名")
            }
            .setNeutralButton("恢复默认") { _, _ ->
                AppSettings.setAssistantPkgsRaw(this, "")
                toast("已恢复默认")
            }
            .setNegativeButton("取消", null)
            .show()
    }

        private fun editVoiceKeywords() {
        val box = EditText(this).apply {
            setText(AppSettings.voiceKeywordsRaw(this@MainActivity))
            hint = "开启解说|ensure:com.pkg/com.pkg.Service"
            setSingleLine(false)
            minLines = 8
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setPadding(dp(16), dp(12), dp(16), dp(12))
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("语音关键词")
            .setMessage(
                "每行一条「关键词|动作」，例如：\n"
                    + "开启解说|ensure:com.nirenr.talkman/...TalkManAccessibilityService\n"
                    + "关闭解说|com.nirenr.talkman/...TalkManAccessibilityService\n\n"
                    + "留空并保存 = 恢复默认"
            )
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                AppSettings.setVoiceKeywordsRaw(this, box.text.toString())
                val n = AppSettings.voiceKeywords(this).size
                toast("已保存 $n 条关键词")
            }
            .setNeutralButton("恢复默认") { _, _ ->
                AppSettings.setVoiceKeywordsRaw(this, "")
                toast("已恢复默认关键词")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun editAlias(comp: String, fallback: String) {
        val cur = AppSettings.aliasMap(this)[SvcRepo.normalize(comp)].orEmpty()
        val box = EditText(this).apply {
            setText(cur)
            hint = "例如：一号、二号、读屏A"
            setSingleLine()
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("给「$fallback」起个名字")
            .setMessage("语音助手里说这个名字就能开关它。留空则恢复原名。")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                AppSettings.setAlias(this, comp, box.text.toString())
                // 必须重建当前页：服务列表和所有「目标服务」行的文字
                // 都是 buildPage() 时用 aliasOf 取的字符串，已固化在 View 里。
                // 只调 refreshState() 的话，那些行仍显示旧名。
                // invalidateCurrentPage 内部会保持滚动位置，不会跳回顶部。
                window.decorView.post { invalidateCurrentPage() }
                toast("已保存：${AppSettings.aliasOf(this, comp, fallback)}")
            }
            .setNeutralButton("恢复原名") { _, _ ->
                AppSettings.setAlias(this, comp, "")
                window.decorView.post { invalidateCurrentPage() }
                toast("已恢复原名")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun hint(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(Color.parseColor(SUB))
        setPadding(0, dp(4), 0, dp(8))
    }

    /**
     * 设置项一行：Material 卡片 + ripple 反馈。
     *
     * 相比原来的裸 LinearLayout：
     *   · ripple 水波反馈（触摸有明确视觉响应）
     *   · 16dp 圆角、Material 3 表面色
     *   · contentDescription 保持「标题，当前 值」，读屏逻辑不变
     */
    /**
     * 一行「标题 + 当前值」的可点卡片。
     *
     * @param value 初次显示的值
     * @param valueProvider 可选：刷新时重新取值的函数。传了就会登记到
     *        rowRefreshers，之后 pickOption 选完只更新这一行，不重建整页。
     */
    private fun selectorRow(
        title: String,
        value: String,
        valueProvider: (() -> String)? = null,
        onClick: () -> Unit
    ): MaterialCardView =
        MaterialCardView(this).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(Color.parseColor(CARD2))
            isClickable = true
            isFocusable = true
            rippleColor = ColorStateList.valueOf(Color.parseColor("#334CAF7D"))
            contentDescription = "$title，当前 $value"
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                addView(TextView(this@MainActivity).apply {
                    text = title
                    textSize = 15f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(Color.parseColor(TEXT))
                })
                val valueTv = TextView(this@MainActivity).apply {
                    text = value
                    textSize = 13f
                    setTextColor(Color.parseColor(SUB))
                    setPadding(0, dp(3), 0, 0)
                }
                addView(valueTv)
                // 登记原地刷新：只有调用方提供了取值函数才登记
                if (valueProvider != null) {
                    rowRefreshers[title] = {
                        val nv = valueProvider()
                        if (valueTv.text.toString() != nv) {
                            valueTv.text = nv
                            contentDescription = "$title，当前 $nv"
                        }
                    }
                }
            })
            setOnClickListener { onClick() }
        }

    /**
     * 单选对话框。
     *
     * 选完后统一重建当前页：所有"选择行"的显示值都来自 AppSettings，
     * 若不在选完后重建，界面会继续显示旧值（需要退出重进才刷新）。
     * 在这里统一处理，就不用给几十个调用点逐个加刷新了。
     */
    private fun pickOption(
        title: String,
        options: List<Pair<String, String>>,
        current: String,
        onPick: (String) -> Unit
    ) {
        val idx = options.indexOfFirst { it.first == current }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setSingleChoiceItems(options.map { it.second }.toTypedArray(), idx) { d, which ->
                onPick(options[which].first)
                d.dismiss()
                // 只刷新选择行的文字，不重建页面。
                // 早期这里调 invalidateCurrentPage() 整页重建，导致
                // 用户滑到下面点一下就被弹回顶部，得重新滑回来。
                window.decorView.post { refreshRowsInPlace() }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickTarget(title: String, current: String, onPick: (String) -> Unit) {
        val opts = mutableListOf("" to "未设置")
        SvcRepo.all(this).forEach { opts.add(it.comp to it.label) }
        pickOption(title, opts, current, onPick)
    }

    // ---------- 工具 ----------

    private fun column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(20), dp(16), dp(32))
    }

    private fun wrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /**
     * 分区标题。Material 3 的 label 样式：字号小、全大写感、字重中等、
     * 与下方内容留出较大间距（24dp），形成清晰的段落分组。
     */
    private fun sectionTitle(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.04f
        setTextColor(Color.parseColor(GREEN))
        setPadding(dp(4), dp(24), 0, dp(10))
    }

    private fun row(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 14f
        setTextColor(Color.parseColor(TEXT))
        setBackgroundColor(Color.parseColor(CARD))
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    /**
     * 卡片容器。
     *
     * ⚠️ 必须显式给 MATCH_PARENT 宽度：LinearLayout 默认按子视图内容宽度排布，
     * 而子视图（selectorRow 等）也是 WRAP_CONTENT，于是整张卡片只有文字那么宽，
     * 触摸区域也只覆盖左边一小条 —— 表现为「只有 1/3 屏幕能点」。
     * 早期版本正是这个 bug。
     */
    /**
     * 把一个视图包进 Material 卡片。
     *
     * 注意这里**不再**用 LinearLayout 中转：直接用 MaterialCardView 承载，
     * 圆角与表面色由卡片自己画，避免「卡片套卡片」出现双层背景。
     */
    private fun cardWrap(v: View): View {
        // 已经是卡片（selectorRow 等）就直接返回，不再包一层
        if (v is MaterialCardView) {
            v.layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            return v
        }
        return MaterialCardView(this).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(Color.parseColor(CARD2))
            addView(v, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun pill(t: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = t
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor(TEXT))
        setBackgroundColor(Color.parseColor(CARD2))
        setPadding(dp(10), dp(8), dp(10), dp(8))
        isClickable = true
        isFocusable = true
        contentDescription = t
        setOnClickListener { onClick() }
    }

    private fun openA11ySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure { toast("无法打开系统无障碍设置") }
    }

    private fun requestIgnoreBattery() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }.onFailure {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }.onFailure { toast("无法打开电池优化设置") }
        }
    }

    /** 保活判定：是否已忽略电池优化（ColorOS 后台冻结的主要开关）。 */
    private fun keepaliveStatus(): String {
        val ignoring = runCatching {
            (getSystemService(android.os.PowerManager::class.java))
                .isIgnoringBatteryOptimizations(packageName)
        }.getOrDefault(false)
        Diag.log("keepalive", "status ignoringBattery=$ignoring brand=${Build.BRAND}")
        return if (ignoring) "正常" else "受限"
    }

    /** 厂商后台限制引导：能跳自启动管理就跳，跳不过去退到应用详情页。 */
    private fun showKeepaliveGuide() {
        val brand = Build.BRAND.orEmpty().lowercase()
        val steps = when {
            brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") ->
                "OPPO/realme：设置 → 电池 → 应用耗电管理 → 本应用：允许自启动 / 允许后台运行；再在最近任务里锁定本应用。"
            brand.contains("xiaomi") || brand.contains("redmi") -> "MIUI：设置 → 应用设置 → 授权管理 → 自启动；省电策略选“无限制”。"
            brand.contains("vivo") || brand.contains("iqoo") -> "vivo：i 管家 → 应用管理 → 权限管理 → 自启动；后台耗电选“允许”。"
            else -> "请在系统设置里允许本应用自启动、后台运行，并加入电池优化白名单。"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("后台保活：" + keepaliveStatus())
            .setMessage(steps)
            .setPositiveButton("打开应用详情") { _, _ ->
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                }.onFailure { toast("无法打开应用详情") }
            }
            .setNeutralButton("电池优化设置") { _, _ -> requestIgnoreBattery() }
            .setNegativeButton("关闭", null)
            .show()
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
        SvcRepo.clearIconCache()
        super.onDestroy()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------- 左右滑切换 ----------

    private val gesture by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > dp(60) && abs(dx) > abs(dy) * 1.5f) {
                    show(if (dx < 0) (current + 1) % PAGE_COUNT else (current + PAGE_COUNT - 1) % PAGE_COUNT)
                    return true
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gesture.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }
}
