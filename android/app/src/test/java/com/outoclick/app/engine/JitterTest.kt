package com.outoclick.app.engine

import org.junit.Assert.assertTrue
import org.junit.Test

class JitterTest {

    @Test
    fun `time never goes negative`() {
        repeat(1_000) {
            val value = Jitter.time(base = 0, jitter = 20, min = 0, max = 60_000)
            assertTrue(value >= 0L)
        }
    }

    @Test
    fun `press stays inside 10 to 500`() {
        repeat(1_000) {
            val low = Jitter.press(base = 10, jitter = 20)
            val high = Jitter.press(base = 500, jitter = 20)
            assertTrue(low in 10L..500L)
            assertTrue(high in 10L..500L)
        }
    }

    @Test
    fun `position stays on screen`() {
        val display = 1080
        repeat(1_000) {
            val x = Jitter.position(anchor = 0.0, displaySize = display, jitterPx = 30, hotZonePx = 20)
            val y = Jitter.position(anchor = 1.0, displaySize = display, jitterPx = 30, hotZonePx = 20)
            assertTrue(x in 0 until display)
            assertTrue(y in 0 until display)
        }
    }
}
