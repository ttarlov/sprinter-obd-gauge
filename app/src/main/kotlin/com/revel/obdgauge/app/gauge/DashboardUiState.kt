package com.revel.obdgauge.app.gauge

import androidx.compose.runtime.Immutable
import com.revel.obdgauge.app.settings.UnitPreferences
import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.Reading
import java.time.Instant

/**
 * Compose-facing state for one gauge tile. Formatting only — see [GaugeFormatting].
 *
 * [Immutable]-annotated for the same reason as [DashboardUiState]: explicit, rather than
 * relying on the Compose compiler's stability inference to hold across every field.
 */
@Immutable
data class GaugeTileUiState(
    val id: String,
    val label: String,
    val valueText: String,
    val zone: ThresholdZone,
    val isStale: Boolean,
    val staleText: String?,
    /** Raw value backing [BoostArc]'s sweep position; ignored by non-boost tiles. */
    val rawValue: Double,
    /**
     * OBD-27: mirrors [PidDefinition.verified] — `false` means this PID's request/parse is a
     * hypothesis, unconfirmed against real hardware (today: [PidIds.TRANS_TEMP] — see
     * `DashboardPids.kt`; [PidIds.OIL_TEMP] was the other one until OBD-50 wired it to the
     * live-verified standard PID `015C`). Drives the tile's unverified badge. A
     * property of the *definition*, not the [Reading] — true even before any reading has
     * arrived, which is why [placeholder] takes it too, and why [DashboardUiState.Loading]
     * below sets it explicitly per-id rather than relying on this default for any of them.
     *
     * Defaults `false` (round-1 review, B8 MINOR — reviews/OBD-24-round1.md): mirrors
     * `PidCatalog.isVerified`'s own deliberate "an id this module cannot vouch for is not
     * verified" stance — an id nobody explicitly marked verified must never silently render
     * as trustworthy. The one prior spot that defaulted to `true` (`DashboardScreen.kt`'s
     * `GaugeSlot` placeholder fallback for an id absent from [GAUGE_CATALOG]) is flipped to
     * match.
     */
    val verified: Boolean = false,
) {
    companion object {
        /** Shown before any [Reading] has arrived for [id]. */
        fun placeholder(
            id: String,
            label: String,
            verified: Boolean = false,
        ) = GaugeTileUiState(
            id = id,
            label = label,
            valueText = NO_READING_TEXT,
            zone = ThresholdZone.NEUTRAL,
            isStale = false,
            staleText = null,
            rawValue = 0.0,
            verified = verified,
        )
    }
}

/**
 * Full dashboard render state: the four gauge tiles plus the underlying [LinkState].
 *
 * [Immutable]-annotated: every property is a val of a stable type, but [LinkState] is a
 * sealed interface defined in `:core:model`, so the Compose compiler can't infer its
 * stability across the module boundary without this explicit annotation — without it, every
 * recomposition would treat this class as potentially-unstable input.
 */
@Immutable
data class DashboardUiState(
    val coolant: GaugeTileUiState,
    val transTemp: GaugeTileUiState,
    val oilTemp: GaugeTileUiState,
    val boost: GaugeTileUiState,
    val connection: LinkState,
    /**
     * OBD-42: tile state for every [GAUGE_CATALOG] id that ISN'T one of the four fixed fields
     * above (today, just [PidIds.RPM]) — computed the same way, off the same [Reading]/
     * threshold/unit inputs, so a swap-picker mini-card and a swapped-in dashboard tile show
     * identical value/label/threshold-coloring for a given id. Empty by default so every
     * pre-OBD-42 direct constructor call (previews, hand-built test fixtures) keeps compiling
     * unchanged; see [tileFor].
     */
    val extraTiles: Map<String, GaugeTileUiState> = emptyMap(),
    /**
     * OBD-27: raw-response viewer content per [GAUGE_CATALOG] id, computed alongside
     * [extraTiles] off the exact same [tileState]/[rawFrameState] pass — see [toDashboardUiState].
     * Empty by default so pre-OBD-27 direct constructor calls (previews, hand-built fixtures)
     * keep compiling; a tap on a tile whose id isn't in here simply has nothing to show, which
     * only happens for a [DashboardUiState] no caller populated via [toDashboardUiState].
     */
    val rawFrames: Map<String, RawFrameUiState> = emptyMap(),
) {
    companion object {
        val Loading =
            DashboardUiState(
                coolant = GaugeTileUiState.placeholder(PidIds.COOLANT, "Coolant", verified = true),
                transTemp = GaugeTileUiState.placeholder(PidIds.TRANS_TEMP, "Trans", verified = false),
                // OBD-50: oilTemp is now backed by the standard, live-verified 015C — see
                // DashboardPids.kt.
                oilTemp = GaugeTileUiState.placeholder(PidIds.OIL_TEMP, "Oil", verified = true),
                // OBD-57: boost is the speed-density estimate ("Est."), so it badges unverified.
                boost = GaugeTileUiState.placeholder(PidIds.BOOST, "Boost", verified = false),
                connection = LinkState.Disconnected,
            )
    }
}

