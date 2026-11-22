package com.outoclick.app

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val scope = MainScope()
    private lateinit var store: ScriptStore
    private var script: Script? = null
    private lateinit var spinner: Spinner
    private lateinit var stepsBox: LinearLayout
    private lateinit var stepsHeader: TextView
    private lateinit var scriptInfo: TextView
    private lateinit var accStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var notifStatus: TextView
    private var guardSpinner = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ScriptStore(this)
        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.BG
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContentView(buildRoot())
        script = store.ensureSeed()
        rebuildSpinner()
        refreshSteps()
        refreshPermissions()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        val activeId = store.activeId()
        if (activeId != null) {
            val fresh = store.load(activeId)
            if (fresh != null) {
                script = fresh
                rebuildSpinner()
                refreshSteps()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------
    private fun buildRoot(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Palette.BG)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(16), dp(8) + bars.top, dp(16), dp(32) + bars.bottom)
            insets
        }
        scroll.addView(root)

        root.addView(tv("outoclick", 26f, Palette.TEXT, bold = true))
        root.addView(tv("安卓固定坐标自动点击器 · MVP", 12f, Palette.MUTED).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        })

        root.addTop(permissionCard(), 18)
        root.addTop(scriptCard(), 12)
        root.addTop(stepsCard(), 12)
        root.addTop(runCard(), 12)
        return scroll
    }

    private fun LinearLayout.addTop(view: View, top: Int) {
        addView(
            view,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(top) }
        )
    }

    private fun permissionCard(): LinearLayout {
        val card = card()
        card.addView(tv("准备", 16f, Palette.TEXT, bold = true))
        card.addView(tv("无障碍用于执行手势，悬浮窗用于取点与控制。全部在本机运行。", 11.5f, Palette.MUTED).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        })
        accStatus = permissionRow(card, "无障碍服务", "执行点击、长按、滑动", "去开启") {
            openAccessibility()
        }
        overlayStatus = permissionRow(card, "悬浮窗", "准星取点 + 贴边悬浮条", "去开启") {
            openOverlaySettings()
        }
        notifStatus = permissionRow(card, "通知", "前台运行状态", "去开启") {
            requestNotifications()
        }
        val prepare = btn("一键检查并开启", Palette.PRIMARY, Palette.ON_PRIMARY) { prepareAll() }
        card.addView(prepare, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })
        return card
    }

    private fun permissionRow(
        parent: LinearLayout,
        title: String,
        desc: String,
        action: String,
        onClick: () -> Unit,
    ): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(title, 14f, Palette.TEXT))
        col.addView(tv(desc, 10.5f, Palette.MUTED))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val status = tv("检查中", 11f, Palette.MUTED)
        row.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(8) })

        val actionButton = btn(action, Palette.CARD_2) { onClick() }
        actionButton.textSize = 11.5f
        actionButton.minHeight = dp(34)
        actionButton.setPadding(dp(10), 0, dp(10), 0)
        row.addView(actionButton)

        parent.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })
        return status
    }

    private fun scriptCard(): LinearLayout {
        val card = card()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(tv("脚本", 16f, Palette.TEXT, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(btn("新建", Palette.CARD_2) { editScriptDialog(null) })
        head.addView(btn("更多", Palette.CARD_2) { moreMenu() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(6) })
        card.addView(head)

        spinner = Spinner(this).apply {
            background = roundedRect(Palette.CARD_2, dp(10), Palette.BORDER, dp(1))
            setPadding(dp(10), 0, dp(10), 0)
        }
        card.addView(spinner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(46)
        ).apply { topMargin = dp(10) })

        scriptInfo = tv("", 10.5f, Palette.MUTED)
        card.addView(scriptInfo, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })
        return card
    }

    private fun stepsCard(): LinearLayout {
        val card = card()
        stepsHeader = tv("步骤", 16f, Palette.TEXT, bold = true)
        card.addView(stepsHeader)
        stepsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(stepsBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val types = listOf(
            "＋ 点击" to StepType.TAP,
            "＋ 长按" to StepType.LONG_PRESS,
            "＋ 滑动" to StepType.SWIPE,
            "＋ 等待" to StepType.WAIT,
        )
        types.forEachIndexed { index, (label, type) ->
            val button = btn(label, Palette.CARD_2) { showStepDialog(type) }
            button.textSize = 12f
            addRow.addView(button, LinearLayout.LayoutParams(
                0,
                dp(40),
                1f
            ).apply { rightMargin = if (index < types.lastIndex) dp(6) else 0 })
        }
        card.addView(addRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        card.addView(tv("提示：坐标可以在目标 App 上用悬浮准星记录，也可以手动填写。", 10.5f, Palette.DIM).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        })
        return card
    }

    private fun runCard(): LinearLayout {
        val card = card()
        card.addView(tv("悬浮控制", 16f, Palette.TEXT, bold = true))
        card.addView(tv("悬浮条：单击打开侧边抽屉；拖动可自由摆放，靠近左右边缘会自动贴边。", 11.5f, Palette.MUTED).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        })
        val start = btn("启动悬浮条", Palette.PRIMARY, Palette.ON_PRIMARY) { startOverlay() }
        card.addView(start, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(44)
        ).apply { topMargin = dp(12) })
        val arrange = btn("可视化编排（拖动点位）", Palette.CARD_2) { startVisualEditor() }
        card.addView(arrange, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(40)
        ).apply { topMargin = dp(8) })
        val test = btn("测试点击屏幕中心", Palette.CARD_2) { testCenterTap() }
        card.addView(test, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(40)
        ).apply { topMargin = dp(8) })
        card.addView(tv("编排模式会暂时收起悬浮条，把每个点位画在屏幕上，拖到哪就保存到哪。", 10.5f, Palette.DIM).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        })
        return card
    }

    // ------------------------------------------------------------------
    // Permissions
    // ------------------------------------------------------------------
    private fun accessibilityOn(): Boolean {
        if (ClickAccessibilityService.instance != null) return true
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any {
            it.equals("$packageName/${ClickAccessibilityService::class.java.name}", true) ||
                it.endsWith("ClickAccessibilityService", true)
        }
    }

    private fun overlayOn(): Boolean = Settings.canDrawOverlays(this)

    private fun notificationsOn(): Boolean {
        val manager = getSystemService(NotificationManager::class.java) ?: return false
        return manager.areNotificationsEnabled()
    }

    private fun refreshPermissions() {
        setStatus(accStatus, accessibilityOn())
        setStatus(overlayStatus, overlayOn())
        setStatus(notifStatus, notificationsOn())
    }

    private fun setStatus(view: TextView, on: Boolean) {
        view.text = if (on) "已开启" else "未开启"
        view.setTextColor(if (on) Palette.GREEN else Palette.RED)
    }

    private fun prepareAll() {
        when {
            !overlayOn() -> {
                toast("请先允许「显示在其他应用上层」")
                openOverlaySettings()
            }
            !accessibilityOn() -> {
                toast("在列表里找到 outoclick 并开启")
                openAccessibility()
            }
            Build.VERSION.SDK_INT >= 33 && !notificationsOn() -> requestNotifications()
            else -> toast("权限已就绪，去目标 App 点悬浮条")
        }
    }

    private fun openAccessibility() {
        toast("在「已下载的应用」里找到 outoclick 点击执行器并开启")
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(intent) }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 77)
        } else {
            toast("当前系统无需通知权限")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 77) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            toast(if (granted) "通知权限已开启" else "通知权限被拒绝，前台服务仍在运行")
            refreshPermissions()
        }
    }

    // ------------------------------------------------------------------
    // Script list
    // ------------------------------------------------------------------
    private fun rebuildSpinner() {
        val list = store.list()
        guardSpinner = true
        val names = list.map { it.name }
        val adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            names
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.setTextColor(Palette.TEXT)
                view.textSize = 14f
                return view
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent) as TextView
                view.setTextColor(Palette.TEXT)
                view.textSize = 14f
                view.setBackgroundColor(Palette.CARD)
                view.setPadding(dp(12), dp(10), dp(12), dp(10))
                return view
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        val targetId = script?.id ?: store.activeId()
        val index = list.indexOfFirst { it.id == targetId }.coerceAtLeast(0)
        spinner.setSelection(index, false)
        guardSpinner = false
        script = list.getOrNull(index)
        updateScriptInfo()

        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (guardSpinner) return
                val current = store.list().getOrNull(position) ?: return
                script = current
                store.setActive(current.id)
                updateScriptInfo()
                refreshSteps()
                notifyOverlay()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun updateScriptInfo() {
        val current = script
        scriptInfo.text = if (current == null) {
            "没有脚本"
        } else {
            buildString {
                append("id ${current.id} · ${current.steps.size} 步 · 循环 ${current.repeat} 次")
                if (current.baseWidth > 0 && current.baseHeight > 0) {
                    append(" · 基准 ${current.baseWidth}×${current.baseHeight}")
                }
                if (current.jitterMs > 0 || current.jitterPx > 0) append(" · 随机节奏")
            }
        }
    }

    private fun moreMenu() {
        val items = arrayOf(
            "重命名 / 循环次数",
            "复制脚本",
            "删除脚本",
            "导出到剪贴板",
            "从剪贴板导入",
            "生成示例脚本",
        )
        AlertDialog.Builder(this)
            .setTitle("脚本操作")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> editScriptDialog(script)
                    1 -> duplicateScript()
                    2 -> deleteScript()
                    3 -> exportScript()
                    4 -> importScript()
                    5 -> createDemo()
                }
            }
            .show()
    }

    private fun editScriptDialog(existing: Script?) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val name = EditText(this).apply {
            setText(existing?.name ?: "新脚本")
            setTextColor(Palette.TEXT)
            hint = "脚本名称"
            setHintTextColor(Palette.DIM)
        }
        val repeat = EditText(this).apply {
            setText((existing?.repeat ?: 1).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Palette.TEXT)
            hint = "循环次数"
            setHintTextColor(Palette.DIM)
        }
        val jitterMs = EditText(this).apply {
            setText((existing?.jitterMs ?: 0).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Palette.TEXT)
            hint = "随机延迟 ±ms"
            setHintTextColor(Palette.DIM)
        }
        val jitterPx = EditText(this).apply {
            setText((existing?.jitterPx ?: 0).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Palette.TEXT)
            hint = "随机坐标 ±px"
            setHintTextColor(Palette.DIM)
        }
        box.addView(tv("名称", 11f, Palette.MUTED))
        box.addView(name)
        box.addView(tv("循环次数（1–100000）", 11f, Palette.MUTED).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        })
        box.addView(repeat)
        box.addView(tv("随机节奏：每步延时 ±ms、坐标 ±px（0 = 关闭）", 11f, Palette.MUTED).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        })
        box.addView(jitterMs)
        box.addView(jitterPx, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "新建脚本" else "脚本设置")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("保存") { _, _ ->
                val title = name.text.toString().trim().ifBlank { "未命名脚本" }
                val rounds = repeat.text.toString().trim().toIntOrNull()?.coerceIn(1, 100_000) ?: 1
                val target = existing ?: Script(name = title)
                target.name = title
                target.repeat = rounds
                target.jitterMs = jitterMs.text.toString().trim().toIntOrNull()?.coerceIn(0, 5000) ?: 0
                target.jitterPx = jitterPx.text.toString().trim().toIntOrNull()?.coerceIn(0, 200) ?: 0
                store.save(target)
                script = store.load(target.id) ?: target
                store.setActive(target.id)
                rebuildSpinner()
                refreshSteps()
                notifyOverlay()
                toast("已保存 ${target.name}")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun duplicateScript() {
        val current = script ?: return
        val copy = store.duplicate(current)
        script = copy
        store.setActive(copy.id)
        rebuildSpinner()
        refreshSteps()
        notifyOverlay()
        toast("已复制")
    }

    private fun deleteScript() {
        val current = script ?: return
        AlertDialog.Builder(this)
            .setTitle("删除脚本")
            .setMessage("确定删除「${current.name}」？")
            .setPositiveButton("删除") { _, _ ->
                store.delete(current.id)
                script = store.list().firstOrNull() ?: store.ensureSeed()
                store.setActive(script!!.id)
                rebuildSpinner()
                refreshSteps()
                notifyOverlay()
                toast("已删除")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun createDemo() {
        val demo = Script.demo()
        store.save(demo)
        script = demo
        store.setActive(demo.id)
        rebuildSpinner()
        refreshSteps()
        notifyOverlay()
        toast("已生成示例，请把坐标改成你的目标位置")
    }

    private fun exportScript() {
        val current = script ?: return
        val manager = getSystemService(ClipboardManager::class.java)
        manager.setPrimaryClip(ClipData.newPlainText("outoclick-script", current.exportJson()))
        toast("已复制「${current.name}」的 JSON")
    }

    private fun importScript() {
        val manager = getSystemService(ClipboardManager::class.java)
        val text = manager.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
        if (text.isNullOrBlank()) {
            toast("剪贴板为空")
            return
        }
        val imported = store.importFrom(text)
        if (imported == null) {
            toast("JSON 解析失败")
            return
        }
        script = imported
        store.setActive(imported.id)
        rebuildSpinner()
        refreshSteps()
        toast("已导入 ${imported.name}")
    }

    // ------------------------------------------------------------------
    // Steps
    // ------------------------------------------------------------------
    private fun refreshSteps() {
        val current = script
        if (current == null) {
            stepsHeader.text = "步骤"
            stepsBox.removeAllViews()
            return
        }
        stepsHeader.text = "步骤 · ${current.steps.size}  ·  循环 ${current.repeat} 次"
        stepsBox.removeAllViews()
        if (current.steps.isEmpty()) {
            stepsBox.addView(tv("还没有步骤。用下面的按钮添加，或去目标 App 用悬浮准星记录。", 12f, Palette.MUTED))
            updateScriptInfo()
            return
        }
        current.steps.forEachIndexed { index, step ->
            stepsBox.addView(stepRow(index, step))
            if (index < current.steps.lastIndex) stepsBox.addView(divider())
        }
        updateScriptInfo()
    }

    private fun stepRow(index: Int, step: Step): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        row.addView(tv("${index + 1}", 11f, Palette.MUTED, bold = true).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setOnClickListener { showStepDialog(step.type, index) }
        }
        col.addView(tv(step.summary(), 13.5f, if (step.enabled) Palette.TEXT else Palette.DIM))
        val meta = buildString {
            append("延时 ${step.delayAfterMs}ms")
            if (!step.enabled) append(" · 已禁用")
        }
        col.addView(tv(meta, 10.5f, Palette.MUTED))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(smallBtn("↑") { moveStep(index, -1) })
        row.addView(smallBtn("↓") { moveStep(index, 1) })
        row.addView(smallBtn("改") { showStepDialog(step.type, index) })
        row.addView(smallBtn("删") { deleteStep(index) })
        return row
    }

    private fun smallBtn(text: String, onClick: () -> Unit): View =
        btn(text, Palette.CARD_2) { onClick() }.apply {
            textSize = 12f
            minWidth = dp(34)
            minimumWidth = dp(34)
            minHeight = dp(32)
            setPadding(dp(6), 0, dp(6), 0)
        }

    private fun showStepDialog(type: StepType, index: Int = -1) {
        val current = script ?: return
        val existing = current.steps.getOrNull(index)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        fun field(label: String, value: String): EditText {
            box.addView(tv(label, 11f, Palette.MUTED).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            })
            val edit = EditText(this).apply {
                setText(value)
                inputType = InputType.TYPE_CLASS_NUMBER
                setTextColor(Palette.TEXT)
                setHintTextColor(Palette.DIM)
            }
            box.addView(edit)
            return edit
        }

        val fx = field("X 坐标", (existing?.x ?: 540).toString())
        val fy = field("Y 坐标", (existing?.y ?: 1200).toString())
        val fx2 = field("终点 X（滑动）", (existing?.x2 ?: 540).toString())
        val fy2 = field("终点 Y（滑动）", (existing?.y2 ?: 800).toString())
        val durationDefault = when (type) {
            StepType.LONG_PRESS -> 800
            StepType.SWIPE -> 300
            StepType.WAIT -> 1000
            StepType.TAP -> 60
        }
        val fduration = field(
            if (type == StepType.WAIT) "等待时长 ms" else "时长 ms",
            (existing?.durationMs ?: durationDefault).toString()
        )
        val fdelay = field("执行后延时 ms", (existing?.delayAfterMs ?: 300).toString())
        fx2.visibility = if (type == StepType.SWIPE) View.VISIBLE else View.GONE
        fy2.visibility = if (type == StepType.SWIPE) View.VISIBLE else View.GONE
        fx.visibility = if (type == StepType.WAIT) View.GONE else View.VISIBLE
        fy.visibility = if (type == StepType.WAIT) View.GONE else View.VISIBLE
        box.getChildAt(0).visibility = fx.visibility
        box.getChildAt(1).visibility = fx.visibility
        box.getChildAt(2).visibility = fy.visibility
        box.getChildAt(3).visibility = fy.visibility
        box.getChildAt(4).visibility = fx2.visibility
        box.getChildAt(5).visibility = fx2.visibility
        box.getChildAt(6).visibility = fy2.visibility
        box.getChildAt(7).visibility = fy2.visibility
        fduration.visibility = View.VISIBLE
        if (type == StepType.TAP) {
            box.getChildAt(8).visibility = View.GONE
            box.getChildAt(9).visibility = View.GONE
        }

        val enabled = CheckBox(this).apply {
            text = "启用该步骤"
            isChecked = existing?.enabled ?: true
            setTextColor(Palette.TEXT)
        }
        box.addView(enabled, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        box.addView(tv("建议在目标 App 上用悬浮准星取点，这里的坐标是屏幕物理像素。", 10.5f, Palette.DIM).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        })

        val scroll = ScrollView(this).apply { addView(box) }
        val title = when (type) {
            StepType.TAP -> "点击"
            StepType.LONG_PRESS -> "长按"
            StepType.SWIPE -> "滑动"
            StepType.WAIT -> "等待"
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加$title" else "编辑$title")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                fun n(edit: EditText, def: Int): Int =
                    edit.text.toString().trim().toIntOrNull()?.coerceAtLeast(0) ?: def

                val step = existing ?: Step(type = type)
                step.type = type
                step.x = n(fx, 0)
                step.y = n(fy, 0)
                step.x2 = n(fx2, 0)
                step.y2 = n(fy2, 0)
                step.durationMs = n(fduration, durationDefault).coerceAtLeast(1)
                step.delayAfterMs = n(fdelay, 300)
                step.enabled = enabled.isChecked
                if (existing == null) current.steps.add(step)
                store.save(current)
                refreshSteps()
                notifyOverlay()
                val size = Screen.size(this)
                if (type != StepType.WAIT && (step.x >= size.x || step.y >= size.y)) {
                    toast("坐标超出屏幕 ${size.x}×${size.y}，请检查")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun moveStep(index: Int, delta: Int) {
        val current = script ?: return
        val target = index + delta
        if (index !in current.steps.indices || target !in current.steps.indices) return
        val item = current.steps.removeAt(index)
        current.steps.add(target, item)
        store.save(current)
        refreshSteps()
        notifyOverlay()
    }

    private fun deleteStep(index: Int) {
        val current = script ?: return
        if (index !in current.steps.indices) return
        current.steps.removeAt(index)
        store.save(current)
        refreshSteps()
        notifyOverlay()
    }

    // ------------------------------------------------------------------
    // Overlay
    // ------------------------------------------------------------------
    private fun startOverlay() {
        val current = script
        if (current == null) {
            toast("请先新建脚本")
            return
        }
        if (!overlayOn()) {
            toast("请先允许悬浮窗权限")
            openOverlaySettings()
            return
        }
        if (OverlayService.running) {
            // 服务已在运行：这个按钮兼作「显示悬浮条」
            store.setBallVisible(true)
            runCatching {
                startService(
                    Intent(this, OverlayService::class.java)
                        .setAction(OverlayService.ACTION_SHOW_BALL)
                )
                toast("已显示悬浮条")
                moveTaskToBack(true)
            }.onFailure { toast("操作失败：${it.message}") }
            return
        }
        if (!accessibilityOn()) {
            toast("请先开启无障碍服务")
            openAccessibility()
            return
        }
        store.setActive(current.id)
        store.setBallVisible(true)
        runCatching {
            startForegroundService(Intent(this, OverlayService::class.java))
            toast("悬浮条已启动，切到目标 App 后点它")
            moveTaskToBack(true)
        }.onFailure {
            toast("启动失败：${it.message}")
        }
    }

    private fun startVisualEditor() {
        val current = script
        if (current == null) {
            toast("请先新建脚本")
            return
        }
        if (!overlayOn()) {
            toast("请先允许悬浮窗权限")
            openOverlaySettings()
            return
        }
        store.setActive(current.id)
        runCatching {
            startForegroundService(
                Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_EDIT)
            )
            moveTaskToBack(true)
        }.onFailure {
            toast("启动失败：${it.message}")
        }
    }

    private fun testCenterTap() {
        val service = ClickAccessibilityService.instance
        if (service == null) {
            toast("无障碍服务未开启")
            openAccessibility()
            return
        }
        val size = Screen.size(this)
        scope.launch {
            val ok = service.tap(size.x / 2, size.y / 2, 60)
            toast(if (ok) "已点击屏幕中心 (${size.x / 2}, ${size.y / 2})" else "点击失败")
        }
    }

    private fun notifyOverlay() {
        if (!OverlayService.running) return
        runCatching {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_REFRESH))
        }
    }
}
