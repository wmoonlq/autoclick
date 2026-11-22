package com.outoclick.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 全屏可视化编排：把脚本里每个点位画成一个可拖动的圆点。
 *
 * 交互：
 * - 拖动圆点 = 修改坐标（滑动步骤有起点 S 和终点 E 两个圆点）
 * - 点空白处 = 在该位置新增一个点击步骤
 * - 点圆点   = 选中，底部可改类型 / 删除
 */
class VisualEditor(
    private val context: Context,
    private val wm: WindowManager,
    private val store: ScriptStore,
    private val script: Script,
    private val onExit: () -> Unit,
) {

    private var root: FrameLayout? = null
    private var canvas: EditorCanvas? = null
    private var infoView: TextView? = null
    private var hintView: TextView? = null
    private var selectionRow: LinearLayout? = null
    private var selectionLabel: TextView? = null
    private var undoButton: AppCompatButton? = null
    private val undoStack = ArrayDeque<List<Step>>()

    fun show(): Boolean {
        if (root != null) return true
        val screen = Screen.size(context)

        val rootView = FrameLayout(context).apply { setBackgroundColor(Color.TRANSPARENT) }

        val editorCanvas = EditorCanvas(
            context = context,
            script = script,
            screen = screen,
            onDragStart = { pushUndo() },
            onChange = { refreshSelectionText() },
            onCommit = { saveAndRefresh() },
            onAdd = { x, y -> addPoint(x, y) },
            onSelect = { refreshBars() },
        )
        rootView.addView(editorCanvas, FrameLayout.LayoutParams(MATCH, MATCH))
        canvas = editorCanvas

        rootView.addView(
            topBar(),
            FrameLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
                topMargin = Screen.statusBarHeight(context) + context.dp(8)
                leftMargin = context.dp(10)
                rightMargin = context.dp(10)
            }
        )
        rootView.addView(
            bottomBar(),
            FrameLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                bottomMargin = Screen.navigationBarHeight(context) + context.dp(10)
                leftMargin = context.dp(10)
                rightMargin = context.dp(10)
            }
        )

        val params = WindowManager.LayoutParams(
            MATCH,
            MATCH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val added = runCatching { wm.addView(rootView, params) }.isSuccess
        if (!added) {
            context.toast("可视化编排打开失败，请检查悬浮窗权限")
            return false
        }
        root = rootView
        refreshBars()
        return true
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        canvas = null
        infoView = null
        hintView = null
        selectionRow = null
        selectionLabel = null
        undoButton = null
    }

    // ------------------------------------------------------------------
    // Chrome
    // ------------------------------------------------------------------
    private fun topBar(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedRect(0xF2141B24.toInt(), context.dp(14), Palette.BORDER, context.dp(1))
        setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))

        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            context.tv("可视化编排", 15f, Palette.TEXT, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        undoButton = smallBtn("撤销") { undoLast() }.apply { isEnabled = false }
        head.addView(undoButton)
        head.addView(
            smallBtn("完成", Palette.BLUE, Color.WHITE) { onExit() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = context.dp(6) }
        )
        addView(head)

        infoView = context.tv("", 10.5f, Palette.MUTED)
        addView(infoView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(3) })

        addView(legend(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(6) })
    }

    private fun legend(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        legendItem(row, Palette.BLUE_LIGHT, "点击")
        legendItem(row, Palette.AMBER, "长按")
        legendItem(row, Palette.VIOLET, "滑动")
        return row
    }

    private fun legendItem(row: LinearLayout, color: Int, label: String) {
        val item = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        item.addView(context.tv("●", 9f, color))
        item.addView(context.tv(" $label", 10f, Palette.MUTED))
        row.addView(item, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = context.dp(12) })
    }

    private fun bottomBar(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedRect(0xF2141B24.toInt(), context.dp(14), Palette.BORDER, context.dp(1))
        setPadding(context.dp(12), context.dp(9), context.dp(12), context.dp(9))

        hintView = context.tv("", 10.5f, Palette.MUTED)
        addView(hintView)

        selectionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        addView(selectionRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(6) })
    }

    private fun smallBtn(
        text: String,
        bg: Int = Palette.CARD_2,
        fg: Int = Palette.TEXT,
        onClick: () -> Unit,
    ): AppCompatButton = context.btn(text, bg, fg, onClick).apply {
        textSize = 11.5f
        minHeight = context.dp(32)
        minWidth = context.dp(40)
        setPadding(context.dp(10), 0, context.dp(10), 0)
    }

    // ------------------------------------------------------------------
    // Mutations
    // ------------------------------------------------------------------
    private fun pushUndo() {
        undoStack.addLast(script.steps.map { it.copy() })
        while (undoStack.size > 30) undoStack.removeFirst()
        undoButton?.isEnabled = true
    }

    private fun undoLast() {
        val previous = undoStack.removeLastOrNull() ?: return
        script.steps.clear()
        script.steps.addAll(previous)
        canvas?.selected = null
        saveAndRefresh()
    }

    private fun addPoint(x: Int, y: Int) {
        pushUndo()
        val step = Step.tap(x, y, 300)
        script.steps.add(step)
        store.save(script)
        canvas?.selected = step
        canvas?.invalidate()
        refreshBars()
        context.toast("已添加点击 #${script.steps.size} ($x, $y)")
    }

    private fun deleteSelected() {
        val step = canvas?.selected ?: return
        val index = script.steps.indexOf(step)
        if (index < 0) return
        pushUndo()
        script.steps.removeAt(index)
        canvas?.selected = null
        saveAndRefresh()
    }

    private fun toggleType(step: Step) {
        pushUndo()
        if (step.type == StepType.TAP) {
            step.type = StepType.LONG_PRESS
            step.durationMs = step.durationMs.coerceAtLeast(800)
        } else {
            step.type = StepType.TAP
            step.durationMs = 60
        }
        saveAndRefresh()
    }

    private fun saveAndRefresh() {
        store.save(script)
        canvas?.invalidate()
        refreshBars()
    }

    private fun refreshBars() {
        val points = script.steps.count { it.type != StepType.WAIT }
        infoView?.text = "${script.name} · ${script.steps.size} 步 · $points 个点"
        undoButton?.isEnabled = undoStack.isNotEmpty()
        hintView?.text = buildString {
            append("点空白处添加点击 · 拖动圆点改坐标 · 点圆点选中")
            val waits = script.steps.count { it.type == StepType.WAIT }
            if (waits > 0) append(" · 另有 $waits 个等待步骤请回脚本页编辑")
        }
        rebuildSelectionRow()
    }

    private fun rebuildSelectionRow() {
        val row = selectionRow ?: return
        val step = canvas?.selected
        row.removeAllViews()
        selectionLabel = null
        if (step == null) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        val label = context.tv(selectionText(step), 11f, Palette.TEXT)
        selectionLabel = label
        row.addView(
            label,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        when (step.type) {
            StepType.TAP -> row.addView(smallBtn("变长按") { toggleType(step) })
            StepType.LONG_PRESS -> row.addView(smallBtn("变点击") { toggleType(step) })
            else -> Unit
        }
        row.addView(
            smallBtn("取消") {
                canvas?.selected = null
                refreshBars()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = context.dp(6) }
        )
        row.addView(
            smallBtn("删除", Palette.RED, Color.WHITE) { deleteSelected() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = context.dp(6) }
        )
    }

    private fun refreshSelectionText() {
        val step = canvas?.selected ?: return
        selectionLabel?.text = selectionText(step)
    }

    private fun selectionText(step: Step): String {
        val index = script.steps.indexOf(step) + 1
        return when (step.type) {
            StepType.TAP -> "#$index 点击 (${step.x}, ${step.y})"
            StepType.LONG_PRESS -> "#$index 长按 (${step.x}, ${step.y})"
            StepType.SWIPE -> "#$index 滑动 (${step.x},${step.y}) → (${step.x2},${step.y2})"
            StepType.WAIT -> "#$index 等待"
        }
    }

    private companion object {
        val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}

// ----------------------------------------------------------------------
// Canvas
// ----------------------------------------------------------------------
private class EditorCanvas(
    context: Context,
    private val script: Script,
    private val screen: Point,
    private val onDragStart: () -> Unit,
    private val onChange: () -> Unit,
    private val onCommit: () -> Unit,
    private val onAdd: (Int, Int) -> Unit,
    private val onSelect: () -> Unit,
) : View(context) {

    var selected: Step? = null
        set(value) {
            field = value
            selectedEnd = false
            invalidate()
        }

    var selectedEnd = false

    private var pending: Anchor? = null
    private var dragging = false
    private var emptyDrag = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var origX = 0
    private var origY = 0

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val dotRadius = context.dp(13)
    private val touchRadius = context.dp(26)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1.5f.toInt()).toFloat()
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x55000000
    }
    private val selectRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2).toFloat()
        color = Color.WHITE
    }
    private val swipeLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(3).toFloat()
        color = Palette.VIOLET
        strokeCap = Paint.Cap.ROUND
    }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1).toFloat()
        color = 0x66F59E0B
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = context.dp(11).toFloat()
    }
    private val coord = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = context.dp(10.5f.toInt()).toFloat()
        setShadowLayer(context.dp(3).toFloat(), 0f, context.dp(1).toFloat(), 0xCC000000.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val location = IntArray(2)
        getLocationOnScreen(location)
        canvas.save()
        canvas.translate(-location[0].toFloat(), -location[1].toFloat())

        selected?.let { step ->
            val px = if (selectedEnd) step.x2 else step.x
            val py = if (selectedEnd) step.y2 else step.y
            canvas.drawLine(0f, py.toFloat(), screen.x.toFloat(), py.toFloat(), guide)
            canvas.drawLine(px.toFloat(), 0f, px.toFloat(), screen.y.toFloat(), guide)
        }

        script.steps.forEachIndexed { index, step ->
            when (step.type) {
                StepType.WAIT -> Unit
                StepType.TAP -> drawDot(canvas, step, step.x, step.y, Palette.BLUE_LIGHT, "${index + 1}", false)
                StepType.LONG_PRESS -> drawDot(canvas, step, step.x, step.y, Palette.AMBER, "${index + 1}", false)
                StepType.SWIPE -> {
                    swipeLine.alpha = if (step.enabled) 255 else 90
                    canvas.drawLine(
                        step.x.toFloat(), step.y.toFloat(),
                        step.x2.toFloat(), step.y2.toFloat(),
                        swipeLine
                    )
                    drawDot(canvas, step, step.x, step.y, Palette.VIOLET, "${index + 1}", false)
                    drawDot(canvas, step, step.x2, step.y2, Palette.VIOLET, "终", true)
                }
            }
        }

        selected?.let { step ->
            val px = if (selectedEnd) step.x2 else step.x
            val py = if (selectedEnd) step.y2 else step.y
            canvas.drawText("$px, $py", px.toFloat(), py - (dotRadius + context.dp(10)).toFloat(), coord)
        }

        canvas.restore()
    }

    private fun drawDot(canvas: Canvas, step: Step, x: Int, y: Int, color: Int, text: String, end: Boolean) {
        val radius = if (end) (dotRadius - context.dp(2)).toFloat() else dotRadius.toFloat()
        val alpha = if (step.enabled) 255 else 90
        val cx = x.toFloat()
        val cy = y.toFloat()

        canvas.drawCircle(cx, cy, radius + context.dp(3), shadow)
        fill.color = color
        fill.alpha = alpha
        canvas.drawCircle(cx, cy, radius, fill)
        ring.color = 0x66FFFFFF
        ring.alpha = alpha
        canvas.drawCircle(cx, cy, radius, ring)
        if (selected === step && selectedEnd == end) {
            canvas.drawCircle(cx, cy, radius + context.dp(4), selectRing)
        }
        label.alpha = alpha
        val baseline = cy - (label.ascent() + label.descent()) / 2f
        canvas.drawText(text, cx, baseline, label)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pending = findAnchor(event.rawX, event.rawY)
                dragging = false
                emptyDrag = false
                downRawX = event.rawX
                downRawY = event.rawY
                pending?.let { hit ->
                    origX = if (hit.end) hit.step.x2 else hit.step.x
                    origY = if (hit.end) hit.step.y2 else hit.step.y
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val hit = pending
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (hit != null) {
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        selected = hit.step
                        selectedEnd = hit.end
                        onDragStart()
                    }
                    if (dragging) {
                        val nx = (origX + dx).toInt().coerceIn(0, screen.x - 1)
                        val ny = (origY + dy).toInt().coerceIn(0, screen.y - 1)
                        if (hit.end) {
                            hit.step.x2 = nx
                            hit.step.y2 = ny
                        } else {
                            hit.step.x = nx
                            hit.step.y = ny
                        }
                        onChange()
                        invalidate()
                    }
                } else if (!emptyDrag && (abs(dx) > slop || abs(dy) > slop)) {
                    emptyDrag = true
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val hit = pending
                if (dragging) {
                    dragging = false
                    onCommit()
                } else if (hit != null) {
                    selected = hit.step
                    selectedEnd = hit.end
                    invalidate()
                    onSelect()
                } else if (!emptyDrag) {
                    val x = event.rawX.toInt().coerceIn(0, screen.x - 1)
                    val y = event.rawY.toInt().coerceIn(0, screen.y - 1)
                    onAdd(x, y)
                }
                pending = null
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    onCommit()
                }
                pending = null
                return true
            }
        }
        return true
    }

    private fun findAnchor(rawX: Float, rawY: Float): Anchor? {
        var best: Anchor? = null
        var bestDistance = touchRadius.toFloat()
        script.steps.forEach { step ->
            if (step.type == StepType.WAIT) return@forEach
            val d1 = distance(rawX, rawY, step.x, step.y)
            if (d1 <= bestDistance) {
                bestDistance = d1
                best = Anchor(step, false)
            }
            if (step.type == StepType.SWIPE) {
                val d2 = distance(rawX, rawY, step.x2, step.y2)
                if (d2 <= bestDistance) {
                    bestDistance = d2
                    best = Anchor(step, true)
                }
            }
        }
        return best
    }

    private fun distance(ax: Float, ay: Float, bx: Int, by: Int): Float {
        val dx = ax - bx
        val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }

    private data class Anchor(val step: Step, val end: Boolean)
}
