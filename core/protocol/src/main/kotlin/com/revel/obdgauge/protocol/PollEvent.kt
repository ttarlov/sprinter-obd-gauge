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

    /**
     * A requested channel cannot produce a value on this vehicle, said in types rather than
     * leaving a gauge indistinguishable from a broken one.
     *
     * Emitted by [RealVehicleDataSource] **once per session, at plan time, before the first
     * command goes out** — the verdict comes from [PidCatalog.availabilityOf]'s recorded survey,
     * not from the wire, so there is nothing to wait for. Only non-[ChannelAvailability.Available]
     * verdicts are emitted; a healthy channel produces no event, and there is **no** recovery
     * event within a session (the survey table cannot change mid-session). A consumer that wants
     * "current availability" should latch the events from session start; silence means available.
     * (Round-1 review MAJOR-1: an earlier draft of this KDoc described an edge-triggered stream
     * with publish-time re-evaluation and Available-on-recovery; none of that exists.)
     *
     * The alternative — publishing a [com.revel.obdgauge.model.Reading] with a placeholder value
     * — is the one thing this module never does: a boost gauge reading 0 PSI because its MAP
     * source does not exist looks exactly like a boost gauge reading 0 PSI because the engine is
     * not pulling. See [ChannelAvailability].
     */
    data class ChannelAvailabilityChanged(
        val id: String,
        val availability: ChannelAvailability,
    ) : PollEvent
}
