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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

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

    private var handle: HandleView? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var sidePanel: SidePanel? = null

    private var crosshair: CrosshairView? = null
    private var crossParams: WindowManager.LayoutParams? = null
    private var pickStage = 0 // 0 = off, 1 = tap picking, 2 = swipe start recorded
    private var swipeStart: Point? = null
    private var pickBar: LinearLayout? = null
    private var pickBarStatus: TextView? = null
    private var pickSwipeButton: AppCompatButton? = null

    private var editor: VisualEditor? = null
    private var editing = false
    private var preview: PointPreview? = null
    private var runningScript: Script? = null
    private var lastStatus = "就绪"

    private val fadeHandler = Handler(Looper.getMainLooper())
    private val fadeRunnable = Runnable {
        val target = if (Runner.running) 0.85f else 0.42f
        handle?.animate()?.alpha(target)?.setDuration(260L)?.start()
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        wm = getSystemService(WindowManager::class.java)
        store = ScriptStore(this)
        val size = Screen.size(this)
        screenW = size.x
        screenH = size.y
        createChannel()

        sidePanel = SidePanel(this, wm, store, { lastStatus }, object : SidePanel.Callbacks {
            override fun onClosed() {
                if (!running || editing || pickStage != 0 || !store.ballVisible()) return
                addHandle()
            }

            override fun onToggleRun() {
                toggleRun()
                sidePanel?.close(animated = false)
            }

            override fun onStop() {
                Runner.stop()
                sidePanel?.close(animated = false)
            }

            override fun onArrange() {
                sidePanel?.close(animated = false)
                enterVisualEditor()
            }

            override fun onPick() {
                sidePanel?.close(animated = false)
                enterPickMode()
            }

            override fun onOpenApp() {
                sidePanel?.close(animated = false)
                openApp()
            }

            override fun onHide() {
                store.setBallVisible(false)
                sidePanel?.close(animated = false)
                updateNotification(lastStatus)
                toast("已隐藏悬浮条，可从通知栏恢复")
            }

            override fun onSelectScript(id: String) {
                switchScript(id)
                sidePanel?.refresh()
            }

            override fun onSave() {
                val script = store.loaded() ?: run {
                    toast("没有加载方案")
                    return
                }
                store.save(script)
                toast("已保存：${script.name}")
                sidePanel?.refresh()
            }

            override fun onSaveAs() {
                val script = store.loaded() ?: run {
                    toast("没有加载方案")
                    return
                }
                if (Runner.running) Runner.stop()
                hidePreview()
                val copy = store.duplicate(script)
                store.setActive(copy.id)
                toast("已另存为「${copy.name}」")
                sidePanel?.refresh()
            }

            override fun onUnload() {
                if (Runner.running) Runner.stop()
                hidePreview()
                store.unload()
                toast("已退出方案，可从方案标签重新加载")
                sidePanel?.refresh()
            }

            override fun onPointsToggle(enabled: Boolean) {
                store.setShowPoints(enabled)
                if (enabled && Runner.running) {
                    showPreview(runningScript ?: store.loaded())
                } else {
                    hidePreview()
                }
                sidePanel?.refresh()
            }

            override fun onJitterToggle(enabled: Boolean) {
                val script = store.loaded() ?: run {
                    toast("先选择一个方案")
                    sidePanel?.refresh()
                    return
                }
                script.jitterMs = if (enabled) 120 else 0
                script.jitterPx = if (enabled) 6 else 0
                store.save(script)
                toast(if (enabled) "随机节奏：±120ms / ±6px" else "随机节奏已关闭")
                sidePanel?.refresh()
            }
        })

        if (store.ballVisible()) addHandle()
        startForegroundCompat(lastStatus)

        Runner.onStatus = { text ->
            lastStatus = text
            mainHandler.post {
                sidePanel?.refresh()
                pickBarStatus?.text = text
                updateNotification(text)
                updateHandleState()
            }
        }
        Runner.onFinished = { text ->
            mainHandler.post {
                hidePreview()
                runningScript = null
                sidePanel?.refresh()
                pickBarStatus?.text = text
                updateNotification(text)
                updateHandleState()
                scheduleFade()
            }
        }
        Runner.onStep = { index ->
            preview?.highlight(index)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REFRESH -> {
                sidePanel?.refresh()
                updateNotification(lastStatus)
            }
            ACTION_EDIT -> enterVisualEditor()
            ACTION_SHOW_BALL -> showHandle()
            ACTION_HIDE_BALL -> hideHandle()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        Runner.stop()
        Runner.onStatus = null
        Runner.onFinished = null
        Runner.onStep = null
        editor?.hide()
        editor = null
        editing = false
        hidePreview()
        runningScript = null
        sidePanel?.close(animated = false)
        sidePanel = null
        removeCrosshair()
        removePickBar()
        removeHandle()
        fadeHandler.removeCallbacks(fadeRunnable)
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
        val visible = store.ballVisible()
        val toggle = PendingIntent.getService(
            this,
            11,
            Intent(this, OverlayService::class.java)
                .setAction(if (visible) ACTION_HIDE_BALL else ACTION_SHOW_BALL),
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
            .addAction(0, if (visible) "隐藏悬浮条" else "显示悬浮条", toggle)
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
    // Floating handle
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

    private fun addHandle() {
        if (!running || editing || pickStage != 0) return
        if (handle != null || sidePanel?.isOpen == true) return
        val width = dp(26)
        val height = dp(72)
        val view = HandleView(this)
        val params = overlayParams(width, height).apply {
            val savedX = store.overlayX()
            val savedY = store.overlayY()
            x = if (savedX >= 0) savedX.coerceIn(0, (screenW - width).coerceAtLeast(0))
            else (screenW - width - dp(4)).coerceAtLeast(0)
            y = if (savedY >= 0) savedY.coerceIn(0, (screenH - height).coerceAtLeast(0))
            else (screenH / 3)
        }
        attachHandle(view, params)
        if (runCatching { wm.addView(view, params) }.isFailure) return
        handle = view
        handleParams = params
        updateHandleState()
        view.alpha = 1f
        scheduleFade()
    }

    private fun removeHandle() {
        handle?.let { runCatching { wm.removeView(it) } }
        handle = null
        fadeHandler.removeCallbacks(fadeRunnable)
    }

    private fun updateHandleState() {
        val color = when {
            Runner.running && Runner.paused -> Palette.AMBER
            Runner.running -> Palette.GREEN
            else -> Palette.TEXT
        }
        handle?.stateColor = color
        handle?.let { scheduleFade() }
    }

    private fun scheduleFade() {
        fadeHandler.removeCallbacks(fadeRunnable)
        fadeHandler.postDelayed(fadeRunnable, 2600L)
    }

    /**
     * 单击打开侧滑抽屉；拖动移动位置，靠近左右边缘会吸附贴边；
     * 从贴边状态横向向内滑也能打开抽屉。
     */
    private fun attachHandle(view: View, params: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var consumed = false

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    consumed = false
                    fadeHandler.removeCallbacks(fadeRunnable)
                    v.animate().cancel()
                    v.alpha = 1f
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true

                    if (moved && !consumed) {
                        val dockedLeft = startX <= dp(2)
                        val dockedRight = startX + params.width >= screenW - dp(2)
                        val inward = if (dockedLeft) dx else if (dockedRight) -dx else 0f
                        if (inward > dp(36) && abs(dx) > abs(dy) * 1.4f) {
                            consumed = true
                            openSidePanel()
                            return@setOnTouchListener true
                        }
                    }

                    if (moved && !consumed) {
                        params.x = (startX + dx).toInt().coerceIn(0, (screenW - params.width).coerceAtLeast(0))
                        params.y = (startY + dy).toInt().coerceIn(0, (screenH - params.height).coerceAtLeast(0))
                        runCatching { wm.updateViewLayout(v, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        openSidePanel()
                    } else if (!consumed) {
                        if (params.x <= dp(40)) {
                            params.x = dp(4)
                        } else if (params.x >= screenW - params.width - dp(40)) {
                            params.x = screenW - params.width - dp(4)
                        }
                        runCatching { wm.updateViewLayout(v, params) }
                        store.setOverlay(params.x, params.y)
                        scheduleFade()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (!consumed) {
                        store.setOverlay(params.x, params.y)
                        scheduleFade()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun openSidePanel() {
        if (!running || editing || pickStage != 0) return
        if (sidePanel?.isOpen == true) return
        val params = handleParams
        val centerX = (params?.x ?: (screenW - dp(26))) + dp(13)
        val side = if (centerX < screenW / 2) Gravity.START else Gravity.END
        removeHandle()
        if (params != null) {
            // 记住当前位置，关闭抽屉后恢复
            store.setOverlay(params.x, params.y)
        }
        sidePanel?.open(side)
    }

    private fun hideHandle() {
        store.setBallVisible(false)
        removeHandle()
        sidePanel?.close(animated = false)
        updateNotification(lastStatus)
        toast("已隐藏悬浮条，可从通知栏恢复")
    }

    private fun showHandle() {
        store.setBallVisible(true)
        if (!editing && pickStage == 0 && sidePanel?.isOpen != true) addHandle()
        updateNotification(lastStatus)
    }

    // ------------------------------------------------------------------
    // Run control
    // ------------------------------------------------------------------
    private fun toggleRun() {
        if (Runner.running) {
            if (Runner.paused) {
                Runner.resume()
                toast("继续运行")
            } else {
                Runner.pause()
                toast("已暂停")
            }
            updateHandleState()
            return
        }
        val script = store.loaded()
        if (script == null) {
            toast("没有脚本，点悬浮条打开面板")
            return
        }
        if (!ClickAccessibilityService.ready()) {
            toast("无障碍服务未开启")
            return
        }
        store.setActive(script.id)
        if (Runner.start(script)) {
            runningScript = script
            toast("开始：${script.name}")
            showPreview(script)
        } else {
            runningScript = null
            toast("脚本没有启用的步骤")
        }
        updateHandleState()
    }

    private fun showPreview(script: Script?) {
        if (script == null || !store.showPoints()) return
        if (preview == null) preview = PointPreview(this, wm)
        preview?.show(script)
    }

    private fun hidePreview() {
        preview?.hide()
        preview = null
    }

    private fun switchScript(id: String) {
        if (Runner.running) Runner.stop()
        store.setActive(id)
        toast("方案：${store.load(id)?.name ?: "脚本"}")
    }

    // ------------------------------------------------------------------
    // Crosshair / picking
    // ------------------------------------------------------------------
    private fun enterPickMode() {
        if (!running || editing || pickStage != 0) return
        val script = store.loaded()
        if (script == null) {
            toast("请先在 App 里新建脚本")
            return
        }
        pickStage = 1
        swipeStart = null
        sidePanel?.close(animated = false)
        removeHandle()
        addCrosshair()
        addPickBar()
        setPickStatus("拖动准星到目标位置")
    }

    private fun addCrosshair() {
        if (crosshair != null) return
        val size = dp(64)
        val view = CrosshairView(this)
        val params = overlayParams(size, size).apply {
            x = (screenW - size) / 2
            y = (screenH - size) / 2
        }
        attachDrag(view, params)
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

    private fun addPickBar() {
        if (pickBar != null) return
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(0xF5171717.toInt(), dp(18), Palette.BORDER, dp(1))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            elevation = dp(16).toFloat()
        }
        pickBarStatus = tv("拖动准星到目标位置", 11f, Palette.MUTED).apply { maxLines = 2 }
        card.addView(pickBarStatus)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val record = btn("记录点击", Palette.PRIMARY, Palette.ON_PRIMARY) { recordTap() }
        val swipe = btn("起点", Palette.CARD_2, Palette.TEXT) { recordSwipe() }
        val cancel = btn("取消", Palette.CARD_2, Palette.MUTED) { cancelPick() }
        pickSwipeButton = swipe
        row.addView(record, LinearLayout.LayoutParams(0, dp(40), 1.5f).apply { rightMargin = dp(6) })
        row.addView(swipe, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(6) })
        row.addView(cancel, LinearLayout.LayoutParams(0, dp(40), 1f))
        card.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        val params = overlayParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = Screen.navigationBarHeight(this@OverlayService) + dp(12)
        }
        runCatching { wm.addView(card, params) }
        pickBar = card
    }

    private fun removePickBar() {
        pickBar?.let { runCatching { wm.removeView(it) } }
        pickBar = null
        pickBarStatus = null
        pickSwipeButton = null
    }

    private fun setPickStatus(text: String) {
        pickBarStatus?.text = text
    }

    private fun recordTap() {
        val point = crosshairCenter() ?: return
        val script = store.loaded() ?: return
        val base = basePoint(script, point)
        script.steps.add(Step.tap(base.x, base.y, 300))
        store.save(script)
        toast("已记录点击 (${base.x}, ${base.y})")
        setPickStatus("已记录 (${base.x}, ${base.y}) · 共 ${script.steps.size} 步")
    }

    private fun recordSwipe() {
        val point = crosshairCenter() ?: return
        val script = store.loaded() ?: return
        if (pickStage != 2) {
            swipeStart = point
            pickStage = 2
            pickSwipeButton?.text = "终点"
            setPickStatus("起点 (${point.x}, ${point.y})，移动准星到终点")
            return
        }
        val start = swipeStart ?: point
        val baseStart = basePoint(script, start)
        val baseEnd = basePoint(script, point)
        script.steps.add(Step.swipe(baseStart.x, baseStart.y, baseEnd.x, baseEnd.y, 300))
        store.save(script)
        toast("已记录滑动 (${baseStart.x},${baseStart.y}) → (${baseEnd.x},${baseEnd.y})")
        pickSwipeButton?.text = "起点"
        swipeStart = null
        pickStage = 1
        setPickStatus("共 ${script.steps.size} 步 · 可继续取点")
    }

    private fun basePoint(script: Script, point: Point): Point {
        val baseW = if (script.baseWidth > 0) script.baseWidth else screenW
        val baseH = if (script.baseHeight > 0) script.baseHeight else screenH
        val x = (point.x.toFloat() * baseW / screenW).roundToInt().coerceIn(0, baseW - 1)
        val y = (point.y.toFloat() * baseH / screenH).roundToInt().coerceIn(0, baseH - 1)
        return Point(x, y)
    }

    private fun cancelPick() {
        removeCrosshair()
        removePickBar()
        pickStage = 0
        swipeStart = null
        if (!editing && store.ballVisible()) addHandle()
    }

    // ------------------------------------------------------------------
    // Visual editor
    // ------------------------------------------------------------------
    private fun enterVisualEditor() {
        if (!running || editing) return
        if (pickStage != 0) cancelPick()
        val script = store.loaded()
        if (script == null) {
            toast("没有脚本，先去 App 新建")
            return
        }
        editing = true
        if (Runner.running) Runner.stop()
        sidePanel?.close(animated = false)
        removeHandle()
        val view = VisualEditor(this, wm, store, script) { exitVisualEditor() }
        if (!view.show()) {
            editing = false
            if (store.ballVisible()) addHandle()
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
        if (store.ballVisible()) addHandle()
        updateNotification(lastStatus)
    }

    // ------------------------------------------------------------------
    // Misc
    // ------------------------------------------------------------------
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
    }

    private fun attachDrag(view: View, params: WindowManager.LayoutParams) {
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

                MotionEvent.ACTION_UP -> true
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
            color = Palette.TEXT
        }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dp(1.4f.toInt()).toFloat()
            color = Palette.TEXT
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Palette.GREEN
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
