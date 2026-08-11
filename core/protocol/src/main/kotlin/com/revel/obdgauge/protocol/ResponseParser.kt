package com.revel.obdgauge.protocol

import com.revel.obdgauge.model.PidDefinition

/**
 * Turns the raw text an ELM327 emits into typed data bytes and scaled values.
 *
 * **This parser never throws.** Every malformed, truncated, interrupted, or outright
 * nonsensical response becomes a [ParseFailure]; a property test hammers it with pseudo-random
 * garbage and mutated valid frames to keep that guarantee honest. The failure mode this guards
 * against is not a crash — it is a *plausible* wrong number on a gauge halfway up a grade — so
 * the parser refuses to guess: a frame that does not match the PID asked for, or that is short
 * a data byte, produces a skipped reading rather than a value.
 *
 * What it tolerates, all of which real dongles produce:
 * - line breaks as `\r`, `\n`, or both, with arbitrary interleaved whitespace;
 * - `>` prompt characters left anywhere in the text;
 * - `SEARCHING...`, `BUS INIT`, and `.` progress noise before the data;
 * - command echo remnants (a dongle that ignored `ATE0`, or answered before it took effect);
 * - headers written `41 05 5A` (spaces on) or `41055A` (after `ATS0`);
 * - ELM's multi-line long-response format, where an ISO-TP length line is followed by
 *   `0:`/`1:`-indexed continuation lines, reassembled in arrival order (the index digit is
 *   not used to reorder);
 * - extra trailing data bytes beyond the PID's defined length.
 *
 * Known limitation: CAN-ID headers are assumed **off** (`ATH0`, the ELM327 default that
 * [Elm327InitStateMachine] never changes). With headers on, a `7E8` frame id would be
 * concatenated into the payload and break byte alignment. See [Mode22ResponseParser] for how
 * the manufacturer-PID path (OBD-15) handles that case — it does not enable headers either.
 */
object ResponseParser {
    /**
     * Parses [raw] for [spec]'s PID and applies its SAE scaling.
     *
     * @return [ParseOutcome.Success] with the value in [spec]'s
     *   [com.revel.obdgauge.model.PidDefinition.unit], or a typed [ParseOutcome.Failure].
     */
    fun parse(
        spec: StandardPidSpec,
        raw: String,
    ): ParseOutcome<Double> =
        when (val bytes = dataBytes(raw, spec.responseMode, spec.pid, spec.dataByteCount)) {
            is ParseOutcome.Failure -> bytes
            is ParseOutcome.Success -> scaleReading(spec.definition, bytes.value)
        }

    /**
     * Extracts the data bytes following a `<responseMode> <pid>` header in [raw].
     *
     * Used by [parse] and, for the `0100` supported-PID bitmap, by [Elm327InitStateMachine].
     *
     * @param raw the response text as [com.revel.obdgauge.model.ObdLink.sendRaw] returned it.
     * @param responseMode the echoed mode byte, i.e. request mode `+ 0x40` (`0x41` for mode 01).
     * @param pid the parameter id within that mode.
     * @param expectedCount how many data bytes the caller requires; extras are ignored, a
     *   shortfall is a [ParseFailure.UnexpectedDataLength].
     */
    fun dataBytes(
        raw: String,
        responseMode: Int,
        pid: Int,
        expectedCount: Int,
    ): ParseOutcome<List<Int>> =
        dataBytesForHeader(
            raw = raw,
            responseHeader = hexByte(responseMode) + hexByte(pid),
            requestMode = responseMode - POSITIVE_RESPONSE_OFFSET,
            expectedCount = expectedCount,
        )

    /**
     * The general form of [dataBytes]: extracts the data bytes following an arbitrary-length
     * [responseHeader] in [raw].
     *
     * Mode 01 answers with a 2-byte header (`41 05`), but manufacturer modes do not: KWP's
     * `21 30` answers `61 30` (2 bytes) and UDS' `22 0F 0C` answers `62 0F 0C` (3 bytes). Taking
     * the header as a hex *string* rather than a mode/pid pair is what lets [Mode22ResponseParser]
     * reuse this — every tolerance and refusal rule below then applies identically to both paths,
     * which is the point: there is exactly one implementation of "find this PID's frame".
     *
     * @param raw the response text as [com.revel.obdgauge.model.ObdLink.sendRaw] returned it.
     * @param responseHeader the positive-response header as uppercase hex, e.g. `"4105"`, `"6130"`.
     * @param requestMode the mode that was requested, used only to recognize its `7F` negative
     *   response (`0x01` for mode 01, `0x21` for the KWP reads the Mercedes codes use).
     * @param expectedCount how many data bytes the caller requires; extras are ignored, a
     *   shortfall is a [ParseFailure.UnexpectedDataLength].
     */
    fun dataBytesForHeader(
        raw: String,
        responseHeader: String,
        requestMode: Int,
        expectedCount: Int,
    ): ParseOutcome<List<Int>> {
        val lines = splitLines(raw)
        val protocolError = protocolError(lines, raw)
        return when {
            protocolError != null -> ParseOutcome.Failure(protocolError)
            lines.isEmpty() -> ParseOutcome.Failure(ParseFailure.Empty)
            else -> extractFrame(lines, raw, responseHeader.uppercase(), requestMode, expectedCount)
        }
    }

