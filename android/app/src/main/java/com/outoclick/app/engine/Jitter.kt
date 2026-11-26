package com.outoclick.app.engine

import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 参数随机化（文档里的“防检测”引擎）。
 * v1 按测试 M6 默认常开：±5ms / ±3px；不暴露 UI。
 * 所有结果都做二次 clamp，避免负数、越界或点击时长 <= 0。
 */
object Jitter {

    fun time(base: Int, jitter: Int, min: Int = 0, max: Int = 60_000): Long {
        val delta = if (jitter > 0) Random.nextInt(-jitter, jitter + 1) else 0
        return (base + delta).coerceIn(min, max).toLong()
    }

    fun press(base: Int, jitter: Int): Long = time(base, jitter, 10, 500)

    fun clickGap(base: Int, jitter: Int): Long = time(base, jitter, 0, 60_000)

    fun pointGap(base: Int, jitter: Int): Long = time(base, jitter, 0, 60_000)

    fun position(anchor: Double, displaySize: Int, jitterPx: Int, hotZonePx: Int): Int {
        if (displaySize <= 0) return 0
        val base = (anchor * displaySize).roundToInt()
        val effectiveJitter = jitterPx.coerceAtLeast(0)
            .coerceAtMost((hotZonePx.coerceAtLeast(0)) / 2)
        val delta = if (effectiveJitter > 0) {
            Random.nextInt(-effectiveJitter, effectiveJitter + 1)
        } else {
            0
        }
        return (base + delta).coerceIn(0, displaySize - 1)
    }
}
