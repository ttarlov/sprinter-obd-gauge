package com.revel.obdgauge.protocol

import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.ObdLink
import com.revel.obdgauge.model.ObdRequest
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.PollPriority
import com.revel.obdgauge.model.Reading
import com.revel.obdgauge.testing.link.FakeObdLink
import com.revel.obdgauge.testing.link.Fault
import com.revel.obdgauge.testing.link.TranscriptParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

@OptIn(ExperimentalCoroutinesApi::class)
class RealVehicleDataSourceTest {
    private val transcript = TranscriptParser.parseResource("transcripts/mode22-trans-temp.txt")
    private val clock = MutableClock()
    private val events = mutableListOf<PollEvent>()
    private val config = PollConfig(cycleInterval = CYCLE, slowEveryNCycles = 5)

    // ---- init and cadence ----

    @Test
    fun `init runs once, then polling begins`() =
        runTest {
            val link = RecordingObdLink(FakeObdLink(transcript))
            val source = sourceFor(link)

            source.start(listOf(def(PidIds.RPM)))
            runCurrent()
            runCycles(6)

            assertEquals(1, link.commands.count { it == "ATZ" })
            assertEquals(1, link.commands.count { it == "0100" })
            assertEquals(INIT_COMMANDS, link.commands.take(INIT_COMMANDS.size))
            assertTrue(events.first() is PollEvent.Initialized)
        }

    @Test
    fun `FAST every cycle, SLOW every Nth, in the requested order`() =
        runTest {
            val link = RecordingObdLink(FakeObdLink(transcript))
            val source = sourceFor(link)

            source.start(listOf(def(PidIds.RPM), def(PidIds.COOLANT), def(PidIds.BARO)))
            runCurrent()
            runCycles(5)

            val polls = link.commands.drop(INIT_COMMANDS.size)
            assertEquals(
                listOf(
                    "010C",
                    "0105",
                    "0133", // cycle 0: everything
                    "010C", // cycle 1
                    "010C", // cycle 2
                    "010C", // cycle 3
                    "010C", // cycle 4
                    "010C",
                    "0105",
                    "0133", // cycle 5: SLOW returns
                ),
                polls,
            )
        }

    @Test
    fun `a mode-22 PID is polled as its whole framed sequence inside the cycle`() =
        runTest {
            val link = RecordingObdLink(FakeObdLink(transcript))
            val source = sourceFor(link)

            source.start(listOf(def(PidIds.RPM), def(PidIds.TRANS_TEMP)))
            runCurrent()

            assertEquals(
                listOf("010C", "ATSH7E1", "ATCRA7E9", "2130", "ATCRA", "ATSH7DF"),
                link.commands.drop(INIT_COMMANDS.size),
            )
            assertEquals(
                TRANS_TEMP_C,
                source.readings.value
                    .getValue(PidIds.TRANS_TEMP)
                    .value,
                TOLERANCE,
            )
        }

    // ---- values ----

    @Test
    fun `standard PIDs publish their natural-unit values with timestamps`() =
        runTest {
            val source = start(def(PidIds.COOLANT), def(PidIds.RPM))

            val readings = source.readings.value
            assertEquals(COOLANT_C, readings.getValue(PidIds.COOLANT).value, TOLERANCE)
            assertEquals(RPM, readings.getValue(PidIds.RPM).value, TOLERANCE)
            assertEquals(clock.now, readings.getValue(PidIds.RPM).timestamp)
            assertFalse(readings.getValue(PidIds.RPM).stale)
        }

    @Test
    fun `asking for boost pulls in the map and baro it needs`() =
        runTest {
            val link = RecordingObdLink(FakeObdLink(transcript))
            val source = sourceFor(link)

            source.start(listOf(def(PidIds.BOOST)))
            runCurrent()

            assertEquals(listOf("010B", "0133"), link.commands.drop(INIT_COMMANDS.size))
            assertEquals(
                BOOST_KPA,
                source.readings.value
                    .getValue(PidIds.BOOST)
                    .value,
                TOLERANCE,
            )
        }

