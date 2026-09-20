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
    fun `GAUGE_CATALOG is DASHBOARD_PIDS plus rpm, speed, engine load, fuel rate and instant mpg, and nothing else`() {
        assertEquals(
            DASHBOARD_PIDS.map { it.id }.toSet() + PidIds.RPM + SPEED_PID_ID + ENGINE_LOAD_PID_ID +
                FUEL_RATE_PID_ID + INSTANT_MPG_PID_ID,
            GAUGE_CATALOG.map { it.id }.toSet(),
        )
    }

    @Test
    fun `OBD-61 speed is a swap-only catalog gauge, never a default dashboard tile`() {
        // Same discipline as rpm: adding speed to DASHBOARD_PIDS would make it a default-visible
        // tile (DEFAULT_GAUGE_ORDER derives from DASHBOARD_PIDS). It must be picker-only.
        assertFalse(SPEED_PID_ID in DASHBOARD_PIDS_BY_ID)
        assertTrue(SPEED_PID_ID in GAUGE_CATALOG_BY_ID)
    }

    @Test
    fun `OBD-61 speed is declared in MPH, verified, and classifies NEUTRAL`() {
        val speed = GAUGE_CATALOG_BY_ID.getValue(SPEED_PID_ID)
        assertEquals(MeasurementUnit.MPH, speed.unit)
        // Standard mode-01 PID 010D — SAE-standard, not a hypothesis.
        assertTrue(speed.verified)
        assertEquals("Speed", speed.label)
        // No seed threshold entry → neutral coloring, like rpm and boost.
        assertFalse(SPEED_PID_ID in ThresholdConfig.seed)
        assertEquals(ThresholdZone.NEUTRAL, ThresholdConfig.classify(SPEED_PID_ID, ARBITRARY_SPEED_VALUE))
    }

    @Test
    fun `OBD-86 engine load is a swap-only catalog gauge, never a default dashboard tile`() {
        // Same discipline as rpm and speed: adding engine load to DASHBOARD_PIDS would make it a
        // default-visible tile. It must be picker-only.
        assertFalse(ENGINE_LOAD_PID_ID in DASHBOARD_PIDS_BY_ID)
        assertTrue(ENGINE_LOAD_PID_ID in GAUGE_CATALOG_BY_ID)
    }

    @Test
    fun `OBD-86 engine load is declared in PERCENT, verified, and classifies NEUTRAL`() {
        val engineLoad = GAUGE_CATALOG_BY_ID.getValue(ENGINE_LOAD_PID_ID)
        assertEquals(MeasurementUnit.PERCENT, engineLoad.unit)
        // Standard mode-01 PID 0104 — SAE-standard, not a hypothesis, and confirmed answering on
        // this van (~56 % observed, docs/hardware/session-2026-08-12.md).
        assertTrue(engineLoad.verified)
        assertEquals("Load", engineLoad.label)
        // No seed threshold entry → neutral coloring, like rpm, speed and boost.
        assertFalse(ENGINE_LOAD_PID_ID in ThresholdConfig.seed)
        assertEquals(
            ThresholdZone.NEUTRAL,
            ThresholdConfig.classify(ENGINE_LOAD_PID_ID, ARBITRARY_ENGINE_LOAD_VALUE),
        )
    }

    @Test
    fun `OBD-87 fuel rate is a swap-only catalog gauge, never a default dashboard tile`() {
        // Same discipline as rpm/speed/engine load: adding fuel rate to DASHBOARD_PIDS would make
        // it a default-visible tile. It must be picker-only — its real job is putting `015E` on
        // the wire for InstantMpgDataSource to consume, a tile of its own is a bonus.
        assertFalse(FUEL_RATE_PID_ID in DASHBOARD_PIDS_BY_ID)
        assertTrue(FUEL_RATE_PID_ID in GAUGE_CATALOG_BY_ID)
    }

    @Test
    fun `OBD-87 fuel rate is declared in LITERS_PER_HOUR, verified, and classifies NEUTRAL`() {
        val fuelRate = GAUGE_CATALOG_BY_ID.getValue(FUEL_RATE_PID_ID)
        assertEquals(MeasurementUnit.LITERS_PER_HOUR, fuelRate.unit)
        // Standard mode-01 PID 015E — live-verified at the protocol layer since OBD-58.
        assertTrue(fuelRate.verified)
        assertEquals("Fuel Rate", fuelRate.label)
        // No seed threshold entry → neutral coloring, like rpm, speed, engine load and boost.
        assertFalse(FUEL_RATE_PID_ID in ThresholdConfig.seed)
        assertEquals(ThresholdZone.NEUTRAL, ThresholdConfig.classify(FUEL_RATE_PID_ID, ARBITRARY_FUEL_RATE_VALUE))
    }

    @Test
    fun `OBD-87 instant mpg is a swap-only catalog gauge, never a default dashboard tile`() {
        assertFalse(INSTANT_MPG_PID_ID in DASHBOARD_PIDS_BY_ID)
        assertTrue(INSTANT_MPG_PID_ID in GAUGE_CATALOG_BY_ID)
    }

    @Test
    fun `OBD-87 instant mpg is declared in MILES_PER_GALLON, unverified (computed estimate), and classifies NEUTRAL`() {
        val instantMpg = GAUGE_CATALOG_BY_ID.getValue(INSTANT_MPG_PID_ID)
        assertEquals(MeasurementUnit.MILES_PER_GALLON, instantMpg.unit)
        // Computed, like boost — badged unverified until it's more than a model of two live inputs.
        assertFalse(instantMpg.verified)
        assertEquals("MPG", instantMpg.label)
        // No seed threshold entry → neutral coloring, like boost.
        assertFalse(INSTANT_MPG_PID_ID in ThresholdConfig.seed)
        assertEquals(ThresholdZone.NEUTRAL, ThresholdConfig.classify(INSTANT_MPG_PID_ID, ARBITRARY_MPG_VALUE))
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

    // OBD-64: the grid-keyed overloads — "shown elsewhere" is decided by the placed-id set now.

    @Test
    fun `candidateGaugesFor(placedIds) returns current plus unplaced ids in stable catalog order`() {
        val placed = setOf(PidIds.COOLANT, PidIds.TRANS_TEMP, PidIds.OIL_TEMP, PidIds.BOOST)
        val candidates = candidateGaugesFor(PidIds.COOLANT, placed).map { it.id }

        // OBD-66: current + unplaced-elsewhere ids, each at its GAUGE_CATALOG slot (coolant is first
        // in the catalog, so here it happens to lead) — trans/oil/boost placed elsewhere are dropped.
        // OBD-87 appended fuelRate/instantMpg to the catalog, so both trail here too.
        assertEquals(
            listOf(PidIds.COOLANT, PidIds.RPM, SPEED_PID_ID, ENGINE_LOAD_PID_ID, FUEL_RATE_PID_ID, INSTANT_MPG_PID_ID),
            candidates,
        )
    }

    @Test
    fun `candidateGaugesFor(placedIds) is a stable ribbon - a lower-index candidate sits LEFT of current`() {
        // Current gauge = speed; coolant is unplaced so it is offered. Because ordering is the
        // stable GAUGE_CATALOG order, coolant (index 0) lands BEFORE speed: reachable by
        // scrolling LEFT of the current gauge, later ids would be RIGHT.
        val placed = setOf(SPEED_PID_ID, PidIds.TRANS_TEMP, PidIds.OIL_TEMP, PidIds.BOOST)
        val candidates = candidateGaugesFor(SPEED_PID_ID, placed).map { it.id }

        // Ribbon order: coolant(0), rpm(4), speed(5), engineLoad(6), fuelRate(7), instantMpg(8) —
        // coolant and rpm precede the current speed; the OBD-87 pair is unplaced too and sits
        // AFTER speed (a RIGHT swipe away).
        assertEquals(
            listOf(PidIds.COOLANT, PidIds.RPM, SPEED_PID_ID, ENGINE_LOAD_PID_ID, FUEL_RATE_PID_ID, INSTANT_MPG_PID_ID),
            candidates,
        )
        val currentIndex = candidates.indexOf(SPEED_PID_ID)
        assertEquals(2, currentIndex)
        assertTrue(candidates.indexOf(PidIds.COOLANT) < currentIndex)
        assertTrue(candidates.indexOf(ENGINE_LOAD_PID_ID) > currentIndex)
        assertTrue(candidates.indexOf(FUEL_RATE_PID_ID) > currentIndex)
        assertTrue(candidates.indexOf(INSTANT_MPG_PID_ID) > currentIndex)
    }

    @Test
    fun `addableGaugesFor offers exactly the catalog ids not already placed`() {
        val placed = setOf(PidIds.COOLANT, PidIds.TRANS_TEMP, PidIds.OIL_TEMP, PidIds.BOOST)

        val addable = addableGaugesFor(placed).map { it.id }.toSet()

        assertEquals(setOf(PidIds.RPM, SPEED_PID_ID, ENGINE_LOAD_PID_ID, FUEL_RATE_PID_ID, INSTANT_MPG_PID_ID), addable)
    }

    @Test
    fun `addableGaugesFor is empty once every catalog id is placed`() {
        val allPlaced = GAUGE_CATALOG.map { it.id }.toSet()

        assertTrue(addableGaugesFor(allPlaced).isEmpty())
    }

    private companion object {
        const val ARBITRARY_RPM_VALUE = 3000.0
        const val ARBITRARY_SPEED_VALUE = 65.0
        const val ARBITRARY_ENGINE_LOAD_VALUE = 56.0
        const val ARBITRARY_FUEL_RATE_VALUE = 1.2
        const val ARBITRARY_MPG_VALUE = 18.0
    }
}
