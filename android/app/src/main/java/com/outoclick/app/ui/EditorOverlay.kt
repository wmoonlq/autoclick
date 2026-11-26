package com.outoclick.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.outoclick.app.Palette
import com.outoclick.app.Screen
import com.outoclick.app.btn
import com.outoclick.app.data.AppRepository
import com.outoclick.app.dp
import com.outoclick.app.model.Button
import com.outoclick.app.model.Plan
import com.outoclick.app.model.PlanStep
import com.outoclick.app.toast
import com.outoclick.app.tv

/**
 * 只负责全屏透明画布和点位编辑弹窗；操作入口统一在悬浮球的边栏里，
 * 不再有独立的顶部工具条。
 */
class EditorOverlay(
    private val context: Context,
    private val wm: WindowManager,
    private val repo: AppRepository,
    private val planId: String,
    private val onChanged: () -> Unit,
) {
    private var root: FrameLayout? = null
    private var canvas: EditorCanvas? = null
    private var pointPanel: PointSettingsPanel? = null
    private var savePanel: SavePlanPanel? = null
    private val dialogs = mutableListOf<OverlayDialog>()

    fun isShowing(): Boolean = root != null

    fun hasSelection(): Boolean = canvas?.selectedStepId != null

    fun currentPlan(): Plan? = repo.read().plan(planId)

    fun show(): Boolean {
        if (root != null) return true
        val plan = currentPlan() ?: return false

        val rootView = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = false
        }
        val canvasView = EditorCanvas(
            context = context,
            screen = Screen.size(context),
            planProvider = { currentPlan() },
            buttonsProvider = { currentButtons() },
            onTapStep = { step, button -> showPointSettings(step, button) },
            onDragButton = { _, _, _ -> },
            onCommit = {
                repo.persist()
                onChanged()
            },
            onReorder = { step, targetIndex ->
                repo.moveStep(planId, step.id, targetIndex)
                refresh()
                onChanged()
            },
            onSelectionChanged = { },
        )
        rootView.addView(
            canvasView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            Screen.overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val added = runCatching {
            wm.addView(rootView, params)
            true
        }.getOrElse {
            android.util.Log.e("EditorOverlay", "addView 失败", it)
            false
        }
        if (!added) return false

        root = rootView
        canvas = canvasView
        refresh()
        return true
    }

    fun hide() {
        dismissPanels()
        val view = root ?: return
        root = null
        canvas = null
        runCatching { wm.removeView(view) }
    }

    fun refresh() {
        if (currentPlan() == null) {
            hide()
            return
        }
        canvas?.refresh()
    }

    fun addPoint() {
        val plan = currentPlan() ?: return
        if (plan.targetAppId == null) {
            context.toast("请先绑定目标应用")
            return
        }
        if (plan.steps.size >= 30) {
            context.toast("单方案最多 30 个点位")
            return
        }
        val added = repo.addPoint(planId) ?: run {
            context.toast("添加失败")
            return
        }
        canvas?.refresh(added.second.id)
        refresh()
        onChanged()
        context.toast("已添加 #${added.second.seq}")
    }

    fun showReusePicker() {
        val plan = currentPlan() ?: return
        if (plan.targetAppId == null) {
            context.toast("请先绑定目标应用")
            return
        }
        val buttons = currentButtons().values.sortedBy { it.id }
        if (buttons.isEmpty()) {
            context.toast("按钮库还是空的，先添加一个点位")
            return
        }
        val dialog = OverlayDialog(context, wm)
        dialogs.add(dialog)
        dialog.onDismiss = { dialogs.remove(dialog) }
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        content.addView(context.tv("复用点位", 17f, Palette.TEXT, true))
        content.addView(context.tv("引用按钮库里的同一个按钮；改位置会同步所有方案", 11.5f, Palette.DIM).apply {
            setPadding(0, context.dp(4), 0, context.dp(10))
        })
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        buttons.forEach { button ->
            list.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(4), context.dp(10), context.dp(4), context.dp(10))
                isClickable = true
                addView(context.tv(
                    button.label.ifBlank { "点位 ${button.id.take(4)}" },
                    14f,
                    Palette.TEXT,
                ))
                addView(context.tv(
                    "x=${"%.3f".format(button.anchorX)} · y=${"%.3f".format(button.anchorY)} · " +
                        "点击 ${button.clickCount} 次",
                    10.5f,
                    Palette.DIM,
                ))
                setOnClickListener {
                    dialog.dismiss()
                    val added = repo.addStepForButton(planId, button.id)
                    if (added == null) {
                        context.toast("插入失败：点位已满或应用不匹配")
                    } else {
                        canvas?.refresh(added.id)
                        refresh()
                        onChanged()
                        context.toast("已复用 #${added.seq}")
                    }
                }
            })
        }
        content.addView(ScrollView(context).apply {
            addView(list)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.dp(340),
            )
        })
        content.addView(
            context.btn("取消", Palette.CARD_2, Palette.TEXT) { dialog.dismiss() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.dp(42),
            ).apply { topMargin = context.dp(12) },
        )
        dialog.show(content)
    }

    fun deleteSelected() {
        val plan = currentPlan() ?: return
        val selectedId = canvas?.selectedStepId
        if (selectedId == null) {
            context.toast("先点选一个点位")
            return
        }
        val step = plan.steps.firstOrNull { it.id == selectedId } ?: return
        val button = repo.read().button(step.buttonId) ?: return
        val refCount = repo.buttonRefCount(button.id)
        val refPlans = repo.buttonRefPlanCount(button.id)
        if (refCount > 1) {
            showConfirm(
                title = "删除按钮",
                message = "该按钮被 $refPlans 个方案、$refCount 个步骤引用。彻底删除会同时移除这些步骤。",
                confirmText = "彻底删除",
                confirmColor = Palette.RED,
                onConfirm = {
                    repo.deleteStep(planId, step.id, deleteButton = true)
                    canvas?.clearSelection()
                    refresh()
                    onChanged()
                },
                secondaryText = "仅从本方案移除",
                onSecondary = {
                    repo.deleteStep(planId, step.id, deleteButton = false)
                    canvas?.clearSelection()
                    refresh()
                    onChanged()
                },
            )
        } else {
            showConfirm(
                title = "删除按钮",
                message = "将彻底删除该按钮。",
                confirmText = "删除",
                confirmColor = Palette.RED,
                onConfirm = {
                    repo.deleteStep(planId, step.id, deleteButton = true)
                    canvas?.clearSelection()
                    refresh()
                    onChanged()
                },
            )
        }
    }

    fun showSavePanel() {
        val plan = currentPlan() ?: return
        if (plan.targetAppId == null) {
            context.toast("请先绑定目标应用")
            return
        }
        if (plan.steps.isEmpty()) {
            context.toast("请先添加点位")
            return
        }
        if (plan.steps.size > 30) {
            context.toast("单方案最多 30 个点位")
            return
        }
        if (plan.steps.none { it.enabled }) {
            context.toast("至少需要 1 个启用的点位")
            return
        }
        savePanel?.dismiss()
        savePanel = SavePlanPanel(
            context = context,
            wm = wm,
            plan = plan,
            buttons = currentButtons(),
            onSave = { name, loop ->
                repo.savePlan(planId, name, loop)
                context.toast("已保存：$name")
                refresh()
                onChanged()
            },
        ).also { it.show() }
    }

    private fun showPointSettings(step: PlanStep, button: Button) {
        pointPanel?.dismiss()
        pointPanel = PointSettingsPanel(
            context = context,
            wm = wm,
            step = step,
            button = button,
            onSave = { clickCount, clickGap, press, pointGap ->
                repo.updateButton(button.id) {
                    it.clickCount = clickCount
                    it.clickGapMs = clickGap
                    it.pressMs = press
                }
                repo.updateStep(planId, step.id) {
                    it.pointGapMs = pointGap
                }
                refresh()
                onChanged()
            },
            onDelete = { deleteSelected() },
        ).also { it.show() }
    }

    private fun showConfirm(
        title: String,
        message: String,
        confirmText: String,
        confirmColor: Int,
        onConfirm: () -> Unit,
        secondaryText: String? = null,
        onSecondary: (() -> Unit)? = null,
    ) {
        val dialog = OverlayDialog(context, wm)
        dialogs.add(dialog)
        dialog.onDismiss = { dialogs.remove(dialog) }
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        content.addView(context.tv(title, 17f, Palette.TEXT, true))
        content.addView(context.tv(message, 12.5f, Palette.MUTED).apply {
            setPadding(0, context.dp(8), 0, context.dp(16))
            setLineSpacing(context.dp(2).toFloat(), 1f)
        })

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(
            context.btn("取消", Palette.CARD_2, Palette.TEXT) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, context.dp(42), 1f).apply { rightMargin = context.dp(8) },
        )
        if (secondaryText != null && onSecondary != null) {
            actions.addView(
                context.btn(secondaryText, Palette.CARD_2, Palette.TEXT) {
                    dialog.dismiss()
                    onSecondary()
                },
                LinearLayout.LayoutParams(0, context.dp(42), 1.1f).apply { rightMargin = context.dp(8) },
            )
        }
        actions.addView(
            context.btn(
                confirmText,
                confirmColor,
                if (confirmColor == Palette.PRIMARY) Palette.ON_PRIMARY else Palette.TEXT,
            ) {
                dialog.dismiss()
                onConfirm()
            },
            LinearLayout.LayoutParams(0, context.dp(42), 1f),
        )
        content.addView(actions)
        dialog.show(content)
    }

    private fun currentButtons(): Map<String, Button> {
        val plan = currentPlan() ?: return emptyMap()
        val appId = plan.targetAppId ?: return emptyMap()
        return repo.read().buttonsFor(appId)
    }

    private fun dismissPanels() {
        pointPanel?.dismiss()
        pointPanel = null
        savePanel?.dismiss()
        savePanel = null
        val copy = dialogs.toList()
        dialogs.clear()
        copy.forEach { it.dismiss() }
    }
}
