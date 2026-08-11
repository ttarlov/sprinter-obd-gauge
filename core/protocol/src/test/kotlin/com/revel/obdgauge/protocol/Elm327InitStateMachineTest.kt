package com.revel.obdgauge.protocol

import com.revel.obdgauge.testing.link.FakeObdLink
import com.revel.obdgauge.testing.link.Fault
import com.revel.obdgauge.testing.link.TranscriptEntry
import com.revel.obdgauge.testing.link.TranscriptParser
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Init-sequence behaviour against `FakeObdLink`: the happy path over the shipped transcript, and
 * every fault the fake can inject, at every step.
 *
 * The recurring assertion is that a failure is a *typed value* — nothing here is allowed to
 * throw, and nothing is allowed to report success on a link the vehicle never answered.
 */
class Elm327InitStateMachineTest {
    private val baseTranscript = TranscriptParser.parseResource(TRANSCRIPT_RESOURCE)

    // --- happy path ---------------------------------------------------------------------------

    @Test
    fun `runs the full sequence in order and reports success`() =
        runTest {
            val link = RecordingObdLink(FakeObdLink(baseTranscript))
            val result = Elm327InitStateMachine(link).run()

            assertEquals(FULL_SEQUENCE, link.commands)
            val success = result as InitResult.Success
            assertEquals("ELM327 v2.1", success.banner)
            assertEquals(listOf(0xBE, 0x3E, 0xB8, 0x11), success.supportedPidBitmap)
        }

    @Test
    fun `tolerates the ATE0 echo the dongle emits before echo actually goes off`() =
        runTest {
            val link = FakeObdLink(baseTranscript)
            assertTrue(Elm327InitStateMachine(link).run() is InitResult.Success)
        }

    @Test
    fun `tolerates a leading SEARCHING line on the 0100 verify`() =
        runTest {
            val link = FakeObdLink(transcriptWith("0100" to "SEARCHING...\r41 00 BE 3E B8 11"))
            val result = Elm327InitStateMachine(link).run() as InitResult.Success
            assertEquals(listOf(0xBE, 0x3E, 0xB8, 0x11), result.supportedPidBitmap)
        }

    @Test
    fun `accepts a clone banner as long as it identifies itself`() =
        runTest {
            for (banner in listOf("ELM327 v1.5", "OBDII by Veepeak", "elm327 v2.2")) {
                val link = FakeObdLink(transcriptWith("ATZ" to "ATZ\r$banner"))
                val result = Elm327InitStateMachine(link).run() as InitResult.Success
                assertEquals(banner, result.banner)
            }
        }

    @Test
    fun `decodes the 0100 supported-PID bitmap`() =
        runTest {
            val result = Elm327InitStateMachine(FakeObdLink(baseTranscript)).run() as InitResult.Success

            // BE = 1011 1110 -> PIDs 01, 03..07 supported, PID 02 not.
            assertTrue(result.supports(0x01))
            assertFalse(result.supports(0x02))
            assertTrue(result.supports(0x05))
            // 3E = 0011 1110 -> PIDs 0B..0F supported, 09/0A/10 not.
            assertTrue(result.supports(0x0B))
            assertTrue(result.supports(0x0C))
            assertTrue(result.supports(0x0D))
            assertTrue(result.supports(0x0F))
            assertFalse(result.supports(0x0A))
            // Outside the 0x01..0x20 bitmap this request covers.
            assertFalse(result.supports(0x33))
            assertFalse(result.supports(0x00))
        }

    // --- ATZ retry policy ---------------------------------------------------------------------

    @Test
    fun `retries ATZ exactly once when the first command times out`() =
        runTest {
            val link = RecordingObdLink(FakeObdLink(baseTranscript, positionFaults = mapOf(1 to Fault.Timeout)))
            val result = Elm327InitStateMachine(link).run()

            assertTrue(result is InitResult.Success)
            assertEquals(listOf("ATZ") + FULL_SEQUENCE, link.commands)
        }

    @Test
    fun `gives up after the second ATZ timeout instead of looping`() =
        runTest {
            val faults = mapOf(1 to Fault.Timeout, 2 to Fault.Timeout)
            val link = RecordingObdLink(FakeObdLink(baseTranscript, positionFaults = faults))
            val result = Elm327InitStateMachine(link).run()

            assertEquals(InitFailure.NoResponse(InitStep.RESET), failureOf(result))
            assertEquals(listOf("ATZ", "ATZ"), link.commands)
        }

    @Test
    fun `does not retry a step other than ATZ`() =
        runTest {
            val link = RecordingObdLink(FakeObdLink(baseTranscript, positionFaults = mapOf(4 to Fault.Timeout)))
            val result = Elm327InitStateMachine(link).run()

            assertEquals(InitFailure.NoResponse(InitStep.SPACES_OFF), failureOf(result))
            assertEquals(listOf("ATZ", "ATE0", "ATL0", "ATS0"), link.commands)
        }

