package com.outoclick.app.engine

import kotlin.math.abs
import kotlin.math.max

data class OrientationSnapshot(
    val width: Int,
    val height: Int,
    val rotation: Int,
)

sealed class OrientationCheck {
    object Ok : OrientationCheck()
    data class Reject(val code: String, val message: String) : OrientationCheck()
}

/**
 * 方向判定只做纯逻辑，方便 JVM 单测覆盖。
 *
 * 不用“rotation 数字必须完全相等”做硬判据：
 * - 绑定时可能在 outoclick 主页面，方向不代表目标应用；
 * - Display.rotation 是屏幕旋转，不是 App 内容方向；
 * - 系统栏/分辨率微调会让精确像素比较误报。
 *
 * 这里只比较：竖屏/横屏、宽高比容差、180° 倒置。
 */
object OrientationPolicy {

    const val ASPECT_TOLERANCE = 0.05

    fun check(bound: OrientationSnapshot?, current: OrientationSnapshot): OrientationCheck {
        if (bound == null || bound.width <= 0 || bound.height <= 0 || bound.rotation < 0) {
            return OrientationCheck.Reject(
                "UNCALIBRATED",
                "目标应用方向尚未校准：切到目标应用后长按悬浮球 → 校准方向",
            )
        }
        if (current.width <= 0 || current.height <= 0) {
            return OrientationCheck.Reject("DISPLAY", "当前显示尺寸不可用，请重试")
        }

        val boundPortrait = bound.height >= bound.width
        val currentPortrait = current.height >= current.width
        if (boundPortrait != currentPortrait) {
            return OrientationCheck.Reject(
                "ROTATION",
                "屏幕方向与绑定时不一致（竖屏/横屏）：请恢复方向，或在悬浮球边栏点「校准方向」",
            )
        }

        val boundAspect = bound.width.toDouble() / bound.height
        val currentAspect = current.width.toDouble() / current.height
        val aspectDiff = abs(boundAspect - currentAspect) / max(boundAspect, currentAspect)
        if (aspectDiff > ASPECT_TOLERANCE) {
            return OrientationCheck.Reject(
                "DISPLAY",
                "屏幕比例与绑定时不一致：请恢复分辨率，或重新校准方向",
            )
        }

        // 0 与 2、1 与 3 都是 180° 倒置：方向/比例一样，但坐标会上下镜像。
        val rotationDelta = abs(bound.rotation - current.rotation) % 4
        if (rotationDelta == 2) {
            return OrientationCheck.Reject(
                "ROTATION_180",
                "屏幕倒置，请把设备转回绑定时方向",
            )
        }
        return OrientationCheck.Ok
    }
}
