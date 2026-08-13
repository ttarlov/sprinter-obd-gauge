package com.revel.obdgauge.app.service

import com.revel.obdgauge.model.LinkError
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
import org.junit.Assert.assertFalse
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

    // ---- OBD-25 ownership: the SOURCE restarts on the link's success edge, never on failure ----

    // The OBD-24 hazard statement's exact shape (reviews/OBD-24-round1.md, reproduced into
    // issues/OBD-25.md): the deleted self-heal fired here, on a fall from Ready to a clean
    // Disconnected. A reconnect policy that parks at Disconnected between attempts had its
    // backoff defeated every cycle by that trigger. Nothing restarts on this edge any more —
    // re-add a `Disconnected` trigger of any shape and this test fails.
    @Test
    fun `a drop from Ready to Disconnected does NOT restart the source`() =
        runTest(UnconfinedTestDispatcher()) {
            val dataSource = RecordingVehicleDataSource()
            val controller = ConnectionServiceController(dataSource, backgroundScope) {}
            controller.start()
            dataSource.setConnection(LinkState.Ready)
            val whileReady = dataSource.startCallCount

            dataSource.setConnection(LinkState.Disconnected)

            // The drop itself contributes nothing. Pre-OBD-25 this edge was the self-heal's
            // trigger and the count went up by one here, every single time the link fell.
            assertEquals(whileReady, dataSource.startCallCount)
        }

    // The other half of the same edge: the link coming (back) up IS a restart trigger, because
    // RealVehicleDataSource's poll loop parks on a link drop and only resumes when its owner
    // calls start() again on a live link (its KDoc, "On a link drop the loop parks").
    @Test
    fun `the source restarts exactly once when the link reaches Ready`() =
        runTest(UnconfinedTestDispatcher()) {
            val dataSource = RecordingVehicleDataSource()
            val controller = ConnectionServiceController(dataSource, backgroundScope) {}
            controller.start()

            dataSource.setConnection(LinkState.Scanning)
            dataSource.setConnection(LinkState.Connecting)
            dataSource.setConnection(LinkState.Ready)

            assertEquals(2, dataSource.startCallCount)
        }

    // Ready is an *edge*, not a level: a readings emission while already Ready (every poll cycle,
    // twice a second on the van) must not re-enter the session. Without the `!wasReady` guard
    // this would restart the ELM327 init sequence on every published reading.
    @Test
    fun `staying Ready across many readings restarts nothing`() =
        runTest(UnconfinedTestDispatcher()) {
            val dataSource = RecordingVehicleDataSource()
            val controller = ConnectionServiceController(dataSource, backgroundScope) {}
            controller.start()
            dataSource.setConnection(LinkState.Ready)

            repeat(FAILING_SCAN_CYCLES) { tick ->
                dataSource.setReadings(
                    mapOf(PidIds.COOLANT to Reading(PidIds.COOLANT, COOLANT_VALUE + tick, Instant.EPOCH, false)),
                )
            }

            assertEquals(2, dataSource.startCallCount)
        }

    /**
     * **The storm regression test.** This is the one the OBD-24 review's measurement demands:
     * 30 `start()` calls in 60 s against a 2 s failing scan, i.e. 15 scans/30 s vs Android's ~5,
     * at which point the OS silently blanks scan results and the storm prevents the reconnect it
     * is forcing.
     *
     * The cycle below is `:core:ble`'s reconnect machine losing, forty times over, in every shape
     * it can lose in: a sweep that finds nothing (`Scanning → Error`), a handshake that fails
     * (`Connecting → Error`), a policy parked between attempts (`Error → Disconnected`), and the
     * `Disconnected` the old trigger fired on. `LinkState` has no `Reconnecting` case — the whole
     * backoff is spent in `Error(cause)` (see `BleObdLink`'s KDoc) — so this genuinely is what a
     * backing-off link looks like from here.
     *
     * The controller must not have moved: **one** `start()`, the one [start] itself issued. Any
     * failure-driven restart trigger, bounded or not, makes this fail — which is what makes the
     * 30-per-minute regression unreintroducible rather than merely fixed.
     */
    @Test
    fun `a link cycling through the whole reconnect-failure shape never fights the backoff`() =
        runTest(UnconfinedTestDispatcher()) {
            val dataSource = RecordingVehicleDataSource()
            val controller = ConnectionServiceController(dataSource, backgroundScope) {}
            controller.start()

            repeat(FAILING_SCAN_CYCLES) {
                dataSource.setConnection(LinkState.Scanning)
                dataSource.setConnection(LinkState.Error(LinkError.DeviceNotFound))
                dataSource.setConnection(LinkState.Connecting)
                dataSource.setConnection(LinkState.Error(LinkError.Timeout))
                dataSource.setConnection(LinkState.Disconnected)
            }

            assertEquals(RESTARTS_ON_READY_ONLY, dataSource.startCallCount)
        }

    // The full van story: connected, dropped mid-drive, the machine retries and fails a few
    // times, then succeeds. Exactly one restart — booked by the success, not by any of the
    // failures — so the poll loop that parked on the drop comes back and nothing else does.
    @Test
    fun `a drop, several failed retries, then a successful reconnect restarts the source once`() =
        runTest(UnconfinedTestDispatcher()) {
            val dataSource = RecordingVehicleDataSource()
            val controller = ConnectionServiceController(dataSource, backgroundScope) {}
            controller.start()
            dataSource.setConnection(LinkState.Ready)
            val afterFirstReady = dataSource.startCallCount

            dataSource.setConnection(LinkState.Error(LinkError.Gatt(GATT_DROP_CODE)))
            repeat(RETRY_ATTEMPTS) {
                dataSource.setConnection(LinkState.Scanning)
                dataSource.setConnection(LinkState.Error(LinkError.DeviceNotFound))
            }
            assertEquals(afterFirstReady, dataSource.startCallCount)

            dataSource.setConnection(LinkState.Connecting)
            dataSource.setConnection(LinkState.Ready)

            assertEquals(afterFirstReady + 1, dataSource.startCallCount)
        }

    @Test
    fun `does not restart while stopped even if the link comes back Ready`() =
        runTest(UnconfinedTestDispatcher()) {
            val dataSource = RecordingVehicleDataSource()
            val controller = ConnectionServiceController(dataSource, backgroundScope) {}
            controller.start()
            controller.stop()

            dataSource.setConnection(LinkState.Ready)

            assertEquals(1, dataSource.startCallCount)
        }

    // ---- OBD-25: the keep-alive lease, the other half of the ownership split ----

    @Test
    fun `start publishes the keep-alive intent and stop withdraws it`() =
        runTest(UnconfinedTestDispatcher()) {
            val keepAlive = PollKeepAlive()
            val controller = ConnectionServiceController(RecordingVehicleDataSource(), backgroundScope, keepAlive) {}

            assertFalse(keepAlive.active.value)
            controller.start()
            assertTrue(keepAlive.active.value)
            controller.stop()
            assertFalse(keepAlive.active.value)
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
        const val RETRY_ATTEMPTS = 5
        const val GATT_DROP_CODE = 133

        /** The initial [ConnectionServiceController.start] and nothing else. */
        const val RESTARTS_ON_READY_ONLY = 1
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
