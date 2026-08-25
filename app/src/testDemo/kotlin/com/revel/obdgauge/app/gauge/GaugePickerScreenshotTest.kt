package com.revel.obdgauge.app.gauge

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.click
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
                GaugeDashboard(sampleUiState(), dangerPulseEnabled = false)
            }
        }

        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-rearrange-swap-coolant").performTouchInput { click() }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().captureRoboImage(SCREENSHOT_DIR + "gauge_picker_mode.png")
    }

    // OBD-66/72's in-tile flip editor — the back face of a focused swap card. OBD-77 moved the ⚙
    // badge off this path (it opens the floating card now, see `expanded editor card` below), so
    // the only route left to `GaugeEditorFace` is the gear ON a swap card: ⇄ → the pager → gear.
    @Config(qualifiers = "w800dp-h360dp-land")
    @Test
    fun `threshold editor back face, landscape`() {
        composeTestRule.setContent {
            ObdGaugeTheme {
                GaugeDashboard(sampleUiState(), dangerPulseEnabled = false)
            }
        }

        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-rearrange-swap-coolant").performTouchInput { click() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-threshold-gear-coolant").performTouchInput { click() }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().captureRoboImage(SCREENSHOT_DIR + "gauge_threshold_editor.png")
    }

    // OBD-64/67: the add palette open over the dashboard — reached via long-press (rearrange mode)
    // → the ⇄ badge → the edit bar's "＋ Add" button, showing the addable gauges (rpm/speed) as
    // live mini-cards.
    @Config(qualifiers = "w800dp-h360dp-land")
    @Test
    fun `add palette open, landscape`() {
        composeTestRule.setContent {
            ObdGaugeTheme {
                GaugeDashboard(sampleUiState(), dangerPulseEnabled = false)
            }
        }

        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-rearrange-swap-coolant").performTouchInput { click() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-edit-add").performTouchInput { click() }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().captureRoboImage(SCREENSHOT_DIR + "gauge_add_palette.png")
    }

    // OBD-67: rearrange mode itself — the grid backdrop, jiggling badges, and the ×/⇄/⚙ cluster on
    // every tile. Jiggle is a continuous animation (LocalRearrangeJiggleEnabled), disabled here for
    // the same reason dangerPulseEnabled is — a still board is the right thing to screenshot.
    @Config(qualifiers = "w800dp-h360dp-land")
    @Test
    fun `rearrange mode, landscape`() {
        composeTestRule.setContent {
            ObdGaugeTheme {
                CompositionLocalProvider(LocalRearrangeJiggleEnabled provides false) {
                    GaugeDashboard(sampleUiState(), dangerPulseEnabled = false)
                }
            }
        }

        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().captureRoboImage(SCREENSHOT_DIR + "dashboard_rearrange_mode.png")
    }

    // OBD-77: ⚙ → the expand-in-place editor card, floating over a dimmed grid at a comfortable
    // fixed size regardless of how small the gauge's own tile is. Landscape (the dash-mount
    // primary) is the wide two-column arrangement: STYLE beside THRESHOLD, chips stacked.
    @Config(qualifiers = "w800dp-h360dp-land")
    @Test
    fun `expanded editor card, landscape`() {
        composeTestRule.setContent {
            ObdGaugeTheme {
                CompositionLocalProvider(LocalRearrangeJiggleEnabled provides false) {
                    GaugeDashboard(sampleUiState(), dangerPulseEnabled = false)
                }
            }
        }

        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-rearrange-threshold-coolant").performTouchInput { click() }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().captureRoboImage(SCREENSHOT_DIR + "dashboard_editor_expanded.png")
    }

    // OBD-77: the same card in portrait — a tall screen gets the single stacked column, and the
    // style chips flow as a row rather than stacking.
    @Config(qualifiers = "w400dp-h800dp-port")
    @Test
    fun `expanded editor card, portrait`() {
        composeTestRule.setContent {
            ObdGaugeTheme {
                CompositionLocalProvider(LocalRearrangeJiggleEnabled provides false) {
                    GaugeDashboard(sampleUiState(), dangerPulseEnabled = false)
                }
            }
        }

        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-rearrange-threshold-coolant").performTouchInput { click() }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().captureRoboImage(SCREENSHOT_DIR + "dashboard_editor_expanded_portrait.png")
    }

    // OBD-72/77: a gauge with no threshold config (boost) gets a style-only card — the section
    // simply isn't there, and the card shrinks to fit rather than reserving empty space.
    @Config(qualifiers = "w800dp-h360dp-land")
    @Test
    fun `expanded editor card, style only`() {
        composeTestRule.setContent {
            ObdGaugeTheme {
                CompositionLocalProvider(LocalRearrangeJiggleEnabled provides false) {
                    GaugeDashboard(sampleUiState(), dangerPulseEnabled = false)
                }
            }
        }

        composeTestRule.onNodeWithTag("gauge-boost").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-rearrange-threshold-boost").performTouchInput { click() }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().captureRoboImage(SCREENSHOT_DIR + "dashboard_editor_style_only.png")
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
