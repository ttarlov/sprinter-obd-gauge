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
 * The channel id for calculated engine load, mirrored from `:core:protocol`'s
 * `ProtocolPidIds.ENGINE_LOAD` ("engineLoad"). Defined locally for the same reason as
 * [SPEED_PID_ID]: `:core:protocol` is a `prodImplementation`-only dependency, so flavor-common
 * `src/main/` cannot import `ProtocolPidIds` directly. A `testProd` parity assertion pins this
 * equal to `ProtocolPidIds.ENGINE_LOAD` so the two can never silently drift.
 */
const val ENGINE_LOAD_PID_ID: String = "engineLoad"

private const val ENGINE_LOAD_STANDARD_MODE = 1
private const val ENGINE_LOAD_PID = 0x04
private const val ENGINE_LOAD_UNUSED_PARSE_RESULT = 0.0

/**
 * OBD-86's engine load gauge: standard PID `0104`, `A × 100 / 255` %, already decoded and
 * verified at the protocol layer (`PidRegistry.engineLoad`, ~56 % observed on this van). Declared
 * in [MeasurementUnit.PERCENT] — a unitless percentage, so
 * [DisplayUnitDataSource][com.revel.obdgauge.app.datasource.DisplayUnitDataSource] passes it
 * through untouched, the same as every other channel with no display-unit mismatch to resolve.
 *
 * Swap-only, exactly like [RPM_PID_DEFINITION] and [SPEED_PID_DEFINITION]: added to
 * [GAUGE_CATALOG] but deliberately NOT to [DASHBOARD_PIDS], so it is offered by the swap
 * carousel and the "+" add-palette without becoming a fifth always-visible tile on a fresh
 * install. No seed threshold entry ([ThresholdConfig.seed]) — a plain neutral readout, like
 * boost and speed, not a green/amber/red band.
 */
val ENGINE_LOAD_PID_DEFINITION: PidDefinition =
    PidDefinition(
        id = ENGINE_LOAD_PID_ID,
        label = "Load",
        unit = MeasurementUnit.PERCENT,
        request = ObdRequest.StandardPid(mode = ENGINE_LOAD_STANDARD_MODE, pid = ENGINE_LOAD_PID),
        parse = { ENGINE_LOAD_UNUSED_PARSE_RESULT },
        pollPriority = PollPriority.FAST,
        verified = true,
    )

/**
 * The channel id for engine fuel rate, mirrored from `:core:protocol`'s
 * `ProtocolPidIds.FUEL_RATE` ("fuelRate"). Defined locally for the same reason as
 * [SPEED_PID_ID]/[ENGINE_LOAD_PID_ID]: `:core:protocol` is a `prodImplementation`-only
 * dependency, so flavor-common `src/main/` cannot import `ProtocolPidIds` directly. A
 * `testProd` parity assertion pins this equal to `ProtocolPidIds.FUEL_RATE` so the two can never
 * silently drift.
 */
const val FUEL_RATE_PID_ID: String = "fuelRate"

private const val FUEL_RATE_STANDARD_MODE = 1
private const val FUEL_RATE_PID = 0x5E
private const val FUEL_RATE_UNUSED_PARSE_RESULT = 0.0

/**
 * OBD-87's fuel-rate gauge: standard PID `015E`, `(256A+B)/20` L/h, already decoded and verified
 * at the protocol layer (`PidRegistry.fuelRate`, live since OBD-58) but never before requested by
 * `:app` — [GAUGE_CATALOG] is what `ActivePollSet.activePids()` polls, and fuel rate was absent
 * from it, so the channel never went on the wire. Added here **swap-only**, exactly like
 * [RPM_PID_DEFINITION]/[SPEED_PID_DEFINITION]/[ENGINE_LOAD_PID_DEFINITION], purely so this
 * definition's presence in [GAUGE_CATALOG] puts fuel rate on the wire every session — the
 * `InstantMpgDataSource` decorator (`app/src/prod/.../datasource/`) is what actually consumes it,
 * but a gauge tile of its own is a legitimate bonus (raw L/h, mirrors `moduleVoltage`'s "live
 * channel, not built for a tile yet until now" story).
 *
 * Declared in [MeasurementUnit.LITERS_PER_HOUR] — the same unit `:core:protocol` parses to, so
 * [DisplayUnitDataSource][com.revel.obdgauge.app.datasource.DisplayUnitDataSource] passes it
 * through untouched, like `moduleVoltage`/`maf` would if they ever got tiles.
 */
