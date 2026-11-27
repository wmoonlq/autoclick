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
 * 悬浮球的紧凑操作边栏。
 *
 * 停止：开始当前方案 + 编辑/方案/校准/退出。
 * 编辑：添加/复用/删除/保存 + 开始运行/完成。
 * 运行/暂停：暂停/继续 + 停止；长按悬浮球本身是停止，不打开这个编辑边栏。
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
        fun onCalibrate()
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
            background = roundedRect(Palette.CARD, context.dp(22))
            setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(10))
            isClickable = true
            setOnClickListener { }
        }

        card.addView(context.tv(planName.ifBlank { "未选择方案" }, 15f, Palette.TEXT, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        card.addView(context.tv(status, 10.5f, Palette.MUTED).apply {
            setPadding(0, context.dp(3), 0, 0)
        })
        if (targetLabel.isNotBlank()) {
            card.addView(context.tv(targetLabel, 10f, Palette.DIM).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, 0, 0, context.dp(6))
            })
        }

        if (mode == Mode.EDIT) {
            grid(
                card,
                cell("添加点") { callbacks.onAddPoint() },
                cell("复用") { callbacks.onReusePoint() },
            )
            grid(
                card,
                cell(
                    "删除",
                    if (hasSelection) Palette.TEXT else Palette.DIM,
                ) { callbacks.onDeletePoint() },
                cell("保存") { callbacks.onSave() },
            )
            grid(
                card,
                cell("开始运行", Palette.PRIMARY, primary = true) { callbacks.onPrimary() },
                cell("完成") { callbacks.onDoneEdit() },
            )
        } else {
            if (active) {
                when {
                    running -> grid(
                        card,
                        cell("暂停运行", Palette.PRIMARY, primary = true) { callbacks.onPrimary() },
                        cell("停止", Palette.RED) { callbacks.onStop() },
                    )

                    paused -> grid(
                        card,
                        cell("继续运行", Palette.PRIMARY, primary = true) { callbacks.onPrimary() },
                        cell("停止", Palette.RED) { callbacks.onStop() },
                    )

                    else -> {
                        grid(
                            card,
                            cell("开始当前方案", Palette.PRIMARY, primary = true) { callbacks.onPrimary() },
                            cell("编辑点位") { callbacks.onEdit() },
                        )
                        grid(
                            card,
                            cell("方案列表") { callbacks.onOpenPlans() },
                            cell("校准方向") { callbacks.onCalibrate() },
                        )
                        grid(
                            card,
                            cell("退出", Palette.RED) { callbacks.onExit() },
                            cell("关闭", Palette.DIM) { dismiss() },
                        )
                    }
                }
                if (running || paused) {
                    grid(
                        card,
                        cell("方案列表") { callbacks.onOpenPlans() },
                        cell("关闭", Palette.DIM) { dismiss() },
                    )
                }
            } else {
                card.addView(context.tv("还没有当前方案", 12f, Palette.MUTED).apply {
                    setPadding(context.dp(4), context.dp(8), context.dp(4), context.dp(8))
                })
                grid(
                    card,
                    cell("方案列表") { callbacks.onOpenPlans() },
                    cell("退出", Palette.RED) { callbacks.onExit() },
                )
            }
        }

        if (mode == Mode.EDIT) {
            card.addView(context.tv("关闭", 10.5f, Palette.DIM).apply {
                gravity = Gravity.CENTER
                setPadding(0, context.dp(8), 0, context.dp(2))
                isClickable = true
                setOnClickListener { dismiss() }
            })
        }

        val x = if (anchorX > screen.x / 2) {
            (anchorX - menuWidth - context.dp(8)).coerceAtLeast(0)
        } else {
            (anchorX + ballWidth + context.dp(8)).coerceAtMost((screen.x - menuWidth).coerceAtLeast(0))
        }
        val y = (anchorY - context.dp(16)).coerceIn(0, (screen.y - context.dp(420)).coerceAtLeast(0))

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

    private fun grid(card: LinearLayout, left: TextView, right: TextView) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            rightMargin = context.dp(3)
        })
        row.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            leftMargin = context.dp(3)
        })
        // 显式给行宽高，否则 width=0 + weight 的子项在悬浮窗里可能没有命中区域。
        card.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            context.dp(44),
        ).apply { topMargin = context.dp(3) })
    }

    private fun cell(
        text: String,
        color: Int = Palette.TEXT,
        primary: Boolean = false,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        this.text = text
        textSize = 11.5f
        gravity = Gravity.CENTER
        setTextColor(if (primary) Palette.PRIMARY else color)
        background = roundedRect(
            if (primary) Palette.PRIMARY_SOFT else Palette.CARD_2,
            context.dp(12),
        )
        minimumHeight = context.dp(44)
        isClickable = true
        setOnClickListener { onClick() }
    }
}
