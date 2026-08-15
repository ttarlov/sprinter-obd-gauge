package com.revel.obdgauge.app.gauge

import com.revel.obdgauge.app.gauge.grid.GridEngine
import com.revel.obdgauge.app.gauge.grid.GridPlacement
import com.revel.obdgauge.app.service.ConnectionServiceController
import com.revel.obdgauge.app.service.PollKeepAlive
import com.revel.obdgauge.app.settings.AppSettings
import com.revel.obdgauge.app.settings.DEFAULT_GAUGE_ORDER
import com.revel.obdgauge.app.settings.SettingsRepository
import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.VehicleDataSource
import com.revel.obdgauge.testing.datasource.FakeVehicleDataSource
import com.revel.obdgauge.testing.datasource.Scenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Plain-JVM ViewModel tests: no Robolectric needed since [DashboardViewModel] and
 * [FakeVehicleDataSource] are both pure Kotlin against `viewModelScope`'s test-dispatcher
 * scheduler. Compose rendering assertions live in `DashboardScreenTest` instead.
 *
 * `runTest(testDispatcher)` reuses the exact dispatcher installed via [Dispatchers.setMain]
 * so `viewModelScope` (which resolves to `Dispatchers.Main.immediate`) and this test's
 * `backgroundScope` share one [kotlinx.coroutines.test.TestCoroutineScheduler] — required for
 * `advanceUntilIdle()` to actually drain both the fake's replay job and the ViewModel's
 * `combine()`/`stateIn()` pipeline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val fixedClock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `IDLE scenario tail renders green values with no stale treatment`() =
        withDashboard(Scenario.IDLE) { state ->
            assertEquals(ThresholdZone.GREEN, state.coolant.zone)
            assertEquals(ThresholdZone.GREEN, state.oilTemp.zone)
            assertEquals(ThresholdZone.GREEN, state.transTemp.zone)
            assertEquals("190°F", state.coolant.valueText)
            assertEquals(false, state.coolant.isStale)
            assertEquals(null, state.coolant.staleText)
        }

    @Test
    fun `TOWN_HEAT_SOAK tail zones follow the OBD-66 seed thresholds`() =
        withDashboard(Scenario.TOWN_HEAT_SOAK) { state ->
            // OBD-66 seeds: coolant red≥225, oil amber≥245, trans amber≥215. So the heat-soak tail
            // (coolant 225 / oil 240 / trans 215) is RED / GREEN / AMBER respectively.
            assertEquals(ThresholdZone.RED, state.coolant.zone)
            assertEquals(ThresholdZone.GREEN, state.oilTemp.zone)
            assertEquals(ThresholdZone.AMBER, state.transTemp.zone)
            assertEquals("225°F", state.coolant.valueText)
            assertEquals("240°F", state.oilTemp.valueText)
            assertEquals("215°F", state.transTemp.valueText)
        }

    @Test
    fun `empty readings map renders placeholder text and neutral zone`() {
        val state = toDashboardUiState(emptyMap(), LinkState.Disconnected, fixedClock.instant())

        assertEquals(NO_READING_TEXT, state.coolant.valueText)
        assertEquals(ThresholdZone.NEUTRAL, state.coolant.zone)
        assertEquals(false, state.coolant.isStale)
        assertEquals(null, state.coolant.staleText)
        assertEquals(NO_READING_TEXT, state.boost.valueText)
        assertEquals(ThresholdZone.NEUTRAL, state.boost.zone)
    }

    @Test
    fun `producer starts only once uiState has a collector and stops after the subscription timeout`() =
        runTest(testDispatcher) {
            val fake = FakeVehicleDataSource(scenario = Scenario.IDLE, scope = this)
            val recording = RecordingVehicleDataSource(fake)
            val viewModel = DashboardViewModel(recording, fixedClock, InMemorySettingsRepository())

            // No collector yet: the init-block-eager `start()` this review flagged (M7) must
            // be gone.
            assertEquals(0, recording.startCallCount)

            val collectJob = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            assertEquals(1, recording.startCallCount)
            assertEquals(0, recording.stopCallCount)

            // WhileSubscribed(5_000) should keep the producer running for a while after the
            // last collector disappears, then stop it.
            collectJob.cancel()
            advanceUntilIdle()
            assertEquals(0, recording.stopCallCount)

            advanceTimeBy(SUBSCRIPTION_TIMEOUT_MILLIS + 1_000)
            advanceUntilIdle()
            assertEquals(1, recording.stopCallCount)
        }

    /**
     * OBD-25's half of the self-heal ownership resolution, from the ViewModel side.
     *
     * With the foreground service holding the keep-alive lease, the UI-gated teardown must NOT
     * stop the shared data source: the screen going off on a dash mount is precisely when
     * `ObdConnectionService` (and the wake lock it holds) needs polling to continue. Before
     * OBD-25 this stop always fired, and the service papered over it by re-issuing `start()` on
     * an observed `Disconnected` — the trigger `reviews/OBD-24-round1.md` measured at 30
     * `start()`/60 s once a real reconnect policy sits behind the same source.
     */
    @Test
    fun `the UI-gated stop defers to the service's keep-alive lease`() =
        runTest(testDispatcher) {
            val fake = FakeVehicleDataSource(scenario = Scenario.IDLE, scope = this)
            val recording = RecordingVehicleDataSource(fake)
            val keepAlive = PollKeepAlive()
            val viewModel = DashboardViewModel(recording, fixedClock, InMemorySettingsRepository(), keepAlive)
            val controller = ConnectionServiceController(recording, backgroundScope, keepAlive) {}
            controller.start()

            val collectJob = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            collectJob.cancel()
            advanceTimeBy(SUBSCRIPTION_TIMEOUT_MILLIS + 1_000)
            advanceUntilIdle()

            // The subscription lapsed and WhileSubscribed fired — but the service is running, so
            // the source was left polling. Remove the keep-alive check and this reads 1.
            assertEquals(0, recording.stopCallCount)

            // The service ending the session is what actually stops it — one owner, one stop.
            controller.stop()
            assertEquals(1, recording.stopCallCount)
        }

    /** The lease is opt-in: with no service holding one, the pre-OBD-25 teardown is unchanged. */
    @Test
    fun `without a keep-alive lease the UI-gated stop still fires`() =
        runTest(testDispatcher) {
            val fake = FakeVehicleDataSource(scenario = Scenario.IDLE, scope = this)
            val recording = RecordingVehicleDataSource(fake)
            val viewModel = DashboardViewModel(recording, fixedClock, InMemorySettingsRepository(), PollKeepAlive())

            val collectJob = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            collectJob.cancel()
            advanceTimeBy(SUBSCRIPTION_TIMEOUT_MILLIS + 1_000)
            advanceUntilIdle()

            assertEquals(1, recording.stopCallCount)
        }

    @Test
    fun `swapGauge replaces the id at oldId's position, keeps visibility, and the dashboard shows real rpm data`() =
        runTest(testDispatcher) {
            val fake = FakeVehicleDataSource(scenario = Scenario.IDLE, scope = this)
            val viewModel = DashboardViewModel(fake, fixedClock, InMemorySettingsRepository())
            backgroundScope.launch { viewModel.uiState.collect {} }
            backgroundScope.launch { viewModel.gaugeOrder.collect {} }
            advanceUntilIdle()

            viewModel.swapGauge(PidIds.COOLANT, PidIds.RPM)
            advanceUntilIdle()

            assertEquals(
                PidIds.RPM,
                viewModel.gaugeOrder.value
                    .first()
                    .id,
            )
            assertEquals(
                true,
                viewModel.gaugeOrder.value
                    .first()
                    .visible,
            )
            // GAUGE_CATALOG (not just DASHBOARD_PIDS) must reach dataSource.start() — otherwise
            // FakeVehicleDataSource would never emit an rpm reading at all and this would still
            // read the NO_READING_TEXT placeholder.
            val rpmTile = viewModel.uiState.value.tileFor(PidIds.RPM)
            assertEquals(false, rpmTile?.valueText == NO_READING_TEXT)
        }

    @Test
    fun `swapGauge is a no-op when oldId equals newId`() =
        runTest(testDispatcher) {
            val fake = FakeVehicleDataSource(scenario = Scenario.IDLE, scope = this)
            val viewModel = DashboardViewModel(fake, fixedClock, InMemorySettingsRepository())
            backgroundScope.launch { viewModel.uiState.collect {} }
            backgroundScope.launch { viewModel.gaugeOrder.collect {} }
            advanceUntilIdle()
            val before = viewModel.gaugeOrder.value

            viewModel.swapGauge(PidIds.COOLANT, PidIds.COOLANT)
            advanceUntilIdle()

            assertEquals(before, viewModel.gaugeOrder.value)
        }

    // --- OBD-64: grid as the single source of truth (eager seed + mutations) -----------------

    @Test
    fun `gridLayout is eagerly seeded from gaugeOrder at the canonical 4 columns when absent`() =
        runTest(testDispatcher) {
            val viewModel = seededViewModel()

            val grid = viewModel.gridLayout.value!!
            assertEquals(GRID_CANONICAL_COLUMNS, grid.columns)
            assertEquals(DEFAULT_GAUGE_ORDER.filter { it.visible }.map { it.id }, grid.ids)
        }

    @Test
    fun `the eager seed does not overwrite an already-persisted grid`() =
        runTest(testDispatcher) {
            val fake = FakeVehicleDataSource(scenario = Scenario.IDLE, scope = this)
            val stored =
                GridEngine.repack(GRID_CANONICAL_COLUMNS, listOf(GridPlacement(PidIds.BOOST, 0, 0, colSpan = 2)))
            val repository = InMemorySettingsRepository(AppSettings(gridLayout = stored))
            val viewModel = DashboardViewModel(fake, fixedClock, repository)
            backgroundScope.launch { viewModel.gridLayout.collect {} }
            advanceUntilIdle()

            assertEquals(stored, viewModel.gridLayout.value)
        }

    @Test
    fun `addGauge places a previously-unplaced gauge on the persisted grid`() =
        runTest(testDispatcher) {
            val viewModel = seededViewModel()

            viewModel.addGauge(SPEED_PID_ID)
            advanceUntilIdle()

            assertTrue(SPEED_PID_ID in viewModel.gridLayout.value!!.ids)
        }

    @Test
    fun `removeGauge drops a tile from the persisted grid`() =
        runTest(testDispatcher) {
            val viewModel = seededViewModel()

            viewModel.removeGauge(PidIds.COOLANT)
            advanceUntilIdle()

            assertFalse(PidIds.COOLANT in viewModel.gridLayout.value!!.ids)
        }

    @Test
    fun `resizeGauge changes a tile's span and keeps the canonical column count`() =
        runTest(testDispatcher) {
            val viewModel = seededViewModel()

            viewModel.resizeGauge(PidIds.COOLANT, colSpan = 2, rowSpan = 2)
            advanceUntilIdle()

            val grid = viewModel.gridLayout.value!!
            val coolant = grid.placementFor(PidIds.COOLANT)!!
            assertEquals(2, coolant.colSpan)
            assertEquals(2, coolant.rowSpan)
            assertEquals(GRID_CANONICAL_COLUMNS, grid.columns)
        }

    @Test
    fun `swapGauge renames the placement in place on the persisted grid`() =
        runTest(testDispatcher) {
            val viewModel = seededViewModel()
            val before = viewModel.gridLayout.value!!.placementFor(PidIds.COOLANT)!!

            viewModel.swapGauge(PidIds.COOLANT, PidIds.RPM)
            advanceUntilIdle()

            val grid = viewModel.gridLayout.value!!
            assertNull(grid.placementFor(PidIds.COOLANT))
            // Same cell + span, only the id changed.
            assertEquals(before.copy(id = PidIds.RPM), grid.placementFor(PidIds.RPM))
        }

    /** A VM whose eager-seed has run and whose `gridLayout` flow has a live collector. */
    private fun TestScope.seededViewModel(): DashboardViewModel {
        val fake = FakeVehicleDataSource(scenario = Scenario.IDLE, scope = this)
        val viewModel = DashboardViewModel(fake, fixedClock, InMemorySettingsRepository())
        backgroundScope.launch { viewModel.gridLayout.collect {} }
        advanceUntilIdle()
        return viewModel
    }

    private fun withDashboard(
        scenario: Scenario,
        assertions: (DashboardUiState) -> Unit,
    ) = runTest(testDispatcher) {
        // The fake's replay job runs on `this` (the runTest TestScope), not `backgroundScope`:
        // a `backgroundScope` coroutine parked in `delay()` was observed not to resume under
        // `advanceUntilIdle()` in this coroutines-test version. The replay script is finite, so
        // waiting on `this` never hangs the test.
        val dataSource = FakeVehicleDataSource(scenario = scenario, scope = this)
        val viewModel = DashboardViewModel(dataSource, fixedClock, InMemorySettingsRepository())
        // The collector itself never completes (StateFlow.collect runs forever), so it must be
        // on `backgroundScope` to avoid runTest failing with "test finished but a coroutine is
        // still active." Its suspension is a direct Flow emission handoff, not a scheduled
        // delay, so it isn't subject to the same bug as the replay job above.
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        assertions(viewModel.uiState.value)
    }

    private companion object {
        // Mirrors DashboardViewModel's private STOP_TIMEOUT_MILLIS (SharingStarted.WhileSubscribed).
        const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * Counts [start]/[stop] calls so [DashboardViewModelTest] can assert *when* the producer is
 * driven, not just what it eventually emits (OBD-10 review M7).
 */
private class RecordingVehicleDataSource(
    private val delegate: VehicleDataSource,
) : VehicleDataSource by delegate {
    var startCallCount = 0
        private set
    var stopCallCount = 0
        private set

    override fun start(pids: List<PidDefinition>) {
        startCallCount++
        delegate.start(pids)
    }

    override fun stop() {
        stopCallCount++
        delegate.stop()
    }
}

/**
 * Trivial [SettingsRepository] double: no DataStore/disk, just the defaults, since these tests
 * exercise reading/formatting scenario data, not OBD-21's settings persistence itself (see
 * `SettingsRepositoryTest` and the live-recolor test for that).
 */
private class InMemorySettingsRepository(
    initial: AppSettings = AppSettings(),
) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings = state

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        state.value = transform(state.value)
    }
}
