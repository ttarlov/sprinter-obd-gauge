package com.revel.obdgauge.app.datasource

import com.revel.obdgauge.app.gauge.INSTANT_MPG_PID_ID
import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.Reading
import com.revel.obdgauge.model.VehicleDataSource
import com.revel.obdgauge.protocol.PidCatalog
import com.revel.obdgauge.protocol.ProtocolPidIds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * [InstantMpgCompute]: the pure `mpg = speed_mph / (fuelRate_Lph / 3.785411784)` formula plus its
 * absence guards. No coroutine, no `Clock`, no `VehicleDataSource` — exactly the split
 * `ComputedChannelsTest` uses for boost in `:core:protocol`.
 */
class InstantMpgComputeTest {
    @Test
    fun `a realistic cruise sample computes the expected mpg`() {
        // 65 mph at 3.2 L/h — a deliberately round, arithmetically-checkable pair, not a claim
        // about this van's real economy. gph = 3.2 / 3.785411784 = 0.845488...;
        // mpg = 65 / 0.845488... = 76.891176...
        val speed = reading(SPEED_ID, 65.0)
        val fuelRate = reading(FUEL_RATE_ID, 3.2)

        val result = InstantMpgCompute.compute(speed, fuelRate)!!

        assertEquals(INSTANT_MPG_PID_ID, result.id)
        assertEquals(76.891177, result.value, TOLERANCE)
    }

    @Test
    fun `freshness is the OLDER of the two inputs' timestamps, mirroring ComputedChannels boost`() {
        val older = Instant.EPOCH
        val newer = Instant.EPOCH.plusSeconds(5)
        val speed = Reading(SPEED_ID, 50.0, newer, stale = false)
        val fuelRate = Reading(FUEL_RATE_ID, 2.0, older, stale = false)

        val result = InstantMpgCompute.compute(speed, fuelRate)!!

        assertEquals(older, result.timestamp)
    }

    @Test
    fun `stale if EITHER input is stale`() {
        val speed = Reading(SPEED_ID, 50.0, Instant.EPOCH, stale = true)
        val fuelRate = Reading(FUEL_RATE_ID, 2.0, Instant.EPOCH, stale = false)

        assertTrue(InstantMpgCompute.compute(speed, fuelRate)!!.stale)
    }

    @Test
    fun `absent speed yields no reading`() {
        assertNull(InstantMpgCompute.compute(null, reading(FUEL_RATE_ID, 2.0)))
    }

    @Test
    fun `absent fuel rate yields no reading`() {
        assertNull(InstantMpgCompute.compute(reading(SPEED_ID, 50.0), null))
    }

    @Test
    fun `fuel-cut (fuelRate zero) yields no reading, never a fabricated infinity`() {
        assertNull(InstantMpgCompute.compute(reading(SPEED_ID, 50.0), reading(FUEL_RATE_ID, 0.0)))
    }

    @Test
    fun `a negative fuel rate - a sensor dropout, not a real reading - also yields no reading`() {
        assertNull(InstantMpgCompute.compute(reading(SPEED_ID, 50.0), reading(FUEL_RATE_ID, -1.0)))
    }

    @Test
    fun `idle - stopped but fuel still flowing - is a well-defined zero, not an absence`() {
        val result = InstantMpgCompute.compute(reading(SPEED_ID, 0.0), reading(FUEL_RATE_ID, 0.8))

        assertEquals(0.0, result!!.value, TOLERANCE)
    }

    private fun reading(
        id: String,
        value: Double,
    ) = Reading(id = id, value = value, timestamp = Instant.EPOCH, stale = false)

    private companion object {
        const val SPEED_ID = "speed"
        const val FUEL_RATE_ID = "fuelRate"
        const val TOLERANCE = 0.001
    }
}

/**
 * [InstantMpgSmoother]: the ~2-3 s rolling average over successive raw samples. Deterministic —
 * every [InstantMpgSmoother.accept] call takes its `now` explicitly, so the window is driven
 * entirely by the [Instant]s a test chooses, exactly like `WedgeInputsTest` drives
 * `wedgeInputs`/`wedgeReconnectDecision` with explicit `nowMillis` values.
 */
class InstantMpgSmootherTest {
    @Test
    fun `a single sample smooths to itself`() {
        val smoother = InstantMpgSmoother()
        val now = Instant.EPOCH

        val result = smoother.accept(reading(20.0, now), now)!!

        assertEquals(20.0, result.value, TOLERANCE)
    }

