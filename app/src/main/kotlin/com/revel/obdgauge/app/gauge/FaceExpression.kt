package com.revel.obdgauge.app.gauge

// OBD-73 "dumb mode": pure value→mood mapping, kept Compose-free (like GaugeRenderMath.kt) so it
// unit-tests as plain JUnit. FaceGauge.kt does the drawing off this.

/** A temp gauge's mood in "dumb mode", coarsest-to-hottest. */
enum class FaceExpression {
    HAPPY,
    CONCERNED,
    SAD,
    CRYING,
}

/**
 * Maps [value] to a [FaceExpression] from the gauge's own [thresholds]:
 * - RED zone (≥ danger) → [FaceExpression.CRYING]
 * - AMBER zone (≥ caution, < danger) → [FaceExpression.SAD]
 * - GREEN but within a caution-band's width **below** the yellow line → [FaceExpression.CONCERNED]
 * - GREEN, comfortably below → [FaceExpression.HAPPY]
 *
 * The "concerned" approach band mirrors the amber band's own width (`redMin − greenMax`), so a
 * gauge with tight caution→danger spacing frets sooner and a loosely-spaced one frets later — no
 * magic constant, and it scales with whatever the user sets the thresholds to. A gauge with no
 * thresholds ([ThresholdZone.NEUTRAL], e.g. boost) is always HAPPY, though FACE isn't offered
 * there anyway (the style picker gates it on having thresholds).
 */
fun faceExpression(
    thresholds: GaugeThresholds,
    value: Double,
): FaceExpression =
    when (thresholds.classify(value)) {
        ThresholdZone.RED -> FaceExpression.CRYING
        ThresholdZone.AMBER -> FaceExpression.SAD
        ThresholdZone.GREEN -> {
            val greenMax = thresholds.greenMax
            val redMin = thresholds.redMin
            val concernBand = if (greenMax != null && redMin != null) (redMin - greenMax).coerceAtLeast(0.0) else 0.0
            if (greenMax != null && value >= greenMax - concernBand) FaceExpression.CONCERNED else FaceExpression.HAPPY
        }
        ThresholdZone.NEUTRAL -> FaceExpression.HAPPY
    }

/**
 * OBD-73 (Taras's friend's idea): a continuous "heat" `0f..1f` for a temp face — `0` at the top of
 * the happy band (where "concerned" begins), ramping to `1` at the danger line and beyond. Drives
 * the face's flush (yellow → hot red) and how much it sweats, INDEPENDENTLY of the discrete
 * expression, so the face reddens smoothly while the mouth/brows snap at the threshold boundaries.
 * A gauge with no thresholds returns `0` (never reddens — e.g. boost's excitement face stays yellow).
 */
fun faceHeat(
    thresholds: GaugeThresholds,
    value: Double,
): Float {
    val greenMax = thresholds.greenMax
    val redMin = thresholds.redMin
    if (greenMax == null || redMin == null) return 0f
    val concernBand = (redMin - greenMax).coerceAtLeast(0.0)
    val start = greenMax - concernBand
    val span = redMin - start
    return if (span <= 0.0) {
        if (value >= redMin) 1f else 0f
    } else {
        ((value - start) / span).coerceIn(0.0, 1.0).toFloat()
    }
}
