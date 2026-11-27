package com.merin.a11yguard2

import android.app.Activity
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

class MainActivity : Activity() {

    companion object {
        private const val PAGE_COUNT = 6
        private const val BG = "#101418"
        private const val CARD = "#171C22"
        private const val CARD2 = "#1E252D"
        private const val TEXT = "#F2F4F6"
        private const val SUB = "#A9B4BE"
        private const val GREEN = "#2E7D32"
        private const val ORANGE = "#EF6C00"
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
            ).apply { bottomMargin = dp(60) }
        )

        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#171C22"))
        }
        root.addView(
            nav,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60),
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

    override fun onResume() {
        super.onResume()
        if (::summary.isInitialized) refreshState()
        if (current == 2) {
            BackTapRuntime.acquire(this, BackTapRuntime.OWNER_TEST)
            bindBackTapTest()
        }
    }

    override fun onPause() {
        BackTapRuntime.testListener = null
        BackTapRuntime.release(this, BackTapRuntime.OWNER_TEST)
        super.onPause()
    }

    // ---------- 导航 ----------

    private fun buildNav() {
        titles.forEachIndexed { i, t ->
            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = "底部导航-$t"
                setOnClickListener { show(i) }
            }
            val ic = ImageView(this)
            ic.setImageResource(icons[i])
            ic.setColorFilter(Color.parseColor(SUB))
            tab.addView(ic, LinearLayout.LayoutParams(dp(20), dp(20)))
            val tv = TextView(this).apply {
                text = t
                textSize = 11f
                setTextColor(Color.parseColor(SUB))
                gravity = Gravity.CENTER
            }
            tab.addView(tv)
            nav.addView(tab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            tabs.add(tab)
        }
    }

    private fun show(i: Int) {
        current = i
        val page = pages[i] ?: buildPage(i).also { pages[i] = it }
        content.removeAllViews()
        content.addView(page)
        if (i == 2) {
            BackTapRuntime.acquire(this, BackTapRuntime.OWNER_TEST)
            bindBackTapTest()
        } else {
            BackTapRuntime.testListener = null
            BackTapRuntime.release(this, BackTapRuntime.OWNER_TEST)
        }
        tabs.forEachIndexed { idx, tab ->
            val tv = tab.getChildAt(1) as TextView
            tv.setTextColor(Color.parseColor(if (idx == i) TEXT else SUB))
            if (idx == i) tab.setBackgroundColor(Color.parseColor("#232B33")) else tab.setBackgroundColor(Color.TRANSPARENT)
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
            AlertDialog.Builder(this)
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
            AlertDialog.Builder(this)
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

        val onlyUser = Switch(this).apply {
            text = "仅显示用户安装的应用"
            textSize = 14f
            setTextColor(Color.parseColor(TEXT))
            isChecked = AppSettings.onlyUserApps(this@MainActivity)
            isFocusable = true
            setOnCheckedChangeListener { _, v ->
                prefs.edit().putBoolean("only_user_apps", v).apply()
                Diag.log("setting", "only_user_apps=$v")
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

        val volSw = Switch(this).apply {
            text = "音量键触发"
            textSize = 14f
            setTextColor(Color.parseColor(TEXT))
            isChecked = AppSettings.volKeyOn(this@MainActivity)
            isFocusable = true
            setOnCheckedChangeListener { _, v ->
                prefs.edit().putBoolean("volkey_on", v).apply()
                Diag.log("setting", "volkey_on=$v")
                // 关闭时立刻清掉残留的按键计数与已排队的确认任务，
                // 否则「双击一下 → 马上关开关」时，那条 220ms 后执行的
                // 待触发任务仍会跑，表现为关掉后还切了一次服务。
                TapAgentService.instance?.onVolKeySwitchChanged()
                if (v && TapAgentService.instance == null) toast("需先开启「点按代理」服务，音量键才会生效")
            }
        }
        ll.addView(cardWrap(volSw))

        ll.addView(cardWrap(selectorRow("双击上键", targetLabel(AppSettings.volUpTarget(this))) {
            pickTarget("选择双击上键的目标服务", AppSettings.volUpTarget(this)) { c ->
                prefs.edit().putString("volup_target", c).apply()
                show(2)
            }
        }))
        ll.addView(cardWrap(selectorRow("双击下键", targetLabel(AppSettings.volDownTarget(this))) {
            pickTarget("选择双击下键的目标服务", AppSettings.volDownTarget(this)) { c ->
                prefs.edit().putString("voldown_target", c).apply()
                show(2)
            }
        }))
        ll.addView(cardWrap(selectorRow("连击窗口", "${AppSettings.volWindowMs(this)} ms") {
            pickOption(
                "音量键连击间隔（超出即从 1 重计）",
                listOf("200" to "200ms", "300" to "300ms", "400" to "400ms",
                    "500" to "500ms（标准）", "600" to "600ms"),
                AppSettings.volWindowMs(this).toString()
            ) { v ->
                prefs.edit().putInt("trigger_window_ms", v.toInt()).apply()
                show(2)
            }
        }))
        ll.addView(cardWrap(selectorRow("通用震动", vibLabel(AppSettings.genVibMs(this))) {
            pickOption(
                "通用震动时长",
                listOf("60" to "轻（60ms）", "110" to "中（110ms）",
                    "200" to "标准（200ms）", "250" to "强（250ms）"),
                AppSettings.genVibMs(this).toString()
            ) { v ->
                prefs.edit().putInt("gen_vib", v.toInt()).apply()
                show(2)
            }
        }))

        ll.addView(sectionTitle("背面敲击"))
        val backTapSw = Switch(this).apply {
            text = "背面敲击（双击 / 三击）"
            textSize = 14f
            setTextColor(Color.parseColor(TEXT))
            isChecked = AppSettings.backTapOn(this@MainActivity)
            isFocusable = true
            setOnCheckedChangeListener { _, v ->
                prefs.edit().putBoolean("backtap_on", v).apply()
                Diag.log("setting", "backtap_on=$v")
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
            "目标服务",
            targetLabel(AppSettings.backTapTarget(this))
        ) {
            pickTarget("选择背面敲击要切换的服务", AppSettings.backTapTarget(this)) { c ->
                prefs.edit().putString("backtap_target", c).apply()
                renderBackTapTest(BackTapRuntime.lastEvent)
                show(2)
            }
        }))

        val screenOffSw = Switch(this).apply {
            text = "熄屏后继续检测（耗电增加）"
            textSize = 14f
            setTextColor(Color.parseColor(TEXT))
            isChecked = AppSettings.backTapScreenOff(this@MainActivity)
            isFocusable = true
            setOnCheckedChangeListener { _, v ->
                prefs.edit().putBoolean("backtap_screen_off", v).apply()
                Diag.log("setting", "backtap_screen_off=$v")
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
        ll.addView(hint("默认无动作，服务保持运行（锁屏听书不断）。"))
        return ScrollView(this).apply { addView(ll) }
    }


    // ---------- 背面敲击 ----------

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
                ev.accepted -> "计入 ${ev.count}/${ev.need}"
                else -> "未计入（${ev.reason}）"
            }
            sb.append("\n最近一次：峰值 %.2f m/s² · %s".format(ev.peak, verdict))
            sb.append("\n置信度：%.2f".format(ev.confidence))
        }
        tv.text = sb.toString()
    }

    private fun backTapSensitivityLabel(): String {
        val lv = AppSettings.backTapSensitivity(this)
        val p = BackTapLogic.paramsForSensitivity(lv)
        return "$lv / 10 · 阈值 %.1f · 间隔 %dms".format(p.ampThreshold, p.maxGapMs)
    }

    private fun backTapLevelText(level: Int): String {
        val p = BackTapLogic.paramsForSensitivity(level)
        return "$level / 10 · 振幅阈值 %.1f m/s² · 间隔窗口 %dms · 置信度 %.2f"
            .format(p.ampThreshold, p.maxGapMs, p.minConfidence)
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
        AlertDialog.Builder(this)
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

    private fun vibLabel(ms: Int): String = when {
        ms <= 0 -> "关"
        ms <= 60 -> "轻（${ms}ms）"
        ms <= 110 -> "中（${ms}ms）"
        ms <= 160 -> "标准（${ms}ms）"
        else -> "强（${ms}ms）"
    }

    private fun actionLabel(a: String): String = when {
        a.isEmpty() || a == AppSettings.ACT_NONE -> "无动作"
        a == AppSettings.ACT_MASTER -> "打开守护服务（总开关）"
        a.startsWith(AppSettings.ACT_PREFIX) ->
            "切换：" + targetLabel(a.removePrefix(AppSettings.ACT_PREFIX))
        else -> a
    }

    private fun pickAction(title: String, current: String, onPick: (String) -> Unit) {
        val opts = mutableListOf(
            AppSettings.ACT_NONE to "无动作",
            AppSettings.ACT_MASTER to "打开守护服务（总开关）",
            // 语音助手：触发后说一句话来开关服务，说完自动释放麦克风。
            // 适合读屏已经关掉、不方便摸屏幕的情况。
        )
        SvcRepo.all(this).forEach { opts.add(AppSettings.ACT_PREFIX + it.comp to it.label) }
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
        val volSw = Switch(this).apply {
            text = "音量键启动（双击音量上/下键）"
            textSize = 14f
            setTextColor(Color.parseColor(TEXT))
            isChecked = AppSettings.volKeyOn(this@MainActivity)
            isFocusable = true
            setOnCheckedChangeListener { _, v ->
                prefs.edit().putBoolean("volkey_on", v).apply()
                Diag.log("setting", "volkey_on=$v")
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
        val stats = Switch(this).apply {
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
        val tbSw = Switch(this).apply {
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
        val sw = Switch(this).apply {
            text = "切换前二次确认"
            textSize = 14f
            setTextColor(Color.parseColor(TEXT))
            isChecked = prefs.getBoolean("confirm_before_toggle", false)
            isFocusable = true
            setOnCheckedChangeListener { _, v ->
                prefs.edit().putBoolean("confirm_before_toggle", v).apply()
                Diag.log("setting", "confirm_before_toggle=$v")
            }
        }
        ll.addView(cardWrap(sw))

        ll.addView(sectionTitle("入口"))
        ll.addView(cardWrap(clickRow("打开系统无障碍设置") { openA11ySettings() }))
        ll.addView(cardWrap(clickRow("忽略电池优化") { requestIgnoreBattery() }))
        ll.addView(cardWrap(clickRow("后台保活：" + keepaliveStatus()) { showKeepaliveGuide() }))

        // ---------- 内置更新 ----------
        ll.addView(sectionTitle("更新"))
        val updateSw = Switch(this).apply {
            text = "检查更新"
            textSize = 14f
            setTextColor(Color.parseColor(TEXT))
            isChecked = AppSettings.updateCheckOn(this@MainActivity)
            isFocusable = true
            setOnCheckedChangeListener { _, v ->
                AppSettings.setUpdateCheckOn(this@MainActivity, v)
                Diag.log("setting", "update_on=$v")
                if (v && AppSettings.updateSource(this@MainActivity).isEmpty()) {
                    toast("请先填写更新源（owner/repo 或 JSON 地址）")
                }
            }
        }
        ll.addView(cardWrap(updateSw))

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
        AlertDialog.Builder(this)
            .setTitle("更新源")
            .setMessage(msg)
            .setView(et)
            .setPositiveButton("保存") { _, _ ->
                val v = et.text.toString().trim()
                if (v.isEmpty() && builtIn.isNotEmpty()) {
                    AppSettings.resetUpdateSource(this)
                    Diag.log("setting", "update_source -> 恢复内置默认")
                    toast("已恢复内置默认更新源")
                } else {
                    AppSettings.setUpdateSource(this, v)
                    Diag.log("setting", "update_source=${v.take(60)}")
                }
                refreshState()
            }
            .setNeutralButton("恢复内置") { _, _ ->
                AppSettings.resetUpdateSource(this)
                Diag.log("setting", "update_source -> 恢复内置默认")
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
        AlertDialog.Builder(this)
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
        AlertDialog.Builder(this)
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
            text = "包名：com.merin.a11yguard2\n版本：1.5.0（versionCode 6）\n" +
                "执行层：自动（WRITE_SECURE_SETTINGS 直写 → Shizuku → 点按）\n" +
                "保活：前台服务 + 15 分钟自检 + 开机补启"
            textSize = 13f
            setTextColor(Color.parseColor(TEXT))
        })
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
        AlertDialog.Builder(this)
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
        AlertDialog.Builder(this)
            .setTitle("权限体检")
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("好", null)
            .show()
    }

    private fun emergency() {
        AlertDialog.Builder(this)
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

    private fun targetLabel(comp: String): String =
        if (comp.isEmpty()) "未设置"
        else AppSettings.aliasOf(this, comp, SvcRepo.labelOf(this, comp))

    /**
     * 改名对话框。
     *
     * 给无障碍服务起中文别名（比如「一号」「二号」），因为语音识别对英文服务名
     * 的准确率很低，改成短中文名之后说「开启一号」就能稳定命中。
     */
    private fun editAlias(comp: String, fallback: String) {
        val cur = AppSettings.aliasMap(this)[SvcRepo.normalize(comp)].orEmpty()
        val box = EditText(this).apply {
            setText(cur)
            hint = "例如：一号、二号、读屏A"
            setSingleLine()
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("给「$fallback」起个名字")
            .setMessage("语音助手里说这个名字就能开关它。留空则恢复原名。")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                AppSettings.setAlias(this, comp, box.text.toString())
                refreshState()
                toast("已保存")
            }
            .setNeutralButton("恢复原名") { _, _ ->
                AppSettings.setAlias(this, comp, "")
                refreshState()
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

    private fun selectorRow(title: String, value: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(CARD))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            contentDescription = "$title，当前 $value"
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(TEXT))
            })
            addView(TextView(this@MainActivity).apply {
                text = value
                textSize = 12f
                setTextColor(Color.parseColor(SUB))
            })
            setOnClickListener { onClick() }
        }

    private fun pickOption(
        title: String,
        options: List<Pair<String, String>>,
        current: String,
        onPick: (String) -> Unit
    ) {
        val idx = options.indexOfFirst { it.first == current }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(options.map { it.second }.toTypedArray(), idx) { d, which ->
                onPick(options[which].first)
                d.dismiss()
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
        setPadding(dp(16), dp(16), dp(16), dp(24))
    }

    private fun wrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun sectionTitle(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(Color.parseColor(SUB))
        setPadding(0, dp(16), 0, dp(6))
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
    private fun cardWrap(v: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(v, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        setPadding(0, dp(3), 0, dp(3))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
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
        AlertDialog.Builder(this)
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
