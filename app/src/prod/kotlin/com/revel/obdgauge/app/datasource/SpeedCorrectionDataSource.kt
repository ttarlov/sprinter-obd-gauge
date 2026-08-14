package com.revel.obdgauge.app.datasource

import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.Reading
import com.revel.obdgauge.model.VehicleDataSource
import com.revel.obdgauge.protocol.ProtocolPidIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * OBD-61's speedometer-correction seam: multiplies **only** the speed channel's value by the
 * GPS-learned [factor] (true speed = ecu × factor), passing every other channel through
 * untouched.
 *
 * ## Where it sits, and why that commutes
 * The prod chain is `RealVehicleDataSource → DisplayUnitDataSource → SpeedCorrectionDataSource`.
 * By the time a reading reaches here, [DisplayUnitDataSource] has already re-expressed speed in
 * the mph the app catalog declares. The correction is a **unitless** multiplier, so applying it
 * after the km/h→mph conversion gives the identical result as before it — `(kmh × 0.621371) ×
 * factor == (kmh × factor) × 0.621371`. Wrapping *around* [DisplayUnitDataSource] (rather than
 * modifying it) keeps that class the single, untouched home of unit conversion.
 *
 * ## Degrades to identity
 * When [factor] is 1.0 — no GPS, no permission, or calibration still in warm-up — every reading
 * passes through byte for byte. A channel with no speed reading stays absent; nothing is ever
 * materialised. [start]/[stop]/[connection] are pass-throughs, so the frozen `VehicleDataSource`
 * lifecycle contract stays the delegate's.
 */
class SpeedCorrectionDataSource(
    private val delegate: VehicleDataSource,
    private val factor: StateFlow<Double>,
    scope: CoroutineScope,
) : VehicleDataSource {
    override val readings: StateFlow<Map<String, Reading>> =
        combine(delegate.readings, factor, ::applyCorrection)
            .stateIn(scope, SharingStarted.Eagerly, applyCorrection(delegate.readings.value, factor.value))

    override val connection: StateFlow<LinkState> get() = delegate.connection

    override fun start(pids: List<PidDefinition>) = delegate.start(pids)

    override fun stop() = delegate.stop()

    private companion object {
        val SPEED_ID: String = ProtocolPidIds.SPEED

        fun applyCorrection(
            readings: Map<String, Reading>,
            factor: Double,
        ): Map<String, Reading> {
            val speed = readings[SPEED_ID]
            return if (speed == null || factor == 1.0) {
                readings
            } else {
                readings + (SPEED_ID to speed.copy(value = speed.value * factor))
            }
        }
    }
}
