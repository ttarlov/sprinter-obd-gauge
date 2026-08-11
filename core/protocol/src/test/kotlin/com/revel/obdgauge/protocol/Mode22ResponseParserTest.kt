package com.revel.obdgauge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Mode22ResponseParserTest {
    private val transTemp = MercedesPidRegistry.transTemp

    @Test
    fun `parses the predicted trans temp reply into Celsius`() {
        assertValue(95.0, "61 30 91")
    }

    @Test
    fun `tolerates the same wire noise the standard parser does`() {
        assertValue(95.0, "613091")
        assertValue(95.0, "61 30 91\r>")
        assertValue(95.0, "SEARCHING...\r61 30 91")
        assertValue(95.0, "2130\r61 30 91") // echo remnant before the answer
        assertValue(95.0, "61 30 91 00 00 00") // ECU padded the frame
        assertValue(95.0, "\r\n  61 30 91  \r\n")
        assertValue(121.0, "61 30 ab") // lowercase hex
    }

    @Test
    fun `a frame for a different identifier is refused, not read as trans temp`() {
        // 61 31 is local id 0x31 - a different channel entirely. Reading its byte would put a
        // confident wrong temperature on the gauge.
        val reason = failure("61 31 91")

        assertTrue(reason is ParseFailure.NoMatchingFrame)
        assertEquals("6130", (reason as ParseFailure.NoMatchingFrame).expectedHeader)
    }

    @Test
    fun `a mode-01 answer is not mistaken for a mode-22 one`() {
        assertTrue(failure("41 05 91") is ParseFailure.NoMatchingFrame)
    }

    @Test
    fun `a truncated frame is refused rather than scaled from a missing byte`() {
        val reason = failure("61 30")

        assertTrue(reason is ParseFailure.UnexpectedDataLength)
        reason as ParseFailure.UnexpectedDataLength
        assertEquals(1, reason.expected)
        assertEquals(0, reason.actual)
    }

    @Test
    fun `the ECU's negative response is typed against the KWP request mode, not mode 01`() {
        val reason = failure("7F 21 12")

        assertTrue(reason is ParseFailure.NegativeResponse)
        reason as ParseFailure.NegativeResponse
        assertEquals(0x21, reason.requestMode)
        assertEquals(0x12, reason.code)
    }

    @Test
    fun `ELM327 and bus status responses become their own typed failures`() {
        assertEquals(ParseFailure.NoData, failure("NO DATA"))
        assertEquals(ParseFailure.UnknownCommand, failure("?"))
        assertEquals(ParseFailure.Stopped, failure("STOPPED"))
        assertEquals(ParseFailure.UnableToConnect, failure("UNABLE TO CONNECT"))
        assertEquals(ParseFailure.Empty, failure("   \r  >  "))
        assertTrue(failure("CAN ERROR") is ParseFailure.BusError)
    }

    @Test
    fun `an unaligned header match is refused`() {
        // "A613091F" contains the digits 6130 only at an odd nibble offset, where they are the
        // low nibble of one byte and the high nibble of the next - not a header at all.
        assertTrue(failure("A6 13 09 1F") is ParseFailure.NoMatchingFrame)
    }

    @Test
    fun `an 11-bit CAN id left switched on fails safe on digit parity`() {
        // ATH1 output: "7E9 03 61 30 91". The 3-digit id makes the line odd-length, so no even
        // offset can match and the parse ends typed rather than shifted.
        val reason = failure("7E9 03 61 30 91")

        assertTrue("must not invent a value: $reason", reason is ParseFailure.MalformedHex)
    }

    @Test
    fun `a 29-bit CAN id left switched on stays byte-aligned and still finds the real frame`() {
        // Four whole header bytes preserve alignment, so the true 6130 is found at its true offset.
        assertValue(95.0, "18 DA F1 E9 03 61 30 91")
    }

    @Test
    fun `a scaling lambda that throws becomes a typed failure, never a value`() {
        val exploding = transTemp.copy(definition = transTemp.definition.copy(parse = { error("boom") }))

        val outcome = Mode22ResponseParser.parse(exploding, "61 30 91")

        assertTrue(outcome.failureOrNull() is ParseFailure.ScalingError)
    }

    @Test
    fun `a non-finite scaling result is refused`() {
        val nonFinite = transTemp.copy(definition = transTemp.definition.copy(parse = { Double.NaN }))

        assertTrue(Mode22ResponseParser.parse(nonFinite, "61 30 91").failureOrNull() is ParseFailure.ScalingError)
    }

    @Test
    fun `multi-byte identifiers work, so a true UDS mode-22 PID needs no new machinery`() {
        val uds =
            transTemp.copy(
                requestBytes = "220543",
                responseHeader = "620543",
                dataByteIndex = 0,
                dataByteCount = 1,
            )

        assertEquals(0x22, uds.requestMode)
        assertEquals(42.0, Mode22ResponseParser.parse(uds, "62 05 43 5C").valueOrNull()!!, TOLERANCE)
        assertTrue(Mode22ResponseParser.parse(uds, "7F 22 31").failureOrNull() is ParseFailure.NegativeResponse)
    }

    @Test
    fun `a value byte deeper in the frame is extracted at its own offset`() {
        val readsSecondByte = transTemp.definition.copy(parse = { VendoredSaeScaling.dataByte(it, 1).toDouble() })
        val second = transTemp.copy(dataByteIndex = 1, dataByteCount = 1, definition = readsSecondByte)

        assertEquals(2, second.requiredDataBytes)
        assertEquals(0x77.toDouble(), Mode22ResponseParser.parse(second, "6130 91 77").valueOrNull()!!, TOLERANCE)
        // ...and a frame that stops before that byte is refused rather than defaulted to zero.
        val short = Mode22ResponseParser.parse(second, "6130 91").failureOrNull()
        assertTrue(short is ParseFailure.UnexpectedDataLength)
    }

    private fun assertValue(
        expected: Double,
        raw: String,
    ) {
        val outcome = Mode22ResponseParser.parse(transTemp, raw)
        assertEquals("parsing \"$raw\"", expected, outcome.valueOrNull() ?: Double.NaN, TOLERANCE)
    }

    private fun failure(raw: String): ParseFailure =
        requireNotNull(Mode22ResponseParser.parse(transTemp, raw).failureOrNull()) {
            "expected a failure for \"$raw\""
        }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
