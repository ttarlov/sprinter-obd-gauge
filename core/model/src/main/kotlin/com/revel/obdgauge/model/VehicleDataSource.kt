package com.revel.obdgauge.model

import kotlinx.coroutines.flow.StateFlow

/**
 * What the UI consumes: a stream of typed [Reading]s and the underlying [LinkState], driven
 * by a list of [PidDefinition]s. This is a **frozen Phase-0 contract** (see
 * `docs/01-build-plan.md` §0.2 and `DECISIONS.md`).
 *
 * `:core:protocol` implements this over an [ObdLink], owning the poll scheduler and all
 * protocol/parsing logic. `:core:testing` provides `FakeVehicleDataSource` (scripted
 * scenarios) so the UI agent never needs a real dongle to build or test against.
 *
 * Computed channels (e.g. boost = MAP − baro) are this implementation's responsibility —
 * the UI never does protocol math; it only renders whatever [Reading]s appear in [readings].
 */
interface VehicleDataSource {
    /** Latest reading per PID, keyed by [PidDefinition.id]. Updated in place as new data arrives. */
    val readings: StateFlow<Map<String, Reading>>

    /** Current connection lifecycle state, forwarded from (or derived from) the underlying [ObdLink]. */
    val connection: StateFlow<LinkState>

    /**
     * Begin connecting and polling for exactly the given [pids]. Calling again replaces the
     * active set.
     *
     * Restart-safe by contract: consumers gate polling on UI subscription
     * (`WhileSubscribed`), so implementations MUST tolerate `start` after `stop` (fresh
     * session) and repeated `start` (replace, not duplicate) without leaking a prior
     * poll loop. (Pinned per OBD-10 round-2 review.)
     */
    fun start(pids: List<PidDefinition>)

    /**
     * Stop polling and release the underlying connection. Idempotent by contract: consumers
     * may call this more than once per session (subscription teardown + owner teardown) —
     * a second `stop` MUST be a safe no-op.
     */
    fun stop()
}
