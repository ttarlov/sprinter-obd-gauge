package com.revel.obdgauge.app.gauge

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.revel.obdgauge.app.ui.theme.ObdGaugeTheme
import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.Reading
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * OBD-72's Roborazzi references for the two new render styles, rendered inside the REAL
 * [GaugeDashboard] (not an isolated composable) — same discipline `DashboardScreenshotTest`/
 * `GaugePickerScreenshotTest` already use, so these references show exactly what a style pick
 * actually looks like on the dash, chrome and all: the outer tile's threshold-tinted
 * background/danger-pulse border stays underneath the needle/bar-arc content (see [GaugeTile]'s
 * KDoc on why that carries "for free"), so these screenshots double as proof the "coloring is
 * sacred across every style" contract holds, not just a look at the new Canvas drawing in
 * isolation.
 *
 * Coolant is the representative temp gauge (`issues/OBD-72.md`'s own example channel) — its
 * [PidIds.COOLANT] threshold seed (green <215 / amber 215–225 / red ≥225 °F, `ThresholdConfig.kt`)
 * gives clean green/amber/red fixture values. `renderStyles` is passed straight into
 * [GaugeDashboard] rather than driven through the picker UI (`GaugePickerScreenshotTest` already
 * covers that flow's interaction/structure) — these references are about the STYLE, not the path
 * to reach it.
 *
 * Same "captureRoboImage no-ops outside the dedicated Roborazzi tasks" note as
 * `DashboardScreenshotTest` applies here — see its KDoc.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GaugeRenderStyleScreenshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Config(qualifiers = "w800dp-h360dp-land")
    @Test
    fun `needle style, green zone, landscape`() {
        capture("gauge_needle_green_landscape.png", GaugeRenderStyle.NEEDLE, GREEN_COOLANT)
    }

    @Config(qualifiers = "w360dp-h640dp-port")
    @Test
    fun `needle style, green zone, portrait`() {
        capture("gauge_needle_green_portrait.png", GaugeRenderStyle.NEEDLE, GREEN_COOLANT)
    }

    @Config(qualifiers = "w800dp-h360dp-land")
    @Test
    fun `needle style, amber zone, landscape`() {
        capture("gauge_needle_amber_landscape.png", GaugeRenderStyle.NEEDLE, AMBER_COOLANT)
    }

    // Danger (RED) zone — pulse off (same discipline as DashboardScreenshotTest's own danger
    // reference) so this is a stable single frame rather than a point on the pulse cycle, and so
    // the needle's own RED zone-arc band is unambiguously visible.
    @Config(qualifiers = "w800dp-h360dp-land")
    @Test
    fun `needle style, red danger zone, landscape`() {
        capture("gauge_needle_red_landscape.png", GaugeRenderStyle.NEEDLE, RED_COOLANT)
    }

    @Config(qualifiers = "w800dp-h360dp-land")
    @Test
    fun `needle style, stale reading, landscape`() {
        capture("gauge_needle_stale_landscape.png", GaugeRenderStyle.NEEDLE, GREEN_COOLANT, stale = true)
    }

    @Config(qualifiers = "w800dp-h360dp-land")
    @Test
    fun `bar-arc style, green zone, landscape`() {
        capture("gauge_bar_arc_green_landscape.png", GaugeRenderStyle.BAR_ARC, GREEN_COOLANT)
    }

    @Config(qualifiers = "w360dp-h640dp-port")
    @Test
    fun `bar-arc style, green zone, portrait`() {
        capture("gauge_bar_arc_green_portrait.png", GaugeRenderStyle.BAR_ARC, GREEN_COOLANT)
    }

    @Config(qualifiers = "w800dp-h360dp-land")
    @Test
    fun `bar-arc style, amber zone, landscape`() {
        capture("gauge_bar_arc_amber_landscape.png", GaugeRenderStyle.BAR_ARC, AMBER_COOLANT)
    }

    @Config(qualifiers = "w800dp-h360dp-land")
    @Test
    fun `bar-arc style, red danger zone, landscape`() {
        capture("gauge_bar_arc_red_landscape.png", GaugeRenderStyle.BAR_ARC, RED_COOLANT)
    }

    @Config(qualifiers = "w800dp-h360dp-land")
    @Test
    fun `bar-arc style, stale reading, landscape`() {
        capture("gauge_bar_arc_stale_landscape.png", GaugeRenderStyle.BAR_ARC, GREEN_COOLANT, stale = true)
    }

    // OBD-72's "legibility at small tile sizes" call: a 2x1 wide boost tile leaves every OTHER
    // gauge (coolant included) squeezed into the grid's remaining 1x1 cells — below
    // GAUGE_COMPACT_SIZE_DP, so this reference documents the auto-simplified look (thinned ticks/
    // no tick labels for needle, fewer/chunkier segments for bar-arc) side by side with the
    // full-size references above.
    @Config(qualifiers = "w800dp-h360dp-land")
    @Test
    fun `needle style, compact 1x1 tile, landscape`() {
        captureCompact("gauge_needle_compact_landscape.png", GaugeRenderStyle.NEEDLE)
    }

    @Config(qualifiers = "w800dp-h360dp-land")
    @Test
    fun `bar-arc style, compact 1x1 tile, landscape`() {
        captureCompact("gauge_bar_arc_compact_landscape.png", GaugeRenderStyle.BAR_ARC)
    }

    private fun capture(
        fileName: String,
        style: GaugeRenderStyle,
        coolantValue: Double,
        stale: Boolean = false,
    ) {
        val now = Instant.EPOCH
        val readings =
            mapOf(
                PidIds.COOLANT to Reading(PidIds.COOLANT, coolantValue, now, stale = stale),
                PidIds.TRANS_TEMP to Reading(PidIds.TRANS_TEMP, SAFE_TRANS, now, stale = false),
                PidIds.OIL_TEMP to Reading(PidIds.OIL_TEMP, SAFE_OIL, now, stale = false),
                PidIds.BOOST to Reading(PidIds.BOOST, SAFE_BOOST, now, stale = false),
            )
        composeTestRule.setContent {
            ObdGaugeTheme {
                GaugeDashboard(
                    toDashboardUiState(readings, LinkState.Ready, now),
                    renderStyles = mapOf(PidIds.COOLANT to style),
                    dangerPulseEnabled = false,
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(SCREENSHOT_DIR + fileName)
    }

    // Boost 2-wide forces every 1x1 gauge (coolant included) into the grid's smallest cell, well
    // under GAUGE_COMPACT_SIZE_DP at this viewport — see GaugeTileGrid/GridEngine for the packing.
    private fun captureCompact(
        fileName: String,
        style: GaugeRenderStyle,
    ) {
        val now = Instant.EPOCH
        val readings =
            mapOf(
                PidIds.COOLANT to Reading(PidIds.COOLANT, GREEN_COOLANT, now, stale = false),
                PidIds.TRANS_TEMP to Reading(PidIds.TRANS_TEMP, SAFE_TRANS, now, stale = false),
                PidIds.OIL_TEMP to Reading(PidIds.OIL_TEMP, SAFE_OIL, now, stale = false),
                PidIds.BOOST to Reading(PidIds.BOOST, SAFE_BOOST, now, stale = false),
            )
        val grid =
            com.revel.obdgauge.app.gauge.grid.GridEngine.repack(
                columns = 4,
                ordered =
                    listOf(
                        com.revel.obdgauge.app.gauge.grid
                            .GridPlacement(PidIds.BOOST, 0, 0, colSpan = 2, rowSpan = 1),
                        com.revel.obdgauge.app.gauge.grid
                            .GridPlacement(PidIds.COOLANT, 0, 0),
                        com.revel.obdgauge.app.gauge.grid
                            .GridPlacement(PidIds.OIL_TEMP, 0, 0),
                        com.revel.obdgauge.app.gauge.grid
                            .GridPlacement(PidIds.TRANS_TEMP, 0, 0),
                    ),
            )
        composeTestRule.setContent {
            ObdGaugeTheme {
                GaugeDashboard(
                    toDashboardUiState(readings, LinkState.Ready, now),
                    gridLayoutsByColumns = mapOf(GRID_CANONICAL_COLUMNS to grid),
                    renderStyles = mapOf(PidIds.COOLANT to style),
                    dangerPulseEnabled = false,
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(SCREENSHOT_DIR + fileName)
    }

    private companion object {
        // Same path-resolution note as DashboardScreenshotTest.SCREENSHOT_DIR.
        const val SCREENSHOT_DIR = "src/testDemo/screenshots/"

        // ThresholdConfig.seed's coolant band: green <215 / amber 215-225 / red >=225 °F.
        const val GREEN_COOLANT = 180.0
        const val AMBER_COOLANT = 220.0
        const val RED_COOLANT = 230.0
        const val SAFE_TRANS = 180.0
        const val SAFE_OIL = 200.0
        const val SAFE_BOOST = 8.0
    }
}
