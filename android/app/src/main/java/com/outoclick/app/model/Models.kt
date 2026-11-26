package com.outoclick.app.model

import java.util.UUID

/** 生成短 id，便于日志和界面展示。 */
fun newId(prefix: String = ""): String =
    prefix + UUID.randomUUID().toString().replace("-", "").take(12)

enum class PlanStatus { DRAFT, SAVED, ARCHIVED }

enum class SessionStatus {
    PENDING, RUNNING, PAUSED, FINISHED, STOPPED, FAILED;

    val isTerminal: Boolean
        get() = this == FINISHED || this == STOPPED || this == FAILED

    val isActive: Boolean
        get() = !isTerminal
}

enum class PauseReason { USER, ROTATION, SCREEN_OFF, TARGET_MISMATCH, PERMISSION_LOST }

enum class StopReason { COMPLETE, USER, KILLED, TIMEOUT, ERROR, TARGET_MISMATCH, PERMISSION_LOST }

/**
 * 目标应用。v1 只记录包名，不记录安装路径（更新/分身都会让路径失效）。
 * 坐标是归一化的，绑定方向/显示尺寸用于运行前守卫。
 */
data class TargetApp(
    var id: String = newId("app_"),
    var packageName: String = "",
    var label: String = "",
    var boundRotation: Int = 0,
    var boundDisplayW: Int = 0,
    var boundDisplayH: Int = 0,
    var firstBoundAt: Long = System.currentTimeMillis(),
    var lastRunAt: Long = 0L,
)

/**
 * 按钮库中的按钮：描述“点哪里、多大热区、怎么点”，与具体方案无关。
 * 同一个按钮可以被多个方案/多个步骤引用。
 */
data class Button(
    var id: String = newId("btn_"),
    var targetAppId: String = "",
    var label: String = "",
    /** 中心点，0..1 */
    var anchorX: Double = 0.5,
    var anchorY: Double = 0.5,
    /** 热区，0.01..1，默认 0.12 x 0.05 */
    var width: Double = 0.12,
    var height: Double = 0.05,
    /** 同一按钮连续点击次数，1..50 */
    var clickCount: Int = 1,
    /** 点击间隔：上一次抬起 -> 下一次按下，0..60000；clickCount=1 时无效 */
    var clickGapMs: Int = 120,
    /** 点击时长：按下 -> 抬起，10..500 */
    var pressMs: Int = 75,
)

/**
 * 随机化参数。v1 按测试 M6 默认常开，固定 ±5ms / ±3px，不暴露 UI。
 * enabled=false 时所有抖动为 0。
 */
data class JitterProfile(
    var enabled: Boolean = true,
    var delayMs: Int = 5,
    var clickGapMs: Int = 5,
    var pressMs: Int = 5,
    var positionPx: Int = 3,
)

/**
 * 方案编排步骤：引用一个按钮，并决定“先点谁、隔多久、是否启用”。
 * seq 从 1 开始连续。
 */
data class PlanStep(
    var id: String = newId("st_"),
    var buttonId: String = "",
    var seq: Int = 1,
    var enabled: Boolean = true,
    /** 点位间隔：本步最后一次抬起 -> 下一步第一次按下；跨轮同样生效。0..60000 */
    var pointGapMs: Int = 1000,
)

data class Plan(
    var id: String = newId("plan_"),
    var targetAppId: String? = null,
    var name: String = "未命名",
    var status: PlanStatus = PlanStatus.DRAFT,
    var loopCount: Int = 1,
    var jitter: JitterProfile = JitterProfile(),
    var version: Int = 0,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var lastRunAt: Long = 0L,
    var deleted: Boolean = false,
    val steps: MutableList<PlanStep> = mutableListOf(),
) {
    /** 与文档字段 anti_detect_enabled 对齐；v1 默认 true。 */
    val antiDetectEnabled: Boolean
        get() = jitter.enabled

    fun enabledSteps(): List<PlanStep> =
        steps.filter { it.enabled }.sortedBy { it.seq }

    fun nextSeq(): Int = (steps.maxOfOrNull { it.seq } ?: 0) + 1

    fun resequence() {
        steps.sortedBy { it.seq }.forEachIndexed { index, step -> step.seq = index + 1 }
    }
}

/**
 * 一次运行会话。运行态唯一来源；Plan.status 不承载 RUNNING。
 * lastProgressAt 使用 elapsedRealtime，看门狗据此判断无进度超时。
 */
data class RunSession(
    var id: String = newId("run_"),
    var planId: String = "",
    var planVersion: Int = 0,
    var targetAppId: String? = null,
    var targetPackage: String = "",
    /** 运行时深拷贝快照，按钮在会话开始后被改也不影响本次运行 */
    var snapshotJson: String = "",
    var status: SessionStatus = SessionStatus.PENDING,
    var pausedReason: PauseReason? = null,
    var stopReason: StopReason? = null,
    var currentStepSeq: Int = 0,
    var loopIndex: Int = 0,
    var lastProgressAt: Long = 0L,
    var foregroundPackage: String = "",
    var displayW: Int = 0,
    var displayH: Int = 0,
    var rotation: Int = 0,
    var errorCode: String? = null,
    var okCount: Int = 0,
    var failCount: Int = 0,
    var skipCount: Int = 0,
    var startedAt: Long = 0L,
    var endedAt: Long = 0L,
)

data class AppSettings(
    var activePlanId: String? = null,
    var schemaVersion: Int = 1,
)

data class AppState(
    var schemaVersion: Int = 1,
    val apps: MutableList<TargetApp> = mutableListOf(),
    val buttons: MutableList<Button> = mutableListOf(),
    val plans: MutableList<Plan> = mutableListOf(),
    val sessions: MutableList<RunSession> = mutableListOf(),
    var settings: AppSettings = AppSettings(),
) {
    fun plan(id: String?): Plan? =
        id?.let { pid -> plans.firstOrNull { it.id == pid && !it.deleted } }

    fun app(id: String?): TargetApp? =
        id?.let { aid -> apps.firstOrNull { it.id == aid } }

    fun appByPackage(packageName: String): TargetApp? =
        apps.firstOrNull { it.packageName == packageName }

    fun button(id: String?): Button? =
        id?.let { bid -> buttons.firstOrNull { it.id == bid } }

    fun activePlan(): Plan? = plan(settings.activePlanId)

    /** 未删除、未归档的方案占配额 */
    fun planCount(): Int = plans.count { !it.deleted && it.status != PlanStatus.ARCHIVED }

    fun nonTerminalSession(): RunSession? =
        sessions.lastOrNull { it.status.isActive }

    fun buttonsFor(appId: String): Map<String, Button> =
        buttons.filter { it.targetAppId == appId }.associateBy { it.id }

    fun buttonRefCount(buttonId: String): Int =
        plans.filter { !it.deleted }.sumOf { plan ->
            plan.steps.count { it.buttonId == buttonId }
        }

    fun buttonRefPlanCount(buttonId: String): Int =
        plans.count { plan ->
            !plan.deleted && plan.steps.any { it.buttonId == buttonId }
        }
}
