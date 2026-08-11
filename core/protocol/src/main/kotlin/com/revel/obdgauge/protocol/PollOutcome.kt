package com.revel.obdgauge.protocol

/**
 * How one attempt to read one channel ended.
 *
 * Shared by both request paths — a bare standard PID and a header-framed manufacturer PID — even
 * though only the latter can produce [HeaderRejected]. One result type means
 * [RealVehicleDataSource] folds every poll through a single exhaustive `when`, rather than two
 * near-identical ones that could drift apart and leave a failure mode handled on one path only.
 *
 * The distinction that matters is between [Skipped] and everything else: [Skipped] is the loop's
 * normal, correct refusal to publish a doubtful number, and it must never be confused with a
 * value.
 */
sealed interface PollOutcome {
    /** The vehicle answered and the reply scaled cleanly. [value] is in the channel's unit. */
    data class Value(
        val value: Double,
    ) : PollOutcome

    /**
     * The exchange completed but produced no usable number — the ECU said `NO DATA`, rejected the
     * request, or answered something the framing rules refuse. The reading is skipped: the
     * previous value stands and ages toward stale.
     */
    data class Skipped(
        val reason: ParseFailure,
    ) : PollOutcome

    /**
     * A framing command (`ATSH`/`ATCRA`) was not acknowledged, so the request was never sent.
     * Its own outcome rather than a [Skipped], because it means the *dongle* disagreed, not the
     * vehicle — a clone that does not implement header control shows up here on every cycle and
     * needs to be visible as such during bring-up.
     */
    data class HeaderRejected(
        val command: String,
        val raw: String,
    ) : PollOutcome

    /** The link dropped or timed out mid-exchange. The poll loop parks. */
    data class LinkDown(
        val message: String,
    ) : PollOutcome
}
