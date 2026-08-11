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
 * Runs one manufacturer-PID exchange as an **atomic, self-restoring header-scoped sequence**:
 *
 * ```
 *   ATSH7E1     set the transmit header to the transmission controller
 *   ATCRA7E9    only let that controller's replies through
 *   2130        the actual request
 *   ATCRA       clear the receive filter          ┐ restore, so the next standard
 *   ATSH7DF     restore the broadcast header      ┘ 0105 still reaches the engine ECU
 * ```
 *
 * **Why restoration is not optional.** `ATSH` and `ATCRA` are sticky dongle state, not per-command
 * arguments. Leave `ATSH 7E1` set and every subsequent `0105`/`010B`/`0133` is addressed to the
 * transmission controller, which does not implement them — the standard gauges would go silent
 * (fail-safe: [ResponseParser] returns `NO DATA`, never a wrong number, so nothing false is ever
 * displayed) and stay silent until the app restarts. Leave `ATCRA 7E9` set and the engine ECU's
 * replies are filtered out even with the right header. So restoration runs after every outcome,
 * successful or not.
 *
 * **Atomicity.** The five commands must not interleave with another PID's poll. That holds by
 * construction: [RealVehicleDataSource] polls from a single sequential coroutine, and `ObdLink`
 * is single-flight by contract on top of that. This class adds no locking of its own; it would
 * only give a false sense of atomicity, since two interleaved *sequences* would corrupt each
 * other's header state regardless of who holds a mutex around `sendRaw`.
 *
 * **When restoration itself fails** — the link dropped, or the dongle stopped answering — the
 * dongle is left in an unknown header state. That is remembered in [restorePending], and the next
 * [request] (or an explicit [restoreHeaders], which the scheduler calls before its standard-PID
 * polls) retries it. Without that, one dropped BLE packet at the wrong moment would silently kill
 * every standard gauge for the rest of the session.
 *
 * **Cancellation** propagates untouched and deliberately does *not* run the restore: a cancelled
 * poll loop is a stopping app, `NonCancellable` cleanup would delay teardown behind two more BLE
 * round trips, and [restorePending] already records that the dongle needs fixing up if polling
 * resumes.
 *
 * @param link the connected, initialized link. This class never connects or initializes.
 * @param config framing/restore parameters; see [Mode22Config].
 */
class Mode22Requester(
    private val link: ObdLink,
    private val config: Mode22Config = Mode22Config(),
) {
    /**
     * True when the dongle may still be holding a non-default `ATSH`/`ATCRA` — either a request is
     * in flight, or a restore failed. Cleared only by a restore that the dongle acknowledged.
     */
    var restorePending: Boolean = false
        private set

    /** Runs one full framed exchange for [spec]. See the class KDoc for the command sequence. */
    suspend fun request(spec: Mode22PidSpec): PollOutcome {
        val outcome = frameAndSend(spec)
        if (outcome is PollOutcome.LinkDown) {
            // The link is gone; two more doomed commands would only add timeouts. restorePending
            // stays true, so the next request or scheduler cycle fixes the header state up.
            return outcome
        }
        restoreHeaders()
        return outcome
    }

    /**
     * Restores the default header and clears the receive filter, if anything might be set.
     *
     * Idempotent and cheap to call speculatively: a no-op when [restorePending] is already false.
     * The scheduler calls it before a run of standard PIDs so a previously failed restore cannot
     * poison them.
     *
     * @return `true` when the dongle is known to be back at its defaults.
     */
    suspend fun restoreHeaders(): Boolean {
        if (!restorePending) {
            return true
        }
        val filterCleared = !config.rxFilterEnabled || sendFraming(CLEAR_RX_FILTER_COMMAND) is FramingResult.Ok
        val headerRestored = sendFraming(SET_HEADER_COMMAND + config.defaultHeader) is FramingResult.Ok
        restorePending = !(filterCleared && headerRestored)
        return !restorePending
    }

    private suspend fun frameAndSend(spec: Mode22PidSpec): PollOutcome {
        // Set before sending for coroutine-cancellation safety: failures return VALUES (a
        // post-assignment would still run), but a cancellation mid-send would skip it, so
        // "pending" has to cover the in-flight window too.
        restorePending = true
        return applyFraming(spec)?.toOutcome() ?: sendRequest(spec)
    }

    /**
     * Issues the framing commands in order, stopping at the first one the dongle did not accept.
     *
     * @return the failing [FramingResult], or `null` when every command took — the request may
     *   only be sent in that second case, because an unset header would address the wrong ECU.
     */
    private suspend fun applyFraming(spec: Mode22PidSpec): FramingResult? {
        val filter = spec.rxFilter
        val commands =
            buildList {
                add(SET_HEADER_COMMAND + spec.canId)
                if (config.rxFilterEnabled && !filter.isNullOrEmpty()) {
                    add(SET_RX_FILTER_COMMAND + filter)
                }
            }
        return commands.firstNotNullOfOrNull { command ->
            sendFraming(command).takeIf { it !is FramingResult.Ok }
        }
    }

    private suspend fun sendRequest(spec: Mode22PidSpec): PollOutcome =
        when (val raw = send(spec.requestBytes, config.requestTimeout)) {
            is RawResult.Failed -> PollOutcome.LinkDown(raw.message)
            is RawResult.Text ->
                when (val parsed = Mode22ResponseParser.parse(spec, raw.value)) {
                    is ParseOutcome.Success -> PollOutcome.Value(parsed.value)
                    is ParseOutcome.Failure -> PollOutcome.Skipped(parsed.reason)
                }
        }

    private suspend fun sendFraming(command: String): FramingResult =
        when (val raw = send(command, config.atTimeout)) {
            is RawResult.Failed -> FramingResult.Failed(raw.message)
            is RawResult.Text ->
                if (acknowledges(raw.value)) {
                    FramingResult.Ok
                } else {
                    FramingResult.Rejected(command, raw.value.trim())
                }
        }

    private suspend fun send(
        command: String,
        timeout: Duration,
    ): RawResult = link.sendCatching(command, timeout)

    private companion object {
        /**
         * `ATSH`/`ATCRA` are written **without a separating space** (`ATSH7E1`, not `ATSH 7E1`).
         * The ELM327 strips whitespace from commands, so both forms are equivalent on a genuine
         * chip — but the no-space form is what the datasheet's own examples and the project's
         * shipped transcript fixture use, and it removes one thing for a clone's simplified
         * command parser to get wrong.
         *
         * Note `ATSH` is **not** `ATH`: `ATSH` sets the transmit header, `ATH` toggles whether
         * received CAN ids are printed. Headers stay off; see [Mode22ResponseParser].
         */
        const val SET_HEADER_COMMAND = "ATSH"
        const val SET_RX_FILTER_COMMAND = "ATCRA"

        /** `ATCRA` with no argument is the ELM327's documented "reset the receive filter". */
        const val CLEAR_RX_FILTER_COMMAND = "ATCRA"
    }
}

/** Outcome of one `ATSH`/`ATCRA` framing command. */
private sealed interface FramingResult {
    data object Ok : FramingResult

    data class Rejected(
        val command: String,
        val raw: String,
    ) : FramingResult

    data class Failed(
        val message: String,
    ) : FramingResult
}

private fun FramingResult.toOutcome(): PollOutcome =
    when (this) {
        FramingResult.Ok -> error("Ok is not a failure outcome")
        is FramingResult.Rejected -> PollOutcome.HeaderRejected(command, raw)
        is FramingResult.Failed -> PollOutcome.LinkDown(message)
    }
