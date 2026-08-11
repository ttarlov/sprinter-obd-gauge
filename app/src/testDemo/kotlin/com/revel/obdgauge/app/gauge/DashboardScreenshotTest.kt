package com.revel.obdgauge.app.gauge

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.revel.obdgauge.app.sparkline.SparklinePoint
import com.revel.obdgauge.app.ui.theme.ObdGaugeTheme
import com.revel.obdgauge.testing.datasource.FakeVehicleDataSource
import com.revel.obdgauge.testing.datasource.Scenario
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * Screenshot references (OBD-10 AC: "Screenshot tests cover both orientations"), committed
 * under `src/testDemo/screenshots/`. Uses the TOWN_HEAT_SOAK tail so the reference image also
 * documents the amber threshold coloring visually, not just the two green/neutral states.
 *
 * Roborazzi's `captureRoboImage` no-ops unless invoked via its own Gradle tasks
 * (`recordRoborazziDebug` / `verifyRoborazziDebug`) or `-Proborazzi.test.record=true` /
 * `-Proborazzi.test.verify=true` — a plain `testDebugUnitTest` run exercises this code path
 * without comparing pixels, so these tests never block the base build gate on an
 * environment-sensitive pixel diff.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardScreenshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Config(qualifiers = "w800dp-h360dp-land")
    @Test
    fun `dashboard renders in landscape`() {
        composeTestRule.setContent {
            ObdGaugeTheme {
                GaugeDashboard(
                    sampleUiState(),
                    sparklines = sampleSparklines(),
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(SCREENSHOT_DIR + "dashboard_landscape.png")
    }

    @Config(qualifiers = "w360dp-h640dp-port")
    @Test
    fun `dashboard renders in portrait`() {
        composeTestRule.setContent {
            ObdGaugeTheme {
                GaugeDashboard(
                    sampleUiState(),
                    sparklines = sampleSparklines(),
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(SCREENSHOT_DIR + "dashboard_portrait.png")
    }

    // OBD-20 AC: the references should show sparklines, not just bare tiles — a small synthetic
    // rising trend per gauge, distinct enough from a flat line to be visibly a chart.
    private fun sampleSparklines(): Map<String, StateFlow<List<SparklinePoint>>> =
        DASHBOARD_PIDS.associate { pid ->
            val base = sampleUiState().tileFor(pid.id)?.rawValue ?: 0.0
            val points =
                (0 until SPARKLINE_SAMPLE_COUNT).map { i ->
                    SparklinePoint(
                        Instant.EPOCH.plusMillis(i * SPARKLINE_SAMPLE_INTERVAL_MILLIS),
                        base - SPARKLINE_SAMPLE_COUNT + i,
                    )
                }
            pid.id to MutableStateFlow<List<SparklinePoint>>(points)
        }

    // Runs the fake on `this` (the runTest TestScope), not `backgroundScope` — see
    // DashboardScreenTest's dashboardUiStateFor for why.
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
        // Roborazzi's default record-path strategy resolves a bare filename against the JVM's
        // current working directory (the module dir for a Gradle `Test` task), so this prefix
        // is what actually lands references under version control instead of `app/*.png`.
        // Lives under src/testDemo/ (OBD-12) alongside this test, which only runs for the
        // `demo` flavor.
        const val SCREENSHOT_DIR = "src/testDemo/screenshots/"
        const val SPARKLINE_SAMPLE_COUNT = 20

        // 250 ms = a nominal 4 Hz cadence, well under SparklineChart's 2 s gap threshold, so the
        // reference image shows a continuous line rather than 20 disconnected points.
        const val SPARKLINE_SAMPLE_INTERVAL_MILLIS = 250L
    }
}
