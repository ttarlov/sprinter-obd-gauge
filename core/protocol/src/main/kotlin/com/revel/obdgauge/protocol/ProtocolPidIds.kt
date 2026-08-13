package com.revel.obdgauge.protocol

/**
 * Channel ids for standard PIDs that `:core:model`'s [com.revel.obdgauge.model.PidIds] does not
 * (yet) name.
 *
 * `PidIds` is the frozen contract layer and holds the ids the UI already binds to — `coolant`,
 * `rpm`, `baro`, plus the mode-22 and computed channels. The PIDs below are known to the
 * protocol layer but not surfaced as their own gauges by the frozen contract (`map` feeds the
 * computed boost channel; `iat`, `speed`, `engineLoad` and `throttle` are registry-complete and
 * available to OBD-42's swap catalog), so their ids live here rather than forcing a contract
 * change on a frozen file.
 *
 * Naming follows `PidIds`' convention exactly: the constant is the SCREAMING_SNAKE form of a
 * camelCase id string, and abbreviations stay abbreviated (`PidIds.BARO = "baro"`, so
 * `MAP = "map"` and `IAT = "iat"`). If one of these later earns a gauge, promoting it into
 * `PidIds` is a value-preserving move: the id string does not change.
 */
object ProtocolPidIds {
    /** Intake manifold absolute pressure (standard PID `010B`), kPa absolute. */
    const val MAP: String = "map"

    /** Intake air temperature (standard PID `010F`), °C. */
    const val IAT: String = "iat"

    /** Vehicle speed (standard PID `010D`), km/h. */
    const val SPEED: String = "speed"

    /** Calculated engine load (standard PID `0104`), percent. */
    const val ENGINE_LOAD: String = "engineLoad"

    /** Throttle position (standard PID `0111`), percent. See [PidRegistry.throttlePosition]. */
    const val THROTTLE: String = "throttle"

    /**
     * Transmission fluid temperature read out of the TCU's KWP `21 30` record (OBD-49), °C.
     *
     * Deliberately **not** [com.revel.obdgauge.model.PidIds.TRANS_TEMP]: that id belongs to the
     * X-Gauge-derived hypothesis in [MercedesPidRegistry], and the two are different decodes of
     * the same request. See [TcuRecordRegistry] for which one the 2026-08-12 capture supports and
     * why this branch does not silently swap them.
     */
    const val TRANS_TEMP_RECORD: String = "transTempRecord"

    // --- OBD-50 (session 2, 2026-08-13): live-verified standard PIDs with no dashboard gauge ---

    /** Fuel level input (standard PID `012F`), percent. */
    const val FUEL_LEVEL: String = "fuelLevel"

    /** Ambient air temperature (standard PID `0146`), °C. */
    const val AMBIENT_TEMP: String = "ambientTemp"

    /** Accelerator pedal position D (standard PID `0149`), percent. */
    const val ACCEL_PEDAL: String = "accelPedal"

    /** Engine's demand (driver's intended) percent torque (standard PID `0161`), percent. */
    const val DEMAND_TORQUE: String = "demandTorque"

    /** Engine's actual percent torque (standard PID `0162`), percent. */
    const val ACTUAL_TORQUE: String = "actualTorque"
}
