package com.revel.obdgauge.app.service

import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.Reading
import com.revel.obdgauge.model.VehicleDataSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Plain-JVM tests against a hand-rolled [VehicleDataSource] double (no `:core:testing`
 * dependency — this file lives in `app/src/test`, which must stay flavor-agnostic; see
 * `GaugeSwapPickerTest`'s own hand-rolled double for the same reason).
 *
 * [UnconfinedTestDispatcher]-backed `runTest`/`backgroundScope` so [ConnectionServiceController]'s
 * `scope.launch { collect { ... } }` runs eagerly enough for each `emit()` below to be observed
 * before the next assertion — matches the `combine(...).collect` shape under test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionServiceControllerTest {
    @Test
    fun `start begins polling exactly once and is a no-op on a repeat call`() =
        runTest(UnconfinedTestDispatcher()) {
            val dataSource = RecordingVehicleDataSource()
            val controller = ConnectionServiceController(dataSource, backgroundScope) {}

            controller.start()
            controller.start()

            assertEquals(1, dataSource.startCallCount)
        }

    @Test
    fun `stop tears down the poll loop and is idempotent`() =
        runTest(UnconfinedTestDispatcher()) {
            val dataSource = RecordingVehicleDataSource()
            val controller = ConnectionServiceController(dataSource, backgroundScope) {}
            controller.start()

            controller.stop()
            controller.stop()

            assertEquals(1, dataSource.stopCallCount)
        }

    @Test
    fun `never restarts on the natural pre-connect Disconnected value`() =
        runTest(UnconfinedTestDispatcher()) {
            val dataSource = RecordingVehicleDataSource()
            val controller = ConnectionServiceController(dataSource, backgroundScope) {}

            // dataSource.connection starts (and stays) Disconnected before any Connect step —
            // this must NOT be read as "we were connected and got dropped."
            controller.start()

            assertEquals(1, dataSource.startCallCount)
        }

    @Test
    fun `re-issues start when connection transitions from Ready back to Disconnected`() =
        runTest(UnconfinedTestDispatcher()) {
            val dataSource = RecordingVehicleDataSource()
            val controller = ConnectionServiceController(dataSource, backgroundScope) {}
            controller.start()
            assertEquals(1, dataSource.startCallCount)

            dataSource.setConnection(LinkState.Ready)
            dataSource.setConnection(LinkState.Disconnected)

            // Simulates DashboardViewModel's WhileSubscribed stop() firing once the UI subscription
            // lapses — the controller must resume polling on its own, per its KDoc.
            assertEquals(2, dataSource.startCallCount)
        }

    // B2 MAJOR (round-1 review, reviews/OBD-24-round1.md): the pre-round-2 trigger was
    // `previous != Disconnected`, which counted Scanning as "was connected" — this is the direct
    // regression guard for that mutant (re-broaden `wasReady` back to "not Disconnected" and this
    // test starts failing).
    @Test
    fun `Scanning transitioning to Disconnected does NOT count as was-connected`() =
        runTest(UnconfinedTestDispatcher()) {
            val dataSource = RecordingVehicleDataSource()
            val controller = ConnectionServiceController(dataSource, backgroundScope) {}
            controller.start()

            dataSource.setConnection(LinkState.Scanning)
            dataSource.setConnection(LinkState.Disconnected)

            assertEquals(1, dataSource.startCallCount)
        }

    // B2's measured hazard: a failing-scan source cycling Scanning<->Disconnected produced 30
    // start() calls in 60s against a 2s scan (unbounded, zero backoff) — 3x Android's BLE scan
    // throttle, which then silently blanks scan results and prevents the very reconnect it's
    // trying to force. This reproduces that cycle shape (well past 30 iterations) and pins the
    // fix: with Scanning excluded from `wasReady`, the count must stay bounded at the single
    // initial `start()` no matter how many Scanning/Disconnected cycles follow.
    @Test
    fun `a failing-scan source cycling Scanning to Disconnected does not produce an unbounded restart storm`() =
        runTest(UnconfinedTestDispatcher()) {
            val dataSource = RecordingVehicleDataSource()
            val controller = ConnectionServiceController(dataSource, backgroundScope) {}
            controller.start()

            repeat(FAILING_SCAN_CYCLES) {
                dataSource.setConnection(LinkState.Scanning)
                dataSource.setConnection(LinkState.Disconnected)
            }

            assertEquals(1, dataSource.startCallCount)
        }

    // Same storm shape, but the scan cycle passes through Connecting too (a device was briefly
    // found, then GATT/service-discovery failed before ever reaching Ready) — still must not
    // count as was-connected.
    @Test
    fun `Connecting transitioning to Disconnected does NOT count as was-connected either`() =
        runTest(UnconfinedTestDispatcher()) {
            val dataSource = RecordingVehicleDataSource()
            val controller = ConnectionServiceController(dataSource, backgroundScope) {}
            controller.start()

            dataSource.setConnection(LinkState.Scanning)
            dataSource.setConnection(LinkState.Connecting)
            dataSource.setConnection(LinkState.Disconnected)

            assertEquals(1, dataSource.startCallCount)
        }

    @Test
    fun `does not restart while stopped even if connection flips to Disconnected`() =
        runTest(UnconfinedTestDispatcher()) {
            val dataSource = RecordingVehicleDataSource()
            val controller = ConnectionServiceController(dataSource, backgroundScope) {}
            controller.start()
            dataSource.setConnection(LinkState.Ready)
            controller.stop()

            dataSource.setConnection(LinkState.Disconnected)

            assertEquals(1, dataSource.startCallCount)
        }

    @Test
    fun `notifies the latest coolant reading and connection state on every emission`() =
        runTest(UnconfinedTestDispatcher()) {
            val dataSource = RecordingVehicleDataSource()
            val observed = mutableListOf<ServiceNotificationState>()
            val controller = ConnectionServiceController(dataSource, backgroundScope) { observed.add(it) }

            controller.start()
            dataSource.setConnection(LinkState.Ready)
            dataSource.setReadings(
                mapOf(PidIds.COOLANT to Reading(PidIds.COOLANT, COOLANT_VALUE, Instant.EPOCH, stale = false)),
            )

            assertTrue(observed.isNotEmpty())
            assertEquals("Connected — Coolant 190°F", observed.last().text)
        }

    private companion object {
        const val COOLANT_VALUE = 190.0
        const val FAILING_SCAN_CYCLES = 40
    }
}

/**
 * Counts [start]/[stop] calls; readings/connection are driven directly by the test. `internal`
 * (not `private`) so `ObdConnectionServiceTest` (a different source set, but the same
 * `testDemoDebugUnitTest` compilation) can reuse it directly for its mutation-(d) killer test
 * rather than hand-rolling a second copy.
 */
internal class RecordingVehicleDataSource : VehicleDataSource {
    private val mutableReadings = MutableStateFlow<Map<String, Reading>>(emptyMap())
    override val readings: StateFlow<Map<String, Reading>> = mutableReadings.asStateFlow()

    private val mutableConnection = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val connection: StateFlow<LinkState> = mutableConnection.asStateFlow()

    var startCallCount = 0
        private set
    var stopCallCount = 0
        private set

    override fun start(pids: List<PidDefinition>) {
        startCallCount++
    }

    override fun stop() {
        stopCallCount++
    }

    fun setConnection(state: LinkState) {
        mutableConnection.value = state
    }

    fun setReadings(readings: Map<String, Reading>) {
        mutableReadings.value = readings
    }
}