    // --- rejected responses -------------------------------------------------------------------

    @Test
    fun `garbage from ATZ is a typed rejection carrying the raw text`() =
        runTest {
            val link = RecordingObdLink(FakeObdLink(baseTranscript, positionFaults = mapOf(1 to Fault.Garbage)))
            val failure = failureOf(Elm327InitStateMachine(link).run())

            assertTrue("got $failure", failure is InitFailure.Rejected)
            assertEquals(InitStep.RESET, (failure as InitFailure.Rejected).step)
            assertEquals("G@RB13D//FRAME??", failure.raw)
            // The retry policy is timeout-only: a dongle that ANSWERS wrongly is never retried
            // (review round-1 NIT — previously only incidentally covered).
            assertEquals(listOf("ATZ"), link.commands)
        }

    @Test
    fun `a banner that does not identify an OBD dongle is rejected`() =
        runTest {
            val link = FakeObdLink(transcriptWith("ATZ" to "ATZ\rREADY"))
            val failure = failureOf(Elm327InitStateMachine(link).run())
            assertEquals(InitFailure.Rejected(InitStep.RESET, "ATZ\rREADY"), failure)
        }

    @Test
    fun `an ATZ error line is rejected rather than taken as a banner`() =
        runTest {
            val link = FakeObdLink(transcriptWith("ATZ" to "ELM327 ERROR"))
            val failure = failureOf(Elm327InitStateMachine(link).run())
            assertEquals(InitFailure.Rejected(InitStep.RESET, "ELM327 ERROR"), failure)
        }

    @Test
    fun `an unknown-command reply to a config step is rejected at that step`() =
        runTest {
            val link = RecordingObdLink(FakeObdLink(transcriptWithout("ATE0")))
            val failure = failureOf(Elm327InitStateMachine(link).run())

            assertEquals(InitFailure.Rejected(InitStep.ECHO_OFF, "?"), failure)
            assertEquals(listOf("ATZ", "ATE0"), link.commands)
        }

    @Test
    fun `garbage from a config step is rejected at that step`() =
        runTest {
            val link = FakeObdLink(baseTranscript, positionFaults = mapOf(3 to Fault.Garbage))
            val failure = failureOf(Elm327InitStateMachine(link).run())
            assertEquals(InitFailure.Rejected(InitStep.LINEFEEDS_OFF, "G@RB13D//FRAME??"), failure)
        }

    @Test
    fun `STOPPED from a config step is rejected, since it never acknowledged`() =
        runTest {
            val link = FakeObdLink(baseTranscript, positionFaults = mapOf(5 to Fault.Stopped))
            val failure = failureOf(Elm327InitStateMachine(link).run())
            assertEquals(InitFailure.Rejected(InitStep.PROTOCOL_AUTO, "STOPPED"), failure)
        }

    @Test
    fun `an OK buried in an error response does not pass a config step`() =
        runTest {
            val link = FakeObdLink(transcriptWith("ATL0" to "OK\rBUS INIT: ERROR"))
            val failure = failureOf(Elm327InitStateMachine(link).run())
            assertEquals(InitFailure.Rejected(InitStep.LINEFEEDS_OFF, "OK\rBUS INIT: ERROR"), failure)
        }

    // --- protocol verification ----------------------------------------------------------------

    @Test
    fun `NO DATA on the 0100 verify means no protocol, not a dongle fault`() =
        runTest {
            val link = FakeObdLink(baseTranscript, positionFaults = mapOf(6 to Fault.NoData))
            assertEquals(InitFailure.NoProtocol("NO DATA"), failureOf(Elm327InitStateMachine(link).run()))
        }

    @Test
    fun `UNABLE TO CONNECT on the 0100 verify means no protocol`() =
        runTest {
            val link = FakeObdLink(transcriptWith("0100" to "SEARCHING...\rUNABLE TO CONNECT"))
            val failure = failureOf(Elm327InitStateMachine(link).run())
            assertEquals(InitFailure.NoProtocol("SEARCHING...\rUNABLE TO CONNECT"), failure)
        }

    @Test
    fun `a bus error on the 0100 verify means no protocol`() =
        runTest {
            val link = FakeObdLink(transcriptWith("0100" to "CAN ERROR"))
            assertEquals(InitFailure.NoProtocol("CAN ERROR"), failureOf(Elm327InitStateMachine(link).run()))
        }

    @Test
    fun `STOPPED on the 0100 verify is a dongle-side rejection, not a missing protocol`() =
        runTest {
            val link = FakeObdLink(baseTranscript, positionFaults = mapOf(6 to Fault.Stopped))
            assertEquals(
                InitFailure.Rejected(InitStep.VERIFY, "STOPPED"),
                failureOf(Elm327InitStateMachine(link).run()),
            )
        }

