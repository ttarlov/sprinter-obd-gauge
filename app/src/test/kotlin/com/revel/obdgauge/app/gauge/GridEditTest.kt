package com.revel.obdgauge.app.gauge

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.revel.obdgauge.app.settings.AppSettings
import com.revel.obdgauge.app.settings.SettingsRepository
import com.revel.obdgauge.app.ui.theme.ObdGaugeTheme
import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.Reading
import com.revel.obdgauge.model.VehicleDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * OBD-64: the grid is now MUTABLE from the UI — add (>4), remove, and resize a tile from menus, no
 * drag. Wires [GaugeDashboard] to a real [DashboardViewModel] (so every mutation round-trips
 * through `SettingsRepository`/`gridLayout` exactly as production does) over a fixed-readings
 * [VehicleDataSource] double, then drives the "+" add-cell/palette and the long-press picker's new
 * size/remove controls. Assertions check BOTH the rendered tree and the persisted `gridLayout`, so
 * a regression that renders right but persists wrong (or vice versa) still fails.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w800dp-h360dp-land")
class GridEditTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)

    @Test
    fun `the edit-bar Add button opens the palette and adding places a fifth gauge`() {
        val viewModel = newViewModel()
        setDashboard(viewModel)

        // The normal dashboard is clean — no always-visible add cell.
        composeTestRule.onNodeWithTag("gauge-add-cell").assertDoesNotExist()
        composeTestRule.onNodeWithTag("gauge-edit-add").assertDoesNotExist()

        // Add lives behind long-press, in the edit bar; rpm/speed unplaced, so it's shown.
        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-edit-add").performTouchInput { click() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-add-palette").assertExists()

        composeTestRule.onNodeWithTag("gauge-add-option-${PidIds.RPM}").performScrollTo().performTouchInput { click() }
        composeTestRule.waitForIdle()

        // The fifth tile is now on the dashboard, live, and persisted.
        composeTestRule.onNodeWithTag("gauge-${PidIds.RPM}").assertExists()
        composeTestRule.onNodeWithTag("gauge-${PidIds.RPM}-value").assertTextEquals(RPM_TEXT)
        assertEquals(
            5,
            viewModel.gridLayout.value!!
                .ids.size,
        )
        assert(PidIds.RPM in viewModel.gridLayout.value!!.ids)
        // Palette closed itself after the pick.
        composeTestRule.onNodeWithTag("gauge-add-palette").assertDoesNotExist()
    }

    @Test
    fun `a resize chip changes the tile's persisted span`() {
        val viewModel = newViewModel()
        setDashboard(viewModel)
        assertEquals(
            1,
            viewModel.gridLayout.value!!
                .placementFor(PidIds.COOLANT)!!
                .colSpan,
        )

        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-resize-2x2-coolant").performTouchInput { click() }
        composeTestRule.waitForIdle()

        val coolant = viewModel.gridLayout.value!!.placementFor(PidIds.COOLANT)!!
        assertEquals(2, coolant.colSpan)
        assertEquals(2, coolant.rowSpan)
    }

    @Test
    fun `remove drops a tile and it returns as an addable candidate`() {
        val viewModel = newViewModel()
        setDashboard(viewModel)

        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-remove-coolant").performTouchInput { click() }
        composeTestRule.waitForIdle()

        // Gone from the grid (rendered + persisted), and the picker closed with it.
        composeTestRule.onNodeWithTag("gauge-coolant").assertDoesNotExist()
        composeTestRule.onNodeWithTag("gauge-picker-coolant").assertDoesNotExist()
        assertNull(viewModel.gridLayout.value!!.placementFor(PidIds.COOLANT))

        // It's now offered again in the add palette — reached via another tile's edit bar.
        composeTestRule.onNodeWithTag("gauge-oilTemp").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-edit-add").performTouchInput { click() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-add-option-${PidIds.COOLANT}").performScrollTo().assertExists()
    }

    private fun setDashboard(viewModel: DashboardViewModel) {
        composeTestRule.setContent {
            ObdGaugeTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val gaugeOrder by viewModel.gaugeOrder.collectAsStateWithLifecycle()
                val gridLayout by viewModel.gridLayout.collectAsStateWithLifecycle()
                GaugeDashboard(
                    uiState = uiState,
                    gaugeOrder = gaugeOrder,
                    gridLayout = gridLayout,
                    onSwapGauge = viewModel::swapGauge,
                    onAddGauge = viewModel::addGauge,
                    onRemoveGauge = viewModel::removeGauge,
                    onResizeGauge = viewModel::resizeGauge,
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun newViewModel(): DashboardViewModel =
        DashboardViewModel(GridEditVehicleDataSource(fixedReadings()), clock, GridEditSettingsRepository())

    private companion object {
        const val RPM_TEXT = "3200 RPM"

        fun fixedReadings(now: Instant = Instant.EPOCH): Map<String, Reading> =
            mapOf(
                PidIds.COOLANT to Reading(PidIds.COOLANT, 190.0, now, stale = false),
                PidIds.TRANS_TEMP to Reading(PidIds.TRANS_TEMP, 150.0, now, stale = false),
                PidIds.OIL_TEMP to Reading(PidIds.OIL_TEMP, 200.0, now, stale = false),
                PidIds.BOOST to Reading(PidIds.BOOST, 5.0, now, stale = false),
                PidIds.RPM to Reading(PidIds.RPM, 3200.0, now, stale = false),
                SPEED_PID_ID to Reading(SPEED_PID_ID, 65.0, now, stale = false),
            )
    }
}

/** Emits a fixed readings map, [LinkState.Ready] — start/stop no-ops. Mirrors the picker tests' double. */
private class GridEditVehicleDataSource(
    initial: Map<String, Reading>,
) : VehicleDataSource {
    override val readings = MutableStateFlow(initial)
    override val connection = MutableStateFlow<LinkState>(LinkState.Ready)

    override fun start(pids: List<PidDefinition>) = Unit

    override fun stop() = Unit
}

/** No-DataStore settings double — the eager seed writes its `gridLayout` in memory. */
private class GridEditSettingsRepository : SettingsRepository {
    private val state = MutableStateFlow(AppSettings())
    override val settings = state

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        state.value = transform(state.value)
    }
}
