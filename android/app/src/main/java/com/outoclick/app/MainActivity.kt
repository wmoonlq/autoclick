package com.outoclick.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.outoclick.app.data.AppRepository
import com.outoclick.app.engine.ForegroundTracker
import com.outoclick.app.engine.SessionManager
import com.outoclick.app.model.Plan
import com.outoclick.app.model.PlanStatus
import com.outoclick.app.model.SessionStatus

class MainActivity : AppCompatActivity() {

    private lateinit var repo: AppRepository
    private lateinit var sessions: SessionManager
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private var lastSessionRender = 0L

    private val repoListener: () -> Unit = {
        runOnUiThread { render() }
    }
    private val sessionListener: () -> Unit = {
        runOnUiThread {
            val now = System.currentTimeMillis()
            if (now - lastSessionRender > 700) {
                lastSessionRender = now
                render()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(this)
        repo = AppGraph.repo
        sessions = AppGraph.sessions

        scroll = ScrollView(this).apply {
            setBackgroundColor(Palette.BG)
            isFillViewport = true
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        scroll.addView(content)
        setContentView(scroll)
        render()
    }

    override fun onStart() {
        super.onStart()
        repo.addListener(repoListener)
        sessions.addUpdateListener(sessionListener)
        // overlay 事件被无障碍服务忽略，MainActivity 回到前台时主动上报，
        // 保证运行中的会话会因为“离开目标应用”而暂停。
        ForegroundTracker.onPackage(packageName)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onStop() {
        repo.removeListener(repoListener)
        sessions.removeUpdateListener(sessionListener)
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    // ------------------------------------------------------------------
    // 页面
    // ------------------------------------------------------------------

    private fun render() {
        val scrollY = scroll.scrollY
        content.removeAllViews()

        content.addView(header())
        content.addView(statusSection())
        content.addView(plansSection())
        content.addView(permissionLine())
        content.addView(footer())

        scroll.post { scroll.scrollTo(0, scrollY) }
    }

    private fun header(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val row = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            tv("outoclick", 26f, Palette.TEXT, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(tv("⋯", 22f, Palette.MUTED).apply {
            setPadding(dp(14), dp(2), dp(4), dp(8))
            setOnClickListener { moreMenu() }
        })
        addView(row)
        addView(tv("点击器功能实验室 · v${versionName()}", 11f, Palette.MUTED).apply {
            setPadding(0, dp(2), 0, 0)
        })
    }

    private fun statusSection(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(Palette.CARD, dp(22))
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        val session = sessions.currentSession()
        val activeSession = session != null && !session.status.isTerminal
        val plan = repo.read().activePlan()
        val dotColor = when {
            sessions.isRunning() -> Palette.PRIMARY
            sessions.isPaused() -> Palette.AMBER
            else -> Palette.DIM
        }
        val title = when {
            activeSession -> sessions.statusText()
            plan != null -> "就绪"
            else -> "开始使用"
        }
        val subtitle = when {
            activeSession -> sessions.progressText()
            plan != null -> {
                val app = repo.read().app(plan.targetAppId)
                val target = app?.label ?: "未绑定目标应用"
                "${plan.name} · $target"
            }
            else -> "创建方案，绑定目标应用，切到目标应用后点悬浮球"
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(tv("●", 10f, dotColor).apply { setPadding(0, 0, dp(8), 0) })
        titleRow.addView(
            tv(title, 17f, Palette.TEXT, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        box.addView(titleRow)
        box.addView(tv(subtitle, 11.5f, Palette.MUTED).apply {
            setPadding(dp(18), dp(4), 0, 0)
            maxLines = 2
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        when {
            sessions.isRunning() -> {
                actions.addView(bigBtn("暂停", Palette.PRIMARY_SOFT, Palette.PRIMARY) {
                    sessions.pause()
                }, bigWeight(1f))
                actions.addView(bigBtn("停止", Palette.CARD_2, Palette.RED) {
                    sessions.stop()
                }, bigWeight(0.7f))
            }

            sessions.isPaused() -> {
                actions.addView(bigBtn("继续", Palette.PRIMARY_SOFT, Palette.PRIMARY) {
                    if (!sessions.resume()) toast("请先切回目标应用并恢复方向")
                }, bigWeight(1f))
                actions.addView(bigBtn("停止", Palette.CARD_2, Palette.RED) {
                    sessions.stop()
                }, bigWeight(0.7f))
            }

            plan == null -> {
                actions.addView(bigBtn("创建方案", Palette.PRIMARY_SOFT, Palette.PRIMARY) {
                    createPlan()
                }, bigWeight(1f))
            }

            plan.targetAppId == null -> {
                actions.addView(bigBtn("绑定目标应用", Palette.PRIMARY_SOFT, Palette.PRIMARY) {
                    showBindPicker(plan, thenEdit = false)
                }, bigWeight(1f))
            }

            else -> {
                actions.addView(bigBtn("启动服务", Palette.PRIMARY_SOFT, Palette.PRIMARY) {
                    prepareRun(plan.id)
                }, bigWeight(1f))
                actions.addView(bigBtn("编辑", Palette.CARD_2, Palette.TEXT) {
                    startEdit(plan.id)
                }, bigWeight(0.7f))
            }
        }
        box.addView(actions)
        return wrap(box)
    }

    private fun plansSection(): View {
        val plans = repo.read().plans.filter { !it.deleted }.sortedByDescending { it.updatedAt }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(2))
        }
        header.addView(
            tv("我的方案", 13f, Palette.MUTED),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(tv("${plans.size}/10", 11f, Palette.DIM).apply {
            setPadding(0, 0, dp(12), 0)
        })
        header.addView(tv("＋", 22f, Palette.PRIMARY).apply {
            setPadding(dp(8), 0, dp(4), dp(4))
            setOnClickListener { createPlan() }
        })
        box.addView(header)

        if (plans.isEmpty()) {
            box.addView(tv("还没有方案，点右上角＋创建", 12f, Palette.MUTED).apply {
                setPadding(0, dp(12), 0, 0)
            })
            return wrap(box)
        }
        plans.forEachIndexed { index, plan ->
            box.addView(planRow(plan))
            if (index < plans.lastIndex) {
                box.addView(View(this).apply {
                    setBackgroundColor(Palette.BORDER)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(1),
                    )
                })
            }
        }
        return wrap(box)
    }

    private fun planRow(plan: Plan): View {
        val state = repo.read()
        val app = state.app(plan.targetAppId)
        val active = state.settings.activePlanId == plan.id
        val session = sessions.currentSession()?.takeIf { it.planId == plan.id }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
            isClickable = true
            setOnClickListener { planMenu(plan) }
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (active) {
            nameRow.addView(tv("●", 9f, Palette.PRIMARY).apply { setPadding(0, 0, dp(6), 0) })
        }
        nameRow.addView(tv(plan.name, 15f, Palette.TEXT, true))
        left.addView(nameRow)
        val target = app?.label ?: "未绑定目标应用"
        val suffix = buildString {
            append(target)
            append(" · ${plan.steps.size} 点 · ${plan.loopCount} 轮")
            if (plan.status == PlanStatus.DRAFT) append(" · 草稿")
            if (session != null && session.status.isActive) {
                append(" · ")
                append(sessionStatusLabel(session.status))
            }
        }
        left.addView(tv(suffix, 11f, if (app == null) Palette.AMBER else Palette.MUTED).apply {
            setPadding(if (active) dp(16) else 0, dp(4), 0, 0)
            maxLines = 2
        })
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(tv("⋯", 20f, Palette.DIM).apply {
            setPadding(dp(12), 0, dp(2), dp(6))
        })
        return row
    }

    private fun planMenu(plan: Plan) {
        val active = repo.read().settings.activePlanId == plan.id
        val items = mutableListOf<String>()
        items.add("编辑点位")
        items.add("运行")
        items.add(if (active) "取消当前方案" else "设为当前方案")
        items.add("绑定 / 更换目标应用")
        items.add("删除方案")
        AlertDialog.Builder(this)
            .setTitle(plan.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> startEdit(plan.id)
                    1 -> prepareRun(plan.id)
                    2 -> {
                        repo.setActivePlan(if (active) null else plan.id)
                        render()
                    }
                    3 -> showBindPicker(plan, thenEdit = false)
                    4 -> confirmDelete(plan)
                }
            }
            .show()
    }

    private fun permissionLine(): View {
        val a11y = accessibilityEnabled()
        val overlay = Settings.canDrawOverlays(this)
        val notify = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val ok = a11y && overlay && notify
        val missing = buildList {
            if (!a11y) add("无障碍")
            if (!overlay) add("悬浮窗")
            if (!notify) add("通知")
        }
        val text = if (ok) "权限已就绪" else "${missing.joinToString("、")}待开启"
        return tv("● $text", 11f, if (ok) Palette.PRIMARY else Palette.AMBER).apply {
            setPadding(0, dp(20), 0, 0)
            setOnClickListener { permissionDialog() }
        }
    }

    private fun permissionDialog() {
        val a11y = accessibilityEnabled()
        val overlay = Settings.canDrawOverlays(this)
        val notify = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val battery = batteryIgnored()
        val message = buildString {
            append("无障碍：").append(if (a11y) "已开启" else "未开启").append("\n")
            append("悬浮窗：").append(if (overlay) "已开启" else "未开启").append("\n")
            append("通知：").append(if (notify) "已开启" else "未开启").append("\n")
            append("电池优化：").append(if (battery) "已加入白名单" else "未加入")
        }
        AlertDialog.Builder(this)
            .setTitle("权限检查")
            .setMessage(message)
            .setPositiveButton("去设置") { _, _ ->
                when {
                    !a11y -> runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    !overlay -> runCatching {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName"),
                            )
                        )
                    }
                    !notify -> if (Build.VERSION.SDK_INT >= 33) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
                    } else {
                        openNotificationSettings()
                    }
                    else -> requestBatteryWhitelist()
                }
            }
            .setNeutralButton("电池优化") { _, _ -> requestBatteryWhitelist() }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun moreMenu() {
        val items = mutableListOf("权限检查", "使用风险", "新手指南")
        if (AutomationService.running) items.add("停止服务")
        AlertDialog.Builder(this)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "权限检查" -> permissionDialog()
                    "使用风险" -> showRiskDialog()
                    "新手指南" -> showGuideDialog()
                    "停止服务" -> {
                        stopService(Intent(this, AutomationService::class.java))
                        render()
                    }
                }
            }
            .show()
    }

    private fun footer(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(22), 0, 0)
        }
        row.addView(tv("使用风险", 10.5f, Palette.MUTED).apply {
            setOnClickListener { showRiskDialog() }
        })
        row.addView(tv("  ·  ", 10.5f, Palette.DIM))
        row.addView(tv("新手指南", 10.5f, Palette.MUTED).apply {
            setOnClickListener { showGuideDialog() }
        })
        row.addView(
            tv("  ·  本机存储 · v${versionName()}", 10.5f, Palette.DIM),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        return row
    }

    private fun bigBtn(
        text: String,
        bg: Int,
        fg: Int,
        onClick: () -> Unit,
    ) = btn(text, bg, fg, onClick).apply {
        background = roundedRect(bg, dp(22))
        minHeight = dp(46)
        textSize = 14f
    }

    private fun bigWeight(value: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, dp(46), value).apply { rightMargin = dp(8) }

    // ------------------------------------------------------------------
    // 操作
    // ------------------------------------------------------------------

    private fun createPlan() {
        if (repo.planCount() >= 10) {
            toast("最多 10 个方案，请先归档或删除")
            return
        }
        val plan = repo.createPlan()
        if (plan == null) {
            toast("创建失败")
            return
        }
        repo.setActivePlan(plan.id)
        render()
        startEdit(plan.id)
    }

    private fun startEdit(planId: String) {
        val plan = repo.read().plan(planId) ?: return
        if (plan.targetAppId == null) {
            showBindPicker(plan, thenEdit = true)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            showPermissionDialog("需要悬浮窗权限", "编辑画布要覆盖在目标应用上，请先授权。") {
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName"),
                        )
                    )
                }
            }
            return
        }
        repo.setActivePlan(planId)
        val intent = Intent(this, AutomationService::class.java)
            .setAction(ACTION_EDIT)
            .putExtra(EXTRA_PLAN_ID, planId)
        ContextCompat.startForegroundService(this, intent)
        toast("已进入编辑；切到目标应用后显示悬浮球和操作边栏")
    }

