package com.revel.obdgauge.protocol

import com.revel.obdgauge.testing.link.FakeObdLink
import com.revel.obdgauge.testing.link.Fault
import com.revel.obdgauge.testing.link.TranscriptEntry
import com.revel.obdgauge.testing.link.TranscriptParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `21 30` exchange end to end: framing, restore, and extraction — with the ECU's half of the
 * conversation being the **real captured bytes** rather than a prediction (OBD-49).
 *
 * The AT commands come from the branch's synthetic transcript (a dongle answering `OK` is not a
 * hypothesis); only the `2130` answer is swapped for a capture. That is the whole point: the
 * request framing was already proven by OBD-15's tests, and what was missing was the reply this
 * module would actually meet.
 */
class KwpRecordRequesterTest {
    private val baseTranscript = TranscriptParser.parseResource("transcripts/mode22-trans-temp.txt")
    private val spec = TcuRecordRegistry.transTempRecord

    @Test
    fun `the happy path sends header, filter, request, then restores both`() =
        runTest {
            val link = RecordingObdLink(FakeObdLink(answering(TcuRecordCaptures.WARM_IDLE)))
            val requester = KwpRecordRequester(link)

            val outcome = requester.request(spec)

            assertTrue("expected a record, got $outcome", outcome is RecordOutcome.Received)
            assertEquals(
                // 2026-08-12 warm idle: byte 1 = 0x13 → 63 − 19 = 44 °C (OBD-55 inverse law).
                44.0,
                TcuRecordRegistry.transTempCelsius((outcome as RecordOutcome.Received).record),
                0.0,
            )
            assertEquals(listOf("ATSH7E1", "ATCRA7E9", "2130", "ATCRA", "ATSH7DF"), link.commands)
            assertFalse("a clean restore leaves nothing pending", requester.restorePending)
        }

    @Test
    fun `poll scales the record through the definition and answers as an ordinary PollOutcome`() =
        runTest {
            // Session-3 post-drive: byte 1 = 0x12 → 45 °C under 63 − raw.
            val link = FakeObdLink(answering(TcuRecordCaptures.S3_POST_DRIVE))

            assertEquals(PollOutcome.Value(45.0), KwpRecordRequester(link).poll(spec))
        }

    @Test
    fun `the session-3 anchors each survive a full framed exchange`() =
        runTest {
            // 63 − raw at byte 1: warm idle 0x23 → 28, post-drive and heat-soak 0x12 → 45.
            val anchors =
                listOf(
                    "warm idle" to (TcuRecordCaptures.S3_WARM_IDLE to 28.0),
                    "post-drive" to (TcuRecordCaptures.S3_POST_DRIVE to 45.0),
                    "heat-soak" to (TcuRecordCaptures.S3_HEAT_SOAK to 45.0),
                )
            for ((name, cap012) in anchors) {
                val (capture, expected) = cap012
                val link = FakeObdLink(answering(capture))

                assertEquals(name, PollOutcome.Value(expected), KwpRecordRequester(link).poll(spec))
            }
        }

    @Test
    fun `the captured UDS negative is skipped with its service byte intact, and restores anyway`() =
        runTest {
            // What `220543` drew from this TCU. Sent against `2130` it is still a negative
            // response and still must not leave the dongle addressed to 7E1.
            val link = RecordingObdLink(FakeObdLink(answering(TcuRecordCaptures.UDS_22_NEGATIVE)))
            val requester = KwpRecordRequester(link)

            val outcome = requester.request(spec)

            assertEquals(
                RecordOutcome.Skipped(ParseFailure.NegativeResponse(requestMode = 0x22, code = 0x11)),
                outcome,
            )
            assertEquals(listOf("ATSH7E1", "ATCRA7E9", "2130", "ATCRA", "ATSH7DF"), link.commands)
            assertFalse(requester.restorePending)
        }

    @Test
    fun `a dongle that rejects ATSH never sends the request`() =
        runTest {
            val link =
                RecordingObdLink(
                    FakeObdLink(answering(TcuRecordCaptures.WARM_IDLE).filterNot { it.command == "ATSH7E1" }),
                )

            val requester = KwpRecordRequester(link)
            val outcome = requester.request(spec)

            assertTrue(outcome is RecordOutcome.HeaderRejected)
            assertEquals("ATSH7E1", (outcome as RecordOutcome.HeaderRejected).command)
            assertFalse("the request must not go out on an unset header", "2130" in link.commands)

            // Round-1 review NIT-1: a rejected ATSH is NOT a link failure, so the restore still
            // has to run. ATSH7E1 may have been accepted-then-rejected by a clone, or a later
            // framing command may have taken; leaving without restoring would address every
            // subsequent 0105 to the TCU and silently kill the standard gauges for the session.
            assertEquals(listOf("ATCRA", "ATSH7DF"), link.commands.takeLast(2))
            assertFalse("a completed restore leaves nothing pending", requester.restorePending)
        }

