package com.outoclick.app.ui

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import com.outoclick.app.Palette
import com.outoclick.app.Screen
import com.outoclick.app.dp
import com.outoclick.app.roundedRect
import com.outoclick.app.tv

class FloatingMenu(
    private val context: Context,
    private val wm: WindowManager,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onOpenPlans()
        fun onEditPlan()
        fun onToggleRun()
        fun onStop()
        fun onToggleToolbar()
        fun onExit()
    }

    private var root: LinearLayout? = null

    fun isShowing(): Boolean = root != null

    fun show(
        anchorX: Int,
        anchorY: Int,
        ballWidth: Int,
        ballHeight: Int,
        status: String,
        running: Boolean,
        paused: Boolean,
        active: Boolean,
        editing: Boolean,
        toolbarVisible: Boolean,
    ) {
        dismiss()
        val screen = Screen.size(context)
        val menuWidth = context.dp(210)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(Palette.CARD, context.dp(16), Palette.BORDER, context.dp(1))
            setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
        }

        card.addView(context.tv(status, 11f, Palette.MUTED).apply {
            setPadding(context.dp(4), 0, context.dp(4), context.dp(8))
        })

        if (active) {
            if (running) {
                card.addView(menuRow("暂停运行") {
                    dismiss()
                    callbacks.onToggleRun()
                })
            } else if (paused) {
                card.addView(menuRow("继续运行") {
                    dismiss()
                    callbacks.onToggleRun()
                })
            } else {
                card.addView(menuRow("开始当前方案") {
                    dismiss()
                    callbacks.onToggleRun()
                })
            }
            if (running || paused) {
                card.addView(menuRow("停止运行", Palette.RED) {
                    dismiss()
                    callbacks.onStop()
                })
            }
            card.addView(menuRow("编辑当前方案") {
                dismiss()
                callbacks.onEditPlan()
            })
        } else {
            card.addView(context.tv("未选择当前方案", 12f, Palette.DIM).apply {
                setPadding(context.dp(4), 0, context.dp(4), context.dp(8))
            })
        }

        if (editing) {
            card.addView(menuRow(if (toolbarVisible) "隐藏工具条" else "显示工具条") {
                dismiss()
                callbacks.onToggleToolbar()
            })
        }
        card.addView(menuRow("方案列表") {
            dismiss()
            callbacks.onOpenPlans()
        })
        card.addView(menuRow("关闭菜单", Palette.DIM) { dismiss() })
        card.addView(menuRow("退出悬浮球", Palette.RED) {
            dismiss()
            callbacks.onExit()
        })

        val x = if (anchorX > screen.x / 2) {
            (anchorX - menuWidth - context.dp(8)).coerceAtLeast(0)
        } else {
            (anchorX + ballWidth + context.dp(8)).coerceAtMost((screen.x - menuWidth).coerceAtLeast(0))
        }
        val y = (anchorY - context.dp(20)).coerceIn(0, (screen.y - context.dp(360)).coerceAtLeast(0))

        // 菜单做成可聚焦窗口：编辑画布也是悬浮窗，不可聚焦的菜单在部分版本上
        // 会被全屏编辑层挡住点击。菜单关闭后目标应用会重新拿到焦点。
        val params = WindowManager.LayoutParams(
            menuWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Screen.overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

        runCatching {
            wm.addView(card, params)
            root = card
        }.onFailure {
            android.util.Log.e("FloatingMenu", "addView 失败", it)
        }
    }

    fun dismiss() {
        val view = root ?: return
        root = null
        runCatching { wm.removeView(view) }
    }

    private fun menuRow(text: String, color: Int = Palette.TEXT, onClick: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(4), 0, context.dp(4), 0)
            addView(context.tv(text, 13.5f, color), LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ))
            minimumHeight = context.dp(40)
            isClickable = true
            setOnClickListener { onClick() }
        }
}
