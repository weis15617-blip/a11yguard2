package com.merin.a11yguard2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 唯一的圆形主按钮：
 * - 单击 = 切换开/关；长按 = 弹出加锁/置顶菜单
 * - 读屏等价入口：可访问性动作“更多操作” → 同一个菜单
 * - 视觉：开 = 实心（含对勾），关 = 空心描边（含电源弧线），不只靠颜色区分
 */
class BigToggleView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : View(ctx, attrs) {

    /** 用于主按钮或服务行：服务名会进入读屏描述。 */
    var serviceName: String = "无障碍守护"
        set(value) {
            field = value
            refreshA11y()
        }

    /** 图标直径（dp）：主按钮 96，服务行 64。 */
    var iconSizeDp: Float = 96f
        set(value) {
            field = value
            requestLayout()
        }

    var on: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            refreshA11y()
            invalidate()
        }

    /** 已加锁：外圈改双线，读屏desc追加“已加锁”。 */
    var locked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            refreshA11y()
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = Color.parseColor("#8A949E")
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(12f)
    }

    init {
        isClickable = true
        isLongClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        refreshA11y()
    }

    fun desc(): String = (if (on) "$serviceName 已开启" else "$serviceName 已关闭") +
        (if (locked) "，已加锁" else "")

    private fun refreshA11y() {
        contentDescription = desc()
        if (isAttachedToWindow) announceForAccessibility(desc())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(iconSizeDp)
        setMeasuredDimension(size.toInt(), size.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) - dp(3f)
        val green = Color.parseColor("#2E7D32")
        val offGray = Color.parseColor("#8A949E")

        if (on) {
            fill.color = green
            fill.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, r, fill)
            glyph.color = Color.WHITE
            drawCheck(canvas, cx, cy, r)
            label.color = Color.WHITE
        } else {
            canvas.drawCircle(cx, cy, r, ring)
            glyph.color = offGray
            drawPower(canvas, cx, cy, r)
            label.color = offGray
        }
        val textY = cy + r * 0.62f
        canvas.drawText(if (on) "已开启" else "已关闭", cx, textY, label)
        if (locked) {
            ring.strokeWidth = dp(2f)
            ring.color = if (on) Color.WHITE else offGray
            canvas.drawCircle(cx, cy, r - dp(9f), ring)
        }
    }

    private fun drawCheck(c: Canvas, cx: Float, cy: Float, r: Float) {
        val p = Path()
        p.moveTo(cx - r * 0.38f, cy - r * 0.04f)
        p.lineTo(cx - r * 0.10f, cy + r * 0.26f)
        p.lineTo(cx + r * 0.42f, cy - r * 0.30f)
        c.drawPath(p, glyph)
    }

    private fun drawPower(c: Canvas, cx: Float, cy: Float, r: Float) {
        val arcR = r * 0.42f
        val rect = RectF(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
        c.drawArc(rect, -50f, 280f, false, glyph)
        c.drawLine(cx, cy - arcR * 1.35f, cx, cy - arcR * 0.15f, glyph)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
        info.text = desc()
        info.contentDescription = desc()
        info.isClickable = true
        info.isLongClickable = true
        info.isFocusable = true
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_more, "更多操作"))
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == R.id.action_more) {
            performLongClick()
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    // 显式触摸处理：长按判定不依赖 View 默认实现，按住超过系统阈值即弹菜单；
    // 只有可点击的主按钮才消费事件，行内小图标保持透明，交给父容器行处理。
    private var downX = 0f
    private var downY = 0f
    private var longFired = false
    private var longPressTask: Runnable? = null
    private val touchSlop by lazy { android.view.ViewConfiguration.get(context).scaledTouchSlop }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isClickable) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                longFired = false
                isPressed = true
                val task = Runnable {
                    longFired = true
                    isPressed = false
                    performLongClick()
                }
                longPressTask = task
                postDelayed(task, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.abs(event.x - downX) > touchSlop ||
                    kotlin.math.abs(event.y - downY) > touchSlop
                ) {
                    cancelLongPressTask()
                    isPressed = false
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                cancelLongPressTask()
                isPressed = false
                if (!longFired) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelLongPressTask()
                isPressed = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun cancelLongPressTask() {
        longPressTask?.let { removeCallbacks(it) }
        longPressTask = null
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
