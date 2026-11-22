package com.outoclick.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatButton

object Palette {
    const val BG = 0xFF0D0D0D.toInt()
    const val CARD = 0xFF171717.toInt()
    const val CARD_2 = 0xFF212121.toInt()
    const val CARD_3 = 0xFF2A2A2A.toInt()
    const val BORDER = 0xFF303030.toInt()
    const val TEXT = 0xFFECECEC.toInt()
    const val MUTED = 0xFFA3A3A3.toInt()
    const val DIM = 0xFF6E6E6E.toInt()
    const val PRIMARY = 0xFFECECEC.toInt()
    const val ON_PRIMARY = 0xFF0D0D0D.toInt()
    const val GREEN = 0xFF10A37F.toInt()
    const val AMBER = 0xFFE8A33D.toInt()
    const val RED = 0xFFE5484D.toInt()
    const val VIOLET = 0xFFA78BFA.toInt()
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun roundedRect(color: Int, radiusPx: Int, strokeColor: Int? = null, strokePx: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radiusPx.toFloat()
        if (strokeColor != null && strokePx > 0) setStroke(strokePx, strokeColor)
    }

fun Context.tv(
    text: CharSequence,
    sizeSp: Float = 14f,
    color: Int = Palette.TEXT,
    bold: Boolean = false,
): TextView = TextView(this).apply {
    this.text = text
    textSize = sizeSp
    setTextColor(color)
    if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    includeFontPadding = false
}

fun Context.btn(
    text: String,
    bg: Int = Palette.CARD_2,
    fg: Int = Palette.TEXT,
    onClick: () -> Unit,
): AppCompatButton = AppCompatButton(this).apply {
    this.text = text
    textSize = 13f
    isAllCaps = false
    stateListAnimator = null
    setTextColor(fg)
    background = roundedRect(bg, dp(10))
    minHeight = dp(40)
    minWidth = 0
    setPadding(dp(12), 0, dp(12), 0)
    setOnClickListener { onClick() }
}

fun Context.card(radiusDp: Int = 16, bg: Int = Palette.CARD): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedRect(bg, dp(radiusDp), Palette.BORDER, dp(1))
        setPadding(dp(14), dp(14), dp(14), dp(14))
    }

fun Context.divider(): View = View(this).apply {
    setBackgroundColor(0xFF1E2733.toInt())
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
        topMargin = dp(10)
        bottomMargin = dp(10)
    }
}

fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

fun LinearLayout.row(spacingDp: Int = 8): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    if (spacingDp > 0) {
        // spacing handled by child margins via addSpaced
    }
}

fun LinearLayout.addSpaced(view: View, weight: Float = 0f, spacingDp: Int = 8, leftMargin: Int = 0) {
    val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
    if (weight == 0f) {
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
    }
    lp.leftMargin = context.dp(leftMargin)
    lp.rightMargin = context.dp(spacingDp)
    addView(view, lp)
}

fun View.marginTop(dpValue: Int) {
    val lp = layoutParams as? ViewGroup.MarginLayoutParams ?: return
    lp.topMargin = context.dp(dpValue)
    layoutParams = lp
}

fun View.marginBottom(dpValue: Int) {
    val lp = layoutParams as? ViewGroup.MarginLayoutParams ?: return
    lp.bottomMargin = context.dp(dpValue)
    layoutParams = lp
}
