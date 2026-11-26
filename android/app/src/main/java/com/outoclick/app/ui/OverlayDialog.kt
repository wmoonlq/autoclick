package com.outoclick.app.ui

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.outoclick.app.Palette
import com.outoclick.app.Screen
import com.outoclick.app.dp
import com.outoclick.app.roundedRect

/**
 * 悬浮层里的模态卡片。使用 WindowManager 直接添加，避免 Service 里
 * 用 AlertDialog 时遇到 BadTokenException。
 *
 * 需要输入框时保持可聚焦；关闭后目标应用会重新拿到焦点。
 */
class OverlayDialog(
    private val context: Context,
    private val wm: WindowManager,
) {
    private var root: FrameLayout? = null
    var onDismiss: (() -> Unit)? = null

    fun isShowing(): Boolean = root != null

    fun show(content: View): Boolean {
        if (root != null) return false
        val rootView = FrameLayout(context).apply {
            setBackgroundColor(0x99000000.toInt())
            isFocusableInTouchMode = true
            setOnClickListener { dismiss() }
        }

        val cardView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(Palette.CARD, context.dp(20), Palette.BORDER, context.dp(1))
            setPadding(context.dp(18), context.dp(18), context.dp(18), context.dp(18))
            isClickable = true
            setOnClickListener { }
        }
        cardView.addView(
            content,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        rootView.addView(
            cardView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER
                leftMargin = context.dp(24)
                rightMargin = context.dp(24)
            },
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            Screen.overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        rootView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismiss()
                true
            } else {
                false
            }
        }

        return runCatching {
            wm.addView(rootView, params)
            root = rootView
            rootView.requestFocus()
            true
        }.getOrElse {
            android.util.Log.e("OverlayDialog", "addView 失败", it)
            false
        }
    }

    fun dismiss() {
        val view = root ?: return
        root = null
        runCatching { wm.removeView(view) }
        onDismiss?.invoke()
    }

    fun replace(content: View) {
        dismiss()
        show(content)
    }
}
