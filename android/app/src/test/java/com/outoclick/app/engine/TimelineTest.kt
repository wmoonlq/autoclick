package com.outoclick.app.engine

import com.outoclick.app.model.Button
import com.outoclick.app.model.Plan
import com.outoclick.app.model.PlanStep
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineTest {

    private fun button(
        id: String,
        clickCount: Int = 1,
        pressMs: Int = 75,
        clickGapMs: Int = 120,
    ) = Button(
        id = id,
        clickCount = clickCount,
        pressMs = pressMs,
        clickGapMs = clickGapMs,
    )

    private fun step(id: String, buttonId: String, seq: Int, pointGapMs: Int = 1000) =
        PlanStep(id = id, buttonId = buttonId, seq = seq, pointGapMs = pointGapMs)

    @Test
    fun `3 points 1 click 10 rounds equals 31250ms`() {
        val buttons = listOf("b1", "b2", "b3").associate { it to button(it) }
        val plan = Plan(
            loopCount = 10,
            steps = mutableListOf(
                step("s1", "b1", 1),
                step("s2", "b2", 2),
                step("s3", "b3", 3),
            ),
        )
        assertEquals(31_250L, Timeline.planDurationMs(plan, buttons))
    }

    @Test
    fun `3 points 1 click 3 rounds equals 8675ms`() {
        val buttons = listOf("b1", "b2", "b3").associate { it to button(it) }
        val plan = Plan(
            loopCount = 3,
            steps = mutableListOf(
                step("s1", "b1", 1),
                step("s2", "b2", 2),
                step("s3", "b3", 3),
            ),
        )
        assertEquals(8_675L, Timeline.planDurationMs(plan, buttons))
    }

    @Test
    fun `2 points 3 clicks per point single round is 2930ms before last gap`() {
        val buttons = mapOf(
            "b1" to button("b1", clickCount = 3),
            "b2" to button("b2", clickCount = 3),
        )
        val plan = Plan(
            loopCount = 1,
            steps = mutableListOf(
                step("s1", "b1", 1),
                step("s2", "b2", 2),
            ),
        )
        // 单步动作 = 3*75 + 2*120 = 465；单轮含 pointGap = (465+1000)*2 = 2930。
        // 跑完只扣掉最后一个 pointGap，所以整次预估 = 1930。
        assertEquals(2_930L, 465L * 2 + 1_000L * 2)
        assertEquals(1_930L, Timeline.planDurationMs(plan, buttons))
    }

    @Test
    fun `disabled steps are excluded`() {
        val buttons = listOf("b1", "b2", "b3").associate { it to button(it) }
        val plan = Plan(
            loopCount = 1,
            steps = mutableListOf(
                step("s1", "b1", 1),
                step("s2", "b2", 2).apply { enabled = false },
                step("s3", "b3", 3),
            ),
        )
        // 2 个启用步骤：(75+1000)*2 - 1000 = 1150
        assertEquals(1_150L, Timeline.planDurationMs(plan, buttons))
    }

    @Test
    fun `empty plan is zero`() {
        assertEquals(0L, Timeline.planDurationMs(Plan(), emptyMap()))
    }

    @Test
    fun `duration formatting`() {
        assertEquals("31.3s", Timeline.formatDuration(31_250L))
        assertEquals("0s", Timeline.formatDuration(0L))
    }
}
