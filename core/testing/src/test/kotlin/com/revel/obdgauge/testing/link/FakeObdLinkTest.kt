package com.revel.obdgauge.testing.link

import com.revel.obdgauge.model.LinkState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class FakeObdLinkTest {
    private val transcript = TranscriptParser.parseResource("transcripts/elm327-init-and-pids.txt")

    @Test
    fun `replays the full init and PID happy path`() =
        runTest {
            val link = FakeObdLink(transcript)
            link.connect()
            assertEquals(LinkState.Ready, link.state.value)

            assertTrue(link.sendRaw("ATZ").contains("ELM327"))
            assertEquals("ATE0\rOK", link.sendRaw("ATE0"))
            assertEquals("OK", link.sendRaw("ATL0"))
            assertEquals("OK", link.sendRaw("ATS0"))
            assertEquals("OK", link.sendRaw("ATSP0"))
            assertEquals("SEARCHING...\r41 00 BE 3E B8 11", link.sendRaw("0100"))
            assertEquals("41 05 5A", link.sendRaw("0105"))
            assertEquals("41 0B 64", link.sendRaw("010B"))
            assertEquals("41 33 62", link.sendRaw("0133"))
            assertEquals("41 0C 1F 40", link.sendRaw("010C"))
            assertEquals("OK", link.sendRaw("ATSH7E1"))
            assertEquals("OK", link.sendRaw("ATCRA7E9"))
            assertEquals("62 05 43 5C", link.sendRaw("220543"))

            link.disconnect()
            assertEquals(LinkState.Disconnected, link.state.value)
        }

    @Test
    fun `repeated PID polls repeat the last scripted response`() =
        runTest {
            val link = FakeObdLink(transcript)
            link.sendRaw("0105")
            assertEquals("41 05 5A", link.sendRaw("0105"))
            assertEquals("41 05 5A", link.sendRaw("0105"))
        }

    @Test
    fun `unknown command returns the ELM327 unknown-command reply`() =
        runTest {
            val link = FakeObdLink(transcript)
            assertEquals("?", link.sendRaw("ATXYZ"))
        }

    @Test
    fun `Garbage fault returns a malformed frame instead of the scripted response`() =
        runTest {
            val link = FakeObdLink(transcript, commandFaults = mapOf("0105" to listOf(Fault.Garbage)))
            assertNotEquals("41 05 5A", link.sendRaw("0105"))
        }

    @Test
    fun `NoData fault returns NO DATA`() =
        runTest {
            val link = FakeObdLink(transcript, commandFaults = mapOf("0105" to listOf(Fault.NoData)))
            assertEquals("NO DATA", link.sendRaw("0105"))
        }

    @Test
    fun `Stopped fault returns STOPPED`() =
        runTest {
            val link = FakeObdLink(transcript, commandFaults = mapOf("0105" to listOf(Fault.Stopped)))
            assertEquals("STOPPED", link.sendRaw("0105"))
        }

    @Test(expected = TimeoutCancellationException::class)
    fun `Timeout fault never responds and sendRaw times out`() =
        runTest {
            val link = FakeObdLink(transcript, commandFaults = mapOf("0105" to listOf(Fault.Timeout)))
            link.sendRaw("0105", timeout = 200.milliseconds)
        }

    @Test
    fun `MidResponseDisconnect fault throws and moves state to Error`() =
        runTest {
            val link =
                FakeObdLink(transcript, commandFaults = mapOf("0105" to listOf(Fault.MidResponseDisconnect)))
            var threw = false
            try {
                link.sendRaw("0105")
            } catch (expected: ObdLinkDisconnectedException) {
                threw = true
            }
            assertTrue(threw)
            assertTrue(link.state.value is LinkState.Error)
        }

    @Test
    fun `fault queue falls back to the scripted response once exhausted`() =
        runTest {
            val link = FakeObdLink(transcript, commandFaults = mapOf("0105" to listOf(Fault.NoData)))
            assertEquals("NO DATA", link.sendRaw("0105"))
            assertEquals("41 05 5A", link.sendRaw("0105"))
        }

    @Test
    fun `position fault takes precedence over a command fault`() =
        runTest {
            val link =
                FakeObdLink(
                    transcript,
                    commandFaults = mapOf("0105" to listOf(Fault.NoData)),
                    positionFaults = mapOf(1 to Fault.Stopped),
                )
            assertEquals("STOPPED", link.sendRaw("0105"))
        }

    @Test
    fun `latency and fault injection combine on the same call`() =
        runTest {
            val link =
                FakeObdLink(
                    transcript,
                    commandLatency = mapOf("0105" to 150.milliseconds),
                    commandFaults = mapOf("0105" to listOf(Fault.NoData)),
                )
            val before = currentTime
            assertEquals("NO DATA", link.sendRaw("0105"))
            assertEquals(150L, currentTime - before)
        }

    @Test
    fun `per-command latency delays the response by virtual time`() =
        runTest {
            val link = FakeObdLink(transcript, commandLatency = mapOf("0105" to 300.milliseconds))
            val before = currentTime
            link.sendRaw("0105")
            assertEquals(300L, currentTime - before)
        }

    @Test
    fun `half-duplex violation throws when a second sendRaw arrives mid-flight`() =
        runTest {
            val link = FakeObdLink(transcript, commandLatency = mapOf("0105" to 500.milliseconds))
            val first = async { link.sendRaw("0105") }
            runCurrent()

            var threw = false
            try {
                link.sendRaw("010B")
            } catch (expected: IllegalStateException) {
                threw = true
            }
            assertTrue(threw)

            assertEquals("41 05 5A", first.await())
        }
}
