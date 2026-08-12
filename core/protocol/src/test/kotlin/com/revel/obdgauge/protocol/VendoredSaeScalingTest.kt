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

    @Test
    fun `scaling rejects values outside one unsigned byte`() {
        assertThrows(IllegalArgumentException::class.java) { VendoredSaeScaling.temperatureCelsius(256) }
        assertThrows(IllegalArgumentException::class.java) { VendoredSaeScaling.temperatureCelsius(-1) }
        assertThrows(IllegalArgumentException::class.java) { VendoredSaeScaling.pressureKpa(256) }
        assertThrows(IllegalArgumentException::class.java) { VendoredSaeScaling.speedKmh(-1) }
        assertThrows(IllegalArgumentException::class.java) { VendoredSaeScaling.engineRpm(0x00, 256) }
        assertThrows(IllegalArgumentException::class.java) { VendoredSaeScaling.engineRpm(-1, 0x00) }
    }
}
