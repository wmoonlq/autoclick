package com.outoclick.app.data

import android.content.Context
import com.outoclick.app.Screen
import com.outoclick.app.model.AppState
import com.outoclick.app.model.Button
import com.outoclick.app.model.JitterProfile
import com.outoclick.app.model.Plan
import com.outoclick.app.model.PlanStatus
import com.outoclick.app.model.PlanStep
import com.outoclick.app.model.newId
import org.json.JSONObject
import java.io.File

/**
 * 旧版（v0.4）脚本迁移：
 * - TAP / LONG_PRESS -> Button + PlanStep
 * - SWIPE / WAIT 无法用 v1 的按钮模型表达，跳过（旧文件保留不删）
 * - 坐标从“基准像素”转换为归一化 anchor
 * - 旧方案没有目标应用，targetAppId 为空；首次绑定目标应用时认领按钮
 */
object LegacyMigration {

    private data class MigratedPlan(val plan: Plan, val buttons: List<Button>)

    fun read(context: Context): AppState? {
        val dir = File(context.filesDir, "scripts")
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return null
        if (files.isEmpty()) return null

        val screen = Screen.size(context)
        val state = AppState()
        files.forEach { file ->
            val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@forEach
            val migrated = buildPlan(root, screen.x, screen.y) ?: return@forEach
            state.plans.add(migrated.plan)
            state.buttons.addAll(migrated.buttons)
        }
        return state.takeIf { it.plans.isNotEmpty() }
    }

    private fun buildPlan(root: JSONObject, screenW: Int, screenH: Int): MigratedPlan? {
        val baseW = root.optInt("baseWidth", 0).takeIf { it > 0 } ?: screenW
        val baseH = root.optInt("baseHeight", 0).takeIf { it > 0 } ?: screenH
        if (baseW <= 0 || baseH <= 0) return null

        val plan = Plan(
            id = newId("plan_"),
            targetAppId = null,
            name = root.optString("name").ifBlank { "迁移方案" }.take(20),
            status = PlanStatus.SAVED,
            loopCount = root.optInt("repeat", 1).coerceIn(1, 99),
            jitter = JitterProfile(),
            version = 1,
            createdAt = root.optLong("updatedAt", System.currentTimeMillis()),
            updatedAt = root.optLong("updatedAt", System.currentTimeMillis()),
        )

        val steps = root.optJSONArray("steps") ?: return null
        val migratedButtons = mutableListOf<Button>()
        for (i in 0 until steps.length()) {
            val item = steps.optJSONObject(i) ?: continue
            val type = item.optString("type")
            if (type != "TAP" && type != "LONG_PRESS") continue
            val x = item.optInt("x", 0).coerceIn(0, baseW)
            val y = item.optInt("y", 0).coerceIn(0, baseH)
            val press = item.optInt("durationMs", if (type == "LONG_PRESS") 800 else 60)
                .coerceIn(10, 500)
            val button = Button(
                id = newId("btn_"),
                targetAppId = "",
                label = type,
                anchorX = (x.toDouble() / baseW).coerceIn(0.0, 1.0),
                anchorY = (y.toDouble() / baseH).coerceIn(0.0, 1.0),
                width = 0.12,
                height = 0.05,
                clickCount = 1,
                clickGapMs = 120,
                pressMs = press,
            )
            val step = PlanStep(
                id = newId("st_"),
                buttonId = button.id,
                seq = plan.steps.size + 1,
                enabled = item.optBoolean("enabled", true),
                pointGapMs = item.optInt("delayAfterMs", 300).coerceIn(0, 60_000),
            )
            migratedButtons.add(button)
            plan.steps.add(step)
        }
        if (plan.steps.isEmpty()) return null
        plan.resequence()
        return MigratedPlan(plan, migratedButtons)
    }
}
