package com.outoclick.app.engine

import android.content.Context
import android.os.SystemClock
import com.outoclick.app.ClickAccessibilityService
import com.outoclick.app.Screen
import com.outoclick.app.data.AppRepository
import com.outoclick.app.model.Button
import com.outoclick.app.model.PauseReason
import com.outoclick.app.model.PlanStep
import com.outoclick.app.model.RunSession
import com.outoclick.app.model.SessionStatus
import com.outoclick.app.model.StopReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

sealed class StartResult {
    object Success : StartResult()
    data class Failure(val message: String) : StartResult()
}

/**
 * 运行会话管理器：状态机 + 时间轴执行 + 看门狗 + 前台/方向守卫。
 * 单实例：全局最多 1 个非终态会话。
 */
class SessionManager(
    private val context: Context,
    private val repo: AppRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runJob: Job? = null
    private var watchdogJob: Job? = null
    private var current: RunSession? = null
    private var snapshot: RunSnapshot? = null
    private var lastPersistAt = 0L

    private val updateListeners = CopyOnWriteArrayList<() -> Unit>()
    private val messageListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val finishListeners = CopyOnWriteArrayList<(RunSession) -> Unit>()

    fun addUpdateListener(listener: () -> Unit) = updateListeners.addIfAbsent(listener)
    fun removeUpdateListener(listener: () -> Unit) = updateListeners.remove(listener)
    fun addMessageListener(listener: (String) -> Unit) = messageListeners.addIfAbsent(listener)
    fun removeMessageListener(listener: (String) -> Unit) = messageListeners.remove(listener)
    fun addFinishListener(listener: (RunSession) -> Unit) = finishListeners.addIfAbsent(listener)
    fun removeFinishListener(listener: (RunSession) -> Unit) = finishListeners.remove(listener)

    fun currentSession(): RunSession? = current
    fun currentSnapshot(): RunSnapshot? = snapshot

    fun isRunning(): Boolean = current?.status == SessionStatus.RUNNING
    fun isPaused(): Boolean = current?.status == SessionStatus.PAUSED
    fun isActive(): Boolean = current?.status?.isActive == true

    fun reconcileOnStartup() {
        repo.reconcileSessionsOnStartup()
        current = null
        snapshot = null
        runJob = null
        watchdogJob = null
    }

    fun start(planId: String): StartResult {
        if (current?.status?.isActive == true) {
            return StartResult.Failure("已有方案在运行，请先停止")
        }
        val state = repo.read()
        val plan = state.plan(planId) ?: return StartResult.Failure("方案不存在")
        val app = state.app(plan.targetAppId) ?: return StartResult.Failure("请先绑定目标应用")
        val buttons = state.buttonsFor(app.id)
        val guard = Guards.checkStart(context, plan, app, buttons, state)
        if (!guard.ok) return StartResult.Failure(guard.message)

        val size = Screen.size(context)
        val rotation = Screen.rotation(context)
        val runSnapshot = RunSnapshot.from(plan, app, buttons, size.x, size.y, rotation)
        if (runSnapshot.steps.none { it.enabled }) {
            return StartResult.Failure("方案没有启用的步骤")
        }

        val session = RunSession(
            planId = plan.id,
            planVersion = plan.version,
            targetAppId = app.id,
            targetPackage = app.packageName,
            snapshotJson = runSnapshot.toJsonString(),
            status = SessionStatus.PENDING,
            foregroundPackage = ForegroundTracker.packageName,
            displayW = size.x,
            displayH = size.y,
            rotation = rotation,
            startedAt = System.currentTimeMillis(),
            lastProgressAt = SystemClock.elapsedRealtime(),
        )

        repo.addSession(session)
        repo.update { s ->
            s.settings.activePlanId = plan.id
            s.plan(plan.id)?.lastRunAt = System.currentTimeMillis()
            s.app(app.id)?.lastRunAt = System.currentTimeMillis()
        }

        current = session
        snapshot = runSnapshot
        session.status = SessionStatus.RUNNING
        session.lastProgressAt = SystemClock.elapsedRealtime()
        repo.persistSession(session)
        lastPersistAt = System.currentTimeMillis()
        notifyUpdate()
        emitMessage("开始：${plan.name}")

        runJob = scope.launch { execute(session, runSnapshot) }
        watchdogJob = scope.launch { watchdog(session) }
        return StartResult.Success
    }

    fun pause(reason: PauseReason = PauseReason.USER): Boolean {
        val session = current ?: return false
        if (session.status != SessionStatus.RUNNING) return false
        session.status = SessionStatus.PAUSED
        session.pausedReason = reason
        repo.persistSession(session)
        notifyUpdate()
        emitMessage(pauseMessage(reason))
        return true
    }

    fun resume(): Boolean {
        val session = current ?: return false
        if (session.status != SessionStatus.PAUSED) return false
        val snap = snapshot ?: return false

        if (!Screen.isInteractive(context)) {
            emitMessage("屏幕已熄灭，请点亮后再继续")
            return false
        }
        if (Screen.isLocked(context)) {
            emitMessage("屏幕已锁定，请解锁后再继续")
            return false
        }
        if (ForegroundTracker.packageName != snap.targetPackage) {
            emitMessage("请先切回目标应用再继续")
            return false
        }
        if (Screen.rotation(context) != snap.rotation) {
            emitMessage("屏幕方向已变化，请恢复后再继续")
            return false
        }

        session.status = SessionStatus.RUNNING
        session.pausedReason = null
        session.errorCode = null
        session.lastProgressAt = SystemClock.elapsedRealtime()
        repo.persistSession(session)
        notifyUpdate()
        emitMessage("继续运行")
        return true
    }

    fun stop(reason: StopReason = StopReason.USER): Boolean {
        val session = current ?: return false
        if (session.status.isTerminal) return false
        session.status = if (reason == StopReason.ERROR) SessionStatus.FAILED else SessionStatus.STOPPED
        session.stopReason = reason
        session.pausedReason = null
        session.endedAt = System.currentTimeMillis()
        runJob?.cancel()
        runJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        repo.persistSession(session)
        notifyUpdate()
        finishListeners.forEach { runCatching { it(session) } }
        emitMessage(stopMessage(reason))
        return true
    }

    fun togglePause(): Boolean =
        if (isRunning()) pause(PauseReason.USER) else if (isPaused()) resume() else false

    fun onForegroundPackage(packageName: String) {
        val session = current ?: return
        if (!session.status.isActive) return
        if (session.foregroundPackage != packageName) {
            session.foregroundPackage = packageName
            repo.persistSession(session)
        }
        val snap = snapshot ?: return
        if (packageName == snap.targetPackage) return

        if (Guards.isHardBlockedPackage(packageName)) {
            stop(StopReason.TARGET_MISMATCH)
            emitMessage("检测到系统页面，已停止")
            return
        }
        if (session.status == SessionStatus.RUNNING) {
            pause(PauseReason.TARGET_MISMATCH)
            emitMessage("前台应用已切换，已暂停")
        }
    }

    fun onRotationChanged(rotation: Int) {
        val session = current ?: return
        val snap = snapshot ?: return
        if (session.status == SessionStatus.RUNNING && rotation != snap.rotation) {
            pause(PauseReason.ROTATION)
            emitMessage("屏幕方向变化，已暂停")
        }
    }

    fun onScreenOff() {
        val session = current ?: return
        if (session.status == SessionStatus.RUNNING) {
            pause(PauseReason.SCREEN_OFF)
            emitMessage("屏幕熄灭，已暂停")
        }
    }

    fun statusText(): String {
        val session = current ?: return "就绪"
        val name = snapshot?.planName ?: "方案"
        return when (session.status) {
            SessionStatus.PENDING -> "准备中 · $name"
            SessionStatus.RUNNING -> {
                val total = snapshot?.steps?.count { it.enabled } ?: 0
                val step = total.coerceAtLeast(1)
                "运行中 · $name · ${session.loopIndex}/${snapshot?.loopCount ?: 1} 轮 · 第 ${session.currentStepSeq.coerceAtMost(step)} 步"
            }
            SessionStatus.PAUSED -> "已暂停 · ${pauseLabel(session.pausedReason)}"
            SessionStatus.FINISHED -> "完成 · $name"
            SessionStatus.STOPPED -> "已停止 · ${stopLabel(session.stopReason)}"
            SessionStatus.FAILED -> "失败 · ${session.errorCode ?: "ERROR"}"
        }
    }

    fun progressText(): String {
        val session = current ?: return "就绪"
        return when (session.status) {
            SessionStatus.RUNNING, SessionStatus.PAUSED ->
                "${session.loopIndex}/${snapshot?.loopCount ?: 1} 轮 · 第 ${session.currentStepSeq} 步 · 已点 ${session.okCount} 次"
            SessionStatus.FINISHED ->
                "完成 · ${session.okCount} 次点击"
            SessionStatus.STOPPED ->
                "已停止 · ${session.okCount} 次点击"
            SessionStatus.FAILED ->
                "失败 · ${session.errorCode ?: "ERROR"}"
            SessionStatus.PENDING -> "准备中"
        }
    }

    // ------------------------------------------------------------------
    // 执行
    // ------------------------------------------------------------------

    private suspend fun execute(session: RunSession, snap: RunSnapshot) {
        try {
            val steps = snap.steps.filter { it.enabled }.sortedBy { it.seq }
            if (steps.isEmpty()) throw IllegalStateException("没有启用的步骤")

            for (round in 1..snap.loopCount) {
                session.loopIndex = round
                for ((index, step) in steps.withIndex()) {
                    ensureRunning(session)
                    session.currentStepSeq = step.seq
                    markProgress(session, persist = false)

                    var click = 1
                    while (click <= step.clickCount) {
                        ensureRunning(session)
                        awaitResumed(session)

                        val hotW = (step.width * snap.displayW).toInt().coerceAtLeast(1)
                        val hotH = (step.height * snap.displayH).toInt().coerceAtLeast(1)
                        val jitterPx = if (snap.jitter.enabled) snap.jitter.positionPx else 0
                        val x = Jitter.position(step.anchorX, snap.displayW, jitterPx, minOf(hotW, hotH))
                        val y = Jitter.position(step.anchorY, snap.displayH, jitterPx, minOf(hotW, hotH))
                        val press = Jitter.press(
                            step.pressMs,
                            if (snap.jitter.enabled) snap.jitter.pressMs else 0,
                        )

                        val ok = tapWithRetry(session, x, y, press)
                        if (!ok) {
                            if (session.status == SessionStatus.PAUSED) {
                                awaitResumed(session)
                                continue
                            }
                            throw IllegalStateException("手势注入失败")
                        }

                        session.okCount += 1
                        markProgress(session, persist = false)

                        if (click < step.clickCount) {
                            delay(
                                Jitter.clickGap(
                                    step.clickGapMs,
                                    if (snap.jitter.enabled) snap.jitter.clickGapMs else 0,
                                )
                            )
                        }
                        click += 1
                    }

                    val isLastStep = index == steps.lastIndex
                    val isLastRound = round == snap.loopCount
                    if (!(isLastStep && isLastRound)) {
                        delay(
                            Jitter.pointGap(
                                step.pointGapMs,
                                if (snap.jitter.enabled) snap.jitter.delayMs else 0,
                            )
                        )
                    }
                }
            }

            finish(session, SessionStatus.FINISHED, StopReason.COMPLETE, null)
        } catch (cancel: CancellationException) {
            if (session.status.isActive) {
                finish(session, SessionStatus.STOPPED, StopReason.USER, null)
            }
        } catch (t: Throwable) {
            if (session.status.isActive) {
                session.failCount += 1
                finish(
                    session,
                    SessionStatus.FAILED,
                    StopReason.ERROR,
                    t.message ?: t.javaClass.simpleName,
                )
            }
        }
    }

    private suspend fun tapWithRetry(
        session: RunSession,
        x: Int,
        y: Int,
        pressMs: Long,
    ): Boolean {
        val service = ClickAccessibilityService.instance ?: return false
        var ok = runCatching { service.tap(x, y, pressMs.toInt()) }.getOrDefault(false)
        if (!ok && session.status == SessionStatus.RUNNING) {
            delay(120)
            ok = runCatching { service.tap(x, y, pressMs.toInt()) }.getOrDefault(false)
        }
        if (!ok && session.status == SessionStatus.RUNNING) {
            session.errorCode = "DISPATCH_REJECTED"
            pause(PauseReason.USER)
            emitMessage("手势被系统取消，已暂停")
        }
        return ok
    }

    private suspend fun ensureRunning(session: RunSession) {
        if (!session.status.isActive || session.status == SessionStatus.STOPPED) {
            throw CancellationException("stopped")
        }
        if (session.status == SessionStatus.PAUSED) {
            awaitResumed(session)
        }
    }

    private suspend fun awaitResumed(session: RunSession) {
        while (session.status == SessionStatus.PAUSED) {
            delay(100)
            if (!session.status.isActive) throw CancellationException("stopped")
        }
        if (session.status != SessionStatus.RUNNING) {
            throw CancellationException("stopped")
        }
    }

    private fun markProgress(session: RunSession, persist: Boolean) {
        session.lastProgressAt = SystemClock.elapsedRealtime()
        val now = System.currentTimeMillis()
        if (persist || now - lastPersistAt >= 2_000) {
            lastPersistAt = now
            repo.persistSession(session)
        }
        notifyUpdate()
    }

    private suspend fun watchdog(session: RunSession) {
        while (true) {
            delay(1_000)
            if (session.status.isTerminal) break
            if (session.status != SessionStatus.RUNNING) continue
            val snap = snapshot ?: break
            val step = snap.steps.firstOrNull { it.seq == session.currentStepSeq }
            val expected = if (step != null) {
                Timeline.expectedStepIntervalMs(
                    Button(
                        clickCount = step.clickCount,
                        clickGapMs = step.clickGapMs,
                        pressMs = step.pressMs,
                    ),
                    PlanStep(pointGapMs = step.pointGapMs),
                    snap.jitter,
                )
            } else {
                30_000L
            }
            val timeout = maxOf(expected * 3, 30_000L)
            val idle = SystemClock.elapsedRealtime() - session.lastProgressAt
            if (idle > timeout) {
                session.errorCode = "WATCHDOG_TIMEOUT"
                stop(StopReason.TIMEOUT)
                emitMessage("长时间无进度，已自动停止")
                break
            }
        }
    }

    private fun finish(
        session: RunSession,
        status: SessionStatus,
        reason: StopReason,
        error: String?,
    ) {
        if (session.status.isTerminal) return
        session.status = status
        session.stopReason = reason
        session.pausedReason = null
        session.errorCode = error
        session.endedAt = System.currentTimeMillis()
        runJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        repo.persistSession(session)
        notifyUpdate()
        finishListeners.forEach { runCatching { it(session) } }
        emitMessage(
            when (status) {
                SessionStatus.FINISHED -> "完成：${snapshot?.planName ?: "方案"}"
                SessionStatus.FAILED -> "失败：${error ?: "ERROR"}"
                else -> stopMessage(reason)
            }
        )
    }

    private fun notifyUpdate() {
        updateListeners.forEach { runCatching { it() } }
    }

    private fun emitMessage(message: String) {
        messageListeners.forEach { runCatching { it(message) } }
    }

    private fun pauseMessage(reason: PauseReason): String = when (reason) {
        PauseReason.USER -> "已暂停"
        PauseReason.ROTATION -> "屏幕方向变化，已暂停"
        PauseReason.SCREEN_OFF -> "屏幕熄灭，已暂停"
        PauseReason.TARGET_MISMATCH -> "前台应用切换，已暂停"
        PauseReason.PERMISSION_LOST -> "权限丢失，已暂停"
    }

    private fun pauseLabel(reason: PauseReason?): String = when (reason) {
        PauseReason.ROTATION -> "方向变化"
        PauseReason.SCREEN_OFF -> "屏幕熄灭"
        PauseReason.TARGET_MISMATCH -> "目标不匹配"
        PauseReason.PERMISSION_LOST -> "权限丢失"
        else -> "用户暂停"
    }

    private fun stopMessage(reason: StopReason): String = when (reason) {
        StopReason.COMPLETE -> "完成"
        StopReason.USER -> "已停止"
        StopReason.KILLED -> "进程被杀，会话已中断"
        StopReason.TIMEOUT -> "超时，已停止"
        StopReason.ERROR -> "出错，已停止"
        StopReason.TARGET_MISMATCH -> "目标应用不匹配，已停止"
        StopReason.PERMISSION_LOST -> "权限丢失，已停止"
    }

    private fun stopLabel(reason: StopReason?): String = when (reason) {
        StopReason.COMPLETE -> "完成"
        StopReason.KILLED -> "进程被杀"
        StopReason.TIMEOUT -> "超时"
        StopReason.ERROR -> "出错"
        StopReason.TARGET_MISMATCH -> "目标不匹配"
        StopReason.PERMISSION_LOST -> "权限丢失"
        else -> "用户停止"
    }
}
