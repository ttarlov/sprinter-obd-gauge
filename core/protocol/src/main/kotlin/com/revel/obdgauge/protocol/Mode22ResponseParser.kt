package com.revel.obdgauge.protocol

/**
 * Turns a manufacturer-PID reply into a scaled value, with the same never-throws, never-guess
 * discipline as [ResponseParser].
 *
 * ## How these responses are framed
 *
 * A KWP/UDS read over CAN goes out as an ISO-TP single frame — `02 21 30 00 00 00 00 00` for the
 * trans-temp code — and comes back as `03 61 30 XX 00 00 00 00`. The ELM327 handles ISO-TP
 * itself: it strips the CAN id (headers are off) and the PCI byte, and prints only the service
 * data. So `sendRaw("2130")` returns text like:
 * ```
 *   6130A5
 * ```
 * which is structurally identical to a mode-01 answer (`41055A`) — a positive-response header
 * followed by data bytes — differing only in that the header is `61 30` instead of `41 05` and
 * that the identifier may be longer than one byte on a true UDS `22` read. That is why this
 * parser is a thin front end over [ResponseParser.dataBytesForHeader] rather than a second
 * implementation: every tolerance (`SEARCHING…`, echo remnants, spaces on/off, `\r` vs `\n`,
 * indexed multi-line replies) and every refusal (unaligned header match, short frame, `7F`
 * negative response, odd-nibble hex) is inherited unchanged. A second parser would be a second
 * place for a framing bug to live.
 *
 * ## Headers stay off — deliberately, and the caveat wave 1 left open
 *
 * `MODULE.md` flags that responses are only safe to parse with CAN-ID headers **off** (`ATH0`).
 * This path keeps it that way: [Mode22Requester] issues `ATSH`/`ATCRA` — which change *which*
 * ECU is addressed and *which* replies are let through — and never `ATH1`, which is the unrelated
 * command that would prepend the id to every printed line. `ATSH` is not `ATH`; conflating them
 * is precisely the mistake `DECISIONS.md` D1 records kotlin-obd-api making.
 *
 * If headers were somehow on anyway (a dongle whose defaults differ, a debug-console session that
 * left `ATH1` set), the inherited byte-aligned header search fails safe rather than inventing a
 * value: an 11-bit id prints as three hex digits, making the line odd-length so no even offset can
 * match `6130` and the parse ends as [ParseFailure.MalformedHex]; a 29-bit id is four whole bytes,
 * so alignment is preserved and the real `6130` is still found at its true offset. Neither case
 * yields a shifted byte. The value is accepted only when a byte-aligned `61 30` is present, which
 * is also what makes the [ATCRA][Mode22Requester] filter non-load-bearing for correctness.
 */
object Mode22ResponseParser {
    /**
     * Parses [raw] for [spec]'s manufacturer PID and applies its scaling.
     *
     * @return [ParseOutcome.Success] with the value in [spec]'s
     *   [definition.unit][com.revel.obdgauge.model.PidDefinition.unit] — °C for the temperature
     *   codes, never the ScanGauge's °F — or a typed [ParseOutcome.Failure].
     */
    fun parse(
        spec: Mode22PidSpec,
        raw: String,
    ): ParseOutcome<Double> {
        val bytes =
            ResponseParser.dataBytesForHeader(
                raw = raw,
                responseHeader = spec.responseHeader,
                requestMode = spec.requestMode,
                expectedCount = spec.requiredDataBytes,
            )
        return when (bytes) {
            is ParseOutcome.Failure -> bytes
            is ParseOutcome.Success -> scaleReading(spec.definition, bytes.value)
        }
    }
}
