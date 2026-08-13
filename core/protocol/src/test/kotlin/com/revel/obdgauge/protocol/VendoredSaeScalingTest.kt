package com.revel.obdgauge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exact-value checks of every vendored SAE J1979 formula at its boundaries.
 *
 * These assertions are written against the **SAE definitions**, not against the vendored source,
 * so they are an independent check of the transcription rather than a restatement of it. Exact
 * equality (`delta = 0.0`) is deliberate: every one of these results is representable in binary
 * floating point, so any drift means the arithmetic changed.
 */
class VendoredSaeScalingTest {
    // --- 0105 coolant / 010F intake air: A - 40 degrees C -----------------------------------

    @Test
    fun `temperature at raw 0x00 is the SAE floor of -40 C`() {
        assertEquals(-40.0, VendoredSaeScaling.temperatureCelsius(0x00), 0.0)
    }

    @Test
    fun `temperature at raw 0xFF is the SAE ceiling of 215 C`() {
        assertEquals(215.0, VendoredSaeScaling.temperatureCelsius(0xFF), 0.0)
    }

    @Test
    fun `temperature at raw 0x28 is 0 C`() {
        assertEquals(0.0, VendoredSaeScaling.temperatureCelsius(0x28), 0.0)
    }

    @Test
    fun `temperature at the transcript fixture's raw 0x5A is 50 C`() {
        assertEquals(50.0, VendoredSaeScaling.temperatureCelsius(0x5A), 0.0)
    }

    // --- 010C engine speed: (256A + B) / 4 rpm ----------------------------------------------

    @Test
    fun `rpm at raw 0x0000 is zero`() {
        assertEquals(0.0, VendoredSaeScaling.engineRpm(0x00, 0x00), 0.0)
    }

    @Test
    fun `rpm at raw 0x1AF8 is 1726 rpm`() {
        assertEquals(0x1AF8 / 4.0, VendoredSaeScaling.engineRpm(0x1A, 0xF8), 0.0)
        assertEquals(1726.0, VendoredSaeScaling.engineRpm(0x1A, 0xF8), 0.0)
    }

    @Test
    fun `rpm at the transcript fixture's raw 0x1F40 is 2000 rpm`() {
        assertEquals(2000.0, VendoredSaeScaling.engineRpm(0x1F, 0x40), 0.0)
    }

    @Test
    fun `rpm at raw 0xFFFF is the SAE ceiling of 16383_75 rpm`() {
        assertEquals(16383.75, VendoredSaeScaling.engineRpm(0xFF, 0xFF), 0.0)
    }

    /**
     * The documented divergence from kotlin-obd-api: its `Long / Int` divide truncates this to
     * `0`. SAE specifies quarter-rpm resolution, and this module implements SAE.
     */
    @Test
    fun `rpm keeps SAE quarter-rpm resolution instead of truncating like the vendored source`() {
        assertEquals(0.25, VendoredSaeScaling.engineRpm(0x00, 0x01), 0.0)
        assertEquals(0.75, VendoredSaeScaling.engineRpm(0x00, 0x03), 0.0)
        assertEquals(191.75, VendoredSaeScaling.engineRpm(0x02, 0xFF), 0.0)
    }

    // --- 010B MAP / 0133 barometric: A kPa absolute -----------------------------------------

    @Test
    fun `pressure at raw 0x65 is 101 kPa`() {
        assertEquals(101.0, VendoredSaeScaling.pressureKpa(0x65), 0.0)
    }

    @Test
    fun `pressure spans the full SAE range of 0 to 255 kPa`() {
        assertEquals(0.0, VendoredSaeScaling.pressureKpa(0x00), 0.0)
        assertEquals(255.0, VendoredSaeScaling.pressureKpa(0xFF), 0.0)
    }

    // --- 010D vehicle speed: A km/h ---------------------------------------------------------

    @Test
    fun `speed spans the full SAE range of 0 to 255 km per hour`() {
        assertEquals(0.0, VendoredSaeScaling.speedKmh(0x00), 0.0)
        assertEquals(80.0, VendoredSaeScaling.speedKmh(0x50), 0.0)
        assertEquals(255.0, VendoredSaeScaling.speedKmh(0xFF), 0.0)
    }

    // --- byte handling ----------------------------------------------------------------------

    @Test
    fun `dataByte reads a high byte as unsigned, not as a negative Kotlin Byte`() {
        val data = byteArrayOf(0xBE.toByte(), 0x3E, 0xB8.toByte(), 0x11)
        assertEquals(0xBE, VendoredSaeScaling.dataByte(data, 0))
        assertEquals(0x3E, VendoredSaeScaling.dataByte(data, 1))
        assertEquals(0xB8, VendoredSaeScaling.dataByte(data, 2))
        assertEquals(0x11, VendoredSaeScaling.dataByte(data, 3))
    }