    private fun prepareRun(planId: String) {
        val plan = repo.read().plan(planId) ?: return
        if (plan.targetAppId == null) {
            showBindPicker(plan, thenEdit = false)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            showPermissionDialog("需要悬浮窗权限", "运行控制依赖目标应用上的悬浮球，请先授权。") {
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName"),
                        )
                    )
                }
            }
            return
        }
        if (!accessibilityEnabled()) {
            showPermissionDialog("需要无障碍服务", "点击注入依赖无障碍服务，请先开启。") {
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
            return
        }
        repo.setActivePlan(planId)
        startOverlay(ACTION_START)
        toast("服务已启动；切到目标应用后悬浮球才会出现")
        render()
    }

    /**
     * 先绑定目标应用再进入编辑/运行；悬浮球只在目标应用前台显示。
     */
    private fun showBindPicker(plan: Plan, thenEdit: Boolean) {
        val apps = AppGraph.resolver.launchableApps()
        if (apps.isEmpty()) {
            toast("没有找到可绑定的应用")
            return
        }
        val labels = apps.map { "${it.label}\n${it.packageName}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择目标应用")
            .setItems(labels) { _, which ->
                val app = apps[which]
                // 方向/尺寸不在这里记：当前前台是 outoclick，不是目标应用。
                // 目标应用真正到前台时由 AutomationService 自动校准。
                repo.bindPlanToApp(
                    planId = plan.id,
                    packageName = app.packageName,
                    label = app.label,
                )
                repo.setActivePlan(plan.id)
                toast("已绑定：${app.label}")
                render()
                if (thenEdit) {
                    startEdit(plan.id)
                } else {
                    startOverlay(ACTION_START)
                    toast("服务已启动；切到目标应用后悬浮球才会出现")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(plan: Plan) {
        AlertDialog.Builder(this)
            .setTitle("删除方案")
            .setMessage("删除「${plan.name}」？运行中的会话会先停止。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                val session = sessions.currentSession()
                if (session != null && session.planId == plan.id && session.status.isActive) {
                    sessions.stop()
                }
                repo.deletePlan(plan.id)
                render()
            }
            .show()
    }

    private fun startOverlay(action: String) {
        if (!Settings.canDrawOverlays(this)) {
            showPermissionDialog("需要悬浮窗权限", "悬浮球要显示在其他应用上层，请先授权。") {
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName"),
                        )
                    )
                }
            }
            return
        }
        val intent = Intent(this, AutomationService::class.java).setAction(action)
        runCatching {
            ContextCompat.startForegroundService(this, intent)
            toast("服务已启动；悬浮球只在目标应用前台显示")
        }.onFailure {
            toast("启动失败：${it.message}")
        }
        render()
    }

    // ------------------------------------------------------------------
    // 权限
    // ------------------------------------------------------------------

    private fun accessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val full = "$packageName/${ClickAccessibilityService::class.java.name}"
        val short = "$packageName/.ClickAccessibilityService"
        return enabled.split(':').any { it.equals(full, true) || it.equals(short, true) }
    }

    private fun batteryIgnored(): Boolean {
        val power = getSystemService(PowerManager::class.java) ?: return true
        return power.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryWhitelist() {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        runCatching { startActivity(intent) }
    }

    private fun showPermissionDialog(title: String, message: String, onGo: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("去设置") { _, _ -> onGo() }
            .show()
    }

    private fun showRiskDialog() {
        AlertDialog.Builder(this)
            .setTitle("使用风险")
            .setMessage(
                "自动化操作可能违反目标游戏的用户协议，存在封号风险。\n\n" +
                    "本工具只提供固定坐标点击与参数随机化，不保证规避任何检测，也不对账号风险负责。" +
                    "请仅在允许自动化的范围内使用。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun showGuideDialog() {
        AlertDialog.Builder(this)
            .setTitle("三步开始")
            .setMessage(
                "1. 开启无障碍、悬浮窗、通知权限。\n\n" +
                    "2. 创建方案，在目标应用上绑定应用并添加点位。\n\n" +
                    "3. 切到目标应用，点悬浮球 → 开始当前方案。\n\n" +
                    "运行中单击悬浮球立即停止；长按打开菜单。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    // ------------------------------------------------------------------
    // UI helpers
    // ------------------------------------------------------------------

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedRect(Palette.CARD, dp(18), Palette.BORDER, dp(1))
        setPadding(dp(14), dp(14), dp(14), dp(14))
    }

    private fun wrap(view: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(view, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        setPadding(0, dp(12), 0, 0)
    }

    private fun weight(value: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, dp(42), value).apply {
            rightMargin = dp(6)
        }

    private fun statusPill(plan: Plan): TextView {
        val (text, color) = when (plan.status) {
            PlanStatus.DRAFT -> "草稿" to Palette.AMBER
            PlanStatus.SAVED -> "已保存" to Palette.GREEN
            PlanStatus.ARCHIVED -> "已归档" to Palette.DIM
        }
        return pill(text, Palette.CARD_2, color)
    }

    private fun sessionStatusLabel(status: SessionStatus): String = when (status) {
        SessionStatus.RUNNING -> "运行中"
        SessionStatus.PAUSED -> "已暂停"
        SessionStatus.PENDING -> "准备中"
        SessionStatus.FINISHED -> "已完成"
        SessionStatus.STOPPED -> "已停止"
        SessionStatus.FAILED -> "失败"
    }

    private fun permissionRow(
        title: String,
        desc: String,
        granted: Boolean,
        action: String,
        onClick: () -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv(title, 14f, Palette.TEXT, true))
            addView(tv(desc, 10.5f, Palette.DIM).apply { setPadding(0, dp(2), 0, 0) })
        }
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            tv(if (granted) "已开启" else action, 11.5f, if (granted) Palette.GREEN else Palette.AMBER).apply {
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = roundedRect(Palette.CARD_2, dp(12), Palette.BORDER, dp(1))
                setOnClickListener { if (!granted) onClick() }
            },
        )
        return row
    }

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val ACTION_START = AutomationService.ACTION_START
        private const val ACTION_EDIT = AutomationService.ACTION_EDIT
        private const val EXTRA_PLAN_ID = AutomationService.EXTRA_PLAN_ID
    }
}
