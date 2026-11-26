package com.outoclick.app.ui

import android.content.Context
import android.graphics.PixelFormat
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.outoclick.app.Palette
import com.outoclick.app.Screen
import com.outoclick.app.dp
import com.outoclick.app.roundedRect
import com.outoclick.app.tv

/**
 * 悬浮球的操作边栏。
 *
 * 停止状态长按悬浮球打开：开始/暂停/停止、编辑点位、方案列表、退出。
 * 编辑状态由同一个边栏切换成编辑操作：添加/复用/删除/保存/完成。
 * 运行中不打开编辑边栏；长按悬浮球是停止。
 */
class FloatingMenu(
    private val context: Context,
    private val wm: WindowManager,
    private val callbacks: Callbacks,
) {
    enum class Mode { NORMAL, EDIT }

    interface Callbacks {
        fun onPrimary()
        fun onStop()
        fun onEdit()
        fun onAddPoint()
        fun onReusePoint()
        fun onDeletePoint()
        fun onSave()
        fun onDoneEdit()
        fun onOpenPlans()
        fun onExit()
    }

    private var root: LinearLayout? = null

    fun isShowing(): Boolean = root != null

    fun show(
        anchorX: Int,
        anchorY: Int,
        ballWidth: Int,
        ballHeight: Int,
        mode: Mode,
        status: String,
        planName: String,
        targetLabel: String,
        active: Boolean,
        running: Boolean,
        paused: Boolean,
        hasSelection: Boolean,
    ) {
        dismiss()
        val screen = Screen.size(context)
        val menuWidth = context.dp(224)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(Palette.CARD, context.dp(18), Palette.BORDER, context.dp(1))
            setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
        }

        val planTitle = if (planName.isBlank()) "未选择方案" else planName
        card.addView(context.tv(planTitle, 15f, Palette.TEXT, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        card.addView(context.tv(status, 10.5f, Palette.MUTED).apply {
            setPadding(0, context.dp(3), 0, context.dp(1))
        })
        if (targetLabel.isNotBlank()) {
            card.addView(context.tv(targetLabel, 10f, Palette.DIM).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, 0, 0, context.dp(4))
            })
        }

        if (mode == Mode.EDIT) {
            card.addView(row("＋ 添加点") { callbacks.onAddPoint() })
            card.addView(row("复用点位") { callbacks.onReusePoint() })
            card.addView(
                row(
                    "删除点",
                    if (hasSelection) Palette.TEXT else Palette.DIM,
                ) { callbacks.onDeletePoint() },
            )
            card.addView(row("保存方案") { callbacks.onSave() })
            card.addView(row("开始运行", primary = true) { callbacks.onPrimary() })
            card.addView(row("完成编辑") { callbacks.onDoneEdit() })
        } else {
            if (active) {
                when {
                    running -> card.addView(row("暂停运行", primary = true) { callbacks.onPrimary() })
                    paused -> card.addView(row("继续运行", primary = true) { callbacks.onPrimary() })
                    else -> card.addView(row("开始当前方案", primary = true) { callbacks.onPrimary() })
                }
                if (running || paused) {
                    card.addView(row("停止运行", Palette.RED) { callbacks.onStop() })
                }
                if (!running && !paused) {
                    card.addView(row("编辑点位") { callbacks.onEdit() })
                }
            } else {
                card.addView(context.tv("未选择当前方案", 11.5f, Palette.DIM).apply {
                    setPadding(context.dp(4), context.dp(6), context.dp(4), context.dp(6))
                })
            }
            card.addView(row("方案列表") { callbacks.onOpenPlans() })
        }

        card.addView(row("关闭菜单", Palette.DIM) { dismiss() })
        card.addView(row("退出悬浮球", Palette.RED) { callbacks.onExit() })

        val x = if (anchorX > screen.x / 2) {
            (anchorX - menuWidth - context.dp(8)).coerceAtLeast(0)
        } else {
            (anchorX + ballWidth + context.dp(8)).coerceAtMost((screen.x - menuWidth).coerceAtLeast(0))
        }
        val y = (anchorY - context.dp(16)).coerceIn(0, (screen.y - context.dp(620)).coerceAtLeast(0))

        // 可聚焦便于按钮点击，但 NOT_TOUCH_MODAL 保证边栏外的触摸仍然落到画布/目标应用。
        val params = WindowManager.LayoutParams(
            menuWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Screen.overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
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

    private fun row(
        text: String,
        color: Int = Palette.TEXT,
        primary: Boolean = false,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        this.text = text
        textSize = 13.5f
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(12), 0, context.dp(12), 0)
        setTextColor(if (primary) Palette.ON_PRIMARY else color)
        background = roundedRect(
            if (primary) Palette.PRIMARY else 0x00000000,
            context.dp(10),
            if (primary) Palette.PRIMARY else null,
            0,
        )
        minimumHeight = context.dp(42)
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = context.dp(2) }
    }
}
