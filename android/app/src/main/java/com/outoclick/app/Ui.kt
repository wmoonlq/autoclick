package com.outoclick.app

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatButton

object Palette {
    // 参考图的视觉语言：暖炭黑 + 单一黄色强调；少边框、大圆角、留白。
    const val BG = 0xFF141414.toInt()
    const val CARD = 0xFF242424.toInt()
    const val CARD_2 = 0xFF2E2E2E.toInt()
    const val CARD_3 = 0xFF383838.toInt()
    const val BORDER = 0xFF343434.toInt()
    const val TEXT = 0xFFF2F2F2.toInt()
    const val MUTED = 0xFFA0A0A0.toInt()
    const val DIM = 0xFF6E6E6E.toInt()
    const val PRIMARY = 0xFFFFD43B.toInt()
    const val ON_PRIMARY = 0xFF171717.toInt()
    /** 参考图里大按钮的“半透明黄底” */
    const val PRIMARY_SOFT = 0x33FFD43B.toInt()
    const val GREEN = 0xFFFFD43B.toInt()
    const val AMBER = 0xFFFF9F43.toInt()
    const val RED = 0xFFFF5A5F.toInt()
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
    setBackgroundColor(Palette.BORDER)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
        topMargin = dp(10)
        bottomMargin = dp(10)
    }
}

fun Context.input(
    value: String = "",
    hint: String = "",
    numeric: Boolean = false,
): EditText = EditText(this).apply {
    setText(value)
    this.hint = hint
    textSize = 14f
    setTextColor(Palette.TEXT)
    setHintTextColor(Palette.DIM)
    background = roundedRect(Palette.CARD_2, dp(10), Palette.BORDER, dp(1))
    setPadding(dp(12), dp(8), dp(12), dp(8))
    inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
    if (numeric) setSelectAllOnFocus(true)
    minHeight = dp(44)
}

fun Context.pill(
    text: CharSequence,
    bg: Int = Palette.CARD_2,
    fg: Int = Palette.TEXT,
): TextView = TextView(this).apply {
    this.text = text
    textSize = 11f
    setTextColor(fg)
    gravity = Gravity.CENTER
    setPadding(dp(10), dp(4), dp(10), dp(4))
    background = roundedRect(bg, dp(20), Palette.BORDER, dp(1))
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
