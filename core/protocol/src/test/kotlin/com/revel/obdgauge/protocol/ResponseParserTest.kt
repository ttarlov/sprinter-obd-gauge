package com.revel.obdgauge.protocol

import com.revel.obdgauge.testing.link.FakeObdLink
import com.revel.obdgauge.testing.link.TranscriptParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Response-parser behaviour: correct values on well-formed frames, correct *typed failures* on
 * everything else, and no value at all when the frame cannot be trusted.
 *
 * The bar is not "does not crash" — it is "never produces a number that is wrong". Several tests
 * below assert a [ParseFailure] where a sloppier parser would happily return a plausible value.
 */
class ResponseParserTest {
    private val transcript = TranscriptParser.parseResource(TRANSCRIPT_RESOURCE)

    // --- values from the shipped transcript fixture ------------------------------------------

    @Test
    fun `parses every standard PID in the shipped transcript fixture`() {
        assertValue(50.0, PidRegistry.coolant, responseFor("0105"))
        assertValue(100.0, PidRegistry.map, responseFor("010B"))
        assertValue(98.0, PidRegistry.baro, responseFor("0133"))
        assertValue(2000.0, PidRegistry.rpm, responseFor("010C"))
    }

    @Test
    fun `parses PID responses read back over FakeObdLink`() =
        runTest {
            val link = FakeObdLink(transcript)
            assertValue(50.0, PidRegistry.coolant, link.sendRaw(PidRegistry.coolant.command))
            assertValue(2000.0, PidRegistry.rpm, link.sendRaw(PidRegistry.rpm.command))
        }

    @Test
    fun `parses the PIDs the fixture does not script, from synthetic frames`() {
        assertValue(0.0, PidRegistry.intakeAirTemp, "41 0F 28")
        assertValue(80.0, PidRegistry.speed, "41 0D 50")
    }

    // --- tolerance ---------------------------------------------------------------------------

    @Test
    fun `tolerates a leading SEARCHING line`() {
        assertValue(50.0, PidRegistry.coolant, "SEARCHING...\r41 05 5A")
    }

    @Test
    fun `tolerates spaces off, as ATS0 leaves the dongle`() {
        assertValue(50.0, PidRegistry.coolant, "41055A")
    }

    @Test
    fun `tolerates arbitrary interleaved whitespace, CR, LF and prompt characters`() {
        assertValue(50.0, PidRegistry.coolant, "\r\n   41   05\t5A  \r\n>")
        assertValue(50.0, PidRegistry.coolant, "41 05 5A>")
        assertValue(50.0, PidRegistry.coolant, "\r\r\r41\r05\r5A\r\r")
    }

    @Test
    fun `tolerates lowercase hex`() {
        assertValue(50.0, PidRegistry.coolant, "41 05 5a")
    }

    @Test
    fun `tolerates a command echo remnant from a dongle that ignored ATE0`() {
        assertValue(50.0, PidRegistry.coolant, "0105\r41055A")
        assertValue(2000.0, PidRegistry.rpm, "010C\r41 0C 1F 40")
    }

    @Test
    fun `tolerates BUS INIT and progress-dot noise`() {
        assertValue(50.0, PidRegistry.coolant, "BUS INIT: OK\r41 05 5A")
        assertValue(50.0, PidRegistry.coolant, "....\r41 05 5A")
    }

    @Test
    fun `tolerates extra data bytes past the PID's defined length`() {
        assertValue(50.0, PidRegistry.coolant, "41 05 5A 00 00 00")
        assertValue(2000.0, PidRegistry.rpm, "41 0C 1F 40 AA BB")
    }

    @Test
    fun `reassembles ELM's indexed multi-line long-response format`() {
        val raw = "014\r0: 41 00 BE 3E\r1: B8 11 00 00"
        val parsed = ResponseParser.dataBytes(raw, responseMode = 0x41, pid = 0x00, expectedCount = 4)
        assertEquals(listOf(0xBE, 0x3E, 0xB8, 0x11), parsed.valueOrNull())
    }

