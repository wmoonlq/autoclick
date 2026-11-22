package com.outoclick.app

import android.content.Context
import android.view.Gravity
import android.widget.TextView

/** GPT 风格的小开关胶囊：开 = 白底黑字，关 = 深灰底灰字。 */
class ToggleChip(context: Context) : TextView(context) {

    var onToggle: ((Boolean) -> Unit)? = null

    var isOn: Boolean = false
        set(value) {
            field = value
            render()
        }

    init {
        gravity = Gravity.CENTER
        textSize = 10.5f
        setPadding(context.dp(10), 0, context.dp(10), 0)
        isClickable = true
        setOnClickListener {
            isOn = !isOn
            onToggle?.invoke(isOn)
        }
        render()
    }

    private fun render() {
        text = if (isOn) "开" else "关"
        setTextColor(if (isOn) Palette.ON_PRIMARY else Palette.MUTED)
        background = roundedRect(
            if (isOn) Palette.PRIMARY else Palette.CARD_2,
            context.dp(14),
            if (isOn) Palette.PRIMARY else Palette.BORDER,
            context.dp(1)
        )
    }
}
