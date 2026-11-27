package com.merin.a11yguard2

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.widget.Toast
import android.app.AlertDialog
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 语音控制页 —— 用系统语音识别 + 自定义关键词切换无障碍服务。
 *
 * ══════════════════════════════════════════════════════════════════
 * 为什么不是「接管小布」
 * ══════════════════════════════════════════════════════════════════
 *
 * Android 的语音助手（小布 / Google Assistant）是系统级独占的，
 * 第三方应用**没有 API** 去拦截或注入它的语音指令 —— 那是安全边界。
 *
 * 所以做法是：**自己调系统语音识别**（SpeechRecognizer），
 * 识别完在本页匹配关键词。这样：
 *   · 不需要联网（系统识别器多数支持离线）
 *   · 关键词完全自定义，识别不好就加同义词
 *   · 说完立刻执行，不用手动点
 *
 * ══════════════════════════════════════════════════════════════════
 * 怎么让语音助手唤起本页
 * ══════════════════════════════════════════════════════════════════
 *
 * 本 App 注册为「辅助应用（Assist）」后：
 *   · 长按 Home 键 / 上滑导航条 → 弹出选择 → 选本 App
 *   · 或设为默认辅助应用后直接唤起
 *   · 唤起即开始听（onCreate 里自动 startListening）
 *
 * ══════════════════════════════════════════════════════════════════
 * 关键词设计
 * ══════════════════════════════════════════════════════════════════
 *
 * 每条关键词映射到一个「动作」，动作格式复用 Triggers.runAction：
 *   · com.pkg/com.pkg.Service   切换该服务
 *   · ensure:com.pkg/...        确保开启
 *   · master                    切换守护服务
 *
 * 关键词支持「包含匹配」：用户的说法是「开启解说」，
 * 只要识别结果里含「开启解说」就算命中，不需要一字不差。
 */
class VoiceActivity : Activity() {

    companion object {
        private const val REQ_AUDIO = 1001

        /** 内置默认关键词（首次打开时写入，用户可改）。 */
        val DEFAULT_KEYWORDS: List<Pair<String, String>> = listOf(
            "开启解说" to "ensure:com.nirenr.talkman/com.nirenr.talkman.TalkManAccessibilityService",
            "关闭解说" to "com.nirenr.talkman/com.nirenr.talkman.TalkManAccessibilityService",
            "开启守护" to "ensure:master",
            "关闭守护" to "master"
        )
    }

    private var recognizer: SpeechRecognizer? = null
    private lateinit var statusTv: TextView
    private lateinit var heardTv: TextView
    private lateinit var logTv: TextView
    private lateinit var main: LinearLayout

