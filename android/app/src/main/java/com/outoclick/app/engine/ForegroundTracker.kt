package com.outoclick.app.engine

import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 前台应用追踪。数据来源：无障碍服务的 TYPE_WINDOW_STATE_CHANGED 事件。
 * 不做轮询，也不申请 UsageStats 权限。
 */
object ForegroundTracker {

    @Volatile
    var packageName: String = ""
        private set

    @Volatile
    var updatedAt: Long = 0L
        private set

    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun addListener(listener: (String) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    fun onPackage(pkg: String?) {
        val value = pkg?.trim().orEmpty()
        if (value.isBlank()) return
        val changed = value != packageName
        packageName = value
        updatedAt = SystemClock.elapsedRealtime()
        if (changed) {
            listeners.forEach { runCatching { it(value) } }
        }
    }

    fun reset() {
        packageName = ""
        updatedAt = 0L
    }
}
