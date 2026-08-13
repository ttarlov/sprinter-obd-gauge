package com.revel.obdgauge.app.gauge

import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.Reading
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

// Pure display formatting for gauge values — no protocol math, no unit conversion (the
// Reading's value already arrives in MeasurementUnit-correct form; see
// com.revel.obdgauge.model.VehicleDataSource). Kept as top-level functions (not ViewModel
// methods) so they're trivially unit-testable without any Android/Robolectric dependency.

/** Symbol appended to a formatted value, e.g. "°F" or " PSI". */
fun unitSuffix(unit: MeasurementUnit): String =
    when (unit) {
        MeasurementUnit.CELSIUS -> "°C"
        MeasurementUnit.FAHRENHEIT -> "°F"
        MeasurementUnit.PSI -> " PSI"
        MeasurementUnit.KPA -> " kPa"
        MeasurementUnit.RPM -> " RPM"
        MeasurementUnit.KMH -> " km/h"
        MeasurementUnit.MPH -> " mph"
        MeasurementUnit.PERCENT -> "%"
    }

/**
 * Formats [value] rounded to the nearest whole number for [unit], with its suffix. Whole
 * numbers read faster at arm's length off a dash mount than decimals do for Fahrenheit, where
 * the underlying data is already whole-degree-scale. [PSI][MeasurementUnit.PSI] keeps one
 * decimal since a half-PSI matters at this gauge's small numeric range;
 * [CELSIUS][MeasurementUnit.CELSIUS] keeps one decimal for the opposite reason — 1 Fahrenheit
 * degree is ~0.56 Celsius, so a Fahrenheit-wire reading (OBD-21's display-unit conversion,
 * `gauge/UnitConversion.kt`) rounded to whole Celsius degrees would lose most of its resolution.
 */
fun formatGaugeValue(
    value: Double,
    unit: MeasurementUnit,
): String {
    val number =
        if (unit == MeasurementUnit.PSI ||
            unit == MeasurementUnit.CELSIUS
        ) {
            formatOneDecimal(value)
        } else {
            value.roundToInt().toString()
        }
    return number + unitSuffix(unit)
}

/**
 * Formats to one decimal place, computing the sign separately from the truncated whole part
 * — for `-1 < value < 0` the whole part is `0`, which can't carry a sign on its own (e.g.
 * boost at -0.3 PSI must render "-0.3", not "0.3").
 */
private fun formatOneDecimal(value: Double): String {
    val tenths = kotlin.math.abs((value * TENTHS_PER_UNIT).roundToInt())
    val whole = tenths / TENTHS_PER_UNIT
    val fraction = tenths % TENTHS_PER_UNIT
    val sign = if (value < 0 && tenths != 0) "-" else ""
    return "$sign$whole.$fraction"
}

private const val TENTHS_PER_UNIT = 10

/** Placeholder shown for a gauge with no reading yet. */
const val NO_READING_TEXT = "—"

/** "last seen Xs ago" for a stale [Reading], measured against [now]. */
fun formatStaleText(
    reading: Reading,
    now: Instant,
): String = "last seen ${elapsedSeconds(reading, now)}s ago"

/**
 * "captured Xs ago" for OBD-27's raw-response viewer — unlike [formatStaleText], shown
 * regardless of [Reading.stale], since "when did this number arrive" matters to that viewer
 * even for a perfectly fresh reading.
 */
fun formatCapturedText(
    reading: Reading,
    now: Instant,
): String = "captured ${elapsedSeconds(reading, now)}s ago"

private fun elapsedSeconds(
    reading: Reading,
    now: Instant,
): Long = Duration.between(reading.timestamp, now).seconds.coerceAtLeast(0)
