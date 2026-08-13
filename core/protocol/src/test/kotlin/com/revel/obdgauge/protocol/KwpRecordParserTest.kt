package com.revel.obdgauge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `21 30` reassembly, against the records the van actually sent (OBD-49 framing captures).
 *
 * This suite is about the **reassembly machinery** — kept for OBD-51 to re-identify the real
 * transmission-temperature byte, since the OBD-55 byte-1 `63 − raw` decode was falsified on-vehicle
 * (OBD-59, `docs/hardware/session-4-2026-08-13-transtemp-FALSIFIED.md`) and retired. So the
 * assertions here read **raw bytes** and the byte-11 coolant probe (`raw − 50`, exact on an integer
 * byte), never a trans-temp value: what must hold is that the reassembly lands on the right offsets,
 * which is exactly what the re-identification will depend on.
 */
class KwpRecordParserTest {
    private val spec = TcuRecordRegistry.transTempRecord

    // ---- the three captures ----

    @Test
    fun `the warm-idle capture reassembles into the 24 record bytes the session wrote down`() {
        val record = recordFrom(TcuRecordCaptures.WARM_IDLE)

        assertEquals(
            listOf(
                0x00,
                0x13,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x08,
                0x04,
                0x00,
                0xDD,
                0x8E,
                0xFF,
                0xF3,
                0xFF,
                0xF3,
                0x00,
                0x00,
                0x86,
                0x18,
                0x00,
                0x08,
                0x00,
                0x00,
            ),
            record.bytes,
        )
    }

    // OBD-59: the byte-1 `63 − raw` decode was FALSIFIED on-vehicle (byte 1 jumps at operating RPM;
    // docs/hardware/session-4-2026-08-13-transtemp-FALSIFIED.md) and retired, so the byte-1 anchor,
    // the coolant-decoupling identification, and the inverse-law tests that pinned it are gone with
    // it — there is no trans-temp decode left to assert. The reassembly-correctness checks below
    // (raw bytes and the byte-11 coolant probe) are what OBD-51 re-identifies the real byte on top of.

    @Test
    fun `the byte-11 coolant anchor holds at 92, 91 and 97 C across the session`() {
        // The reassembly-correctness probe, not a channel: engine coolant read out of the TCU's own
        // record. It moved +6 C over a 15-minute drive, so it is live data rather than a
        // constant — and a reassembly off by one byte cannot land on all three of these.
        assertEquals(92.0, TcuRecordRegistry.tcuCoolantCelsius(recordFrom(TcuRecordCaptures.WARM_IDLE)), 0.0)
        assertEquals(91.0, TcuRecordRegistry.tcuCoolantCelsius(recordFrom(TcuRecordCaptures.POST_STALL)), 0.0)
        assertEquals(97.0, TcuRecordRegistry.tcuCoolantCelsius(recordFrom(TcuRecordCaptures.POST_DRIVE)), 0.0)
    }

    @Test
    fun `the byte offsets hold across the 2026-08-12 records the session wrote down`() {
        // byte 1 (the falsified trans-temp candidate) 13 -> 13 -> 12 and byte 19 (uncatalogued
        // status) 18 -> 10 -> 00, as the field notes recorded — pinned as evidence the reassembly
        // lands on the right offsets, since two independent fields agreeing with the notes is not
        // something a shifted reassembly can fake. These are raw bytes, not a decode.
        assertEquals(listOf(0x13, 0x13, 0x12), TcuRecordCaptures.ALL_RECORDS.map { recordFrom(it.second).byteAt(1) })
        assertEquals(listOf(0x18, 0x10, 0x00), TcuRecordCaptures.ALL_RECORDS.map { recordFrom(it.second).byteAt(19) })
    }

    @Test
    fun `the definition's parse refuses because the byte-1 decode was falsified`() {
        // OBD-59: no field is identified, so the definition scales nothing — a poll of it skips on a
        // ScalingError rather than publishing a number. This is what keeps the gate honest even if a
        // caller reached past PidCatalog straight to the spec.
        val record = recordFrom(TcuRecordCaptures.WARM_IDLE)

        try {
            spec.definition.parse(ByteArray(record.bytes.size) { record.bytes[it].toByte() })
            throw AssertionError("expected the falsified decode to refuse")
        } catch (expected: UnsupportedOperationException) {
            assertTrue(expected.message.orEmpty().contains("falsified"))
        }
    }

    @Test
    fun `the trailing padding byte is discarded, not counted as record data`() {
        // Four frames carry 6 + 7 + 7 + 7 = 27 bytes; the first frame declares 26. The surplus
        // FF is CAN padding. Counting it would leave a 25-byte record and shift nothing today —
        // which is exactly why it needs pinning before some later field lives at byte 24.
        assertEquals(TcuRecordRegistry.RECORD_DATA_BYTES, recordFrom(TcuRecordCaptures.WARM_IDLE).bytes.size)
        assertEquals(0x00, recordFrom(TcuRecordCaptures.WARM_IDLE).byteAt(23))
    }

