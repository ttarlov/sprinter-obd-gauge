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
import com.revel.obdgauge.testing.link.TranscriptEntry
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
            // Repointed in round 2 from PidIds.TRANS_TEMP to PIPELINE_CHANNEL: the trans-temp
            // decode is falsified and must no longer reach the wire, but the scheduler behaviour
            // asserted here — a manufacturer channel running its whole framed sequence within one
            // cycle, between the standard polls — is spec-agnostic and keeps its full strength.
            val link = RecordingObdLink(FakeObdLink(transcript))
            val source = sourceFor(link)

            source.start(listOf(def(PidIds.RPM), def(PIPELINE_CHANNEL_ID)))
            runCurrent()

            assertEquals(
                listOf("010C", "ATSH7E1", "ATCRA7E9", "2130", "ATCRA", "ATSH7DF"),
                link.commands.drop(INIT_COMMANDS.size),
            )
            assertEquals(
                TRANS_TEMP_C,
                source.readings.value
                    .getValue(PIPELINE_CHANNEL_ID)
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
    fun `asking for boost pulls in the speed-density inputs it can poll`() =
        runTest {
            // OBD-57: boost expands to [maf, iatSensor, rpm, baro]. MAF has no channel yet
            // (PendingUnitContract, g/s unit — OBD-58), so it is not put on the wire; the three
            // pollable inputs are, in dependency order.
            val link = RecordingObdLink(FakeObdLink(transcript))
            val source = sourceFor(link)

            source.start(listOf(def(PidIds.BOOST)))
            runCurrent()

            assertEquals(listOf("0168", "010C", "0133"), link.commands.drop(INIT_COMMANDS.size))
            // And no boost value: MAF is the one input that cannot land, so the subtraction never
            // runs and nothing (not a zero) is published. See the availability test below for why.
            assertNull(source.readings.value[PidIds.BOOST])
        }

    @Test
    fun `boost is not published when it was not asked for`() =
        runTest {
            val source = start(def(ProtocolPidIds.MAP), def(PidIds.BARO))

            assertNull(source.readings.value[PidIds.BOOST])
        }

    @Test
    fun `the wired speed-density path computes a real boost once MAF lands - the OBD-58 flip preview`() =
        runTest {
            // A preview of the auto-flip: inject MAF as a resolvable channel (exactly what OBD-58
            // does when the g/s unit lands and MAF joins PidRegistry) and answer all four inputs.
            // No production code changes — publish() already routes boost through
            // ComputedChannels.speedDensityBoost — so a real boost value appears.
            val script =
                transcript.filterNot { it.command in setOf("0166", "0168") } +
                    TranscriptEntry("0166", "41 66 01 01 C7 00 00") + // MAF sensor A → 14.21875 g/s
                    TranscriptEntry("0168", "41 68 01 54 00 00 21 00") // IAT sensor 1 → 44 °C
            val link = FakeObdLink(script)
            val source = sourceWithMaf(link)

            source.start(listOf(def(PidIds.BOOST)))
            runCurrent()

            val boost = source.readings.value[PidIds.BOOST]
            assertTrue("boost goes live the moment MAF is pollable", boost != null)
            // The wired path must agree exactly with the pure function on the same four inputs.
            val expected =
                ComputedChannels.speedDensityBoost(
                    maf = Reading("maf", 14.21875, clock.now, stale = false),
                    iat = Reading(ProtocolPidIds.IAT_SENSOR, 44.0, clock.now, stale = false),
                    rpm = Reading(PidIds.RPM, RPM, clock.now, stale = false),
                    baro = Reading(PidIds.BARO, BARO_KPA, clock.now, stale = false),
                )!!
            assertEquals(expected.value, boost!!.value, TOLERANCE)
            assertFalse("a computed idle boost is not a zero placeholder", boost.value == 0.0)
        }

    // ---- OBD-43: explicit degradation when the vehicle cannot feed a channel ----

    @Test
    fun `asking for boost on this van announces the missing MAF before any command goes out`() =
        runTest {
            val link = RecordingObdLink(FakeObdLink(transcript))
            val source = sourceFor(link)

            source.start(listOf(def(PidIds.BOOST)))

            // Before runCurrent(): nothing has been sent yet, and the verdict is already known —
            // from the frozen-unit gap, not inferred from a gauge that never moves.
            assertEquals(emptyList<String>(), link.commands)
            // OBD-57: the one ungrounded input is MAF. Consequence first (boost), then each input
            // that is not Available (MAF), each typed. IAT/rpm/baro are Available and stay silent.
            assertEquals(
                listOf(
                    PidIds.BOOST to ChannelAvailability.MissingInputs(listOf(ProtocolPidIds.MAF)),
                    ProtocolPidIds.MAF to PidCatalog.availabilityOf(ProtocolPidIds.MAF),
                ),
                events.filterIsInstance<PollEvent.ChannelAvailabilityChanged>().map { it.id to it.availability },
            )
            assertTrue(
                PidCatalog.availabilityOf(ProtocolPidIds.MAF) is ChannelAvailability.PendingUnitContract,
            )
        }

    @Test
    fun `a boost gauge with no MAF reading gets no reading at all, never a zero`() =
        runTest {
            // The van's real shape post-OBD-57: IAT/rpm/baro answer, MAF has no channel yet. The
            // speed-density model has three of four operands, and 0 kPa — "no boost, engine not
            // pulling" — must never stand in for "MAF is not published on this vehicle yet".
            val source = start(def(PidIds.BOOST))

            assertNull("no boost reading", source.readings.value[PidIds.BOOST])
            assertNull("and no MAF to have made a MAP from", source.readings.value[ProtocolPidIds.MAF])
            assertEquals(
                "baro still reads — three of four inputs are alive and that stays visible",
                BARO_KPA,
                source.readings.value
                    .getValue(PidIds.BARO)
                    .value,
                TOLERANCE,
            )
            assertTrue(
                "and the loop said why, in types",
                events.any {
                    it is PollEvent.ChannelAvailabilityChanged &&
                        it.id == PidIds.BOOST &&
                        it.availability == ChannelAvailability.MissingInputs(listOf(ProtocolPidIds.MAF))
                },
            )
        }

    @Test
    fun `the verdict is stated once per session, not once per publish`() =
        runTest {
            val source = start(def(PidIds.BOOST))
            runCycles(4)
            source.stop()

            // Dozens of publishes, one verdict. Deriving availability from "boost has not been
            // published lately" would emit several times a second for the life of the session and
            // drown the console it is meant to inform.
            assertEquals(
                1,
                events.count { it is PollEvent.ChannelAvailabilityChanged && it.id == PidIds.BOOST },
            )
        }

    @Test
    fun `a fresh session restates the verdict rather than assuming the consumer remembers`() =
        runTest {
            val link = FakeObdLink(transcript)
            val source = sourceFor(link)

            source.start(listOf(def(PidIds.BOOST)))
            runCurrent()
            source.start(listOf(def(PidIds.BOOST)))
            runCurrent()

            assertEquals(
                2,
                events.count { it is PollEvent.ChannelAvailabilityChanged && it.id == PidIds.BOOST },
            )
        }

    @Test
    fun `a falsified decode is never requested and never stored, only announced`() =
        runTest {
            // The round-1 review's closing argument, as a test. A channel whose decode is known
            // wrong must not reach the wire and must store no Reading — an answer would be misread
            // by construction and render a plausible wrong number. OBD-55 repointed this from
            // PidIds.TRANS_TEMP (now a live, identified channel) to a synthetic falsified probe:
            // the gate is the subject, and it is spec-agnostic. See FALSIFIED_PROBE.
            val link = RecordingObdLink(FakeObdLink(transcript))
            val source = sourceFor(link)

            source.start(listOf(def(PidIds.RPM), def(FALSIFIED_PROBE_ID)))
            runCurrent()
            runCycles(5)

            val polls = link.commands.drop(INIT_COMMANDS.size)
            assertFalse("the header must never be set for a falsified channel: $polls", "ATSH7E1" in polls)
            assertFalse("and the request must never go out: $polls", "2130" in polls)
            assertNull("nothing may be stored under it", source.readings.value[FALSIFIED_PROBE_ID])
            assertTrue(
                "the verdict is announced once, at plan time",
                events.any {
                    it is PollEvent.ChannelAvailabilityChanged &&
                        it.id == FALSIFIED_PROBE_ID &&
                        it.availability is ChannelAvailability.DecodeFalsified
                },
            )
            assertEquals(
                RPM,
                source.readings.value
                    .getValue(PidIds.RPM)
                    .value,
                TOLERANCE,
            )
        }

    @Test
    fun `gating the falsified channel costs the rest of the cycle nothing`() =
        runTest {
            // The gate skips a channel, it does not abort the cycle: everything else in the plan
            // must still be polled, in order, exactly as if the falsified id were absent.
            val link = RecordingObdLink(FakeObdLink(transcript))
            val source = sourceFor(link)

            source.start(listOf(def(PidIds.RPM), def(FALSIFIED_PROBE_ID), def(PidIds.COOLANT)))
            runCurrent()

            assertEquals(listOf("010C", "0105"), link.commands.drop(INIT_COMMANDS.size))
            assertEquals(
                COOLANT_C,
                source.readings.value
                    .getValue(PidIds.COOLANT)
                    .value,
                TOLERANCE,
            )
        }

    @Test
    fun `the trans-temp record channel is polled as its framed sequence and shows its value`() =
        runTest {
            // OBD-55: PidIds.TRANS_TEMP now resolves to the KWP `21 30` record channel. It runs the
            // same five-command framed sequence a mode-22 poll does, and its byte-1 value lands on
            // the gauge — the inverse of the falsified-gate test above. The shared fixture answers
            // 2130 as a single frame; the record channel needs a real multi-frame block, so swap in
            // the session-3 post-drive record (byte 1 = 0x12 → 63 − 18 = 45 °C).
            val recordScript =
                transcript.filterNot { it.command == "2130" } +
                    TranscriptEntry("2130", TcuRecordCaptures.S3_POST_DRIVE)
            val link = RecordingObdLink(FakeObdLink(recordScript))
            val source = sourceFor(link)

            source.start(listOf(def(PidIds.RPM), def(PidIds.TRANS_TEMP)))
            runCurrent()

            assertEquals(
                listOf("010C", "ATSH7E1", "ATCRA7E9", "2130", "ATCRA", "ATSH7DF"),
                link.commands.drop(INIT_COMMANDS.size),
            )
            assertEquals(
                45.0,
                source.readings.value
                    .getValue(PidIds.TRANS_TEMP)
                    .value,
                TOLERANCE,
            )
        }

    @Test
    fun `a channel nothing is known against is announced as nothing`() =
        runTest {
            start(def(PidIds.RPM), def(PidIds.COOLANT))

            assertTrue(
                "silence for healthy channels keeps the event stream a signal",
                events.none { it is PollEvent.ChannelAvailabilityChanged },
            )
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
            // Repointed in round 2, same reason as the framed-sequence test above: "a dongle
            // without ATSH support costs only the manufacturer channel" is a claim about the
            // scheduler's failure isolation, not about which manufacturer spec is riding on it.
            val link = FakeObdLink(transcript.filterNot { it.command == "ATSH7E1" })
            val source = start(def(PidIds.RPM), def(PIPELINE_CHANNEL_ID), link = link)

            assertTrue(events.any { it is PollEvent.HeaderRejected && it.id == PIPELINE_CHANNEL_ID })
            assertNull(source.readings.value[PIPELINE_CHANNEL_ID])
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
            // Repointed in round 2: a restore can only fail after a header was actually set, and
            // the falsified channel no longer sets one. The retry-at-top-of-cycle contract is
            // unchanged and still fully asserted, now through PIPELINE_CHANNEL.
            val link = RecordingObdLink(FakeObdLink(transcript.filterNot { it.command == "ATSH7DF" }))
            val source = sourceFor(link)

            source.start(listOf(def(PidIds.RPM), def(PIPELINE_CHANNEL_ID)))
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

            // Repointed in round 2 for the same reason as the sibling restore test: the
            // intra-cycle retry (review round-1 M1 of OBD-16) needs a manufacturer channel that
            // actually sets a header, and the falsified one no longer does.
            source.start(listOf(def(PIPELINE_CHANNEL_ID), def(PidIds.RPM)))
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
            overrides =
                TestOverrides(
                    extraChannels =
                        listOf(
                            PolledPid.Manufacturer(PIPELINE_CHANNEL),
                            PolledPid.Manufacturer(FALSIFIED_PROBE),
                        ),
                    extraFalsified = setOf(FALSIFIED_PROBE_ID),
                ),
        )

    /**
     * Like [sourceFor], but with a resolvable MAF channel folded in — a stand-in for what OBD-58
     * does when the g/s unit lands and MAF becomes an ordinary [PidRegistry] channel. Lets the
     * live speed-density boost path be exercised end-to-end before that contract change ships.
     */
    private fun TestScope.sourceWithMaf(link: ObdLink): RealVehicleDataSource =
        RealVehicleDataSource(
            link = link,
            scope = backgroundScope,
            config = config,
            clock = clock,
            onEvent = events::add,
            overrides = TestOverrides(extraChannels = listOf(PolledPid.Standard(MAF_PROBE))),
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

        /** From the fixture: baro `41 33 51`. */
        const val BARO_KPA = 81.0

        /** From the fixture: `61 30 91` → 145 − 50. */
        const val TRANS_TEMP_C = 95.0

        /** A synthetic id, deliberately outside `PidCatalog`'s falsified-decode list. */
        const val PIPELINE_CHANNEL_ID = "pipelineProbe"

        /**
         * The manufacturer channel these tests use to exercise the *scheduler pipeline*.
         *
         * Round-2 change (falsified-decode gate). `PidIds.TRANS_TEMP` used to be this vehicle,
         * but its X-Gauge decode was falsified by the 2026-08-12 capture, so it is now neither
         * sent nor stored — polling it is exactly what must no longer happen. The behaviour these
         * tests actually cover is scheduler-level and spec-agnostic: does a `PolledPid.Manufacturer`
         * run its whole five-command framed sequence inside one cycle, does a rejected `ATSH` cost
         * only that channel, is an unacknowledged restore retried. So the pipeline gets a vehicle
         * of its own rather than losing its coverage to a decision about a different question.
         *
         * It rides the same `2130` wire address as the fixture's scripted answer — the command
         * sequence and the `61 30 91` reply are the point — under an id nothing has falsified.
         */
        val PIPELINE_CHANNEL: Mode22PidSpec =
            MercedesPidRegistry.transTemp.let { source ->
                source.copy(definition = source.definition.copy(id = PIPELINE_CHANNEL_ID))
            }

        /**
         * A resolvable MAF channel for the OBD-58-flip-preview test — id [ProtocolPidIds.MAF] so
         * `publish()`'s `polled[MAF]` finds it, wire address `0166`, sensor-A g/s decode. Its
         * declared unit is a placeholder (the whole point of OBD-58 is that g/s has no real
         * [MeasurementUnit] yet), which is harmless: the boost computation reads the value, never
         * the unit.
         */
        val MAF_PROBE: StandardPidSpec =
            StandardPidSpec(
                definition =
                    PidDefinition(
                        id = ProtocolPidIds.MAF,
                        label = "MAF",
                        unit = MeasurementUnit.KPA,
                        request = ObdRequest.StandardPid(mode = 1, pid = 0x66),
                        parse = { data ->
                            VendoredSaeScaling.massAirFlowGramsPerSecond(
                                b = VendoredSaeScaling.dataByte(data, 1),
                                c = VendoredSaeScaling.dataByte(data, 2),
                            )
                        },
                        pollPriority = PollPriority.FAST,
                        verified = false,
                    ),
                mode = 1,
                pid = 0x66,
                dataByteCount = 3,
            )

        /** A synthetic id the test marks falsified via `extraFalsified`, to exercise the gate. */
        const val FALSIFIED_PROBE_ID = "falsifiedProbe"

        /**
         * A manufacturer channel these tests use to exercise the *falsified-decode gate*.
         *
         * OBD-55 change. `PidIds.TRANS_TEMP` used to be the gate's real subject, but its decode was
         * identified on-vehicle and it is now a live channel — so the gate's own set is empty and
         * would go untested. The behaviour the gate tests cover (a falsified id is never framed,
         * never sent, never stored, only announced) is spec-agnostic, so it gets a synthetic
         * vehicle of its own — resolvable via `extraChannels`, marked falsified via
         * `extraFalsified` — rather than losing coverage to the id swap. Mirrors [PIPELINE_CHANNEL].
         */
        val FALSIFIED_PROBE: Mode22PidSpec =
            MercedesPidRegistry.transTemp.let { source ->
                source.copy(definition = source.definition.copy(id = FALSIFIED_PROBE_ID))
            }
    }
}
