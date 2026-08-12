package com.revel.obdgauge.protocol

import com.revel.obdgauge.model.PidDefinition

/**
 * One KWP2000 `21 xx` **record** channel on a physically addressed ECU (OBD-49).
 *
 * The mode-22 counterpart of this — [Mode22PidSpec] — models a request whose answer is a single
 * frame carrying one value. This models the shape the 722.9 transmission controller actually
 * sends: `readDataByLocalIdentifier` returns a *block* of ECU state, 26 bytes over four CAN
 * frames, from which individual fields are read by offset. The whole record is fetched once and
 * decoded many times; splitting it into per-field requests would mean N round trips for data the
 * ECU already sent in one.
 *
 * @param definition the contract-level definition handed to the UI and the scheduler. `verified`
 *   is `false` until a cold-start capture proves the byte offset — see [TcuRecordRegistry].
 * @param canId the `ATSH` transmit header, e.g. `"7E1"`.
 * @param rxFilter the `ATCRA` receive filter and the CAN id whose frames are reassembled, e.g.
 *   `"7E9"`, or `null` to accept any responder. On this van three ECUs answer every functional
 *   request, so for a physically addressed read this is what keeps another module's frames out of
 *   the reassembly buffer when headers are on.
 * @param localIdentifier the KWP local id being read, e.g. `0x30`.
 * @param recordDataBytes how many bytes follow the `61 XX` positive-response header, e.g. 24.
 * @param dataByteIndex index within those bytes of the field [definition] scales.
 */
data class KwpRecordSpec(
    val definition: PidDefinition,
    val canId: String,
    val rxFilter: String?,
    val localIdentifier: Int,
    val recordDataBytes: Int,
    val dataByteIndex: Int,
) {
    /** KWP `readDataByLocalIdentifier`. Needed to recognize its `7F 21 xx` negative response. */
    val requestMode: Int get() = KWP_READ_BY_LOCAL_IDENTIFIER

    /** The request payload, e.g. `"2130"`. */
    val requestBytes: String get() = hexByte(requestMode) + hexByte(localIdentifier)

    /** The header a positive answer opens with: request mode `+ 0x40`, id echoed — e.g. `"6130"`. */
    val responseHeader: String get() = hexByte(requestMode + POSITIVE_RESPONSE_OFFSET) + hexByte(localIdentifier)

    /** Total service bytes in a complete positive answer: the 2-byte header plus the record. */
    val serviceByteCount: Int get() = RESPONSE_HEADER_BYTES + recordDataBytes
}

/**
 * The record bytes that followed a `61 XX` positive-response header — the ECU's block of state,
 * reassembled and length-checked, with nothing interpreted yet.
 *
 * A type of its own rather than a bare `List<Int>` so that "24 validated record bytes" cannot be
 * confused with "some bytes off the wire" at a call site, and so field decoders
 * ([TcuRecordRegistry]) have one place to hang off.
 */
data class KwpRecord(
    val bytes: List<Int>,
) {
    /**
     * The record byte at [index].
     *
     * @throws IllegalArgumentException if [index] is outside the record. Reachable only by
     *   misuse: [KwpRecordParser] refuses a record short of its spec's length before this type is
     *   constructed, so a decoder cannot silently read a padding byte as a temperature.
     */
    fun byteAt(index: Int): Int {
        require(index in bytes.indices) {
            "record byte index $index out of bounds for a ${bytes.size}-byte record"
        }
        return bytes[index]
    }
}

/** KWP2000 service `21`, `readDataByLocalIdentifier`. */
internal const val KWP_READ_BY_LOCAL_IDENTIFIER = 0x21

/** A positive response repeats the service byte and the identifier: `61 30` for `21 30`. */
internal const val RESPONSE_HEADER_BYTES = 2
