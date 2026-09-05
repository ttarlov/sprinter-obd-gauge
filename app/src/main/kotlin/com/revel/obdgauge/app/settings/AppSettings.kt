package com.revel.obdgauge.app.settings

import com.revel.obdgauge.app.gauge.GaugeRenderStyle
import com.revel.obdgauge.app.gauge.GaugeScale
import com.revel.obdgauge.app.gauge.GaugeScaleDefaults
import com.revel.obdgauge.app.gauge.GaugeThresholds
import com.revel.obdgauge.app.gauge.ThresholdConfig
import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.PidIds

/** One row in the configurable gauge order/selection list (OBD-21). */
data class GaugeOrderEntry(
    val id: String,
    val visible: Boolean = true,
)

/**
 * Default order/visibility: every OBD-10 gauge, visible, in the dashboard's original layout
 * order (coolant, trans, oil, then boost — matching `DashboardScreen.kt`'s pre-OBD-21 hardcoded
 * tile list).
 */
val DEFAULT_GAUGE_ORDER: List<GaugeOrderEntry> =
    // Hand-ordered for the classic layout, then future-proofed: any catalog gauge not named
    // here is appended visible, so a new DASHBOARD_PIDS entry reaches FRESH installs too —
    // the codec-side reconciliation only covers persisted settings (round-2 NIT).
    (
        listOf(PidIds.COOLANT, PidIds.TRANS_TEMP, PidIds.OIL_TEMP, PidIds.BOOST) +
            com.revel.obdgauge.app.gauge.DASHBOARD_PIDS
                .map { it.id }
    ).distinct().map { GaugeOrderEntry(it) }

/**
 * Which unit each *kind* of channel displays in. Temperature and pressure are the only two
 * user-toggleable kinds today (coolant/oil/trans are temperatures; boost is pressure) — see
 * `gauge/UnitConversion.kt`'s `UnitKind`. Defaults match `DashboardPids.DASHBOARD_PIDS`' today
 * declared units exactly, so a freshly-installed app (no settings saved yet) renders identically
 * to pre-OBD-21 behavior.
 */
data class UnitPreferences(
    val temperatureUnit: MeasurementUnit = MeasurementUnit.FAHRENHEIT,
    val pressureUnit: MeasurementUnit = MeasurementUnit.PSI,
)

private const val POLL_HZ_4_MS = 250L
private const val POLL_HZ_2_MS = 500L
private const val POLL_HZ_1_MS = 1_000L

/** One poll-rate choice: milliseconds between FAST-priority PID cycles. */
enum class PollRate(
    val intervalMs: Long,
) {
    HZ_4(POLL_HZ_4_MS),
    HZ_2(POLL_HZ_2_MS),
    HZ_1(POLL_HZ_1_MS),
}

/**
 * The full set of OBD-21 user-configurable app settings, persisted via
 * [DataStoreSettingsRepository]. [ThresholdConfig.seed] remains the default table;
 * [thresholdOverrides] layers user edits on top (see [effectiveThresholds]) and is empty until
 * the user actually edits something.
 *
 * Threshold values inside [thresholdOverrides] are stored in each gauge's NATURAL/wire unit —
 * i.e. whatever [com.revel.obdgauge.app.gauge.DashboardPids.DASHBOARD_PIDS] currently declares
 * that gauge's [com.revel.obdgauge.model.PidDefinition.unit] to be (today `FAHRENHEIT`/`PSI` for
 * the `demo` flavor's fake data) — never in [units]' display unit. That is what makes a °F/°C
 * toggle in [units] never rewrite a stored threshold: conversion only happens at the
 * display/edit boundary (`gauge/UnitConversion.kt`). See `app/MODULE.md`'s "Unit conversion"
 * section for the full rationale, including the Phase-4/OBD-25 caveat.
 *
 * [pollRate] is plumbed through for the future real `:core:protocol` poll scheduler (OBD-25) to
 * read; the `demo` flavor's `FakeVehicleDataSource` replays a fixed script and ignores it
 * entirely — see `app/MODULE.md`.
 */