    @Test
    fun `two samples within the window average together`() {
        val smoother = InstantMpgSmoother()
        val t0 = Instant.EPOCH
        val t1 = t0.plusMillis(1_000)

        smoother.accept(reading(10.0, t0), t0)
        val result = smoother.accept(reading(20.0, t1), t1)!!

        assertEquals(15.0, result.value, TOLERANCE)
    }

    @Test
    fun `a sample older than the window is evicted before averaging`() {
        val smoother = InstantMpgSmoother(window = Duration.ofMillis(2_500))
        val t0 = Instant.EPOCH
        val t1 = t0.plusMillis(3_000) // outside the 2.5s window relative to t2 below
        val t2 = t0.plusMillis(3_100)

        smoother.accept(reading(0.0, t0), t0)
        smoother.accept(reading(30.0, t1), t1) // t0 is already 3s behind t1 (> the 2.5s window) - evicted here
        val result = smoother.accept(reading(30.0, t2), t2)!!

        // t0's 0.0 sample never survives past the t1 call - only the two 30.0 samples remain by
        // t2, so the average is exactly 30.0, not skewed toward the stale zero.
        assertEquals(30.0, result.value, TOLERANCE)
    }

    @Test
    fun `a null sample (fuel-cut or dropout) is skipped, not treated as a zero and not resetting the window`() {
        val smoother = InstantMpgSmoother()
        val t0 = Instant.EPOCH
        val t1 = t0.plusMillis(500)
        val t2 = t0.plusMillis(1_000)

        smoother.accept(reading(20.0, t0), t0)
        val duringGap = smoother.accept(null, t1)!!
        val afterGap = smoother.accept(reading(20.0, t2), t2)!!

        // The gap tick still answers from the surviving window (just the t0 sample) - not null,
        // and not 0.0 - and the later real sample rejoins the same running average.
        assertEquals(20.0, duringGap.value, TOLERANCE)
        assertEquals(20.0, afterGap.value, TOLERANCE)
    }

    @Test
    fun `no valid samples in-window is absence, not a zero or a stale echo`() {
        val smoother = InstantMpgSmoother(window = Duration.ofMillis(2_500))
        val t0 = Instant.EPOCH
        val t1 = t0.plusMillis(3_000)

        smoother.accept(reading(20.0, t0), t0)
        // t1 is past the window with no new sample to replace the evicted one.
        val result = smoother.accept(null, t1)

        assertNull(result)
    }

    @Test
    fun `never having received a sample is absence`() {
        val smoother = InstantMpgSmoother()

        assertNull(smoother.accept(null, Instant.EPOCH))
    }

    @Test
    fun `a repeated sample at the exact same timestamp replaces rather than double-counts`() {
        // The decorator's stateIn seed value and the upstream flow's own replay of the delegate's
        // current StateFlow value can both process the identical Reading — see
        // InstantMpgDataSource.readings' KDoc. The smoother must not let that inflate the average.
        val smoother = InstantMpgSmoother()
        val t0 = Instant.EPOCH

        smoother.accept(reading(10.0, t0), t0)
        smoother.accept(reading(10.0, t0), t0) // exact same timestamp, simulating the double-invoke
        val result = smoother.accept(reading(20.0, t0.plusMillis(100)), t0.plusMillis(100))!!

        // If the first sample had been double-counted, the average would skew toward 10.0
        // (10, 10, 20 -> 13.33) instead of the correct two-distinct-sample average.
        assertEquals(15.0, result.value, TOLERANCE)
    }

    @Test
    fun `stale if any surviving in-window sample is stale`() {
        val smoother = InstantMpgSmoother()
        val t0 = Instant.EPOCH
        val t1 = t0.plusMillis(500)

        smoother.accept(Reading(INSTANT_MPG_PID_ID, 10.0, t0, stale = true), t0)
        val result = smoother.accept(Reading(INSTANT_MPG_PID_ID, 20.0, t1, stale = false), t1)!!

        assertTrue(result.stale)
    }

    private fun reading(
        value: Double,
        timestamp: Instant,
    ) = Reading(id = INSTANT_MPG_PID_ID, value = value, timestamp = timestamp, stale = false)

    private companion object {
        const val TOLERANCE = 0.001
    }
}

