package com.outoclick.app

import android.content.Context
import android.graphics.Point
import android.view.WindowManager

object Screen {
    /** Full display size including status/navigation bars, in the same space
     *  that AccessibilityService gestures use. */
    fun size(context: Context): Point {
        val wm = context.getSystemService(WindowManager::class.java)
        val point = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(point)
        return point
    }

    fun statusBarHeight(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    fun navigationBarHeight(context: Context): Int {
        val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
}
