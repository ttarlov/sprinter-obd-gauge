package com.revel.obdgauge.app.sparkline

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.revel.obdgauge.app.gauge.DashboardUiState
import com.revel.obdgauge.app.gauge.GaugeDashboard
import com.revel.obdgauge.app.gauge.GaugeTileUiState
import com.revel.obdgauge.app.gauge.ThresholdZone
import com.revel.obdgauge.app.ui.theme.ObdGaugeTheme
import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.PidIds
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * OBD-20 AC: "No dropped frames or jank at a 4 Hz update rate" — the headless half of that (the
 * other half, real frame timing, needs a device macrobenchmark; see `app/MODULE.md`, tracked as
 * OBD-34). This asserts the *architectural* guarantee that makes jank unlikely: a sparkline
 * tick, driven independently of `DashboardUiState`, recomposes only the leaf that collects it
 * (`GaugeSparklineStrip` in `DashboardScreen.kt`), never [GaugeDashboard] itself — see
 * `SparklineHistoryHolder`'s KDoc for the architecture this guards.
 *
 * The companion pure-cost bound on downsampling itself lives in
 * `SparklineBufferTest.downsample cost is bounded regardless of input size`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w800dp-h360dp-land")
class SparklineRecompositionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `pumping sparkline updates at 4 Hz does not recompose GaugeDashboard's own scope`() {
        var dashboardScopeRecompositions = 0
        // Counts recompositions of GaugeDashboard's OWN composable body: this Modifier is
        // passed straight into `GaugeDashboard`'s `modifier` param, applied to its outermost
        // `Surface` — `composed {}`'s factory lambda re-runs exactly when the composable it's
        // attached to recomposes (review round-1 M3: the prior version of this test only
        // counted recompositions of the *test's own* wrapping scope, which a mutation lifting
        // `collectAsStateWithLifecycle()` up into `GaugeDashboard` couldn't fail — the wrapper
        // never reads the flow either way. Measuring GaugeDashboard's own scope directly closes
        // that gap.)
        val countingModifier =
            Modifier.composed {
                SideEffect { dashboardScopeRecompositions++ }
                Modifier
            }
        val sparklineFlow = MutableStateFlow<List<SparklinePoint>>(emptyList())
        val fixedUiState = fixedUiState()

        composeTestRule.setContent {
            ObdGaugeTheme {
                GaugeDashboard(
                    uiState = fixedUiState,
                    modifier = countingModifier,
                    sparklines = mapOf(PidIds.COOLANT to sparklineFlow),
                )
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(1, dashboardScopeRecompositions)

        // Simulate a 4 Hz stream: 20 ticks, as if replaying 5 s of real polling.
        val start = Instant.EPOCH
        repeat(SIMULATED_TICKS) { i ->
            sparklineFlow.value = listOf(SparklinePoint(start.plusMillis(i * FOUR_HZ_INTERVAL_MS), i.toDouble()))
        }
        composeTestRule.waitForIdle()

        assertEquals(
            "sparkline ticks must not recompose GaugeDashboard's own scope",
            1,
            dashboardScopeRecompositions,
        )
    }

    @Test
    fun `the sparkline leaf itself still renders once data arrives`() {
        val sparklineFlow = MutableStateFlow<List<SparklinePoint>>(emptyList())
        composeTestRule.setContent {
            ObdGaugeTheme {
                GaugeDashboard(uiState = fixedUiState(), sparklines = mapOf(PidIds.COOLANT to sparklineFlow))
            }
        }

        val start = Instant.EPOCH
        sparklineFlow.value =
            (0 until SIMULATED_TICKS).map { i ->
                SparklinePoint(start.plusMillis(i * FOUR_HZ_INTERVAL_MS), i.toDouble())
            }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("gauge-${PidIds.COOLANT}-sparkline").assertIsDisplayed()
    }

    // B8 (round-1 review): GaugeTileUiState's `verified` now defaults to `false` — pass it
    // explicitly here so this fixture matches the real catalog (coolant/boost verified, trans/oil
    // not) rather than accidentally exercising the unverified-badge overlay on all four tiles,
    // which this recomposition-scoping test has no interest in.
    private fun fixedUiState() =
        DashboardUiState(
            coolant =
                GaugeTileUiState(PidIds.COOLANT, "Coolant", "190°F", ThresholdZone.GREEN, false, null, 190.0, true),
            transTemp =
                GaugeTileUiState(PidIds.TRANS_TEMP, "Trans", "160°F", ThresholdZone.GREEN, false, null, 160.0, false),
            oilTemp =
                GaugeTileUiState(PidIds.OIL_TEMP, "Oil", "200°F", ThresholdZone.GREEN, false, null, 200.0, false),
            boost =
                GaugeTileUiState(PidIds.BOOST, "Boost", "0.0 PSI", ThresholdZone.NEUTRAL, false, null, 0.0, true),
            connection = LinkState.Ready,
        )

    private companion object {
        const val SIMULATED_TICKS = 20
        const val FOUR_HZ_INTERVAL_MS = 250L
    }
}
