package com.outoclick.app.ui

import android.content.Context
import android.graphics.PixelFormat
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import com.outoclick.app.Palette
import com.outoclick.app.Screen
import com.outoclick.app.btn
import com.outoclick.app.dp
import com.outoclick.app.roundedRect
import com.outoclick.app.tv

/**
 * 编辑方案但还没绑定目标应用时，检测到可绑定的前台应用就弹这张卡。
 * 不用 AlertDialog，避免 Service 的 window token 问题。
 */
class BindPrompt(
    private val context: Context,
    private val wm: WindowManager,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onBind()
        fun onManual()
        fun onIgnore()
    }

    private var root: LinearLayout? = null

    fun isShowing(): Boolean = root != null

    fun show(appLabel: String, packageName: String) {
        dismiss()
        val screen = Screen.size(context)
        val width = (screen.x - context.dp(32)).coerceAtLeast(context.dp(240))

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(Palette.CARD, context.dp(18), Palette.BORDER, context.dp(1))
            setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(14))
        }
        card.addView(context.tv("绑定目标应用", 15f, Palette.TEXT, true))
        card.addView(context.tv("检测到「$appLabel」", 12.5f, Palette.MUTED).apply {
            setPadding(0, context.dp(4), 0, context.dp(2))
        })
        card.addView(context.tv(packageName, 10.5f, Palette.DIM).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(12), 0, 0)
        }
        actions.addView(
            context.btn("绑定", Palette.PRIMARY, Palette.ON_PRIMARY) {
                dismiss()
                callbacks.onBind()
            },
            LinearLayout.LayoutParams(0, context.dp(40), 1f).apply { rightMargin = context.dp(8) },
        )
        actions.addView(
            context.btn("手动选择", Palette.CARD_2, Palette.TEXT) {
                dismiss()
                callbacks.onManual()
            },
            LinearLayout.LayoutParams(0, context.dp(40), 1f).apply { rightMargin = context.dp(8) },
        )
        actions.addView(
            context.btn("忽略", Palette.CARD_2, Palette.DIM) {
                dismiss()
                callbacks.onIgnore()
            },
            LinearLayout.LayoutParams(0, context.dp(40), 0.7f),
        )
        card.addView(actions)

        val params = WindowManager.LayoutParams(
            width,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Screen.overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = context.dp(16)
            y = Screen.statusBarHeight(context) + context.dp(84)
        }

        runCatching {
            wm.addView(card, params)
            root = card
        }.onFailure {
            android.util.Log.e("BindPrompt", "addView 失败", it)
        }
    }

    fun dismiss() {
        val view = root ?: return
        root = null
        runCatching { wm.removeView(view) }
    }
}
