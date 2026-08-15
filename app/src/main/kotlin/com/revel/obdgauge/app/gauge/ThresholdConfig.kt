package com.revel.obdgauge.app.gauge

import com.revel.obdgauge.model.PidIds

/**
 * Which color band a gauge value falls into. [NEUTRAL] means the gauge isn't threshold-coded
 * at all (boost) rather than "currently in a safe band" — see [ThresholdConfig.seed].
 */
enum class ThresholdZone {
    GREEN,
    AMBER,
    RED,
    NEUTRAL,
}

/**
 * One gauge's threshold band, expressed as two optional boundaries rather than per-gauge
 * `if`/`when` branches, so [ThresholdConfig] stays a data table (OBD-10 AC: "driven by a
 * config table, not hardcoded per gauge") that OBD-21 can later make user-editable.
 *
 * A gauge with both boundaries `null` is [ThresholdZone.NEUTRAL] (boost: no color coding,
 * only the arc sweep). Otherwise: below [greenMax] (or at-or-below, if [greenInclusive]) is
 * [ThresholdZone.GREEN]; above [redMin] (or at-or-above, if [redInclusive]) is
 * [ThresholdZone.RED]; everything in between is [ThresholdZone.AMBER].
 */
data class GaugeThresholds(
    val greenMax: Double? = null,
    val greenInclusive: Boolean = false,
    val redMin: Double? = null,
    val redInclusive: Boolean = false,
) {
    /** Classifies [value] into a [ThresholdZone] per this band's boundaries. */
    fun classify(value: Double): ThresholdZone {
        val isGreen = greenMax != null && (if (greenInclusive) value <= greenMax else value < greenMax)
        val isRed = redMin != null && (if (redInclusive) value >= redMin else value > redMin)
        return when {
            greenMax == null && redMin == null -> ThresholdZone.NEUTRAL
            isGreen -> ThresholdZone.GREEN
            isRed -> ThresholdZone.RED
            else -> ThresholdZone.AMBER
        }
    }
}

/**
 * Seed threshold table for the four OBD-10 gauges, keyed by [PidIds] id (matches
 * [com.revel.obdgauge.model.PidDefinition.id] shape).
 *
 * OBD-66 re-baselined these to the researched caution/danger temperatures for a loaded
 * Sprinter/Revel working grades (all values in each gauge's declared wire unit — FAHRENHEIT for
 * coolant/trans/oil, see `DashboardPids.DASHBOARD_PIDS`). The YELLOW (amber) threshold is
 * [GaugeThresholds.greenMax] — the value AT/ABOVE which the tile leaves green for amber (the
 * green band stays exclusive, so a reading sitting exactly on it reads amber). The RED (danger)
 * threshold is [GaugeThresholds.redMin], made **inclusive** ([GaugeThresholds.redInclusive]) so a
 * reading sitting exactly on the danger line already reads — and pulses — RED, matching OBD-66's
 * "pulse when the value is at/above the danger threshold" contract.
 * - coolant: green <215 / amber 215–225 / red ≥225
 * - trans:   green <215 / amber 215–240 / red ≥240
 * - oil:     green <245 / amber 245–260 / red ≥260
 * - boost:   neutral, no color coding
 *
 * These are the DEFAULTS; OBD-21's settings screen and OBD-66's per-gauge gear editor both layer
 * user overrides on top (see `AppSettings.effectiveThresholds`).
 */
object ThresholdConfig {
    private const val COOLANT_GREEN_MAX = 215.0
    private const val COOLANT_RED_MIN = 225.0
    private const val TRANS_GREEN_MAX = 215.0
    private const val TRANS_RED_MIN = 240.0
    private const val OIL_GREEN_MAX = 245.0
    private const val OIL_RED_MIN = 260.0

    val seed: Map<String, GaugeThresholds> =
        mapOf(
            PidIds.COOLANT to
                GaugeThresholds(greenMax = COOLANT_GREEN_MAX, redMin = COOLANT_RED_MIN, redInclusive = true),
            PidIds.TRANS_TEMP to
                GaugeThresholds(greenMax = TRANS_GREEN_MAX, redMin = TRANS_RED_MIN, redInclusive = true),
            PidIds.OIL_TEMP to
                GaugeThresholds(greenMax = OIL_GREEN_MAX, redMin = OIL_RED_MIN, redInclusive = true),
            PidIds.BOOST to GaugeThresholds(),
        )

    /**
     * Classifies [value] for gauge [id] against [thresholds] (defaulting to [seed]), returning
     * [ThresholdZone.NEUTRAL] for an id absent from [thresholds]. [thresholds] lets OBD-21's
     * user overrides (layered on top of [seed] — see `AppSettings.effectiveThresholds`) drive
     * classification without this function needing to know anything about settings/DataStore.
     */
    fun classify(
        id: String,
        value: Double,
        thresholds: Map<String, GaugeThresholds> = seed,
    ): ThresholdZone = thresholds[id]?.classify(value) ?: ThresholdZone.NEUTRAL
}
