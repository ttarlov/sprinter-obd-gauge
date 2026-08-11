package com.revel.obdgauge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the X-Gauge field decode, field by field.
 *
 * The control case is deliberately the **coolant** code, not the trans-temp one: coolant's right
 * answer is known independently from SAE J1979 (`A − 40` °C at `41 05`), so a decoder that
 * reproduces it has demonstrated its field conventions on ground truth. Every trans-temp
 * assertion below rests on that.
 */
class XGaugeCodeTest {
    private val coolant =
        XGaugeCode(name = "CLT", txd = "07DF0105", rxf = "034105000000", rxd = "1808", mth = "00010001FFD8")

    private val transTemp = MercedesPidRegistry.TRANS_TEMP_CODE

    @Test
    fun `control case - the published coolant X-Gauge decodes to the known-correct SAE PID`() {
        val decoded = coolant.decode()

        assertEquals("7DF", decoded.canId)
        assertEquals("0105", decoded.requestBytes)
        assertEquals(0x01, decoded.requestMode)
        assertEquals("4105", decoded.responseHeader)
        assertEquals(0, decoded.dataByteIndex)
        assertEquals(1, decoded.dataByteCount)
    }

    @Test
    fun `control case - coolant MTH is exactly SAE's A minus 40 in Celsius`() {
        val decoded = coolant.decode()

        assertEquals(1, decoded.multiplier)
        assertEquals(1, decoded.divisor)
        assertEquals(-40, decoded.adder)
        // Not a 9/5 conversion: this code displays Celsius directly, so no offset may be derived.
        assertFalse(decoded.isFahrenheitConversion)
        assertEquals(VendoredSaeScaling.temperatureCelsius(0x5A), decoded.displayValue(0x5A), TOLERANCE)
    }

    @Test
    fun `trans temp TXD splits into the transmission controller's id and a KWP 21 30 read`() {
        val decoded = transTemp.decode()

        assertEquals("7E1", decoded.canId)
        assertEquals("2130", decoded.requestBytes)
        assertEquals(0x21, decoded.requestMode)
        assertEquals("6130", decoded.responseHeader)
    }

    @Test
    fun `trans temp RXD reads one byte immediately after the response header`() {
        val decoded = transTemp.decode()

        // RXD 1808 = bit 24, 8 bits = frame byte 3 = [PCI][61][30][value].
        assertEquals(0, decoded.dataByteIndex)
        assertEquals(1, decoded.dataByteCount)
        assertEquals(1, decoded.requiredDataBytes)
    }

    @Test
    fun `trans temp RXF's PCI byte independently corroborates a three-byte reply`() {
        val decoded = transTemp.decode()

        // PCI 03 = "61 30 XX": exactly the header plus the one data byte RXD points at.
        assertEquals(0x03, decoded.rxFilterPci)
        assertEquals(decoded.responseHeader.length / 2 + decoded.requiredDataBytes, decoded.rxFilterPci)
    }

    @Test
    fun `MTH decodes as multiplier divisor and a two's-complement signed adder`() {
        val decoded = transTemp.decode()

        assertEquals(9, decoded.multiplier)
        assertEquals(5, decoded.divisor)
        assertEquals(-58, decoded.adder) // 0xFFC6
    }

    @Test
    fun `MTH's minus 58 resolves exactly to a Celsius raw carrying a minus 50 offset`() {
        val decoded = transTemp.decode()

        assertTrue(decoded.isFahrenheitConversion)
        assertEquals(TRANS_TEMP_CELSIUS_OFFSET, decoded.celsiusOffset, TOLERANCE)
    }

    @Test
    fun `the Celsius rewrite reproduces the ScanGauge formula over the whole raw byte range`() {
        val decoded = transTemp.decode()
        val offset = decoded.celsiusOffset

        for (raw in 0..MAX_BYTE) {
            val celsius = raw - offset
            val fahrenheit = celsius * F_PER_C + F_FREEZING
            assertEquals(
                "raw $raw: publishing Celsius must round-trip to the ScanGauge's displayed F",
                decoded.displayValue(raw),
                fahrenheit,
                TOLERANCE,
            )
        }
    }

    @Test
    fun `an SAE style minus 40 raw would have produced a minus 40 adder, not minus 58`() {
        // The counterfactual that makes -58 evidence rather than an anomaly: a code over an
        // A-40 raw is FFD8 -> -40, and 0xFFD8 is exactly what the coolant code carries.
        val saeStyle = transTemp.copy(mth = "00090005FFD8").decode()

        assertEquals(-40, saeStyle.adder)
        assertEquals(SAE_CELSIUS_OFFSET, saeStyle.celsiusOffset, TOLERANCE)
    }

    @Test
    fun `celsiusOffset refuses codes that are not a Celsius to Fahrenheit conversion`() {
        val notAConversion = transTemp.copy(mth = "00010001FFC6").decode()

        assertFalse(notAConversion.isFahrenheitConversion)
        assertThrowsIllegalArgument { notAConversion.celsiusOffset }
    }

    @Test
    fun `a sub-byte RXD bit field is refused rather than rounded`() {
        assertThrowsIllegalArgument { transTemp.copy(rxd = "1804").decode() }
        assertThrowsIllegalArgument { transTemp.copy(rxd = "1C08").decode() }
    }

    @Test
    fun `an RXD start bit inside the response header is refused`() {
        // Bit 8 = frame byte 1 = the 61 of "61 30", not data.
        assertThrowsIllegalArgument { transTemp.copy(rxd = "0808").decode() }
    }

    @Test
    fun `malformed fields fail loudly instead of decoding to something plausible`() {
        assertThrowsIllegalArgument { transTemp.copy(txd = "07E1213G").decode() }
        assertThrowsIllegalArgument { transTemp.copy(txd = "07E1").decode() }
        assertThrowsIllegalArgument { transTemp.copy(txd = "07E1213").decode() }
        assertThrowsIllegalArgument { transTemp.copy(rxd = "180800").decode() }
        assertThrowsIllegalArgument { transTemp.copy(mth = "0009000").decode() }
        assertThrowsIllegalArgument { transTemp.copy(rxf = "03").decode() }
        assertThrowsIllegalArgument { transTemp.copy(mth = "").decode() }
    }

    @Test
    fun `displayValue multiplies before dividing so no intermediate truncation occurs`() {
        val decoded = transTemp.decode()

        // raw 1 * 9 / 5 = 1.8, not 1 * (9/5 as Int = 1) = 1.
        assertEquals(1.8 - 58, decoded.displayValue(1), TOLERANCE)
    }

    @Test
    fun `a zero MTH divisor is refused rather than producing infinity`() {
        val decoded = transTemp.copy(mth = "000900000000").decode()

        assertThrowsIllegalArgument { decoded.displayValue(1) }
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().isNotEmpty())
        }
    }

    private companion object {
        const val TOLERANCE = 1e-9
        const val TRANS_TEMP_CELSIUS_OFFSET = 50.0
        const val SAE_CELSIUS_OFFSET = 40.0
        const val F_PER_C = 9.0 / 5.0
        const val F_FREEZING = 32.0
        const val MAX_BYTE = 255
    }
}
