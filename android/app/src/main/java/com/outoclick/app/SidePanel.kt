package com.outoclick.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

import kotlin.math.min

/**
 * iQOO 游戏助手式的侧滑抽屉：半透明遮罩 + 从屏幕侧边滑入的工具面板。
 * 视觉参考 ChatGPT 深色：中性灰、白色主按钮、细边框、大圆角。
 */
class SidePanel(
    private val context: Context,
    private val wm: WindowManager,
    private val store: ScriptStore,
    private val statusProvider: () -> String,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        fun onClosed()
        fun onToggleRun()
        fun onStop()
        fun onArrange()
        fun onPick()
        fun onOpenApp()
        fun onHide()
        fun onSelectScript(id: String)
        fun onSave()
        fun onSaveAs()
        fun onUnload()
        fun onPointsToggle(enabled: Boolean)
        fun onJitterToggle(enabled: Boolean)
    }

    private var scrim: FrameLayout? = null
    private var drawer: LinearLayout? = null
    private var schemeName: TextView? = null
    private var schemeMeta: TextView? = null
    private var chipsRow: LinearLayout? = null
    private var mainButton: LinearLayout? = null
    private var mainIcon: ImageView? = null
    private var mainLabel: TextView? = null
    private var statusView: TextView? = null
    private var statePill: TextView? = null
    private var pointsToggle: ToggleChip? = null
    private var jitterToggle: ToggleChip? = null
    private var chipsSignature: String? = null
    private var side = Gravity.END

    var isOpen = false
        private set

    fun open(gravity: Int) {
        if (isOpen) return
        side = gravity
        val screen = Screen.size(context)
        val width = min(context.dp(300), screen.x - context.dp(56))
        val panel = build(width)
        panel.translationX = if (gravity == Gravity.START) {
            -(width + context.dp(24)).toFloat()
        } else {
            (width + context.dp(24)).toFloat()
        }

        val scrimView = FrameLayout(context).apply {
            setBackgroundColor(0x3D000000)
            isClickable = true
            setOnClickListener { close() }
        }
        scrimView.addView(panel, FrameLayout.LayoutParams(width, MATCH, gravity).apply {
            topMargin = Screen.statusBarHeight(context) + context.dp(10)
            bottomMargin = Screen.navigationBarHeight(context) + context.dp(10)
        })

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
        ).apply { this.gravity = Gravity.TOP or Gravity.START }

        if (runCatching { wm.addView(scrimView, params) }.isFailure) return
        scrim = scrimView
        drawer = panel
        isOpen = true
        panel.animate()
            .translationX(0f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        refresh()
    }

    fun close(animated: Boolean = true) {
        val current = scrim ?: return
        val panel = drawer
        isOpen = false
        scrim = null
        drawer = null
        schemeName = null
        schemeMeta = null
        chipsRow = null
        mainButton = null
        mainIcon = null
        mainLabel = null
        statusView = null
        statePill = null
        pointsToggle = null
        jitterToggle = null
        chipsSignature = null

        val remove = {
            runCatching { wm.removeView(current) }
            callbacks.onClosed()
        }
        if (animated && panel != null) {
            val off = if (side == Gravity.START) {
                -(panel.width + context.dp(24)).toFloat()
            } else {
                (panel.width + context.dp(24)).toFloat()
            }
            panel.animate()
                .translationX(off)
                .setDuration(170L)
                .withEndAction { remove() }
                .start()
        } else {
            remove()
        }
    }

    fun refresh() {
        if (!isOpen) return
        val script = store.loaded()
        schemeName?.text = script?.name ?: "未加载方案"
        schemeMeta?.text = if (script == null) {
            "点上方方案标签加载，或去 App 里新建"
        } else {
            "${script.steps.size} 步 · 循环 ${script.repeat} 次 · ${script.steps.count { s -> s.enabled }} 步启用"
        }
        statusView?.text = statusProvider()
        updateStatePill()

        val activeId = store.activeId()
        val signature = store.list().joinToString("|") { "${it.id}:${it.name}" } + "#" + (activeId ?: "")
        if (signature != chipsSignature) {
            chipsSignature = signature
            rebuildChips(activeId)
        }

        pointsToggle?.isOn = store.showPoints()
        jitterToggle?.isOn = script != null && (script.jitterMs > 0 || script.jitterPx > 0)
        updateMain()
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------
    private fun build(width: Int): LinearLayout {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(0xF5171717.toInt(), context.dp(20), Palette.BORDER, context.dp(1))
            setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(14))
            isClickable = true
            elevation = context.dp(18).toFloat()
        }

        panel.addView(header())

        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        content.addView(spacer(context.dp(14)))
        content.addView(sectionLabel("当前方案"))
        schemeName = context.tv("", 16f, Palette.TEXT, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        content.add(schemeName, topMargin = context.dp(4))
        schemeMeta = context.tv("", 10.5f, Palette.MUTED).apply { maxLines = 2 }
        content.add(schemeMeta, topMargin = context.dp(2))

        chipsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val chipsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipsRow)
        }
        content.add(chipsScroll, height = context.dp(36), topMargin = context.dp(8))

        content.addView(spacer(context.dp(16)))
        content.addView(primaryRow())
        content.addView(spacer(context.dp(18)))
        content.addView(sectionLabel("工具"))
        content.add(toolGrid(), topMargin = context.dp(8))

        content.addView(spacer(context.dp(18)))
        content.addView(sectionLabel("选项"))
        val points = buildPointsToggle()
        pointsToggle = points
        content.add(switchRow("回放时显示落点", points), topMargin = context.dp(8))
        val jitter = buildJitterToggle()
        jitterToggle = jitter
        content.add(switchRow("随机节奏（±120ms / ±6px）", jitter), topMargin = context.dp(6))

        content.addView(spacer(context.dp(18)))
        content.addView(sectionLabel("方案"))
        content.add(schemeButtons(), topMargin = context.dp(8))

        val scroll = ScrollView(context).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        panel.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        statusView = context.tv(statusProvider(), 10.5f, Palette.MUTED).apply {
            maxLines = 3
            setLineSpacing(context.dp(2).toFloat(), 1f)
        }
        panel.add(statusView, topMargin = context.dp(10))
        panel.add(
            context.tv("点悬浮条打开 · 拖到屏幕边缘会自动贴边", 9.5f, Palette.DIM),
            topMargin = context.dp(6)
        )
        return panel
    }

    private fun buildPointsToggle(): ToggleChip = ToggleChip(context).apply {
        isOn = store.showPoints()
        onToggle = { callbacks.onPointsToggle(it) }
    }

    private fun buildJitterToggle(): ToggleChip = ToggleChip(context).apply {
        val script = store.loaded()
        isOn = script != null && (script.jitterMs > 0 || script.jitterPx > 0)
        onToggle = { callbacks.onJitterToggle(it) }
    }

    private fun switchRow(label: String, toggle: ToggleChip): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            context.tv(label, 11.5f, Palette.TEXT),
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        row.addView(toggle, LinearLayout.LayoutParams(context.dp(52), context.dp(30)))
        return row
    }

    private fun schemeButtons(): LinearLayout {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val save = context.btn("保存", Palette.CARD_2, Palette.TEXT) { callbacks.onSave() }
        val saveAs = context.btn("另存为", Palette.CARD_2, Palette.TEXT) { callbacks.onSaveAs() }
        val exit = context.btn("退出", Palette.CARD_2, Palette.MUTED) { callbacks.onUnload() }
        listOf(save, saveAs, exit).forEachIndexed { index, button ->
            button.textSize = 11.5f
            row.addView(button, LinearLayout.LayoutParams(0, context.dp(38), 1f).apply {
                rightMargin = if (index < 2) context.dp(6) else 0
            })
        }
        return row
    }

    private fun header(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            context.tv("outoclick", 16f, Palette.TEXT, bold = true),
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        statePill = context.tv("就绪", 10.5f, Palette.MUTED).apply {
            setPadding(context.dp(9), context.dp(4), context.dp(9), context.dp(4))
            background = roundedRect(Palette.CARD_2, context.dp(10))
        }
        row.addView(statePill)
        val close = context.tv("×", 20f, Palette.MUTED).apply {
            gravity = Gravity.CENTER
            isClickable = true
            background = roundedRect(Palette.CARD_2, context.dp(16))
            setOnClickListener { close() }
        }
        row.addView(close, LinearLayout.LayoutParams(context.dp(32), context.dp(32)).apply {
            leftMargin = context.dp(8)
        })
        return row
    }

    private fun primaryRow(): LinearLayout {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

        mainButton = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = roundedRect(Palette.PRIMARY, context.dp(14))
            isClickable = true
            setOnClickListener { callbacks.onToggleRun() }
        }
        val button = mainButton!!
        mainIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_action_play)
            setColorFilter(Palette.ON_PRIMARY)
        }
        button.addView(mainIcon, LinearLayout.LayoutParams(context.dp(20), context.dp(20)))
        mainLabel = context.tv("开始", 14.5f, Palette.ON_PRIMARY, bold = true)
        button.addView(mainLabel, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            leftMargin = context.dp(7)
        })
        row.addView(button, LinearLayout.LayoutParams(0, context.dp(52), 1f))

        val stop = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = roundedRect(Palette.CARD_2, context.dp(14), Palette.BORDER, context.dp(1))
            isClickable = true
            setOnClickListener { callbacks.onStop() }
        }
        val stopIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_action_stop)
            setColorFilter(Palette.RED)
        }
        stop.addView(stopIcon, LinearLayout.LayoutParams(context.dp(18), context.dp(18)))
        stop.addView(context.tv("停止", 12.5f, Palette.RED), LinearLayout.LayoutParams(WRAP, WRAP).apply {
            leftMargin = context.dp(5)
        })
        row.addView(stop, LinearLayout.LayoutParams(context.dp(88), context.dp(52)).apply {
            leftMargin = context.dp(8)
        })
        return row
    }

    private fun toolGrid(): LinearLayout {
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        grid.addView(toolRow(
            tool(R.drawable.ic_action_edit, "可视化编排") { callbacks.onArrange() },
            tool(R.drawable.ic_action_target, "准星取点") { callbacks.onPick() }
        ))
        grid.add(toolRow(
            tool(R.drawable.ic_action_app, "脚本列表") { callbacks.onOpenApp() },
            tool(R.drawable.ic_action_hide, "隐藏悬浮条") { callbacks.onHide() }
        ), topMargin = context.dp(8))
        return grid
    }

    private fun toolRow(left: View, right: View): LinearLayout {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(left, LinearLayout.LayoutParams(0, context.dp(70), 1f))
        row.addView(right, LinearLayout.LayoutParams(0, context.dp(70), 1f).apply {
            leftMargin = context.dp(8)
        })
        return row
    }

    private fun tool(iconRes: Int, label: String, onClick: () -> Unit): LinearLayout {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedRect(Palette.CARD_2, context.dp(14), Palette.BORDER, context.dp(1))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val icon = ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(Palette.MUTED)
        }
        box.addView(icon, LinearLayout.LayoutParams(context.dp(22), context.dp(22)))
        box.addView(context.tv(label, 11.5f, Palette.TEXT), LinearLayout.LayoutParams(WRAP, WRAP).apply {
            topMargin = context.dp(6)
        })
        return box
    }

    private fun sectionLabel(text: String): TextView =
        context.tv(text, 10.5f, Palette.DIM).apply { letterSpacing = 0.06f }

    private fun LinearLayout.add(
        view: View?,
        width: Int = MATCH,
        height: Int = WRAP,
        topMargin: Int = 0,
    ) {
        if (view == null) return
        addView(view, LinearLayout.LayoutParams(width, height).apply { this.topMargin = topMargin })
    }

    private fun spacer(height: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, height)
    }

    // ------------------------------------------------------------------
    // Dynamic parts
    // ------------------------------------------------------------------
    private fun rebuildChips(activeId: String?) {
        val row = chipsRow ?: return
        row.removeAllViews()
        val scripts = store.list()
        if (scripts.isEmpty()) {
            row.addView(context.tv("没有脚本", 11f, Palette.MUTED))
            return
        }
        scripts.forEach { script ->
            val selected = script.id == activeId
            val chip = context.tv(script.name, 11.5f, if (selected) Palette.ON_PRIMARY else Palette.TEXT).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = context.dp(150)
                gravity = Gravity.CENTER
                setPadding(context.dp(12), 0, context.dp(12), 0)
                background = roundedRect(
                    if (selected) Palette.PRIMARY else Palette.CARD_2,
                    context.dp(16),
                    if (selected) Palette.PRIMARY else Palette.BORDER,
                    context.dp(1)
                )
                isClickable = true
                setOnClickListener { callbacks.onSelectScript(script.id) }
            }
            row.addView(chip, LinearLayout.LayoutParams(WRAP, context.dp(32)).apply {
                rightMargin = context.dp(6)
            })
        }
    }

    private fun updateStatePill() {
        val pill = statePill ?: return
        val (text, bg, fg) = when {
            Runner.running && Runner.paused -> Triple("已暂停", Palette.AMBER, Color.BLACK)
            Runner.running -> Triple("运行中", Palette.GREEN, Color.WHITE)
            else -> Triple("就绪", Palette.CARD_2, Palette.MUTED)
        }
        pill.text = text
        pill.setTextColor(fg)
        pill.background = roundedRect(bg, context.dp(10))
    }

    private fun updateMain() {
        val button = mainButton ?: return
        val label = mainLabel ?: return
        val icon = mainIcon ?: return
        if (store.loaded() == null) {
            label.text = "先选择方案"
            label.setTextColor(Palette.MUTED)
            icon.setImageResource(R.drawable.ic_action_play)
            icon.setColorFilter(Palette.MUTED)
            button.background = roundedRect(Palette.CARD_3, context.dp(14), Palette.BORDER, context.dp(1))
            return
        }
        when {
            Runner.running && Runner.paused -> {
                label.text = "继续"
                label.setTextColor(Palette.ON_PRIMARY)
                icon.setImageResource(R.drawable.ic_action_play)
                icon.setColorFilter(Palette.ON_PRIMARY)
                button.background = roundedRect(Palette.PRIMARY, context.dp(14))
            }

            Runner.running -> {
                label.text = "暂停"
                label.setTextColor(Palette.TEXT)
                icon.setImageResource(R.drawable.ic_action_pause)
                icon.setColorFilter(Palette.TEXT)
                button.background = roundedRect(Palette.CARD_3, context.dp(14), Palette.BORDER, context.dp(1))
            }

            else -> {
                label.text = "开始"
                label.setTextColor(Palette.ON_PRIMARY)
                icon.setImageResource(R.drawable.ic_action_play)
                icon.setColorFilter(Palette.ON_PRIMARY)
                button.background = roundedRect(Palette.PRIMARY, context.dp(14))
            }
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
