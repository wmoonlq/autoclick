package com.outoclick.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import com.outoclick.app.engine.ForegroundTracker
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class ClickAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ForegroundTracker.reset()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        ForegroundTracker.reset()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    /**
     * 只用来追踪前台包名，不读取窗口内容（canRetrieveWindowContent=false）。
     * 包名用于目标应用守卫：启动前校验、运行中切换暂停、系统页面硬阻止。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        // 悬浮球、编辑画布、菜单、设置弹窗都是我们自己的 overlay 窗口，
        // 它们获得焦点时也会产生 windowStateChanged。这里完全忽略本应用事件；
        // MainActivity 真正回到前台时由 Activity.onStart 主动上报。
        if (pkg == packageName) return

        ForegroundTracker.onPackage(pkg)
    }

    suspend fun tap(x: Int, y: Int, durationMs: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatch(path, durationMs)
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return dispatch(path, durationMs)
    }

    private suspend fun dispatch(path: Path, durationMs: Int): Boolean =
        withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { cont ->
                val gesture = GestureDescription.Builder()
                    .addStroke(
                        GestureDescription.StrokeDescription(
                            path,
                            0L,
                            durationMs.coerceIn(1, 60_000).toLong(),
                        )
                    )
                    .build()
                val callback = object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
                val accepted = runCatching { dispatchGesture(gesture, callback, null) }.getOrDefault(false)
                if (!accepted && cont.isActive) cont.resume(false)
            }
        } ?: false

    companion object {
        @Volatile
        var instance: ClickAccessibilityService? = null

        fun ready(): Boolean = instance != null
    }
}
