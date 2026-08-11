package com.revel.obdgauge.app.gauge

import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.Reading
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class GaugeFormattingTest {
    @Test
    fun `temperature values round to whole degrees with unit suffix`() {
        assertEquals("225°F", formatGaugeValue(225.0, MeasurementUnit.FAHRENHEIT))
        assertEquals("225°F", formatGaugeValue(224.6, MeasurementUnit.FAHRENHEIT))
    }

    @Test
    fun `boost keeps one decimal place`() {
        assertEquals("8.0 PSI", formatGaugeValue(8.0, MeasurementUnit.PSI))
        assertEquals("-2.0 PSI", formatGaugeValue(-2.0, MeasurementUnit.PSI))
        assertEquals("11.5 PSI", formatGaugeValue(11.5, MeasurementUnit.PSI))
    }

    @Test
    fun `boost sign is preserved when the whole part rounds to zero`() {
        assertEquals("-0.3 PSI", formatGaugeValue(-0.3, MeasurementUnit.PSI))
    }

    @Test
    fun `celsius keeps one decimal place (review round-1 NIT)`() {
        // 235 F -> ~112.78 C: whole-degree rounding would lose most of the resolution a
        // Fahrenheit-wire reading actually has.
        assertEquals("112.8°C", formatGaugeValue(112.78, MeasurementUnit.CELSIUS))
    }

    @Test
    fun `stale text reports whole elapsed seconds`() {
        val reading = Reading(id = "coolant", value = 190.0, timestamp = Instant.EPOCH, stale = true)
        val now = Instant.EPOCH.plusSeconds(12)
        assertEquals("last seen 12s ago", formatStaleText(reading, now))
    }

    @Test
    fun `stale text never goes negative`() {
        val reading = Reading(id = "coolant", value = 190.0, timestamp = Instant.EPOCH.plusSeconds(5), stale = true)
        val now = Instant.EPOCH
        assertEquals("last seen 0s ago", formatStaleText(reading, now))
    }
}
