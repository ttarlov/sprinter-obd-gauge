package com.revel.obdgauge.app.gauge

import org.junit.Assert.assertEquals
import org.junit.Test

/** OBD-72 device fix: gauge text sizes scale with the tile's own measured dimension. */
class GaugeTextScaleTest {
    @Test
    fun `scaledFontSizePx is the fraction of dimension within the clamp range`() {
        assertEquals(32f, scaledFontSizePx(dimensionPx = 160f, fraction = 0.2f, minPx = 16f, maxPx = 48f), 0f)
    }

    @Test
    fun `scaledFontSizePx clamps a small dimension to the floor`() {
        assertEquals(16f, scaledFontSizePx(dimensionPx = 40f, fraction = 0.2f, minPx = 16f, maxPx = 48f), 0f)
    }

    @Test
    fun `scaledFontSizePx clamps a large dimension to the ceiling`() {
        assertEquals(48f, scaledFontSizePx(dimensionPx = 500f, fraction = 0.2f, minPx = 16f, maxPx = 48f), 0f)
    }

    @Test
    fun `scaledFontSizePx at exactly the floor or ceiling dimension`() {
        // fraction*dimension landing exactly on a bound stays at that bound, not clamped past it.
        assertEquals(16f, scaledFontSizePx(dimensionPx = 80f, fraction = 0.2f, minPx = 16f, maxPx = 48f), 0f)
        assertEquals(48f, scaledFontSizePx(dimensionPx = 240f, fraction = 0.2f, minPx = 16f, maxPx = 48f), 0f)
    }

    @Test
    fun `scaledFontSizePx with zero dimension clamps to the floor rather than going to zero`() {
        assertEquals(16f, scaledFontSizePx(dimensionPx = 0f, fraction = 0.2f, minPx = 16f, maxPx = 48f), 0f)
    }
}