/**
 * Pure mapper from [VehicleDataSource][com.revel.obdgauge.model.VehicleDataSource] output to
 * [DashboardUiState]. Shared by [DashboardViewModel] and UI tests so tests exercise the exact
 * same formatting/threshold-classification code path the app renders with — never a
 * hand-duplicated copy of it.
 *
 * @param thresholds threshold table to classify against — [ThresholdConfig.seed] by default;
 *   OBD-21's `DashboardViewModel` passes `AppSettings.effectiveThresholds()` instead so a user
 *   override recolors the dashboard the instant it's saved.
 * @param units display-unit preference (OBD-21); defaults to [UnitPreferences]'s own defaults,
 *   which match what [DASHBOARD_PIDS_BY_ID] declares today — so an unconfigured app (or a test
 *   that doesn't care about units) renders identically to pre-OBD-21 output.
 */
fun toDashboardUiState(
    readings: Map<String, Reading>,
    connection: LinkState,
    now: Instant,
    thresholds: Map<String, GaugeThresholds> = ThresholdConfig.seed,
    units: UnitPreferences = UnitPreferences(),
): DashboardUiState {
    // One tile computed per GAUGE_CATALOG entry (OBD-42's superset of DASHBOARD_PIDS — see
    // GaugeCatalog.kt) rather than four hand-duplicated call sites: this is what guarantees a
    // swap-picker mini-card and the tile it swaps into read value/label/threshold-coloring off
    // the exact same code path, never a hand-copied "candidate" formatter that could drift.
    val tiles = GAUGE_CATALOG.associate { pid -> pid.id to tileState(pid, readings, now, thresholds, units) }
    // Same GAUGE_CATALOG pass, same reason as `tiles` above: a picker candidate's raw-viewer
    // content and a swapped-in tile's must read off the one code path, never a hand-copied one.
    val rawFrames =
        GAUGE_CATALOG.associate { pid ->
            pid.id to rawFrameState(pid, readings[pid.id], tiles.getValue(pid.id).valueText, now)
        }
    return DashboardUiState(
        coolant = tiles.getValue(PidIds.COOLANT),
        transTemp = tiles.getValue(PidIds.TRANS_TEMP),
        oilTemp = tiles.getValue(PidIds.OIL_TEMP),
        boost = tiles.getValue(PidIds.BOOST),
        connection = connection,
        extraTiles = tiles - CORE_TILE_IDS,
        rawFrames = rawFrames,
    )
}

private val CORE_TILE_IDS = setOf(PidIds.COOLANT, PidIds.TRANS_TEMP, PidIds.OIL_TEMP, PidIds.BOOST)

/**
 * Looks up the [GaugeTileUiState] for [id]: one of the four fixed dashboard slots, or (OBD-42)
 * [DashboardUiState.extraTiles] for any other [GAUGE_CATALOG] id — the swap picker's candidate
 * mini-cards and a tile that's just been swapped both resolve through this same lookup, so
 * there's exactly one place that decides "what does gauge id X look like right now."
 */
fun DashboardUiState.tileFor(id: String): GaugeTileUiState? =
    when (id) {
        PidIds.COOLANT -> coolant
        PidIds.TRANS_TEMP -> transTemp
        PidIds.OIL_TEMP -> oilTemp
        PidIds.BOOST -> boost
        else -> extraTiles[id]
    }

private fun tileState(
    pid: PidDefinition,
    readings: Map<String, Reading>,
    now: Instant,
    thresholds: Map<String, GaugeThresholds>,
    units: UnitPreferences,
): GaugeTileUiState {
    val id = pid.id
    val reading = readings[id] ?: return GaugeTileUiState.placeholder(id, pid.label, verified = pid.verified)
    // wireUnit is read from the PidDefinition, never hardcoded — see UnitConversion.kt's KDoc
    // on why this must not assume FAHRENHEIT/PSI once :core:protocol wiring lands (OBD-25).
    val wireUnit = pid.unit
    val displayUnit = units.displayUnitFor(wireUnit)
    val displayValue = UnitConversion.convert(reading.value, wireUnit, displayUnit)
    return GaugeTileUiState(
        id = id,
        label = pid.label,
        valueText = formatGaugeValue(displayValue, displayUnit),
        // Classification stays against the raw wire-unit reading and wire-unit-scaled
        // thresholds (never the display-converted value) — see AppSettings' KDoc on why
        // thresholds are stored in wire units.
        zone = ThresholdConfig.classify(id, reading.value, thresholds),
        isStale = reading.stale,
        staleText = if (reading.stale) formatStaleText(reading, now) else null,
        rawValue = reading.value,
        verified = pid.verified,
    )
}
