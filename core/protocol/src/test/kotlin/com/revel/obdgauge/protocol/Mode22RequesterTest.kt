package com.revel.obdgauge.protocol

import com.revel.obdgauge.testing.link.FakeObdLink
import com.revel.obdgauge.testing.link.Fault
import com.revel.obdgauge.testing.link.TranscriptParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The full mode-22 exchange over the branch-local synthetic transcript
 * (`transcripts/mode22-trans-temp.txt`).
 *
 * That fixture is a *prediction*, not a capture — see its header comment. What these tests can
 * therefore prove is that this module frames the request, restores the dongle, and extracts the
 * byte exactly as its decode says it should. Whether the van's TCM answers `61 30 91` at all is
 * OBD-22's question.
 */
class Mode22RequesterTest {
    private val transcript = TranscriptParser.parseResource("transcripts/mode22-trans-temp.txt")
    private val spec = MercedesPidRegistry.transTemp

    @Test
    fun `the happy path sends header, filter, request, then restores both`() =
        runTest {
            val link = RecordingObdLink(FakeObdLink(transcript))
            val requester = Mode22Requester(link)

            val outcome = requester.request(spec)

            assertEquals(PollOutcome.Value(TRANS_TEMP_CELSIUS), outcome)
            assertEquals(listOf("ATSH7E1", "ATCRA7E9", "2130", "ATCRA", "ATSH7DF"), link.commands)
            assertFalse("a clean restore leaves nothing pending", requester.restorePending)
        }

    @Test
    fun `restoration also runs when the vehicle refuses the request`() =
        runTest {
            val link = RecordingObdLink(FakeObdLink(transcript, commandFaults = mapOf("2130" to listOf(Fault.NoData))))
            val requester = Mode22Requester(link)

            val outcome = requester.request(spec)

            assertEquals(PollOutcome.Skipped(ParseFailure.NoData), outcome)
            assertEquals(listOf("ATSH7E1", "ATCRA7E9", "2130", "ATCRA", "ATSH7DF"), link.commands)
            assertFalse(requester.restorePending)
        }

    @Test
    fun `a garbage reply is skipped and the dongle is still restored`() =
        runTest {
            val faults = mapOf("2130" to listOf(Fault.Garbage))
            val link = RecordingObdLink(FakeObdLink(transcript, commandFaults = faults))
            val requester = Mode22Requester(link)

            assertTrue(requester.request(spec) is PollOutcome.Skipped)
            assertEquals(listOf("ATSH7E1", "ATCRA7E9", "2130", "ATCRA", "ATSH7DF"), link.commands)
        }

    @Test
    fun `a dongle that rejects ATSH never sends the request`() =
        runTest {
            // A command absent from the transcript gets ELM327's own "?" - exactly what a clone
            // without header support would answer.
            val link = RecordingObdLink(FakeObdLink(transcript.filterNot { it.command == "ATSH7E1" }))
            val requester = Mode22Requester(link)

            val outcome = requester.request(spec)

            assertTrue(outcome is PollOutcome.HeaderRejected)
            outcome as PollOutcome.HeaderRejected
            assertEquals("ATSH7E1", outcome.command)
            assertFalse("the request must not go out on an unset header", "2130" in link.commands)
        }

    @Test
    fun `a dongle that rejects ATCRA never sends the request either`() =
        runTest {
            val link = RecordingObdLink(FakeObdLink(transcript.filterNot { it.command == "ATCRA7E9" }))

            val outcome = Mode22Requester(link).request(spec)

            assertTrue(outcome is PollOutcome.HeaderRejected)
            assertFalse("2130" in link.commands)
        }

    @Test
    fun `a link drop mid-request skips the doomed restore and remembers it is pending`() =
        runTest {
            val faults = mapOf("2130" to listOf(Fault.MidResponseDisconnect))
            val link = RecordingObdLink(FakeObdLink(transcript, commandFaults = faults))
            val requester = Mode22Requester(link)

            val outcome = requester.request(spec)

            assertTrue(outcome is PollOutcome.LinkDown)
            assertEquals(listOf("ATSH7E1", "ATCRA7E9", "2130"), link.commands)
            assertTrue("the dongle may still hold ATSH 7E1", requester.restorePending)
        }

    @Test
    fun `a failed restore is retried before the next request, not silently forgotten`() =
        runTest {
            // ATSH7DF answers once, then the dongle stops acknowledging it... then recovers.
            val faults = mapOf("ATSH7DF" to listOf(Fault.Timeout))
            val link = RecordingObdLink(FakeObdLink(transcript, commandFaults = faults))
            val requester = Mode22Requester(link)

            requester.request(spec)
            assertTrue("a restore the dongle never acknowledged stays pending", requester.restorePending)

            assertTrue(requester.restoreHeaders())
            assertFalse(requester.restorePending)
            assertEquals(listOf("ATCRA", "ATSH7DF"), link.commands.takeLast(2))
        }

    @Test
    fun `restoreHeaders is a no-op when nothing was ever set`() =
        runTest {
            val link = RecordingObdLink(FakeObdLink(transcript))
            val requester = Mode22Requester(link)

            assertTrue(requester.restoreHeaders())
            assertEquals(emptyList<String>(), link.commands)
        }

    @Test
    fun `disabling the receive filter drops ATCRA from both halves of the sequence`() =
        runTest {
            val link = RecordingObdLink(FakeObdLink(transcript))
            val requester = Mode22Requester(link, Mode22Config(rxFilterEnabled = false))

            val outcome = requester.request(spec)

            // The payload-level 6130 match still guards the value, so the reading is unaffected.
            assertEquals(PollOutcome.Value(TRANS_TEMP_CELSIUS), outcome)
            assertEquals(listOf("ATSH7E1", "2130", "ATSH7DF"), link.commands)
        }

    @Test
    fun `a request timeout is reported as a link failure, not as a value`() =
        runTest {
            val faults = mapOf("2130" to listOf(Fault.Timeout))
            val link = RecordingObdLink(FakeObdLink(transcript, commandFaults = faults))

            val outcome = Mode22Requester(link).request(spec)

            assertTrue(outcome is PollOutcome.LinkDown)
            assertTrue((outcome as PollOutcome.LinkDown).message.contains("timeout"))
        }

    private companion object {
        /** Raw 0x91 = 145 -> 145 − 50 = 95 °C (203 °F on a ScanGauge). */
        const val TRANS_TEMP_CELSIUS = 95.0
    }
}
