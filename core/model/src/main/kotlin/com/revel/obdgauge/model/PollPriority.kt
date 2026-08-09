package com.revel.obdgauge.model

/**
 * How often a [PidDefinition] is polled by the scheduler in `:core:protocol`. This is a
 * **frozen Phase-0 contract** (see `docs/01-build-plan.md` §0.2 and `DECISIONS.md`).
 */
enum class PollPriority {
    /** Polled every scheduler cycle — values that change quickly, e.g. boost, RPM. */
    FAST,

    /** Polled every Nth scheduler cycle — slow-changing values, e.g. temperatures, baro. */
    SLOW,
}
