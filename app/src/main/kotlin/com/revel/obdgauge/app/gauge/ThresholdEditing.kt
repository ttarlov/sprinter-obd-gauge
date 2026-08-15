// MatchingDeclarationName: this file groups the enum + the free functions that operate on it as one
// cohesive "threshold editing" unit (like `GaugeFormatting.kt`/`UnitConversion.kt` do for their
// domains), rather than splitting a one-constant enum into its own file.
@file:Suppress("MatchingDeclarationName")

package com.revel.obdgauge.app.gauge

import com.revel.obdgauge.model.MeasurementUnit

// OBD-66: the pure logic behind the per-gauge gear/flip threshold editor — kept as plain functions
// (no Compose, no Android) so the value-stepping/clamping, the color→field mapping, and the pre-fill
// default are unit-testable in isolation, exactly the split `GaugeFormatting`/`UnitConversion`
// already use. The Compose editor (`GaugePicker.kt`'s `ThresholdEditorFace`) is a thin shell over
// these.

/** Which of a gauge's two threshold boundaries the editor's squares select. */
enum class ThresholdColor {
    /** The amber (caution) boundary — [GaugeThresholds.greenMax]. */
    YELLOW,

    /** The red (danger) boundary — [GaugeThresholds.redMin]. */
    RED,
}

/** Default step (°F) for the +/- stepper — whole-degree resolution reads fine at arm's length. */
const val THRESHOLD_STEP_DEFAULT: Double = 5.0

/**
 * Pre-fill fallback (display unit) when a gauge has no threshold set for the selected color at all
 * — Taras's spec: "starting at 200". In practice every temperature gauge the editor is offered on
 * has a seed value, so this is the belt-and-braces floor, not the common path.
 */
const val THRESHOLD_FALLBACK_DEFAULT: Double = 200.0

/** Clamp range (display unit) for an edited threshold — a sane envelope for a temperature gauge. */
const val THRESHOLD_MIN: Double = 0.0
const val THRESHOLD_MAX: Double = 500.0

/** The boundary value this band holds for [color] (wire unit), or null if that boundary is unset. */
fun GaugeThresholds.valueFor(color: ThresholdColor): Double? =
    when (color) {
        ThresholdColor.YELLOW -> greenMax
        ThresholdColor.RED -> redMin
    }

/**
 * This band with [color]'s boundary set to [wireValue] (wire unit), leaving the other boundary
 * untouched. Setting the RED boundary also pins [GaugeThresholds.redInclusive] true so the danger
 * zone (and its pulse) fires at/above the value the user just chose — the seed's own convention.
 */
fun GaugeThresholds.withValueFor(
    color: ThresholdColor,
    wireValue: Double,
): GaugeThresholds =
    when (color) {
        ThresholdColor.YELLOW -> copy(greenMax = wireValue)
        ThresholdColor.RED -> copy(redMin = wireValue, redInclusive = true)
    }

/** [current] moved by [steps] increments of [step], clamped to [[min], [max]] (all display unit). */
fun stepThreshold(
    current: Double,
    steps: Int,
    step: Double = THRESHOLD_STEP_DEFAULT,
    min: Double = THRESHOLD_MIN,
    max: Double = THRESHOLD_MAX,
): Double = (current + steps * step).coerceIn(min, max)

/**
 * The value (in [displayUnit]) the editor should pre-fill for [color]: the gauge's current
 * effective boundary converted from [wireUnit], or [THRESHOLD_FALLBACK_DEFAULT] when that boundary
 * is unset.
 */
fun initialDisplayThreshold(
    thresholds: GaugeThresholds,
    color: ThresholdColor,
    wireUnit: MeasurementUnit,
    displayUnit: MeasurementUnit,
): Double {
    val wire = thresholds.valueFor(color) ?: return THRESHOLD_FALLBACK_DEFAULT
    return UnitConversion.convert(wire, wireUnit, displayUnit)
}

/**
 * The [GaugeThresholds] to persist after the user sets [color] to [displayValue] (in [displayUnit])
 * — converts back to [wireUnit] and folds it into [thresholds] via [withValueFor], so the other
 * boundary and its inclusivity survive the edit (the same store-in-wire-unit discipline the
 * settings screen follows — see `AppSettings`' KDoc).
 */
fun thresholdsWithDisplayValue(
    thresholds: GaugeThresholds,
    color: ThresholdColor,
    displayValue: Double,
    wireUnit: MeasurementUnit,
    displayUnit: MeasurementUnit,
): GaugeThresholds = thresholds.withValueFor(color, UnitConversion.convert(displayValue, displayUnit, wireUnit))
