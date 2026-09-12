package com.revel.obdgauge.app.gauge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.revel.obdgauge.app.ui.theme.ObdGaugeTheme
import com.revel.obdgauge.model.LinkError
import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.Reading
import com.revel.obdgauge.testing.datasource.FakeVehicleDataSource
import com.revel.obdgauge.testing.datasource.Scenario
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * [ConnectionBanner] assertions (OBD-11 self-test plan): hand-built [LinkState] values cover
 * each treatment in isolation (mirrors [DashboardScreenTest]'s "hand-built red-valued
 * readings" pattern for states a scripted scenario doesn't hit), and the
 * [Scenario.DISCONNECT_RECONNECT] walk-through covers the full
 * `Ready -> Error -> Scanning -> Connecting -> Ready` sequence a real outage produces, plus
 * "last seen Xs ago" correctness while readings are frozen stale mid-outage.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionBannerTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    // ---- OBD-84: the permanent pill's Ready split ----

    @Test
    fun `Ready with data flowing shows the Live pill`() {
        composeTestRule.setContent {
            ObdGaugeTheme { ConnectionBanner(LinkState.Ready, dataFlowing = true) }
        }

        composeTestRule.onNodeWithTag("connection-banner").assert(hasBannerState("live"))
        composeTestRule.onNodeWithTag("connection-banner-message").assertTextEquals("Live · reading ECU")
    }

    @Test
    fun `Ready with no fresh data shows the waiting-for-ECU pill`() {
        composeTestRule.setContent {
            ObdGaugeTheme { ConnectionBanner(LinkState.Ready, dataFlowing = false) }
        }

        composeTestRule.onNodeWithTag("connection-banner").assert(hasBannerState("waiting"))
        composeTestRule.onNodeWithTag("connection-banner-message").assertTextEquals("Connected · waiting for ECU")
    }

    @Test
    fun `Ready renders nothing when showConnectionStatus is off - the pre-OBD-84 behavior`() {
        composeTestRule.setContent {
            ObdGaugeTheme { ConnectionBanner(LinkState.Ready, dataFlowing = true, showConnectionStatus = false) }
        }

        composeTestRule.onNodeWithTag("connection-banner").assertDoesNotExist()
    }

    @Test
    fun `showConnectionStatus off still shows reconnect feedback - the toggle only gates Ready`() {
        composeTestRule.setContent {
            ObdGaugeTheme { ConnectionBanner(LinkState.Scanning, showConnectionStatus = false) }
        }

        composeTestRule.onNodeWithTag("connection-banner").assert(hasBannerState("scanning"))
    }

    @Test
    fun `Disconnected shows a neutral not-connected banner`() {
        composeTestRule.setContent { ObdGaugeTheme { ConnectionBanner(LinkState.Disconnected) } }

        composeTestRule.onNodeWithTag("connection-banner").assert(hasBannerState("disconnected"))
        composeTestRule.onNodeWithTag("connection-banner-message").assertTextEquals("Not connected")
    }

    // ---- OBD-25: the connect affordance ----

    @Test
    fun `no connect action means no button - the demo flavor's banner is unchanged`() {
        composeTestRule.setContent { ObdGaugeTheme { ConnectionBanner(LinkState.Disconnected) } }

        composeTestRule.onNodeWithTag("connection-banner-connect").assertDoesNotExist()
    }

    @Test
    fun `a connect action renders a Connect button that calls back exactly once per tap`() {
        var taps = 0
        composeTestRule.setContent {
            ObdGaugeTheme { ConnectionBanner(LinkState.Disconnected, onConnect = { taps++ }) }
        }

        composeTestRule.onNodeWithTag("connection-banner-connect").assertTextEquals("Connect")
        composeTestRule.onNodeWithTag("connection-banner-connect").performClick()

        assertEquals(1, taps)
    }

    @Test
    fun `an errored link offers Retry`() {
        composeTestRule.setContent {
            ObdGaugeTheme { ConnectionBanner(LinkState.Error(LinkError.DeviceNotFound), onConnect = {}) }
        }

        composeTestRule.onNodeWithTag("connection-banner-connect").assertTextEquals("Retry")
    }

    // An attempt is already in flight; a second tap would only supersede it and restart
    // :core:ble's backoff from zero — see ConnectActionLabelTest for the full reasoning.
    @Test
    fun `a link mid-attempt offers no button even with a connect action wired`() {
        composeTestRule.setContent {
            ObdGaugeTheme { ConnectionBanner(LinkState.Scanning, onConnect = {}) }
        }

        composeTestRule.onNodeWithTag("connection-banner-connect").assertDoesNotExist()
    }

    @Test
    fun `Scanning shows its status message`() {
        composeTestRule.setContent { ObdGaugeTheme { ConnectionBanner(LinkState.Scanning) } }

        composeTestRule.onNodeWithTag("connection-banner").assert(hasBannerState("scanning"))
        composeTestRule.onNodeWithTag("connection-banner-message").assertTextEquals("Scanning for dongle…")
    }

    @Test
    fun `Connecting shows its status message`() {
        composeTestRule.setContent { ObdGaugeTheme { ConnectionBanner(LinkState.Connecting) } }

        composeTestRule.onNodeWithTag("connection-banner").assert(hasBannerState("connecting"))
        composeTestRule.onNodeWithTag("connection-banner-message").assertTextEquals("Connecting…")
    }

    @Test
    fun `Error shows the cause message and a reconnecting affordance`() {
        composeTestRule.setContent {
            ObdGaugeTheme { ConnectionBanner(LinkState.Error(LinkError.BluetoothOff)) }
        }

        composeTestRule.onNodeWithTag("connection-banner").assert(hasBannerState("error"))
        composeTestRule.onNodeWithTag("connection-banner-message").assertTextEquals("Bluetooth is off")
        composeTestRule.onNodeWithTag("connection-banner-reconnecting").assertTextEquals("Reconnecting…")
    }

    @Test
    fun `zone-tile staleness still renders alongside a visible banner`() {
        // Sanity check that OBD-10's stale-tile treatment (dimmed value + last-seen text) and
        // OBD-11's banner aren't accidentally coupled/exclusive — both render from the same
        // DashboardUiState at once.
        val now = Instant.EPOCH.plusSeconds(STALE_ELAPSED_SECONDS)
        val readings =
            mapOf(
                PidIds.COOLANT to
                    Reading(id = PidIds.COOLANT, value = 190.0, timestamp = Instant.EPOCH, stale = true),
            )
        val state = toDashboardUiState(readings, LinkState.Error(LinkError.Timeout), now)
        composeTestRule.setContent { ObdGaugeTheme { GaugeDashboard(state) } }

        composeTestRule.onNodeWithTag("connection-banner").assert(hasBannerState("error"))
        composeTestRule.onNodeWithTag("gauge-coolant-stale").assertTextEquals("last seen ${STALE_ELAPSED_SECONDS}s ago")
    }

    /**
     * Drives the real [Scenario.DISCONNECT_RECONNECT] script (per its documented sequence:
     * `Ready -> Error(Timeout) -> Scanning -> Connecting -> Ready`, see `ScriptStep.kt`) and
     * records every `(readings, connection)` combination the fake emits along the way — not
     * just its tail state, unlike [DashboardScreenTest]'s scenario tests. Renders each
     * milestone through the real [GaugeDashboard]/[ConnectionBanner] and asserts banner
     * presence/content plus the frozen coolant reading's stale text at each step.
     *
     * The stale-text expectations below are hand-derived from the script's fixed tick
     * arithmetic (500ms default `tickInterval`; see the inline comments at each assertion for
     * the derivation), the same fixture-verification style [DashboardScreenTest] already uses
     * for TOWN_HEAT_SOAK's hardcoded values.
     */
    @Test
    fun `DISCONNECT_RECONNECT walks the banner through every LinkState with correct stale text`() {
        val states = mutableListOf<DashboardUiState>()
        runTest {
            val clock = tickingClock(testScheduler)
            val dataSource = FakeVehicleDataSource(scenario = Scenario.DISCONNECT_RECONNECT, scope = this)
            dataSource.start(DASHBOARD_PIDS)
            val recorder =
                launch {
                    combine(dataSource.readings, dataSource.connection) { readings, connection ->
                        toDashboardUiState(readings, connection, clock.instant())
                    }.collect { states += it }
                }
            advanceUntilIdle()
            recorder.cancel()
        }

        val readyBefore = states.first { it.connection == LinkState.Ready }
        val errorState = states.last { it.connection is LinkState.Error }
        val scanningState = states.last { it.connection == LinkState.Scanning }
        val connectingState = states.last { it.connection == LinkState.Connecting }
        val readyAfter = states.last { it.connection == LinkState.Ready }

        // setContent may only be called once per test; the walk below reuses a single
        // composition and drives it forward with a mutableStateOf, the same way real
        // navigation through app state would recompose GaugeDashboard.
        var uiState by mutableStateOf(readyBefore)
        composeTestRule.setContent { ObdGaugeTheme { GaugeDashboard(uiState) } }

        // Ready (start of the sequence, no reading yet): OBD-84's permanent pill shows
        // "waiting for ECU" rather than hiding — dataFlowing is false with no reading at all.
        assertEquals(false, readyBefore.dataFlowing)
        composeTestRule.onNodeWithTag("connection-banner").assert(hasBannerState("waiting"))
        composeTestRule.onNodeWithTag("connection-banner-message").assertTextEquals("Connected · waiting for ECU")

        // Error(Timeout) — connection just dropped; the coolant reading (last fresh at 191°F,
        // t=1000ms virtual) is now frozen+stale, and only 500ms of real/test time has elapsed
        // (t=1500ms) — under a full second, so the truncating "Xs ago" math reads 0s.
        assertEquals(191.0, errorState.coolant.rawValue, 0.0)
        assertEquals(true, errorState.coolant.isStale)
        uiState = errorState
        composeTestRule.onNodeWithTag("connection-banner").assert(hasBannerState("error"))
        composeTestRule.onNodeWithTag("connection-banner-message").assertTextEquals("Connection timed out")
        composeTestRule.onNodeWithTag("connection-banner-reconnecting").assertTextEquals("Reconnecting…")
        composeTestRule.onNodeWithTag("gauge-coolant-stale").assertTextEquals("last seen 0s ago")

        // Scanning — t=2000ms virtual, 1000ms since the frozen reading: "1s ago".
        uiState = scanningState
        composeTestRule.onNodeWithTag("connection-banner").assert(hasBannerState("scanning"))
        composeTestRule.onNodeWithTag("connection-banner-message").assertTextEquals("Scanning for dongle…")
        composeTestRule.onNodeWithTag("gauge-coolant-stale").assertTextEquals("last seen 1s ago")

        // Connecting — t=2500ms virtual, 1500ms since the frozen reading, truncates to "1s ago".
        uiState = connectingState
        composeTestRule.onNodeWithTag("connection-banner").assert(hasBannerState("connecting"))
        composeTestRule.onNodeWithTag("connection-banner-message").assertTextEquals("Connecting…")
        composeTestRule.onNodeWithTag("gauge-coolant-stale").assertTextEquals("last seen 1s ago")

        // Ready again, post-recovery: fresh (non-stale) 192°F reading means dataFlowing is true,
        // so OBD-84's pill now reads "Live" rather than hiding.
        assertEquals(192.0, readyAfter.coolant.rawValue, 0.0)
        assertEquals(false, readyAfter.coolant.isStale)
        assertEquals(true, readyAfter.dataFlowing)
        uiState = readyAfter
        composeTestRule.onNodeWithTag("connection-banner").assert(hasBannerState("live"))
        composeTestRule.onNodeWithTag("connection-banner-message").assertTextEquals("Live · reading ECU")
        composeTestRule.onNodeWithTag("gauge-coolant-value").assertTextEquals("192°F")
    }

    private fun hasBannerState(name: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, name)

    private companion object {
        const val STALE_ELAPSED_SECONDS = 42L
    }
}

/**
 * A [Clock] whose [Clock.instant] tracks a [TestCoroutineScheduler]'s virtual time, anchored
 * at [Instant.EPOCH] — the test-side equivalent of the `demo` flavor's real-wall-clock-offset
 * [Clock] (`src/demo/.../di/DataSourceModule.kt`): both read `EPOCH + elapsed-since-anchor`,
 * which is exactly what keeps "last seen Xs ago" math sane against
 * [FakeVehicleDataSource]'s EPOCH-based virtual reading timestamps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun tickingClock(scheduler: TestCoroutineScheduler): Clock =
    object : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.EPOCH.plusMillis(scheduler.currentTime)
    }
