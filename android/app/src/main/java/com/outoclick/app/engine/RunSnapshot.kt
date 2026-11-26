package com.outoclick.app.engine

import com.outoclick.app.model.Button
import com.outoclick.app.model.JitterProfile
import com.outoclick.app.model.Plan
import com.outoclick.app.model.TargetApp
import org.json.JSONArray
import org.json.JSONObject

data class SnapshotStep(
    val seq: Int,
    val buttonId: String,
    val enabled: Boolean,
    val pointGapMs: Int,
    val anchorX: Double,
    val anchorY: Double,
    val width: Double,
    val height: Double,
    val clickCount: Int,
    val clickGapMs: Int,
    val pressMs: Int,
)

/**
 * 运行快照：会话开始时的深拷贝。之后改按钮/方案都不影响本次运行。
 * 同时落库到 RunSession.snapshotJson，便于排障和断点对账。
 */
data class RunSnapshot(
    val planId: String,
    val planName: String,
    val targetPackage: String,
    val loopCount: Int,
    val jitter: JitterProfile,
    val displayW: Int,
    val displayH: Int,
    val rotation: Int,
    val steps: List<SnapshotStep>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("plan_id", planId)
        put("plan_name", planName)
        put("target_package", targetPackage)
        put("loop_count", loopCount)
        put("jitter", JSONObject().apply {
            put("enabled", jitter.enabled)
            put("delay_ms", jitter.delayMs)
            put("click_gap_ms", jitter.clickGapMs)
            put("press_ms", jitter.pressMs)
            put("position_px", jitter.positionPx)
        })
        put("display_w", displayW)
        put("display_h", displayH)
        put("rotation", rotation)
        put("steps", JSONArray().also { array ->
            steps.forEach { step ->
                array.put(JSONObject().apply {
                    put("seq", step.seq)
                    put("button_id", step.buttonId)
                    put("enabled", step.enabled)
                    put("point_gap_ms", step.pointGapMs)
                    put("anchor_x", step.anchorX)
                    put("anchor_y", step.anchorY)
                    put("width", step.width)
                    put("height", step.height)
                    put("click_count", step.clickCount)
                    put("click_gap_ms", step.clickGapMs)
                    put("press_ms", step.pressMs)
                })
            }
        })
    }

    fun toJsonString(): String = toJson().toString()

    companion object {
        fun from(
            plan: Plan,
            app: TargetApp,
            buttons: Map<String, Button>,
            displayW: Int,
            displayH: Int,
            rotation: Int,
        ): RunSnapshot {
            val snapshotSteps = plan.steps.sortedBy { it.seq }.mapNotNull { step ->
                val button = buttons[step.buttonId] ?: return@mapNotNull null
                SnapshotStep(
                    seq = step.seq,
                    buttonId = button.id,
                    enabled = step.enabled,
                    pointGapMs = step.pointGapMs.coerceIn(0, 60_000),
                    anchorX = button.anchorX,
                    anchorY = button.anchorY,
                    width = button.width,
                    height = button.height,
                    clickCount = button.clickCount,
                    clickGapMs = button.clickGapMs,
                    pressMs = button.pressMs,
                )
            }
            return RunSnapshot(
                planId = plan.id,
                planName = plan.name,
                targetPackage = app.packageName,
                loopCount = plan.loopCount.coerceIn(1, 99),
                jitter = JitterProfile(
                    enabled = plan.jitter.enabled,
                    delayMs = plan.jitter.delayMs,
                    clickGapMs = plan.jitter.clickGapMs,
                    pressMs = plan.jitter.pressMs,
                    positionPx = plan.jitter.positionPx,
                ),
                displayW = displayW,
                displayH = displayH,
                rotation = rotation,
                steps = snapshotSteps,
            )
        }

        fun fromJson(text: String): RunSnapshot? {
            if (text.isBlank()) return null
            return runCatching {
                val root = JSONObject(text)
                val jitter = root.optJSONObject("jitter") ?: JSONObject()
                val steps = mutableListOf<SnapshotStep>()
                val array = root.optJSONArray("steps") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    steps.add(
                        SnapshotStep(
                            seq = item.optInt("seq", i + 1),
                            buttonId = item.optString("button_id"),
                            enabled = item.optBoolean("enabled", true),
                            pointGapMs = item.optInt("point_gap_ms", 1000).coerceIn(0, 60_000),
                            anchorX = item.optDouble("anchor_x", 0.5).coerceIn(0.0, 1.0),
                            anchorY = item.optDouble("anchor_y", 0.5).coerceIn(0.0, 1.0),
                            width = item.optDouble("width", 0.12).coerceIn(0.01, 1.0),
                            height = item.optDouble("height", 0.05).coerceIn(0.01, 1.0),
                            clickCount = item.optInt("click_count", 1).coerceIn(1, 50),
                            clickGapMs = item.optInt("click_gap_ms", 120).coerceIn(0, 60_000),
                            pressMs = item.optInt("press_ms", 75).coerceIn(10, 500),
                        )
                    )
                }
                RunSnapshot(
                    planId = root.optString("plan_id"),
                    planName = root.optString("plan_name"),
                    targetPackage = root.optString("target_package"),
                    loopCount = root.optInt("loop_count", 1).coerceIn(1, 99),
                    jitter = JitterProfile(
                        enabled = jitter.optBoolean("enabled", true),
                        delayMs = jitter.optInt("delay_ms", 5).coerceIn(0, 200),
                        clickGapMs = jitter.optInt("click_gap_ms", 5).coerceIn(0, 200),
                        pressMs = jitter.optInt("press_ms", 5).coerceIn(0, 200),
                        positionPx = jitter.optInt("position_px", 3).coerceIn(0, 200),
                    ),
                    displayW = root.optInt("display_w", 0),
                    displayH = root.optInt("display_h", 0),
                    rotation = root.optInt("rotation", 0),
                    steps = steps,
                )
            }.getOrNull()
        }
    }
}
