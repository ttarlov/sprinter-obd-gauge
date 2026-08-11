package com.revel.obdgauge.app.gauge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.revel.obdgauge.app.settings.AppSettings
import com.revel.obdgauge.app.settings.DEFAULT_GAUGE_ORDER
import com.revel.obdgauge.app.settings.GaugeOrderEntry
import com.revel.obdgauge.app.settings.SettingsRepository
import com.revel.obdgauge.app.settings.effectiveThresholds
import com.revel.obdgauge.app.settings.withGaugeSwapped
import com.revel.obdgauge.app.sparkline.SparklineHistoryHolder
import com.revel.obdgauge.app.sparkline.SparklinePoint
import com.revel.obdgauge.model.VehicleDataSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

/**
 * StateFlow-in, Compose-state-out (per `docs/01-build-plan.md` §2A): formats
 * [VehicleDataSource] output into [DashboardUiState] and nothing else. Zero protocol math —
 * every value it renders arrived already parsed and unit-converted from the data source.
 *
 * @param clock used only to compute "last seen Xs ago" for stale readings; injected (rather
 *   than `Instant.now()`) so tests can supply a fixed instant for deterministic assertions.
 * @param settingsRepository OBD-21 settings (thresholds/units/gauge order/keep-screen-on):
 *   combined into [uiState] so a settings edit recolors/reformats the dashboard live, without
 *   restarting anything.
 */
@HiltViewModel
class DashboardViewModel
    @Inject
    constructor(
        private val dataSource: VehicleDataSource,
        private val clock: Clock,
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        // Per-gauge rolling history for OBD-20's sparklines, fed a step inside the same
        // combine() below. Deliberately NOT part of `uiState`'s DashboardUiState — see
        // SparklineHistoryHolder's KDoc for why folding it in would defeat the whole point of
        // keeping 4 Hz updates from recomposing the entire dashboard.
        // GAUGE_CATALOG (OBD-42's swap-picker superset), not DASHBOARD_PIDS: a gauge swapped
        // into a slot (e.g. rpm) needs its own rolling history too, not just the core four.
        private val sparklineHistory = SparklineHistoryHolder(GAUGE_CATALOG.map { it.id })

        // start()/stop() are driven by [uiState]'s own subscription (onStart/onCompletion,
        // upstream of stateIn) rather than the ViewModel's own init/onCleared lifetime — that
        // way SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS) actually gates polling: the
        // data source starts only once uiState gains its first collector, and stops once the
        // last collector has been gone for STOP_TIMEOUT_MILLIS.
        val uiState: StateFlow<DashboardUiState> =
            combine(
                dataSource.readings,
                dataSource.connection,
                settingsRepository.settings,
            ) { readings, connection, settings ->
                sparklineHistory.onReadings(readings, clock.instant())
                toDashboardUiState(
                    readings,
                    connection,
                    clock.instant(),
                    settings.effectiveThresholds(),
                    settings.units,
                )
            }.onStart { dataSource.start(GAUGE_CATALOG) }
                .onCompletion { dataSource.stop() }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = DashboardUiState.Loading,
                )

        /** Which gauges show, and in what order — OBD-21's settings screen writes this. */
        val gaugeOrder: StateFlow<List<GaugeOrderEntry>> =
            settingsRepository.settings
                .map { it.gaugeOrder }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), DEFAULT_GAUGE_ORDER)

        /** OBD-21's keep-screen-on toggle; `MainActivity` applies it to the window. */
        val keepScreenOn: StateFlow<Boolean> =
            settingsRepository.settings
                .map { it.keepScreenOn }
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    AppSettings().keepScreenOn,
                )

        /** OBD-20's per-gauge sparkline strip — see [SparklineHistoryHolder]. */
        fun sparklineFlow(id: String): StateFlow<List<SparklinePoint>> = sparklineHistory.flowFor(id)

        /**
         * OBD-42: the long-press picker's "tap a candidate" action. Replaces [oldId] with
         * [newId] at that gaugeOrder position (keeping visibility — [AppSettings.withGaugeSwapped])
         * via the exact same [SettingsRepository.update] path [SettingsViewModel]'s mutators use,
         * so the swap persists and the dashboard/settings screen converge on it live, no restart.
         * A no-op when [oldId] equals [newId] (the picker's own "tap the current gauge to dismiss"
         * affordance calls back into UI-only state, never this).
         */
        fun swapGauge(
            oldId: String,
            newId: String,
        ) {
            if (oldId == newId) return
            viewModelScope.launch { settingsRepository.update { it.withGaugeSwapped(oldId, newId) } }
        }

        override fun onCleared() {
            // Belt-and-suspenders: makes teardown deterministic on ViewModel clear rather than
            // relying solely on the async cancellation of the onCompletion above. stop() is
            // idempotent (see FakeVehicleDataSource), so this is safe to call twice.
            dataSource.stop()
            super.onCleared()
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
