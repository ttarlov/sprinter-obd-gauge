package com.revel.obdgauge.protocol

/**
 * Reassembles a KWP `21 xx` multi-frame record out of what an ELM327 printed, in either of the
 * two framings this project has seen from real hardware (OBD-49).
 *
 * ## Why this is not [ResponseParser.dataBytesForHeader] with a bigger `expectedCount`
 *
 * With CAN-ID headers **off** — the app's normal state — it very nearly is, and that path is
 * handled here by the same normalization: ELM does the ISO-TP reassembly itself, drops the CAN
 * id and the PCI bytes, and prints an indexed long response whose payload starts `61 30`.
 *
 * With headers **on** it is a different problem, and headers-on is how the 2026-08-12 session
 * captured this record from the console:
 * ```
 *   7E9 10 1A 61 30 00 13 00 00      first frame:      PCI 1_, length 0x01A = 26, 6 data bytes
 *   7E9 21 00 00 00 08 04 00 DD      consecutive #1:   PCI 2_, 7 data bytes
 *   7E9 22 8E FF F3 FF F3 00 00      consecutive #2
 *   7E9 23 86 18 00 08 00 00 FF      consecutive #3:   6 payload bytes + 1 byte of padding
 * ```
 * Fed to the byte-aligned header search, that text finds nothing: an 11-bit CAN id prints as
 * **three** hex digits, so every line is odd-length and the real `6130` sits at an odd offset.
 * The existing parser therefore fails *safe* on it (a typed `NoMatchingFrame`, never a shifted
 * byte) — but it cannot read it, and these captures are the only ground truth this channel has.
 *
 * ## What is validated, and why each check is not optional
 *
 * - **Responder id.** Every functional request on this van draws answers from three ECUs
 *   (`7E8` engine, `7E9` TCU, `7EC`), so with headers on, frames from other modules can be
 *   interleaved with the ones being reassembled. Frames whose id is not [KwpRecordSpec.rxFilter]
 *   are dropped before reassembly rather than concatenated into it.
 * - **Sequence numbers.** Consecutive frames must run `21, 22, 23, …` (the low nibble, wrapping
 *   at 16). A dropped or reordered frame would otherwise shift every subsequent byte — including
 *   byte 18 — and produce a temperature that looks entirely plausible.
 * - **Declared length.** The first frame states how many service bytes are coming; fewer means a
 *   truncated record, which is refused rather than zero-filled. More is CAN padding (`FF` here)
 *   and is discarded, since a record is defined by its declared length, not by frame arithmetic.
 * - **`61 30` at the head.** The reassembled block must open with the positive-response header
 *   for the identifier that was asked for. An answer to a different local id is not this record.
 * - **`7F` negatives, whatever service they name.** `7F 21 xx` is this request being refused;
 *   the session also captured `7F 22 11` (the falsified UDS `22 05 43` hypothesis). Both are
 *   reported as [ParseFailure.NegativeResponse] carrying the service byte *as it arrived*, so a
 *   log shows which request the ECU thought it was rejecting instead of a bare "no frame".
 */
object KwpRecordParser {
    /**
     * Parses [raw] as [spec]'s record.
     *
     * Never throws; every wire condition is a typed [ParseFailure], exactly as in
     * [ResponseParser].
     */
    fun parse(
        spec: KwpRecordSpec,
        raw: String,
    ): ParseOutcome<KwpRecord> {
        val lines = splitLines(raw)
        val status = protocolErrorIn(lines, raw) ?: ParseFailure.Empty.takeIf { lines.isEmpty() }
        return when {
            status != null -> ParseOutcome.Failure(status)
            else ->
                when (val assembled = serviceHexOf(spec, lines)) {
                    is ParseOutcome.Failure -> assembled
                    is ParseOutcome.Success -> readRecord(spec, assembled.value, raw)
                }
        }
    }

    /**
     * The service bytes as hex, from whichever framing [lines] turn out to be in.
     *
     * Headers-on CAN frames win when any are present: they carry the responder id and the
     * declared length, which is strictly more information than the headers-off form. Falling back
     * to the headers-off form when none are present is what lets one parser serve both the
     * console captures and the app's own `ATH0` sessions.
     */
    private fun serviceHexOf(
        spec: KwpRecordSpec,
        lines: List<String>,
    ): ParseOutcome<String> {
        val frames = lines.mapNotNull(::canFrameOf).filter { spec.rxFilter == null || it.id == spec.rxFilter }
        return if (frames.isEmpty()) {
            ParseOutcome.Success(hexPayload(lines))
        } else {
            reassemble(frames)
        }
    }

