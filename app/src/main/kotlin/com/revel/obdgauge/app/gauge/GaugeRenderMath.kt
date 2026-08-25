package com.revel.obdgauge.app.gauge

// OBD-72: the pure value→angle (needle) and value→segment (LED bar-arc) mapping the two new
// render styles share, plus the threshold-band→sweep-span math that paints each style's own
// version of OBD-66's "sacred" green/amber/red vocabulary onto a fixed GaugeScale sweep. Kept
// Canvas/Compose-free so it's unit-testable in isolation — the same split
// GaugeFormatting.kt/UnitConversion.kt/ThresholdEditing.kt already use for their own domains.

/**
 * Sweep geometry shared by [NeedleGauge] and [BarArcGauge] — the same 135°-start/270°-sweep
 * convention [BoostArc] already established for this app's one existing arc gauge, so every fixed-
 * sweep gauge on this dashboard reads the same "empty wedge at the bottom" shape.
 */
const val GAUGE_SWEEP_START_DEG = 135f
const val GAUGE_SWEEP_ANGLE_DEG = 270f

/**
 * Below this smaller-tile-dimension (dp), [NeedleGauge] drops its tick labels and thins its tick
 * set to just the two ends ([GaugeScale.displayTicks]), and [BarArcGauge] renders fewer, chunkier
 * segments ([barArcSegmentCount]) — OBD-72's "legibility at small tile sizes" call. A 1×1 tile has
 * no room for a full numeral ladder around a dial; the dial/arc, its zone coloring, and the big
 * center readout all stay legible regardless.
 */
const val GAUGE_COMPACT_SIZE_DP = 120

/** LED segment count for a normal-sized [BarArcGauge] tile. */
const val BAR_ARC_SEGMENTS_DEFAULT = 20

/** LED segment count for a [GAUGE_COMPACT_SIZE_DP]-and-below tile — fewer, chunkier segments. */
const val BAR_ARC_SEGMENTS_COMPACT = 10

/** Angular gap (degrees) between adjacent LED segments. */
const val BAR_ARC_SEGMENT_GAP_DEG = 3f

/** The segment count [BarArcGauge] should render for a dial of [compact]ness. */
fun barArcSegmentCount(compact: Boolean): Int = if (compact) BAR_ARC_SEGMENTS_COMPACT else BAR_ARC_SEGMENTS_DEFAULT

/**
 * The needle's angle in degrees — [androidx.compose.ui.graphics.drawscope.DrawScope.drawArc]'s
 * convention (clockwise from 3 o'clock) — for [value] on [scale]. Clamped at both ends via
 * [GaugeScale.sweepFraction], so an out-of-range reading pins the needle at the corresponding end
 * of the sweep rather than pointing somewhere off-dial.
 */
fun needleAngleDegrees(
    scale: GaugeScale,
    value: Double,
): Float = GAUGE_SWEEP_START_DEG + GAUGE_SWEEP_ANGLE_DEG * scale.sweepFraction(value)

/** One colored span of a gauge's sweep, as a fraction range of the full `0f..1f` sweep. */
data class ThresholdZoneSpan(
    val startFraction: Float,
    val endFraction: Float,
    val zone: ThresholdZone,
)

/**
 * [thresholds]' green/amber/red boundaries mapped onto [scale]'s `0f..1f` sweep — [NeedleGauge]'s
 * colored zone-arc bands, painted just inside the dial's tick ring the way a real TunerStudio-
 * style gauge paints caution/danger bands onto its face. A NEUTRAL band (both boundaries null —
 * boost, per [ThresholdConfig.seed]) is a single [ThresholdZone.NEUTRAL] span covering the whole
 * sweep (rendered as no band at all — see [NeedleGauge]). Boundaries outside [scale] clamp to the
 * scale's own ends via [GaugeScale.sweepFraction], same discipline as everywhere else in this file.
 */
fun thresholdZoneSpans(
    scale: GaugeScale,
    thresholds: GaugeThresholds,
): List<ThresholdZoneSpan> {
    val greenMax = thresholds.greenMax
    val redMin = thresholds.redMin
    if (greenMax == null && redMin == null) {
        return listOf(ThresholdZoneSpan(0f, 1f, ThresholdZone.NEUTRAL))
    }
    val greenEnd = greenMax?.let(scale::sweepFraction) ?: 0f
    val redStart = redMin?.let(scale::sweepFraction) ?: 1f
    val spans = mutableListOf<ThresholdZoneSpan>()
    if (greenMax != null && greenEnd > 0f) {
        spans.add(ThresholdZoneSpan(0f, greenEnd, ThresholdZone.GREEN))
    }
    if (redStart > greenEnd) {
        spans.add(ThresholdZoneSpan(greenEnd, redStart, ThresholdZone.AMBER))
    }
    if (redMin != null && redStart < 1f) {
        spans.add(ThresholdZoneSpan(redStart, 1f, ThresholdZone.RED))
    }
    return spans
}

/**
 * How many of [totalSegments] LED segments are lit for [value] on [scale] — rounds to the
 * nearest segment, clamped to `[0, totalSegments]`.
 */
fun litSegmentCount(
    scale: GaugeScale,
    value: Double,
    totalSegments: Int,
): Int {
    if (totalSegments <= 0) return 0
    return Math.round(scale.sweepFraction(value) * totalSegments).coerceIn(0, totalSegments)
}

/**
 * Which [ThresholdZone] segment [index] (0-based, of [totalSegments]) should light up as —
 * classifies the WIRE value at that segment's sweep midpoint against [thresholds], so a lit
 * bar-arc reads green near the bottom of the sweep and red near the top exactly where
 * [thresholdZoneSpans] would paint the needle's own zone arc, even though only [litSegmentCount]
 * segments are actually lit at any one reading.
 */
fun segmentZone(
    scale: GaugeScale,
    thresholds: GaugeThresholds,
    index: Int,
    totalSegments: Int,
): ThresholdZone {
    if (totalSegments <= 0) return ThresholdZone.NEUTRAL
    val midFraction = (index + HALF_SEGMENT) / totalSegments
    val midValue = scale.min + (scale.max - scale.min) * midFraction
    return thresholds.classify(midValue)
}

/**
 * Start angle (degrees) of segment [index] of [totalSegments] within the shared
 * [GAUGE_SWEEP_ANGLE_DEG] sweep, leaving [gapDeg] of dead space between adjacent segments.
 */
fun segmentStartAngleDeg(
    index: Int,
    totalSegments: Int,
    gapDeg: Float = BAR_ARC_SEGMENT_GAP_DEG,
): Float {
    if (totalSegments <= 0) return GAUGE_SWEEP_START_DEG
    val span = segmentSweepDeg(totalSegments, gapDeg)
    return GAUGE_SWEEP_START_DEG + index * (span + gapDeg)
}

/** The angular width (degrees) of one of [totalSegments] segments, given [gapDeg] between them. */
fun segmentSweepDeg(
    totalSegments: Int,
    gapDeg: Float = BAR_ARC_SEGMENT_GAP_DEG,
): Float {
    if (totalSegments <= 0) return 0f
    return (GAUGE_SWEEP_ANGLE_DEG - gapDeg * (totalSegments - 1)) / totalSegments
}

private const val HALF_SEGMENT = 0.5f
