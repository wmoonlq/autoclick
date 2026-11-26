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
        content.addView(statusCard())
        content.addView(riskCard())
        content.addView(guideCard())
        content.addView(permissionCard())
        content.addView(plansCard())
        content.addView(overlayCard())
        content.addView(footer())

        scroll.post { scroll.scrollTo(0, scrollY) }
    }

    private fun header(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(tv("outoclick", 28f, Palette.TEXT, true))
        addView(tv("点击器功能实验室 · v${versionName()}", 12f, Palette.MUTED).apply {
            setPadding(0, dp(4), 0, 0)
        })
        addView(tv("固定点位编排 · 目标应用守卫 · 常驻急停", 12f, Palette.DIM).apply {
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun statusCard(): View {
        val card = card()
        val active = sessions.currentSession()
        val status = sessions.statusText()
        val progress = sessions.progressText()
        val title = tv(
            if (active != null && !active.status.isTerminal) "运行会话" else "当前状态",
            12f,
            Palette.MUTED,
        )
        card.addView(title)
        card.addView(tv(status, 17f, Palette.TEXT, true).apply {
            setPadding(0, dp(6), 0, 0)
        })
        if (active != null) {
            card.addView(tv(progress, 11.5f, Palette.MUTED).apply {
                setPadding(0, dp(4), 0, 0)
            })
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        if (sessions.isRunning()) {
            row.addView(btn("暂停", Palette.CARD_2, Palette.TEXT) { sessions.pause() }, weight(1f))
            row.addView(btn("停止", Palette.RED, Palette.TEXT) {
                sessions.stop()
            }, weight(1f))
        } else if (sessions.isPaused()) {
            row.addView(btn("继续", Palette.PRIMARY, Palette.ON_PRIMARY) {
                if (!sessions.resume()) toast("请先切回目标应用并恢复方向")
            }, weight(1f))
            row.addView(btn("停止", Palette.RED, Palette.TEXT) {
                sessions.stop()
            }, weight(1f))
        } else {
            val activePlan = repo.read().activePlan()
            row.addView(btn("启动悬浮球", Palette.PRIMARY, Palette.ON_PRIMARY) {
                startOverlay(ACTION_START)
            }, weight(1f))
            if (activePlan != null) {
                row.addView(btn("编辑当前方案", Palette.CARD_2, Palette.TEXT) {
                    startEdit(activePlan.id)
                }, weight(1f))
            }
        }
        card.addView(row)
        return wrap(card)
    }

    private fun riskCard(): View {
        val card = card()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(tv("⚠ 部分游戏存在封号风险", 12.5f, Palette.AMBER).apply {
            setPadding(0, 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(tv("更多 ›", 12f, Palette.MUTED).apply {
            setPadding(dp(10), dp(4), 0, dp(4))
            setOnClickListener { showRiskDialog() }
        })
        card.addView(row)
        return wrap(card)
    }

    private fun guideCard(): View {
        val card = card()
        card.addView(tv("点击器新手指南", 15f, Palette.TEXT, true))
        card.addView(tv("三步开始：权限 → 建方案 → 悬浮球运行", 11.5f, Palette.MUTED).apply {
            setPadding(0, dp(4), 0, dp(8))
        })
        card.addView(btn("查看详情", Palette.CARD_2, Palette.TEXT) { showGuideDialog() })
        return wrap(card)
    }

    private fun permissionCard(): View {
        val card = card()
        card.addView(tv("准备", 13f, Palette.MUTED, true))
        card.addView(permissionRow(
            title = "无障碍服务",
            desc = "手势注入，不读取屏幕内容",
            granted = accessibilityEnabled(),
            action = "去开启",
        ) {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            toast("Android 13+ 侧载应用需先在应用信息里允许受限设置")
        })
        card.addView(permissionRow(
            title = "悬浮窗",
            desc = "悬浮球、工具条、编辑画布",
            granted = Settings.canDrawOverlays(this),
            action = "去授权",
        ) {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    )
                )
            }
        })
        card.addView(permissionRow(
            title = "通知",
            desc = "前台服务状态；被拒绝时用悬浮球停止",
            granted = NotificationManagerCompat.from(this).areNotificationsEnabled(),
            action = "去授权",
        ) {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            } else {
                openNotificationSettings()
            }
        })
        card.addView(permissionRow(
            title = "电池优化",
            desc = "建议加入白名单，降低服务被杀概率",
            granted = batteryIgnored(),
            action = "去设置",
        ) {
            requestBatteryWhitelist()
        })
        return wrap(card)
    }

    private fun plansCard(): View {
        val card = card()
        val state = repo.read()
        val plans = state.plans.filter { !it.deleted }.sortedByDescending { it.updatedAt }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(tv("我的方案（${plans.size}/10）", 15f, Palette.TEXT, true), LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f,
        ))
        header.addView(btn("＋ 创建方案", Palette.PRIMARY, Palette.ON_PRIMARY) {
            createPlan()
        })
        card.addView(header)

        if (plans.isEmpty()) {
            card.addView(tv("还没有方案。创建一个，然后在目标应用上绑定并添加点位。", 12f, Palette.MUTED).apply {
                setPadding(0, dp(12), 0, 0)
            })
            return wrap(card)
        }

        plans.forEach { plan ->
            card.addView(planRow(plan))
        }
        return wrap(card)
    }

    private fun planRow(plan: Plan): View {
        val state = repo.read()
        val app = state.app(plan.targetAppId)
        val active = state.settings.activePlanId == plan.id
        val session = sessions.currentSession()?.takeIf { it.planId == plan.id }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, dp(2))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(tv(plan.name, 16f, Palette.TEXT, true), LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f,
        ))
        top.addView(statusPill(plan))
        box.addView(top)

        val target = app?.let { "${it.label} · ${it.packageName}" } ?: "未绑定目标应用"
        box.addView(tv(target, 11f, if (app == null) Palette.AMBER else Palette.MUTED).apply {
            setPadding(0, dp(4), 0, 0)
        })
        box.addView(tv(
            "${plan.steps.size} 个点位 · 循环 ${plan.loopCount} 轮 · v${plan.version}",
            11f,
            Palette.DIM,
        ).apply {
            setPadding(0, dp(3), 0, 0)
        })

        if (session != null && session.status.isActive) {
            box.addView(tv(
                "${sessionStatusLabel(session.status)} · ${sessions.progressText()}",
                11f,
                if (session.status == SessionStatus.PAUSED) Palette.AMBER else Palette.GREEN,
            ).apply { setPadding(0, dp(4), 0, 0) })
        }
        if (plan.steps.size > 30) {
            box.addView(tv("超过 30 个点位，运行前需要删减", 11f, Palette.RED).apply {
                setPadding(0, dp(4), 0, 0)
            })
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(4))
        }
        actions.addView(
            tv(if (active) "当前方案 ✓" else "设为当前", 11f, if (active) Palette.ON_PRIMARY else Palette.MUTED).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = roundedRect(
                    if (active) Palette.PRIMARY else Palette.CARD_2,
                    dp(12),
                    Palette.BORDER,
                    dp(1),
                )
                setOnClickListener {
                    repo.setActivePlan(if (active) null else plan.id)
                    render()
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(8)
            },
        )
        actions.addView(btn("编辑", Palette.CARD_2, Palette.TEXT) { startEdit(plan.id) }, weight(1f))
        actions.addView(btn("运行", Palette.CARD_2, Palette.TEXT) { prepareRun(plan.id) }, weight(1f))
        actions.addView(btn("删除", Palette.CARD_2, Palette.RED) { confirmDelete(plan) }, weight(1f))
        box.addView(actions)
        return box
    }

    private fun overlayCard(): View {
        val card = card()
        card.addView(tv("悬浮球", 13f, Palette.MUTED, true))
        card.addView(tv(
            if (AutomationService.running) {
                "悬浮球运行中。运行中单击悬浮球立即停止；长按打开菜单。"
            } else {
                "启动后悬浮球常驻，不会自动隐藏；编辑工具条可以由它显示/隐藏。"
            },
            12f,
            if (AutomationService.running) Palette.GREEN else Palette.MUTED,
        ).apply { setPadding(0, dp(6), 0, dp(10)) })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (AutomationService.running) {
            row.addView(btn("停止悬浮球", Palette.RED, Palette.TEXT) {
                stopService(Intent(this@MainActivity, AutomationService::class.java))
                render()
            }, weight(1f))
        } else {
            row.addView(btn("启动悬浮球", Palette.PRIMARY, Palette.ON_PRIMARY) {
                startOverlay(ACTION_START)
            }, weight(1f))
        }
        row.addView(btn("无障碍设置", Palette.CARD_2, Palette.TEXT) {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }, weight(1f))
        card.addView(row)
        return wrap(card)
    }

    private fun footer(): View = tv(
        "所有方案与会话只存在本机 SQLite · App 不联网 · 无障碍服务不读取屏幕内容",
        10.5f,
        Palette.DIM,
    ).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(18), 0, 0)
    }

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
        if (!Settings.canDrawOverlays(this)) {
            showPermissionDialog("需要悬浮窗权限", "编辑工具条要覆盖在目标应用上，请先授权。") {
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
        toast("已打开编辑工具条，请切到目标应用")
    }

    private fun prepareRun(planId: String) {
        if (!Settings.canDrawOverlays(this)) {
            showPermissionDialog("需要悬浮窗权限", "运行控制依赖常驻悬浮球，请先授权。") {
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
        toast("已设为当前方案。切到目标应用后，点悬浮球选择「开始当前方案」")
        render()
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
            toast("悬浮球已启动")
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
