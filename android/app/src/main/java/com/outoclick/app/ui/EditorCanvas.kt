package com.outoclick.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.outoclick.app.Palette
import com.outoclick.app.dp
import com.outoclick.app.model.Button
import com.outoclick.app.model.Plan
import com.outoclick.app.model.PlanStep
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 全屏透明画布：把方案步骤画成带编号的圆点。
 *
 * - 单击圆点：选中并打开单点设置
 * - 拖动圆点：改坐标（松手提交）
 * - 长按圆点 1 秒：进入排序模式，拖到目标圆点后松手重排
 * - 点空白：取消选中
 *
 * v1 不做画布缩放；重叠点位用序号偏移 + 重复点击轮换选中。
 */
class EditorCanvas(
    context: Context,
    private val screen: Point,
    private val planProvider: () -> Plan?,
    private val buttonsProvider: () -> Map<String, Button>,
    private val onTapStep: (PlanStep, Button) -> Unit,
    private val onDragButton: (Button, Double, Double) -> Unit,
    private val onCommit: () -> Unit,
    private val onReorder: (PlanStep, Int) -> Unit,
    private val onSelectionChanged: (String?) -> Unit,
) : View(context) {

    private data class Marker(
        val step: PlanStep,
        val button: Button,
        val x: Float,
        val y: Float,
        val index: Int,
    )

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val dotRadius = context.dp(14).toFloat()
    private val touchRadius = context.dp(28).toFloat()

    var selectedStepId: String? = null
        private set

    private var pressedStep: PlanStep? = null
    private var pressedButton: Button? = null
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var reorderMode = false
    private var longPressFired = false
    private var reorderTargetIndex: Int? = null
    private var lastHitIds: List<String> = emptyList()
    private var lastHitIndex = 0

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1.5f.toInt()).coerceAtLeast(1).toFloat()
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = context.dp(12).toFloat()
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = context.dp(13).toFloat()
        color = Palette.MUTED
    }
    private val hotZone = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1).toFloat()
        color = 0x6610A37F
    }

    private val longPressRunnable = Runnable {
        if (pressedStep != null && !dragging) {
            reorderMode = true
            longPressFired = true
            reorderTargetIndex = pressedStep?.let { step -> markers().indexOfFirst { it.step.id == step.id } }
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    }

    init {
        isClickable = true
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun refresh(selectedId: String? = selectedStepId) {
        selectedStepId = selectedId?.takeIf { id -> planProvider()?.steps?.any { it.id == id } == true }
        invalidate()
    }

    fun clearSelection() {
        selectedStepId = null
        invalidate()
        onSelectionChanged(null)
    }

    private fun markers(): List<Marker> {
        val plan = planProvider() ?: return emptyList()
        val buttons = buttonsProvider()
        return plan.steps
            .sortedBy { it.seq }
            .mapIndexedNotNull { index, step ->
                val button = buttons[step.buttonId] ?: return@mapIndexedNotNull null
                Marker(
                    step = step,
                    button = button,
                    x = (button.anchorX * width).toFloat().takeIf { width > 0 } ?: (button.anchorX * screen.x).toFloat(),
                    y = (button.anchorY * height).toFloat().takeIf { height > 0 } ?: (button.anchorY * screen.y).toFloat(),
                    index = index,
                )
            }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val list = markers()
        if (list.isEmpty()) {
            canvas.drawText("点击「＋ 添加点」开始配置", width / 2f, height / 2f, hint)
            return
        }

        list.forEach { marker ->
            val selected = marker.step.id == selectedStepId
            val target = reorderMode && reorderTargetIndex == marker.index &&
                marker.step.id != pressedStep?.id

            if (selected) {
                val w = (marker.button.width * width).toFloat()
                    .coerceAtLeast(context.dp(24).toFloat())
                val h = (marker.button.height * height).toFloat()
                    .coerceAtLeast(context.dp(16).toFloat())
                canvas.drawRoundRect(
                    RectF(marker.x - w / 2f, marker.y - h / 2f, marker.x + w / 2f, marker.y + h / 2f),
                    context.dp(8).toFloat(),
                    context.dp(8).toFloat(),
                    hotZone,
                )
            }

            val offset = labelOffset(marker, list)
            val cx = marker.x + offset.first
            val cy = marker.y + offset.second

            fill.color = when {
                selected -> Palette.PRIMARY
                !marker.step.enabled -> 0xCC2A2A2E.toInt()
                else -> 0xFF2B2B30.toInt()
            }
            canvas.drawCircle(cx, cy, dotRadius, fill)

            ring.color = when {
                selected -> Palette.PRIMARY
                target -> Palette.GREEN
                !marker.step.enabled -> Palette.BORDER
                else -> 0x99FFFFFF.toInt()
            }
            ring.strokeWidth = if (target) context.dp(3).toFloat() else context.dp(1.5f.toInt()).coerceAtLeast(1).toFloat()
            canvas.drawCircle(cx, cy, dotRadius, ring)

            label.color = if (selected) Palette.ON_PRIMARY else Palette.TEXT
            val metrics = label.fontMetrics
            val baseline = cy - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(marker.step.seq.toString(), cx, baseline, label)
        }

        if (reorderMode) {
            canvas.drawText("拖到目标序号后松手", width / 2f, context.dp(56).toFloat(), hint)
        }
    }

    /** 重叠点位时把序号稍微错开，避免完全盖住。 */
    private fun labelOffset(marker: Marker, all: List<Marker>): Pair<Float, Float> {
        val nearby = all.filter { it.step.id != marker.step.id && hypot(it.x - marker.x, it.y - marker.y) < dotRadius * 2.5f }
        if (nearby.isEmpty()) return 0f to 0f
        val angle = Math.toRadians((marker.index * 137.5).toDouble())
        val distance = context.dp(18).toFloat()
        return (kotlin.math.cos(angle) * distance).toFloat() to
            (kotlin.math.sin(angle) * distance).toFloat()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = findHit(event.x, event.y)
                if (hit == null) {
                    clearSelection()
                    return true
                }
                pressedStep = hit.step
                pressedButton = hit.button
                selectedStepId = hit.step.id
                onSelectionChanged(selectedStepId)
                downX = event.x
                downY = event.y
                dragging = false
                reorderMode = false
                longPressFired = false
                reorderTargetIndex = null
                postDelayed(longPressRunnable, 1_000L)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val step = pressedStep ?: return true
                val dx = event.x - downX
                val dy = event.y - downY
                if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                    dragging = true
                    removeCallbacks(longPressRunnable)
                    if (reorderMode) {
                        longPressFired = true
                    }
                }
                if (reorderMode) {
                    val list = markers()
                    if (list.isNotEmpty()) {
                        reorderTargetIndex = list.minByOrNull {
                            hypot(it.x - event.x, it.y - event.y)
                        }?.index
                    }
                    invalidate()
                } else if (dragging) {
                    val anchorX = (event.x / width.coerceAtLeast(1)).toDouble().coerceIn(0.0, 1.0)
                    val anchorY = (event.y / height.coerceAtLeast(1)).toDouble().coerceIn(0.0, 1.0)
                    pressedButton?.let { button ->
                        button.anchorX = anchorX
                        button.anchorY = anchorY
                        onDragButton(button, anchorX, anchorY)
                    }
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                val step = pressedStep
                val button = pressedButton
                if (step != null && button != null) {
                    if (reorderMode) {
                        val target = reorderTargetIndex
                        if (target != null && !longPressFired) {
                            // 长按后没有移动，视为取消排序
                        } else if (target != null) {
                            onReorder(step, target)
                        }
                    } else if (dragging) {
                        onCommit()
                    } else if (!longPressFired) {
                        onTapStep(step, button)
                    }
                }
                resetPress()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                resetPress()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun resetPress() {
        pressedStep = null
        pressedButton = null
        dragging = false
        reorderMode = false
        longPressFired = false
        reorderTargetIndex = null
        invalidate()
    }

    private fun findHit(x: Float, y: Float): Marker? {
        val hits = markers()
            .mapNotNull { marker ->
                val offset = labelOffset(marker, markers())
                val dx = x - (marker.x + offset.first)
                val dy = y - (marker.y + offset.second)
                val distance = hypot(dx, dy)
                if (distance <= touchRadius) marker to distance else null
            }
            .sortedBy { it.second }
            .map { it.first }
        if (hits.isEmpty()) {
            lastHitIds = emptyList()
            return null
        }
        val ids = hits.map { it.step.id }
        val index = if (ids == lastHitIds) (lastHitIndex + 1) % hits.size else 0
        lastHitIds = ids
        lastHitIndex = index
        return hits[index]
    }
}
