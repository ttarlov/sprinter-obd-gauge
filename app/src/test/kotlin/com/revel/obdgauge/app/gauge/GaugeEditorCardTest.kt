package com.revel.obdgauge.app.gauge

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
 * OBD-77: the ⚙ badge now opens a dashboard-level floating editor card instead of flipping the
 * in-tile swap card, and ⇄ still opens the in-tile swap carousel — the whole point being that the
 * two badges drive fully independent state. These tests pin that split, the three dismiss paths
 * (Done / back / tap-outside), and that persistence is unchanged by the re-host: a style pick and
 * a threshold step still land in the repository.
 *
 * The grow/collapse motion itself is a device-only visual (`GaugeEditorGrowTest` covers its pure
 * math); what's asserted here is structure and state.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w800dp-h360dp-land")
class GaugeEditorCardTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
    private val repository = EditorCardInMemorySettingsRepository()

    @Test
    fun `the gear badge opens the floating editor card, not the in-tile swap pager`() {
        setDashboard()
        openFloatingEditor()

        composeTestRule.onNodeWithTag("gauge-editor-card-coolant").assertExists()
        composeTestRule.onNodeWithTag("gauge-editor-title").assertTextEquals("Coolant")
        // The in-tile carousel is untouched by the ⚙ path now.
        composeTestRule.onNodeWithTag("gauge-swap-pager-coolant").assertDoesNotExist()
        // Both editor sections are live inside the card.
        composeTestRule.onNodeWithTag("gauge-style-picker-coolant").assertExists()
        composeTestRule.onNodeWithTag("gauge-threshold-square-yellow").assertExists()
        composeTestRule.onNodeWithTag("gauge-threshold-value").assertTextEquals("215°F")
    }

    @Test
    fun `the swap badge still opens the in-tile swap pager, not the floating editor`() {
        setDashboard()
        enterRearrangeMode()
        composeTestRule.onNodeWithTag("gauge-rearrange-swap-coolant").performTouchInput { click() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("gauge-swap-pager-coolant").assertExists()
        composeTestRule.onNodeWithTag("gauge-editor-card-coolant").assertDoesNotExist()
    }

    @Test
    fun `Done collapses the floating editor`() {
        setDashboard()
        openFloatingEditor()

        composeTestRule.onNodeWithTag("gauge-editor-done").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("gauge-editor-card-coolant").assertDoesNotExist()
        // Dismissing the editor leaves rearrange mode itself running — only back/Done exits that.
        composeTestRule.onNodeWithTag("gauge-rearrange-threshold-coolant").assertExists()
    }

    @Test
    fun `tapping outside the card collapses the floating editor`() {
        setDashboard()
        openFloatingEditor()

        // The scrim's own top-left corner is well clear of the centred card.
        composeTestRule.onNodeWithTag("gauge-editor-scrim").performTouchInput { click(topLeft) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("gauge-editor-card-coolant").assertDoesNotExist()
    }

    @Test
    fun `back closes the editor first and leaves rearrange mode only on a second press`() {
        setDashboard()
        openFloatingEditor()

        pressBack()
        composeTestRule.onNodeWithTag("gauge-editor-card-coolant").assertDoesNotExist()
        composeTestRule.onNodeWithTag("gauge-rearrange-threshold-coolant").assertExists()

        pressBack()
        composeTestRule.onNodeWithTag("gauge-rearrange-threshold-coolant").assertDoesNotExist()
    }

    @Test
    fun `picking a style in the floating card persists it`() {
        setDashboard()
        openFloatingEditor()

        composeTestRule.onNodeWithTag("gauge-style-NEEDLE-coolant").performTouchInput { click() }
        composeTestRule.waitForIdle()

        assertEquals(GaugeRenderStyle.NEEDLE, repository.settings.value.renderStyles[PidIds.COOLANT])
    }

    @Test
    fun `stepping a threshold in the floating card persists it`() {
        setDashboard()
        openFloatingEditor()

        composeTestRule.onNodeWithTag("gauge-threshold-square-red").performTouchInput { click() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-threshold-value").assertTextEquals("225°F")
        composeTestRule.onNodeWithTag("gauge-threshold-plus").performTouchInput { click() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("gauge-threshold-value").assertTextEquals("230°F")
        val band =
            repository.settings.value.thresholdOverrides
                .getValue(PidIds.COOLANT)
        assertEquals(230.0, band.redMin!!, 0.0)
        // The boundary the user never touched survives the round trip, exactly as before OBD-77.
        assertEquals(215.0, band.greenMax!!, 0.0)
    }

    private fun pressBack() {
        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()
    }

    private fun enterRearrangeMode() {
        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
    }

    private fun openFloatingEditor() {
        enterRearrangeMode()
        composeTestRule.onNodeWithTag("gauge-rearrange-threshold-coolant").performTouchInput { click() }
        composeTestRule.waitForIdle()
    }

    private fun setDashboard() {
        val viewModel =
            DashboardViewModel(EditorCardFixedReadingsVehicleDataSource(fixedReadings()), clock, repository)
        composeTestRule.setContent {
            ObdGaugeTheme {
                // The jiggle is a never-idle infinite transition; off here for the same reason
                // every other rearrange-mode test disables it (it would hang waitForIdle).
                CompositionLocalProvider(LocalRearrangeJiggleEnabled provides false) {
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                    val gaugeOrder by viewModel.gaugeOrder.collectAsStateWithLifecycle()
                    val gridLayoutsByColumns by viewModel.gridLayoutsByColumns.collectAsStateWithLifecycle()
                    val thresholds by viewModel.thresholds.collectAsStateWithLifecycle()
                    val renderStyles by viewModel.renderStyles.collectAsStateWithLifecycle()
                    GaugeDashboard(
                        uiState = uiState,
                        gaugeOrder = gaugeOrder,
                        gridLayoutsByColumns = gridLayoutsByColumns,
                        thresholds = thresholds,
                        renderStyles = renderStyles,
                        dangerPulseEnabled = false,
                        onSwapGauge = viewModel::swapGauge,
                        onSetThreshold = viewModel::setThreshold,
                        onSetRenderStyle = viewModel::setRenderStyle,
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

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

private class EditorCardFixedReadingsVehicleDataSource(
    initial: Map<String, Reading>,
) : VehicleDataSource {
    override val readings = MutableStateFlow(initial)
    override val connection = MutableStateFlow<LinkState>(LinkState.Ready)

    override fun start(pids: List<PidDefinition>) = Unit

    override fun stop() = Unit
}

private class EditorCardInMemorySettingsRepository : SettingsRepository {
    private val state = MutableStateFlow(AppSettings())
    override val settings = state

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        state.value = transform(state.value)
    }
}
