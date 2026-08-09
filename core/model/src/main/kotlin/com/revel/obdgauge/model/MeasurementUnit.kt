package com.revel.obdgauge.model

/**
 * Unit a [Reading] value is expressed in. Named `MeasurementUnit` (not `Unit`) to avoid
 * colliding with `kotlin.Unit`.
 *
 * This is a **frozen Phase-0 contract** (see `docs/01-build-plan.md` §0.2 and
 * `DECISIONS.md`).
 */
enum class MeasurementUnit {
    /** Degrees Celsius. */
    CELSIUS,

    /** Degrees Fahrenheit. */
    FAHRENHEIT,

    /** Pounds per square inch (gauge), used for boost. */
    PSI,

    /** Kilopascals (absolute), used for MAP/baro before boost is computed. */
    KPA,

    /** Revolutions per minute. */
    RPM,

    /** Kilometers per hour. */
    KMH,

    /** Miles per hour. */
    MPH,

    /** Percentage (0-100), e.g. throttle position. */
    PERCENT,
}
