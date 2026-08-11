package com.revel.obdgauge.app.gauge

import com.revel.obdgauge.app.settings.GaugeOrderEntry
import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.ObdRequest
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.PollPriority

/**
 * OBD-42's swap-picker candidate catalog: every gauge the long-press carousel can offer,
 * deliberately a **superset** of [DASHBOARD_PIDS] rather than a mutation of it.
 *
 * [DASHBOARD_PIDS] (and therefore [DASHBOARD_PIDS_BY_ID]) stays the "core four, visible by
 * default" list — it drives [com.revel.obdgauge.app.settings.DEFAULT_GAUGE_ORDER] and
 * `SettingsCodec`'s auto-backfill of a *missing* slot. Adding [RPM_PID_DEFINITION] there would
 * make it a fifth always-visible tile on a fresh install, which is not what "swap-in candidate"
 * means. [GAUGE_CATALOG] is the broader list this feature actually needs: every id a tile's
 * picker may legally show, whether or not that id currently occupies a visible slot.
 */
val RPM_PID_DEFINITION: PidDefinition =
    PidDefinition(
        id = PidIds.RPM,
        label = "RPM",
        unit = MeasurementUnit.RPM,
        request = ObdRequest.StandardPid(mode = RPM_STANDARD_MODE, pid = RPM_PID),
        // Placeholder, same as every other DASHBOARD_PIDS entry — FakeVehicleDataSource replays
        // by id, never calls this; the real mode-01 parse lives in :core:protocol.
        parse = { RPM_UNUSED_PARSE_RESULT },
        pollPriority = PollPriority.FAST,
    )

/**
 * All gauges the OBD-42 swap picker may offer: [DASHBOARD_PIDS] plus [RPM_PID_DEFINITION].
 * Neutral-colored automatically — [ThresholdConfig.seed] has no entry for [PidIds.RPM], and
 * [ThresholdConfig.classify] returns [ThresholdZone.NEUTRAL] for any id absent from its
 * threshold table, exactly like [PidIds.BOOST].
 */
val GAUGE_CATALOG: List<PidDefinition> = DASHBOARD_PIDS + RPM_PID_DEFINITION

/** [GAUGE_CATALOG] keyed by [PidDefinition.id]. */
val GAUGE_CATALOG_BY_ID: Map<String, PidDefinition> = GAUGE_CATALOG.associateBy { it.id }

/**
 * Candidate gauges the swap picker offers for the tile currently showing [currentId]:
 * [currentId] itself first (so the carousel's first mini-card is always "what's showing now" —
 * tapping it is one of the picker's dismiss paths, see `DashboardScreen.kt`'s `GaugePickerTile`),
 * then every other [catalog] entry that both passes [isEligible] and isn't already visible on a
 * *different* tile (an id shown twice would be confusing and would collide on `testTag("gauge-
 * <id>")`/sparkline-flow lookups, which are keyed by id, not by tile position).
 *
 * @param isEligible the seam for the "verified gauges only" filtering `issues/OBD-42.md`'s
 *   Contract surface describes: once prod wiring lands, the real registry's equivalent of
 *   [PidDefinition.verified] (or a future `PidCatalog.isVerified` hook, if/when a `PidCatalog`
 *   type exists — no such type exists in this codebase yet) should be passed here as e.g.
 *   `{ pid -> pid.verified }`. The `demo` flavor's default is deliberately permissive — every
 *   fake channel counts as a candidate, matching `issues/OBD-42.md`'s "demo: all fake channels
 *   count" — so this parameter is documented, not implemented, for prod. **Do not wire prod
 *   filtering by changing this default**; pass the predicate explicitly at the prod call site
 *   once it exists.
 */
fun candidateGaugesFor(
    currentId: String,
    gaugeOrder: List<GaugeOrderEntry>,
    catalog: List<PidDefinition> = GAUGE_CATALOG,
    isEligible: (PidDefinition) -> Boolean = { true },
): List<PidDefinition> {
    val visibleElsewhere =
        gaugeOrder.filter { it.visible && it.id != currentId }.mapTo(mutableSetOf()) { it.id }
    val current = catalog.firstOrNull { it.id == currentId }
    val others =
        catalog.filter { pid ->
            pid.id != currentId && pid.id !in visibleElsewhere && isEligible(pid)
        }
    return listOfNotNull(current) + others
}

private const val RPM_UNUSED_PARSE_RESULT = 0.0
private const val RPM_STANDARD_MODE = 1
private const val RPM_PID = 0x0C
