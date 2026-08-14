package com.revel.obdgauge.app.gauge

import com.revel.obdgauge.app.settings.UnitPreferences
import com.revel.obdgauge.model.MeasurementUnit

/**
 * Which family of units a [MeasurementUnit] belongs to, for generic (not per-gauge-hardcoded)
 * display conversion — see `UnitConversion` and `app/MODULE.md`'s "Unit conversion" section.
 */
enum class UnitKind { TEMPERATURE, PRESSURE, SPEED, OTHER }

fun MeasurementUnit.kind(): UnitKind =
    when (this) {
        MeasurementUnit.CELSIUS, MeasurementUnit.FAHRENHEIT -> UnitKind.TEMPERATURE
        MeasurementUnit.KPA, MeasurementUnit.PSI -> UnitKind.PRESSURE
        MeasurementUnit.KMH, MeasurementUnit.MPH -> UnitKind.SPEED
        else -> UnitKind.OTHER
    }

/**
 * Which unit [UnitPreferences] wants a value expressed in, given the unit it actually arrived
 * in ([wireUnit] — see [com.revel.obdgauge.model.PidDefinition.unit]). Non-temperature,
 * non-pressure units (RPM, km/h, %...) pass through unchanged: there's no user toggle for them
 * today.
 */
fun UnitPreferences.displayUnitFor(wireUnit: MeasurementUnit): MeasurementUnit =
    when (wireUnit.kind()) {
        UnitKind.TEMPERATURE -> temperatureUnit
        UnitKind.PRESSURE -> pressureUnit
        // No user toggle for speed today — the gauge catalog's declared unit (MPH) is the
        // display unit, and OBD-61's km/h→mph conversion happens at the DisplayUnitDataSource
        // seam (the catalog declares MPH, the protocol emits KMH), not via a preference.
        UnitKind.SPEED -> wireUnit
        UnitKind.OTHER -> wireUnit
    }

/**
 * Display-unit conversion (OBD-21), pure and testable, deliberately generic over
 * [MeasurementUnit] rather than hardcoded per gauge.
 *
 * ### Why the source unit is never assumed
 * A [com.revel.obdgauge.model.Reading]'s value arrives already scaled to whatever
 * [com.revel.obdgauge.model.PidDefinition.unit] its channel declares (see
 * `DashboardPids.DASHBOARD_PIDS_BY_ID`) — today that's `FAHRENHEIT`/`PSI` for the `demo`
 * flavor's fake data (`DashboardPids.kt` declares it that way, matching what
 * `FakeVehicleDataSource`'s scripts actually emit); `:core:protocol`'s real registry parses to
 * `CELSIUS`/`KPA` instead (its `MODULE.md` "Unit strategy" section flags this exact mismatch as
 * unresolved until OBD-25 rewires `DASHBOARD_PIDS`). Every call site here reads the *declared*
 * `PidDefinition.unit` as the conversion's `from`, rather than assuming a fixed unit — so this
 * code needs zero changes on either side of that Phase-4 rewiring; only `DASHBOARD_PIDS`'
 * declared units (and `ThresholdConfig.seed`'s values, which must stay expressed in the same
 * unit — see that object's KDoc) need to change, and this module already reacts to whatever
 * they say.
 */
object UnitConversion {
    fun convert(
        value: Double,
        from: MeasurementUnit,
        to: MeasurementUnit,
    ): Double {
        if (from == to) return value
        return when (from.kind()) {
            UnitKind.TEMPERATURE -> convertTemperature(value, from, to)
            UnitKind.PRESSURE -> convertPressure(value, from, to)
            UnitKind.SPEED -> convertSpeed(value, from, to)
            UnitKind.OTHER -> value
        }
    }

    private fun convertSpeed(
        value: Double,
        from: MeasurementUnit,
        to: MeasurementUnit,
    ): Double =
        when {
            from == MeasurementUnit.KMH && to == MeasurementUnit.MPH -> value * MPH_PER_KMH
            from == MeasurementUnit.MPH && to == MeasurementUnit.KMH -> value / MPH_PER_KMH
            else -> value
        }

    private fun convertTemperature(
        value: Double,
        from: MeasurementUnit,
        to: MeasurementUnit,
    ): Double =
        when {
            from == MeasurementUnit.FAHRENHEIT && to == MeasurementUnit.CELSIUS ->
                (value - FAHRENHEIT_OFFSET) / FAHRENHEIT_SCALE
            from == MeasurementUnit.CELSIUS && to == MeasurementUnit.FAHRENHEIT ->
                value * FAHRENHEIT_SCALE + FAHRENHEIT_OFFSET
            else -> value
        }

    private fun convertPressure(
        value: Double,
        from: MeasurementUnit,
        to: MeasurementUnit,
    ): Double =
        when {
            from == MeasurementUnit.PSI && to == MeasurementUnit.KPA -> value * KPA_PER_PSI
            from == MeasurementUnit.KPA && to == MeasurementUnit.PSI -> value / KPA_PER_PSI
            else -> value
        }

    private const val FAHRENHEIT_SCALE = 9.0 / 5.0
    private const val FAHRENHEIT_OFFSET = 32.0

    // 1 psi = 6.894757 kPa, the standard conversion factor (matches common SAE/engineering
    // references; not sourced from kotlin-obd-api — see DECISIONS.md D1 on vendoring scope).
    private const val KPA_PER_PSI = 6.894757

    // 1 km/h = 0.621371 mph, the standard conversion factor (OBD-61).
    private const val MPH_PER_KMH = 0.621371
}
