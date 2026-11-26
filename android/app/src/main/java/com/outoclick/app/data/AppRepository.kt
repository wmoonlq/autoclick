package com.outoclick.app.data

import android.content.Context
import android.util.Log
import com.outoclick.app.model.AppState
import com.outoclick.app.model.Button
import com.outoclick.app.model.Plan
import com.outoclick.app.model.PlanStatus
import com.outoclick.app.model.PlanStep
import com.outoclick.app.model.RunSession
import com.outoclick.app.model.SessionStatus
import com.outoclick.app.model.StopReason
import com.outoclick.app.model.TargetApp
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SQLite 数据仓库。
 *
 * - apps / buttons / plans / plan_steps / settings 是结构表；
 * - sessions 是运行历史，独立更新，保留最近 50 条；
 * - 所有结构写入走事务；运行进度只 UPDATE 一条 session。
 * - 内存里保留 AppState 作为读模型，UI/引擎通过 [read] 读取。
 */
class AppRepository(context: Context) {

    companion object {
        private const val TAG = "AppRepository"
    }

    private val app = context.applicationContext
    private val db = DatabaseHelper(app)
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val legacyMarker = File(File(app.filesDir, "v1").apply { mkdirs() }, "legacy_migrated")

    @Volatile
    private var state: AppState = db.loadState()

    init {
        migrateLegacyIfNeeded()
    }

    /** 只读快照。不要在仓库外直接修改返回对象。 */
    fun read(): AppState = state

    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun persist() {
        synchronized(lock) { db.saveState(state) }
    }

    fun update(block: (AppState) -> Unit) {
        mutate { block(it) }
    }

    fun <T> mutate(block: (AppState) -> T): T {
        val result = synchronized(lock) {
            block(state).also { db.saveState(state) }
        }
        notifyChanged()
        return result
    }

    // ------------------------------------------------------------------
    // 方案
    // ------------------------------------------------------------------

    fun createPlan(): Plan? = mutate { s ->
        if (s.planCount() >= 10) return@mutate null
        val plan = Plan(status = PlanStatus.DRAFT)
        s.plans.add(plan)
        plan
    }

    fun deletePlan(planId: String) {
        update { s ->
            val plan = s.plan(planId) ?: return@update
            plan.deleted = true
            plan.steps.clear()
            if (s.settings.activePlanId == planId) s.settings.activePlanId = null
            cleanupOrphanButtonsLocked(s)
        }
    }

    fun setActivePlan(planId: String?) {
        update { s ->
            s.settings.activePlanId = if (planId != null && s.plan(planId) != null) planId else null
        }
    }

    fun savePlan(planId: String, name: String, loopCount: Int): Boolean = mutate { s ->
        val plan = s.plan(planId) ?: return@mutate false
        val safeName = name.trim().ifBlank { "未命名" }.take(20)
        plan.name = safeName
        plan.loopCount = loopCount.coerceIn(1, 99)
        plan.status = PlanStatus.SAVED
        plan.version += 1
        plan.updatedAt = System.currentTimeMillis()
        true
    }

    fun updatePlanLoop(planId: String, loopCount: Int) {
        update { s ->
            s.plan(planId)?.loopCount = loopCount.coerceIn(1, 99)
        }
    }

    // ------------------------------------------------------------------
    // 目标应用 / 按钮 / 编排
    // ------------------------------------------------------------------

