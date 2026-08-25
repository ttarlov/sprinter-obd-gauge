package com.revel.obdgauge.app.gauge

import com.revel.obdgauge.model.PidIds

/**
 * OBD-72: a gauge's full-scale sweep bounds — [min]/[max] (the sweep's two ends) plus [tick]
 * (spacing between labeled tick marks). Needed only by the fixed-sweep render styles
 * ([GaugeRenderStyle.NEEDLE]/[GaugeRenderStyle.BAR_ARC]) — [GaugeRenderStyle.DIGITAL] needs no
 * scale at all, which is exactly the gap `issues/OBD-72.md` calls out as "the one genuinely new
 * piece."
 *
 * Stored in the gauge's declared WIRE unit, exactly like [GaugeThresholds] (see `AppSettings`'
 * KDoc on why thresholds are stored in wire units) — never the display unit — so a °F/°C toggle
 * never rewrites a persisted scale override. [min] should be strictly less than [max]; a
 * corrupted persisted override that violates this can't crash a render — see [sweepFraction] and
 * [tickValues], both of which defend against it directly.
 */
data class GaugeScale(
    val min: Double,
    val max: Double,
    val tick: Double,
)

/**
 * OBD-72's researched seed scales, taken directly from `research/gauge-scale-ranges.md`'s master
 * table (display-unit column — which for this app's channels today already equals
 * `DASHBOARD_PIDS`'/[GAUGE_CATALOG]'s declared wire unit: FAHRENHEIT for the three temperatures,
 * PSI for boost, RPM/MPH for the other two). Only the six [GAUGE_CATALOG] channels this app
 * actually renders a gauge for are seeded — the research doc's other 14 rows are protocol
 * channels with no dashboard tile yet (see its own "Channel inventory" note).
 *
 * The RPM ceiling is that research's own flagged "least-confident number" — no OM642 factory
 * rev-limiter is publicly documented; override from a real tach reading if one is ever captured.
 * Boost's 0–25 psi sweep is sized around sourced turbo hardware limits, not a claim the speed-
 * density *estimate* itself will track that range accurately (the "Est." badge should carry onto
 * the needle/bar-arc styles the same way it does onto the digital tile today).
 */
object GaugeScaleDefaults {
    val seed: Map<String, GaugeScale> =
        mapOf(
            PidIds.COOLANT to GaugeScale(min = COOLANT_MIN, max = COOLANT_MAX, tick = COOLANT_TICK),
            PidIds.TRANS_TEMP to GaugeScale(min = TRANS_MIN, max = TRANS_MAX, tick = TRANS_TICK),
            PidIds.OIL_TEMP to GaugeScale(min = OIL_MIN, max = OIL_MAX, tick = OIL_TICK),
            PidIds.BOOST to GaugeScale(min = BOOST_MIN, max = BOOST_MAX, tick = BOOST_TICK),
            PidIds.RPM to GaugeScale(min = RPM_MIN, max = RPM_MAX, tick = RPM_TICK),
            SPEED_PID_ID to GaugeScale(min = SPEED_MIN, max = SPEED_MAX, tick = SPEED_TICK),
        )

    private val FALLBACK = GaugeScale(min = FALLBACK_MIN, max = FALLBACK_MAX, tick = FALLBACK_TICK)

    /** [seed]'s scale for [id], or a generic 0–100 fallback for a catalog id [seed] doesn't cover. */
    fun forId(id: String): GaugeScale = seed[id] ?: FALLBACK

    private const val COOLANT_MIN = 40.0
    private const val COOLANT_MAX = 260.0
    private const val COOLANT_TICK = 20.0
    private const val TRANS_MIN = 100.0
    private const val TRANS_MAX = 280.0
    private const val TRANS_TICK = 20.0
    private const val OIL_MIN = 100.0
    private const val OIL_MAX = 300.0
    private const val OIL_TICK = 25.0
    private const val BOOST_MIN = 0.0
    private const val BOOST_MAX = 25.0
    private const val BOOST_TICK = 5.0
    private const val RPM_MIN = 0.0
    private const val RPM_MAX = 5_000.0
    private const val RPM_TICK = 500.0
    private const val SPEED_MIN = 0.0
    private const val SPEED_MAX = 100.0
    private const val SPEED_TICK = 20.0
    private const val FALLBACK_MIN = 0.0
    private const val FALLBACK_MAX = 100.0
    private const val FALLBACK_TICK = 10.0
}

/**
 * Fraction (`0f..1f`) of this scale's sweep that [value] represents, clamping out-of-range values
 * to the nearest end — the shared value→position math [needleAngleDegrees], [litSegmentCount],
 * and [thresholdZoneSpans] all build on (`GaugeRenderMath.kt`). A degenerate scale ([max] <=
 * [min], which no seed or UI path produces but a hand-edited persisted override could) returns
 * `0f` rather than dividing by zero or going negative.
 */
fun GaugeScale.sweepFraction(value: Double): Float {
    if (max <= min) return 0f
    return ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f)
}

/**
 * Tick values from [GaugeScale.min] to [GaugeScale.max] stepped by [GaugeScale.tick], inclusive of
 * both ends (the last step is truncated to land exactly on [GaugeScale.max] rather than
 * overshooting it). A degenerate scale ([GaugeScale.max] <= [GaugeScale.min]) or a non-positive
 * [GaugeScale.tick] returns just the two end values instead of looping forever.
 */
fun GaugeScale.tickValues(): List<Double> {
    if (max <= min || tick <= 0.0) return listOf(min, max)
    val values = mutableListOf<Double>()
    var v = min
    while (v < max) {
        values.add(v)
        v += tick
    }
    values.add(max)
    return values
}

/**
 * [tickValues], thinned to just the two ends when [compact] — a 1×1 tile has no room for a full
 * tick ladder (OBD-72's "legibility at small tile sizes" call — see [GAUGE_COMPACT_SIZE_DP]).
 */
fun GaugeScale.displayTicks(compact: Boolean): List<Double> = if (compact) listOf(min, max) else tickValues()
