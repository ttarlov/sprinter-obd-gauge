package com.revel.obdgauge.protocol

import com.revel.obdgauge.model.ObdLink

/** What one KWP record request produced. The record counterpart of [PollOutcome]. */
sealed interface RecordOutcome {
    /** The ECU returned a complete, length-checked record. */
    data class Received(
        val record: KwpRecord,
    ) : RecordOutcome

    /** No usable record this time, for a typed reason — including a `7F` negative response. */
    data class Skipped(
        val reason: ParseFailure,
    ) : RecordOutcome

    /** The dongle refused an `ATSH`/`ATCRA` framing command, so nothing was requested. */
    data class HeaderRejected(
        val command: String,
        val raw: String,
    ) : RecordOutcome

    /** The link dropped or timed out. */
    data class LinkDown(
        val message: String,
    ) : RecordOutcome
}

/**
 * Requests a KWP `21 xx` record from a physically addressed ECU, under the same self-restoring
 * header discipline as [Mode22Requester] — literally the same, since both delegate to
 * [HeaderScope].
 *
 * For the 722.9 transmission controller that sequence is:
 * ```
 *   ATSH7E1     address the transmission controller
 *   ATCRA7E9    let only its replies through
 *   2130        readDataByLocalIdentifier, local id 0x30
 *   ATCRA       clear the receive filter          ┐ restore, so the next standard
 *   ATSH7DF     restore the broadcast header      ┘ 0105 still reaches the engine ECU
 * ```
 *
 * Two ways to consume the answer, and the distinction is deliberate:
 *
 * - [request] hands back the whole [KwpRecord]. The ECU sends 24 bytes of state for one round
 *   trip; a caller that wants two fields out of it (a displayed temperature and a cross-check)
 *   should not pay for two exchanges, and a capture session wants the raw block regardless.
 * - [poll] applies one spec's [com.revel.obdgauge.model.PidDefinition] to that record and answers
 *   in [PollOutcome], so a record-backed channel is interchangeable with a mode-01 or mode-22 one
 *   at the scheduler's boundary.
 *
 * @param link the connected, initialized link. This class never connects or initializes.
 * @param config framing/restore parameters, shared with the mode-22 path; see [Mode22Config].
 */
class KwpRecordRequester(
    link: ObdLink,
    config: Mode22Config = Mode22Config(),
) {
    private val scope = HeaderScope(link, config)

    /** See [HeaderScope.restorePending]. */
    val restorePending: Boolean get() = scope.restorePending

    /** Runs one full framed exchange for [spec] and returns the whole record. */
    suspend fun request(spec: KwpRecordSpec): RecordOutcome =
        when (val result = scope.request(spec.canId, spec.rxFilter, spec.requestBytes)) {
            is FramedResult.LinkDown -> RecordOutcome.LinkDown(result.message)
            is FramedResult.HeaderRejected -> RecordOutcome.HeaderRejected(result.command, result.raw)
            is FramedResult.Response ->
                when (val parsed = KwpRecordParser.parse(spec, result.raw)) {
                    is ParseOutcome.Success -> RecordOutcome.Received(parsed.value)
                    is ParseOutcome.Failure -> RecordOutcome.Skipped(parsed.reason)
                }
        }

    /**
     * Runs one exchange and scales [spec]'s field out of the record.
     *
     * Scaling goes through [scaleReading], the same boundary the other two request paths use, so
     * a record field that is still a *hypothesis* — which trans temp is until the cold-start
     * capture — cannot leak a `NaN` or a thrown exception onto a gauge any more than a verified
     * one could.
     */
    suspend fun poll(spec: KwpRecordSpec): PollOutcome =
        when (val outcome = request(spec)) {
            is RecordOutcome.LinkDown -> PollOutcome.LinkDown(outcome.message)
            is RecordOutcome.HeaderRejected -> PollOutcome.HeaderRejected(outcome.command, outcome.raw)
            is RecordOutcome.Skipped -> PollOutcome.Skipped(outcome.reason)
            is RecordOutcome.Received ->
                when (val scaled = scaleReading(spec.definition, outcome.record.bytes)) {
                    is ParseOutcome.Success -> PollOutcome.Value(scaled.value)
                    is ParseOutcome.Failure -> PollOutcome.Skipped(scaled.reason)
                }
        }

    /** See [HeaderScope.restoreHeaders]. */
    suspend fun restoreHeaders(): Boolean = scope.restoreHeaders()
}
