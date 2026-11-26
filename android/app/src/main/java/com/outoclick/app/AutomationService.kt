package com.outoclick.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.outoclick.app.data.AppRepository
import com.outoclick.app.engine.ForegroundTracker
import com.outoclick.app.engine.SessionManager
import com.outoclick.app.engine.StartResult
import com.outoclick.app.engine.TargetAppResolver
import com.outoclick.app.model.StopReason
import com.outoclick.app.ui.BindPrompt
import com.outoclick.app.ui.EditorOverlay
import com.outoclick.app.ui.FloatingBallView
import com.outoclick.app.ui.FloatingMenu

/**
 * 前台服务：常驻悬浮球、编辑工具条、运行会话、通知与急停。
 *
 * 设计约束（v4）：
 * - 悬浮球是唯一常驻入口，不允许隐藏；
 * - 运行中单击悬浮球 = 立即停止；
 * - 目标应用切换/系统页面/方向变化/息屏 -> 暂停或停止；
 * - START_NOT_STICKY，进程被杀后由下次启动对账，不自动复活。
 */
class AutomationService : Service() {

    companion object {
        @Volatile
        var running: Boolean = false
            private set

        const val ACTION_START = "com.outoclick.app.action.START"
        const val ACTION_EDIT = "com.outoclick.app.action.EDIT"
        const val ACTION_RUN = "com.outoclick.app.action.RUN"
        const val ACTION_STOP = "com.outoclick.app.action.STOP"
        const val ACTION_REPOST = "com.outoclick.app.action.REPOST"
        const val ACTION_SHOW_TOOLBAR = "com.outoclick.app.action.SHOW_TOOLBAR"
        const val ACTION_HIDE_TOOLBAR = "com.outoclick.app.action.HIDE_TOOLBAR"
        const val EXTRA_PLAN_ID = "plan_id"

        private const val CHANNEL_ID = "outoclick_overlay"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var wm: WindowManager
    private lateinit var repo: AppRepository
    private lateinit var sessions: SessionManager
    private lateinit var resolver: TargetAppResolver
    private lateinit var prefs: android.content.SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    private var ball: FloatingBallView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var menu: FloatingMenu? = null
    private var editor: EditorOverlay? = null
    private var bindPrompt: BindPrompt? = null
    private var editingPlanId: String? = null
    private var lastIgnoredPackage: String? = null
    private var lastPromptPackage: String? = null
    private var lastNotificationText = "就绪"
    private var foregroundStarted = false

    private val updateListener: () -> Unit = {
        mainHandler.post { refreshUi() }
    }
    private val messageListener: (String) -> Unit = { message ->
        mainHandler.post {
            toast(message)
            updateNotification()
        }
    }
    private val finishListener: (com.outoclick.app.model.RunSession) -> Unit = {
        mainHandler.post {
            refreshUi()
            updateNotification()
        }
    }

    private val foregroundListener: (String) -> Unit = { packageName ->
        mainHandler.post { onForegroundChanged(packageName) }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            sessions.onRotationChanged(Screen.rotation(this@AutomationService))
            editor?.refresh()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                sessions.onScreenOff()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        AppGraph.init(this)
        repo = AppGraph.repo
        sessions = AppGraph.sessions
        resolver = AppGraph.resolver
        wm = getSystemService(WindowManager::class.java)
        prefs = getSharedPreferences("outoclick_ui", MODE_PRIVATE)

        if (!Settings.canDrawOverlays(this)) {
            toast("缺少悬浮窗权限")
            stopSelf()
            return
        }

        createChannel()
        startForegroundCompat(notificationText())
        addBall()

        sessions.addUpdateListener(updateListener)
        sessions.addMessageListener(messageListener)
        sessions.addFinishListener(finishListener)
        ForegroundTracker.addListener(foregroundListener)

        val dm = getSystemService(DisplayManager::class.java)
        dm.registerDisplayListener(displayListener, mainHandler)
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat(notificationText())

        when (intent?.action) {
            ACTION_START -> {
                addBall()
                updateNotification()
            }

            ACTION_EDIT -> {
                val planId = intent.getStringExtra(EXTRA_PLAN_ID)
                if (planId != null) openEditor(planId) else toast("缺少方案")
            }

            ACTION_RUN -> {
                val planId = intent.getStringExtra(EXTRA_PLAN_ID)
                startRun(planId)
            }

            ACTION_STOP -> sessions.stop(StopReason.USER)

            ACTION_SHOW_TOOLBAR -> showToolbar()

            ACTION_HIDE_TOOLBAR -> hideToolbar()

            ACTION_REPOST -> updateNotification()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        sessions.removeUpdateListener(updateListener)
        sessions.removeMessageListener(messageListener)
        sessions.removeFinishListener(finishListener)
        ForegroundTracker.removeListener(foregroundListener)
        runCatching {
            getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        }
        runCatching { unregisterReceiver(screenReceiver) }
        if (sessions.isActive()) sessions.stop(StopReason.USER)
        hideEditor(clearEditing = true)
        menu?.dismiss()
        menu = null
        bindPrompt?.dismiss()
        bindPrompt = null
        removeBall()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 悬浮球
    // ------------------------------------------------------------------

    private fun addBall() {
        if (ball != null) return
        val size = dp(52)
        val screen = Screen.size(this)
        val view = FloatingBallView(this).apply {
            stateColor = stateColor()
            onTap = {
                if (sessions.isRunning()) {
                    sessions.stop(StopReason.USER)
                } else {
                    toggleMenu()
                }
            }
            onLongPress = { toggleMenu() }
            onDrag = { dx, dy ->
                val params = ballParams
                if (params != null) {
                    params.x = (params.x + dx.toInt()).coerceIn(0, (screen.x - size).coerceAtLeast(0))
                    params.y = (params.y + dy.toInt()).coerceIn(0, (screen.y - size).coerceAtLeast(0))
                    runCatching { wm.updateViewLayout(this, params) }
                }
            }
            onDragEnd = {
                snapBallToEdge()
                saveBallPosition()
            }
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            Screen.overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("ball_x", -1)
            y = prefs.getInt("ball_y", -1)
            if (x < 0) x = (screen.x - size - dp(10)).coerceAtLeast(0)
            if (y < 0) y = (screen.y / 2 - size / 2).coerceAtLeast(0)
            x = x.coerceIn(0, (screen.x - size).coerceAtLeast(0))
            y = y.coerceIn(0, (screen.y - size).coerceAtLeast(0))
        }
        runCatching {
            wm.addView(view, params)
            ball = view
            ballParams = params
        }.onFailure {
            android.util.Log.e("AutomationService", "添加悬浮球失败", it)
        }
    }

    private fun removeBall() {
        val view = ball ?: return
        ball = null
        ballParams = null
        runCatching { wm.removeView(view) }
    }

    /**
     * 编辑层是全屏悬浮窗，后添加的窗口会盖住先添加的。悬浮球必须始终可点，
     * 所以在编辑层显示后重新 add 一次，把它提到最上层。
     */
    private fun bringBallToFront() {
        val view = ball ?: return
        val params = ballParams ?: return
        runCatching {
            wm.removeView(view)
            wm.addView(view, params)
        }.onFailure {
            android.util.Log.e("AutomationService", "悬浮球置顶失败", it)
        }
    }

    private fun snapBallToEdge() {
        val params = ballParams ?: return
        val view = ball ?: return
        val screen = Screen.size(this)
        params.x = if (params.x + view.width / 2 < screen.x / 2) 0 else {
            (screen.x - view.width).coerceAtLeast(0)
        }
        runCatching { wm.updateViewLayout(view, params) }
        saveBallPosition()
    }

    private fun saveBallPosition() {
        val params = ballParams ?: return
        prefs.edit().putInt("ball_x", params.x).putInt("ball_y", params.y).apply()
    }

    private fun stateColor(): Int = when {
        sessions.isRunning() -> Palette.GREEN
        sessions.isPaused() -> Palette.AMBER
        else -> Palette.MUTED
    }

    private fun refreshBall() {
        ball?.stateColor = stateColor()
    }

    private fun toggleMenu() {
        val existing = menu
        if (existing?.isShowing() == true) {
            existing.dismiss()
            return
        }
        val params = ballParams ?: return
        val view = ball ?: return
        val active = repo.read().activePlan()
        val menuView = FloatingMenu(this, wm, object : FloatingMenu.Callbacks {
            override fun onOpenPlans() = openPlans()
            override fun onEditPlan() {
                val plan = repo.read().activePlan() ?: run {
                    toast("先选择一个方案")
                    return
                }
                openEditor(plan.id)
            }

            override fun onToggleRun() = toggleRun()
            override fun onStop() {
                sessions.stop(StopReason.USER)
            }
            override fun onToggleToolbar() {
                if (editor?.isShowing() == true) hideToolbar() else showToolbar()
            }

            override fun onExit() {
                if (sessions.isActive()) sessions.stop(StopReason.USER)
                stopSelf()
            }
        })
        menu = menuView
        menuView.show(
            anchorX = params.x,
            anchorY = params.y,
            ballWidth = view.width,
            ballHeight = view.height,
            status = statusText(),
            running = sessions.isRunning(),
            paused = sessions.isPaused(),
            active = active != null,
            editing = editor != null,
            toolbarVisible = editor?.isShowing() == true,
        )
    }

    // ------------------------------------------------------------------
    // 编辑层
    // ------------------------------------------------------------------

    private fun openEditor(planId: String) {
        val plan = repo.read().plan(planId) ?: run {
            toast("方案不存在")
            return
        }
        if (editor != null && editingPlanId == planId) {
            if (editor?.isShowing() == true) return
            showToolbar()
            return
        }
        hideEditor(clearEditing = true)
        editingPlanId = planId
        editor = EditorOverlay(
            context = this,
            wm = wm,
            repo = repo,
            planId = planId,
            callbacks = object : EditorOverlay.Callbacks {
                override fun onExit() = exitEditor()
                override fun onHideToolbar() = hideToolbar()
                override fun onRun() = startRun(planId)
            },
        )
        if (editor?.show() != true) {
            toast("无法打开编辑层，请检查悬浮窗权限")
            editor = null
            editingPlanId = null
            return
        }
        bringBallToFront()
        if (plan.targetAppId == null) {
            maybeShowBindPrompt(ForegroundTracker.packageName)
        }
        updateNotification()
    }

    private fun hideToolbar() {
        editor?.hide()
        bindPrompt?.dismiss()
        updateNotification()
    }

    private fun showToolbar() {
        val planId = editingPlanId ?: return
        if (editor == null) {
            openEditor(planId)
            return
        }
        if (editor?.show() != true) {
            toast("无法显示工具条")
            return
        }
        bringBallToFront()
        maybeShowBindPrompt(ForegroundTracker.packageName)
        updateNotification()
    }

    private fun exitEditor() {
        if (sessions.isActive()) sessions.stop(StopReason.USER)
        hideEditor(clearEditing = true)
        openPlans()
    }

    private fun hideEditor(clearEditing: Boolean) {
        editor?.hide()
        bindPrompt?.dismiss()
        bindPrompt = null
        lastPromptPackage = null
        if (clearEditing) {
            editor = null
            editingPlanId = null
        }
        updateNotification()
    }

    private fun openPlans() {
        hideEditor(clearEditing = true)
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        runCatching { startActivity(intent) }
    }

    // ------------------------------------------------------------------
    // 运行
    // ------------------------------------------------------------------

    private fun startRun(planId: String?) {
        val id = planId ?: repo.read().activePlan()?.id
        if (id == null) {
            toast("先选择一个方案")
            return
        }
        when (val result = sessions.start(id)) {
            is StartResult.Failure -> toast(result.message)
            StartResult.Success -> {
                hideToolbar()
                addBall()
                refreshUi()
            }
        }
    }

    private fun toggleRun() {
        if (sessions.isRunning() || sessions.isPaused()) {
            sessions.togglePause()
            refreshUi()
            return
        }
        startRun(repo.read().activePlan()?.id)
    }

    // ------------------------------------------------------------------
    // 前台应用 / 绑定
    // ------------------------------------------------------------------

    private fun onForegroundChanged(packageName: String) {
        sessions.onForegroundPackage(packageName)
        if (lastIgnoredPackage != null && packageName != lastIgnoredPackage) {
            lastIgnoredPackage = null
        }
        maybeShowBindPrompt(packageName)
    }

    private fun maybeShowBindPrompt(packageName: String) {
        val planId = editingPlanId ?: return
        if (editor?.isShowing() != true) return
        val plan = repo.read().plan(planId) ?: return
        if (plan.targetAppId != null) {
            bindPrompt?.dismiss()
            lastPromptPackage = null
            return
        }
        if (packageName.isBlank() || packageName == this.packageName) return
        if (!resolver.isBindable(packageName)) {
            bindPrompt?.dismiss()
            lastPromptPackage = null
            return
        }
        if (packageName == lastIgnoredPackage) return
        if (bindPrompt?.isShowing() == true && lastPromptPackage == packageName) return

        bindPrompt?.dismiss()
        lastPromptPackage = packageName
        val app = resolver.resolve(packageName)
        bindPrompt = BindPrompt(this, wm, object : BindPrompt.Callbacks {
            override fun onBind() {
                editor?.bindPackage(packageName)
                bindPrompt = null
                lastPromptPackage = null
            }

            override fun onManual() {
                bindPrompt = null
                lastPromptPackage = null
                editor?.showManualBind()
            }

            override fun onIgnore() {
                lastIgnoredPackage = packageName
                bindPrompt = null
                lastPromptPackage = null
            }
        }).also { it.show(app.label, app.packageName) }
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "悬浮控制",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "运行状态、暂停与停止"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            11,
            Intent(this, AutomationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val repost = PendingIntent.getService(
            this,
            12,
            Intent(this, AutomationService::class.java).setAction(ACTION_REPOST),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_target)
            .setContentTitle("outoclick")
            .setContentText(lastNotificationText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setDeleteIntent(repost)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "方案列表", open)

        if (sessions.isActive()) {
            builder.addAction(0, "停止", stop)
        }
        return builder.build()
    }

    private fun startForegroundCompat(text: String) {
        lastNotificationText = text
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun updateNotification() {
        lastNotificationText = notificationText()
        if (!foregroundStarted) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun notificationText(): String {
        if (sessions.isActive()) return sessions.statusText()
        val active = repo.read().activePlan()
        return if (active != null) "就绪 · ${active.name}" else "就绪 · 未选择方案"
    }

    private fun statusText(): String {
        if (sessions.isActive()) return sessions.statusText()
        val active = repo.read().activePlan()
        return if (active != null) "就绪 · ${active.name}" else "就绪 · 未选择方案"
    }

    private fun refreshUi() {
        refreshBall()
        updateNotification()
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        runCatching { android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show() }
    }
}