    @Test
    fun `a link drop mid-request skips the doomed restore and remembers it is pending`() =
        runTest {
            val faults = mapOf("2130" to listOf(Fault.MidResponseDisconnect))
            val link = RecordingObdLink(FakeObdLink(answering(TcuRecordCaptures.WARM_IDLE), commandFaults = faults))
            val requester = KwpRecordRequester(link)

            val outcome = requester.request(spec)

            assertTrue(outcome is RecordOutcome.LinkDown)
            assertEquals(listOf("ATSH7E1", "ATCRA7E9", "2130"), link.commands)
            assertTrue("the dongle may still hold ATSH 7E1", requester.restorePending)
        }

    @Test
    fun `a failed restore is retried before the next request, not silently forgotten`() =
        runTest {
            val faults = mapOf("ATSH7DF" to listOf(Fault.Timeout))
            val link = RecordingObdLink(FakeObdLink(answering(TcuRecordCaptures.WARM_IDLE), commandFaults = faults))
            val requester = KwpRecordRequester(link)

            requester.request(spec)
            assertTrue("a restore the dongle never acknowledged stays pending", requester.restorePending)

            assertTrue(requester.restoreHeaders())
            assertFalse(requester.restorePending)
            assertEquals(listOf("ATCRA", "ATSH7DF"), link.commands.takeLast(2))
        }

    @Test
    fun `restoreHeaders is a no-op when nothing was ever set`() =
        runTest {
            val link = RecordingObdLink(FakeObdLink(answering(TcuRecordCaptures.WARM_IDLE)))

            assertTrue(KwpRecordRequester(link).restoreHeaders())
            assertEquals(emptyList<String>(), link.commands)
        }

    @Test
    fun `disabling the receive filter drops ATCRA and the record still parses`() =
        runTest {
            // Without ATCRA the reassembler is what keeps other ECUs out, by CAN id. The value
            // must be unaffected — the filter is a noise reduction, never a correctness guard.
            val link = RecordingObdLink(FakeObdLink(answering(TcuRecordCaptures.WARM_IDLE)))
            val requester = KwpRecordRequester(link, Mode22Config(rxFilterEnabled = false))

            assertEquals(PollOutcome.Value(44.0), requester.poll(spec))
            assertEquals(listOf("ATSH7E1", "2130", "ATSH7DF"), link.commands)
        }

    @Test
    fun `a request timeout is reported as a link failure, not as a value`() =
        runTest {
            val faults = mapOf("2130" to listOf(Fault.Timeout))
            val link = FakeObdLink(answering(TcuRecordCaptures.WARM_IDLE), commandFaults = faults)

            val outcome = KwpRecordRequester(link).request(spec)

            assertTrue(outcome is RecordOutcome.LinkDown)
            assertTrue((outcome as RecordOutcome.LinkDown).message.contains("timeout"))
        }

    @Test
    fun `the request that goes on the wire is the one the session confirmed`() {
        assertEquals("2130", spec.requestBytes)
        assertEquals("6130", spec.responseHeader)
        assertEquals("7E1", spec.canId)
        assertEquals("7E9", spec.rxFilter)
        assertEquals(0x21, spec.requestMode)
        assertEquals(26, spec.serviceByteCount)
    }

    @Test
    fun `the channel ships unverified because the slope is provisional`() {
        // The one assertion in this file about restraint rather than capability. OBD-55 identified
        // byte 1 and pinned the offset (cold soak = ambient), but the 1 °C/count slope is assumed,
        // not measured — the drive only reached ~45 °C — so the channel ships unverified until a
        // hot cross-checked sample lands (OBD-51).
        assertFalse("the slope above ~60 °C is untested", spec.definition.verified)
        assertEquals(TcuRecordRegistry.TRANS_TEMP_BYTE, spec.dataByteIndex)
    }

    /** The branch transcript with its predicted `2130` answer replaced by [capture]. */
    private fun answering(capture: String): List<TranscriptEntry> =
        baseTranscript.filterNot { it.command == "2130" } + TranscriptEntry("2130", capture)
}
