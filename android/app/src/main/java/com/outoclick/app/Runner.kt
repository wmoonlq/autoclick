package com.outoclick.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

object Runner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var paused: Boolean = false
        private set

    var onStatus: ((String) -> Unit)? = null
    var onFinished: ((String) -> Unit)? = null

    /** 当前执行到原始 steps 的下标（-1 表示结束/无） */
    var onStep: ((Int) -> Unit)? = null

    fun start(script: Script): Boolean {
        if (running) return false
        val service = ClickAccessibilityService.instance
        if (service == null) {
            onStatus?.invoke("无障碍服务未开启")
            return false
        }
        val enabled = script.steps.withIndex().filter { it.value.enabled }
        if (enabled.isEmpty()) {
            onStatus?.invoke("脚本没有启用的步骤")
            return false
        }
        val rounds = script.repeat.coerceIn(1, 100_000)

        val size = Screen.size(service)
        val baseW = if (script.baseWidth > 0) script.baseWidth else size.x
        val baseH = if (script.baseHeight > 0) script.baseHeight else size.y
        val scaleX = size.x.toFloat() / baseW
        val scaleY = size.y.toFloat() / baseH
        val jitterMs = script.jitterMs.coerceIn(0, 5000)
        val jitterPx = script.jitterPx.coerceIn(0, 200)

        fun jitterDelay(value: Int): Int {
            if (jitterMs <= 0) return value
            return (value + Random.nextInt(-jitterMs, jitterMs + 1)).coerceAtLeast(0)
        }

        fun scaleXAt(value: Int): Int {
            val jitter = if (jitterPx > 0) Random.nextInt(-jitterPx, jitterPx + 1) else 0
            return (value * scaleX).roundToInt().plus(jitter).coerceIn(0, size.x - 1)
        }

        fun scaleYAt(value: Int): Int {
            val jitter = if (jitterPx > 0) Random.nextInt(-jitterPx, jitterPx + 1) else 0
            return (value * scaleY).roundToInt().plus(jitter).coerceIn(0, size.y - 1)
        }

        running = true
        paused = false
        job = scope.launch {
            var result = "完成"
            try {
                outer@ for (round in 1..rounds) {
                    for ((order, pair) in enabled.withIndex()) {
                        if (!running) break@outer
                        while (paused && running) delay(100)
                        if (!running) break@outer
                        val (stepIndex, step) = pair
                        onStep?.invoke(stepIndex)
                        onStatus?.invoke(
                            "${round}/${rounds} 轮 · ${order + 1}/${enabled.size} · ${step.summary()}"
                        )
                        when (step.type) {
                            StepType.TAP -> check(
                                service.tap(scaleXAt(step.x), scaleYAt(step.y), 60)
                            ) { "点击手势未完成" }
                            StepType.LONG_PRESS -> check(
                                service.tap(
                                    scaleXAt(step.x),
                                    scaleYAt(step.y),
                                    step.durationMs.coerceAtLeast(100)
                                )
                            ) { "长按手势未完成" }
                            StepType.SWIPE -> check(
                                service.swipe(
                                    scaleXAt(step.x),
                                    scaleYAt(step.y),
                                    scaleXAt(step.x2),
                                    scaleYAt(step.y2),
                                    step.durationMs
                                )
                            ) { "滑动手势未完成" }
                            StepType.WAIT -> delay(
                                jitterDelay(step.durationMs).coerceAtLeast(1).toLong()
                            )
                        }
                        val wait = jitterDelay(step.delayAfterMs)
                        if (wait > 0) delay(wait.toLong())
                    }
                }
            } catch (cancel: CancellationException) {
                result = "已停止"
                throw cancel
            } catch (error: Exception) {
                result = "出错：${error.message ?: error.javaClass.simpleName}"
            } finally {
                running = false
                paused = false
                job = null
                onStep?.invoke(-1)
                onStatus?.invoke(result)
                onFinished?.invoke(result)
            }
        }
        return true
    }

    fun pause() {
        if (!running) return
        paused = true
        onStatus?.invoke("已暂停")
    }

    fun resume() {
        if (!running) return
        paused = false
        onStatus?.invoke("继续运行")
    }

    fun stop() {
        if (!running && job == null) return
        running = false
        paused = false
        job?.cancel()
        job = null
        onStatus?.invoke("已停止")
    }
}
