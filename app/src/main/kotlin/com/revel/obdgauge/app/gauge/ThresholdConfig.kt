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
 * [com.revel.obdgauge.model.PidDefinition.id] shape). Values per `docs/01-build-plan.md`
 * §2A and `issues/OBD-10.md`:
 * - coolant: green <220 / amber 220-230 / red >230
 * - trans: green <200 / amber 200-250 / red >250 (the build plan's "amber 200-240 / red
 *   >250" leaves 240-250 unspecified; folded into amber here rather than left undefined)
 * - oil: normal (green) <=235 / amber >235, no red band
 * - boost: neutral, no color coding
 *
 * User-editable overrides land in OBD-21; this object is the seed/default table.
 */
object ThresholdConfig {
    private const val COOLANT_GREEN_MAX = 220.0
    private const val COOLANT_RED_MIN = 230.0
    private const val TRANS_GREEN_MAX = 200.0
    private const val TRANS_RED_MIN = 250.0
    private const val OIL_GREEN_MAX = 235.0

    val seed: Map<String, GaugeThresholds> =
        mapOf(
            PidIds.COOLANT to GaugeThresholds(greenMax = COOLANT_GREEN_MAX, redMin = COOLANT_RED_MIN),
            PidIds.TRANS_TEMP to GaugeThresholds(greenMax = TRANS_GREEN_MAX, redMin = TRANS_RED_MIN),
            PidIds.OIL_TEMP to GaugeThresholds(greenMax = OIL_GREEN_MAX, greenInclusive = true),
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
