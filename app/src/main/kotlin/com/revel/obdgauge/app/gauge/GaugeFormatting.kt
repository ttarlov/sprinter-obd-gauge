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
 * numbers read faster at arm's length off a dash mount than decimals do; boost keeps one
 * decimal since a half-PSI matters at this gauge's small numeric range.
 */
fun formatGaugeValue(
    value: Double,
    unit: MeasurementUnit,
): String {
    val number = if (unit == MeasurementUnit.PSI) formatOneDecimal(value) else value.roundToInt().toString()
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
): String {
    val elapsedSeconds = Duration.between(reading.timestamp, now).seconds.coerceAtLeast(0)
    return "last seen ${elapsedSeconds}s ago"
}
