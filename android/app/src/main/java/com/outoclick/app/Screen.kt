package com.outoclick.app

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.os.PowerManager
import android.app.KeyguardManager
import android.view.WindowManager

object Screen {

    /** 全屏尺寸（含状态栏/导航栏），与 dispatchGesture 使用同一坐标空间。 */
    @Suppress("DEPRECATION")
    fun size(context: Context): Point {
        val wm = context.getSystemService(WindowManager::class.java)
        val point = Point()
        wm.defaultDisplay.getRealSize(point)
        return point
    }

    @Suppress("DEPRECATION")
    fun rotation(context: Context): Int {
        val wm = context.getSystemService(WindowManager::class.java)
        return wm.defaultDisplay.rotation
    }

    fun isInteractive(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return true
        return power.isInteractive
    }

    fun isLocked(context: Context): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return false
        return keyguard.isKeyguardLocked
    }

    fun statusBarHeight(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    fun navigationBarHeight(context: Context): Int {
        val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    /**
     * API 26 起使用 TYPE_APPLICATION_OVERLAY；Android 7.x 只能用 TYPE_PHONE。
     * 两者都需要 SYSTEM_ALERT_WINDOW。
     */
    @Suppress("DEPRECATION")
    fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
}
