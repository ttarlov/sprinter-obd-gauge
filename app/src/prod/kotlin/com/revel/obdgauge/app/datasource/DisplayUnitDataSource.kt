package com.revel.obdgauge.app.datasource

import com.revel.obdgauge.app.gauge.GAUGE_CATALOG_BY_ID
import com.revel.obdgauge.app.gauge.UnitConversion
import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.Reading
import com.revel.obdgauge.model.VehicleDataSource
import com.revel.obdgauge.protocol.PidCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Re-expresses `:core:protocol`'s readings in the units `:app`'s gauge catalog declares — the
 * "resolve this at Phase-4 integration" item `core/protocol/MODULE.md` has been carrying since
 * OBD-13 (OBD-25).
 *
 * ## The mismatch, and why it is fixed *here*
 *
 * `:core:protocol` parses every channel to its natural SI-ish unit (`CELSIUS`, `KPA`, `RPM`,
 * `PERCENT`) so that boost's `MAP − baro` subtracts two commensurate absolute pressures.
 * `:app`'s [com.revel.obdgauge.app.gauge.DASHBOARD_PIDS] declares `coolant` as `FAHRENHEIT` and
 * `boost` as `PSI`, because that is what the `demo` flavor's `FakeVehicleDataSource` emits — and
 * **[com.revel.obdgauge.app.gauge.ThresholdConfig]'s amber/red values, plus every user threshold
 * override persisted by OBD-21, are stored in those same declared "wire" units.**
 *
 * `core/protocol/MODULE.md` names the two legal landing spots: convert at the UI boundary, or
 * change `DASHBOARD_PIDS`' declared units. This is the first. Changing the declared units was
 * rejected because it silently reinterprets every threshold already persisted on Taras's phone
 * (a 230 °F red line would become a 230 °C one) and would change what the `demo` flavor renders,
 * which this issue explicitly holds fixed.
 *
 * So: the conversion happens once, at the `prod` DI seam, and everything downstream —
 * thresholds, OBD-21's display-unit preference, the sparkline buffers, the notification's
 * headline reading — keeps working against exactly one declared unit per channel, unchanged.
 * `UnitConversion` was already written to read the *declared* unit as its `from` rather than
 * assuming one (see its KDoc), so nothing in `src/main/` needs to know this class exists.
 *
 * Channels `:app` has no gauge for (`engineLoad`, `throttle`, `map`, `speed`, `iat`) pass
 * through untouched: there is no declared display unit to convert *to*, and inventing one would
 * be worse than leaving the protocol's own.
 *
 * ## What it does not do
 *
 * No filtering, no defaulting, no substitution. A channel absent from [VehicleDataSource.readings]
 * stays absent — the typed-unavailable story (boost with no MAP on this van) is expressed by
 * absence plus `PollEvent.ChannelAvailabilityChanged`, and a wrapper that quietly materialised a
 * zero here would undo the whole point of `ComputedChannels.boost` returning `null`.
 *
 * [start]/[stop]/[connection] are pass-throughs, so the frozen `VehicleDataSource` lifecycle
 * contract (start replaces, stop is idempotent, `connection` forwards the link's state) is the
 * delegate's, unmodified.
 *
 * @param scope the scope the conversion collector runs in; a singleton app-level scope, so the
 *   converted flow is live for as long as the delegate is.
 */
class DisplayUnitDataSource(
    private val delegate: VehicleDataSource,
    scope: CoroutineScope,
) : VehicleDataSource {
    override val readings: StateFlow<Map<String, Reading>> =
        delegate.readings
            .map(::toDeclaredUnits)
            .stateIn(scope, SharingStarted.Eagerly, toDeclaredUnits(delegate.readings.value))

    override val connection: StateFlow<LinkState> get() = delegate.connection

    override fun start(pids: List<PidDefinition>) = delegate.start(pids)

    override fun stop() = delegate.stop()

    private fun toDeclaredUnits(readings: Map<String, Reading>): Map<String, Reading> =
        readings.mapValues { (id, reading) ->
            val from = PROTOCOL_UNITS[id] ?: return@mapValues reading
            val to = GAUGE_CATALOG_BY_ID[id]?.unit ?: return@mapValues reading
            if (from == to) reading else reading.copy(value = UnitConversion.convert(reading.value, from, to))
        }

    private companion object {
        /** The unit each channel actually arrives in, straight from the protocol catalog. */
        val PROTOCOL_UNITS: Map<String, MeasurementUnit> =
            PidCatalog.definitions.associate { it.id to it.unit }
    }
}