data class AppSettings(
    val gaugeOrder: List<GaugeOrderEntry> = DEFAULT_GAUGE_ORDER,
    val thresholdOverrides: Map<String, GaugeThresholds> = emptyMap(),
    val units: UnitPreferences = UnitPreferences(),
    val keepScreenOn: Boolean = false,
    val pollRate: PollRate = PollRate.HZ_4,
    // OBD-61: the GPS-auto-learned speedometer correction multiplier (true = ecu × factor).
    // 1.0 means "no correction / not yet learned", which is what a fresh install renders — the
    // raw ECU speed. Written silently by SpeedCalibrator as the factor converges; there is no
    // Settings UI for it in v1.
    val speedCorrectionFactor: Double = 1.0,
    /**
     * OBD-68 (was a single [com.revel.obdgauge.app.gauge.grid.GridLayout] under OBD-62/67 — see
     * the round-4 pivot note in `issues/OBD-67.md`): one freeform grid layout PER COLUMN COUNT,
     * keyed by [com.revel.obdgauge.app.gauge.grid.GridLayout.columns] (landscape=4, portrait=2 in
     * this app). A single canonical layout repacked per orientation at render time couldn't hold
     * independent freeform placements for both — landscape and portrait are different canvases,
     * arranged independently, but must always agree on WHICH gauges are present (see
     * `GridLayoutSet`'s KDoc for that sync invariant). Empty until a grid is persisted — the
     * dashboard derives layouts from [gaugeOrder] via `GridMigration.fromGaugeOrder` in that case,
     * so this stays additive and a fresh/old install renders exactly as before.
     */
    val gridLayoutsByColumns: Map<Int, com.revel.obdgauge.app.gauge.grid.GridLayout> = emptyMap(),
    /**
     * OBD-72: per-gauge render style (digital/needle/bar-arc), keyed by gauge id — same shape as
     * [thresholdOverrides], missing means [GaugeRenderStyle.DIGITAL] (see [renderStyleFor]) so a
     * fresh/old install renders exactly as before this feature.
     */
    val renderStyles: Map<String, GaugeRenderStyle> = emptyMap(),
    /**
     * OBD-72: per-gauge full-scale sweep bounds, user overrides layered on top of
     * [GaugeScaleDefaults.seed] (see [effectiveScales]) — needle/bar-arc's "genuinely new" data
     * model. Stored in each gauge's wire unit, exactly like [thresholdOverrides] — see that
     * property's own KDoc for why. Empty until a user edits a scale.
     */
    val scaleOverrides: Map<String, GaugeScale> = emptyMap(),
    /**
     * OBD-79: the manual odometer anchor — "Set odometer" writes this + stamps
     * [lastManualEntryEpochMillis]. `0` on a fresh install means "never set," which
     * [currentOdometerMiles] and the Maintenance list both render as an honest zero rather than a
     * sentinel — the "Set odometer" affordance is always visible as the fix.
     */
    val odometerAnchorMiles: Int = 0,
    /** Wall-clock time [odometerAnchorMiles] was last set, for a future "as of" display. */
    val anchorAtEpochMillis: Long = 0,
    /**
     * OBD-80: PID `0131` ("distance since codes cleared," km) read at the moment
     * [odometerAnchorMiles] was last set — the baseline OBD-80's auto-advance diffs against.
     * Always `0` from this issue (no OBD read happens here); OBD-80 is the only writer.
     */
    val anchorRefDistanceKm: Int = 0,
    /**
     * OBD-80: miles accumulated since the anchor via the `0131` auto-advance, added to
     * [odometerAnchorMiles] by a future revision of [currentOdometerMiles]. Always `0.0` from this
     * issue — see that property's KDoc.
     */
    val accumulatedSinceAnchorMiles: Double = 0.0,
    /**
     * Wall-clock time of the last manual "Set odometer" entry — distinct from
     * [anchorAtEpochMillis] only once OBD-80 can also move the anchor on its own.
     */
    val lastManualEntryEpochMillis: Long = 0,
)

/**
 * OBD-79: the single accessor every screen reads for "what mileage is this van at right now" —
 * today just [odometerAnchorMiles], the manual anchor. OBD-80 lands PID `0131` auto-advance by
 * changing ONLY this function's body to add [accumulatedSinceAnchorMiles] (already plumbed and
 * defaulted to `0.0` above), so no caller of [currentOdometerMiles] needs to change when that
 * lands — see `issues/OBD-79.md`'s "Interaction with OBD-80" section.
 */
val AppSettings.currentOdometerMiles: Int
    get() = odometerAnchorMiles

/** [ThresholdConfig.seed] with [AppSettings.thresholdOverrides] layered on top. */
fun AppSettings.effectiveThresholds(): Map<String, GaugeThresholds> = ThresholdConfig.seed + thresholdOverrides

/** [GaugeScaleDefaults.seed] with [AppSettings.scaleOverrides] layered on top. */
fun AppSettings.effectiveScales(): Map<String, GaugeScale> = GaugeScaleDefaults.seed + scaleOverrides

/** [AppSettings.renderStyles]' entry for [id], or [GaugeRenderStyle.DIGITAL] if unset. */
fun AppSettings.renderStyleFor(id: String): GaugeRenderStyle = renderStyles[id] ?: GaugeRenderStyle.DIGITAL

/**
 * OBD-42: replaces the [gaugeOrder] entry currently named [oldId] with [newId], keeping that
 * entry's [GaugeOrderEntry.visible] and position — "swap" is exactly this, never a remove-then-
 * insert (which would also change position/visibility). A no-op if [oldId] isn't present.
 * Pure/testable in isolation; [com.revel.obdgauge.app.gauge.DashboardViewModel.swapGauge] is the
 * only production caller, round-tripping it through the same [SettingsRepository.update] path
 * every other OBD-21 setting mutator uses (see `SettingsViewModel`) — so a swap persists and
 * live-updates the dashboard exactly like a threshold edit does.
 */
fun AppSettings.withGaugeSwapped(
    oldId: String,
    newId: String,
): AppSettings =
    copy(
        gaugeOrder =
            gaugeOrder.map { entry ->
                if (entry.id == oldId) entry.copy(id = newId) else entry
            },
    )
