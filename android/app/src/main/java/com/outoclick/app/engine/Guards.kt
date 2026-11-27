package com.outoclick.app.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import com.outoclick.app.ClickAccessibilityService
import com.outoclick.app.Screen
import com.outoclick.app.model.AppState
import com.outoclick.app.model.Button
import com.outoclick.app.model.Plan
import com.outoclick.app.model.PlanStatus
import com.outoclick.app.model.TargetApp

data class GuardResult(
    val ok: Boolean,
    val message: String = "",
    val code: String = "",
) {
    companion object {
        fun ok() = GuardResult(true)
        fun fail(message: String, code: String = "") = GuardResult(false, message, code)
    }
}

/**
 * 启动前后置校验。任何一项不满足都拒绝开跑，不做降级和猜测。
 */
object Guards {

    /** 运行期硬阻止：系统设置、安装器、权限控制器、SystemUI。 */
    private val HARD_BLOCK_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.shell",
    )

    /** 不能作为目标应用：硬阻止 + 常见桌面。 */
    private val BLOCKED_PACKAGES = HARD_BLOCK_PACKAGES + setOf(
        // 常见桌面；默认桌面还会通过 homePackage() 动态识别
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.miui.home",
        "com.huawei.android.launcher",
        "com.oppo.launcher",
        "com.vivo.launcher",
        "com.bbk.launcher2",
        "com.sec.android.app.launcher",
    )

    fun checkStart(
        context: Context,
        plan: Plan,
        app: TargetApp?,
        buttons: Map<String, Button>,
        state: AppState,
    ): GuardResult {
        if (!Settings.canDrawOverlays(context)) {
            return GuardResult.fail("请先允许「显示在其他应用上层」", "OVERLAY")
        }
        if (!ClickAccessibilityService.ready()) {
            return GuardResult.fail("请先开启无障碍服务", "ACCESSIBILITY")
        }
        if (!Screen.isInteractive(context)) {
            return GuardResult.fail("屏幕已熄灭，请点亮屏幕后再开始", "SCREEN_OFF")
        }
        if (Screen.isLocked(context)) {
            return GuardResult.fail("屏幕已锁定，请解锁后再开始", "LOCKED")
        }
        if (plan.status != PlanStatus.SAVED) {
            return GuardResult.fail("请先保存方案", "DRAFT")
        }
        if (app == null) {
            return GuardResult.fail("请先绑定目标应用", "NO_TARGET")
        }
        if (isBlockedPackage(context, app.packageName)) {
            return GuardResult.fail("目标应用不能是系统页面或桌面", "BLOCKED_TARGET")
        }
        if (plan.steps.isEmpty()) {
            return GuardResult.fail("方案没有点位，请先添加", "EMPTY")
        }
        if (plan.steps.size > 30) {
            return GuardResult.fail("单方案最多 30 个点位，请先删减", "TOO_MANY_STEPS")
        }
        if (plan.steps.none { it.enabled }) {
            return GuardResult.fail("方案没有启用的点位", "ALL_DISABLED")
        }
        if (plan.steps.any { it.enabled && buttons[it.buttonId] == null }) {
            return GuardResult.fail("方案引用了已删除的按钮，请重新编辑", "MISSING_BUTTON")
        }
        if (plan.loopCount !in 1..99) {
            return GuardResult.fail("循环轮次需在 1–99", "LOOP")
        }
        val foreground = ForegroundTracker.packageName
        if (foreground.isBlank()) {
            return GuardResult.fail("还未识别到前台应用，请切到目标应用后再试", "NO_FOREGROUND")
        }
        if (foreground != app.packageName) {
            return GuardResult.fail("请先打开目标应用：${app.label}", "TARGET_MISMATCH")
        }
        if (app.boundDisplayW <= 0 || app.boundDisplayH <= 0 || app.boundRotation < 0) {
            return GuardResult.fail(
                "目标应用方向尚未校准：切到目标应用后长按悬浮球 → 校准方向",
                "UNCALIBRATED",
            )
        }
        val size = Screen.size(context)
        val boundPortrait = app.boundDisplayH >= app.boundDisplayW
        val currentPortrait = size.y >= size.x
        if (boundPortrait != currentPortrait) {
            return GuardResult.fail(
                "屏幕方向与绑定时不一致（竖屏/横屏）：请恢复方向，或在悬浮球边栏点「校准方向」",
                "ROTATION",
            )
        }
        val boundAspect = app.boundDisplayW.toDouble() / app.boundDisplayH
        val currentAspect = size.x.toDouble() / size.y
        val aspectDiff = kotlin.math.abs(boundAspect - currentAspect) /
            kotlin.math.max(boundAspect, currentAspect)
        if (aspectDiff > 0.05) {
            return GuardResult.fail(
                "屏幕比例与绑定时不一致：请恢复分辨率，或重新校准方向",
                "DISPLAY",
            )
        }
        // 180° 倒置：方向/比例都一样，但坐标会上下镜像，单独拦一次。
        val currentRotation = Screen.rotation(context)
        val rotationDelta = kotlin.math.abs(app.boundRotation - currentRotation) % 4
        if (rotationDelta == 2) {
            return GuardResult.fail("屏幕倒置，请把设备转回绑定时方向", "ROTATION_180")
        }
        if (state.nonTerminalSession() != null) {
            return GuardResult.fail("已有方案在运行，请先停止", "BUSY")
        }
        return GuardResult.ok()
    }

    fun isBlockedPackage(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return true
        if (packageName in BLOCKED_PACKAGES) return true
        val home = homePackage(context)
        return home.isNotBlank() && packageName == home
    }

    /**
     * 运行中硬停止：只对系统页面。桌面/切到其他 App 按测试 M5-12 应暂停，
     * 由用户确认后再继续，不能直接停掉会话。
     */
    fun isHardBlockedPackage(packageName: String): Boolean =
        packageName.isBlank() || packageName in HARD_BLOCK_PACKAGES

    /**
     * 输入法窗口会以独立包名产生 windowStateChanged。它只是盖在目标 App 上，
     * 不代表用户切走了；否则一弹键盘悬浮球就消失、会话也会误暂停。
     */
    fun isImePackage(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val imm = context.getSystemService(InputMethodManager::class.java)
        val enabled = imm?.enabledInputMethodList
            ?.mapNotNull { it.packageName }
            .orEmpty()
        if (packageName in enabled) return true
        val current = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        )
        return current?.substringBefore('/') == packageName
    }

    fun homePackage(context: Context): String {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return runCatching {
            context.packageManager
                .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
                ?.packageName
                .orEmpty()
        }.getOrDefault("")
    }

    fun rotationLabel(rotation: Int): String = when (rotation) {
        0 -> "竖屏 0°"
        1 -> "横屏 90°"
        2 -> "倒置 180°"
        3 -> "横屏 270°"
        else -> "${rotation}°"
    }
}
