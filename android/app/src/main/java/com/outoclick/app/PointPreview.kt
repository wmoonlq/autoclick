package com.outoclick.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.roundToInt

/** 回放落点预览：运行中在画面上叠加黄色光点 + 编号，不拦截触摸。 */
class PointPreview(
    private val context: Context,
    private val wm: WindowManager,
) {
    private var view: PreviewView? = null
    private var params: WindowManager.LayoutParams? = null

    fun show(script: Script) {
        if (view != null) return
        val size = Screen.size(context)
        val preview = PreviewView(context, script, size)
        val layout = WindowManager.LayoutParams(
            ViewGroupParams.MATCH,
            ViewGroupParams.MATCH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        if (runCatching { wm.addView(preview, layout) }.isFailure) return
        view = preview
        params = layout
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        params = null
    }

    fun highlight(index: Int) {
        view?.current = index
        view?.invalidate()
    }

    private object ViewGroupParams {
        const val MATCH = android.view.ViewGroup.LayoutParams.MATCH_PARENT
    }

    private class PreviewView(
        context: Context,
        private val script: Script,
        private val screen: Point,
    ) : View(context) {

        var current: Int = -1

        private val baseW = if (script.baseWidth > 0) script.baseWidth else screen.x
        private val baseH = if (script.baseHeight > 0) script.baseHeight else screen.y
        private val scaleX = screen.x.toFloat() / baseW
        private val scaleY = screen.y.toFloat() / baseH

        private val yellow = 0xFFF6C344.toInt()
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = yellow
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = yellow
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dp(2).toFloat()
            color = Color.WHITE
        }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dp(2).toFloat()
            color = yellow
            alpha = 140
            strokeCap = Paint.Cap.ROUND
        }
        private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = context.dp(11).toFloat()
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            setShadowLayer(context.dp(3).toFloat(), 0f, context.dp(1).toFloat(), 0xCC000000.toInt())
        }

        private fun sx(value: Int) = (value * scaleX).roundToInt().coerceIn(0, screen.x - 1)
        private fun sy(value: Int) = (value * scaleY).roundToInt().coerceIn(0, screen.y - 1)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val location = IntArray(2)
            getLocationOnScreen(location)
            canvas.save()
            canvas.translate(-location[0].toFloat(), -location[1].toFloat())

            script.steps.forEachIndexed { index, step ->
                if (!step.enabled || step.type == StepType.WAIT) return@forEachIndexed
                val x = sx(step.x).toFloat()
                val y = sy(step.y).toFloat()
                val active = index == current
                val haloRadius = if (active) context.dp(18).toFloat() else context.dp(13).toFloat()

                halo.alpha = if (active) 90 else 45
                dot.alpha = if (active) 255 else 210

                if (step.type == StepType.SWIPE) {
                    canvas.drawLine(x, y, sx(step.x2).toFloat(), sy(step.y2).toFloat(), line)
                }

                canvas.drawCircle(x, y, haloRadius, halo)
                canvas.drawCircle(x, y, context.dp(5).toFloat(), dot)
                if (active) {
                    canvas.drawCircle(x, y, context.dp(9).toFloat(), ring)
                }

                number.alpha = if (active) 255 else 190
                canvas.drawText(
                    "${index + 1}",
                    x + context.dp(10),
                    y - context.dp(9),
                    number
                )
            }

            canvas.restore()
        }
    }
}
