package com.revel.obdgauge.app.gauge

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.width
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.revel.obdgauge.app.settings.AppSettings
import com.revel.obdgauge.app.settings.DEFAULT_GAUGE_ORDER
import com.revel.obdgauge.app.settings.DataStoreSettingsRepository
import com.revel.obdgauge.app.settings.GaugeOrderEntry
import com.revel.obdgauge.app.settings.SettingsRepository
import com.revel.obdgauge.app.ui.theme.ObdGaugeTheme
import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.Reading
import com.revel.obdgauge.model.VehicleDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * OBD-65 (in-tile pager): long-pressing a tile turns its content into a [SwapPager] — a
 * [androidx.compose.foundation.pager.HorizontalPager] filling the tile's own bounds, page 0 the
 * current gauge, following pages the swap candidates. Swipe (device-only — Robolectric can't drive
 * fling reliably) to flip through them; tap a candidate page to swap it in, tap page 0 / the scrim
 * to dismiss. These tests pin the STRUCTURE (a pager exists, its pages are the right gauges, page
 * taps select/dismiss and persist) and reach candidate pages deterministically via
 * `performScrollToIndex` (the pager's own scroll semantics) rather than a synthetic swipe.
 *
 * `DEFAULT_GAUGE_ORDER` (all four core gauges placed) is used throughout, so every picker's pages
 * are exactly `[current, rpm, speed]` — the current gauge plus the two catalog-only swap-in
 * candidates.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w800dp-h360dp-land")
class GaugeSwapPickerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())
    private val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `long-press turns that tile only into a swap pager`() {
        setDashboard(newViewModel())

        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        // The picked tile is now a pager; its normal value node is gone (replaced by pages).
        composeTestRule.onNodeWithTag("gauge-swap-pager-coolant").assertExists()
        composeTestRule.onNodeWithTag("gauge-coolant-value").assertDoesNotExist()
        // The rest of the dashboard stays live and untouched — no pager anywhere else.
        composeTestRule.onNodeWithTag("gauge-transTemp-value").assertTextEquals(TRANS_TEXT)
        composeTestRule.onNodeWithTag("gauge-oilTemp-value").assertTextEquals(OIL_TEXT)
        composeTestRule.onNodeWithTag("gauge-boost-value").assertTextEquals(BOOST_TEXT)
        composeTestRule.onNodeWithTag("gauge-swap-pager-transTemp").assertDoesNotExist()
    }

    @Test
    fun `pager opens on the current gauge and is horizontally scrollable`() {
        setDashboard(newViewModel())

        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        // Page 0 is the current gauge, showing a live value — "feels alive, not a menu".
        composeTestRule.onNodeWithTag("gauge-swap-page-coolant").assertExists()
        composeTestRule.onNodeWithTag("gauge-swap-page-coolant-value").assertTextEquals(COOLANT_TEXT)
        // The pager is a real horizontally-scrollable surface (the fix for the drag being eaten).
        composeTestRule
            .onNodeWithTag("gauge-swap-pager-coolant")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
    }

    @Test
    fun `the centered card is a fraction of the frame so neighbours peek`() {
        setDashboard(newViewModel())

        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        // The centered card (~75% per SWAP_CARD_FRACTION) is clearly narrower than the pager/frame
        // it sits in — the remaining width is the left/right peek where neighbour cards show.
        val pagerWidth = composeTestRule.onNodeWithTag("gauge-swap-pager-coolant").getBoundsInRoot().width
        val cardWidth = composeTestRule.onNodeWithTag("gauge-swap-page-coolant").getBoundsInRoot().width
        assertTrue(
            "card ($cardWidth) should be narrower than the pager ($pagerWidth) so neighbours peek",
            cardWidth < pagerWidth,
        )
        assertTrue(
            "card ($cardWidth) should still be the majority of the pager ($pagerWidth), not tiny",
            cardWidth > pagerWidth / 2,
        )
    }

    @Test
    fun `pager pages are the current gauge plus catalog candidates, excluding gauges placed elsewhere`() {
        setDashboard(newViewModel())

        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        // Candidates (rpm, speed) are reachable pages with live values.
        composeTestRule.onNodeWithTag("gauge-swap-pager-coolant").performScrollToIndex(RPM_PAGE)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-swap-page-rpm").assertExists()
        composeTestRule.onNodeWithTag("gauge-swap-page-rpm-value").assertTextEquals(RPM_TEXT)

        composeTestRule.onNodeWithTag("gauge-swap-pager-coolant").performScrollToIndex(SPEED_PAGE)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-swap-page-$SPEED_PID_ID").assertExists()

        // Gauges already placed on OTHER tiles are NOT offered — they stay on their own tiles.
        composeTestRule.onNodeWithTag("gauge-transTemp-value").assertTextEquals(TRANS_TEXT)
        composeTestRule.onNodeWithTag("gauge-oilTemp-value").assertTextEquals(OIL_TEXT)
    }

    @Test
    fun `tap a candidate page swaps the tile and persists, surviving a recreated repository`() {
        val file = temporaryFolder.newFile("gauge-swap.preferences_pb").also { it.delete() }
        val firstScope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())
        val firstRepository =
            DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = firstScope) { file })
        setDashboard(newViewModel(firstRepository))

        swapCoolantToRpm()

        composeTestRule.onNodeWithTag("gauge-rpm").assertExists()
        composeTestRule.onNodeWithTag("gauge-rpm-value").assertTextEquals(RPM_TEXT)
        composeTestRule.onNodeWithTag("gauge-coolant").assertDoesNotExist()

        // Simulated restart (mirrors SettingsRepositoryTest): the first DataStore instance must
        // be gone, not just idle, before a second one opens the same file.
        firstScope.cancel()
        val secondScope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())
        val secondRepository =
            DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = secondScope) { file })
        val restartedOrder = runBlocking { secondRepository.settings.first() }.gaugeOrder
        secondScope.cancel()

        assertEquals(PidIds.RPM, restartedOrder.first().id)
        assertTrue(restartedOrder.first().visible)
    }

    @Test
    fun `M1 - the picker scrim is gone after a completed swap (no stale pickerTileId)`() {
        setDashboard(newViewModel())

        swapCoolantToRpm()

        // Sanity: the swap actually landed.
        composeTestRule.onNodeWithTag("gauge-rpm").assertExists()
        // The self-heal clears pickerTileId once coolant leaves the layout, so the scrim is gone.
        composeTestRule.onNodeWithTag("gauge-picker-scrim").assertDoesNotExist()
    }

    @Test
    fun `M1 - the old id returning to the layout via a non-picker path does not reopen its pager`() {
        val viewModel = newViewModel()
        setDashboard(viewModel)

        swapCoolantToRpm()
        composeTestRule.onNodeWithTag("gauge-rpm").assertExists()

        viewModel.swapGauge(PidIds.RPM, PidIds.COOLANT)
        composeTestRule.waitForIdle()

        // Coolant comes back as a normal live tile, never re-entering pick mode.
        composeTestRule.onNodeWithTag("gauge-coolant").assertExists()
        composeTestRule.onNodeWithTag("gauge-coolant-value").assertTextEquals(COOLANT_TEXT)
        composeTestRule.onNodeWithTag("gauge-swap-pager-coolant").assertDoesNotExist()
    }

    @Test
    fun `M3 - a swapped-in gauge renders a placeholder, not a missing tile, during Loading`() {
        // DashboardUiState.Loading only carries the four core placeholders (extraTiles is empty
        // by default) — a tile freshly swapped to a non-core id must still render something for
        // every frame before the first real reading arrives, not a gap in the layout.
        val loadingOrder = listOf(GaugeOrderEntry(PidIds.RPM)) + DEFAULT_GAUGE_ORDER.drop(1)
        composeTestRule.setContent {
            ObdGaugeTheme {
                GaugeDashboard(uiState = DashboardUiState.Loading, gaugeOrder = loadingOrder)
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("gauge-rpm").assertExists()
        composeTestRule.onNodeWithTag("gauge-rpm-value").assertTextEquals(NO_READING_TEXT)
    }

    @Test
    fun `dismiss - tapping the current gauge's own page changes nothing`() {
        setDashboard(newViewModel())
        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("gauge-swap-page-coolant").performTouchInput { click() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("gauge-coolant").assertExists()
        composeTestRule.onNodeWithTag("gauge-coolant-value").assertTextEquals(COOLANT_TEXT)
        composeTestRule.onNodeWithTag("gauge-swap-pager-coolant").assertDoesNotExist()
    }

    @Test
    fun `dismiss - the back gesture changes nothing`() {
        setDashboard(newViewModel())
        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("gauge-coolant").assertExists()
        composeTestRule.onNodeWithTag("gauge-coolant-value").assertTextEquals(COOLANT_TEXT)
        composeTestRule.onNodeWithTag("gauge-swap-pager-coolant").assertDoesNotExist()
    }

    @Test
    fun `dismiss - tapping outside the tile changes nothing`() {
        setDashboard(newViewModel())
        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("gauge-picker-scrim").performTouchInput { click() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("gauge-coolant").assertExists()
        composeTestRule.onNodeWithTag("gauge-coolant-value").assertTextEquals(COOLANT_TEXT)
        composeTestRule.onNodeWithTag("gauge-swap-pager-coolant").assertDoesNotExist()
    }

    @Test
    fun `dismiss - tapping a different live tile also changes nothing`() {
        setDashboard(newViewModel())
        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("gauge-transTemp").performTouchInput { click() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("gauge-swap-pager-coolant").assertDoesNotExist()
        composeTestRule.onNodeWithTag("gauge-coolant-value").assertTextEquals(COOLANT_TEXT)
    }

    @Test
    fun `swap correctness - value, label, unit suffix, and threshold coloring all come from the new pid`() {
        setDashboard(newViewModel())
        // Confirm the pre-swap state really is coolant's RED zone first, so the post-swap
        // assertions below are a meaningful contrast — a "still red, still coolant" bug
        // wouldn't be caught by asserting the new state in isolation.
        composeTestRule.onNodeWithTag("gauge-coolant").assert(hasZone(ThresholdZone.RED))

        swapCoolantToRpm()

        // VALUE: the fed rpm reading, never the stale coolant one.
        composeTestRule.onNodeWithTag("gauge-rpm-value").assertTextEquals(RPM_TEXT)
        // LABEL: "RPM", not "Coolant".
        composeTestRule.onNodeWithTag("gauge-rpm-label").assertTextEquals("RPM")
        // THRESHOLD COLORING: rpm has no seed threshold entry, so NEUTRAL — not RED, which is
        // what coolant's own value would still produce if coloring were stuck on the old pid.
        composeTestRule.onNodeWithTag("gauge-rpm").assert(hasZone(ThresholdZone.NEUTRAL))
    }

    @Config(qualifiers = "w360dp-h640dp-port")
    @Test
    fun `pager is reachable in portrait too`() {
        setDashboard(newViewModel())

        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("gauge-swap-pager-coolant").assertExists()
        composeTestRule.onNodeWithTag("gauge-swap-pager-coolant").performScrollToIndex(RPM_PAGE)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-swap-page-rpm").assertExists()
    }

    /** Long-press coolant, scroll the pager to the rpm page, and tap it to swap. */
    private fun swapCoolantToRpm() {
        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-swap-pager-coolant").performScrollToIndex(RPM_PAGE)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-swap-page-rpm").performTouchInput { click() }
        composeTestRule.waitForIdle()
    }

    private fun setDashboard(viewModel: DashboardViewModel) {
        composeTestRule.setContent {
            ObdGaugeTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val gaugeOrder by viewModel.gaugeOrder.collectAsStateWithLifecycle()
                GaugeDashboard(uiState = uiState, gaugeOrder = gaugeOrder, onSwapGauge = viewModel::swapGauge)
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun newViewModel(repository: SettingsRepository = PickerInMemorySettingsRepository()): DashboardViewModel =
        DashboardViewModel(FixedReadingsVehicleDataSource(fixedReadings()), clock, repository)

    private fun hasZone(zone: ThresholdZone) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, zone.name.lowercase())

    private companion object {
        // Pages are [current, rpm, speed] for a default-order picker (GaugeCatalog.candidateGaugesFor).
        const val RPM_PAGE = 1
        const val SPEED_PAGE = 2

        const val COOLANT_VALUE = 235.0
        const val TRANS_VALUE = 150.0
        const val OIL_VALUE = 200.0
        const val BOOST_VALUE = 5.0
        const val RPM_VALUE = 3200.0

        const val COOLANT_TEXT = "235°F"
        const val TRANS_TEXT = "150°F"
        const val OIL_TEXT = "200°F"
        const val BOOST_TEXT = "5.0 PSI"
        const val RPM_TEXT = "3200 RPM"

        fun fixedReadings(now: Instant = Instant.EPOCH): Map<String, Reading> =
            mapOf(
                PidIds.COOLANT to Reading(PidIds.COOLANT, COOLANT_VALUE, now, stale = false),
                PidIds.TRANS_TEMP to Reading(PidIds.TRANS_TEMP, TRANS_VALUE, now, stale = false),
                PidIds.OIL_TEMP to Reading(PidIds.OIL_TEMP, OIL_VALUE, now, stale = false),
                PidIds.BOOST to Reading(PidIds.BOOST, BOOST_VALUE, now, stale = false),
                PidIds.RPM to Reading(PidIds.RPM, RPM_VALUE, now, stale = false),
            )
    }
}

/** Emits a fixed readings map, [LinkState.Ready] — start/stop are no-ops, mirroring `LiveRecolorTest`'s double. */
private class FixedReadingsVehicleDataSource(
    initial: Map<String, Reading>,
) : VehicleDataSource {
    override val readings = MutableStateFlow(initial)
    override val connection = MutableStateFlow<LinkState>(LinkState.Ready)

    override fun start(pids: List<PidDefinition>) = Unit

    override fun stop() = Unit
}

/** No-DataStore double for tests that don't need real persistence — mirrors `DashboardViewModelTest`'s own. */
private class PickerInMemorySettingsRepository : SettingsRepository {
    private val state = MutableStateFlow(AppSettings())
    override val settings = state

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        state.value = transform(state.value)
    }
}
