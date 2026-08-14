package com.revel.obdgauge.app.gauge

import com.revel.obdgauge.app.settings.UnitPreferences
import com.revel.obdgauge.model.MeasurementUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class UnitConversionTest {
    @Test
    fun `same unit is a no-op`() {
        assertEquals(220.0, UnitConversion.convert(220.0, MeasurementUnit.FAHRENHEIT, MeasurementUnit.FAHRENHEIT), 0.0)
    }

    @Test
    fun `fahrenheit to celsius`() {
        assertEquals(0.0, UnitConversion.convert(32.0, MeasurementUnit.FAHRENHEIT, MeasurementUnit.CELSIUS), 1e-9)
        assertEquals(100.0, UnitConversion.convert(212.0, MeasurementUnit.FAHRENHEIT, MeasurementUnit.CELSIUS), 1e-9)
    }

    @Test
    fun `celsius to fahrenheit`() {
        assertEquals(32.0, UnitConversion.convert(0.0, MeasurementUnit.CELSIUS, MeasurementUnit.FAHRENHEIT), 1e-9)
        assertEquals(212.0, UnitConversion.convert(100.0, MeasurementUnit.CELSIUS, MeasurementUnit.FAHRENHEIT), 1e-9)
    }

    @Test
    fun `psi to kpa`() {
        assertEquals(6.894757, UnitConversion.convert(1.0, MeasurementUnit.PSI, MeasurementUnit.KPA), 1e-6)
    }

    @Test
    fun `kpa to psi round trips`() {
        val kpa = UnitConversion.convert(14.7, MeasurementUnit.PSI, MeasurementUnit.KPA)
        val roundTripped = UnitConversion.convert(kpa, MeasurementUnit.KPA, MeasurementUnit.PSI)
        assertEquals(14.7, roundTripped, 1e-9)
    }

    @Test
    fun `kmh to mph`() {
        // OBD-61: 1 km/h = 0.621371 mph; 100 km/h = 62.1371 mph; 96.56 km/h ≈ 60 mph.
        assertEquals(0.621371, UnitConversion.convert(1.0, MeasurementUnit.KMH, MeasurementUnit.MPH), 1e-6)
        assertEquals(62.1371, UnitConversion.convert(100.0, MeasurementUnit.KMH, MeasurementUnit.MPH), 1e-4)
    }

    @Test
    fun `mph to kmh round trips`() {
        val mph = UnitConversion.convert(105.0, MeasurementUnit.KMH, MeasurementUnit.MPH)
        val roundTripped = UnitConversion.convert(mph, MeasurementUnit.MPH, MeasurementUnit.KMH)
        assertEquals(105.0, roundTripped, 1e-9)
    }

    @Test
    fun `units outside temperature and pressure pass through unchanged`() {
        assertEquals(3000.0, UnitConversion.convert(3000.0, MeasurementUnit.RPM, MeasurementUnit.RPM), 0.0)
    }

    @Test
    fun `kind classifies each MeasurementUnit`() {
        assertEquals(UnitKind.TEMPERATURE, MeasurementUnit.CELSIUS.kind())
        assertEquals(UnitKind.TEMPERATURE, MeasurementUnit.FAHRENHEIT.kind())
        assertEquals(UnitKind.PRESSURE, MeasurementUnit.KPA.kind())
        assertEquals(UnitKind.PRESSURE, MeasurementUnit.PSI.kind())
        assertEquals(UnitKind.OTHER, MeasurementUnit.RPM.kind())
        // OBD-61: km/h and mph are their own SPEED kind so UnitConversion converts between them.
        assertEquals(UnitKind.SPEED, MeasurementUnit.KMH.kind())
        assertEquals(UnitKind.SPEED, MeasurementUnit.MPH.kind())
        assertEquals(UnitKind.OTHER, MeasurementUnit.PERCENT.kind())
    }

    @Test
    fun `displayUnitFor picks the preference matching the wire unit's kind`() {
        val prefs = UnitPreferences(temperatureUnit = MeasurementUnit.CELSIUS, pressureUnit = MeasurementUnit.KPA)

        assertEquals(MeasurementUnit.CELSIUS, prefs.displayUnitFor(MeasurementUnit.FAHRENHEIT))
        assertEquals(MeasurementUnit.KPA, prefs.displayUnitFor(MeasurementUnit.PSI))
        assertEquals(MeasurementUnit.RPM, prefs.displayUnitFor(MeasurementUnit.RPM))
    }

    @Test
    fun `default UnitPreferences matches DASHBOARD_PIDS' declared units, a no-op conversion`() {
        val defaults = UnitPreferences()
        DASHBOARD_PIDS_BY_ID.values.forEach { pid ->
            val displayUnit = defaults.displayUnitFor(pid.unit)
            assertEquals(pid.unit, displayUnit)
        }
    }
}
