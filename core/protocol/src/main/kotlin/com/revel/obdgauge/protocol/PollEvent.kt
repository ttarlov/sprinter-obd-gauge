package com.revel.obdgauge.protocol

/**
 * Something the poll loop did that is worth seeing but is not a [com.revel.obdgauge.model.Reading].
 *
 * Typed rather than logged as strings, for two reasons. A skipped reading is the loop's *normal*
 * way of refusing a doubtful value, so it has to be observable — a silently skipped PID and a
 * silently wrong PID look identical from the outside, and only one of them is acceptable. And the
 * debug console (OBD-19) and hardware bring-up (OBD-22) both need to render these, which means
 * they need structure, not prose.
 *
 * `:core:protocol` has no logger dependency by design (pure Kotlin/JVM, `DECISIONS.md` D2), so
 * these are handed to a caller-supplied sink; see [RealVehicleDataSource].
 */
sealed interface PollEvent {
    /** The ELM327 init sequence completed; polling starts now. Once per session. */
    data class Initialized(
        val result: InitResult.Success,
    ) : PollEvent

    /** Init failed, so the session never started polling. The loop parks; see [RealVehicleDataSource]. */
    data class InitFailed(
        val failure: InitFailure,
    ) : PollEvent

    /**
     * One PID produced no usable value this cycle and was skipped: its previous reading stands and
     * ages toward stale. Routine on a real bus (a PID the ECU does not implement answers `NO DATA`
     * forever).
     */
    data class ReadingSkipped(
        val id: String,
        val reason: ParseFailure,
    ) : PollEvent

    /** The dongle refused an `ATSH`/`ATCRA` framing command, so a manufacturer PID was not sent. */
    data class HeaderRejected(
        val id: String,
        val command: String,
        val raw: String,
    ) : PollEvent

    /**
     * Restoring the default header after a manufacturer PID failed, leaving the dongle addressed
     * at a non-default ECU. The next cycle retries before polling anything standard.
     */
    data object HeaderRestoreFailed : PollEvent

    /** The link dropped or timed out; the loop parks. [id] is the PID in flight, if any. */
    data class LinkDropped(
        val id: String?,
        val message: String,
    ) : PollEvent

    /**
     * `start` was asked for a channel [PidCatalog] does not know, so it cannot be polled. A
     * wiring bug (an id typo, or a placeholder definition that never got replaced) — loud here
     * rather than a gauge that simply never updates.
     */
    data class UnknownPid(
        val id: String,
    ) : PollEvent
}
