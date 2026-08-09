package com.revel.obdgauge.app.gauge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.revel.obdgauge.model.VehicleDataSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import javax.inject.Inject

/**
 * StateFlow-in, Compose-state-out (per `docs/01-build-plan.md` §2A): formats
 * [VehicleDataSource] output into [DashboardUiState] and nothing else. Zero protocol math —
 * every value it renders arrived already parsed and unit-converted from the data source.
 *
 * @param clock used only to compute "last seen Xs ago" for stale readings; injected (rather
 *   than `Instant.now()`) so tests can supply a fixed instant for deterministic assertions.
 */
@HiltViewModel
class DashboardViewModel
    @Inject
    constructor(
        private val dataSource: VehicleDataSource,
        private val clock: Clock,
    ) : ViewModel() {
        // start()/stop() are driven by [uiState]'s own subscription (onStart/onCompletion,
        // upstream of stateIn) rather than the ViewModel's own init/onCleared lifetime — that
        // way SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS) actually gates polling: the
        // data source starts only once uiState gains its first collector, and stops once the
        // last collector has been gone for STOP_TIMEOUT_MILLIS.
        val uiState: StateFlow<DashboardUiState> =
            combine(dataSource.readings, dataSource.connection) { readings, connection ->
                toDashboardUiState(readings, connection, clock.instant())
            }.onStart { dataSource.start(DASHBOARD_PIDS) }
                .onCompletion { dataSource.stop() }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = DashboardUiState.Loading,
                )

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
