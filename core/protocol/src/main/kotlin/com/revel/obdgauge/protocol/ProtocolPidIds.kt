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

    /** Intake air temperature (standard PID `010F`), °C. **Unsupported on this van** — see [IAT_SENSOR]. */
    const val IAT: String = "iat"

    /**
     * Intake air temperature from the extended dual-bank PID `0168`, sensor 1, °C — the IAT that
     * this van actually answers (standard `010F`/[IAT] returns `NO DATA`; `0168` fills the gap,
     * `docs/hardware/research-2026-08-13-boost-inference.md`). A distinct id from [IAT] because it
     * is a distinct PID with a distinct availability verdict: `010F` is
     * [ChannelAvailability.UnsupportedByVehicle], `0168` is a live channel. This is the charge-air
     * temperature the speed-density boost model (OBD-57) reads. Value unverified pending a 🖐
     * throttle sweep; decode format high-confidence.
     */
    const val IAT_SENSOR: String = "iatSensor"

    /**
     * Mass air flow from the extended dual-bank PID `0166`, sensor A, g/s — the airflow input to
     * the speed-density boost model (OBD-57). Standard MAF `0110` is unsupported; `0166` is
     * answered.
     *
     * **No polled channel resolves this id yet.** Its natural unit is grams-per-second, which the
     * frozen `:core:model` [com.revel.obdgauge.model.MeasurementUnit] enum does not name, so — like
     * `015E` fuel-rate (L/h) and `0142` module-voltage (V) — MAF cannot become a
     * [StandardPidSpec] without an additive contract change this module will not make on its own
     * authority. The decode lives in [VendoredSaeScaling.massAirFlowGramsPerSecond]; boost declares
     * this id as a dependency and [PidCatalog.availabilityOf] reports it
     * [ChannelAvailability.PendingUnitContract] until the unit lands, so boost reads
     * `MissingInputs(["maf"])` rather than silently claiming it can spool.
     */
    const val MAF: String = "maf"

    /** Vehicle speed (standard PID `010D`), km/h. */
    const val SPEED: String = "speed"

    /** Calculated engine load (standard PID `0104`), percent. */
    const val ENGINE_LOAD: String = "engineLoad"

    /** Throttle position (standard PID `0111`), percent. See [PidRegistry.throttlePosition]. */
    const val THROTTLE: String = "throttle"

    // Transmission fluid temperature is `com.revel.obdgauge.model.PidIds.TRANS_TEMP` — since OBD-55
    // that frozen id resolves to the KWP `21 30` record channel ([TcuRecordRegistry]), which the
    // 2026-08-13 drive test identified. The former protocol-local `TRANS_TEMP_RECORD` alias existed
    // only while the record decode and the falsified X-Gauge decode coexisted under different ids;
    // the id swap removed that split, so no separate constant is needed.

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
