package com.outoclick.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.outoclick.app.Palette
import com.outoclick.app.dp

/**
 * 常驻悬浮球。规范要求它不可被隐藏，运行中单击 = 立即停止。
 * 拖动/长按菜单由 [com.outoclick.app.AutomationService] 处理。
 */
class FloatingBallView(context: Context) : View(context) {

    var stateColor: Int = Palette.MUTED
        set(value) {
            field = value
            invalidate()
        }

    var onTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null
    var onDrag: ((dx: Float, dy: Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var downAt = 0L
    private var dragging = false
    private var longPressFired = false

    private val longPressRunnable = Runnable {
        if (!dragging) {
            longPressFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPress?.invoke()
        }
    }

    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xF21A1A1E.toInt()
    }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1).toFloat()
        color = 0x66FFFFFF
    }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66000000
        setShadowLayer(context.dp(8).toFloat(), 0f, context.dp(3).toFloat(), 0x66000000)
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f - context.dp(3)

        canvas.drawCircle(cx, cy, radius, shadow)
        canvas.drawCircle(cx, cy, radius, body)
        canvas.drawCircle(cx, cy, radius, border)

        icon.color = stateColor
        val arm = radius * 0.42f
        canvas.drawCircle(cx, cy, arm * 0.42f, icon)
        canvas.drawLine(cx - arm, cy, cx - arm * 0.55f, cy, icon)
        canvas.drawLine(cx + arm * 0.55f, cy, cx + arm, cy, icon)
        canvas.drawLine(cx, cy - arm, cx, cy - arm * 0.55f, icon)
        canvas.drawLine(cx, cy + arm * 0.55f, cx, cy + arm, icon)

        val dot = Paint(icon).apply {
            style = Paint.Style.FILL
            color = stateColor
        }
        canvas.drawCircle(cx + radius * 0.58f, cy - radius * 0.58f, context.dp(3).toFloat(), dot)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                downAt = System.currentTimeMillis()
                dragging = false
                longPressFired = false
                postDelayed(longPressRunnable, 550L)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val totalDx = event.rawX - downRawX
                val totalDy = event.rawY - downRawY
                if (!dragging && (kotlin.math.abs(totalDx) > slop || kotlin.math.abs(totalDy) > slop)) {
                    dragging = true
                    removeCallbacks(longPressRunnable)
                }
                if (dragging) {
                    onDrag?.invoke(event.rawX - lastRawX, event.rawY - lastRawY)
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                if (dragging) {
                    onDragEnd?.invoke()
                } else if (!longPressFired && System.currentTimeMillis() - downAt < 450L) {
                    performClick()
                    onTap?.invoke()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                if (dragging) onDragEnd?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