    fun bindPlanToApp(
        planId: String,
        packageName: String,
        label: String,
        rotation: Int,
        displayW: Int,
        displayH: Int,
    ): TargetApp? = mutate { s ->
        val plan = s.plan(planId) ?: return@mutate null
        val target = s.apps.firstOrNull { it.packageName == packageName } ?: TargetApp(
            packageName = packageName,
            label = label.ifBlank { packageName },
            boundRotation = rotation,
            boundDisplayW = displayW,
            boundDisplayH = displayH,
        ).also { s.apps.add(it) }

        target.label = label.ifBlank { packageName }
        target.boundRotation = rotation
        target.boundDisplayW = displayW
        target.boundDisplayH = displayH

        val previousAppId = plan.targetAppId
        if (previousAppId != null && previousAppId != target.id && plan.steps.isNotEmpty()) {
            // 从 A 应用切到 B 应用：旧点位对 B 没有意义，清空并清理孤儿按钮。
            plan.steps.clear()
            cleanupOrphanButtonsLocked(s)
        }

        // 旧数据迁移出来的按钮 targetAppId 为空，绑定时直接认领。
        plan.steps.forEach { step ->
            s.button(step.buttonId)?.let { button ->
                if (button.targetAppId.isBlank()) button.targetAppId = target.id
            }
        }

        plan.targetAppId = target.id
        plan.updatedAt = System.currentTimeMillis()
        target
    }

    fun addPoint(planId: String): Pair<Button, PlanStep>? = mutate { s ->
        val plan = s.plan(planId) ?: return@mutate null
        val appId = plan.targetAppId ?: return@mutate null
        if (plan.steps.size >= 30) return@mutate null
        val button = Button(
            targetAppId = appId,
            anchorX = 0.5,
            anchorY = 0.5,
            width = 0.12,
            height = 0.05,
        )
        s.buttons.add(button)
        val step = PlanStep(
            buttonId = button.id,
            seq = plan.nextSeq(),
            pointGapMs = 1000,
        )
        plan.steps.add(step)
        plan.updatedAt = System.currentTimeMillis()
        button to step
    }

    /** 把按钮库里已有的按钮插入当前方案，形成共享引用。 */
    fun addStepForButton(planId: String, buttonId: String): PlanStep? = mutate { s ->
        val plan = s.plan(planId) ?: return@mutate null
        val appId = plan.targetAppId ?: return@mutate null
        val button = s.button(buttonId) ?: return@mutate null
        if (button.targetAppId.isNotBlank() && button.targetAppId != appId) return@mutate null
        if (plan.steps.size >= 30) return@mutate null
        val step = PlanStep(
            buttonId = button.id,
            seq = plan.nextSeq(),
            pointGapMs = 1000,
        )
        plan.steps.add(step)
        plan.updatedAt = System.currentTimeMillis()
        step
    }

    fun updateButton(buttonId: String, block: (Button) -> Unit) {
        update { s ->
            s.button(buttonId)?.let { button ->
                block(button)
                button.anchorX = button.anchorX.coerceIn(0.0, 1.0)
                button.anchorY = button.anchorY.coerceIn(0.0, 1.0)
                button.width = button.width.coerceIn(0.01, 1.0)
                button.height = button.height.coerceIn(0.01, 1.0)
                button.clickCount = button.clickCount.coerceIn(1, 50)
                button.clickGapMs = button.clickGapMs.coerceIn(0, 60_000)
                button.pressMs = button.pressMs.coerceIn(10, 500)
            }
        }
    }

    fun updateStep(planId: String, stepId: String, block: (PlanStep) -> Unit) {
        update { s ->
            val plan = s.plan(planId) ?: return@update
            plan.steps.firstOrNull { it.id == stepId }?.let { step ->
                block(step)
                step.pointGapMs = step.pointGapMs.coerceIn(0, 60_000)
                plan.updatedAt = System.currentTimeMillis()
            }
        }
    }

    fun moveStep(planId: String, stepId: String, targetIndex: Int) {
        update { s ->
            val plan = s.plan(planId) ?: return@update
            val ordered = plan.steps.sortedBy { it.seq }.toMutableList()
            val from = ordered.indexOfFirst { it.id == stepId }
            if (from < 0) return@update
            val step = ordered.removeAt(from)
            val to = targetIndex.coerceIn(0, ordered.size)
            ordered.add(to, step)
            plan.steps.clear()
            plan.steps.addAll(ordered)
            plan.resequence()
            plan.updatedAt = System.currentTimeMillis()
        }
    }

