package com.revel.obdgauge.protocol

/**
 * Why a raw ELM327 response did not yield a value.
 *
 * Every one of these is an **expected** condition on a real vehicle — an ECU that does not
 * implement a PID, a bus that dropped out, a dongle that got interrupted mid-command — so they
 * are values, not exceptions. [ResponseParser] never throws; a failure here means the caller
 * skips the reading (leaving the previous one to go stale) rather than showing a number that
 * might be wrong.
 */
sealed interface ParseFailure {
    /** `NO DATA` — the ECU did not answer this PID (commonly: it does not implement it). */
    data object NoData : ParseFailure

    /** `STOPPED` — the command was interrupted, usually by another command or a bus event. */
    data object Stopped : ParseFailure

    /** `UNABLE TO CONNECT` — the dongle could not establish a protocol with the vehicle. */
    data object UnableToConnect : ParseFailure

    /** `?` — the dongle did not understand the command it was sent. */
    data object UnknownCommand : ParseFailure

    /** The response was empty (or nothing but prompt/whitespace). */
    data object Empty : ParseFailure

    /**
     * A bus-level error line: `CAN ERROR`, `BUS ERROR`, `BUS INIT: ERROR`, `DATA ERROR`,
     * `BUFFER FULL`, or a bare `ERROR`.
     *
     * @param raw the response as received, for the debug console and logs.
     */
    data class BusError(
        val raw: String,
    ) : ParseFailure

    /**
     * A `7F` negative response: the ECU explicitly rejected the request.
     *
     * @param requestMode the mode that was rejected (e.g. `0x01`).
     * @param code the negative response code (NRC) byte, or `null` if the frame was truncated
     *   before it.
     */
    data class NegativeResponse(
        val requestMode: Int,
        val code: Int?,
    ) : ParseFailure

    /**
     * The response carried no frame for the PID that was asked for.
     *
     * @param expectedHeader the `41 XX` header that was searched for, e.g. `"4105"`.
     * @param raw the response as received.
     */
    data class NoMatchingFrame(
        val expectedHeader: String,
        val raw: String,
    ) : ParseFailure

    /**
     * The response looked like hex but could not be framed into whole bytes (odd digit count
     * after reassembly), so no byte boundary can be trusted.
     *
     * @param raw the response as received.
     */
    data class MalformedHex(
        val raw: String,
    ) : ParseFailure

    /**
     * The PID's frame was found but carried fewer data bytes than the PID defines. Frames with
     * *extra* bytes are accepted (the surplus is ignored); short frames are not, because
     * scaling a missing byte as zero is exactly the plausible-but-wrong value this layer exists
     * to prevent.
     *
     * @param expected the data-byte count from [StandardPidSpec.dataByteCount].
     * @param actual how many data bytes were actually present after the header.
     */
    data class UnexpectedDataLength(
        val expected: Int,
        val actual: Int,
    ) : ParseFailure

    /**
     * A [com.revel.obdgauge.model.PidDefinition.parse] lambda threw, or returned a non-finite
     * value. Unreachable through this parser's own registry (data length is validated first);
     * it exists so a future or third-party definition cannot break the never-throws guarantee
     * or leak a `NaN` onto a gauge.
     *
     * @param message diagnostic detail for logs.
     */
    data class ScalingError(
        val message: String,
    ) : ParseFailure
}

/**
 * The outcome of a parse: a [Success] carrying [T], or a [Failure] carrying a typed
 * [ParseFailure]. Deliberately not `kotlin.Result` — failures here are ordinary expected
 * outcomes, not `Throwable`s, and modelling them as such is what keeps the parser from
 * throwing.
 */
sealed interface ParseOutcome<out T> {
    /** The parse succeeded. */
    data class Success<out T>(
        val value: T,
    ) : ParseOutcome<T>

    /** The parse failed for the typed [reason]; no value is available. */
    data class Failure(
        val reason: ParseFailure,
    ) : ParseOutcome<Nothing>
}

/** The parsed value, or `null` if this outcome is a [ParseOutcome.Failure]. */
fun <T> ParseOutcome<T>.valueOrNull(): T? =
    when (this) {
        is ParseOutcome.Success -> value
        is ParseOutcome.Failure -> null
    }

/** The typed failure, or `null` if this outcome is a [ParseOutcome.Success]. */
fun <T> ParseOutcome<T>.failureOrNull(): ParseFailure? =
    when (this) {
        is ParseOutcome.Success -> null
        is ParseOutcome.Failure -> reason
    }