/**
 * [InstantMpgDataSource]'s decorator wiring: it multiplexes [InstantMpgCompute] and
 * [InstantMpgSmoother] over the delegate's live `readings`, and is otherwise a pass-through —
 * same shape as `SpeedCorrectionDataSourceTest`/`DisplayUnitDataSourceTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstantMpgDataSourceTest {
    @Test
    fun `the local instant mpg id has no protocol counterpart - it is computed here, not on the wire`() {
        // Unlike SPEED_PID_ID/FUEL_RATE_PID_ID (which pin equal to a ProtocolPidIds constant),
        // INSTANT_MPG_PID_ID is purely local: PidCatalog (the protocol layer's registry) has no
        // entry for it at all, by design - it never goes out over the wire as a PID.
        assertTrue(PidCatalog.byId(INSTANT_MPG_PID_ID) == null)
    }

    @Test
    fun `speed and fuel rate together produce a smoothed instant mpg reading`() =
        runTest {
            val upstream =
                InstantMpgFakeSource(
                    reading(ProtocolPidIds.SPEED, 60.0),
                    reading(ProtocolPidIds.FUEL_RATE, 3.0),
                )
            val source = InstantMpgDataSource(upstream, fixedClock, backgroundScope)
            advanceUntilIdle()

            val mpg = source.readings.value.getValue(INSTANT_MPG_PID_ID)
            // gph = 3.0 / 3.785411784 = 0.792516...; mpg = 60 / 0.792516... = 75.708236...
            assertEquals(75.708236, mpg.value, TOLERANCE)
        }

    @Test
    fun `every other channel passes through untouched`() {
        runTest {
            val coolant = reading(PidIds.COOLANT, 201.2)
            val upstream = InstantMpgFakeSource(coolant)
            val source = InstantMpgDataSource(upstream, fixedClock, backgroundScope)
            advanceUntilIdle()

            assertEquals(coolant, source.readings.value.getValue(PidIds.COOLANT))
        }
    }

    @Test
    fun `no speed and no fuel rate is absence - no instantMpg key at all`() =
        runTest {
            val source = InstantMpgDataSource(InstantMpgFakeSource(), fixedClock, backgroundScope)
            advanceUntilIdle()

            assertNull(source.readings.value[INSTANT_MPG_PID_ID])
        }

    @Test
    fun `fuel rate present but zero (fuel-cut) is absence too`() =
        runTest {
            val upstream =
                InstantMpgFakeSource(
                    reading(ProtocolPidIds.SPEED, 55.0),
                    reading(ProtocolPidIds.FUEL_RATE, 0.0),
                )
            val source = InstantMpgDataSource(upstream, fixedClock, backgroundScope)
            advanceUntilIdle()

            assertNull(source.readings.value[INSTANT_MPG_PID_ID])
        }

    @Test
    fun `start, stop and connection are pass-throughs`() =
        runTest {
            val upstream = InstantMpgFakeSource()
            val source = InstantMpgDataSource(upstream, fixedClock, backgroundScope)

            source.start(PidCatalog.definitions)
            source.stop()
            upstream.setConnection(LinkState.Ready)

            assertEquals(1, upstream.startCallCount)
            assertEquals(1, upstream.stopCallCount)
            assertEquals(PidCatalog.definitions.map { it.id }, upstream.lastRequested.map { it.id })
            assertEquals(LinkState.Ready, source.connection.value)
        }

    private fun reading(
        id: String,
        value: Double,
    ) = Reading(id = id, value = value, timestamp = Instant.EPOCH, stale = false)

    private companion object {
        const val TOLERANCE = 0.001
        val fixedClock: Clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
    }
}

/** Minimal upstream double — `:core:testing` is `demo`-only and must not reach a prod classpath. */
private class InstantMpgFakeSource(
    vararg initial: Reading,
) : VehicleDataSource {
    private val mutableReadings = MutableStateFlow(initial.associateBy(Reading::id))
    override val readings: StateFlow<Map<String, Reading>> = mutableReadings.asStateFlow()

    private val mutableConnection = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val connection: StateFlow<LinkState> = mutableConnection.asStateFlow()

    var startCallCount = 0
        private set
    var stopCallCount = 0
        private set
    var lastRequested: List<PidDefinition> = emptyList()
        private set

    override fun start(pids: List<PidDefinition>) {
        startCallCount++
        lastRequested = pids
    }

    override fun stop() {
        stopCallCount++
    }

    fun setConnection(state: LinkState) {
        mutableConnection.value = state
    }
}
