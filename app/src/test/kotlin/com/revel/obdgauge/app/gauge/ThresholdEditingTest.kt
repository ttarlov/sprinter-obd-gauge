package com.revel.obdgauge.app.gauge

import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.PidIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OBD-66: the pure logic behind the gear/flip threshold editor (`ThresholdEditing.kt`). */
class ThresholdEditingTest {
    @Test
    fun `valueFor maps YELLOW to greenMax and RED to redMin`() {
        val band = GaugeThresholds(greenMax = 215.0, redMin = 225.0)
        assertEquals(215.0, band.valueFor(ThresholdColor.YELLOW))
        assertEquals(225.0, band.valueFor(ThresholdColor.RED))
    }

    @Test
    fun `valueFor returns null for an unset boundary`() {
        assertNull(GaugeThresholds().valueFor(ThresholdColor.YELLOW))
        assertNull(GaugeThresholds().valueFor(ThresholdColor.RED))
    }

    @Test
    fun `withValueFor YELLOW sets greenMax and leaves redMin untouched`() {
        val band = GaugeThresholds(greenMax = 215.0, redMin = 225.0)
        val next = band.withValueFor(ThresholdColor.YELLOW, 210.0)
        assertEquals(210.0, next.greenMax)
        assertEquals(225.0, next.redMin)
    }

    @Test
    fun `withValueFor RED sets redMin, pins redInclusive, and leaves greenMax untouched`() {
        val band = GaugeThresholds(greenMax = 215.0, redMin = 225.0, redInclusive = false)
        val next = band.withValueFor(ThresholdColor.RED, 230.0)
        assertEquals(230.0, next.redMin)
        assertTrue(next.redInclusive)
        assertEquals(215.0, next.greenMax)
    }

    @Test
    fun `stepThreshold moves by whole increments`() {
        assertEquals(220.0, stepThreshold(215.0, steps = 1), 0.0)
        assertEquals(205.0, stepThreshold(215.0, steps = -2), 0.0)
    }

    @Test
    fun `stepThreshold clamps to the min and max envelope`() {
        assertEquals(THRESHOLD_MIN, stepThreshold(THRESHOLD_MIN, steps = -1), 0.0)
        assertEquals(THRESHOLD_MAX, stepThreshold(THRESHOLD_MAX, steps = 1), 0.0)
    }

    @Test
    fun `initialDisplayThreshold falls back to 200 when the boundary is unset`() {
        val value =
            initialDisplayThreshold(
                GaugeThresholds(),
                ThresholdColor.YELLOW,
                MeasurementUnit.FAHRENHEIT,
                MeasurementUnit.FAHRENHEIT,
            )
        assertEquals(THRESHOLD_FALLBACK_DEFAULT, value, 0.0)
    }

    @Test
    fun `initialDisplayThreshold converts the stored wire value into the display unit`() {
        // 215 F stored, shown in Celsius ≈ 101.67 C.
        val value =
            initialDisplayThreshold(
                GaugeThresholds(greenMax = 215.0),
                ThresholdColor.YELLOW,
                MeasurementUnit.FAHRENHEIT,
                MeasurementUnit.CELSIUS,
            )
        assertEquals(101.666, value, 1e-2)
    }

    @Test
    fun `thresholdsWithDisplayValue converts back to the wire unit before folding it in`() {
        // Set YELLOW to 100 C on a Fahrenheit-wire gauge → stored greenMax = 212 F.
        val next =
            thresholdsWithDisplayValue(
                GaugeThresholds(redMin = 225.0),
                ThresholdColor.YELLOW,
                displayValue = 100.0,
                wireUnit = MeasurementUnit.FAHRENHEIT,
                displayUnit = MeasurementUnit.CELSIUS,
            )
        assertEquals(212.0, next.greenMax!!, 1e-9)
        assertEquals(225.0, next.redMin)
    }

    @Test
    fun `OBD-66 seed defaults are the researched caution and danger table`() {
        val coolant = ThresholdConfig.seed.getValue(PidIds.COOLANT)
        assertEquals(215.0, coolant.greenMax)
        assertEquals(225.0, coolant.redMin)
        assertTrue(coolant.redInclusive)

        val trans = ThresholdConfig.seed.getValue(PidIds.TRANS_TEMP)
        assertEquals(215.0, trans.greenMax)
        assertEquals(240.0, trans.redMin)
        assertTrue(trans.redInclusive)

        val oil = ThresholdConfig.seed.getValue(PidIds.OIL_TEMP)
        assertEquals(245.0, oil.greenMax)
        assertEquals(260.0, oil.redMin)
        assertTrue(oil.redInclusive)
    }
}
