package com.revel.obdgauge.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.revel.obdgauge.app.gauge.GaugeThresholds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * StateFlow-in (from [SettingsRepository]), mutation-methods-out. Every mutator round-trips
 * through [SettingsRepository.update] rather than mutating local state directly, so the
 * settings screen and the dashboard (both observing [SettingsRepository.settings], see
 * `gauge/DashboardViewModel.kt`) always converge on the same persisted value — this is what
 * makes the OBD-21 AC "editing a threshold live-recolors the dashboard, no restart" true: the
 * dashboard's `combine()` picks up the new value the moment this repository emits it.
 */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val repository: SettingsRepository,
    ) : ViewModel() {
        val settings: StateFlow<AppSettings> =
            repository.settings.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = AppSettings(),
            )

        fun setGaugeVisible(
            id: String,
            visible: Boolean,
        ) = update { settings ->
            settings.copy(
                gaugeOrder =
                    settings.gaugeOrder.map { entry ->
                        if (entry.id ==
                            id
                        ) {
                            entry.copy(visible = visible)
                        } else {
                            entry
                        }
                    },
            )
        }

        /** Moves gauge [id] by [delta] positions (negative = up/earlier, positive = down/later). */
        fun moveGauge(
            id: String,
            delta: Int,
        ) = update { settings ->
            val order = settings.gaugeOrder.toMutableList()
            val index = order.indexOfFirst { it.id == id }
            if (index < 0) return@update settings
            val target = (index + delta).coerceIn(0, order.lastIndex)
            if (target == index) return@update settings
            val entry = order.removeAt(index)
            order.add(target, entry)
            settings.copy(gaugeOrder = order)
        }

        /** Sets (or, if [thresholds] is null, clears) gauge [id]'s threshold override. */
        fun setThresholdOverride(
            id: String,
            thresholds: GaugeThresholds?,
        ) = update { settings ->
            val overrides = settings.thresholdOverrides.toMutableMap()
            if (thresholds == null) overrides.remove(id) else overrides[id] = thresholds
            settings.copy(thresholdOverrides = overrides)
        }

        fun resetThresholdsToDefault() = update { it.copy(thresholdOverrides = emptyMap()) }

        fun setUnits(units: UnitPreferences) = update { it.copy(units = units) }

        fun setKeepScreenOn(enabled: Boolean) = update { it.copy(keepScreenOn = enabled) }

        fun setShowConnectionStatus(enabled: Boolean) = update { it.copy(showConnectionStatus = enabled) }

        fun setPollRate(rate: PollRate) = update { it.copy(pollRate = rate) }

        private fun update(transform: (AppSettings) -> AppSettings) {
            viewModelScope.launch { repository.update(transform) }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
