package com.outoclick.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class StepType {
    TAP, LONG_PRESS, SWIPE, WAIT;

    companion object {
        fun from(value: String?): StepType =
            entries.firstOrNull { it.name == value } ?: TAP
    }
}

data class Step(
    var type: StepType = StepType.TAP,
    var x: Int = 0,
    var y: Int = 0,
    var x2: Int = 0,
    var y2: Int = 0,
    var durationMs: Int = 60,
    var delayAfterMs: Int = 300,
    var enabled: Boolean = true,
) {
    fun summary(): String = when (type) {
        StepType.TAP -> "点击 ($x, $y)"
        StepType.LONG_PRESS -> "长按 ($x, $y) · ${durationMs}ms"
        StepType.SWIPE -> "滑动 ($x,$y) → ($x2,$y2) · ${durationMs}ms"
        StepType.WAIT -> "等待 ${durationMs}ms"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.name)
        put("x", x)
        put("y", y)
        put("x2", x2)
        put("y2", y2)
        put("durationMs", durationMs)
        put("delayAfterMs", delayAfterMs)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(o: JSONObject): Step = Step(
            type = StepType.from(o.optString("type")),
            x = o.optInt("x"),
            y = o.optInt("y"),
            x2 = o.optInt("x2"),
            y2 = o.optInt("y2"),
            durationMs = o.optInt("durationMs", 60),
            delayAfterMs = o.optInt("delayAfterMs", 300),
            enabled = o.optBoolean("enabled", true),
        )

        fun tap(x: Int, y: Int, delay: Int = 300) =
            Step(type = StepType.TAP, x = x, y = y, durationMs = 60, delayAfterMs = delay)

        fun longPress(x: Int, y: Int, duration: Int = 800) =
            Step(type = StepType.LONG_PRESS, x = x, y = y, durationMs = duration)

        fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int = 300) =
            Step(type = StepType.SWIPE, x = x1, y = y1, x2 = x2, y2 = y2, durationMs = duration)

        fun wait(ms: Int) =
            Step(type = StepType.WAIT, durationMs = ms, delayAfterMs = 0)
    }
}

data class Script(
    var id: String = UUID.randomUUID().toString().replace("-", "").take(10),
    var name: String = "未命名脚本",
    var repeat: Int = 1,
    val steps: MutableList<Step> = mutableListOf(),
    var updatedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", 1)
        put("id", id)
        put("name", name)
        put("repeat", repeat)
        put("updatedAt", updatedAt)
        put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
    }

    fun exportJson(): String = toJson().toString(2)

    companion object {
        fun fromJson(o: JSONObject): Script? = try {
            val script = Script(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString().take(10) },
                name = o.optString("name").ifBlank { "导入脚本" },
                repeat = o.optInt("repeat", 1).coerceIn(1, 100000),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
            )
            val array = o.optJSONArray("steps") ?: JSONArray()
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { script.steps.add(Step.fromJson(it)) }
            }
            script
        } catch (_: Exception) {
            null
        }

        fun decode(text: String): Script? = try {
            fromJson(JSONObject(text.trim()))
        } catch (_: Exception) {
            null
        }

        fun demo(): Script = Script(
            name = "示例（请改坐标）",
            steps = mutableListOf(
                Step.tap(540, 1200, 800),
                Step.wait(600),
                Step.tap(540, 1600, 800),
            ),
        )
    }
}
