package com.revel.obdgauge.protocol

/**
 * Channel ids for standard PIDs that `:core:model`'s [com.revel.obdgauge.model.PidIds] does not
 * (yet) name.
 *
 * `PidIds` is the frozen contract layer and holds the ids the UI already binds to — `coolant`,
 * `rpm`, `baro`, plus the mode-22 and computed channels. The three PIDs below are polled by the
 * protocol layer but not yet surfaced as their own gauges (`map` feeds the computed boost
 * channel; `iat` and `speed` are registry-complete but unbound), so their ids live here rather
 * than forcing a contract change on a frozen file.
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
}
