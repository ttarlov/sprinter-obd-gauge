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
 * The channel id for vehicle speed, mirrored from `:core:protocol`'s `ProtocolPidIds.SPEED`
 * ("speed"). Defined locally because `:core:protocol` is a `prodImplementation`-only dependency
 * (see `app/build.gradle.kts`) and this file is flavor-common `src/main/` — the app-model
 * `PidIds` does not name speed. A `testProd` parity assertion pins this equal to
 * `ProtocolPidIds.SPEED` so the two can never silently drift.
 */
const val SPEED_PID_ID: String = "speed"

private const val SPEED_STANDARD_MODE = 1
private const val SPEED_PID = 0x0D
private const val SPEED_UNUSED_PARSE_RESULT = 0.0

/**
 * OBD-61's speed gauge: standard PID `010D`, declared in [MeasurementUnit.MPH] so the existing
 * [DisplayUnitDataSource][com.revel.obdgauge.app.datasource.DisplayUnitDataSource] seam
 * auto-converts the protocol layer's km/h into mph (the same way it turns coolant °C into °F) —
 * no per-gauge conversion code. Declared exactly like [RPM_PID_DEFINITION]: a `verified` standard
 * mode-01 PID with a placeholder parse (the real decode lives in `:core:protocol`).
 *
 * Swap-only, like rpm: added to [GAUGE_CATALOG] but deliberately NOT to [DASHBOARD_PIDS], so it
 * is offered by the long-press picker without becoming a fifth always-visible tile on a fresh
 * install. Because it *is* in [GAUGE_CATALOG], `DashboardViewModel.start(GAUGE_CATALOG)` polls it
 * even while it occupies no visible slot — which is what lets OBD-61's GPS calibrator observe the
 * raw ECU speed continuously.
 */
val SPEED_PID_DEFINITION: PidDefinition =
    PidDefinition(
        id = SPEED_PID_ID,
        label = "Speed",
        unit = MeasurementUnit.MPH,
        request = ObdRequest.StandardPid(mode = SPEED_STANDARD_MODE, pid = SPEED_PID),
        parse = { SPEED_UNUSED_PARSE_RESULT },
        pollPriority = PollPriority.FAST,
        verified = true,
    )

/**
 * All gauges the OBD-42 swap picker may offer: [DASHBOARD_PIDS] plus [RPM_PID_DEFINITION] and
 * [SPEED_PID_DEFINITION] (OBD-61). Neutral-colored automatically — [ThresholdConfig.seed] has no
 * entry for [PidIds.RPM] or speed, and [ThresholdConfig.classify] returns [ThresholdZone.NEUTRAL]
 * for any id absent from its threshold table, exactly like [PidIds.BOOST].
 */
val GAUGE_CATALOG: List<PidDefinition> = DASHBOARD_PIDS + RPM_PID_DEFINITION + SPEED_PID_DEFINITION

/** [GAUGE_CATALOG] keyed by [PidDefinition.id]. */
val GAUGE_CATALOG_BY_ID: Map<String, PidDefinition> = GAUGE_CATALOG.associateBy { it.id }

/**
 * Candidate gauges the swap picker offers for the tile currently showing [currentId]:
 * [currentId] itself first (so the carousel's first mini-card is always "what's showing now" —
 * tapping it is one of the picker's dismiss paths, see `DashboardScreen.kt`'s `GaugePickerTile`),
 * then every other [catalog] entry that both passes [isEligible] and isn't already visible on a
 * *different* tile (an id shown twice would be confusing and would collide on `testTag("gauge-
 * <id>")` lookups, which are keyed by id, not by tile position).
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

/**
 * OBD-64/OBD-66: the grid-native swap-candidate list — the same membership as the [gaugeOrder]-keyed
 * [candidateGaugesFor] above ("already shown elsewhere" decided by [placedIds], the ids the persisted
 * `GridLayout` places), but returned in the **stable [catalog] ribbon order** rather than
 * current-first. This is the overload the in-tile pager uses: [currentId] itself plus every [catalog]
 * entry that passes [isEligible] and isn't placed on some *other* tile, each kept at its natural
 * [catalog] index. [currentId] is always included even though it is itself in [placedIds].
 *
 * OBD-66 made the order stable (a left↔right ribbon) so gauges keep consistent spatial positions
 * across opens/swaps: a candidate earlier in [catalog] than [currentId] is a LEFT swipe away, a later
 * one a RIGHT swipe. The pager opens centered on [currentId] at its ribbon slot (see `SwapPager`'s
 * `initialPage`) — so the current gauge is no longer necessarily the first page.
 */
fun candidateGaugesFor(
    currentId: String,
    placedIds: Set<String>,
    catalog: List<PidDefinition> = GAUGE_CATALOG,
    isEligible: (PidDefinition) -> Boolean = { true },
): List<PidDefinition> {
    val placedElsewhere = placedIds - currentId
    return catalog.filter { pid ->
        pid.id == currentId || (pid.id !in placedElsewhere && isEligible(pid))
    }
}

/**
 * OBD-64: the gauges the "+" add-cell/palette may offer — every [catalog] entry not already on the
 * grid ([placedIds]) that passes [isEligible]. Empty exactly when everything the catalog knows is
 * already placed, which is when the "+" cell hides.
 */
fun addableGaugesFor(
    placedIds: Set<String>,
    catalog: List<PidDefinition> = GAUGE_CATALOG,
    isEligible: (PidDefinition) -> Boolean = { true },
): List<PidDefinition> = catalog.filter { it.id !in placedIds && isEligible(it) }

private const val RPM_UNUSED_PARSE_RESULT = 0.0
private const val RPM_STANDARD_MODE = 1
private const val RPM_PID = 0x0C
