package com.revel.obdgauge.app.maintenance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.revel.obdgauge.app.maintenance.di.MaintenanceClock
import com.revel.obdgauge.app.settings.SettingsRepository
import com.revel.obdgauge.app.settings.currentOdometerMiles
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

/** One row on the Maintenance list: a catalog item plus its already-computed status. */
data class MaintenanceListRow(
    val item: ServiceItem,
    val status: MaintenanceStatus,
)

sealed interface MaintenanceListUiState {
    data object Loading : MaintenanceListUiState

    data class Loaded(
        val rows: List<MaintenanceListRow>,
        val currentOdometerMiles: Int,
    ) : MaintenanceListUiState
}

/**
 * OBD-79: StateFlow-in (from [MaintenanceRepository] + [SettingsRepository]), mutation-methods-out
 * — same shape [com.revel.obdgauge.app.gauge.DashboardViewModel]/`SettingsViewModel` already use.
 * Every write round-trips through the repositories, never local state, so the list and detail
 * screens (and, on OBD-80, a dashboard reading the same odometer) always converge on one value.
 *
 * @param clock a REAL wall clock ([MaintenanceClock] — see that qualifier's KDoc for why this
 *   can't be the app's shared, flavor-conditional [Clock] binding), used only for "today," to
 *   compute [MaintenanceStatus] countdowns. Injected (not `LocalDate.now()`) so tests supply a
 *   fixed date, same rationale as `DashboardViewModel`'s own [Clock] parameter.
 */
@HiltViewModel
class MaintenanceViewModel
    @Inject
    constructor(
        private val repository: MaintenanceRepository,
        private val settingsRepository: SettingsRepository,
        @MaintenanceClock private val clock: Clock,
    ) : ViewModel() {
        init {
            // Idempotent eager-seed, same pattern DashboardViewModel's grid-layout seed uses:
            // a no-op every call after the first, since seedIfNeeded only inserts into an empty table.
            viewModelScope.launch { repository.seedIfNeeded() }
        }

        val uiState: StateFlow<MaintenanceListUiState> =
            combine(
                repository.serviceItems,
                repository.latestRecordByItemId,
                settingsRepository.settings,
            ) { items, latestByItemId, settings ->
                val today = LocalDate.now(clock)
                val odometer = settings.currentOdometerMiles
                val rows =
                    items.map { item ->
                        MaintenanceListRow(
                            item = item,
                            status = MaintenanceStatus.forItem(item, latestByItemId[item.id], odometer, today),
                        )
                    }
                MaintenanceListUiState.Loaded(rows, odometer)
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                MaintenanceListUiState.Loading,
            )

        /**
         * One item's editable state + record history, for the detail screen. `null` while
         * [itemId] hasn't loaded yet.
         */
        fun detailState(itemId: Long): StateFlow<MaintenanceDetailUiState?> =
            combine(
                repository.serviceItems.map { items -> items.firstOrNull { it.id == itemId } },
                repository.recordsFor(itemId),
                settingsRepository.settings,
            ) { item, records, settings ->
                item?.let { MaintenanceDetailUiState(it, records, settings.currentOdometerMiles) }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

        fun updateServiceItem(item: ServiceItem) {
            viewModelScope.launch { repository.updateServiceItem(item) }
        }

        fun logService(
            serviceItemId: Long,
            performedDateEpochDay: Long,
            odometerMiles: Int,
            notes: String?,
            costCents: Int?,
        ) {
            viewModelScope.launch {
                repository.logService(
                    MaintenanceRecord(
                        serviceItemId = serviceItemId,
                        performedDateEpochDay = performedDateEpochDay,
                        odometerMiles = odometerMiles,
                        notes = notes,
                        costCents = costCents,
                    ),
                )
            }
        }

        /** The "Set odometer" affordance's commit — writes the manual anchor + stamps the entry time. */
        fun setOdometer(miles: Int) {
            viewModelScope.launch {
                val now = clock.millis()
                settingsRepository.update { settings ->
                    settings.copy(
                        odometerAnchorMiles = miles,
                        anchorAtEpochMillis = now,
                        lastManualEntryEpochMillis = now,
                    )
                }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }

/**
 * One item's detail-screen state: the item itself, its record history (newest first), and the
 * live odometer for prefill.
 */
data class MaintenanceDetailUiState(
    val item: ServiceItem,
    val records: List<MaintenanceRecord>,
    val currentOdometerMiles: Int,
)