    @Test
    fun `drops a bare ISO-TP length line that would otherwise break byte alignment`() {
        val raw = "009\r4100BE3EB811"
        val parsed = ResponseParser.dataBytes(raw, responseMode = 0x41, pid = 0x00, expectedCount = 4)
        assertEquals(listOf(0xBE, 0x3E, 0xB8, 0x11), parsed.valueOrNull())
    }

    // --- typed failures ----------------------------------------------------------------------

    @Test
    fun `NO DATA is a typed failure, not a value and not a throw`() {
        assertFailure(ParseFailure.NoData, PidRegistry.coolant, "NO DATA")
        assertFailure(ParseFailure.NoData, PidRegistry.coolant, "SEARCHING...\rNO DATA\r")
    }

    @Test
    fun `STOPPED is a typed failure`() {
        assertFailure(ParseFailure.Stopped, PidRegistry.coolant, "STOPPED")
    }

    @Test
    fun `UNABLE TO CONNECT is a typed failure distinct from NO DATA`() {
        assertFailure(ParseFailure.UnableToConnect, PidRegistry.coolant, "UNABLE TO CONNECT")
    }

    @Test
    fun `a bare question mark is the dongle's unknown-command reply`() {
        assertFailure(ParseFailure.UnknownCommand, PidRegistry.coolant, "?")
        assertFailure(ParseFailure.UnknownCommand, PidRegistry.coolant, "\r?\r>")
    }

    @Test
    fun `bus-level errors are typed failures carrying the raw text`() {
        for (raw in listOf("CAN ERROR", "BUS ERROR", "BUS INIT: ERROR", "DATA ERROR", "BUFFER FULL", "ERROR")) {
            val failure = ResponseParser.parse(PidRegistry.coolant, raw).failureOrNull()
            assertTrue("expected BusError for \"$raw\", got $failure", failure is ParseFailure.BusError)
            assertEquals(raw, (failure as ParseFailure.BusError).raw)
        }
    }

    @Test
    fun `an empty or prompt-only response is a typed failure`() {
        assertFailure(ParseFailure.Empty, PidRegistry.coolant, "")
        assertFailure(ParseFailure.Empty, PidRegistry.coolant, "   \r\n  >  \r")
    }

    @Test
    fun `garbage that is not hex at all yields no matching frame`() {
        val failure = ResponseParser.parse(PidRegistry.coolant, "G@RB13D//FRAME??").failureOrNull()
        assertTrue(failure is ParseFailure.NoMatchingFrame)
        assertEquals("4105", (failure as ParseFailure.NoMatchingFrame).expectedHeader)
    }

    @Test
    fun `a frame for a different PID is never scaled as this one`() {
        val failure = ResponseParser.parse(PidRegistry.coolant, "41 0C 1F 40").failureOrNull()
        assertTrue(failure is ParseFailure.NoMatchingFrame)
        assertNull(ResponseParser.parse(PidRegistry.coolant, "41 0C 1F 40").valueOrNull())
    }

    @Test
    fun `a short frame is a typed failure rather than a byte silently read as zero`() {
        assertFailure(ParseFailure.UnexpectedDataLength(1, 0), PidRegistry.coolant, "41 05")
        assertFailure(ParseFailure.UnexpectedDataLength(2, 1), PidRegistry.rpm, "41 0C 1F")
    }

    @Test
    fun `hex that cannot be framed into whole bytes is a typed failure`() {
        val failure = ResponseParser.parse(PidRegistry.coolant, "410").failureOrNull()
        assertTrue("got $failure", failure is ParseFailure.MalformedHex)
    }

    @Test
    fun `a 7F negative response is reported with its NRC`() {
        assertFailure(ParseFailure.NegativeResponse(0x01, 0x12), PidRegistry.coolant, "7F 01 12")
        assertFailure(ParseFailure.NegativeResponse(0x01, null), PidRegistry.coolant, "7F01")
    }

