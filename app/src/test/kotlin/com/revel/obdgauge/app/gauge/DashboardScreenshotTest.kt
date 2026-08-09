package com.revel.obdgauge.app.gauge

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
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
 * Screenshot references (OBD-10 AC: "Screenshot tests cover both orientations"), committed
 * under `src/test/screenshots/`. Uses the TOWN_HEAT_SOAK tail so the reference image also
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
        composeTestRule.setContent { ObdGaugeTheme { GaugeDashboard(sampleUiState()) } }
        composeTestRule.onRoot().captureRoboImage(SCREENSHOT_DIR + "dashboard_landscape.png")
    }

    @Config(qualifiers = "w360dp-h640dp-port")
    @Test
    fun `dashboard renders in portrait`() {
        composeTestRule.setContent { ObdGaugeTheme { GaugeDashboard(sampleUiState()) } }
        composeTestRule.onRoot().captureRoboImage(SCREENSHOT_DIR + "dashboard_portrait.png")
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
        const val SCREENSHOT_DIR = "src/test/screenshots/"
    }
}
