package com.outoclick.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientationPolicyTest {

    private fun snap(w: Int, h: Int, r: Int) = OrientationSnapshot(w, h, r)

    @Test
    fun `uncalibrated is rejected`() {
        val result = OrientationPolicy.check(snap(0, 0, -1), snap(1080, 2340, 0))
        assertTrue(result is OrientationCheck.Reject)
        assertEquals("UNCALIBRATED", (result as OrientationCheck.Reject).code)
    }

    @Test
    fun `portrait bound and portrait current passes`() {
        val result = OrientationPolicy.check(snap(1080, 2340, 0), snap(1080, 2340, 0))
        assertTrue(result is OrientationCheck.Ok)
    }

    @Test
    fun `portrait bound and landscape current is rejected`() {
        val result = OrientationPolicy.check(snap(1080, 2340, 0), snap(2340, 1080, 1))
        assertTrue(result is OrientationCheck.Reject)
        assertEquals("ROTATION", (result as OrientationCheck.Reject).code)
    }

    @Test
    fun `small aspect change is allowed`() {
        val result = OrientationPolicy.check(snap(1080, 2340, 0), snap(1080, 2280, 0))
        assertTrue(result is OrientationCheck.Ok)
    }

    @Test
    fun `large aspect change is rejected`() {
        val result = OrientationPolicy.check(snap(1080, 2340, 0), snap(1080, 1600, 0))
        assertTrue(result is OrientationCheck.Reject)
        assertEquals("DISPLAY", (result as OrientationCheck.Reject).code)
    }

    @Test
    fun `180 degree inversion is rejected`() {
        val result = OrientationPolicy.check(snap(1080, 2340, 0), snap(1080, 2340, 2))
        assertTrue(result is OrientationCheck.Reject)
        assertEquals("ROTATION_180", (result as OrientationCheck.Reject).code)
    }

    @Test
    fun `landscape bound and landscape current passes`() {
        val result = OrientationPolicy.check(snap(2340, 1080, 1), snap(2340, 1080, 1))
        assertTrue(result is OrientationCheck.Ok)
    }

    @Test
    fun `landscape reverse is rejected as 180`() {
        val result = OrientationPolicy.check(snap(2340, 1080, 1), snap(2340, 1080, 3))
        assertTrue(result is OrientationCheck.Reject)
        assertEquals("ROTATION_180", (result as OrientationCheck.Reject).code)
    }
}
