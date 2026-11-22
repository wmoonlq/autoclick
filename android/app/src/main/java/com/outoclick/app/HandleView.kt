package com.outoclick.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * 贴边悬浮条：胶囊形状，顶部状态点 + 中部三颗 grip 点。
 * 风格参考 ChatGPT 深色：中性灰、细边框、低饱和。
 */
class HandleView(context: Context) : View(context) {

    var stateColor: Int = Palette.GREEN
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density

    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xE61F1F1F.toInt()
    }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x2EFFFFFF
    }
    private val grip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99B4B4B4.toInt()
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = width / 2f
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, body)
        canvas.drawRoundRect(rect, radius, radius, border)

        dot.color = stateColor
        canvas.drawCircle(width / 2f, 13f * density, 3f * density, dot)

        val cx = width / 2f
        val cy = height / 2f + 4f * density
        for (i in -1..1) {
            canvas.drawCircle(cx, cy + i * 6f * density, 1.6f * density, grip)
        }
    }
}
