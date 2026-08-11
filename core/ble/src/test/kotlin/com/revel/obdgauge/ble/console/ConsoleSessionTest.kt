package com.revel.obdgauge.ble.console

import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.ObdLink
import com.revel.obdgauge.testing.link.FakeObdLink
import com.revel.obdgauge.testing.link.Fault
import com.revel.obdgauge.testing.link.TranscriptEntry
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * OBD-19: the debug-console REPL controller, exercised against `FakeObdLink` — no Android, no
 * Robolectric needed since [ConsoleSession] is pure logic over `ObdLink`.
 *
 * [ConsoleSession] collects `link.state` on the scope it's given for the session's whole
 * lifetime (see its KDoc) — under `runTest` that collector must run on `backgroundScope`, never
 * the test's own scope, or the test hangs waiting for a coroutine that's designed to never
 * complete. `newSession` below is every test's single construction point so that's not
 * something each test has to get right on its own; it also drains the scheduler once
 * immediately after construction, so the one entry the collector always logs first — the
 * link's ambient starting state (see [ConsoleSession]'s KDoc: it is not filtered out) — lands
 * deterministically before any test performs its own action, instead of racing it.
 *
 * One local quirk of this project's `kotlinx-coroutines-test` version: a `backgroundScope` job
 * only gets its first real dispatch once the *calling* coroutine has gone through an actual
 * dispatch cycle of its own — a bare [advanceUntilIdle]/[runCurrent] called synchronously,
 * with nothing in between, does not reach it (confirmed empirically: identical code launched
 * on the plain `TestScope` runs immediately under `advanceUntilIdle()`; the same launched on
 * `backgroundScope` does not, until a [yield] happens first). [settle] below is that `yield()`
 * plus a drain, used at every point a test needs the state collector to have caught up.
 */
