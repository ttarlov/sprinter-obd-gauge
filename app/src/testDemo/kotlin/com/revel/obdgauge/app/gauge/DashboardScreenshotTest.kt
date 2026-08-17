package com.revel.obdgauge.app.gauge

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import com.revel.obdgauge.app.gauge.grid.GridEngine
import com.revel.obdgauge.app.gauge.grid.GridLayout
import com.revel.obdgauge.app.gauge.grid.GridPlacement
import com.revel.obdgauge.app.sparkline.SparklinePoint
import com.revel.obdgauge.app.ui.theme.ObdGaugeTheme
import com.revel.obdgauge.model.LinkError
import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.Reading
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
                    // OBD-66: the TOWN_HEAT_SOAK tail now puts coolant at its danger line (RED). Pulse
                    // off so the reference is a stable single frame, not a point on the pulse cycle.
                    dangerPulseEnabled = false,
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
                    dangerPulseEnabled = false,
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(SCREENSHOT_DIR + "dashboard_portrait.png")
    }

    // OBD-63: spanning-grid scenarios. Each passes an explicit GridLayout so the reference
    // documents a specific span/side-by-side arrangement, not just the migrated default. The
    // engine repacks by packing-order + spans (positions in these fixtures are placeholders), so
    // these fix the ORDER and SPANS and let repack settle the cells — exactly the production path.

    @Config(qualifiers = "w800dp-h360dp-land")
    @Test
    fun `dashboard renders a 2x1 wide tile`() {
        // Boost runs two columns wide in the top-left; the other three flow around it.
        captureGrid(
            "dashboard_grid_wide.png",
            gridOf(
                GridPlacement(BOOST, 0, 0, colSpan = 2, rowSpan = 1),
                GridPlacement(COOLANT, 0, 0),
                GridPlacement(OIL, 0, 0),
                GridPlacement(TRANS, 0, 0),
            ),
        )
    }

    @Config(qualifiers = "w800dp-h360dp-land")
    @Test
    fun `dashboard renders six tiles with speed beside oil`() {
        // Six 1×1 tiles: coolant, oil, speed, boost fill the top row (oil at col 1, speed at
        // col 2 → side-by-side); trans and rpm wrap to the second row.
        captureGrid(
            "dashboard_grid_six.png",
            gridOf(
                GridPlacement(COOLANT, 0, 0),
                GridPlacement(OIL, 0, 0),
                GridPlacement(SPEED, 0, 0),
                GridPlacement(BOOST, 0, 0),
                GridPlacement(TRANS, 0, 0),
                GridPlacement(RPM, 0, 0),
            ),
        )
    }

    @Config(qualifiers = "w800dp-h360dp-land")
    @Test
    fun `dashboard renders a 2x2 tile`() {
        // Boost is a big 2×2 square in the top-left; the temp tiles flow around it.
        captureGrid(
            "dashboard_grid_big.png",
            gridOf(
                GridPlacement(BOOST, 0, 0, colSpan = 2, rowSpan = 2),
                GridPlacement(COOLANT, 0, 0),
                GridPlacement(OIL, 0, 0),
                GridPlacement(TRANS, 0, 0),
            ),
        )
    }

    // OBD-66: a tile parked in the danger (RED) zone — a hand-built all-red state so the reference
    // documents the danger look plainly. Pulse off so it's a stable single frame (the pulse itself
    // is a device-only visual, asserted structurally in DashboardScreenTest, not by pixels).
    @Config(qualifiers = "w800dp-h360dp-land")
    @Test
    fun `dashboard danger zone tile`() {
        val now = Instant.EPOCH
        val readings =
            mapOf(
                PidIds.COOLANT to Reading(PidIds.COOLANT, DANGER_COOLANT, now, stale = false),
                PidIds.TRANS_TEMP to Reading(PidIds.TRANS_TEMP, DANGER_TRANS, now, stale = false),
                PidIds.OIL_TEMP to Reading(PidIds.OIL_TEMP, SAFE_OIL, now, stale = false),
                PidIds.BOOST to Reading(PidIds.BOOST, SAFE_BOOST, now, stale = false),
            )
        composeTestRule.setContent {
            ObdGaugeTheme {
                GaugeDashboard(toDashboardUiState(readings, LinkState.Ready, now), dangerPulseEnabled = false)
            }
        }
        composeTestRule.onRoot().captureRoboImage(SCREENSHOT_DIR + "gauge_danger_zone.png")
    }

    // OBD-67 round-7 device-verified regression coverage: rounds 5/6 only exercised the
    // CONNECTED banner state (LinkState.Ready renders no banner at all — see ConnectionBanner's
    // own KDoc), so a pixel diff against those references could never have caught the "Retry"
    // label wrapping one-letter-per-line in the disconnected/error state — the exact state a
    // parked/bench dashboard actually shows. These two pin that state's banner as a single short
    // row, in both normal and rearrange mode (round 6 made the top chrome row's HEIGHT invariant
    // between modes; this is the same assertion made concrete for the one state that actually
    // stresses the banner's own internal layout).
    @Config(qualifiers = "w800dp-h360dp-land")
    @Test
    fun `disconnected banner stays a single short row`() {
        composeTestRule.setContent {
            ObdGaugeTheme {
                GaugeDashboard(disconnectedBannerUiState(), onConnect = {}, dangerPulseEnabled = false)
            }
        }
        composeTestRule.onRoot().captureRoboImage(SCREENSHOT_DIR + "dashboard_disconnected_banner.png")
    }

    @Config(qualifiers = "w800dp-h360dp-land")
    @Test
    fun `disconnected banner stays a single short row in rearrange mode too`() {
        composeTestRule.setContent {
            ObdGaugeTheme {
                GaugeDashboard(disconnectedBannerUiState(), onConnect = {}, dangerPulseEnabled = false)
            }
        }
        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage(SCREENSHOT_DIR + "dashboard_disconnected_banner_rearrange.png")
    }

    // LinkState.Error (not just Disconnected) is the more demanding fixture: it renders BOTH the
    // message Text ("Bluetooth is off") AND the separate "Reconnecting…" Text alongside the
    // "Retry" connect button, all in the one banner row — the exact combination round 7 found
    // wrapping on-device. Readings are the same safe fixture `dashboard danger zone tile` uses;
    // only the connection state differs (values themselves aren't what this test is pinning).
    private fun disconnectedBannerUiState(): DashboardUiState {
        val now = Instant.EPOCH
        val readings =
            mapOf(
                PidIds.COOLANT to Reading(PidIds.COOLANT, SAFE_OIL, now, stale = false),
                PidIds.TRANS_TEMP to Reading(PidIds.TRANS_TEMP, SAFE_OIL, now, stale = false),
                PidIds.OIL_TEMP to Reading(PidIds.OIL_TEMP, SAFE_OIL, now, stale = false),
                PidIds.BOOST to Reading(PidIds.BOOST, SAFE_BOOST, now, stale = false),
            )
        return toDashboardUiState(readings, LinkState.Error(LinkError.BluetoothOff), now)
    }

    private fun captureGrid(
        fileName: String,
        grid: GridLayout,
    ) {
        composeTestRule.setContent {
            ObdGaugeTheme {
                GaugeDashboard(
                    sampleUiState(),
                    // OBD-68: gridOf's placements are already repacked at 4 columns (the
                    // landscape/canonical count these `w800dp...-land` screenshots render at) —
                    // keyed there directly, no other orientation needed for a single reference.
                    gridLayoutsByColumns = mapOf(GRID_CANONICAL_COLUMNS to grid),
                    sparklines = sampleSparklines(),
                    dangerPulseEnabled = false,
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(SCREENSHOT_DIR + fileName)
    }

    // Repack into 4 columns (the landscape grid width) so the fixture is a valid, production-shaped
    // layout regardless of the placeholder positions passed in.
    private fun gridOf(vararg placements: GridPlacement): GridLayout =
        GridEngine.repack(columns = 4, ordered = placements.toList())

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

        // Gauge ids used by the OBD-63 spanning-grid fixtures above.
        const val COOLANT = PidIds.COOLANT
        const val OIL = PidIds.OIL_TEMP
        const val TRANS = PidIds.TRANS_TEMP
        const val BOOST = PidIds.BOOST
        const val RPM = PidIds.RPM
        const val SPEED = SPEED_PID_ID

        // OBD-66 danger-zone fixture: coolant/trans above their RED lines, oil/boost safe.
        const val DANGER_COOLANT = 235.0
        const val DANGER_TRANS = 250.0
        const val SAFE_OIL = 200.0
        const val SAFE_BOOST = 8.0

        const val SPARKLINE_SAMPLE_COUNT = 20

        // 250 ms = a nominal 4 Hz cadence, well under SparklineChart's 2 s gap threshold, so the
        // reference image shows a continuous line rather than 20 disconnected points.
        const val SPARKLINE_SAMPLE_INTERVAL_MILLIS = 250L
    }
}
