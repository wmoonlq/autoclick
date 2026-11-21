package com.outoclick.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    fun start(script: Script): Boolean {
        if (running) return false
        val service = ClickAccessibilityService.instance
        if (service == null) {
            onStatus?.invoke("无障碍服务未开启")
            return false
        }
        val steps = script.steps.filter { it.enabled }
        if (steps.isEmpty()) {
            onStatus?.invoke("脚本没有启用的步骤")
            return false
        }
        val rounds = script.repeat.coerceIn(1, 100_000)
        running = true
        paused = false
        job = scope.launch {
            var result = "完成"
            try {
                outer@ for (round in 1..rounds) {
                    for ((index, step) in steps.withIndex()) {
                        if (!running) break@outer
                        while (paused && running) delay(100)
                        if (!running) break@outer
                        onStatus?.invoke(
                            "${round}/${rounds} 轮 · ${index + 1}/${steps.size} · ${step.summary()}"
                        )
                        when (step.type) {
                            StepType.TAP -> check(service.tap(step.x, step.y, 60)) {
                                "点击手势未完成"
                            }
                            StepType.LONG_PRESS -> check(
                                service.tap(step.x, step.y, step.durationMs.coerceAtLeast(100))
                            ) { "长按手势未完成" }
                            StepType.SWIPE -> check(
                                service.swipe(step.x, step.y, step.x2, step.y2, step.durationMs)
                            ) { "滑动手势未完成" }
                            StepType.WAIT -> delay(step.durationMs.coerceAtLeast(1).toLong())
                        }
                        if (step.delayAfterMs > 0) delay(step.delayAfterMs.toLong())
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
