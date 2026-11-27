package com.merin.a11yguard2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator

/**
 * 服务条目的唯一可点控件：圆形按钮 + 圆内应用图标 + 外圈状态。
 * 已开启：3dp 强调色实线 + 15% 同色光晕；已关闭：2dp 中灰。
 */
class ServiceToggleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var serviceName: String = ""
    var on: Boolean = false
        set(v) { field = v; refreshA11y(); invalidate() }
    var appIcon: Drawable? = null
        set(v) { field = v; invalidate() }
    var circleDp: Float = 96f
    /** 已加锁：外圈改双线，读屏desc追加“已加锁”。 */
    var locked: Boolean = false
        set(v) { field = v; refreshA11y(); invalidate() }
    var onToggle: (() -> Unit)? = null

    private val accent = Color.parseColor("#FF4081")
    private val ringOff = Color.parseColor("#66808080")
    private val dens = resources.displayMetrics.density
    private val iconRatio = 0.62f
    private val strokeOn = 3f * dens
    private val strokeOff = 2f * dens
    private val haloWidth = 6f * dens

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = accent
        alpha = 0x26 // 15%
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val oval = RectF()

    private var downX = 0f
    private var downY = 0f
    private var longFired = false
    private var longTask: Runnable? = null
    private val slop by lazy { ViewConfiguration.get(context).scaledTouchSlop }

    init {
        isClickable = true
        isFocusable = true
        isLongClickable = true
        minimumWidth = (circleDp * dens).toInt()
        minimumHeight = (circleDp * dens).toInt()
    }

    private fun darkTheme(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun desc(): String =
        (if (on) "关闭 " else "开启 ") + serviceName + "，当前" + (if (on) "已开启" else "未开启") +
            (if (locked) "，已加锁" else "")

    private fun refreshA11y() {
        contentDescription = desc()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val want = (circleDp * dens).toInt()
        setMeasuredDimension(resolveSize(want, widthMeasureSpec), resolveSize(want, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val d = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val r = d / 2f - haloWidth
        oval.set(cx - r, cy - r, cx + r, cy + r)

        // 圆内极淡底衬：深色 #22FFFFFF，浅色 #11000000
        fillPaint.color = if (darkTheme()) Color.parseColor("#22FFFFFF") else Color.parseColor("#11000000")
        canvas.drawCircle(cx, cy, r - strokeOn, fillPaint)

        if (on) {
            haloPaint.strokeWidth = haloWidth
            canvas.drawCircle(cx, cy, r + haloWidth / 2f, haloPaint)
            ringPaint.color = accent
            ringPaint.strokeWidth = strokeOn
        } else {
            ringPaint.color = ringOff
            ringPaint.strokeWidth = strokeOff
        }
        canvas.drawCircle(cx, cy, r, ringPaint)
        if (locked) {
            // 双线：内侧再加一圈细线，颜色跟随状态
            canvas.drawCircle(cx, cy, r - 6f * dens, ringPaint)
        }

        // 图标占直径 60–65%，FIT_CENTER 等比不裁切
        appIcon?.let { ic ->
            val s = (d * iconRatio).toInt()
            val l = (cx - s / 2f).toInt()
            val t = (cy - s / 2f).toInt()
            ic.setBounds(l, t, l + s, t + s)
            ic.draw(canvas)
        }
    }

    override fun performClick(): Boolean {
        onToggle?.invoke()
        return super.performClick()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                longFired = false
                animate().scaleX(0.94f).scaleY(0.94f).setDuration(150)
                    .setInterpolator(DecelerateInterpolator()).start()
                val task = Runnable { longFired = true; performLongClick() }
                longTask = task
                postDelayed(task, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.abs(event.x - downX) > slop || kotlin.math.abs(event.y - downY) > slop) {
                    cancelLong(); resetScale()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                cancelLong()
                resetScale()
                if (!longFired) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelLong(); resetScale()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun resetScale() {
        animate().scaleX(1f).scaleY(1f).setDuration(150)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun cancelLong() {
        longTask?.let { removeCallbacks(it) }
        longTask = null
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
        info.text = serviceName
        info.contentDescription = desc()
        info.isCheckable = true
        info.isChecked = on
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_more, "更多操作"))
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == R.id.action_more) {
            performLongClick()
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }
}
