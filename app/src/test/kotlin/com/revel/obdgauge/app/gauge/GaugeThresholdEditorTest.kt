package com.revel.obdgauge.app.gauge

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
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
import org.junit.Assert.assertTrue
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
 * OBD-66: the gear + 3D-flip per-gauge threshold editor. Coolant is fed a fixed 220°F — AMBER under
 * the OBD-66 seed (green <215, red ≥225) — so raising the YELLOW boundary past it is a visible
 * recolor. The tests pin STRUCTURE and STATE (the gear appears on the focused card, flipping reveals
 * the menu, a square selects the right boundary, a step persists it and live-recolors the tile) —
 * the flip/pulse motion itself is a device-only visual, not asserted frame-by-frame.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w800dp-h360dp-land")
class GaugeThresholdEditorTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
    private val repository = EditorInMemorySettingsRepository()

    @Test
    fun `the gear on the focused card flips it to reveal the threshold menu`() {
        setDashboard()
        openEditor()

        // The back face's menu is now present: two squares, the big value, and the +/- stepper.
        composeTestRule.onNodeWithTag("gauge-threshold-square-yellow").assertExists()
        composeTestRule.onNodeWithTag("gauge-threshold-square-red").assertExists()
        composeTestRule.onNodeWithTag("gauge-threshold-plus").assertExists()
        composeTestRule.onNodeWithTag("gauge-threshold-minus").assertExists()
        // YELLOW is selected by default → the seed amber boundary (215°F) pre-fills the number.
        composeTestRule.onNodeWithTag("gauge-threshold-value").assertTextEquals("215°F")
    }

    @Test
    fun `the gear only appears once the tile is in pick mode`() {
        setDashboard()
        composeTestRule.onNodeWithTag("gauge-threshold-gear-coolant").assertDoesNotExist()

        // OBD-67: long-press only enters rearrange mode now; the ⇄ badge is what opens the picker.
        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-rearrange-swap-coolant").performTouchInput { click() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("gauge-threshold-gear-coolant").assertExists()
    }

    @Test
    fun `selecting RED and stepping persists redMin and preserves greenMax`() {
        setDashboard()
        openEditor()

        composeTestRule.onNodeWithTag("gauge-threshold-square-red").performTouchInput { click() }
        composeTestRule.waitForIdle()
        // RED pre-fills with the seed danger boundary, 225°F.
        composeTestRule.onNodeWithTag("gauge-threshold-value").assertTextEquals("225°F")

        composeTestRule.onNodeWithTag("gauge-threshold-plus").performTouchInput { click() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-threshold-value").assertTextEquals("230°F")

        val band =
            repository.settings.value.thresholdOverrides
                .getValue(PidIds.COOLANT)
        assertEquals(230.0, band.redMin!!, 0.0)
        assertTrue(band.redInclusive)
        // The YELLOW boundary the user never touched survives (still the seed 215).
        assertEquals(215.0, band.greenMax!!, 0.0)
    }

    @Test
    fun `raising the YELLOW boundary live-recolors the tile from amber to green`() {
        setDashboard()
        // 220°F sits in coolant's amber band to start with.
        composeTestRule.onNodeWithTag("gauge-coolant").assert(hasZone(ThresholdZone.AMBER))

        openEditor()
        // greenMax 215 → 220 → 225; once greenMax passes the 220 reading it classifies GREEN.
        composeTestRule.onNodeWithTag("gauge-threshold-plus").performTouchInput { click() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-threshold-plus").performTouchInput { click() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-threshold-value").assertTextEquals("225°F")

        // Leave pick mode (tap a live neighbour tile) and the coolant tile is now GREEN.
        composeTestRule.onNodeWithTag("gauge-transTemp").performTouchInput { click() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-coolant").assert(hasZone(ThresholdZone.GREEN))
    }

    /**
     * OBD-67: long-press enters rearrange mode, then the ⚙ badge opens the picker seeded straight
     * to its flipped threshold face (no separate gear tap needed — that's the badge's whole point).
     */
    private fun openEditor() {
        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-rearrange-threshold-coolant").performTouchInput { click() }
        composeTestRule.waitForIdle()
    }

    private fun setDashboard() {
        val viewModel = DashboardViewModel(EditorFixedReadingsVehicleDataSource(fixedReadings()), clock, repository)
        composeTestRule.setContent {
            ObdGaugeTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val gaugeOrder by viewModel.gaugeOrder.collectAsStateWithLifecycle()
                val gridLayoutsByColumns by viewModel.gridLayoutsByColumns.collectAsStateWithLifecycle()
                val thresholds by viewModel.thresholds.collectAsStateWithLifecycle()
                GaugeDashboard(
                    uiState = uiState,
                    gaugeOrder = gaugeOrder,
                    gridLayoutsByColumns = gridLayoutsByColumns,
                    thresholds = thresholds,
                    onSwapGauge = viewModel::swapGauge,
                    onSetThreshold = viewModel::setThreshold,
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun hasZone(zone: ThresholdZone) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, zone.name.lowercase())

    private companion object {
        const val COOLANT_AMBER = 220.0

        fun fixedReadings(now: Instant = Instant.EPOCH): Map<String, Reading> =
            mapOf(
                PidIds.COOLANT to Reading(PidIds.COOLANT, COOLANT_AMBER, now, stale = false),
                PidIds.TRANS_TEMP to Reading(PidIds.TRANS_TEMP, 150.0, now, stale = false),
                PidIds.OIL_TEMP to Reading(PidIds.OIL_TEMP, 200.0, now, stale = false),
                PidIds.BOOST to Reading(PidIds.BOOST, 5.0, now, stale = false),
            )
    }
}

private class EditorFixedReadingsVehicleDataSource(
    initial: Map<String, Reading>,
) : VehicleDataSource {
    override val readings = MutableStateFlow(initial)
    override val connection = MutableStateFlow<LinkState>(LinkState.Ready)

    override fun start(pids: List<PidDefinition>) = Unit

    override fun stop() = Unit
}

private class EditorInMemorySettingsRepository : SettingsRepository {
    private val state = MutableStateFlow(AppSettings())
    override val settings = state

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        state.value = transform(state.value)
    }
}
