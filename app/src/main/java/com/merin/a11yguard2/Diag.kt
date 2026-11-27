package com.merin.a11yguard2

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 诊断日志：内存环形缓冲 + 落盘，字段与验收要求一致。 */
object Diag {
    private const val MAX = 300
    private const val FILE = "diag.log"

    /** 攒够这么多行就写一次盘 */
    private const val FLUSH_EVERY = 16

    /** 或者距上次写盘超过这么久也写一次（保证日志不会长期滞留内存） */
    private const val FLUSH_MS = 2_000L

    /** 文件超过这个大小就轮转，只留最后 MAX_KEEP_LINES 行 */
    private const val MAX_BYTES = 512 * 1024L
    private const val MAX_KEEP_LINES = 2_000

    /** 轮转时最多回溯多少个 64KB 块（防止异常大的文件扫描过久） */
    private const val MAX_SCAN_CHUNKS = 64

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val buf = ArrayDeque<String>()

    @Volatile
    private var app: Context? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
        // 在这里读设置，而不是依赖调用方先赋值：
        // 多个入口（前台服务 / 无障碍服务 / App）都会 init，漏掉一处就会绕过过滤。
        runCatching { onlyAnomalies = AppSettings.logOnlyAnomalies(ctx) }
        if (buf.isEmpty()) {
            runCatching {
                file(ctx).readLines().takeLast(MAX).forEach { buf.addLast(it) }
            }
        }
    }

    /**
     * 只记录异常模式。
     *
     * 打开后，只有「可能代表出问题」的标签才落盘，日常平稳运行的流水账（加载图标、
     * 每次自检成功、界面刷新等）不再写入。这样日志只在真出问题时才有内容，
     * 收集排障时一眼就能看到异常，不会被上万行正常记录淹没。
     */
    var onlyAnomalies: Boolean = false

    /** 异常模式下保留的标签：补回失败、路由不可用、服务销毁、解析异常等 */
    private val ALWAYS_KEEP = setOf(
        "heal", "lock", "route", "shizuku", "keep", "bootrepair",
        "guard", "tapagent", "alarm", "keepalive", "app", "boot"
    )

    /** 明确丢弃的噪音标签：纯装饰性、成功流水账 */
    private val NEVER_KEEP = setOf("icon", "svcrepo", "home")

    @Synchronized
    fun log(tag: String, msg: String) {
        if (onlyAnomalies && !shouldKeep(tag, msg)) return
        val line = "${fmt.format(Date())} [$tag] $msg"
        buf.addLast(line)
        while (buf.size > MAX) buf.removeFirst()
        if (app == null) return
        // 攒一批再落盘：原来每条日志都 appendText 一次（打开/写入/关闭文件），
        // 摇一摇这类每秒十几条的来源会把主线程拖在 IO 上。
        // 现在攒到 FLUSH_EVERY 条或超过 FLUSH_MS 才真正写一次。
        pending.add(line)
        val now = SystemClock.elapsedRealtime()
        if (pending.size >= FLUSH_EVERY || now - lastFlushAt >= FLUSH_MS) flushLocked()
    }

    /**
     * 异常模式下的过滤判断。
     *
     * 判据只看这条日志「是否代表出问题」，不看它来自哪个标签：
     *   - 含失败/异常字样 → 保留
     *   - 含 ok=false / verify=false / repair=false → 保留
     *   - 纯成功流水账（verify=true / ok=true / repair=true / 无需补回）→ 丢弃
     *   - 装饰性噪音（icon 等）→ 丢弃
     *   - 其余（启动、闹钟排期、状态变化这类结构性事件）→ 保留少量
     */
    private fun shouldKeep(tag: String, msg: String): Boolean {
        // 1) 明确的失败信号：一定保留
        val bad = msg.contains("fail", true) || msg.contains("失败") ||
            msg.contains("error", true) || msg.contains("异常") ||
            msg.contains("verify=false") || msg.contains("ok=false") ||
            msg.contains("repair=false") || msg.contains("不可用") ||
            msg.contains("超时") || msg.contains("拒绝")
        if (bad) return true

        // 2) 装饰性噪音：直接丢
        if (tag in NEVER_KEEP) return false

        // 3) 纯成功流水账：丢
        val plainSuccess = msg.contains("verify=true") || msg.contains("ok=true") ||
            msg.contains("repair=true") || msg.contains("无需补回") ||
            msg.contains("skip throttled") || msg.contains("already-bound")
        if (plainSuccess) return false

        // 4) 高频结构性重复：同类只留少量（自检排期、启动命令、settings 变化）
        if (tag == "alarm" || (tag == "fgs" && msg.contains("onStartCommand")) ||
            (tag == "fgs" && msg.contains("settings 变化"))
        ) return false

        // 5) 其余保留（真正的状态变化：开机、服务连接/断开、路由不可用等）
        return true
    }

    private val pending = ArrayList<String>(64)
    private var lastFlushAt = 0L

    /** 把攒下的行一次性追加到文件，并做大小轮转 */
    private fun flushLocked() {
        if (pending.isEmpty()) return
        val ctx = app ?: return
        val batch = pending.joinToString("\n")
        pending.clear()
        lastFlushAt = SystemClock.elapsedRealtime()
        runCatching {
            val f = file(ctx)
            // 文件超过上限就轮转：只保留最后 MAX_KEEP_LINES 行。
            // 用流式读取而不是 readLines()，避免把一个很大的日志整个读进内存
            // （曾经累积到 12 万行，readLines 会一次性吃掉好几 MB）。
            if (f.length() > MAX_BYTES) rotateLocked(f)
            f.appendText(batch + "\n")
        }
    }

    /**
     * 轮转：尾巴留 MAX_KEEP_LINES 行，其余丢弃。
     *
     * 做法是从文件末尾往前按块读，直到凑够需要的行数或到达块数上限。
     * 这样无论文件多大，内存占用都只是一个块 + 保留的行。
     */
    private fun rotateLocked(f: File) {
        val keep = ArrayList<String>(MAX_KEEP_LINES)
        runCatching {
            // 从文件尾部往前按块读，凑够 MAX_KEEP_LINES 行就停。
            // 关键：必须按「行」边界拼装，不能直接按字节块拼字符串 ——
            // UTF-8 中文字符可能跨块，硬按字节切会产生乱码和错行（实测会拼出半截汉字）。
            val chunks = ArrayList<ByteArray>()
            var bytes = 0
            java.io.RandomAccessFile(f, "r").use { raf ->
                val len = raf.length()
                val chunk = 64 * 1024L
                var pos = len
                var newlines = 0
                // 先收集足够的字节：直到换行数够 MAX_KEEP_LINES，或到头，或达到回溯上限
                while (pos > 0 && newlines < MAX_KEEP_LINES && chunks.size < MAX_SCAN_CHUNKS) {
                    val start = maxOf(0L, pos - chunk)
                    val size = (pos - start).toInt()
                    val buf = ByteArray(size)
                    raf.seek(start)
                    raf.readFully(buf)
                    chunks.add(0, buf)
                    for (b in buf) if (b == '\n'.code.toByte()) newlines++
                    bytes += size
                    pos = start
                }
            }
            // 按顺序拼成完整字节数组后一次性解码，这样字符不会被截断
            val all = ByteArray(bytes)
            var off = 0
            for (c in chunks) {
                System.arraycopy(c, 0, all, off, c.size)
                off += c.size
            }
            val text = String(all, Charsets.UTF_8)
            val lines = text.split('\n')
            val tail = lines.filter { it.isNotBlank() }.takeLast(MAX_KEEP_LINES)
            keep.addAll(tail)
        }
        runCatching { f.writeText(keep.joinToString("\n") + "\n") }
        DiagInternal.rotations++
    }

    /** 轮转次数（诊断面板可显示，确认机制在工作） */
    object DiagInternal {
        @Volatile
        var rotations: Int = 0
    }

    /** 立刻把缓冲写盘（清空/导出前调用） */
    @Synchronized
    fun flush() {
        flushLocked()
    }

    @Synchronized
    fun dump(): String {
        flushLocked()
        return buf.joinToString("\n")
    }

    @Synchronized
    fun clear() {
        pending.clear()
        buf.clear()
        app?.let { runCatching { file(it).writeText("") } }
    }

    fun file(ctx: Context): File = File(ctx.filesDir, FILE)
}
