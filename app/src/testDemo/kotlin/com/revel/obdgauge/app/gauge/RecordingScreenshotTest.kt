package com.revel.obdgauge.app.gauge

import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import com.revel.obdgauge.app.recording.RecordingState
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
import java.io.File
import java.time.Instant

/**
 * OBD-70 Roborazzi refs: the top-row Record control in its two states, and the start
 * confirmation dialog. `dangerPulseEnabled = false` (same seam `DashboardScreenshotTest`/
 * `GaugePickerScreenshotTest` already use for OBD-66's danger pulse) freezes BOTH the indicator's
 * red-dot pulse and its elapsed-timer ticker — see `GaugeDashboard`'s own KDoc on `elapsedClock` —
 * so every reference is a single deterministic frame, not a point on a live animation.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingScreenshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Config(qualifiers = "w800dp-h360dp-land")
    @Test
    fun `idle Record button in the top row, landscape`() {
        composeTestRule.setContent {
            ObdGaugeTheme {
                GaugeDashboard(sampleUiState(), dangerPulseEnabled = false)
            }
        }

        composeTestRule.onRoot().captureRoboImage(SCREENSHOT_DIR + "record_button_idle.png")
    }

    @Config(qualifiers = "w800dp-h360dp-land")
    @Test
    fun `live recording indicator in the top row, landscape`() {
        composeTestRule.setContent {
            ObdGaugeTheme {
                GaugeDashboard(
                    sampleUiState(),
                    dangerPulseEnabled = false,
                    recordingState = RecordingState.Recording(0L, File("unused"), rowCount = 754),
                    // 12:34 elapsed — matches formatElapsedRecording(754_000L).
                    elapsedClock = { 754_000L },
                )
            }
        }

        composeTestRule.onRoot().captureRoboImage(SCREENSHOT_DIR + "recording_indicator.png")
    }

    @Config(qualifiers = "w800dp-h360dp-land")
    @Test
    fun `Record tap opens the start confirmation dialog, landscape`() {
        composeTestRule.setContent {
            ObdGaugeTheme {
                GaugeDashboard(sampleUiState(), dangerPulseEnabled = false)
            }
        }

        composeTestRule.onNodeWithTag("record-button").performTouchInput { click() }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().captureRoboImage(SCREENSHOT_DIR + "record_confirm_dialog.png")
    }

    private fun sampleUiState(): DashboardUiState {
        lateinit var result: DashboardUiState
        runTest {
            val dataSource = FakeVehicleDataSource(scenario = Scenario.TOWN_HEAT_SOAK, scope = this)
            dataSource.start(DASHBOARD_PIDS)
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