    /**
     * Reads one headers-on CAN line, or `null` if this line is not one.
     *
     * The discriminator is digit parity: an 11-bit CAN id is three hex digits and everything
     * after it is whole bytes, so a headers-on line is always **odd**-length once whitespace is
     * stripped, and a headers-off data line is always even. That is a property of the framing
     * rather than a heuristic about content — but it is 11-bit-specific, which is all this van's
     * ISO 15765-4 bus uses. A 29-bit id (eight digits, even) would fall through to the
     * headers-off path, where the existing byte-aligned search handles it correctly because four
     * whole bytes preserve alignment; that is the same conclusion `MODULE.md` already records.
     *
     * A dropped frame here costs nothing that matters: it either fails the id filter (another
     * ECU's answer, which must not be reassembled) or leaves the sequence incomplete, and an
     * incomplete sequence is refused.
     */
    private fun canFrameOf(line: String): CanFrame? {
        val compact = line.filterNot(Char::isWhitespace)
        val looksAddressed = compact.length > CAN_ID_DIGITS + HEX_DIGITS_PER_BYTE && compact.length % 2 == 1
        if (!looksAddressed || !compact.all { it in HEX_DIGITS }) {
            return null
        }
        return CanFrame(
            id = compact.take(CAN_ID_DIGITS),
            payload = compact.drop(CAN_ID_DIGITS),
        )
    }

    /**
     * Joins [frames] into the service bytes they carry, honouring ISO-TP framing.
     *
     * @return the service hex, or a typed failure if the sequence does not hold together.
     */
    private fun reassemble(frames: List<CanFrame>): ParseOutcome<String> {
        val first = frames.first()
        return when (first.pciType) {
            // `0L` + L service bytes on one line: how a short answer, or a `7F` negative, arrives.
            SINGLE_FRAME -> takeDeclared(first.payload.drop(HEX_DIGITS_PER_BYTE), first.pciLowNibble)
            FIRST_FRAME -> multiFrame(frames)
            // A consecutive frame with nothing to continue: the first frame was lost or filtered.
            // Reassembling from here would silently start the record mid-way.
            else -> ParseOutcome.Failure(ParseFailure.MultiFrameSequenceError(FIRST_FRAME, first.pciType))
        }
    }

    /** `1L LL` + 6 bytes, then `2N` + 7 bytes each until the declared length is covered. */
    private fun multiFrame(frames: List<CanFrame>): ParseOutcome<String> {
        val first = frames.first()
        val continuations = frames.drop(1)
        // Every continuation is validated BEFORE any of it is appended, so a rejection cannot
        // leave a half-assembled record behind for a later change to accidentally use.
        val rejection =
            continuations.withIndex().firstNotNullOfOrNull { (index, frame) ->
                consecutiveFrameFailure(frame, expectedSequence = (index + 1) % SEQUENCE_WRAP)
            }
        if (rejection != null) {
            return ParseOutcome.Failure(rejection)
        }
        val declared = first.pciLowNibble * FIRST_FRAME_LENGTH_HIGH_WEIGHT + first.byteAt(1)
        val body =
            first.payload.drop(FIRST_FRAME_PCI_DIGITS) +
                continuations.joinToString(separator = "") { it.payload.drop(HEX_DIGITS_PER_BYTE) }
        return takeDeclared(body, declared)
    }

    /**
     * Why [frame] cannot be the continuation numbered [expectedSequence], or `null` if it can.
     *
     * Sequence is checked before length so that a frame which is both out of order and over-long
     * reports the ordering problem — the more fundamental of the two.
     *
     * The length check is round-1 review MINOR-1: a CAN consecutive frame carries at most 7 data
     * bytes after its PCI byte. An over-long line (corrupted, or two lines merged by a reassembly
     * glitch upstream) would otherwise append one byte too many and shift everything after it —
     * the trans-temp byte would land on a neighbouring field and read as a plausible temperature.
     * Under-long non-final frames are caught later by the declared-length check; over-long ones
     * have to be refused here, while the frame boundary is still known.
     */
    private fun consecutiveFrameFailure(
        frame: CanFrame,
        expectedSequence: Int,
    ): ParseFailure? =
        when {
            frame.pciType != CONSECUTIVE_FRAME || frame.pciLowNibble != expectedSequence ->
                ParseFailure.MultiFrameSequenceError(expectedSequence, frame.pciLowNibble)
            frame.payload.length > CF_PCI_AND_DATA_HEX_DIGITS ->
                ParseFailure.UnexpectedDataLength(
                    CF_PCI_AND_DATA_HEX_DIGITS / HEX_DIGITS_PER_BYTE,
                    frame.payload.length / HEX_DIGITS_PER_BYTE,
                )
            else -> null
        }

