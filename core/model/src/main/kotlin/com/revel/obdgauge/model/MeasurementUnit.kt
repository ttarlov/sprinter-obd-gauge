package com.revel.obdgauge.model

/**
 * Unit a [Reading] value is expressed in. Named `MeasurementUnit` (not `Unit`) to avoid
 * colliding with `kotlin.Unit`.
 *
 * This is a **frozen Phase-0 contract** (see `docs/01-build-plan.md` §0.2 and
 * `DECISIONS.md`). The Phase-0 freeze stands for every member below; the only sanctioned change is
 * **additive** — appending a member for a real physical quantity this van reports that no existing
 * member names. The three at the end arrived that way (OBD-58, DECISIONS.md D9): mass airflow,
 * fuel rate and module voltage are live channels whose scaling was proven ahead of the unit. Order
 * is not load-bearing (the enum is serialized by name, never by `ordinal`), but appending keeps the
 * diff to what it is — an addition — so new members go at the end.
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

    /** Grams per second, used for mass air flow (`0166`). Additive since OBD-58 (D9). */
    GRAMS_PER_SECOND,

    /** Litres per hour, used for engine fuel rate (`015E`). Additive since OBD-58 (D9). */
    LITERS_PER_HOUR,

    /** Volts, used for control-module voltage (`0142`). Additive since OBD-58 (D9). */
    VOLTS,
}