    private val logLines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diag.init(this)
        buildUi()

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
        } else {
            startListening()
        }
    }

    // ══════════ UI ══════════

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 轻提示。 */
    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun buildUi() {
        main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(0xFF0E1215.toInt())
        }

        statusTv = TextView(this).apply {
            text = "准备中…"
            textSize = 18f
            setTextColor(0xFF4CAF7D.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
        }
        main.addView(statusTv)

        heardTv = TextView(this).apply {
            text = "—"
            textSize = 22f
            setTextColor(0xFFE8EDF2.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(20))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        main.addView(heardTv)

        val retryBtn = Button(this).apply {
            text = "再说一次"
            textSize = 16f
            setOnClickListener { startListening() }
        }
        main.addView(retryBtn)

        // 识别器选择 —— 默认那个不能用时的唯一出路
        val pickBtn = Button(this).apply {
            text = "选择识别器"
            textSize = 16f
            setOnClickListener { pickRecognizer() }
        }
        main.addView(pickBtn)

        val closeBtn = Button(this).apply {
            text = "关闭"
            textSize = 16f
            setOnClickListener { finish() }
        }
        main.addView(closeBtn)

        logTv = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF8B9AA8.toInt())
            setPadding(0, dp(20), 0, 0)
        }
        main.addView(logTv)

        val hint = TextView(this).apply {
            text = "\n提示：本页可设为系统辅助应用 —— 长按 Home 键即可唤起。\n" +
                "关键词在设置页可自定义，识别不准时加同义词即可。"
            textSize = 12f
            setTextColor(0xFF5A6B7A.toInt())
        }
        main.addView(hint)

        setContentView(ScrollView(this).apply { addView(main) })
    }

    private fun addLog(s: String) {
        logLines.addLast(s)
        while (logLines.size > 12) logLines.removeFirst()
        logTv.text = logLines.joinToString("\n")
    }

    // ══════════ 语音识别 ══════════

    private fun startListening() {
        recognizer?.destroy()

        // 探测可用性。
        //
        // 注意：isRecognitionAvailable() 依赖包可见性（Android 11+）——
        // 若 AndroidManifest 的 <queries> 里没声明 android.speech.RecognitionService，
        // 它会返回 false，哪怕手机装了好几个识别服务。
        //
        // 而有些 ROM 的实现本身也有 bug，会误报 false。
        // 所以这里探测失败**不直接放弃**，仍然试一次 startListening ——
        // 真不可用的话 onError 会给出明确的错误码。
        val available = SpeechRecognizer.isRecognitionAvailable(this)
        addLog("isRecognitionAvailable = $available")
        diagnoseRecognizers()
        if (!available) {
            statusTv.text = "探测未通过，仍尝试识别…"
        }

        statusTv.text = "请说话…"
        heardTv.text = "—"

        // 优先用用户指定的识别器；没指定就用系统默认。
        val saved = AppSettings.voiceRecognizer(this)
        val r = if (saved.isNotEmpty() && saved.contains('/')) {
            val pkg = saved.substringBefore('/')
            val cls = saved.substringAfter('/')
            runCatching {
                SpeechRecognizer.createSpeechRecognizer(
                    this, android.content.ComponentName(pkg, cls)
                )
            }.getOrElse {
                addLog("指定的识别器不可用（$saved），回退系统默认")
                SpeechRecognizer.createSpeechRecognizer(this)
            }
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        recognizer = r

        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                statusTv.text = "听…"
            }

            override fun onBeginningOfSpeech() {
                statusTv.text = "听到了…"
            }

            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                statusTv.text = "识别中…"
            }

            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "没听清"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没检测到说话"
                    SpeechRecognizer.ERROR_NETWORK -> "网络不可用"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少麦克风权限"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙"
                    else -> "错误码 $error"
                }
                statusTv.text = "识别失败：$msg"
                addLog("onError $error ($msg)")
            }

            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull().orEmpty()
                heardTv.text = if (text.isEmpty()) "（空）" else text
                addLog("识别结果：$text")
                if (text.isNotEmpty()) handleText(text)
            }

            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        runCatching { r.startListening(intent) }
            .onFailure {
                statusTv.text = "无法启动识别：${it.message}"
                addLog("startListening 失败：${it.message}")
            }
    }

    /**
     * 选择语音识别器。
     *
     * 列出所有可见的 RecognitionService，用户选一个存起来，再重新开始听。
     * 「系统默认」这一项对应空字符串，便于退回原状。
     */
    private fun pickRecognizer() {
        val list = runCatching {
            packageManager.queryIntentServices(
                Intent("android.speech.RecognitionService"), 0
            )
        }.getOrDefault(emptyList())

        val labels = mutableListOf<String>()
        val values = mutableListOf<String>()
        val cur = AppSettings.voiceRecognizer(this)

        // 第一项：系统默认
        labels.add("系统默认" + if (cur.isEmpty()) "（当前）" else "")
        values.add("")

        list.forEach { r ->
            val pkg = r.serviceInfo.packageName
            val cls = r.serviceInfo.name
            val comp = "$pkg/$cls"
            val label = runCatching {
                r.loadLabel(packageManager).toString()
            }.getOrDefault(pkg)
            val mark = if (comp == cur) "（当前）" else ""
            labels.add("$label\n$pkg$mark")
            values.add(comp)
        }

        if (labels.size == 1) {
            toast("没有查到任何识别服务")
            return
        }

        val idx = values.indexOf(cur).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("选择语音识别器")
            .setSingleChoiceItems(labels.toTypedArray(), idx) { d, which ->
                AppSettings.setVoiceRecognizer(this, values[which])
                d.dismiss()
                val name = if (values[which].isEmpty()) "系统默认" else values[which]
                addLog("已选择识别器：$name")
                Toast.makeText(this, "已切换：$name", Toast.LENGTH_SHORT).show()
                window.decorView.post { startListening() }
            }
            .setNegativeButton("取消", null)
            .show()
    }

        /** 列出系统里能看到的语音识别服务（诊断用）。 */
    private fun diagnoseRecognizers() {
        runCatching {
            val i = Intent("android.speech.RecognitionService")
            val list = packageManager.queryIntentServices(i, 0)
            if (list.isEmpty()) {
                addLog("queryIntentServices: 0 个（可见性未声明或系统无识别器）")
            } else {
                list.take(5).forEach { r ->
                    addLog("识别服务：${r.serviceInfo.packageName}/${r.serviceInfo.name}")
                }
            }
            // 系统默认识别器
            val def = android.provider.Settings.Secure.getString(
                contentResolver, "voice_recognition_service"
            )
            addLog("默认识别器：${def ?: "（未设置）"}")
        }.onFailure {
            addLog("诊断失败：${it.message}")
        }
    }

    // ══════════ 关键词匹配 ══════════

    private fun handleText(raw: String) {
        val text = raw.replace(" ", "").lowercase()
        val kws = AppSettings.voiceKeywords(this)

        // 包含匹配：说法里含有关键词即命中。
        // 用"最长关键词优先"，避免「开启解说」被「解说」抢先匹配。
        val hit = kws
            .filter { it.first.isNotEmpty() && text.contains(it.first.replace(" ", "").lowercase()) }
            .maxByOrNull { it.first.length }

        if (hit == null) {
            statusTv.text = "未匹配关键词"
            addLog("没有命中任何关键词。已配置：${kws.joinToString { it.first }}")
            return
        }

        val keyword = hit.first
        val action = hit.second
        Diag.log("voice", "matched \"$keyword\" -> $action")
        addLog("命中「$keyword」→ $action")

        statusTv.text = "执行：$keyword"
        Triggers.runAction(this, action, "voice")

        // 执行完给个反馈再关
        statusTv.postDelayed({
            statusTv.text = "已执行：$keyword"
        }, 300)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startListening()
            } else {
                statusTv.text = "需要麦克风权限"
                addLog("用户拒绝录音权限，语音控制不可用")
            }
        }
    }

    override fun onDestroy() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        super.onDestroy()
    }
}
