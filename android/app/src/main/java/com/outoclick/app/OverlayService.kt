package com.outoclick.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.min

class OverlayService : Service() {

    companion object {
        @Volatile
        var running: Boolean = false
            private set
        const val ACTION_REFRESH = "com.outoclick.app.action.REFRESH"
        const val ACTION_EDIT = "com.outoclick.app.action.EDIT"
        const val ACTION_SHOW_BALL = "com.outoclick.app.action.SHOW_BALL"
        const val ACTION_HIDE_BALL = "com.outoclick.app.action.HIDE_BALL"
        private const val CHANNEL_ID = "overlay"
        private const val NOTIFICATION_ID = 1001
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var store: ScriptStore
    private var screenW = 0
    private var screenH = 0

    private var ball: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var titleView: TextView? = null
    private var statusView: TextView? = null
    private var btnStart: AppCompatButton? = null
    private var btnStop: AppCompatButton? = null
    private var normalActions: View? = null
    private var pickActions: View? = null
    private var pickTapButton: AppCompatButton? = null
    private var pickSwipeButton: AppCompatButton? = null

    private var crosshair: CrosshairView? = null
    private var crossParams: WindowManager.LayoutParams? = null
    private var pickStage = 0 // 0 = off, 1 = tap picking, 2 = swipe start recorded
    private var swipeStart: Point? = null
    private var lastStatus = "就绪"

    private var editor: VisualEditor? = null
    private var editing = false

    override fun onCreate() {
        super.onCreate()
        running = true
        wm = getSystemService(WindowManager::class.java)
        store = ScriptStore(this)
        val size = Screen.size(this)
        screenW = size.x
        screenH = size.y
        createChannel()
        if (store.ballVisible()) addBall()
        startForegroundCompat(lastStatus)
        Runner.onStatus = { text ->
            lastStatus = text
            mainHandler.post {
                statusView?.text = text
                updateNotification(text)
                refreshStartButton()
                updateBallVisual()
            }
        }
        Runner.onFinished = { text ->
            mainHandler.post {
                statusView?.text = text
                updateNotification(text)
                refreshStartButton()
                updateBallVisual()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REFRESH -> {
                refreshTitle()
                if (!Runner.running && statusView != null) statusView?.text = "就绪"
                if (!editing && pickStage == 0 && panel != null) {
                    removePanel()
                    addPanel()
                }
            }
            ACTION_EDIT -> enterVisualEditor()
            ACTION_SHOW_BALL -> showBall()
            ACTION_HIDE_BALL -> hideBall()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        Runner.stop()
        Runner.onStatus = null
        Runner.onFinished = null
        editor?.hide()
        editor = null
        editing = false
        removeCrosshair()
        panel?.let { runCatching { wm.removeView(it) } }
        ball?.let { runCatching { wm.removeView(it) } }
        panel = null
        ball = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------
    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "悬浮控制与运行状态"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val ballHidden = ball == null
        val toggle = PendingIntent.getService(
            this,
            11,
            Intent(this, OverlayService::class.java)
                .setAction(if (ballHidden) ACTION_SHOW_BALL else ACTION_HIDE_BALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_target)
            .setContentTitle("outoclick")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, if (ballHidden) "显示悬浮球" else "隐藏悬浮球", toggle)
            .build()
    }

    private fun startForegroundCompat(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    // ------------------------------------------------------------------
    // Floating ball
    // ------------------------------------------------------------------
    private fun overlayParams(width: Int, height: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

    private fun addBall() {
        if (editing || ball != null) return
        val size = dp(54)
        val view = TextView(this).apply {
            text = "OC"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = ballDrawable(Palette.BLUE)
            elevation = dp(8).toFloat()
        }
        val params = overlayParams(size, size).apply {
            val savedX = store.overlayX()
            val savedY = store.overlayY()
            x = (if (savedX >= 0) savedX else dp(16))
                .coerceIn(0, (screenW - size).coerceAtLeast(0))
            y = (if (savedY >= 0) savedY else (screenH / 3).coerceAtLeast(dp(120)))
                .coerceIn(0, (screenH - size).coerceAtLeast(0))
        }
        attachBall(view, params)
        runCatching { wm.addView(view, params) }
        ball = view
        ballParams = params
        updateBallVisual()
    }

    private fun ballDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(2), 0x66FFFFFF)
    }

    private fun updateBallVisual() {
        val view = ball ?: return
        val color = when {
            Runner.running && Runner.paused -> Palette.AMBER
            Runner.running -> Palette.GREEN
            else -> Palette.BLUE
        }
        view.background = ballDrawable(color)
        view.invalidate()
    }

    /**
     * 单击 = 开始/暂停/继续；长按 = 展开或收起面板；拖动 = 自由摆放（不吸边）。
     */
    private fun attachBall(view: View, params: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longPressFired = false

        val longPress = Runnable {
            if (moved || !running) return@Runnable
            longPressFired = true
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (panel == null) addPanel() else removePanel()
        }

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    longPressFired = false
                    mainHandler.postDelayed(longPress, longPressTimeout)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > slop || abs(dy) > slop) {
                        if (!moved) mainHandler.removeCallbacks(longPress)
                        moved = true
                    }
                    if (moved) {
                        params.x = (startX + dx).coerceIn(0, (screenW - params.width).coerceAtLeast(0))
                        params.y = (startY + dy).coerceIn(0, (screenH - params.height).coerceAtLeast(0))
                        runCatching { wm.updateViewLayout(v, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPress)
                    if (moved) {
                        store.setOverlay(params.x, params.y)
                    } else if (!longPressFired) {
                        toggleRun()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPress)
                    if (moved) store.setOverlay(params.x, params.y)
                    true
                }

                else -> false
            }
        }
    }

    private fun toggleRun() {
        if (Runner.running) {
            if (Runner.paused) {
                Runner.resume()
                toast("继续运行")
            } else {
                Runner.pause()
                toast("已暂停")
            }
            refreshStartButton()
            updateBallVisual()
            return
        }
        val active = store.active()
        if (active == null) {
            toast("没有脚本，长按球打开面板")
            return
        }
        if (!ClickAccessibilityService.ready()) {
            toast("无障碍服务未开启")
            return
        }
        store.setActive(active.id)
        if (Runner.start(active)) {
            toast("开始：${active.name}")
        } else {
            toast("脚本没有启用的步骤")
        }
        refreshStartButton()
        updateBallVisual()
    }

    private fun removePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        titleView = null
        statusView = null
        btnStart = null
        btnStop = null
        normalActions = null
        pickActions = null
        pickTapButton = null
        pickSwipeButton = null
    }

