package com.revel.obdgauge.app.gauge

import com.revel.obdgauge.app.gauge.grid.GridEngine
import com.revel.obdgauge.app.gauge.grid.GridLayout
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

    // --- OBD-64/68: grid as the single source of truth (per-orientation eager seed + mutations)
    // (OBD-68 round-4 pivot: one persisted GridLayout per column count, not one canonical layout
    // repacked per orientation — see `issues/OBD-67.md`'s round-4 pivot note and
    // `GridLayoutSetTest`'s pure sync-invariant suite, which these ViewModel tests only confirm
    // the wiring/persistence for.)

    @Test
    fun `both required column-count layouts are eagerly seeded from gaugeOrder when absent`() =
        runTest(testDispatcher) {
            val viewModel = seededViewModel()

            val layouts = viewModel.gridLayoutsByColumns.value
            assertEquals(setOf(GRID_CANONICAL_COLUMNS, GRID_PORTRAIT_COLUMNS), layouts.keys)
            val expectedIds = DEFAULT_GAUGE_ORDER.filter { it.visible }.map { it.id }.toSet()
            assertEquals(expectedIds, layouts.getValue(GRID_CANONICAL_COLUMNS).ids.toSet())
            assertEquals(expectedIds, layouts.getValue(GRID_PORTRAIT_COLUMNS).ids.toSet())
        }

    @Test
    fun `the eager seed does not overwrite an already-persisted layout, but fills the missing orientation`() =
        runTest(testDispatcher) {
            val fake = FakeVehicleDataSource(scenario = Scenario.IDLE, scope = this)
            val stored =
                GridEngine.repack(GRID_CANONICAL_COLUMNS, listOf(GridPlacement(PidIds.BOOST, 0, 0, colSpan = 2)))
            val repository =
                InMemorySettingsRepository(AppSettings(gridLayoutsByColumns = mapOf(GRID_CANONICAL_COLUMNS to stored)))
            val viewModel = DashboardViewModel(fake, fixedClock, repository)
            backgroundScope.launch { viewModel.gridLayoutsByColumns.collect {} }
            advanceUntilIdle()

            val layouts = viewModel.gridLayoutsByColumns.value
            assertEquals(stored, layouts.getValue(GRID_CANONICAL_COLUMNS)) // untouched
            assertTrue("portrait must be seeded to fill the gap", GRID_PORTRAIT_COLUMNS in layouts)
        }

    @Test
    fun `addGauge places a previously-unplaced gauge on every stored orientation's layout`() =
        runTest(testDispatcher) {
            val viewModel = seededViewModel()

            viewModel.addGauge(SPEED_PID_ID)
            advanceUntilIdle()

            assertTrue(SPEED_PID_ID in viewModel.canonicalGrid().ids)
            assertTrue(
                SPEED_PID_ID in
                    viewModel.gridLayoutsByColumns.value
                        .getValue(GRID_PORTRAIT_COLUMNS)
                        .ids,
            )
        }

    @Test
    fun `removeGauge drops a tile from every stored orientation's layout`() =
        runTest(testDispatcher) {
            val viewModel = seededViewModel()

            viewModel.removeGauge(PidIds.COOLANT)
            advanceUntilIdle()

            assertFalse(PidIds.COOLANT in viewModel.canonicalGrid().ids)
            assertFalse(
                PidIds.COOLANT in
                    viewModel.gridLayoutsByColumns.value
                        .getValue(GRID_PORTRAIT_COLUMNS)
                        .ids,
            )
        }

    // OBD-67 round-10 device-verified fix: was `resizeGauge(..., colSpan = 2, rowSpan = 2, ...)`,
    // which this test's seeded layout (the 4 core gauges packed edge-to-edge across one row —
    // coolant at col 0, transTemp at col 1) can never legitimately satisfy IN PLACE: growing
    // coolant to 2 columns wide collides with transTemp sitting right next to it. That was a
    // silent assumption baked into the OLD repack-based `GridEngine.resize` (which "succeeded" by
    // reshuffling transTemp/oilTemp/boost out of the way) — exactly the bug `resizeInPlace` fixes
    // (see its own KDoc). Resizing to 1×2 instead grows DOWN into rearrange mode's always-empty
    // row below, which nothing else occupies, so it's a genuinely satisfiable in-place resize.
    // Also now asserts every OTHER gauge in the SAME orientation is untouched — the direct,
    // ViewModel-level pin of the actual regression (this existing test's original form couldn't
    // have caught a "resize reshuffled everyone else" bug, since it never checked anyone but
    // coolant and the untouched-OTHER-orientation case).
    @Test
    fun `resizeGauge changes a tile's span in ONLY the given orientation's layout`() =
        runTest(testDispatcher) {
            val viewModel = seededViewModel()
            val before = viewModel.canonicalGrid()
            val transTempBefore = before.placementFor(PidIds.TRANS_TEMP)!!
            val oilTempBefore = before.placementFor(PidIds.OIL_TEMP)!!
            val boostBefore = before.placementFor(PidIds.BOOST)!!

            viewModel.resizeGauge(PidIds.COOLANT, colSpan = 1, rowSpan = 2, columns = GRID_CANONICAL_COLUMNS)
            advanceUntilIdle()

            val landscape = viewModel.canonicalGrid()
            val coolant = landscape.placementFor(PidIds.COOLANT)!!
            assertEquals(1, coolant.colSpan)
            assertEquals(2, coolant.rowSpan)
            assertEquals(GRID_CANONICAL_COLUMNS, landscape.columns)
            // No repack, no reflow: every other gauge in this SAME orientation stays exactly put.
            assertEquals(transTempBefore, landscape.placementFor(PidIds.TRANS_TEMP))
            assertEquals(oilTempBefore, landscape.placementFor(PidIds.OIL_TEMP))
            assertEquals(boostBefore, landscape.placementFor(PidIds.BOOST))
            // Portrait's own span for the same gauge is untouched — positions/spans are
            // independent per orientation now.
            val portraitCoolant =
                viewModel.gridLayoutsByColumns.value
                    .getValue(
                        GRID_PORTRAIT_COLUMNS,
                    ).placementFor(PidIds.COOLANT)!!
            assertEquals(1, portraitCoolant.colSpan)
            assertEquals(1, portraitCoolant.rowSpan)
        }

    // OBD-67 round-12 (user decision, replacing round-10's rejection behavior — see
    // GridEngine.resizeWithPush's KDoc for the full "silent-but-correct" diagnosis that led here):
    // a resize that WOULD collide now PUSHES the occupant to a free slot instead of rejecting.
    // Growing coolant 2 wide collides with transTemp immediately to its right; transTemp relocates
    // to the next free slot (row 0 fills solid across all 4 columns once coolant claims 2 of
    // them, so transTemp lands at the start of row 1) while oilTemp/boost — never in the way —
    // stay exactly put.
    @Test
    fun `resizeGauge pushes a colliding tile to a free slot instead of rejecting`() =
        runTest(testDispatcher) {
            val viewModel = seededViewModel()
            val oilTempBefore = viewModel.canonicalGrid().placementFor(PidIds.OIL_TEMP)!!
            val boostBefore = viewModel.canonicalGrid().placementFor(PidIds.BOOST)!!

            viewModel.resizeGauge(PidIds.COOLANT, colSpan = 2, rowSpan = 1, columns = GRID_CANONICAL_COLUMNS)
            advanceUntilIdle()

            val landscape = viewModel.canonicalGrid()
            val coolant = landscape.placementFor(PidIds.COOLANT)!!
            assertEquals(0, coolant.col)
            assertEquals(2, coolant.colSpan)
            val transTemp = landscape.placementFor(PidIds.TRANS_TEMP)!!
            assertEquals(0, transTemp.col)
            assertEquals(1, transTemp.row)
            assertEquals(1, transTemp.colSpan) // displaced, not resized — only its position moved
            assertEquals(oilTempBefore, landscape.placementFor(PidIds.OIL_TEMP))
            assertEquals(boostBefore, landscape.placementFor(PidIds.BOOST))
        }

    // OBD-67 round-13 device-verified (user decision, replacing round-12's rejection here — see
    // GridEngine.resizeWithPush's own KDoc for the "shift left" refinement): user's own words —
    // "I can resize any way I want, but only when the gauge is on the LEFT side. If a gauge is in
    // the RIGHT column it only resizes up/down, not side to side" — a right-column tile widening
    // now shifts left to fit rather than doing nothing. transTemp sits at col 1 of 4; growing it
    // to the full 4-column width shifts it to col 0, displacing every other gauge onto a new row.
    @Test
    fun `resizeGauge shifts a right-of-center tile left to fit a wide span, pushing others`() =
        runTest(testDispatcher) {
            val viewModel = seededViewModel()

            viewModel.resizeGauge(PidIds.TRANS_TEMP, colSpan = 4, rowSpan = 1, columns = GRID_CANONICAL_COLUMNS)
            advanceUntilIdle()

            val landscape = viewModel.canonicalGrid()
            val transTemp = landscape.placementFor(PidIds.TRANS_TEMP)!!
            assertEquals(0, transTemp.col) // shifted left: 0 + 4 == the grid's own column count
            assertEquals(4, transTemp.colSpan)
            // Every other core gauge was in the way (row 0 is now solid transTemp) — displaced
            // onto row 1, not resized.
            assertEquals(1, landscape.placementFor(PidIds.COOLANT)!!.row)
            assertEquals(1, landscape.placementFor(PidIds.OIL_TEMP)!!.row)
            assertEquals(1, landscape.placementFor(PidIds.BOOST)!!.row)
        }

    // OBD-67 round-13: the one resize still refused — a span wider than the grid itself, which
    // no shift or push could ever fit. Not reachable via the real size chips (max 2×2), but the
    // engine itself still has to honor this as a real mathematical edge.
    @Test
    fun `resizeGauge still rejects a span wider than the grid itself`() =
        runTest(testDispatcher) {
            val viewModel = seededViewModel()
            val before = viewModel.canonicalGrid().placementFor(PidIds.TRANS_TEMP)!!

            viewModel.resizeGauge(PidIds.TRANS_TEMP, colSpan = 5, rowSpan = 1, columns = GRID_CANONICAL_COLUMNS)
            advanceUntilIdle()

            assertEquals(before, viewModel.canonicalGrid().placementFor(PidIds.TRANS_TEMP))
        }

    // --- OBD-68: moveGauge/addGaugeAt, rearrange mode's freeform drag-drop commit --------------

    @Test
    fun `moveGauge drops the tile at the explicit cell in the given orientation, keeping others put`() =
        runTest(testDispatcher) {
            val viewModel = seededViewModel()
            val before = viewModel.canonicalGrid()
            val movedId = before.ids.last()
            val untouchedId = before.ids.first()
            val untouchedBefore = before.placementFor(untouchedId)!!

            // well below the seeded row — always free
            viewModel.moveGauge(movedId, col = 0, row = 5, columns = GRID_CANONICAL_COLUMNS)
            advanceUntilIdle()

            val grid = viewModel.canonicalGrid()
            val moved = grid.placementFor(movedId)!!
            assertEquals(0, moved.col)
            assertEquals(5, moved.row)
            assertEquals(untouchedBefore, grid.placementFor(untouchedId)) // no repack, no reflow
            assertEquals(GRID_CANONICAL_COLUMNS, grid.columns)
        }

    @Test
    fun `moveGauge only touches the given orientation's layout, not the other's`() =
        runTest(testDispatcher) {
            val viewModel = seededViewModel()
            val portraitBefore = viewModel.gridLayoutsByColumns.value.getValue(GRID_PORTRAIT_COLUMNS)
            val movedId = viewModel.canonicalGrid().ids.first()

            viewModel.moveGauge(movedId, col = 0, row = 9, columns = GRID_CANONICAL_COLUMNS)
            advanceUntilIdle()

            assertEquals(portraitBefore, viewModel.gridLayoutsByColumns.value.getValue(GRID_PORTRAIT_COLUMNS))
        }

    @Test
    fun `moveGauge is a no-op for an absent id`() =
        runTest(testDispatcher) {
            val viewModel = seededViewModel()
            val before = viewModel.canonicalGrid()

            viewModel.moveGauge("ghost", col = 0, row = 0, columns = GRID_CANONICAL_COLUMNS)
            advanceUntilIdle()

            assertEquals(before, viewModel.canonicalGrid())
        }

    @Test
    fun `moveGauge swaps two same-footprint tiles when the target cell is occupied`() =
        runTest(testDispatcher) {
            val viewModel = seededViewModel()
            val before = viewModel.canonicalGrid()
            val (draggedId, targetId) = before.ids[0] to before.ids[1]
            val draggedOrigin = before.placementFor(draggedId)!!.let { it.col to it.row }
            val targetOrigin = before.placementFor(targetId)!!.let { it.col to it.row }

            viewModel.moveGauge(
                draggedId,
                col = targetOrigin.first,
                row = targetOrigin.second,
                columns = GRID_CANONICAL_COLUMNS,
            )
            advanceUntilIdle()

            val grid = viewModel.canonicalGrid()
            assertEquals(targetOrigin, grid.placementFor(draggedId)!!.let { it.col to it.row })
            assertEquals(draggedOrigin, grid.placementFor(targetId)!!.let { it.col to it.row })
        }

    @Test
    fun `moveGauge snaps back (no-op) when the drop doesn't fit or clean-swap`() =
        runTest(testDispatcher) {
            val viewModel = seededViewModel()
            val before = viewModel.canonicalGrid()
            val movedId = before.ids.first()

            // out of bounds
            viewModel.moveGauge(movedId, col = GRID_CANONICAL_COLUMNS, row = 0, columns = GRID_CANONICAL_COLUMNS)
            advanceUntilIdle()

            assertEquals(before, viewModel.canonicalGrid())
        }

    @Test
    fun `addGaugeAt places at the explicit cell and syncs the gauge into the other orientation`() =
        runTest(testDispatcher) {
            val viewModel = seededViewModel()

            viewModel.addGaugeAt(SPEED_PID_ID, col = 1, row = 5, columns = GRID_CANONICAL_COLUMNS)
            advanceUntilIdle()

            val landscape = viewModel.canonicalGrid()
            val speed = landscape.placementFor(SPEED_PID_ID)!!
            assertEquals(1, speed.col)
            assertEquals(5, speed.row)
            // The invariant: portrait must also carry the new gauge, even though it got no chosen
            // cell there (addInFirstFreeSlot picks one).
            assertTrue(
                SPEED_PID_ID in
                    viewModel.gridLayoutsByColumns.value
                        .getValue(GRID_PORTRAIT_COLUMNS)
                        .ids,
            )
        }

    @Test
    fun `addGaugeAt is a no-op when the gauge is already placed or the cell is taken`() =
        runTest(testDispatcher) {
            val viewModel = seededViewModel()
            val before = viewModel.canonicalGrid()
            val alreadyPlaced = before.ids.first()

            viewModel.addGaugeAt(alreadyPlaced, col = 1, row = 5, columns = GRID_CANONICAL_COLUMNS)
            advanceUntilIdle()
            assertEquals(before, viewModel.canonicalGrid())

            viewModel.addGaugeAt(
                SPEED_PID_ID,
                col = before.placementFor(alreadyPlaced)!!.col,
                row = 0,
                columns = GRID_CANONICAL_COLUMNS,
            )
            advanceUntilIdle()
            assertEquals(before, viewModel.canonicalGrid())
        }

    @Test
    fun `swapGauge renames the placement in place on every stored orientation's layout`() =
        runTest(testDispatcher) {
            val viewModel = seededViewModel()
            val beforeLandscape = viewModel.canonicalGrid().placementFor(PidIds.COOLANT)!!
            val beforePortrait =
                viewModel.gridLayoutsByColumns.value
                    .getValue(
                        GRID_PORTRAIT_COLUMNS,
                    ).placementFor(PidIds.COOLANT)!!

            viewModel.swapGauge(PidIds.COOLANT, PidIds.RPM)
            advanceUntilIdle()

            val landscape = viewModel.canonicalGrid()
            val portrait = viewModel.gridLayoutsByColumns.value.getValue(GRID_PORTRAIT_COLUMNS)
            assertNull(landscape.placementFor(PidIds.COOLANT))
            assertNull(portrait.placementFor(PidIds.COOLANT))
            // Same cell + span PER ORIENTATION, only the id changed — each layout keeps its own
            // geometry, the sync invariant is about the id set, not positions.
            assertEquals(beforeLandscape.copy(id = PidIds.RPM), landscape.placementFor(PidIds.RPM))
            assertEquals(beforePortrait.copy(id = PidIds.RPM), portrait.placementFor(PidIds.RPM))
        }

    /** A VM whose eager-seed has run and whose `gridLayoutsByColumns` flow has a live collector. */
    private fun TestScope.seededViewModel(): DashboardViewModel {
        val fake = FakeVehicleDataSource(scenario = Scenario.IDLE, scope = this)
        val viewModel = DashboardViewModel(fake, fixedClock, InMemorySettingsRepository())
        backgroundScope.launch { viewModel.gridLayoutsByColumns.collect {} }
        advanceUntilIdle()
        return viewModel
    }

    private fun DashboardViewModel.canonicalGrid(): GridLayout =
        gridLayoutsByColumns.value.getValue(GRID_CANONICAL_COLUMNS)

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
