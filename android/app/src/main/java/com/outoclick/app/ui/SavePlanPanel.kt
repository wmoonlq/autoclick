package com.outoclick.app.ui

import android.content.Context
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import com.outoclick.app.Palette
import com.outoclick.app.btn
import com.outoclick.app.dp
import com.outoclick.app.engine.Timeline
import com.outoclick.app.input
import com.outoclick.app.model.Button
import com.outoclick.app.model.Plan
import com.outoclick.app.toast
import com.outoclick.app.tv

class SavePlanPanel(
    private val context: Context,
    private val wm: WindowManager,
    private val plan: Plan,
    private val buttons: Map<String, Button>,
    private val onSave: (name: String, loopCount: Int) -> Unit,
) {
    private val dialog = OverlayDialog(context, wm)

    fun show() {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(context.tv("保存方案", 18f, Palette.TEXT, true))
        content.addView(context.tv("循环轮次决定整份方案跑几遍", 11.5f, Palette.DIM).apply {
            setPadding(0, context.dp(4), 0, context.dp(10))
        })

        val nameInput = context.input(plan.name).apply {
            filters = arrayOf(InputFilter.LengthFilter(20))
        }
        val loopInput = context.input(plan.loopCount.coerceIn(1, 99).toString(), numeric = true)
        val estimate = context.tv("", 12.5f, Palette.MUTED)

        content.addView(label("方案名"))
        content.addView(nameInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        content.addView(label("循环轮次（1–99）"))
        content.addView(loopInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        content.addView(estimate.apply {
            setPadding(0, context.dp(10), 0, 0)
        })

        val steps = plan.steps.filter { it.enabled && buttons.containsKey(it.buttonId) }
        val estimateMs = Timeline.planDurationMs(plan, buttons)
        estimate.text = if (steps.isEmpty()) {
            "还没有启用的点位"
        } else {
            "预估总时长：约 ${Timeline.formatDuration(estimateMs)}"
        }

        loopInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val loop = (s?.toString()?.toIntOrNull() ?: 1).coerceIn(1, 99)
                val copy = plan
                val ms = Timeline.planDurationMs(copy.copy(loopCount = loop), buttons)
                estimate.text = "预估总时长：约 ${Timeline.formatDuration(ms)}"
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(18), 0, 0)
        }
        actions.addView(
            context.btn("取消", Palette.CARD_2, Palette.TEXT) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, context.dp(44), 1f).apply { rightMargin = context.dp(8) },
        )
        val save = context.btn("保存", Palette.PRIMARY, Palette.ON_PRIMARY) {
            val name = nameInput.text.toString().trim()
            if (name.isBlank()) {
                nameInput.error = "请输入方案名"
                return@btn
            }
            if (steps.isEmpty()) {
                context.toast("至少需要 1 个启用的点位")
                return@btn
            }
            val loop = (loopInput.text.toString().toIntOrNull() ?: 1).coerceIn(1, 99)
            val loopEstimate = Timeline.planDurationMs(plan.copy(loopCount = loop), buttons)
            if (loopEstimate > 30 * 60 * 1000L) {
                showLongRunConfirm {
                    onSave(name, loop)
                    dialog.dismiss()
                }
            } else {
                onSave(name, loop)
                dialog.dismiss()
            }
        }
        save.textSize = 14f
        actions.addView(save, LinearLayout.LayoutParams(0, context.dp(44), 1f))
        content.addView(actions)

        dialog.show(content)
    }

    private fun showLongRunConfirm(onConfirm: () -> Unit) {
        val dialog2 = OverlayDialog(context, wm)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(context.tv("运行时间较长", 17f, Palette.TEXT, true))
        content.addView(context.tv("预估超过 30 分钟。运行期间需要保持亮屏、解锁，并让目标应用在前台。", 12.5f, Palette.MUTED).apply {
            setPadding(0, context.dp(8), 0, context.dp(16))
        })
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(
            context.btn("返回修改", Palette.CARD_2, Palette.TEXT) { dialog2.dismiss() },
            LinearLayout.LayoutParams(0, context.dp(42), 1f).apply { rightMargin = context.dp(8) },
        )
        actions.addView(
            context.btn("仍要保存", Palette.PRIMARY, Palette.ON_PRIMARY) {
                dialog2.dismiss()
                onConfirm()
            },
            LinearLayout.LayoutParams(0, context.dp(42), 1f),
        )
        content.addView(actions)
        dialog2.show(content)
    }

    private fun label(text: String) = context.tv(text, 12f, Palette.MUTED).apply {
        setPadding(0, context.dp(10), 0, context.dp(4))
    }

    fun dismiss() = dialog.dismiss()
}
