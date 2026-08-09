package com.revel.obdgauge.app.gauge

import androidx.compose.runtime.Immutable
import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.Reading
import java.time.Instant

/**
 * Compose-facing state for one gauge tile. Formatting only — see [GaugeFormatting].
 *
 * [Immutable]-annotated for the same reason as [DashboardUiState]: explicit, rather than
 * relying on the Compose compiler's stability inference to hold across every field.
 */
@Immutable
data class GaugeTileUiState(
    val id: String,
    val label: String,
    val valueText: String,
    val zone: ThresholdZone,
    val isStale: Boolean,
    val staleText: String?,
    /** Raw value backing [BoostArc]'s sweep position; ignored by non-boost tiles. */
    val rawValue: Double,
) {
    companion object {
        /** Shown before any [Reading] has arrived for [id]. */
        fun placeholder(
            id: String,
            label: String,
        ) = GaugeTileUiState(
            id = id,
            label = label,
            valueText = NO_READING_TEXT,
            zone = ThresholdZone.NEUTRAL,
            isStale = false,
            staleText = null,
            rawValue = 0.0,
        )
    }
}

/**
 * Full dashboard render state: the four gauge tiles plus the underlying [LinkState].
 *
 * [Immutable]-annotated: every property is a val of a stable type, but [LinkState] is a
 * sealed interface defined in `:core:model`, so the Compose compiler can't infer its
 * stability across the module boundary without this explicit annotation — without it, every
 * recomposition would treat this class as potentially-unstable input.
 */
@Immutable
data class DashboardUiState(
    val coolant: GaugeTileUiState,
    val transTemp: GaugeTileUiState,
    val oilTemp: GaugeTileUiState,
    val boost: GaugeTileUiState,
    val connection: LinkState,
) {
    companion object {
        val Loading =
            DashboardUiState(
                coolant = GaugeTileUiState.placeholder(PidIds.COOLANT, "Coolant"),
                transTemp = GaugeTileUiState.placeholder(PidIds.TRANS_TEMP, "Trans"),
                oilTemp = GaugeTileUiState.placeholder(PidIds.OIL_TEMP, "Oil"),
                boost = GaugeTileUiState.placeholder(PidIds.BOOST, "Boost"),
                connection = LinkState.Disconnected,
            )
    }
}

/**
 * Pure mapper from [VehicleDataSource][com.revel.obdgauge.model.VehicleDataSource] output to
 * [DashboardUiState]. Shared by [DashboardViewModel] and UI tests so tests exercise the exact
 * same formatting/threshold-classification code path the app renders with — never a
 * hand-duplicated copy of it.
 */
fun toDashboardUiState(
    readings: Map<String, Reading>,
    connection: LinkState,
    now: Instant,
): DashboardUiState =
    DashboardUiState(
        coolant = tileState(PidIds.COOLANT, "Coolant", MeasurementUnit.FAHRENHEIT, readings, now),
        transTemp = tileState(PidIds.TRANS_TEMP, "Trans", MeasurementUnit.FAHRENHEIT, readings, now),
        oilTemp = tileState(PidIds.OIL_TEMP, "Oil", MeasurementUnit.FAHRENHEIT, readings, now),
        boost = tileState(PidIds.BOOST, "Boost", MeasurementUnit.PSI, readings, now),
        connection = connection,
    )

private fun tileState(
    id: String,
    label: String,
    unit: MeasurementUnit,
    readings: Map<String, Reading>,
    now: Instant,
): GaugeTileUiState {
    val reading = readings[id] ?: return GaugeTileUiState.placeholder(id, label)
    return GaugeTileUiState(
        id = id,
        label = label,
        valueText = formatGaugeValue(reading.value, unit),
        zone = ThresholdConfig.classify(id, reading.value),
        isStale = reading.stale,
        staleText = if (reading.stale) formatStaleText(reading, now) else null,
        rawValue = reading.value,
    )
}
