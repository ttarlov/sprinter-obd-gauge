package com.revel.obdgauge.app.gauge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.revel.obdgauge.app.ui.theme.GaugeAmber
import com.revel.obdgauge.app.ui.theme.GaugeGreen
import com.revel.obdgauge.app.ui.theme.GaugeNeutral
import com.revel.obdgauge.app.ui.theme.GaugeRed
import com.revel.obdgauge.app.ui.theme.ObdGaugeTheme
import com.revel.obdgauge.model.LinkError
import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.Reading
import com.revel.obdgauge.testing.datasource.FakeVehicleDataSource
import com.revel.obdgauge.testing.datasource.Scenario
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * Compose-UI + Robolectric assertions against real [FakeVehicleDataSource] scenario scripts
 * (OBD-10 self-test plan): renders [GaugeDashboard] with the scenarios' tail state and asserts
 * both the displayed value text and zone semantics, exposed for testing as each tile's
 * `stateDescription` property (see `DashboardScreen.kt`). Actual pixel color is covered by
 * [DashboardScreenshotTest]'s committed references plus [zoneColor]'s own unit test below.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `IDLE scenario renders green values on all three temperature tiles`() {
        composeTestRule.setContent { ObdGaugeTheme { GaugeDashboard(dashboardUiStateFor(Scenario.IDLE)) } }

        composeTestRule.onNodeWithTag("gauge-coolant-value").assertTextEquals("190°F")
        composeTestRule.onNodeWithTag("gauge-oilTemp-value").assertTextEquals("200°F")
        composeTestRule.onNodeWithTag("gauge-transTemp-value").assertTextEquals("160°F")
        composeTestRule.onNodeWithTag("gauge-coolant").assert(hasZone(ThresholdZone.GREEN))
        composeTestRule.onNodeWithTag("gauge-oilTemp").assert(hasZone(ThresholdZone.GREEN))
        composeTestRule.onNodeWithTag("gauge-transTemp").assert(hasZone(ThresholdZone.GREEN))
    }

    @Test
    fun `TOWN_HEAT_SOAK tail zones follow the OBD-66 seed thresholds`() {
        // OBD-66 seeds: coolant amber≥215/red≥225, trans amber≥215/red≥240, oil amber≥245/red≥260.
        // So the heat-soak tail (coolant 225, oil 240, trans 215) sits coolant at the danger line
        // (RED), oil still under its amber caution (GREEN), and trans in amber. Pulse disabled so
        // the assertion is about zone, not the RED tile's animated frame.
        composeTestRule.setContent {
            ObdGaugeTheme {
                GaugeDashboard(dashboardUiStateFor(Scenario.TOWN_HEAT_SOAK), dangerPulseEnabled = false)
            }
        }

        composeTestRule.onNodeWithTag("gauge-coolant-value").assertTextEquals("225°F")
        composeTestRule.onNodeWithTag("gauge-oilTemp-value").assertTextEquals("240°F")
        composeTestRule.onNodeWithTag("gauge-transTemp-value").assertTextEquals("215°F")
        composeTestRule.onNodeWithTag("gauge-boost-value").assertTextEquals("1.0 PSI")
        composeTestRule.onNodeWithTag("gauge-coolant").assert(hasZone(ThresholdZone.RED))
        composeTestRule.onNodeWithTag("gauge-oilTemp").assert(hasZone(ThresholdZone.GREEN))
        composeTestRule.onNodeWithTag("gauge-transTemp").assert(hasZone(ThresholdZone.AMBER))
        composeTestRule.onNodeWithTag("gauge-boost").assert(hasZone(ThresholdZone.NEUTRAL))
    }

    @Test
    fun `hand-built red-valued readings render the red state`() {
        // TOWN_HEAT_SOAK's tail never crosses a red boundary (coolant tops out at 225, trans
        // at 215), so ThresholdZone.RED's render path needs values built directly rather than
        // borrowed from a scripted scenario — see OBD-10 review B1.
        val now = Instant.EPOCH
        val readings =
            mapOf(
                PidIds.COOLANT to Reading(id = PidIds.COOLANT, value = 235.0, timestamp = now, stale = false),
                PidIds.TRANS_TEMP to Reading(id = PidIds.TRANS_TEMP, value = 255.0, timestamp = now, stale = false),
                PidIds.OIL_TEMP to Reading(id = PidIds.OIL_TEMP, value = 240.0, timestamp = now, stale = false),
                PidIds.BOOST to Reading(id = PidIds.BOOST, value = 8.0, timestamp = now, stale = false),
            )
        val state = toDashboardUiState(readings, LinkState.Ready, now)
        composeTestRule.setContent { ObdGaugeTheme { GaugeDashboard(state) } }

        composeTestRule.onNodeWithTag("gauge-coolant-value").assertTextEquals("235°F")
        composeTestRule.onNodeWithTag("gauge-transTemp-value").assertTextEquals("255°F")
        composeTestRule.onNodeWithTag("gauge-coolant").assert(hasZone(ThresholdZone.RED))
        composeTestRule.onNodeWithTag("gauge-transTemp").assert(hasZone(ThresholdZone.RED))
    }

    @Test
    fun `stale reading renders dimmed value and last-seen text`() {
        val readingTime = Instant.EPOCH
        val now = Instant.EPOCH.plusSeconds(STALE_ELAPSED_SECONDS)
        val readings =
            mapOf(
                PidIds.COOLANT to Reading(id = PidIds.COOLANT, value = 190.0, timestamp = readingTime, stale = true),
            )
        val state = toDashboardUiState(readings, LinkState.Ready, now)
        composeTestRule.setContent { ObdGaugeTheme { GaugeDashboard(state) } }

        composeTestRule.onNodeWithTag("gauge-coolant-value").assertTextEquals("190°F")
        composeTestRule.onNodeWithTag("gauge-coolant-stale").assertTextEquals("last seen ${STALE_ELAPSED_SECONDS}s ago")
    }

    /**
     * OBD-84's #1 gotcha (see `ConnectionBanner.kt`/`DashboardScreen.kt`'s KDoc): the header
     * `Row`'s height must never depend on which connection-status pill state is showing, since
     * that height drives the grid `BoxWithConstraints`' viewport (and thus every tile's size).
     * Every state that can appear on a real dashboard — including both halves of the new
     * permanent `Ready` split — is asserted to render the SAME header height.
     */
    @Test
    fun `header height is identical across every connection-status pill state`() {
        val now = Instant.EPOCH
        val liveReadings = mapOf(PidIds.COOLANT to Reading(PidIds.COOLANT, 190.0, now, stale = false))
        val waitingReadings = mapOf(PidIds.COOLANT to Reading(PidIds.COOLANT, 190.0, now, stale = true))
        val statesByName =
            linkedMapOf(
                "disconnected" to toDashboardUiState(emptyMap(), LinkState.Disconnected, now),
                "connecting" to toDashboardUiState(emptyMap(), LinkState.Connecting, now),
                "ready-live" to toDashboardUiState(liveReadings, LinkState.Ready, now),
                "ready-waiting" to toDashboardUiState(waitingReadings, LinkState.Ready, now),
                "error" to toDashboardUiState(emptyMap(), LinkState.Error(LinkError.Timeout), now),
            )

        // setContent may only be called once per test (see ConnectionBannerTest's own
        // DISCONNECT_RECONNECT walk for the same pattern) — a single composition is driven
        // forward state-by-state via mutableStateOf instead.
        var uiState by mutableStateOf(statesByName.getValue("disconnected"))
        composeTestRule.setContent { ObdGaugeTheme { GaugeDashboard(uiState) } }

        val heightsPx =
            statesByName.mapValues { (_, state) ->
                uiState = state
                composeTestRule.waitForIdle()
                composeTestRule
                    .onNodeWithTag("dashboard-header-row")
                    .fetchSemanticsNode()
                    .size.height
            }

        val distinctHeights = heightsPx.values.toSet()
        assertEquals(
            "expected one constant header height across all states, got $heightsPx",
            1,
            distinctHeights.size,
        )
    }

    @Config(qualifiers = "w360dp-h640dp-port")
    @Test
    fun `portrait layout displays all four gauge values`() {
        composeTestRule.setContent {
            ObdGaugeTheme { GaugeDashboard(dashboardUiStateFor(Scenario.TOWN_HEAT_SOAK)) }
        }

        // OBD-63: portrait is now a 2-column spanning grid (GaugeGrid), so the four default tiles
        // fit as a 2×2 that fills the viewport without scrolling — no performScrollTo() needed (and
        // it would throw, there being no scroll parent when everything already fits).
        GAUGE_VALUE_TAGS.forEach { tag -> composeTestRule.onNodeWithTag(tag).assertIsDisplayed() }
    }

    @Config(qualifiers = "w800dp-h360dp-land")
    @Test
    fun `landscape layout displays all four gauge values`() {
        composeTestRule.setContent {
            ObdGaugeTheme { GaugeDashboard(dashboardUiStateFor(Scenario.TOWN_HEAT_SOAK)) }
        }

        GAUGE_VALUE_TAGS.forEach { tag -> composeTestRule.onNodeWithTag(tag).assertIsDisplayed() }
    }

    @Test
    fun `zoneColor maps each zone to its theme color`() {
        assertEquals(GaugeGreen, zoneColor(ThresholdZone.GREEN))
        assertEquals(GaugeAmber, zoneColor(ThresholdZone.AMBER))
        assertEquals(GaugeRed, zoneColor(ThresholdZone.RED))
        assertEquals(GaugeNeutral, zoneColor(ThresholdZone.NEUTRAL))
    }

    private fun hasZone(zone: ThresholdZone) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, zone.name.lowercase())

    /**
     * Drives the real scenario script to completion on virtual time, then maps it through the
     * same [toDashboardUiState] the ViewModel uses — never a hand-duplicated copy. Runs the
     * fake on `this` (the runTest TestScope), not `backgroundScope`: a `backgroundScope`
     * coroutine parked in `delay()` was observed not to resume under `advanceUntilIdle()` in
     * this coroutines-test version. The replay script is finite, so waiting on `this` is safe.
     */
    private fun dashboardUiStateFor(scenario: Scenario): DashboardUiState {
        lateinit var result: DashboardUiState
        runTest {
            val dataSource = FakeVehicleDataSource(scenario = scenario, scope = this)
            dataSource.start(DASHBOARD_PIDS)
            advanceUntilIdle()
            result = toDashboardUiState(dataSource.readings.value, dataSource.connection.value, Instant.EPOCH)
        }
        return result
    }

    private companion object {
        const val STALE_ELAPSED_SECONDS = 42L
        val GAUGE_VALUE_TAGS =
            listOf("gauge-coolant-value", "gauge-oilTemp-value", "gauge-transTemp-value", "gauge-boost-value")
    }
}
