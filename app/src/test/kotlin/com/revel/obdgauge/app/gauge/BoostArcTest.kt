package com.revel.obdgauge.app.gauge

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-math coverage for [boostArcFraction] (OBD-10 review M3: BoostArc math was untested). */
class BoostArcTest {
    @Test
    fun `psi below range minimum clamps to zero`() {
        assertEquals(0f, boostArcFraction(-5.0), FLOAT_TOLERANCE)
    }

    @Test
    fun `psi at range minimum is zero`() {
        assertEquals(0f, boostArcFraction(-2.0), FLOAT_TOLERANCE)
    }

    @Test
    fun `psi at zero is one tenth of the sweep`() {
        assertEquals(0.1f, boostArcFraction(0.0), FLOAT_TOLERANCE)
    }

    @Test
    fun `psi at eight is half the sweep`() {
        assertEquals(0.5f, boostArcFraction(8.0), FLOAT_TOLERANCE)
    }

    @Test
    fun `psi at range maximum is one`() {
        assertEquals(1f, boostArcFraction(18.0), FLOAT_TOLERANCE)
    }

    @Test
    fun `psi above range maximum clamps to one`() {
        assertEquals(1f, boostArcFraction(25.0), FLOAT_TOLERANCE)
    }

    private companion object {
        const val FLOAT_TOLERANCE = 0.0001f
    }
}
