package com.revel.obdgauge.app.settings

import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.revel.obdgauge.app.gauge.DashboardViewModel
import com.revel.obdgauge.app.gauge.GaugeDashboard
import com.revel.obdgauge.app.gauge.ThresholdZone
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * OBD-21 AC: "Editing a threshold live-recolors the dashboard in a Compose test (no restart
 * required)". Wires the real [DashboardViewModel] and [SettingsViewModel] to a real Preferences
 * DataStore (a temp file — see `SettingsRepositoryTest` for the same no-Robolectric-needed
 * pattern, though this test also needs Compose+Robolectric for the UI assertions) and a small
 * hand-rolled [VehicleDataSource] double emitting one fixed coolant reading — proving the
 * recolor comes from the settings edit alone, not from new sensor data arriving.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveRecolorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @get:Rule
    val composeTestRule = createComposeRule()

    private val scope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `raising a threshold in the settings screen recolors the dashboard tile without restart`() {
        val file = temporaryFolder.newFile("live-recolor.preferences_pb").also { it.delete() }
        val repository = DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
        // 220 F is AMBER under ThresholdConfig.seed's OBD-66 coolant band (green <215, red ≥225).
        val dataSource = FixedReadingVehicleDataSource(coolantValue = COOLANT_AMBER_VALUE)
        val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
        val dashboardViewModel = DashboardViewModel(dataSource, clock, repository)
        val settingsViewModel = SettingsViewModel(repository)

        composeTestRule.setContent {
            ObdGaugeTheme {
                val uiState by dashboardViewModel.uiState.collectAsStateWithLifecycle()
                GaugeDashboard(uiState = uiState)
                val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
                SettingsScreen(
                    settings = settings,
                    onSetGaugeVisible = settingsViewModel::setGaugeVisible,
                    onMoveGauge = settingsViewModel::moveGauge,
                    onSetThresholdOverride = settingsViewModel::setThresholdOverride,
                    onResetThresholds = settingsViewModel::resetThresholdsToDefault,
                    onSetUnits = settingsViewModel::setUnits,
                    onSetKeepScreenOn = settingsViewModel::setKeepScreenOn,
                    onSetShowConnectionStatus = settingsViewModel::setShowConnectionStatus,
                    onSetPollRate = settingsViewModel::setPollRate,
                    onOpenRecordings = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule
            .onNodeWithTag("gauge-${PidIds.COOLANT}")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    ThresholdZone.AMBER.name.lowercase(),
                ),
            )

        // Raise coolant's green-max well above the fixed reading so the SAME value now falls
        // under green — proof this is live recoloring off the settings edit, not new sensor data.
        composeTestRule.onNodeWithTag("threshold-${PidIds.COOLANT}-greenMax").performTextReplacement("400")
        composeTestRule.waitForIdle()

        composeTestRule
            .onNodeWithTag("gauge-${PidIds.COOLANT}")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    ThresholdZone.GREEN.name.lowercase(),
                ),
            )
    }

    private companion object {
        const val COOLANT_AMBER_VALUE = 220.0
    }
}

private class FixedReadingVehicleDataSource(
    coolantValue: Double,
) : VehicleDataSource {
    override val readings =
        MutableStateFlow(mapOf(PidIds.COOLANT to Reading(PidIds.COOLANT, coolantValue, Instant.EPOCH, stale = false)))
    override val connection = MutableStateFlow<LinkState>(LinkState.Ready)

    override fun start(pids: List<PidDefinition>) = Unit

    override fun stop() = Unit
}