    @Test
    fun `boost is not published when it was not asked for`() =
        runTest {
            val source = start(def(ProtocolPidIds.MAP), def(PidIds.BARO))

            assertNull(source.readings.value[PidIds.BOOST])
        }

    @Test
    fun `boost goes stale when its baro input does, even while map keeps refreshing`() =
        runTest {
            val source = start(def(PidIds.BOOST))
            assertFalse(
                source.readings.value
                    .getValue(PidIds.BOOST)
                    .stale,
            )

            // Skip past baro's window without reaching a cycle that re-polls it.
            clock.advance(STALE_GAP)
            runCycles(1)

            val readings = source.readings.value
            assertTrue("baro aged out", readings.getValue(PidIds.BARO).stale)
            assertFalse("map was just refreshed", readings.getValue(ProtocolPidIds.MAP).stale)
            assertTrue("a fresh MAP over an old baro is not a fresh boost", readings.getValue(PidIds.BOOST).stale)
            assertEquals(BOOST_KPA, readings.getValue(PidIds.BOOST).value, TOLERANCE)
        }

    // ---- refusals ----

    @Test
    fun `a parse failure skips the reading instead of publishing a poisoned value`() =
        runTest {
            val faults = mapOf("0105" to listOf(Fault.Garbage, Fault.NoData))
            val source = start(def(PidIds.COOLANT), link = FakeObdLink(transcript, commandFaults = faults))

            assertNull("nothing at all rather than a wrong number", source.readings.value[PidIds.COOLANT])
            assertTrue(events.any { it is PollEvent.ReadingSkipped && it.id == PidIds.COOLANT })
        }

    @Test
    fun `a value that stops refreshing keeps its number and goes stale on its own`() =
        runTest {
            // Coolant is SLOW in the registry, so cycles 1..4 do not re-poll it. Advancing the
            // clock past its window without a refresh is exactly the "lost the bus" shape.
            val source = start(def(PidIds.COOLANT))
            assertEquals(
                COOLANT_C,
                source.readings.value
                    .getValue(PidIds.COOLANT)
                    .value,
                TOLERANCE,
            )

            clock.advance(STALE_GAP)
            runCycles(1)

            val reading = source.readings.value.getValue(PidIds.COOLANT)
            assertEquals("the last good value is kept, never zeroed", COOLANT_C, reading.value, TOLERANCE)
            assertTrue("but it is dimmed rather than presented as current", reading.stale)
        }

    @Test
    fun `an unknown channel is reported rather than silently never updating`() =
        runTest {
            val source = start(def("madeUpChannel"), def(PidIds.RPM))

            assertTrue(events.any { it is PollEvent.UnknownPid && it.id == "madeUpChannel" })
            assertNull(source.readings.value["madeUpChannel"])
            assertEquals(
                RPM,
                source.readings.value
                    .getValue(PidIds.RPM)
                    .value,
                TOLERANCE,
            )
        }

    @Test
    fun `an ELM327 that will not initialize never polls and parks`() =
        runTest {
            val faults = mapOf("ATZ" to List(ATZ_ATTEMPTS) { Fault.Timeout })
            val link = RecordingObdLink(FakeObdLink(transcript, commandFaults = faults))
            val source = sourceFor(link)

            source.start(listOf(def(PidIds.RPM)))
            // Long enough for both ATZ attempts to run out their 5 s timeouts.
            advanceTimeBy(INIT_GIVE_UP)
            runCurrent()

            assertTrue(events.any { it is PollEvent.InitFailed })
            assertFalse("no PID may be polled on an uninitialized dongle", link.commands.any { it == "010C" })
            assertEquals(emptyMap<String, Reading>(), source.readings.value)
        }

    @Test
    fun `a dongle without ATSH support loses only the mode-22 channel`() =
        runTest {
            // A clone that answers "?" to ATSH: the manufacturer PID cannot be framed, but the
            // standard gauges must carry on as if it were not there.
            val link = FakeObdLink(transcript.filterNot { it.command == "ATSH7E1" })
            val source = start(def(PidIds.RPM), def(PidIds.TRANS_TEMP), link = link)

            assertTrue(events.any { it is PollEvent.HeaderRejected && it.id == PidIds.TRANS_TEMP })
            assertNull(source.readings.value[PidIds.TRANS_TEMP])
            assertEquals(
                RPM,
                source.readings.value
                    .getValue(PidIds.RPM)
                    .value,
                TOLERANCE,
            )
        }

