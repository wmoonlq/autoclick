package com.outoclick.app.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.outoclick.app.Palette
import com.outoclick.app.btn
import com.outoclick.app.dp
import com.outoclick.app.input
import com.outoclick.app.model.Button
import com.outoclick.app.model.PlanStep
import com.outoclick.app.tv

class PointSettingsPanel(
    private val context: Context,
    private val wm: WindowManager,
    private val step: PlanStep,
    private val button: Button,
    private val onSave: (clickCount: Int, clickGapMs: Int, pressMs: Int, pointGapMs: Int) -> Unit,
    private val onDelete: () -> Unit,
) {
    private val dialog = OverlayDialog(context, wm)

    fun show() {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            context.tv("单点设置", 18f, Palette.TEXT, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        titleRow.addView(context.tv("删除", 13f, Palette.RED).apply {
            setPadding(context.dp(12), context.dp(6), context.dp(4), context.dp(6))
            setOnClickListener {
                dialog.dismiss()
                onDelete()
            }
        })
        content.addView(titleRow)
        content.addView(context.tv("1000毫秒为1秒，数值越小，速度越快", 11.5f, Palette.DIM).apply {
            setPadding(0, context.dp(4), 0, context.dp(6))
        })

        val countInput = context.input(button.clickCount.toString(), numeric = true)
        val gapInput = context.input(button.clickGapMs.toString(), numeric = true)
        val pressInput = context.input(button.pressMs.toString(), numeric = true)
        val pointGapInput = context.input(step.pointGapMs.toString(), numeric = true)

        content.addView(
            fieldRow(
                "点击次数",
                "点击次数超过1次才可设置间隔",
                countInput,
                "次",
            )
        )
        content.addView(
            fieldRow(
                "点击间隔",
                "重复点击时的间隔时间",
                gapInput,
                "毫秒",
            )
        )
        content.addView(
            fieldRow(
                "点击时长",
                "按下到抬起的触屏时间",
                pressInput,
                "毫秒",
            )
        )
        content.addView(
            fieldRow(
                "点位间隔",
                "距离上一点位的点击时间间隔",
                pointGapInput,
                "毫秒",
            )
        )

        fun updateGapState() {
            val count = countInput.text.toString().toIntOrNull() ?: 1
            val enabled = count > 1
            gapInput.isEnabled = enabled
            gapInput.alpha = if (enabled) 1f else 0.4f
        }
        countInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = updateGapState()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        updateGapState()

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(16), 0, 0)
        }
        actions.addView(
            context.btn("取消", Palette.CARD_2, Palette.TEXT) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, context.dp(44), 1f).apply { rightMargin = context.dp(8) },
        )
        val complete = context.btn("完成", Palette.PRIMARY, Palette.ON_PRIMARY) {
            val count = (countInput.text.toString().toIntOrNull() ?: 1).coerceIn(1, 50)
            val gap = (gapInput.text.toString().toIntOrNull() ?: 120).coerceIn(0, 60_000)
            val press = (pressInput.text.toString().toIntOrNull() ?: 75).coerceIn(10, 500)
            val pointGap = (pointGapInput.text.toString().toIntOrNull() ?: 1000)
                .coerceIn(0, 60_000)
            onSave(count, gap, press, pointGap)
            dialog.dismiss()
        }
        complete.textSize = 14f
        actions.addView(complete, LinearLayout.LayoutParams(0, context.dp(44), 1f))
        content.addView(actions)

        dialog.show(content)
    }

    private fun fieldRow(
        label: String,
        subtitle: String,
        input: EditText,
        unit: String,
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(10), 0, context.dp(10))
        }
        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(context.tv(label, 15f, Palette.TEXT, true))
            addView(context.tv(subtitle, 11.5f, Palette.DIM).apply {
                setPadding(0, context.dp(2), 0, 0)
            })
        }
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val right = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input.layoutParams = LinearLayout.LayoutParams(context.dp(92), ViewGroup.LayoutParams.WRAP_CONTENT)
        right.addView(input)
        right.addView(context.tv(" $unit", 12f, Palette.MUTED))
        row.addView(right)
        return row
    }

    fun dismiss() = dialog.dismiss()
}
