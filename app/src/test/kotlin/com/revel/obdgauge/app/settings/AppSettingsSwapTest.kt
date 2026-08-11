package com.revel.obdgauge.app.settings

import com.revel.obdgauge.model.PidIds
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [AppSettings.withGaugeSwapped] (OBD-42): pure, plain JVM. `DashboardViewModel.swapGauge` is
 * the only production caller — see its test for the round-trip-through-a-repository coverage.
 */
class AppSettingsSwapTest {
    @Test
    fun `replaces the entry at oldId's position with newId, keeping visibility`() {
        val settings =
            AppSettings(
                gaugeOrder =
                    listOf(
                        GaugeOrderEntry(PidIds.COOLANT, visible = false),
                        GaugeOrderEntry(PidIds.TRANS_TEMP),
                        GaugeOrderEntry(PidIds.OIL_TEMP),
                        GaugeOrderEntry(PidIds.BOOST),
                    ),
            )

        val swapped = settings.withGaugeSwapped(PidIds.COOLANT, PidIds.RPM)

        assertEquals(
            listOf(
                GaugeOrderEntry(PidIds.RPM, visible = false),
                GaugeOrderEntry(PidIds.TRANS_TEMP),
                GaugeOrderEntry(PidIds.OIL_TEMP),
                GaugeOrderEntry(PidIds.BOOST),
            ),
            swapped.gaugeOrder,
        )
    }

    @Test
    fun `is a no-op when oldId is not present`() {
        val settings = AppSettings()

        val swapped = settings.withGaugeSwapped("notAGauge", PidIds.RPM)

        assertEquals(settings.gaugeOrder, swapped.gaugeOrder)
    }

    @Test
    fun `only touches the matching entry, others are untouched instances`() {
        val settings = AppSettings(gaugeOrder = listOf(GaugeOrderEntry(PidIds.COOLANT), GaugeOrderEntry(PidIds.BOOST)))

        val swapped = settings.withGaugeSwapped(PidIds.COOLANT, PidIds.RPM)

        assertEquals(GaugeOrderEntry(PidIds.BOOST), swapped.gaugeOrder[1])
    }
}