    @Test
    fun `a failed header restore is reported and retried at the top of the next cycle`() =
        runTest {
            val link = RecordingObdLink(FakeObdLink(transcript.filterNot { it.command == "ATSH7DF" }))
            val source = sourceFor(link)

            source.start(listOf(def(PidIds.RPM), def(PidIds.TRANS_TEMP)))
            runCurrent()
            runCycles(1)

            assertTrue(events.any { it is PollEvent.HeaderRestoreFailed })
            val beforeTheStandardPoll = link.commands.dropLast(1).last()
            assertEquals("the retry runs before anything standard is polled", "ATSH7DF", beforeTheStandardPoll)
            assertEquals(
                RPM,
                source.readings.value
                    .getValue(PidIds.RPM)
                    .value,
                TOLERANCE,
            )
        }

    @Test
    fun `a failed restore is retried before a standard poll LATER IN THE SAME CYCLE`() =
        runTest {
            // Review round-1 M1: with the mode-22 PID first in the plan, a failed ATSH7DF must
            // not leave this cycle's remaining standard queries addressed to 7E1. The restore
            // fails ONCE (transient), so the requester's own in-request attempt fails and the
            // per-standard-poll retry succeeds — the SECOND ATSH7DF must come before 010C.
            // Without the intra-cycle retry, the second attempt only happens next cycle.
            val faults = mapOf("ATSH7DF" to listOf(Fault.Garbage))
            val link = RecordingObdLink(FakeObdLink(transcript, commandFaults = faults))
            val source = sourceFor(link)

            source.start(listOf(def(PidIds.TRANS_TEMP), def(PidIds.RPM)))
            runCurrent()

            val rpmAt = link.commands.indexOf("010C")
            val restoreAttempts = link.commands.withIndex().filter { it.value == "ATSH7DF" }
            assertTrue("rpm was polled", rpmAt > 0)
            assertTrue(
                "the successful restore retry must precede the standard poll, not wait a cycle: ${'$'}{link.commands}",
                restoreAttempts.count { it.index < rpmAt } >= 2,
            )
            source.stop()
        }

    // ---- link loss ----

    @Test
    fun `a link drop mid-poll parks the loop and marks everything stale`() =
        runTest {
            val faults = mapOf("0133" to listOf(Fault.MidResponseDisconnect))
            val link = RecordingObdLink(FakeObdLink(transcript, commandFaults = faults))
            val source = sourceFor(link)

            source.start(listOf(def(PidIds.RPM), def(PidIds.BARO)))
            runCurrent()

            assertTrue(events.any { it is PollEvent.LinkDropped && it.id == PidIds.BARO })
            assertTrue(
                source.readings.value
                    .getValue(PidIds.RPM)
                    .stale,
            )

            val afterPark = link.commands.size
            runCycles(5)
            assertEquals("a parked loop does not retry, and never reconnects", afterPark, link.commands.size)
        }

    @Test
    fun `the value read just before a drop is kept, not discarded`() =
        runTest {
            val faults = mapOf("0133" to listOf(Fault.MidResponseDisconnect))
            val link = FakeObdLink(transcript, commandFaults = faults)
            val source = start(def(PidIds.RPM), def(PidIds.BARO), link = link)

            assertEquals(
                RPM,
                source.readings.value
                    .getValue(PidIds.RPM)
                    .value,
                TOLERANCE,
            )
        }

    // ---- the pinned lifecycle contract ----

    @Test
    fun `repeated start replaces the poll set without leaking a second loop`() =
        runTest {
            val link = RecordingObdLink(FakeObdLink(transcript))
            val source = sourceFor(link)

            source.start(listOf(def(PidIds.RPM)))
            runCurrent()
            runCycles(2)
            source.start(listOf(def(PidIds.COOLANT)))
            runCurrent()
            val baseline = link.commands.size
            runCycles(3)

            val added = link.commands.drop(baseline)
            assertTrue("the replaced PID must be gone: $added", added.none { it == "010C" })
            assertNull("and its reading with it", source.readings.value[PidIds.RPM])
            assertEquals(2, link.commands.count { it == "ATZ" }) // one init per session, two sessions
        }

