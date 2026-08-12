package com.revel.obdgauge.protocol

import com.revel.obdgauge.model.ObdLink
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How a [Mode22Requester] frames and unframes a manufacturer request.
 *
 * @param defaultHeader the `ATSH` value restored after each request. `7DF` is the ELM327's own
 *   default for ISO 15765-4 11-bit CAN — the functional broadcast address every standard mode-01
 *   request relies on.
 * @param rxFilterEnabled whether to issue `ATCRA` at all. Set `false` if hardware bring-up finds
 *   the derived filter suppressing real answers; the payload-level header match in
 *   [Mode22ResponseParser] still guards the value, so this is a noise/latency trade, not a
 *   correctness one. See [MercedesPidRegistry]'s RXF discussion.
 * @param atTimeout timeout for the `ATSH`/`ATCRA` framing commands, which the dongle answers
 *   locally and instantly.
 * @param requestTimeout timeout for the request itself, which the vehicle has to answer.
 */
data class Mode22Config(
    val defaultHeader: String = "7DF",
    val rxFilterEnabled: Boolean = true,
    val atTimeout: Duration = 2.seconds,
    val requestTimeout: Duration = 2.seconds,
)

/**
 * Runs one manufacturer-PID exchange as an atomic, self-restoring header-scoped sequence.
 *
 * The header discipline itself — the command order, the restore-after-every-outcome rule, the
 * remembered failed restore, the cancellation stance — lives in [HeaderScope], which
 * [KwpRecordRequester] shares. This class is the thin part: it knows that a manufacturer PID's
 * answer is parsed by [Mode22ResponseParser] into one scaled value.
 *
 * @param link the connected, initialized link. This class never connects or initializes.
 * @param config framing/restore parameters; see [Mode22Config].
 */
class Mode22Requester(
    link: ObdLink,
    config: Mode22Config = Mode22Config(),
) {
    private val scope = HeaderScope(link, config)

    /**
     * True when the dongle may still be holding a non-default `ATSH`/`ATCRA` — either a request
     * is in flight, or a restore failed. Cleared only by a restore the dongle acknowledged.
     */
    val restorePending: Boolean get() = scope.restorePending

    /** Runs one full framed exchange for [spec]. See [HeaderScope] for the command sequence. */
    suspend fun request(spec: Mode22PidSpec): PollOutcome =
        when (val result = scope.request(spec.canId, spec.rxFilter, spec.requestBytes)) {
            is FramedResult.LinkDown -> PollOutcome.LinkDown(result.message)
            is FramedResult.HeaderRejected -> PollOutcome.HeaderRejected(result.command, result.raw)
            is FramedResult.Response ->
                when (val parsed = Mode22ResponseParser.parse(spec, result.raw)) {
                    is ParseOutcome.Success -> PollOutcome.Value(parsed.value)
                    is ParseOutcome.Failure -> PollOutcome.Skipped(parsed.reason)
                }
        }

    /**
     * Restores the default header and clears the receive filter, if anything might be set.
     *
     * Idempotent and cheap to call speculatively. See [HeaderScope.restoreHeaders].
     *
     * @return `true` when the dongle is known to be back at its defaults.
     */
    suspend fun restoreHeaders(): Boolean = scope.restoreHeaders()
}