    /**
     * Detects ELM327/bus status responses that mean "no value this time".
     *
     * Safe to run as a substring scan over the whole response: none of these tokens can occur
     * inside hex data, because each contains at least one character outside `0-9A-F`.
     */
    private fun protocolError(
        lines: List<String>,
        raw: String,
    ): ParseFailure? {
        val compact = lines.joinToString(separator = "") { line -> line.filterNot(Char::isWhitespace) }
        return when {
            lines.any { it == UNKNOWN_COMMAND } -> ParseFailure.UnknownCommand
            compact.contains(NO_DATA_TOKEN) -> ParseFailure.NoData
            compact.contains(UNABLE_TO_CONNECT_TOKEN) -> ParseFailure.UnableToConnect
            compact.contains(STOPPED_TOKEN) -> ParseFailure.Stopped
            compact.contains(BUFFER_FULL_TOKEN) -> ParseFailure.BusError(raw.trim())
            compact.contains(ERROR_TOKEN) -> ParseFailure.BusError(raw.trim())
            else -> null
        }
    }

    private fun extractFrame(
        lines: List<String>,
        raw: String,
        header: String,
        requestMode: Int,
        expectedCount: Int,
    ): ParseOutcome<List<Int>> {
        // Frame per line FIRST: joining lines lets a truncated frame steal the next frame's
        // mode byte as data — "41 05\r41 05 5A" would read 0x41 as coolant and report a
        // plausible wrong temperature (review round-1 MAJOR). A complete frame on any single
        // line wins; the joined payload is only a fallback for genuinely cross-line frames
        // ("41 0C\r1F 40"), where no single line carries a full frame to mis-read.
        for (line in normalizedHexLines(lines)) {
            val lineStart = indexOfAligned(line, header)
            if (lineStart >= 0) {
                val outcome = readData(line, lineStart + header.length, expectedCount)
                if (outcome is ParseOutcome.Success) {
                    return outcome
                }
            }
        }
        val hex = hexPayload(lines)
        val start = indexOfAligned(hex, header)
        return if (start >= 0) {
            readData(hex, start + header.length, expectedCount)
        } else {
            missingFrame(hex, raw, requestMode, header)
        }
    }

    private fun readData(
        hex: String,
        dataStart: Int,
        expectedCount: Int,
    ): ParseOutcome<List<Int>> {
        val available = (hex.length - dataStart) / HEX_DIGITS_PER_BYTE
        return if (available < expectedCount) {
            ParseOutcome.Failure(ParseFailure.UnexpectedDataLength(expectedCount, available))
        } else {
            ParseOutcome.Success(
                List(expectedCount) { byteIndex ->
                    val at = dataStart + byteIndex * HEX_DIGITS_PER_BYTE
                    hex.substring(at, at + HEX_DIGITS_PER_BYTE).toInt(HEX_RADIX)
                },
            )
        }
    }

    /** Classifies why no positive frame was found: explicit rejection, junk, or simply absent. */
    private fun missingFrame(
        hex: String,
        raw: String,
        requestMode: Int,
        header: String,
    ): ParseOutcome.Failure {
        val negativeHeader = NEGATIVE_RESPONSE_PREFIX + hexByte(requestMode)
        val negativeStart = indexOfAligned(hex, negativeHeader)
        return when {
            negativeStart >= 0 ->
                ParseOutcome.Failure(
                    ParseFailure.NegativeResponse(
                        requestMode = requestMode,
                        code = nrcAt(hex, negativeStart + negativeHeader.length),
                    ),
                )
            hex.isNotEmpty() && hex.length % HEX_DIGITS_PER_BYTE != 0 ->
                ParseOutcome.Failure(ParseFailure.MalformedHex(raw.trim()))
            else -> ParseOutcome.Failure(ParseFailure.NoMatchingFrame(header, raw.trim()))
        }
    }
}

