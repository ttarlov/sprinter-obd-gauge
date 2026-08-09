package com.revel.obdgauge.model

/**
 * Canonical channel ids used as [PidDefinition.id] / [Reading.id] keys across every module.
 *
 * These strings are the identity layer between the protocol registry, the scripted fakes in
 * `:core:testing`, and the UI's threshold/config tables (which OBD-21 will persist to user
 * settings) — so they live here in the contract module, not in any implementation. Additive
 * only: renaming a constant is a breaking contract change (see `DECISIONS.md`).
 */
object PidIds {
    /** Engine coolant temperature (standard PID 0105). */
    const val COOLANT: String = "coolant"

    /** Engine oil temperature (mode-22, hypothesis until hardware-verified). */
    const val OIL_TEMP: String = "oilTemp"

    /** Transmission temperature (mode-22 X-Gauge code, hypothesis until hardware-verified). */
    const val TRANS_TEMP: String = "transTemp"

    /** Computed boost: MAP absolute minus barometric (never a raw PID). */
    const val BOOST: String = "boost"

    /** Engine speed (standard PID 010C). */
    const val RPM: String = "rpm"

    /** Barometric pressure (standard PID 0133, SLOW poll priority). */
    const val BARO: String = "baro"
}