    @Test
    fun `a header found only at an unaligned offset is not treated as a frame`() {
        // "A4105A": the digits 4105 appear at index 1, i.e. spanning two byte boundaries.
        val failure = ResponseParser.parse(PidRegistry.coolant, "A4105A").failureOrNull()
        assertTrue("got $failure", failure is ParseFailure.NoMatchingFrame)
    }

    @Test
    fun `the leftmost aligned header wins, so an echo cannot shadow the answer`() {
        assertValue(50.0, PidRegistry.coolant, "01054105 5A")
    }

    @Test
    fun `a truncated frame followed by a complete one resolves to the complete frame`() {
        // Review round-1 MAJOR: joined-payload framing let the truncated "41 05" read the next
        // frame's mode byte (0x41 = 65 raw) as data — 25 deg C instead of 50. Per-line framing
        // must pick the complete frame.
        assertValue(50.0, PidRegistry.coolant, "41 05\r41 05 5A")
    }

    @Test
    fun `a truncated rpm frame followed by a complete one resolves to the complete frame`() {
        assertValue(2000.0, PidRegistry.rpm, "41 0C 1F\r41 0C 1F 40")
    }

    // --- scaling-boundary failures ------------------------------------------------------------

    @Test
    fun `a throwing parse lambda becomes a typed scaling error, never an exception`() {
        val exploding = PidRegistry.coolant.withParse { error("boom") }
        val failure = ResponseParser.parse(exploding, "41 05 5A").failureOrNull()
        assertTrue("got $failure", failure is ParseFailure.ScalingError)
        assertTrue((failure as ParseFailure.ScalingError).message.contains("boom"))
    }

    @Test
    fun `a non-finite parse result is rejected rather than shown on a gauge`() {
        for (poison in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val spec = PidRegistry.coolant.withParse { poison }
            val outcome = ResponseParser.parse(spec, "41 05 5A")
            assertNull(outcome.valueOrNull())
            assertTrue(outcome.failureOrNull() is ParseFailure.ScalingError)
        }
    }

    // --- outcome helpers ----------------------------------------------------------------------

    @Test
    fun `outcome accessors expose exactly one of value or failure`() {
        val ok = ResponseParser.parse(PidRegistry.coolant, "41 05 5A")
        assertEquals(50.0, requireNotNull(ok.valueOrNull()), 0.0)
        assertNull(ok.failureOrNull())

        val bad = ResponseParser.parse(PidRegistry.coolant, "NO DATA")
        assertNull(bad.valueOrNull())
        assertEquals(ParseFailure.NoData, bad.failureOrNull())
    }

    // --- fixtures -----------------------------------------------------------------------------

    private fun responseFor(command: String): String =
        transcript.first { it.command.equals(command, ignoreCase = true) }.response

    private fun assertValue(
        expected: Double,
        spec: StandardPidSpec,
        raw: String,
    ) {
        val outcome = ResponseParser.parse(spec, raw)
        val value = outcome.valueOrNull()
        assertTrue("expected a value for \"$raw\", got ${outcome.failureOrNull()}", value != null)
        assertEquals(expected, requireNotNull(value), 0.0)
    }

    private fun assertFailure(
        expected: ParseFailure,
        spec: StandardPidSpec,
        raw: String,
    ) {
        val outcome = ResponseParser.parse(spec, raw)
        assertNull("expected no value for \"$raw\"", outcome.valueOrNull())
        assertEquals(expected, outcome.failureOrNull())
    }

    private companion object {
        const val TRANSCRIPT_RESOURCE = "transcripts/elm327-init-and-pids.txt"
    }
}

/** Copies a spec with a different scaling lambda, for exercising the parser's scaling guard. */
internal fun StandardPidSpec.withParse(parse: (ByteArray) -> Double): StandardPidSpec =
    copy(definition = definition.copy(parse = parse))