    private fun addPanel() {
        removePanel()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(0xF2141B24.toInt(), dp(14), Palette.BORDER, dp(1))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            elevation = dp(10).toFloat()
        }

        val script = store.active()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = tv(
            script?.let { "${it.name} · ${it.steps.size} 步" } ?: "没有脚本",
            13f,
            Palette.TEXT,
            bold = true
        ).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        titleView = title
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = btn("×", Palette.CARD_2) { removePanel() }.apply {
            textSize = 16f
            minWidth = dp(32)
            minimumWidth = dp(32)
            minHeight = dp(32)
            setPadding(0, 0, 0, 0)
        }
        head.addView(close)
        card.addView(head)

        addScriptChips(card)

        val status = tv(if (Runner.running) lastStatus else "就绪", 11f, Palette.MUTED).apply {
            maxLines = 2
            setPadding(0, dp(4), 0, 0)
        }
        statusView = status
        card.addView(status)

        val normal = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val start = btn(if (Runner.running) "暂停" else "开始", Palette.BLUE, Color.WHITE) {
            toggleRun()
            refreshStartButton()
        }
        val stop = btn("停止", Palette.RED, Color.WHITE) {
            Runner.stop()
            refreshStartButton()
        }
        row1.addView(start, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(6) })
        row1.addView(stop, LinearLayout.LayoutParams(0, dp(40), 1f))
        btnStart = start
        btnStop = stop
        normal.addView(row1, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pick = btn("取点", Palette.CARD_2) { enterPickMode() }
        val arrange = btn("编排", Palette.CARD_2) { enterVisualEditor() }
        val hide = btn("隐藏", Palette.CARD_2) { hideBall() }
        listOf(pick, arrange, hide).forEachIndexed { index, button ->
            row2.addView(button, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                rightMargin = if (index < 2) dp(6) else 0
            })
        }
        normal.addView(row2, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })
        card.addView(normal)
        normalActions = normal

        val pickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        val recordTap = btn("记录点击", Palette.BLUE, Color.WHITE) { recordTap() }
        val swipe = btn("起点", Palette.AMBER, Color.BLACK) { recordSwipe() }
        val cancel = btn("取消", Palette.CARD_2) { cancelPick() }
        pickRow.addView(recordTap, LinearLayout.LayoutParams(0, dp(38), 1.4f).apply { rightMargin = dp(6) })
        pickRow.addView(swipe, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(6) })
        pickRow.addView(cancel, LinearLayout.LayoutParams(0, dp(38), 1f))
        card.addView(pickRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        pickActions = pickRow
        pickTapButton = recordTap
        pickSwipeButton = swipe

        val width = dp(272)
        card.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val (x, y) = panelPosition(card.measuredHeight)
        val params = overlayParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            this.x = x
            this.y = y
        }
        runCatching { wm.addView(card, params) }
        panel = card

        if (pickStage != 0) {
            normal.visibility = View.GONE
            pickRow.visibility = View.VISIBLE
            pickSwipeButton?.text = if (pickStage == 2) "终点" else "起点"
        }
        refreshStartButton()
    }

    private fun addScriptChips(card: LinearLayout) {
        val scripts = store.list()
        if (scripts.isEmpty()) return
        val activeId = store.active()?.id
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scripts.forEach { item ->
            val selected = item.id == activeId
            val chip = btn(
                item.name,
                if (selected) Palette.BLUE else Palette.CARD_2,
                if (selected) Color.WHITE else Palette.TEXT
            ) {
                if (item.id != store.active()?.id) switchScript(item.id)
            }.apply {
                textSize = 11.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = dp(168)
                minHeight = dp(32)
                setPadding(dp(10), 0, dp(10), 0)
            }
            row.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(32)
            ).apply { rightMargin = dp(6) })
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        card.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(36)
        ).apply { topMargin = dp(8) })
    }

    private fun switchScript(id: String) {
        if (Runner.running) Runner.stop()
        store.setActive(id)
        val name = store.load(id)?.name ?: "脚本"
        toast("方案：$name")
        removePanel()
        addPanel()
        refreshStartButton()
    }

    private fun panelPosition(panelHeight: Int): Pair<Int, Int> {
        val width = dp(272)
        val ballSize = ballParams?.width ?: dp(54)
        val ballX = ballParams?.x ?: dp(24)
        val ballY = ballParams?.y ?: dp(320)
        val gap = dp(10)
        val bottomLimit = screenH - Screen.navigationBarHeight(this) - dp(8)
        val below = ballY + ballSize + gap
        val above = ballY - panelHeight - gap
        val y = when {
            below + panelHeight <= bottomLimit -> below
            above >= dp(8) -> above
            else -> (bottomLimit - panelHeight).coerceAtLeast(dp(8))
        }
        val x = (ballX + ballSize / 2 - width / 2)
            .coerceIn(dp(8), (screenW - width - dp(8)).coerceAtLeast(dp(8)))
        return x to y
    }

    private fun refreshStartButton() {
        btnStart?.text = when {
            Runner.running && Runner.paused -> "继续"
            Runner.running -> "暂停"
            else -> "开始"
        }
        btnStop?.isEnabled = Runner.running
        btnStart?.isEnabled = true
    }

    private fun refreshTitle() {
        val script = store.active()
        titleView?.text = script?.let { "${it.name} · ${it.steps.size} 步" } ?: "没有脚本"
    }

    // ------------------------------------------------------------------
    // Crosshair / picking
    // ------------------------------------------------------------------
    private fun enterPickMode() {
        val script = store.active()
        if (script == null) {
            toast("请先在 App 里新建脚本")
            return
        }
        addCrosshair()
        pickStage = 1
        swipeStart = null
        normalActions?.visibility = View.GONE
        pickActions?.visibility = View.VISIBLE
        pickTapButton?.text = "记录点击"
        pickSwipeButton?.text = "起点"
        statusView?.text = "拖动准星到目标位置"
    }

    private fun addCrosshair() {
        if (crosshair != null) return
        val size = dp(64)
        val view = CrosshairView(this)
        val params = overlayParams(size, size).apply {
            x = (screenW - size) / 2
            y = (screenH - size) / 2
        }
        attachDrag(view, params, savePosition = false, onClick = null)
        runCatching { wm.addView(view, params) }
        crosshair = view
        crossParams = params
    }

    private fun removeCrosshair() {
        crosshair?.let { runCatching { wm.removeView(it) } }
        crosshair = null
        crossParams = null
    }

    private fun crosshairCenter(): Point? {
        val params = crossParams ?: return null
        return Point(params.x + params.width / 2, params.y + params.height / 2)
    }

    private fun recordTap() {
        val point = crosshairCenter() ?: return
        val script = store.active() ?: return
        script.steps.add(Step.tap(point.x, point.y, 300))
        store.save(script)
        toast("已记录点击 (${point.x}, ${point.y})")
        statusView?.text = "已记录 (${point.x}, ${point.y}) · 共 ${script.steps.size} 步"
        refreshTitle()
    }

    private fun recordSwipe() {
        val point = crosshairCenter() ?: return
        val script = store.active() ?: return
        if (pickStage != 2) {
            swipeStart = point
            pickStage = 2
            pickSwipeButton?.text = "终点"
            statusView?.text = "起点 (${point.x}, ${point.y})，移动准星到终点"
            return
        }
        val start = swipeStart ?: point
        script.steps.add(Step.swipe(start.x, start.y, point.x, point.y, 300))
        store.save(script)
        toast("已记录滑动 (${start.x},${start.y}) → (${point.x},${point.y})")
        statusView?.text = "共 ${script.steps.size} 步"
        swipeStart = null
        pickStage = 1
        pickSwipeButton?.text = "起点"
        refreshTitle()
    }

    private fun cancelPick() {
        removeCrosshair()
        pickStage = 0
        swipeStart = null
        normalActions?.visibility = View.VISIBLE
        pickActions?.visibility = View.GONE
        statusView?.text = "就绪"
    }

    // ------------------------------------------------------------------
    // Visibility / visual editing
    // ------------------------------------------------------------------
    private fun hideBall() {
        if (editing) return
        removePanel()
        ball?.let { runCatching { wm.removeView(it) } }
        ball = null
        ballParams = null
        store.setBallVisible(false)
        updateNotification(lastStatus)
        toast("已隐藏悬浮球，可从通知栏恢复")
    }

    private fun showBall() {
        if (editing) return
        store.setBallVisible(true)
        if (ball == null) addBall()
        updateNotification(lastStatus)
    }

    private fun enterVisualEditor() {
        if (editing) return
        val script = store.active()
        if (script == null) {
            toast("没有脚本，先去 App 新建")
            return
        }
        if (Runner.running) Runner.stop()
        removePanel()
        removeCrosshair()
        pickStage = 0
        swipeStart = null
        ball?.let { runCatching { wm.removeView(it) } }
        ball = null
        ballParams = null
        editing = true
        val view = VisualEditor(this, wm, store, script) { exitVisualEditor() }
        if (!view.show()) {
            editing = false
            if (store.ballVisible()) addBall()
            return
        }
        editor = view
        updateNotification("编排：${script.name}")
    }

    private fun exitVisualEditor() {
        if (!editing) return
        editor?.hide()
        editor = null
        editing = false
        if (store.ballVisible()) addBall()
        refreshTitle()
        refreshStartButton()
        updateNotification(lastStatus)
    }

    // ------------------------------------------------------------------
    // Misc
    // ------------------------------------------------------------------
    private fun attachDrag(
        view: View,
        params: WindowManager.LayoutParams,
        savePosition: Boolean,
        onClick: (() -> Unit)?,
    ) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    params.x = (startX + dx).coerceIn(0, (screenW - params.width).coerceAtLeast(0))
                    params.y = (startY + dy).coerceIn(0, (screenH - params.height).coerceAtLeast(0))
                    runCatching { wm.updateViewLayout(v, params) }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        onClick?.invoke()
                    } else if (savePosition) {
                        store.setOverlay(params.x, params.y)
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private class CrosshairView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x55000000
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dp(2).toFloat()
            color = Palette.AMBER
        }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dp(1.4f.toInt()).toFloat()
            color = Color.WHITE
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Palette.AMBER
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = min(width, height) / 2f - context.dp(4)
            canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r, ring)
            canvas.drawLine(cx - r, cy, cx + r, cy, line)
            canvas.drawLine(cx, cy - r, cx, cy + r, line)
            canvas.drawCircle(cx, cy, context.dp(3).toFloat(), dot)
        }
    }
}