    // ---- framing tolerance ----

    @Test
    fun `the same record parses with LF or CRLF terminators and a trailing prompt`() {
        val lineFeeds = TcuRecordCaptures.WARM_IDLE.replace("\r", "\n")
        val both = TcuRecordCaptures.WARM_IDLE.replace("\r", "\r\n") + "\r\n>"

        assertEquals(warmIdleBytes, recordFrom(lineFeeds).bytes)
        assertEquals(warmIdleBytes, recordFrom(both).bytes)
    }

    @Test
    fun `the same record parses with spaces off, which is how the app configures the dongle`() {
        // ATS0 is in the init sequence, so a live session sees the unspaced form; the capture
        // was taken from the console with spaces on. Both must reassemble identically.
        val spacesOff = TcuRecordCaptures.WARM_IDLE.replace(" ", "")

        assertEquals(recordFrom(TcuRecordCaptures.WARM_IDLE).bytes, recordFrom(spacesOff).bytes)
    }

    @Test
    fun `a command echo ahead of the record does not shift it`() {
        val echoed = "2130\r" + TcuRecordCaptures.WARM_IDLE

        assertEquals(warmIdleBytes, recordFrom(echoed).bytes)
    }

    @Test
    fun `SEARCHING noise ahead of the record is ignored`() {
        assertEquals(warmIdleBytes, recordFrom("SEARCHING...\r" + TcuRecordCaptures.WARM_IDLE).bytes)
    }

    @Test
    fun `another ECU's frames are filtered out instead of reassembled into the record`() {
        // Every functional request on this van draws three answers; a physically addressed one
        // should not, but the receive filter is a noise filter, not a correctness guarantee.
        // Interleaving 7E8 frames must change nothing.
        val interleaved =
            "7E8 03 41 05 86 00 00 00\r" +
                TcuRecordCaptures.WARM_IDLE +
                "7EC 03 41 05 86 00 00 00\r"

        assertEquals(warmIdleBytes, recordFrom(interleaved).bytes)
    }

    @Test
    fun `the headers-off framing an ATH0 session produces reassembles to the same record`() {
        // What the app's own sessions see: ELM does the ISO-TP work, drops the id and the PCI
        // bytes, and prints an indexed long response. Same 24 bytes, different framing — and the
        // whole reason one parser serves both.
        val headersOff =
            "01A\r" +
                "0: 61 30 00 13 00 00\r" +
                "1: 00 00 00 08 04 00\r" +
                "2: DD 8E FF F3 FF F3\r" +
                "3: 00 00 86 18 00 08\r" +
                "4: 00 00\r"

        assertEquals(recordFrom(TcuRecordCaptures.WARM_IDLE).bytes, recordFrom(headersOff).bytes)
    }

    // ---- refusals ----

    @Test
    fun `the captured 7F 22 11 is reported as a negative response naming service 22`() {
        // The falsified UDS hypothesis. The service byte is reported AS RECEIVED: rewriting it
        // to the 0x21 we asked for would erase the one fact the frame carries.
        val failure = KwpRecordParser.parse(spec, TcuRecordCaptures.UDS_22_NEGATIVE).failureOrNull()

        assertEquals(ParseFailure.NegativeResponse(requestMode = 0x22, code = 0x11), failure)
    }

    @Test
    fun `a 7F 21 rejection of this very request is typed the same way`() {
        assertEquals(
            ParseFailure.NegativeResponse(requestMode = 0x21, code = 0x11),
            KwpRecordParser.parse(spec, "7F 21 11\r").failureOrNull(),
        )
        assertEquals(
            ParseFailure.NegativeResponse(requestMode = 0x21, code = 0x78),
            KwpRecordParser.parse(spec, "7E9 03 7F 21 78\r").failureOrNull(),
        )
    }

    @Test
    fun `a negative response never yields a value`() {
        assertNull(KwpRecordParser.parse(spec, TcuRecordCaptures.UDS_22_NEGATIVE).valueOrNull())
    }

    @Test
    fun `a dropped consecutive frame is refused rather than reassembled short`() {
        // Frame 22 missing. Concatenating what arrived would put byte 25 where byte 18 belongs
        // and produce a completely plausible temperature.
        val missingFrame =
            TcuRecordCaptures.WARM_IDLE
                .lines()
                .filterNot { it.startsWith("7E9 22") }
                .joinToString("\r")

        val failure = KwpRecordParser.parse(spec, missingFrame).failureOrNull()

        assertEquals(ParseFailure.MultiFrameSequenceError(expected = 2, actual = 3), failure)
    }

