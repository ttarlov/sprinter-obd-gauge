package com.revel.obdgauge.app.datasource

import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.Reading
import com.revel.obdgauge.model.VehicleDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Placeholder [VehicleDataSource] for the `prod` flavor (OBD-12). Sits permanently at
 * [LinkState.Disconnected] with an empty [readings] map — no BLE scan, no protocol chain,
 * [start] and [stop] are no-ops. The real `ObdLink` → `:core:protocol` → [VehicleDataSource]
 * chain arrives in OBD-25; until then this is what keeps the `prod` flavor buildable and
 * installable without pulling `:core:testing` (and its `FakeVehicleDataSource`) onto its
 * runtime classpath — see the HARD CONSTRAINT in `app/MODULE.md`.
 *
 * Trivially satisfies [VehicleDataSource.start]/[VehicleDataSource.stop]'s restart-safety and
 * idempotence contract: there is nothing to start, replace, or leak.
 */
class StubVehicleDataSource : VehicleDataSource {
    private val mutableReadings = MutableStateFlow<Map<String, Reading>>(emptyMap())
    override val readings: StateFlow<Map<String, Reading>> = mutableReadings.asStateFlow()

    private val mutableConnection = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val connection: StateFlow<LinkState> = mutableConnection.asStateFlow()

    override fun start(pids: List<PidDefinition>) = Unit

    override fun stop() = Unit
}