val FUEL_RATE_PID_DEFINITION: PidDefinition =
    PidDefinition(
        id = FUEL_RATE_PID_ID,
        label = "Fuel Rate",
        unit = MeasurementUnit.LITERS_PER_HOUR,
        request = ObdRequest.StandardPid(mode = FUEL_RATE_STANDARD_MODE, pid = FUEL_RATE_PID),
        parse = { FUEL_RATE_UNUSED_PARSE_RESULT },
        pollPriority = PollPriority.SLOW,
        verified = true,
    )

/**
 * The channel id for the OBD-87 instant-MPG computed gauge — a purely local id, not mirrored from
 * `:core:protocol` (there is no PID for it; it is computed at the `prod` DI seam's outermost
 * decorator, `InstantMpgDataSource`, from GPS-corrected speed and fuel rate — see that class's
 * KDoc). Named like [PidIds.BOOST]: a computed channel's id lives whichever layer computes it,
 * and here that is `:app`, not `:core:protocol`.
 */
const val INSTANT_MPG_PID_ID: String = "instantMpg"

private const val INSTANT_MPG_UNUSED_PARSE_RESULT = 0.0

/**
 * OBD-87's instant fuel-economy gauge: `corrected_speed_mph / (fuelRate_Lph / 3.785411784)`,
 * lightly smoothed (~2-3 s rolling average) by `InstantMpgDataSource`. Like [PidIds.BOOST], this
 * is a **computed estimate** (`verified = false`) with a placeholder [request]/[parse] that the
 * app layer never calls — the real computation lives in `InstantMpgDataSource`, which injects the
 * finished [com.revel.obdgauge.model.Reading] directly into the readings map under
 * [INSTANT_MPG_PID_ID] rather than requesting anything over the wire for this id.
 *
 * Swap-only, neutral (no [ThresholdConfig.seed] entry — higher-is-better banding is a trivial
 * follow-up, not this issue's scope). [GaugeScaleDefaults] does seed a ~0-40 mpg sweep for the
 * needle/bar-arc styles.
 */
val INSTANT_MPG_PID_DEFINITION: PidDefinition =
    PidDefinition(
        id = INSTANT_MPG_PID_ID,
        label = "MPG",
        unit = MeasurementUnit.MILES_PER_GALLON,
        request = ObdRequest.StandardPid(mode = FUEL_RATE_STANDARD_MODE, pid = FUEL_RATE_PID),
        parse = { INSTANT_MPG_UNUSED_PARSE_RESULT },
        pollPriority = PollPriority.SLOW,
        verified = false,
    )

/**
 * All gauges the OBD-42 swap picker may offer: [DASHBOARD_PIDS] plus [RPM_PID_DEFINITION],
 * [SPEED_PID_DEFINITION] (OBD-61), [ENGINE_LOAD_PID_DEFINITION] (OBD-86), and, since OBD-87,
 * [FUEL_RATE_PID_DEFINITION] and [INSTANT_MPG_PID_DEFINITION]. Neutral-colored automatically —
 * [ThresholdConfig.seed] has no entry for any of these five swap-only ids, and
 * [ThresholdConfig.classify] returns [ThresholdZone.NEUTRAL] for any id absent from its
 * threshold table, exactly like [PidIds.BOOST].
 */
val GAUGE_CATALOG: List<PidDefinition> =
    DASHBOARD_PIDS + RPM_PID_DEFINITION + SPEED_PID_DEFINITION + ENGINE_LOAD_PID_DEFINITION +
        FUEL_RATE_PID_DEFINITION + INSTANT_MPG_PID_DEFINITION

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
