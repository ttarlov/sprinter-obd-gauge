package com.revel.obdgauge.app.datasource

import com.revel.obdgauge.app.gauge.SPEED_PID_ID
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * OBD-61's correction seam (prod): multiplies **only** the speed channel by the GPS-learned
 * factor, everything else untouched, degrading to identity at factor 1.0.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeedCorrectionDataSourceTest {
    @Test
    fun `the local speed id matches the protocol layer's ProtocolPidIds_SPEED`() {
        // The app declares SPEED_PID_ID locally (`:core:protocol` is prod-only, and `src/main`
        // cannot import it) — pin them equal so the two can never silently drift.
        assertEquals(ProtocolPidIds.SPEED, SPEED_PID_ID)
    }

    @Test
    fun `the speed channel is multiplied by the factor`() =
        runTest {
            val upstream = SpeedFakeSource(reading(SPEED_PID_ID, 100.0))
            val source = SpeedCorrectionDataSource(upstream, MutableStateFlow(1.1), backgroundScope)
            advanceUntilIdle()

            assertEquals(
                110.0,
                source.readings.value
                    .getValue(SPEED_PID_ID)
                    .value,
                TOLERANCE,
            )
        }

    @Test
    fun `factor 1_0 is a true identity - speed passes through byte for byte`() =
        runTest {
            val speed = reading(SPEED_PID_ID, 96.5)
            val upstream = SpeedFakeSource(speed)
            val source = SpeedCorrectionDataSource(upstream, MutableStateFlow(1.0), backgroundScope)
            advanceUntilIdle()

            assertEquals(speed, source.readings.value.getValue(SPEED_PID_ID))
        }

    @Test
    fun `non-speed channels are never touched, whatever the factor`() =
        runTest {
            val coolant = reading(PidIds.COOLANT, 201.2)
            val rpm = reading(PidIds.RPM, 2500.0)
            val upstream = SpeedFakeSource(coolant, rpm)
            val source = SpeedCorrectionDataSource(upstream, MutableStateFlow(1.1), backgroundScope)
            advanceUntilIdle()

            assertEquals(coolant, source.readings.value.getValue(PidIds.COOLANT))
            assertEquals(rpm, source.readings.value.getValue(PidIds.RPM))
        }

    @Test
    fun `an absent speed channel stays absent - nothing is materialised`() =
        runTest {
            val source = SpeedCorrectionDataSource(SpeedFakeSource(), MutableStateFlow(1.1), backgroundScope)
            advanceUntilIdle()

            assertTrue(source.readings.value.isEmpty())
            assertNull(source.readings.value[SPEED_PID_ID])
        }

    @Test
    fun `a factor change re-emits a freshly corrected speed`() =
        runTest(UnconfinedTestDispatcher()) {
            // Unconfined so the stateIn combine collector starts eagerly and `.value` tracks live
            // emissions (the app collects via DashboardViewModel's WhileSubscribed subscription).
            val factor = MutableStateFlow(1.0)
            val upstream = SpeedFakeSource(reading(SPEED_PID_ID, 100.0))
            val source = SpeedCorrectionDataSource(upstream, factor, backgroundScope)
            assertEquals(
                100.0,
                source.readings.value
                    .getValue(SPEED_PID_ID)
                    .value,
                TOLERANCE,
            )

            factor.value = 1.08
            assertEquals(
                108.0,
                source.readings.value
                    .getValue(SPEED_PID_ID)
                    .value,
                TOLERANCE,
            )
        }

    @Test
    fun `start, stop and connection are pass-throughs`() =
        runTest {
            val upstream = SpeedFakeSource()
            val source = SpeedCorrectionDataSource(upstream, MutableStateFlow(1.1), backgroundScope)

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
        const val TOLERANCE = 1e-9
    }
}

/** Minimal upstream double — `:core:testing` is `demo`-only and must not reach a prod classpath. */
private class SpeedFakeSource(
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