class ConsoleSessionTest {
    private val fixedClock: Clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)

    private fun transcript() =
        listOf(
            TranscriptEntry("ATZ", "ELM327 v2.1"),
            TranscriptEntry("ATE0", "OK"),
        )

    /** See this class's KDoc: `backgroundScope` needs an actual dispatch cycle, not just a
     *  scheduler drain, to run for the first time (or to pick up work queued since the last
     *  one). Call this instead of a bare `advanceUntilIdle()` after anything that changes
     *  `link.state`, or the collector's resulting [ConsoleEntry.LinkStateChanged] may not have
     *  landed yet when the test reads [ConsoleSession.entries]. */
    private suspend fun TestScope.settle() {
        yield()
        advanceUntilIdle()
    }

    private suspend fun TestScope.newSession(
        link: ObdLink,
        forgetter: RememberedDeviceForgetter? = null,
        historyLimit: Int = ConsoleSession.DEFAULT_HISTORY_LIMIT,
    ): ConsoleSession {
        val session =
            ConsoleSession(
                link = link,
                scope = backgroundScope,
                forgetter = forgetter,
                historyLimit = historyLimit,
                clock = fixedClock,
            )
        settle() // let the ambient LinkStateChanged(Disconnected) entry land first.
        return session
    }

    /** Every fixture starts at [LinkState.Disconnected]; asserted once so the rest of the
     *  suite can safely drop this leading entry without re-proving it exists each time. */
    @Test
    fun `constructing a session logs the link's ambient starting state`() =
        runTest {
            val session = newSession(FakeObdLink(transcript()))

            val entries = session.entries.value
            assertEquals(1, entries.size)
            val ambient = entries.single() as ConsoleEntry.LinkStateChanged
            assertEquals(LinkState.Disconnected, ambient.state)
        }

    // ---- command -> response round trip ----------------------------------------------------

    @Test
    fun `a command-response round trip appears in scrollback in order`() =
        runTest {
            val session = newSession(FakeObdLink(transcript()))

            val ok = session.sendCommand("ATZ")

            assertTrue(ok)
            val entries = session.entries.value.drop(1) // drop the ambient LinkStateChanged.
            assertEquals(2, entries.size)
            val sent = entries[0] as ConsoleEntry.CommandSent
            assertEquals("ATZ", sent.command)
            // The injected fixed clock must be the entries' time source — the HH:mm:ss.SSS
            // stamp is how a human pairs responses with commands at the van (review MAJOR).
            assertEquals(java.time.Instant.EPOCH, sent.timestamp)
            val received = entries[1] as ConsoleEntry.ResponseReceived
            assertEquals("ATZ", received.command)
            assertEquals("ELM327 v2.1", received.response)
            assertEquals(java.time.Instant.EPOCH, received.timestamp)
        }

    @Test
    fun `multiple commands stay in send-response pairs, in call order`() =
        runTest {
            val session = newSession(FakeObdLink(transcript()))

            session.sendCommand("ATZ")
            session.sendCommand("ATE0")

            val entries = session.entries.value.drop(1)
            assertEquals(4, entries.size)
            assertEquals("ATZ", (entries[0] as ConsoleEntry.CommandSent).command)
            assertEquals("ATZ", (entries[1] as ConsoleEntry.ResponseReceived).command)
            assertEquals("ATE0", (entries[2] as ConsoleEntry.CommandSent).command)
            assertEquals("OK", (entries[3] as ConsoleEntry.ResponseReceived).response)
        }

    // ---- timeout -----------------------------------------------------------------------------

    @Test
    fun `a timeout is logged as an error entry and the session is usable after`() =
        runTest {
            val link =
                FakeObdLink(
                    transcript = transcript(),
                    commandFaults = mapOf("ATZ" to listOf(Fault.Timeout)),
                )
            val session = newSession(link)

            val timedOut = session.sendCommand("ATZ", timeout = 100.milliseconds)

            assertFalse(timedOut)
            val afterTimeout = session.entries.value.drop(1)
            assertEquals(2, afterTimeout.size)
            val error = afterTimeout[1] as ConsoleEntry.ErrorOccurred
            assertEquals("ATZ", error.command)
            assertTrue(error.message.contains("timeout", ignoreCase = true))

            // The fault queue for "ATZ" is now exhausted (queued fault consumed, none left) —
            // a second call falls back to the scripted response, proving the mutex/in-flight
            // flag were released cleanly after the timeout.
            val ok = session.sendCommand("ATZ")
            assertTrue(ok)
            assertEquals(
                4,
                session.entries.value
                    .drop(1)
                    .size,
            )
            val received = session.entries.value.drop(1)[3] as ConsoleEntry.ResponseReceived
            assertEquals("ELM327 v2.1", received.response)
        }

    // ---- link-state transitions ---------------------------------------------------------------

    @Test
    fun `link-state transitions after the ambient state are recorded in order`() =
        runTest {
            val link = FakeObdLink(transcript(), connectLatency = 10.milliseconds)
            val session = newSession(link)

            session.connect()
            settle()

            val transitions = session.entries.value.filterIsInstance<ConsoleEntry.LinkStateChanged>()
            assertEquals(
                listOf(LinkState.Disconnected, LinkState.Connecting, LinkState.Ready),
                transitions.map { it.state },
            )
        }

    @Test
    fun `a mid-response disconnect is recorded as both an error entry and a state transition`() =
        runTest {
            val link =
                FakeObdLink(
                    transcript = transcript(),
                    commandFaults = mapOf("ATZ" to listOf(Fault.MidResponseDisconnect)),
                )
            val session = newSession(link)

            val ok = session.sendCommand("ATZ")
            settle()

            assertFalse(ok)
            val entries = session.entries.value
            assertTrue(entries.any { it is ConsoleEntry.ErrorOccurred })
            val transitions = entries.filterIsInstance<ConsoleEntry.LinkStateChanged>()
            // The ambient Disconnected entry, plus the disconnect's own Error transition.
            assertEquals(2, transitions.size)
            assertTrue(transitions.last().state is LinkState.Error)
        }

    // ---- history bound -------------------------------------------------------------------------

    @Test
    fun `scrollback is bounded to historyLimit, oldest entries dropped first`() =
        runTest {
            val link = FakeObdLink(transcript = listOf(TranscriptEntry("PING", "PONG")))
            val session = newSession(link, historyLimit = 3)

            // 1 ambient entry + 5 round trips (10 entries) = 11 raw entries; only the newest 3
            // survive: the 4th response, then the 5th command/response pair.
            repeat(5) { session.sendCommand("PING") }

            val entries = session.entries.value
            assertEquals(3, entries.size)
            assertEquals("PONG", (entries[0] as ConsoleEntry.ResponseReceived).response)
            assertEquals("PING", (entries[1] as ConsoleEntry.CommandSent).command)
            assertEquals("PONG", (entries[2] as ConsoleEntry.ResponseReceived).response)
        }

    // ---- in-flight policy: refuse, don't queue --------------------------------------------------

    @Test
    fun `a second command while one is in flight is refused, not queued`() =
        runTest {
            val link = FakeObdLink(transcript = transcript(), defaultLatency = 1.seconds)
            val session = newSession(link)

            val firstCall = async { session.sendCommand("ATZ") }
            runCurrent() // let the first call reach FakeObdLink's delay(); it is now in flight.
            assertTrue(session.commandInFlight.value)

            val refused = session.sendCommand("ATE0")

            assertFalse(refused)
            val afterRefusal = session.entries.value.drop(1)
            // CommandSent(ATZ) so far; the refusal for ATE0 logs an error without a CommandSent.
            assertEquals(2, afterRefusal.size)
            val refusalEntry = afterRefusal[1] as ConsoleEntry.ErrorOccurred
            assertEquals("ATE0", refusalEntry.command)
            assertTrue(refusalEntry.message.contains("refused"))

            advanceUntilIdle()
            assertTrue(firstCall.await())
            assertFalse(session.commandInFlight.value)
        }

    // ---- forget remembered device -----------------------------------------------------------

    @Test
    fun `forgetRememberedDevice delegates to the forgetter when present`() =
        runTest {
            var forgotten = false
            val session =
                newSession(FakeObdLink(transcript()), forgetter = RememberedDeviceForgetter { forgotten = true })

            session.forgetRememberedDevice()

            assertTrue(forgotten)
            assertEquals(1, session.entries.value.size) // just the ambient entry, nothing more.
        }

    @Test
    fun `forgetRememberedDevice logs an error when the link has no forgetter`() =
        runTest {
            val session = newSession(FakeObdLink(transcript()))

            session.forgetRememberedDevice()

            val entries = session.entries.value.drop(1)
            assertEquals(1, entries.size)
            val error = entries.single() as ConsoleEntry.ErrorOccurred
            assertNull(error.command)
            assertTrue(error.message.contains("not supported"))
        }

    // ---- recordError -------------------------------------------------------------------------

    @Test
    fun `recordError appends an error entry directly, for callers outside the link such as denied permissions`() =
        runTest {
            val session = newSession(FakeObdLink(transcript()))

            session.recordError("Bluetooth permission denied: BLUETOOTH_SCAN")

            val error =
                session.entries.value
                    .drop(1)
                    .single() as ConsoleEntry.ErrorOccurred
            assertNull(error.command)
            assertTrue(error.message.contains("permission denied"))
        }
}
