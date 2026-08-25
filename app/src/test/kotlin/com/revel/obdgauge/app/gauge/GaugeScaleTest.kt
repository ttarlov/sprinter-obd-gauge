package com.revel.obdgauge.app.gauge

import org.junit.Assert.assertEquals
import org.junit.Test

/** OBD-72's per-gauge scale model: clamped fraction math + tick generation. */
class GaugeScaleTest {
    private val scale = GaugeScale(min = 0.0, max = 100.0, tick = 25.0)

    @Test
    fun `sweepFraction at the ends and midpoint`() {
        assertEquals(0f, scale.sweepFraction(0.0), 0f)
        assertEquals(1f, scale.sweepFraction(100.0), 0f)
        assertEquals(0.5f, scale.sweepFraction(50.0), 0f)
    }

    @Test
    fun `sweepFraction clamps out-of-range values to the nearest end`() {
        assertEquals(0f, scale.sweepFraction(-40.0), 0f)
        assertEquals(1f, scale.sweepFraction(250.0), 0f)
    }

    @Test
    fun `sweepFraction on a degenerate scale returns zero instead of dividing by zero`() {
        val degenerate = GaugeScale(min = 50.0, max = 50.0, tick = 10.0)
        assertEquals(0f, degenerate.sweepFraction(50.0), 0f)
        val inverted = GaugeScale(min = 100.0, max = 0.0, tick = 10.0)
        assertEquals(0f, inverted.sweepFraction(50.0), 0f)
    }

    @Test
    fun `tickValues steps from min to max inclusive`() {
        assertEquals(listOf(0.0, 25.0, 50.0, 75.0, 100.0), scale.tickValues())
    }

    @Test
    fun `tickValues truncates a non-exact step to land exactly on max`() {
        val uneven = GaugeScale(min = 0.0, max = 100.0, tick = 30.0)
        assertEquals(listOf(0.0, 30.0, 60.0, 90.0, 100.0), uneven.tickValues())
    }

    @Test
    fun `tickValues on a degenerate scale returns just the two ends`() {
        val degenerate = GaugeScale(min = 50.0, max = 50.0, tick = 10.0)
        assertEquals(listOf(50.0, 50.0), degenerate.tickValues())
        val zeroTick = GaugeScale(min = 0.0, max = 100.0, tick = 0.0)
        assertEquals(listOf(0.0, 100.0), zeroTick.tickValues())
        val negativeTick = GaugeScale(min = 0.0, max = 100.0, tick = -5.0)
        assertEquals(listOf(0.0, 100.0), negativeTick.tickValues())
    }

    @Test
    fun `displayTicks thins to the two ends when compact, full ladder otherwise`() {
        assertEquals(listOf(0.0, 100.0), scale.displayTicks(compact = true))
        assertEquals(scale.tickValues(), scale.displayTicks(compact = false))
    }

    @Test
    fun `GaugeScaleDefaults seeds every GAUGE_CATALOG channel that has a dashboard gauge`() {
        val expectedIds =
            setOf(
                com.revel.obdgauge.model.PidIds.COOLANT,
                com.revel.obdgauge.model.PidIds.TRANS_TEMP,
                com.revel.obdgauge.model.PidIds.OIL_TEMP,
                com.revel.obdgauge.model.PidIds.BOOST,
                com.revel.obdgauge.model.PidIds.RPM,
                SPEED_PID_ID,
            )
        assertEquals(expectedIds, GaugeScaleDefaults.seed.keys)
        expectedIds.forEach { id ->
            val scaleForId = GaugeScaleDefaults.seed.getValue(id)
            assert(scaleForId.min < scaleForId.max) { "$id's seed scale must have min < max" }
        }
    }

    @Test
    fun `forId falls back to a generic 0 to 100 scale for an unseeded id`() {
        assertEquals(GaugeScale(0.0, 100.0, 10.0), GaugeScaleDefaults.forId("some-future-channel"))
    }
}