    /**
     * Trims [hex] to exactly [declared] bytes.
     *
     * Surplus is CAN padding — the captured records end `… 00 00 FF`, one byte past the declared
     * 26 — and is dropped. A shortfall is refused: the missing bytes are not zero, they are
     * unknown, and a record short of its length is a record whose later fields cannot be located.
     */
    private fun takeDeclared(
        hex: String,
        declared: Int,
    ): ParseOutcome<String> {
        val available = hex.length / HEX_DIGITS_PER_BYTE
        return if (available < declared) {
            ParseOutcome.Failure(ParseFailure.UnexpectedDataLength(declared, available))
        } else {
            ParseOutcome.Success(hex.take(declared * HEX_DIGITS_PER_BYTE))
        }
    }

    /** Validates the `61 XX` header in [serviceHex] and lifts the record bytes out of it. */
    private fun readRecord(
        spec: KwpRecordSpec,
        serviceHex: String,
        raw: String,
    ): ParseOutcome<KwpRecord> {
        val headerAt = indexOfAligned(serviceHex, spec.responseHeader)
        if (headerAt < 0) {
            return ParseOutcome.Failure(negativeOrMissing(spec, serviceHex, raw))
        }
        val dataStart = headerAt + spec.responseHeader.length
        val available = (serviceHex.length - dataStart) / HEX_DIGITS_PER_BYTE
        return if (available < spec.recordDataBytes) {
            ParseOutcome.Failure(ParseFailure.UnexpectedDataLength(spec.recordDataBytes, available))
        } else {
            ParseOutcome.Success(
                KwpRecord(
                    List(spec.recordDataBytes) { index ->
                        val at = dataStart + index * HEX_DIGITS_PER_BYTE
                        serviceHex.substring(at, at + HEX_DIGITS_PER_BYTE).toInt(HEX_RADIX)
                    },
                ),
            )
        }
    }

    /**
     * Classifies a reply with no positive header: an explicit rejection, unframeable hex, or an
     * answer to something else.
     *
     * The service byte in a `7F` frame is reported as received rather than assumed to be
     * [KwpRecordSpec.requestMode]. The session captured `7F 22 11` — the TCU rejecting UDS `22`,
     * from the hypothesis this issue's request replaced — and a log that rewrote that as "your
     * `21` was rejected" would have hidden the one fact the frame carries.
     */
    private fun negativeOrMissing(
        spec: KwpRecordSpec,
        serviceHex: String,
        raw: String,
    ): ParseFailure {
        val negativeAt = indexOfAligned(serviceHex, NEGATIVE_RESPONSE_PREFIX)
        return when {
            negativeAt >= 0 ->
                ParseFailure.NegativeResponse(
                    requestMode = byteAt(serviceHex, negativeAt + HEX_DIGITS_PER_BYTE) ?: spec.requestMode,
                    code = byteAt(serviceHex, negativeAt + 2 * HEX_DIGITS_PER_BYTE),
                )
            serviceHex.isNotEmpty() && serviceHex.length % HEX_DIGITS_PER_BYTE != 0 ->
                ParseFailure.MalformedHex(raw.trim())
            else -> ParseFailure.NoMatchingFrame(spec.responseHeader, raw.trim())
        }
    }

    private fun byteAt(
        hex: String,
        at: Int,
    ): Int? =
        if (at + HEX_DIGITS_PER_BYTE <= hex.length) {
            hex.substring(at, at + HEX_DIGITS_PER_BYTE).toInt(HEX_RADIX)
        } else {
            null
        }

    /** One printed CAN line with headers on: an 11-bit id and the ISO-TP frame behind it. */
    private data class CanFrame(
        val id: String,
        val payload: String,
    ) {
        /** The PCI high nibble: 0 single frame, 1 first frame, 2 consecutive frame. */
        val pciType: Int get() = payload[0].digitToInt(HEX_RADIX)

        /** The PCI low nibble: a length for `0`/`1` frames, a sequence number for `2` frames. */
        val pciLowNibble: Int get() = payload[1].digitToInt(HEX_RADIX)

        fun byteAt(index: Int): Int =
            payload
                .substring(index * HEX_DIGITS_PER_BYTE, (index + 1) * HEX_DIGITS_PER_BYTE)
                .toInt(HEX_RADIX)
    }

    private const val CAN_ID_DIGITS = 3
    private const val HEX_DIGITS = "0123456789ABCDEF"

    private const val SINGLE_FRAME = 0x0
    private const val FIRST_FRAME = 0x1
    private const val CONSECUTIVE_FRAME = 0x2

    /** `1L LL`: the length is 12 bits, so the PCI's low nibble is the high 4 of it. */
    private const val FIRST_FRAME_LENGTH_HIGH_WEIGHT = 0x100
    private const val FIRST_FRAME_PCI_DIGITS = 4

    /** ISO-TP sequence numbers are 4 bits and wrap `…, 14, 15, 0, 1, …`. */
    private const val SEQUENCE_WRAP = 16

    /** A CF line is its `2N` PCI nibble pair plus at most 7 data bytes: 16 hex digits total. */
    private const val CF_PCI_AND_DATA_HEX_DIGITS = 16
}