/**
 * Applies [definition]'s scaling lambda to [data], converting any throw or non-finite result into
 * a typed [ParseFailure.ScalingError].
 *
 * Shared by [ResponseParser] and [Mode22ResponseParser] deliberately: this is the single boundary
 * that owns the never-throws and never-poisoned-value guarantees, and a manufacturer PID whose
 * scaling is a *hypothesis* (`verified = false`) is exactly the one that must not be allowed to
 * leak a `NaN` onto a gauge.
 *
 * `RuntimeException` is caught broadly on purpose: [PidDefinition.parse] is an arbitrary
 * caller-supplied lambda. Nothing here suspends, so no `CancellationException` can be swallowed.
 */
@Suppress("TooGenericExceptionCaught")
internal fun scaleReading(
    definition: PidDefinition,
    data: List<Int>,
): ParseOutcome<Double> =
    try {
        val value = definition.parse(ByteArray(data.size) { data[it].toByte() })
        if (value.isFinite()) {
            ParseOutcome.Success(value)
        } else {
            ParseOutcome.Failure(ParseFailure.ScalingError("non-finite value $value for ${definition.id}"))
        }
    } catch (e: RuntimeException) {
        ParseOutcome.Failure(
            ParseFailure.ScalingError("${definition.id}: ${e.message ?: e::class.simpleName.orEmpty()}"),
        )
    }

/**
 * Splits [raw] into trimmed, uppercased, non-empty lines with prompt characters removed.
 * Kotlin's `uppercase()` is locale-independent, so this is safe on any device locale.
 */
private fun splitLines(raw: String): List<String> =
    raw
        .split('\r', '\n')
        .map { it.replace(PROMPT, "").trim().uppercase() }
        .filter { it.isNotEmpty() }

/**
 * Reassembles the hex payload from [lines], dropping progress noise and echo lines that are not
 * hex at all.
 *
 * When ELM's indexed long-response format is present (`0:`, `1:`, ...), only those lines carry
 * payload — the bare length line above them is framing metadata and is dropped, which also
 * protects byte alignment (a 3-digit length would shift every subsequent byte).
 */
private fun hexPayload(lines: List<String>): String = normalizedHexLines(lines).joinToString(separator = "")

/** The shared normalization pipeline: noise-stripped, whitespace-free, hex-only lines. */
private fun normalizedHexLines(lines: List<String>): List<String> {
    val indexed = lines.mapNotNull { INDEXED_LINE.matchEntire(it)?.groupValues?.get(2) }
    val candidates = indexed.ifEmpty { lines }
    val hexLines =
        candidates
            .map { line -> line.filterNot(Char::isWhitespace) }
            .filter { it.isNotEmpty() && HEX_ONLY.matches(it) }
    return if (indexed.isEmpty()) dropIsoTpLengthLine(hexLines) else hexLines
}

/**
 * Drops a leading ISO-TP length line (three hex digits on its own, e.g. `014`) when more payload
 * lines follow it. No mode-01 data frame is ever three hex digits long, so this cannot eat real
 * data.
 */
private fun dropIsoTpLengthLine(hexLines: List<String>): List<String> =
    if (hexLines.size > 1 && hexLines.first().length == ISO_TP_LENGTH_DIGITS) hexLines.drop(1) else hexLines

/**
 * Finds [needle] in [hex] at a **byte-aligned** (even) offset, scanning left to right.
 *
 * Alignment matters: an unaligned match would mean reading the low nibble of one byte and the
 * high nibble of the next as if it were a header, which is how a parser invents values that look
 * real. Left-to-right means an echoed request (`0105`) preceding the answer (`41055A`) resolves
 * to the answer, not to a stray overlap.
 */
private fun indexOfAligned(
    hex: String,
    needle: String,
): Int {
    var index = 0
    while (index + needle.length <= hex.length) {
        if (hex.startsWith(needle, index)) {
            return index
        }
        index += HEX_DIGITS_PER_BYTE
    }
    return -1
}

/** Reads the negative-response code byte at [at], or `null` if the frame ended first. */
private fun nrcAt(
    hex: String,
    at: Int,
): Int? =
    if (at + HEX_DIGITS_PER_BYTE <= hex.length) {
        hex.substring(at, at + HEX_DIGITS_PER_BYTE).toInt(HEX_RADIX)
    } else {
        null
    }

private val INDEXED_LINE = Regex("^([0-9A-F]):\\s*(.*)$")
private val HEX_ONLY = Regex("^[0-9A-F]+$")

private const val PROMPT = ">"
private const val UNKNOWN_COMMAND = "?"
private const val NO_DATA_TOKEN = "NODATA"
private const val UNABLE_TO_CONNECT_TOKEN = "UNABLETOCONNECT"
private const val STOPPED_TOKEN = "STOPPED"
private const val BUFFER_FULL_TOKEN = "BUFFERFULL"
private const val ERROR_TOKEN = "ERROR"
private const val NEGATIVE_RESPONSE_PREFIX = "7F"
private const val ISO_TP_LENGTH_DIGITS = 3
