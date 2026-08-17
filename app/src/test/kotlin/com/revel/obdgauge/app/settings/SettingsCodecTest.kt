package com.revel.obdgauge.app.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.revel.obdgauge.app.gauge.DASHBOARD_PIDS_BY_ID
import com.revel.obdgauge.app.gauge.GAUGE_CATALOG_BY_ID
import com.revel.obdgauge.app.gauge.GaugeThresholds
import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.ObdRequest
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.PollPriority
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
                speedCorrectionFactor = 1.1,
            )

        val preferences = mutablePreferencesOf()
        encodeAppSettings(settings, preferences)
        val decoded = decodeAppSettings(preferences.toPreferences())

        assertEquals(settings, decoded)
    }

    @Test
    fun `OBD-61 the speed correction factor round-trips, and defaults to 1_0 when missing`() {
        // Present: a learned factor survives a round trip.
        val preferences = mutablePreferencesOf()
        encodeAppSettings(AppSettings(speedCorrectionFactor = 1.0834), preferences)
        assertEquals(1.0834, decodeAppSettings(preferences.toPreferences()).speedCorrectionFactor, 0.0)

        // Absent: empty preferences decode to the 1.0 default (no correction).
        assertEquals(1.0, decodeAppSettings(emptyPreferences()).speedCorrectionFactor, 0.0)
    }

    @Test
    fun `OBD-68 the per-column-count grid layouts round-trip, and default to empty when missing`() {
        val landscape =
            com.revel.obdgauge.app.gauge.grid.GridEngine.repack(
                4,
                listOf(
                    com.revel.obdgauge.app.gauge.grid
                        .GridPlacement(PidIds.BOOST, 0, 0, colSpan = 2, rowSpan = 1),
                    com.revel.obdgauge.app.gauge.grid
                        .GridPlacement(PidIds.COOLANT, 0, 0),
                    com.revel.obdgauge.app.gauge.grid
                        .GridPlacement(PidIds.OIL_TEMP, 0, 0),
                ),
            )
        val portrait =
            com.revel.obdgauge.app.gauge.grid.GridEngine.repack(
                2,
                listOf(
                    com.revel.obdgauge.app.gauge.grid
                        .GridPlacement(PidIds.COOLANT, 0, 0),
                    com.revel.obdgauge.app.gauge.grid
                        .GridPlacement(PidIds.OIL_TEMP, 0, 0),
                    com.revel.obdgauge.app.gauge.grid
                        .GridPlacement(PidIds.BOOST, 0, 0),
                ),
            )
        val preferences = mutablePreferencesOf()
        encodeAppSettings(AppSettings(gridLayoutsByColumns = mapOf(4 to landscape, 2 to portrait)), preferences)

        val decoded = decodeAppSettings(preferences.toPreferences()).gridLayoutsByColumns
        assertEquals(landscape, decoded[4])
        assertEquals(portrait, decoded[2])

        // Absent: no grid persisted decodes to an empty map (the dashboard then migrates from
        // gaugeOrder for whichever column count it needs).
        assertEquals(emptyMap<Int, Any>(), decodeAppSettings(emptyPreferences()).gridLayoutsByColumns)
    }

    @Test
    fun `OBD-68 a pre-OBD-68 single-layout string decodes as a one-entry map keyed by its own columns`() {
        // Simulates an existing install's persisted data: encoded with the OLD single-layout
        // GridLayoutCodec.encode, under the SAME preferences key OBD-68 reuses (see
        // SettingsCodec's KEY_GRID_LAYOUTS comment) — never actually written via encodeAppSettings
        // here, to faithfully reproduce "old data written by old code."
        val singleLayout =
            com.revel.obdgauge.app.gauge.grid.GridEngine.repack(
                4,
                listOf(
                    com.revel.obdgauge.app.gauge.grid
                        .GridPlacement(PidIds.COOLANT, 0, 0),
                ),
            )
        val oldFormatString =
            com.revel.obdgauge.app.gauge.grid.GridLayoutCodec
                .encode(singleLayout)
        val preferences = mutablePreferencesOf()
        preferences[
            androidx.datastore.preferences.core
                .stringPreferencesKey("grid_layout"),
        ] = oldFormatString

        val decoded = decodeAppSettings(preferences.toPreferences()).gridLayoutsByColumns

        assertEquals(mapOf(4 to singleLayout), decoded)
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

        // OBD-42 review round-1 M2: no auto-backfill anymore (see reconcileGaugeOrder's KDoc) —
        // dropping the unknown id is now the *only* thing this does, so only coolant survives.
        assertEquals(listOf(GaugeOrderEntry(PidIds.COOLANT)), decoded)
    }

    @Test
    fun `decoding a gauge order missing a known id no longer backfills it`() {
        // Pre-OBD-42 this auto-appended transTemp/oilTemp, visible, at the end. Review round-1
        // M2 removed that entirely: a swap can legitimately leave a core id "missing" from the
        // persisted order, and there is no way to tell that apart from genuine catalog drift
        // from the order's shape alone — see reconcileGaugeOrder's KDoc for the full story and
        // why a new catalog gauge is discoverable via the picker instead.
        val preferences = mutablePreferencesOf()
        encodeAppSettings(
            AppSettings(
                gaugeOrder = listOf(GaugeOrderEntry(PidIds.BOOST), GaugeOrderEntry(PidIds.COOLANT, visible = false)),
            ),
            preferences,
        )

        val decoded = decodeAppSettings(preferences.toPreferences()).gaugeOrder

        assertEquals(listOf(PidIds.BOOST, PidIds.COOLANT), decoded.map { it.id })
        assertTrue(decoded[0].visible)
        assertFalse(decoded[1].visible)
    }

    @Test
    fun `OBD-42 a persisted swap to a non-core id like rpm survives decode`() {
        // "rpm" isn't in DASHBOARD_PIDS_BY_ID (deliberately — see GaugeCatalog.kt), but it IS a
        // legal GAUGE_CATALOG id, so a swap that persisted it must not be reconciled away as
        // "unknown" the way a truly retired id would be.
        val preferences = mutablePreferencesOf()
        encodeAppSettings(
            AppSettings(
                gaugeOrder =
                    listOf(
                        GaugeOrderEntry(PidIds.RPM),
                        GaugeOrderEntry(PidIds.TRANS_TEMP),
                        GaugeOrderEntry(PidIds.OIL_TEMP),
                        GaugeOrderEntry(PidIds.BOOST),
                    ),
            ),
            preferences,
        )

        val decoded = decodeAppSettings(preferences.toPreferences()).gaugeOrder

        assertTrue(decoded.any { it.id == PidIds.RPM })
        assertEquals(4, decoded.size)
    }

    @Test
    fun `OBD-42 rpm is never auto-backfilled into a gauge order that never named it`() {
        // Auto-backfill is gone entirely now (review round-1 M2) — a swap candidate must only
        // ever appear because a user explicitly chose it (see SettingsCodec.kt's
        // reconcileGaugeOrder KDoc), never as a side effect of decoding.
        val decoded = decodeAppSettings(emptyPreferences()).gaugeOrder

        assertFalse(decoded.any { it.id == PidIds.RPM })
        assertEquals(DASHBOARD_PIDS_BY_ID.keys, decoded.map { it.id }.toSet())
    }

    @Test
    fun `OBD-42 M2 regression - a catalog that grew a 5th gauge does not resurrect a swapped-away core id`() {
        // Simulates a future DASHBOARD_PIDS growing a 5th entry (OBD-43's shape) WITHOUT
        // actually touching DASHBOARD_PIDS — reconcileGaugeOrder's catalog param exists exactly
        // so this drift scenario is directly testable. Probe that caught the original bug: the
        // slot-count heuristic this replaced saw a 4-slot swapped order against a 5-id catalog
        // as "short a slot" and backfilled coolant back in as a duplicate.
        // The production call site always passes GAUGE_CATALOG_BY_ID (core + swap-only extras
        // like rpm), so a faithful "catalog grew" simulation must too, or a legitimately-
        // persisted rpm swap would itself look like drift here rather than the case under test.
        val fiveGaugeCatalog: Map<String, PidDefinition> = GAUGE_CATALOG_BY_ID + (ENGINE_LOAD.id to ENGINE_LOAD)
        // A 4-slot persisted order where coolant was swapped away (e.g. to rpm) — same shape a
        // real OBD-42 swap produces.
        val swappedOrder =
            listOf(
                GaugeOrderEntry(PidIds.RPM),
                GaugeOrderEntry(PidIds.TRANS_TEMP),
                GaugeOrderEntry(PidIds.OIL_TEMP),
                GaugeOrderEntry(PidIds.BOOST),
            )

        val reconciled = reconcileGaugeOrder(swappedOrder, fiveGaugeCatalog)

        assertEquals(4, reconciled.size)
        assertFalse(reconciled.any { it.id == PidIds.COOLANT })
        assertFalse(reconciled.any { it.id == ENGINE_LOAD.id })

        // Round-2 hardening: a SHORT order (fewer entries than the global DASHBOARD_PIDS size)
        // must also reconcile to exactly its filtered input. The retired slot-count heuristic
        // gated on the GLOBAL catalog size, so the 4-slot case above short-circuited it and
        // could not detect the bug this test is named for — this one reaches the branch.
        val shortOrder =
            listOf(
                GaugeOrderEntry(PidIds.RPM),
                GaugeOrderEntry(PidIds.TRANS_TEMP),
                GaugeOrderEntry(PidIds.BOOST),
            )
        val shortReconciled = reconcileGaugeOrder(shortOrder, fiveGaugeCatalog)
        assertEquals(shortOrder, shortReconciled)
    }

    @Test
    fun `an all-unknown persisted order falls back to defaults instead of a zero-tile dashboard`() {
        // Round-2 MINOR: with backfill gone, total id turnover used to reconcile to an EMPTY
        // order — zero tiles, nothing to long-press, unrecoverable short of clearing app data.
        val preferences =
            androidx.datastore.preferences.core
                .mutablePreferencesOf()
        encodeAppSettings(
            AppSettings(gaugeOrder = listOf(GaugeOrderEntry("ghost"), GaugeOrderEntry("phantom"))),
            preferences,
        )
        val decoded = decodeAppSettings(preferences.toPreferences())
        assertEquals(AppSettings().gaugeOrder, decoded.gaugeOrder)
    }

    private companion object {
        // A synthetic "5th DASHBOARD_PIDS entry" for the M2 regression test above — not a real
        // catalog addition, just enough of a PidDefinition to populate a Map<String,
        // PidDefinition> the way `reconcileGaugeOrder`'s catalog param expects.
        val ENGINE_LOAD =
            PidDefinition(
                id = "engineLoad",
                label = "Load",
                unit = MeasurementUnit.PERCENT,
                request = ObdRequest.StandardPid(mode = 1, pid = 0x04),
                parse = { 0.0 },
                pollPriority = PollPriority.SLOW,
            )
    }
}