    @Test
    fun `dataByte rejects an out-of-bounds index instead of returning a made-up value`() {
        assertThrows(IllegalArgumentException::class.java) {
            VendoredSaeScaling.dataByte(byteArrayOf(0x01), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VendoredSaeScaling.dataByte(ByteArray(0), 0)
        }
    }

    // --- 0104 engine load / 0111 throttle: A * 100 / 255 percent (OBD-43) -------------------

    @Test
    fun `percent at raw 0x00 is exactly 0`() {
        assertEquals(0.0, VendoredSaeScaling.percent(0x00), 0.0)
    }

    @Test
    fun `percent at raw 0xFF is exactly 100, which is what the 255 divisor buys`() {
        // The whole point of dividing by 255 rather than 256: full scale is full scale. A /256
        // implementation lands on 99.609375 here and is wrong by a little bit everywhere else,
        // which is invisible on a gauge and therefore exactly the kind of error to pin.
        assertEquals(100.0, VendoredSaeScaling.percent(0xFF), 0.0)
    }

    @Test
    fun `percent is exact wherever the SAE formula produces a whole number`() {
        assertEquals(20.0, VendoredSaeScaling.percent(51), 0.0)
        assertEquals(40.0, VendoredSaeScaling.percent(102), 0.0)
        assertEquals(60.0, VendoredSaeScaling.percent(153), 0.0)
        assertEquals(80.0, VendoredSaeScaling.percent(204), 0.0)
    }

    @Test
    fun `percent reproduces the values captured from the van on 2026-08-12`() {
        // docs/hardware/session-2026-08-12.md read 0x8E/0x90 as load "~56 %" and 0xD3 as
        // throttle "83 %". Those rounded readings are the independent anchor — they were
        // written down at the van, from a different tool, before this function existed.
        assertEquals(56L, Math.round(VendoredSaeScaling.percent(0x8E)))
        assertEquals(56L, Math.round(VendoredSaeScaling.percent(0x90)))
        assertEquals(83L, Math.round(VendoredSaeScaling.percent(0xD3)))

        // And the full-precision values behind them, so a change in the arithmetic (an
        // int-truncating divide, a /256, a reordered multiply) cannot hide inside the rounding.
        assertEquals(14200.0 / 255.0, VendoredSaeScaling.percent(0x8E), 0.0)
        assertEquals(14400.0 / 255.0, VendoredSaeScaling.percent(0x90), 0.0)
        assertEquals(21100.0 / 255.0, VendoredSaeScaling.percent(0xD3), 0.0)
    }

    @Test
    fun `percent never leaves 0 to 100 and rises monotonically over the whole byte range`() {
        var previous = -1.0
        for (raw in 0..0xFF) {
            val value = VendoredSaeScaling.percent(raw)
            assertTrue("raw $raw produced $value", value in 0.0..100.0)
            assertTrue("raw $raw did not increase: $previous -> $value", value > previous)
            previous = value
        }
    }

    // --- OBD-50 (session 2, 2026-08-13): fuel rate, module voltage, torque ------------------

    @Test
    fun `fuel rate at raw 0x0000 is zero`() {
        assertEquals(0.0, VendoredSaeScaling.fuelRateLitersPerHour(0x00, 0x00), 0.0)
    }

    @Test
    fun `fuel rate at raw 0xFFFF is the SAE ceiling of 3276_75 L per h`() {
        assertEquals(3276.75, VendoredSaeScaling.fuelRateLitersPerHour(0xFF, 0xFF), 0.0)
    }

    @Test
    fun `fuel rate reproduces the van's captured idle value from session 2`() {
        // docs/hardware/session-2026-08-13.md §5: `41 5E 00 17` -> 1.15 L/h idle, the
        // independently written-down anchor.
        assertEquals(1.15, VendoredSaeScaling.fuelRateLitersPerHour(0x00, 0x17), 0.0)
        // Full-precision form behind it, so a truncating divide or a /256 slip cannot hide
        // inside a rounding that happens to also read "1.15".
        assertEquals(23.0 / 20.0, VendoredSaeScaling.fuelRateLitersPerHour(0x00, 0x17), 0.0)
    }

    // --- 0142 module voltage: (256A + B) / 1000 volts (OBD-50) ------------------------------

    @Test
    fun `module voltage at raw 0x0000 is zero`() {
        assertEquals(0.0, VendoredSaeScaling.moduleVoltageVolts(0x00, 0x00), 0.0)
    }

    @Test
    fun `module voltage at raw 0xFFFF is the SAE ceiling of 65_535 V`() {
        assertEquals(65.535, VendoredSaeScaling.moduleVoltageVolts(0xFF, 0xFF), 0.0)
    }

    @Test
    fun `module voltage reproduces the van's captured value from session 2`() {
        // `41 42 36 E2` -> 14.05 V, matched against the ATRV's own 14.1 V.
        assertEquals(14.05, VendoredSaeScaling.moduleVoltageVolts(0x36, 0xE2), 0.0)
        assertEquals(14050.0 / 1000.0, VendoredSaeScaling.moduleVoltageVolts(0x36, 0xE2), 0.0)
    }

    // --- 0161/0162 demand/actual torque: A - 125 percent, signed (OBD-50) -------------------

    @Test
    fun `torque percent at raw 0x00 is the SAE floor of -125 percent`() {
        assertEquals(-125.0, VendoredSaeScaling.torquePercent(0x00), 0.0)
    }

    @Test
    fun `torque percent at raw 0xFF is the SAE ceiling of 130 percent`() {
        assertEquals(130.0, VendoredSaeScaling.torquePercent(0xFF), 0.0)
    }

    @Test
    fun `torque percent at raw 0x7D is exactly 0, the reference-torque baseline`() {
        assertEquals(0.0, VendoredSaeScaling.torquePercent(0x7D), 0.0)
    }

    @Test
    fun `torque percent reproduces the van's captured demand and actual values from session 2`() {
        // `41 61 82` (demand) -> 5 %, `41 62 88` (actual) -> 11 %.
        assertEquals(5.0, VendoredSaeScaling.torquePercent(0x82), 0.0)
        assertEquals(11.0, VendoredSaeScaling.torquePercent(0x88), 0.0)
    }

    // --- OBD-50: oil temp / ambient temp reuse temperatureCelsius; fuel level / accel pedal --
    // --- reuse percent. Their own van anchors, since the formula tests above only pin the ----
    // --- formula, not this session's specific bytes. -----------------------------------------

    @Test
    fun `temperatureCelsius reproduces the van's captured oil and ambient temps from session 2`() {
        // `41 5C 81` (oil) -> 89 C, `41 46 3C` (ambient) -> 20 C.
        assertEquals(89.0, VendoredSaeScaling.temperatureCelsius(0x81), 0.0)
        assertEquals(20.0, VendoredSaeScaling.temperatureCelsius(0x3C), 0.0)
    }

    @Test
    fun `percent reproduces the van's captured fuel level and accel pedal from session 2`() {
        // `41 2F 6D` -> 42.7 % fuel level, `41 49 0D` -> 5.1 % accel pedal — the session's own
        // written-down one-decimal readings.
        assertEquals(42.7, roundToOneDecimal(VendoredSaeScaling.percent(0x6D)), 0.0)
        assertEquals(5.1, roundToOneDecimal(VendoredSaeScaling.percent(0x0D)), 0.0)

        // Full-precision values behind those roundings.
        assertEquals(10900.0 / 255.0, VendoredSaeScaling.percent(0x6D), 0.0)
        assertEquals(1300.0 / 255.0, VendoredSaeScaling.percent(0x0D), 0.0)
    }

    private fun roundToOneDecimal(value: Double): Double = Math.round(value * 10.0) / 10.0

    @Test
    fun `scaling rejects values outside one unsigned byte`() {
        assertThrows(IllegalArgumentException::class.java) { VendoredSaeScaling.temperatureCelsius(256) }
        assertThrows(IllegalArgumentException::class.java) { VendoredSaeScaling.temperatureCelsius(-1) }
        assertThrows(IllegalArgumentException::class.java) { VendoredSaeScaling.pressureKpa(256) }
        assertThrows(IllegalArgumentException::class.java) { VendoredSaeScaling.speedKmh(-1) }
        assertThrows(IllegalArgumentException::class.java) { VendoredSaeScaling.engineRpm(0x00, 256) }
        assertThrows(IllegalArgumentException::class.java) { VendoredSaeScaling.engineRpm(-1, 0x00) }
        assertThrows(IllegalArgumentException::class.java) { VendoredSaeScaling.fuelRateLitersPerHour(256, 0x00) }
        assertThrows(IllegalArgumentException::class.java) { VendoredSaeScaling.fuelRateLitersPerHour(0x00, -1) }
        assertThrows(IllegalArgumentException::class.java) { VendoredSaeScaling.moduleVoltageVolts(256, 0x00) }
        assertThrows(IllegalArgumentException::class.java) { VendoredSaeScaling.moduleVoltageVolts(0x00, -1) }
        assertThrows(IllegalArgumentException::class.java) { VendoredSaeScaling.torquePercent(256) }
        assertThrows(IllegalArgumentException::class.java) { VendoredSaeScaling.torquePercent(-1) }
    }
}
