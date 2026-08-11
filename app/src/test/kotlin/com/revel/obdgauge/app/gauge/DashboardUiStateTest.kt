package com.revel.obdgauge.app.gauge

import com.revel.obdgauge.app.settings.UnitPreferences
import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.Reading
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * Review round-1 M1: classification must run against the raw wire-unit [Reading.value], never
 * the display-converted value — otherwise selecting a different display unit would silently
 * change which threshold zone a reading falls into, not just how it's printed.
 */
class DashboardUiStateTest {
    @Test
    fun `selecting Celsius display does not change which zone a Fahrenheit-wire reading falls into`() {
        // Coolant's wire unit is FAHRENHEIT (DASHBOARD_PIDS_BY_ID). 235 F is RED per
        // ThresholdConfig.seed (green under 220, red over 230) — but 235 F converted to
        // Celsius (~112.8 C) falls under every seed boundary, so if classification ever ran
        // against the display-converted value instead of the wire-unit reading, this would
        // misclassify as GREEN instead of RED.
        val readings =
            mapOf(
                PidIds.COOLANT to Reading(PidIds.COOLANT, WIRE_VALUE_FAHRENHEIT, Instant.EPOCH, stale = false),
            )
        val units = UnitPreferences(temperatureUnit = MeasurementUnit.CELSIUS)

        val state = toDashboardUiState(readings, LinkState.Ready, Instant.EPOCH, units = units)

        assertEquals(ThresholdZone.RED, state.coolant.zone)
    }

    private companion object {
        const val WIRE_VALUE_FAHRENHEIT = 235.0
    }
}