    @Test
    fun `stop is idempotent and halts polling`() =
        runTest {
            val link = RecordingObdLink(FakeObdLink(transcript))
            val source = sourceFor(link)

            source.start(listOf(def(PidIds.RPM)))
            runCurrent()
            source.stop()
            source.stop()
            val afterStop = link.commands.size
            runCycles(5)

            assertEquals(afterStop, link.commands.size)
        }

    @Test
    fun `start after stop is a fresh session`() =
        runTest {
            val link = RecordingObdLink(FakeObdLink(transcript))
            val source = sourceFor(link)

            source.start(listOf(def(PidIds.RPM)))
            runCurrent()
            source.stop()
            source.start(listOf(def(PidIds.COOLANT)))
            runCurrent()

            assertEquals("init re-runs on a fresh session", 2, link.commands.count { it == "ATZ" })
            assertNull("the previous session's readings do not carry over", source.readings.value[PidIds.RPM])
            assertEquals(
                COOLANT_C,
                source.readings.value
                    .getValue(PidIds.COOLANT)
                    .value,
                TOLERANCE,
            )
        }

    @Test
    fun `connection state is forwarded from the link`() =
        runTest {
            val link = FakeObdLink(transcript)
            val source = sourceFor(link)

            assertEquals(link.state.value, source.connection.value)
            link.connect()
            assertEquals(link.state.value, source.connection.value)
        }

    // ---- helpers ----

    private fun TestScope.sourceFor(link: ObdLink): RealVehicleDataSource =
        RealVehicleDataSource(
            link = link,
            scope = backgroundScope,
            config = config,
            clock = clock,
            onEvent = events::add,
        )

    /** Starts a source, runs init plus cycle 0, and hands it back ready to assert on. */
    private fun TestScope.start(
        vararg pids: PidDefinition,
        link: ObdLink = FakeObdLink(transcript),
    ): RealVehicleDataSource {
        val source = sourceFor(link)
        source.start(pids.toList())
        runCurrent()
        return source
    }

    private fun TestScope.runCycles(count: Int) {
        repeat(count) {
            advanceTimeBy(CYCLE + 1.milliseconds)
            runCurrent()
        }
    }

    /**
     * A caller-supplied definition carrying nothing but an id — which is all `start` uses. The
     * placeholder `parse` returns a number that would be glaringly wrong if the scheduler ever
     * used it instead of the registry's own scaling.
     */
    private fun def(id: String): PidDefinition =
        PidDefinition(
            id = id,
            label = id,
            unit = MeasurementUnit.CELSIUS,
            request = ObdRequest.StandardPid(mode = 1, pid = 0),
            parse = { WRONG_ON_PURPOSE },
            pollPriority = PollPriority.FAST,
        )

    private class MutableClock(
        var now: Instant = Instant.EPOCH,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now

        fun advance(by: Duration) {
            now = now.plus(by.toJavaDuration())
        }
    }

    private companion object {
        val CYCLE = 200.milliseconds
        val STALE_GAP = 60.seconds
        val INIT_GIVE_UP = 30.seconds
        const val TOLERANCE = 1e-9
        const val ATZ_ATTEMPTS = 2
        const val WRONG_ON_PURPOSE = -999.0

        val INIT_COMMANDS = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATSP0", "0100")

        /** From the fixture: `41 05 82` → 130 − 40. */
        const val COOLANT_C = 90.0

        /** From the fixture: `41 0C 1F 40` → (256·31 + 64) / 4. */
        const val RPM = 2000.0

        /** From the fixture: MAP `41 0B B4` = 180 kPa, baro `41 33 51` = 81 kPa. */
        const val BOOST_KPA = 99.0

        /** From the fixture: `61 30 91` → 145 − 50. */
        const val TRANS_TEMP_C = 95.0
    }
}
