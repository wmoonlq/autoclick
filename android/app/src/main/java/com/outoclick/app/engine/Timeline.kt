package com.outoclick.app.engine

import com.outoclick.app.model.Button
import com.outoclick.app.model.JitterProfile
import com.outoclick.app.model.Plan
import com.outoclick.app.model.PlanStep
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 时间轴语义（与《数据实体建模 v4》§5 一致）：
 *
 * 单步动作时长 = clickCount × press + (clickCount − 1) × clickGap
 * 单轮时长     = Σ(enabled 步骤动作时长 + pointGap)
 * 预估总时长   = loopCount × 单轮时长 − 最后一个 pointGap
 *
 * pointGap 是本步最后一次抬起 -> 下一步第一次按下，跨轮同样生效；
 * 最后一轮的最后一步不执行 pointGap。
 */
object Timeline {

    fun clickDurationMs(clickCount: Int, pressMs: Int, clickGapMs: Int): Long {
        val count = clickCount.coerceAtLeast(1)
        return count.toLong() * pressMs.coerceAtLeast(0) +
            (count - 1).toLong() * clickGapMs.coerceAtLeast(0)
    }

    fun stepDurationMs(button: Button, step: PlanStep): Long =
        clickDurationMs(button.clickCount, button.pressMs, button.clickGapMs) +
            step.pointGapMs.coerceAtLeast(0)

    fun planDurationMs(plan: Plan, buttons: Map<String, Button>): Long {
        val steps = plan.steps
            .filter { it.enabled && buttons.containsKey(it.buttonId) }
            .sortedBy { it.seq }
        if (steps.isEmpty()) return 0L
        val perRound = steps.sumOf { step ->
            stepDurationMs(buttons.getValue(step.buttonId), step)
        }
        val lastGap = steps.last().pointGapMs.coerceAtLeast(0)
        return (perRound * plan.loopCount.coerceAtLeast(1) - lastGap).coerceAtLeast(0L)
    }

    /**
     * 看门狗用的“当前步骤最大合理间隔”。包含抖动上限与一点余量；
     * 实际超时阈值 = max(3 × 本值, 30s)。
     */
    fun expectedStepIntervalMs(button: Button, step: PlanStep, jitter: JitterProfile): Long {
        var click = clickDurationMs(button.clickCount, button.pressMs, button.clickGapMs)
        if (jitter.enabled) {
            click += button.clickCount.toLong() * jitter.pressMs
            click += (button.clickCount - 1).coerceAtLeast(0).toLong() * jitter.clickGapMs
        }
        val pointGap = step.pointGapMs.coerceAtLeast(0).toLong() +
            if (jitter.enabled) jitter.delayMs.toLong() else 0L
        return (click + pointGap + 500L).coerceAtLeast(1_000L)
    }

    fun formatDuration(ms: Long): String {
        if (ms <= 0) return "0s"
        val totalSeconds = ms / 1000.0
        return when {
            totalSeconds < 60 -> String.format(Locale.US, "%.1fs", totalSeconds)
            else -> {
                val minutes = (totalSeconds / 60).toInt()
                val seconds = (totalSeconds % 60).roundToInt()
                if (seconds == 60) "${minutes + 1}m 0s" else "${minutes}m ${seconds}s"
            }
        }
    }
}
