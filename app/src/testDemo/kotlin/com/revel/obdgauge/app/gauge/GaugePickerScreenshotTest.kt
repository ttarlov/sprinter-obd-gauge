package com.revel.obdgauge.app.gauge

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import com.revel.obdgauge.app.ui.theme.ObdGaugeTheme
import com.revel.obdgauge.testing.datasource.FakeVehicleDataSource
import com.revel.obdgauge.testing.datasource.Scenario
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * OBD-42's one required Roborazzi reference: a gauge tile in picker mode — the "sink into the
 * frame of itself" carousel, with candidates (rpm) visible. A deliberate NEW screenshot, not a
 * regeneration of `DashboardScreenshotTest`'s existing two references (those stay untouched;
 * this file's `sampleUiState` requests `GAUGE_CATALOG`, not `DASHBOARD_PIDS`, specifically so
 * the rpm candidate mini-card shows a real value rather than the placeholder).
 *
 * Same "captureRoboImage no-ops outside the dedicated Roborazzi tasks" note as
 * `DashboardScreenshotTest` applies here — see its KDoc.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class GaugePickerScreenshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Config(qualifiers = "w800dp-h360dp-land")
    @Test
    fun `coolant tile in picker mode, landscape`() {
        composeTestRule.setContent {
            ObdGaugeTheme {
                GaugeDashboard(sampleUiState())
            }
        }

        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().captureRoboImage(SCREENSHOT_DIR + "gauge_picker_mode.png")
    }

    // Same pattern as DashboardScreenshotTest's sampleUiState, but requesting GAUGE_CATALOG
    // (not DASHBOARD_PIDS) so the fake actually emits an rpm reading — otherwise the picker's
    // rpm mini-card would show the placeholder instead of a real value in the reference image.
    private fun sampleUiState(): DashboardUiState {
        lateinit var result: DashboardUiState
        runTest {
            val dataSource = FakeVehicleDataSource(scenario = Scenario.TOWN_HEAT_SOAK, scope = this)
            dataSource.start(GAUGE_CATALOG)
            advanceUntilIdle()
            result = toDashboardUiState(dataSource.readings.value, dataSource.connection.value, Instant.EPOCH)
        }
        return result
    }

    private companion object {
        // Same rationale as DashboardScreenshotTest.SCREENSHOT_DIR.
        const val SCREENSHOT_DIR = "src/testDemo/screenshots/"
    }
}