    @Test
    fun `garbage on the 0100 verify is rejected`() =
        runTest {
            val link = FakeObdLink(baseTranscript, positionFaults = mapOf(6 to Fault.Garbage))
            val failure = failureOf(Elm327InitStateMachine(link).run())
            assertEquals(InitFailure.Rejected(InitStep.VERIFY, "G@RB13D//FRAME??"), failure)
        }

    @Test
    fun `a truncated 0100 bitmap is rejected rather than padded out`() =
        runTest {
            val link = FakeObdLink(transcriptWith("0100" to "41 00 BE 3E"))
            assertEquals(
                InitFailure.Rejected(InitStep.VERIFY, "41 00 BE 3E"),
                failureOf(Elm327InitStateMachine(link).run()),
            )
        }

    @Test
    fun `a 0100 timeout is a no-response failure at the verify step`() =
        runTest {
            val link = FakeObdLink(baseTranscript, positionFaults = mapOf(6 to Fault.Timeout))
            assertEquals(InitFailure.NoResponse(InitStep.VERIFY), failureOf(Elm327InitStateMachine(link).run()))
        }

    // --- link loss ----------------------------------------------------------------------------

    @Test
    fun `a link drop on the first command is a typed LinkDown, not a thrown exception`() =
        runTest {
            val link = FakeObdLink(baseTranscript, positionFaults = mapOf(1 to Fault.MidResponseDisconnect))
            val failure = failureOf(Elm327InitStateMachine(link).run())

            assertTrue("got $failure", failure is InitFailure.LinkDown)
            assertEquals(InitStep.RESET, (failure as InitFailure.LinkDown).step)
            assertTrue(failure.message.isNotEmpty())
        }

    @Test
    fun `a link drop mid-sequence names the step that was in flight`() =
        runTest {
            val link = FakeObdLink(baseTranscript, positionFaults = mapOf(3 to Fault.MidResponseDisconnect))
            val failure = failureOf(Elm327InitStateMachine(link).run())
            assertEquals(InitStep.LINEFEEDS_OFF, (failure as InitFailure.LinkDown).step)
        }

    @Test
    fun `a link drop on the verify step names the verify step`() =
        runTest {
            val link = FakeObdLink(baseTranscript, positionFaults = mapOf(6 to Fault.MidResponseDisconnect))
            val failure = failureOf(Elm327InitStateMachine(link).run())
            assertEquals(InitStep.VERIFY, (failure as InitFailure.LinkDown).step)
        }

    // --- cancellation and restart -------------------------------------------------------------

    @Test
    fun `cancellation propagates instead of being reported as a failure`() =
        runTest {
            val link = RecordingObdLink(FakeObdLink(baseTranscript))
            link.stallCommand = "ATL0"
            var result: InitResult? = null

            val job = launch { result = Elm327InitStateMachine(link).run() }
            runCurrent()
            assertEquals(listOf("ATZ", "ATE0", "ATL0"), link.commands)

            job.cancelAndJoin()
            assertNull("run() swallowed cancellation and returned $result", result)
        }

    @Test
    fun `restarts from a clean state after a cancelled attempt`() =
        runTest {
            val link = RecordingObdLink(FakeObdLink(baseTranscript))
            val machine = Elm327InitStateMachine(link)
            link.stallCommand = "ATL0"

            val job = launch { machine.run() }
            runCurrent()
            job.cancelAndJoin()

            link.stallCommand = null
            assertTrue(machine.run() is InitResult.Success)
            assertEquals(listOf("ATZ", "ATE0", "ATL0") + FULL_SEQUENCE, link.commands)
        }

    @Test
    fun `restarts from a clean state after a failed attempt`() =
        runTest {
            val faults = mapOf(1 to Fault.Timeout, 2 to Fault.Timeout)
            val link = RecordingObdLink(FakeObdLink(baseTranscript, positionFaults = faults))
            val machine = Elm327InitStateMachine(link)

            assertEquals(InitFailure.NoResponse(InitStep.RESET), failureOf(machine.run()))
            assertTrue(machine.run() is InitResult.Success)
            assertEquals(listOf("ATZ", "ATZ") + FULL_SEQUENCE, link.commands)
        }

    // --- fixtures -----------------------------------------------------------------------------

    private fun failureOf(result: InitResult): InitFailure = (result as InitResult.Failure).failure

    private fun transcriptWith(vararg overrides: Pair<String, String>): List<TranscriptEntry> =
        baseTranscript.map { entry ->
            val override = overrides.firstOrNull { it.first.equals(entry.command, ignoreCase = true) }
            if (override == null) entry else TranscriptEntry(entry.command, override.second)
        }

    private fun transcriptWithout(command: String): List<TranscriptEntry> =
        baseTranscript.filterNot { it.command.equals(command, ignoreCase = true) }

    private companion object {
        const val TRANSCRIPT_RESOURCE = "transcripts/elm327-init-and-pids.txt"
        val FULL_SEQUENCE = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATSP0", "0100")
    }
}