    fun deleteStep(planId: String, stepId: String, deleteButton: Boolean) {
        update { s ->
            val plan = s.plan(planId) ?: return@update
            val step = plan.steps.firstOrNull { it.id == stepId } ?: return@update
            plan.steps.remove(step)
            plan.resequence()
            plan.updatedAt = System.currentTimeMillis()

            if (deleteButton) {
                s.plans.forEach { p ->
                    p.steps.removeAll { it.buttonId == step.buttonId }
                    p.resequence()
                }
                s.buttons.removeAll { it.id == step.buttonId }
            } else if (s.buttonRefCount(step.buttonId) == 0) {
                s.buttons.removeAll { it.id == step.buttonId }
            }
            cleanupOrphanButtonsLocked(s)
        }
    }

    fun toggleStepEnabled(planId: String, stepId: String, enabled: Boolean) {
        updateStep(planId, stepId) { it.enabled = enabled }
    }

    // ------------------------------------------------------------------
    // 会话
    // ------------------------------------------------------------------

    fun addSession(session: RunSession) {
        synchronized(lock) {
            state.sessions.removeAll { it.id == session.id }
            state.sessions.add(session)
            while (state.sessions.size > 50) state.sessions.removeAt(0)
            db.upsertSession(session)
        }
        notifyChanged()
    }

    /**
     * 运行进度更新：只 UPSERT 这一条 session，不重写结构表。
     * 不触发 UI 全量刷新；SessionManager 自己负责轻量状态回调。
     */
    fun persistSession(session: RunSession) {
        synchronized(lock) {
            if (state.sessions.none { it.id == session.id }) {
                state.sessions.add(session)
                while (state.sessions.size > 50) state.sessions.removeAt(0)
            }
            db.upsertSession(session)
        }
    }

    fun lastSession(): RunSession? = state.sessions.lastOrNull()

    fun currentNonTerminalSession(): RunSession? = state.nonTerminalSession()

    /**
     * 进程启动对账：旧的 PENDING/RUNNING/PAUSED 不可能还活着。
     * 按测试 M5-20 标记为 STOPPED(KILLED)。
     */
    fun reconcileSessionsOnStartup() {
        db.reconcileSessions()
        synchronized(lock) {
            val now = System.currentTimeMillis()
            state.sessions.filter { it.status.isActive }.forEach { session ->
                session.status = SessionStatus.STOPPED
                session.stopReason = StopReason.KILLED
                session.pausedReason = null
                session.errorCode = "PROCESS_KILLED"
                session.endedAt = now
            }
        }
    }

    // ------------------------------------------------------------------
    // 统计
    // ------------------------------------------------------------------

    fun planCount(): Int = state.planCount()

    fun buttonRefCount(buttonId: String): Int = state.buttonRefCount(buttonId)

    fun buttonRefPlanCount(buttonId: String): Int = state.buttonRefPlanCount(buttonId)

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun notifyChanged() {
        listeners.forEach { listener ->
            runCatching { listener() }.onFailure { Log.e(TAG, "listener 异常", it) }
        }
    }

    private fun migrateLegacyIfNeeded() {
        if (legacyMarker.exists()) return
        runCatching {
            val migrated = LegacyMigration.read(app)
            if (migrated != null && state.plans.isEmpty()) {
                state.plans.addAll(migrated.plans)
                state.buttons.addAll(migrated.buttons)
                db.saveState(state)
                Log.i(TAG, "已迁移旧脚本：${migrated.plans.size} 个方案")
            }
            legacyMarker.parentFile?.mkdirs()
            legacyMarker.writeText("1")
        }.onFailure {
            Log.e(TAG, "旧脚本迁移失败", it)
            runCatching { legacyMarker.writeText("failed") }
        }
    }

    private fun cleanupOrphanButtonsLocked(s: AppState) {
        val referenced = s.plans
            .filter { !it.deleted }
            .flatMap { plan -> plan.steps.map { it.buttonId } }
            .toSet()
        s.buttons.removeAll { it.id !in referenced }
    }
}
