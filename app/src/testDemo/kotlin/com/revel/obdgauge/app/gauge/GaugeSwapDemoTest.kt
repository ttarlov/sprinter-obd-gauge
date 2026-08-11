package com.revel.obdgauge.app.gauge

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.revel.obdgauge.app.settings.AppSettings
import com.revel.obdgauge.app.settings.SettingsRepository
import com.revel.obdgauge.app.ui.theme.ObdGaugeTheme
import com.revel.obdgauge.testing.datasource.FakeVehicleDataSource
import com.revel.obdgauge.testing.datasource.Scenario
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * OBD-42 AC: "Demo flavor demonstrates it: RPM available as a swap-in candidate." The rest of
 * the picker's interaction/persistence/correctness coverage lives in `GaugeSwapPickerTest`
 * (`src/test/`, a hand-rolled [com.revel.obdgauge.model.VehicleDataSource] double, runs under
 * both flavors) — this file's one job is proving the *real* demo wiring actually delivers rpm
 * data end to end: `DashboardViewModel` requesting `GAUGE_CATALOG` (not just `DASHBOARD_PIDS`)
 * from the real [FakeVehicleDataSource], which only emits a channel it was asked for
 * (`FakeVehicleDataSource.start`'s `requestedIds` filter — see its KDoc). Regressing that
 * request list back to `DASHBOARD_PIDS` would make every assertion below fail with the
 * `NO_READING_TEXT` placeholder instead of a real value.
 *
 * [FakeVehicleDataSource] defaults to a real `Dispatchers.Default` scope ticking on real
 * wall-clock `delay()`s — fine for the app, but not for a deterministic test. A
 * [TestCoroutineScheduler] driven by [TestCoroutineScheduler.advanceUntilIdle] (a plain, non-
 * suspend call — usable straight from a non-`runTest` JUnit method) runs its scripted replay to
 * completion instantly instead; `composeTestRule.waitForIdle()` then drains the *separate*
 * `viewModelScope`/Compose recomposition pipeline downstream of it — the same proven pattern
 * `DashboardScreenTest`'s `dashboardUiStateFor` and `LiveRecolorTest` already use.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w800dp-h360dp-land")
class GaugeSwapDemoTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the real demo FakeVehicleDataSource delivers a live rpm value to the swap picker`() {
        val scheduler = TestCoroutineScheduler()
        val dataSource =
            FakeVehicleDataSource(
                scenario = Scenario.GRADE_CLIMB,
                scope = CoroutineScope(StandardTestDispatcher(scheduler)),
            )
        val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
        val viewModel = DashboardViewModel(dataSource, clock, DemoInMemorySettingsRepository())

        composeTestRule.setContent {
            ObdGaugeTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val gaugeOrder by viewModel.gaugeOrder.collectAsStateWithLifecycle()
                GaugeDashboard(uiState = uiState, gaugeOrder = gaugeOrder, onSwapGauge = viewModel::swapGauge)
            }
        }
        composeTestRule.waitForIdle()
        // Runs GRADE_CLIMB's whole scripted replay to completion on virtual time — deterministic,
        // no real-time wait needed (GRADE_CLIMB's rpm sweeps 2000-3200, so the tail is non-zero
        // regardless of which tick this lands on).
        scheduler.advanceUntilIdle()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        // A real, non-placeholder rpm value carries the " RPM" suffix (formatGaugeValue) — the
        // placeholder text (NO_READING_TEXT, "—") never does, so this one assertion covers both
        // "it's live data" and "the unit is right".
        composeTestRule
            .onNodeWithTag("gauge-picker-card-rpm-value")
            .performScrollTo()
            .assertTextContains("RPM", substring = true)
    }
}

/** No-DataStore double — mirrors `DashboardViewModelTest`'s own; this file only needs defaults. */
private class DemoInMemorySettingsRepository : SettingsRepository {
    private val state = MutableStateFlow(AppSettings())
    override val settings = state

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        state.value = transform(state.value)
    }
}