    @Test
    fun `out-of-order consecutive frames are refused`() {
        val swapped =
            "7E9 10 1A 61 30 00 13 00 00\r" +
                "7E9 22 8E FF F3 FF F3 00 00\r" +
                "7E9 21 00 00 00 08 04 00 DD\r" +
                "7E9 23 86 18 00 08 00 00 FF\r"

        assertEquals(
            ParseFailure.MultiFrameSequenceError(expected = 1, actual = 2),
            KwpRecordParser.parse(spec, swapped).failureOrNull(),
        )
    }

    @Test
    fun `a sequence that starts mid-record is refused rather than read from the middle`() {
        val noFirstFrame =
            "7E9 21 00 00 00 08 04 00 DD\r" +
                "7E9 22 8E FF F3 FF F3 00 00\r"

        assertEquals(
            ParseFailure.MultiFrameSequenceError(expected = 1, actual = 2),
            KwpRecordParser.parse(spec, noFirstFrame).failureOrNull(),
        )
    }

    @Test
    fun `an over-long consecutive frame is refused rather than shifting every byte after it`() {
        // Round-1 review MINOR-1. A CAN CF carries at most 7 data bytes after its PCI byte; an
        // 8-byte one (a corrupted or merged line) would append one byte too many and push byte
        // 18 onto a neighbouring field — a shift that produces a completely plausible
        // temperature. The frame boundary is only knowable here, so it is refused here.
        val overLong =
            TcuRecordCaptures.WARM_IDLE.replace(
                "7E9 21 00 00 00 08 04 00 DD",
                "7E9 21 00 00 00 08 04 00 DD 00",
            )

        assertEquals(
            ParseFailure.UnexpectedDataLength(expected = 8, actual = 9),
            KwpRecordParser.parse(spec, overLong).failureOrNull(),
        )
    }

    @Test
    fun `a record truncated before its declared length is refused, not zero-filled`() {
        val truncated =
            "7E9 10 1A 61 30 00 13 00 00\r" +
                "7E9 21 00 00 00 08 04 00 DD\r" +
                "7E9 22 8E FF F3 FF F3 00 00\r"

        assertEquals(
            ParseFailure.UnexpectedDataLength(expected = 26, actual = 20),
            KwpRecordParser.parse(spec, truncated).failureOrNull(),
        )
    }

    @Test
    fun `an answer to a different local identifier is not this record`() {
        val otherId = TcuRecordCaptures.WARM_IDLE.replace("61 30", "61 31")

        val failure = KwpRecordParser.parse(spec, otherId).failureOrNull()

        assertTrue("expected NoMatchingFrame, was $failure", failure is ParseFailure.NoMatchingFrame)
        assertEquals("6130", (failure as ParseFailure.NoMatchingFrame).expectedHeader)
    }

    @Test
    fun `the ELM status lines mean no value, with the same vocabulary as the standard parser`() {
        assertEquals(ParseFailure.NoData, KwpRecordParser.parse(spec, "NO DATA").failureOrNull())
        assertEquals(ParseFailure.Stopped, KwpRecordParser.parse(spec, "STOPPED").failureOrNull())
        assertEquals(ParseFailure.UnknownCommand, KwpRecordParser.parse(spec, "?").failureOrNull())
        assertEquals(ParseFailure.UnableToConnect, KwpRecordParser.parse(spec, "UNABLE TO CONNECT").failureOrNull())
        assertEquals(ParseFailure.Empty, KwpRecordParser.parse(spec, "  \r\n > \r").failureOrNull())
    }

    @Test
    fun `garbage never produces a record`() {
        for (raw in listOf("G@RB13D//FRAME", "6130", "", "0:", "7E9", "ZZZZ ZZZZ")) {
            assertNull("\"$raw\" must not parse", KwpRecordParser.parse(spec, raw).valueOrNull())
        }
    }

    @Test
    fun `a byte index outside the record is refused rather than answered`() {
        val record = recordFrom(TcuRecordCaptures.WARM_IDLE)

        try {
            record.byteAt(TcuRecordRegistry.RECORD_DATA_BYTES)
            throw AssertionError("expected an out-of-bounds refusal")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("out of bounds"))
        }
    }

    private fun recordFrom(raw: String): KwpRecord {
        val outcome = KwpRecordParser.parse(spec, raw)

        return outcome.valueOrNull() ?: throw AssertionError("expected a record, got ${outcome.failureOrNull()}")
    }

    /** The 24 reassembled bytes of the warm-idle capture; the reassembly-invariance reference. */
    private val warmIdleBytes: List<Int> get() = recordFrom(TcuRecordCaptures.WARM_IDLE).bytes
}
