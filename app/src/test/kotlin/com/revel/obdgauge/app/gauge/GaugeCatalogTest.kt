package com.revel.obdgauge.app.gauge

import com.revel.obdgauge.app.settings.DEFAULT_GAUGE_ORDER
import com.revel.obdgauge.app.settings.GaugeOrderEntry
import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.PidIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM: [GAUGE_CATALOG]/[candidateGaugesFor] are pure, no Compose/Android needed.
 * `DashboardScreenTest`/the picker's own compose tests cover the UI that reads these.
 */
class GaugeCatalogTest {
    @Test
    fun `GAUGE_CATALOG is DASHBOARD_PIDS plus rpm, and nothing else`() {
        assertEquals(DASHBOARD_PIDS.map { it.id }.toSet() + PidIds.RPM, GAUGE_CATALOG.map { it.id }.toSet())
    }

    @Test
    fun `rpm is not part of DASHBOARD_PIDS`() {
        // Deliberate: adding rpm to DASHBOARD_PIDS would make it a 5th always-visible tile on a
        // fresh install (DEFAULT_GAUGE_ORDER derives from DASHBOARD_PIDS) — see GaugeCatalog.kt.
        assertFalse(PidIds.RPM in DASHBOARD_PIDS_BY_ID)
        assertTrue(PidIds.RPM in GAUGE_CATALOG_BY_ID)
    }

    // B13 (round-1 review, reviews/OBD-24-round1.md): unpinned before this — RPM_PID_DEFINITION
    // never set `verified` explicitly, silently inheriting whatever PidDefinition's own frozen-
    // contract default happened to be. Standard mode-01 PID 010C is genuinely SAE-standard, not a
    // hypothesis, so `true` is the CORRECT value here (unlike GaugeTileUiState's OWN default,
    // which B8 flipped to the conservative `false` for ids this app can't vouch for at all) — this
    // pins that correctness against an accidental future default flip in :core:model.
    @Test
    fun `rpm is a verified standard PID, not a hypothesis`() {
        assertTrue(GAUGE_CATALOG_BY_ID.getValue(PidIds.RPM).verified)
    }

    @Test
    fun `rpm is declared in RPM units with no seed threshold entry, so it classifies NEUTRAL`() {
        assertEquals(MeasurementUnit.RPM, GAUGE_CATALOG_BY_ID.getValue(PidIds.RPM).unit)
        assertFalse(PidIds.RPM in ThresholdConfig.seed)
        assertEquals(ThresholdZone.NEUTRAL, ThresholdConfig.classify(PidIds.RPM, ARBITRARY_RPM_VALUE))
    }

    @Test
    fun `candidateGaugesFor lists the current gauge first`() {
        val candidates = candidateGaugesFor(PidIds.COOLANT, DEFAULT_GAUGE_ORDER)
        assertEquals(PidIds.COOLANT, candidates.first().id)
    }

    @Test
    fun `candidateGaugesFor excludes ids visible on another tile`() {
        val order =
            listOf(
                GaugeOrderEntry(PidIds.COOLANT),
                GaugeOrderEntry(PidIds.TRANS_TEMP),
                GaugeOrderEntry(PidIds.OIL_TEMP),
                GaugeOrderEntry(PidIds.BOOST),
            )
        val candidates = candidateGaugesFor(PidIds.COOLANT, order)

        // transTemp/oilTemp/boost are all visible elsewhere — not offered as swap-ins.
        assertFalse(candidates.any { it.id == PidIds.TRANS_TEMP })
        assertFalse(candidates.any { it.id == PidIds.OIL_TEMP })
        assertFalse(candidates.any { it.id == PidIds.BOOST })
        // rpm isn't visible anywhere — it IS offered.
        assertTrue(candidates.any { it.id == PidIds.RPM })
    }

    @Test
    fun `candidateGaugesFor offers an id that's hidden elsewhere (visible = false)`() {
        val order =
            listOf(
                GaugeOrderEntry(PidIds.COOLANT),
                GaugeOrderEntry(PidIds.TRANS_TEMP, visible = false),
            )
        val candidates = candidateGaugesFor(PidIds.COOLANT, order)

        // Hidden, not "displayed" — fair game as a candidate.
        assertTrue(candidates.any { it.id == PidIds.TRANS_TEMP })
    }

    @Test
    fun `candidateGaugesFor never excludes the current id even though it's technically visible there`() {
        val order = listOf(GaugeOrderEntry(PidIds.COOLANT))
        val candidates = candidateGaugesFor(PidIds.COOLANT, order)

        assertEquals(1, candidates.count { it.id == PidIds.COOLANT })
    }

    @Test
    fun `isEligible predicate filters candidates, current gauge exempt`() {
        val order = listOf(GaugeOrderEntry(PidIds.COOLANT))
        // Reject everything except the current id itself.
        val candidates = candidateGaugesFor(PidIds.COOLANT, order, isEligible = { false })

        assertEquals(listOf(PidIds.COOLANT), candidates.map { it.id })
    }

    @Test
    fun `demo default (permissive isEligible) offers every catalog gauge not shown elsewhere`() {
        val order = listOf(GaugeOrderEntry(PidIds.COOLANT))
        val candidates = candidateGaugesFor(PidIds.COOLANT, order)

        assertEquals(GAUGE_CATALOG.map { it.id }.toSet(), candidates.map { it.id }.toSet())
    }

    private companion object {
        const val ARBITRARY_RPM_VALUE = 3000.0
    }
}
