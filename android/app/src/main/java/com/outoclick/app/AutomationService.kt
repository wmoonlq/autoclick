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
import com.outoclick.app.model.PauseReason
import com.outoclick.app.model.StopReason
import com.outoclick.app.ui.BallState
import com.outoclick.app.ui.EditorOverlay
import com.outoclick.app.ui.FloatingBallView
import com.outoclick.app.ui.FloatingMenu

/**
 * 前台服务：只在绑定的目标应用前台显示悬浮球。
 *
 * 交互：
 * - 停止：单击开始；长按打开操作边栏（编辑/方案列表/退出）。
 * - 运行中：单击暂停；长按停止（不打开编辑边栏）。
 * - 暂停：单击继续；长按停止。
 * - 编辑：画布 + 同一个操作边栏（添加/复用/删除/保存/完成）。
 * - 主页面/桌面不显示任何 overlay，避免挡住触摸。
 */
class AutomationService : Service() {

    companion object {
        @Volatile
        var running: Boolean = false
            private set

        const val ACTION_START = "com.outoclick.app.action.START"
        const val ACTION_EDIT = "com.outoclick.app.action.EDIT"
        const val ACTION_RUN = "com.outoclick.app.action.RUN"
        const val ACTION_PAUSE = "com.outoclick.app.action.PAUSE"
        const val ACTION_RESUME = "com.outoclick.app.action.RESUME"
        const val ACTION_STOP = "com.outoclick.app.action.STOP"
        const val ACTION_REPOST = "com.outoclick.app.action.REPOST"
        const val EXTRA_PLAN_ID = "plan_id"

        private const val CHANNEL_ID = "outoclick_overlay"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var wm: WindowManager
    private lateinit var repo: AppRepository
    private lateinit var sessions: SessionManager
    private lateinit var prefs: android.content.SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    private var ball: FloatingBallView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panel: FloatingMenu? = null
    private var panelMode = FloatingMenu.Mode.NORMAL
    private var panelWanted = false
    private var editor: EditorOverlay? = null
    private var editingPlanId: String? = null
    private var editorWanted = false

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
        wm = getSystemService(WindowManager::class.java)
        prefs = getSharedPreferences("outoclick_ui", MODE_PRIVATE)

        if (!Settings.canDrawOverlays(this)) {
            toast("缺少悬浮窗权限")
            stopSelf()
            return
        }

        createChannel()
        startForegroundCompat(notificationText())

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

        syncOverlays(ForegroundTracker.packageName)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat(notificationText())

        when (intent?.action) {
            ACTION_START -> {
                syncOverlays(ForegroundTracker.packageName)
                updateNotification()
            }

            ACTION_EDIT -> {
                val planId = intent.getStringExtra(EXTRA_PLAN_ID)
                if (planId != null) openEditor(planId) else toast("缺少方案")
            }

            ACTION_RUN -> startRun(intent.getStringExtra(EXTRA_PLAN_ID))

            ACTION_PAUSE -> sessions.pause(PauseReason.USER)

            ACTION_RESUME -> {
                if (!sessions.resume()) toast("请先切回目标应用并恢复方向")
            }

            ACTION_STOP -> sessions.stop(StopReason.USER)

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
        hideOverlays(clearEditing = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 前台应用 / overlay 显隐
    // ------------------------------------------------------------------

    private fun onForegroundChanged(packageName: String) {
        if (com.outoclick.app.engine.Guards.isImePackage(this, packageName)) return
        sessions.onForegroundPackage(packageName)
        syncOverlays(packageName)
    }

    private fun overlayPlan(): com.outoclick.app.model.Plan? {
        if (editorWanted) {
            editingPlanId?.let { id -> repo.read().plan(id)?.let { return it } }
        }
        return repo.read().activePlan()
    }

    private fun targetPackageOf(plan: com.outoclick.app.model.Plan?): String? =
        plan?.targetAppId?.let { appId -> repo.read().app(appId)?.packageName }

    private fun syncOverlays(foregroundPackage: String) {
        val plan = overlayPlan()
        val target = targetPackageOf(plan)
        val targetForeground = target != null && foregroundPackage == target

        if (!targetForeground) {
            // 主页面、桌面、其他 App：全部收起，不再挡住触摸。
            hideOverlays(clearEditing = false)
            updateNotification()
            return
        }

        // 绑定时前台是 outoclick 自己，方向不可信；目标 App 真正到前台时再校准。
        val app = plan?.targetAppId?.let { repo.read().app(it) }
        if (app != null && (app.boundDisplayW <= 0 || app.boundRotation < 0)) {
            val size = Screen.size(this)
            val rotation = Screen.rotation(this)
            repo.calibrateApp(app.id, rotation, size.x, size.y)
        }

        addBall()
        if (editorWanted && plan != null) {
            ensureEditor(plan.id)
            if (editor?.isShowing() != true) editor?.show()
            bringBallToFront()
            if (panelWanted && panel?.isShowing() != true) {
                showPanel(FloatingMenu.Mode.EDIT)
            } else if (!panelWanted) {
                panel?.dismiss()
                panel = null
            }
        } else {
            editor?.hide()
            if (panelWanted && panel?.isShowing() != true) {
                showPanel(FloatingMenu.Mode.NORMAL)
            } else if (!panelWanted) {
                panel?.dismiss()
                panel = null
            }
        }
        updateNotification()
    }

    private fun hideOverlays(clearEditing: Boolean) {
        panel?.dismiss()
        panel = null
        editor?.hide()
        removeBall()
        if (clearEditing) {
            editor = null
            editingPlanId = null
            editorWanted = false
            panelWanted = false
            panelMode = FloatingMenu.Mode.NORMAL
        }
    }

    // ------------------------------------------------------------------
    // 悬浮球
    // ------------------------------------------------------------------

    private fun addBall() {
        if (ball != null) return
        val size = dp(48)
        val screen = Screen.size(this)
        val view = FloatingBallView(this).apply {
            state = ballState()
            stateColor = stateColor()
            onTap = { onBallTap() }
            onLongPress = { onBallLongPress() }
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

    private fun ballState(): BallState = when {
        sessions.isRunning() -> BallState.RUNNING
        sessions.isPaused() -> BallState.PAUSED
        else -> BallState.IDLE
    }

    private fun stateColor(): Int = when {
        sessions.isRunning() -> Palette.GREEN
        sessions.isPaused() -> Palette.AMBER
        else -> Palette.MUTED
    }

    private fun refreshBall() {
        ball?.state = ballState()
        ball?.stateColor = stateColor()
    }

    private fun onBallTap() {
        when {
            editorWanted && editor?.isShowing() == true -> togglePanel()
            sessions.isRunning() -> sessions.pause(PauseReason.USER)
            sessions.isPaused() -> {
                if (!sessions.resume()) toast("请先切回目标应用并恢复方向")
            }
            else -> startRun(repo.read().activePlan()?.id)
        }
        refreshUi()
    }

    private fun onBallLongPress() {
        if (sessions.isActive()) {
            // 运行中长按 = 停止，不打开编辑边栏。
            sessions.stop(StopReason.USER)
            toast("已停止")
        } else {
            togglePanel()
        }
        refreshUi()
    }

    // ------------------------------------------------------------------
    // 操作边栏 / 编辑
    // ------------------------------------------------------------------

    private fun togglePanel() {
        val existing = panel
        if (existing?.isShowing() == true) {
            existing.dismiss()
            panel = null
            panelWanted = false
            return
        }
        panelMode = if (editorWanted && editor?.isShowing() == true) {
            FloatingMenu.Mode.EDIT
        } else {
            FloatingMenu.Mode.NORMAL
        }
        panelWanted = true
        showPanel(panelMode)
    }

    private fun showPanel(mode: FloatingMenu.Mode) {
        val params = ballParams ?: return
        val view = ball ?: return
        val plan = if (mode == FloatingMenu.Mode.EDIT) {
            editingPlanId?.let { repo.read().plan(it) }
        } else {
            repo.read().activePlan()
        } ?: run {
            toast("先选择一个方案")
            return
        }

        if (mode == FloatingMenu.Mode.EDIT) {
            ensureEditor(plan.id)
        }

        panel?.dismiss()
        panelMode = mode
        val targetLabel = plan.targetAppId?.let { repo.read().app(it)?.label } ?: "未绑定目标应用"
        val menu = FloatingMenu(this, wm, object : FloatingMenu.Callbacks {
            override fun onPrimary() {
                dismissPanel()
                when {
                    sessions.isRunning() -> sessions.pause(PauseReason.USER)
                    sessions.isPaused() -> {
                        if (!sessions.resume()) toast("请先切回目标应用并恢复方向")
                    }
                    else -> startRun(plan.id)
                }
            }

            override fun onStop() {
                dismissPanel()
                sessions.stop(StopReason.USER)
            }

            override fun onEdit() {
                dismissPanel()
                openEditor(plan.id)
            }

            override fun onAddPoint() {
                ensureEditor(plan.id)
                editor?.addPoint()
                refreshPanel()
            }

            override fun onReusePoint() {
                ensureEditor(plan.id)
                editor?.showReusePicker()
            }

            override fun onDeletePoint() {
                editor?.deleteSelected()
            }

            override fun onSave() {
                ensureEditor(plan.id)
                editor?.showSavePanel()
            }

            override fun onDoneEdit() {
                finishEdit()
            }

            override fun onOpenPlans() {
                openPlans()
            }

            override fun onCalibrate() {
                dismissPanel()
                calibrateActiveApp()
            }

            override fun onExit() {
                if (sessions.isActive()) sessions.stop(StopReason.USER)
                stopSelf()
            }
        })
        panel = menu
        menu.show(
            anchorX = params.x,
            anchorY = params.y,
            ballWidth = view.width,
            ballHeight = view.height,
            mode = mode,
            status = when {
                sessions.isRunning() -> "运行中"
                sessions.isPaused() -> "已暂停"
                else -> "就绪"
            },
            planName = plan.name,
            targetLabel = targetLabel,
            active = true,
            running = sessions.isRunning(),
            paused = sessions.isPaused(),
            hasSelection = editor?.hasSelection() == true,
        )
    }

    private fun dismissPanel() {
        panel?.dismiss()
        panel = null
        panelWanted = false
    }

    private fun refreshPanel() {
        if (panel?.isShowing() == true && ball != null) {
            showPanel(panelMode)
        }
    }

    private fun calibrateActiveApp() {
        val plan = overlayPlan() ?: return
        val app = plan.targetAppId?.let { repo.read().app(it) } ?: return
        if (ForegroundTracker.packageName != app.packageName) {
            toast("请先切到目标应用再校准")
            return
        }
        val size = Screen.size(this)
        val rotation = Screen.rotation(this)
        repo.calibrateApp(app.id, rotation, size.x, size.y)
        toast("已按当前方向校准：${size.x}×${size.y}")
        updateNotification()
    }

    private fun ensureEditor(planId: String) {
        if (editor == null || editingPlanId != planId) {
            editor?.hide()
            editingPlanId = planId
            editorWanted = true
            editor = EditorOverlay(
                context = this,
                wm = wm,
                repo = repo,
                planId = planId,
                onChanged = { refreshPanel() },
            )
        }
        if (editor?.isShowing() != true) {
            editor?.show()
        }
        bringBallToFront()
    }

    private fun openEditor(planId: String) {
        val plan = repo.read().plan(planId) ?: run {
            toast("方案不存在")
            return
        }
        if (plan.targetAppId == null) {
            toast("请先在方案列表绑定目标应用")
            openPlans()
            return
        }
        repo.setActivePlan(planId)
        editingPlanId = planId
        editorWanted = true
        panelWanted = true
        panelMode = FloatingMenu.Mode.EDIT
        syncOverlays(ForegroundTracker.packageName)
        if (ForegroundTracker.packageName != targetPackageOf(plan)) {
            toast("已进入编辑，切到目标应用后显示工具条")
        }
        updateNotification()
    }

    private fun finishEdit() {
        dismissPanel()
        editor?.hide()
        editor = null
        editingPlanId = null
        editorWanted = false
        refreshBall()
        updateNotification()
    }

    private fun openPlans() {
        hideOverlays(clearEditing = true)
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
        dismissPanel()
        editor?.hide()
        editor = null
        editingPlanId = null
        editorWanted = false
        mainHandler.postDelayed({
            if (!running) return@postDelayed
            when (val result = sessions.start(id)) {
                is StartResult.Failure -> toast(result.message)
                StartResult.Success -> {
                    addBall()
                    refreshUi()
                    toast("运行中：单击暂停，长按停止")
                }
            }
        }, 400L)
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
            description = "运行状态、暂停、继续与停止"
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
        val pause = servicePending(11, ACTION_PAUSE)
        val resume = servicePending(12, ACTION_RESUME)
        val stop = servicePending(13, ACTION_STOP)
        val repost = servicePending(14, ACTION_REPOST)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_target)
            .setContentTitle("outoclick")
            .setContentText(lastNotificationText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setDeleteIntent(repost)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        when {
            sessions.isRunning() -> {
                builder.addAction(0, "暂停", pause)
                builder.addAction(0, "停止", stop)
            }
            sessions.isPaused() -> {
                builder.addAction(0, "继续", resume)
                builder.addAction(0, "停止", stop)
            }
            else -> builder.addAction(0, "方案列表", open)
        }
        return builder.build()
    }

    private fun servicePending(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, AutomationService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

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
        val plan = repo.read().activePlan() ?: return "就绪 · 未选择方案"
        val target = targetPackageOf(plan)
        return if (target != null && ForegroundTracker.packageName == target) {
            "就绪 · ${plan.name}"
        } else {
            "等待目标应用 · ${plan.name}"
        }
    }

    private fun statusText(): String {
        if (sessions.isActive()) return sessions.statusText()
        val plan = repo.read().activePlan() ?: return "未选择方案"
        return "就绪 · ${plan.name}"
    }

    private fun refreshUi() {
        refreshBall()
        updateNotification()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        runCatching {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
