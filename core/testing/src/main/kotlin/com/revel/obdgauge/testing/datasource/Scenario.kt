package com.revel.obdgauge.testing.datasource

import com.revel.obdgauge.model.PidIds

/**
 * Named, scripted scenarios [FakeVehicleDataSource] can replay. Each is a fixed narrative used
 * for UI development (`:app`'s `demo` flavor) and exact-sequence unit tests; see
 * `scenarioSteps` in `ScenarioScript.kt` for the underlying scripts.
 */
enum class Scenario {
    /** Engine idling: steady coolant/oil/trans, ~0 boost, ~780 rpm, minor deterministic jitter. */
    IDLE,

    /** Stop-and-go town driving in heat: coolant/oil/trans climb into amber-threshold territory. */
    TOWN_HEAT_SOAK,

    /** Sustained grade climb: boost sweeps 0→15→0 PSI, rpm 2000-3200, temps converge upward. */
    GRADE_CLIMB,

    /**
     * Mid-drive BLE dropout and recovery: connection cycles
     * Ready → Error → Scanning → Connecting → Ready, and readings freeze (frozen value/timestamp,
     * `stale = true`) for the duration of the outage.
     */
    DISCONNECT_RECONNECT,
}

/**
 * Stable channel keys the scripted [Scenario]s emit — aliases of the canonical
 * [com.revel.obdgauge.model.PidIds] contract constants. Production code should reference
 * [com.revel.obdgauge.model.PidIds] directly; this object exists so fixture scripts read
 * naturally and stays for source compatibility.
 */
object ScenarioChannel {
    const val COOLANT = PidIds.COOLANT
    const val OIL_TEMP = PidIds.OIL_TEMP
    const val TRANS_TEMP = PidIds.TRANS_TEMP
    const val BOOST = PidIds.BOOST
    const val RPM = PidIds.RPM
}
