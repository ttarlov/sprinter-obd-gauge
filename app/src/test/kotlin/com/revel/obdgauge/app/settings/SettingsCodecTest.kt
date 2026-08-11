package com.revel.obdgauge.app.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.revel.obdgauge.app.gauge.DASHBOARD_PIDS_BY_ID
import com.revel.obdgauge.app.gauge.GaugeThresholds
import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.PidIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure round-trip tests against `mutablePreferencesOf()` — no DataStore/file IO involved, see
 * `SettingsCodec.kt`'s file-level KDoc. Persistence-through-a-real-DataStore is
 * `SettingsRepositoryTest`'s job.
 */
class SettingsCodecTest {
    @Test
    fun `empty preferences decode to AppSettings defaults`() {
        assertEquals(AppSettings(), decodeAppSettings(emptyPreferences()))
    }

    @Test
    fun `a full settings object round-trips`() {
        val settings =
            AppSettings(
                // All four known ids present (reconciliation is a no-op here) — the
                // drop-unknown/append-missing behavior itself is covered by its own tests below.
                gaugeOrder =
                    listOf(
                        GaugeOrderEntry(PidIds.BOOST, visible = false),
                        GaugeOrderEntry(PidIds.COOLANT),
                        GaugeOrderEntry(PidIds.TRANS_TEMP),
                        GaugeOrderEntry(PidIds.OIL_TEMP),
                    ),
                thresholdOverrides =
                    mapOf(
                        PidIds.COOLANT to GaugeThresholds(greenMax = 200.0, redMin = 225.0),
                        PidIds.OIL_TEMP to GaugeThresholds(greenMax = 235.0, greenInclusive = true),
                    ),
                units = UnitPreferences(temperatureUnit = MeasurementUnit.CELSIUS, pressureUnit = MeasurementUnit.KPA),
                keepScreenOn = true,
                pollRate = PollRate.HZ_2,
            )

        val preferences = mutablePreferencesOf()
        encodeAppSettings(settings, preferences)
        val decoded = decodeAppSettings(preferences.toPreferences())

        assertEquals(settings, decoded)
    }

    @Test
    fun `a threshold with no boundaries round-trips its nulls`() {
        val settings = AppSettings(thresholdOverrides = mapOf(PidIds.BOOST to GaugeThresholds()))

        val preferences = mutablePreferencesOf()
        encodeAppSettings(settings, preferences)
        val decoded = decodeAppSettings(preferences.toPreferences())

        assertEquals(GaugeThresholds(), decoded.thresholdOverrides[PidIds.BOOST])
    }

    @Test
    fun `an empty gauge order string decodes to the default order rather than an empty list`() {
        val preferences = mutablePreferencesOf()
        encodeAppSettings(AppSettings(gaugeOrder = emptyList()), preferences)

        val decoded = decodeAppSettings(preferences.toPreferences())

        assertEquals(DEFAULT_GAUGE_ORDER, decoded.gaugeOrder)
    }

    @Test
    fun `decoding a gauge order containing an id no longer in the catalog drops it`() {
        val preferences = mutablePreferencesOf()
        encodeAppSettings(
            AppSettings(gaugeOrder = listOf(GaugeOrderEntry("retiredGauge"), GaugeOrderEntry(PidIds.COOLANT))),
            preferences,
        )

        val decoded = decodeAppSettings(preferences.toPreferences()).gaugeOrder

        assertFalse(decoded.any { it.id == "retiredGauge" })
        assertEquals(DASHBOARD_PIDS_BY_ID.keys, decoded.map { it.id }.toSet())
    }

    @Test
    fun `decoding a gauge order missing a known id appends it as visible, at the end`() {
        val preferences = mutablePreferencesOf()
        encodeAppSettings(
            AppSettings(
                gaugeOrder = listOf(GaugeOrderEntry(PidIds.BOOST), GaugeOrderEntry(PidIds.COOLANT, visible = false)),
            ),
            preferences,
        )

        val decoded = decodeAppSettings(preferences.toPreferences()).gaugeOrder

        assertEquals(DASHBOARD_PIDS_BY_ID.keys, decoded.map { it.id }.toSet())
        // The two persisted entries keep their relative order/visibility, up front...
        assertEquals(listOf(PidIds.BOOST, PidIds.COOLANT), decoded.take(2).map { it.id })
        assertFalse(decoded[1].visible)
        // ...and the two the catalog knows about but the saved order didn't are appended visible.
        val appended = decoded.drop(2)
        assertEquals(setOf(PidIds.TRANS_TEMP, PidIds.OIL_TEMP), appended.map { it.id }.toSet())
        assertTrue(appended.all { it.visible })
    }
}
