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

    @Test
    fun `extraTiles carries rpm, formatted and classified off the same code path as the core four`() {
        val readings =
            mapOf(
                PidIds.RPM to Reading(PidIds.RPM, RPM_VALUE, Instant.EPOCH, stale = false),
            )

        val state = toDashboardUiState(readings, LinkState.Ready, Instant.EPOCH)
        val rpmTile = state.tileFor(PidIds.RPM)

        assertEquals("RPM", rpmTile?.label)
        assertEquals("$RPM_WHOLE RPM", rpmTile?.valueText)
        // No seed threshold entry for rpm (see ThresholdConfig.seed) — same reason boost is
        // NEUTRAL: a swapped-in rpm tile must never appear falsely green/amber/red.
        assertEquals(ThresholdZone.NEUTRAL, rpmTile?.zone)
    }

    @Test
    fun `tileFor returns null for an id that's neither a core field nor in extraTiles`() {
        val state = toDashboardUiState(emptyMap(), LinkState.Ready, Instant.EPOCH)

        assertEquals(null, state.tileFor("notAGauge"))
    }

    // OBD-27: the badge's whole point is not letting the "unverified" fact get silently
    // filtered out anywhere near display — see GaugeCatalog.kt's `isEligible` KDoc for the
    // exact asymmetry this issue was warned to avoid (a picker filter defaulting to permissive
    // is fine; a badge that only shows for SOME unverified ids is not).
    @Test
    fun `oilTemp and transTemp are unverified, coolant and boost are verified, with no reading yet`() {
        val state = toDashboardUiState(emptyMap(), LinkState.Ready, Instant.EPOCH)

        assertEquals(false, state.oilTemp.verified)
        assertEquals(false, state.transTemp.verified)
        assertEquals(true, state.coolant.verified)
        assertEquals(true, state.boost.verified)
    }

    @Test
    fun `verified flag comes from the PidDefinition and survives a reading arriving`() {
        val readings =
            mapOf(
                PidIds.OIL_TEMP to Reading(PidIds.OIL_TEMP, OIL_VALUE, Instant.EPOCH, stale = false),
            )

        val state = toDashboardUiState(readings, LinkState.Ready, Instant.EPOCH)

        assertEquals(false, state.oilTemp.verified)
    }

    @Test
    fun `rawFrames carries a request summary and value text for every GAUGE_CATALOG id`() {
        val readings =
            mapOf(
                PidIds.TRANS_TEMP to Reading(PidIds.TRANS_TEMP, TRANS_VALUE, Instant.EPOCH, stale = false),
            )

        val state = toDashboardUiState(readings, LinkState.Ready, Instant.EPOCH)
        val rawFrame = state.rawFrames.getValue(PidIds.TRANS_TEMP)

        assertEquals(false, rawFrame.verified)
        assertEquals(true, rawFrame.hasReading)
        assertEquals("215°F", rawFrame.valueText)
        assertEquals("captured 0s ago", rawFrame.capturedText)
        assertEquals("ATSH07E12130 ATCRA032200000000 → 220543", rawFrame.requestSummary)
    }

    @Test
    fun `rawFrames reports no reading yet when the channel has never emitted`() {
        val state = toDashboardUiState(emptyMap(), LinkState.Ready, Instant.EPOCH)
        val rawFrame = state.rawFrames.getValue(PidIds.COOLANT)

        assertEquals(false, rawFrame.hasReading)
        assertEquals(null, rawFrame.capturedText)
    }

    private companion object {
        const val WIRE_VALUE_FAHRENHEIT = 235.0
        const val RPM_VALUE = 3000.0
        const val OIL_VALUE = 200.0
        const val TRANS_VALUE = 215.0
        const val RPM_WHOLE = "3000"
    }
}
