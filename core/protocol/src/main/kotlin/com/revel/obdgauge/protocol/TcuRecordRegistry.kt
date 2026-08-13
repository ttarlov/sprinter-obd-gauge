package com.revel.obdgauge.protocol

import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.ObdRequest
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.PollPriority

/**
 * The 722.9 transmission controller's KWP `21 30` record — and, deliberately, **no transmission
 * temperature decoded out of it today**.
 *
 * ## The byte-1 decode was FALSIFIED on-vehicle (OBD-59, `docs/hardware/session-4-2026-08-13-transtemp-FALSIFIED.md`)
 *
 * OBD-55 identified transmission fluid temperature at record **data byte 1** under `°C = 63 − raw`,
 * from a cold-start-through-drive test. That identification was wrong. A live look at operating RPM
 * (engine warming, ~1800 rpm fast idle) on 2026-08-13 caught byte 1 jumping frame-to-frame —
 * four `2130` reads seconds apart read `39, 08, 04, 1C`, which under `63 − raw` is `6, 55, 59, 35 °C`.
 * A temperature does not move 50 °C in seconds: byte 1 is a fast dynamic signal (pressure / slip /
 * duty / current), not a temperature. The OBD-55 identification had aliased from ~6 sparse samples
 * all taken at comparable quiet-idle / parked / engine-off states, where byte 1 happened to sit at
 * decreasing values as the vehicle warmed — no frame-to-frame stability check, no full warmup curve.
 *
 * So the byte-1 `transTempCelsius(63 − raw)` decode is **retired**. `PidIds.TRANS_TEMP` is re-gated
 * to unavailable ([PidCatalog.FALSIFIED_DECODES]): the tile shows "—" rather than a jumping wrong
 * number. Honest-blank beats wrong-but-badged.
 *
 * ## Why the record itself is KEPT (OBD-51)
 *
 * The `21 30` request, its multi-frame reassembly ([KwpRecordParser]) and the framed exchange
 * ([KwpRecordRequester]) all stay. Re-identifying the real transmission-temperature byte needs
 * exactly this machinery: OBD-51 will log the ENTIRE record every few seconds across a cold-start →
 * warmup → drive with simultaneous coolant (`0105`) ground truth, then offline find the byte that is
 * stable frame-to-frame, rises slowly and monotonically, sits below coolant during warmup and
 * converges when hot. Until a byte passes all four on a full curve, [transTempRecord] scales
 * nothing — its parse **refuses**, the same way computed boost's does, so no accidental consumer can
 * publish a value from an unidentified field.
 *
 * ## Byte 11 — the reassembly-correctness probe (kept)
 *
 * Byte 11 reads `raw − 50` = engine coolant, corroborated against SAE `0105` across the captures. It
 * is **not a displayed channel** — the app already shows coolant from `0105` — and it was originally
 * the framing tripwire under the byte-1 decode. With that decode retired it stays as a pure
 * reassembly-correctness probe: across the captured records [tcuCoolantCelsius] must read the known
 * coolant values, and a reassembly shifted by a byte cannot keep all of them. OBD-51's re-ID relies
 * on the framing landing on the right offsets, so this guard earns its keep.
 */
object TcuRecordRegistry {
    /** Record byte 11: TCU-side engine coolant. Test-only reassembly probe — see the class KDoc. */
    internal const val TCU_COOLANT_BYTE = 11

    /**
     * No transmission-temperature field is identified in this record.
     *
     * The byte-1 `63 − raw` candidate (OBD-55) was FALSIFIED on-vehicle 2026-08-13 — it jumps at
     * operating RPM (`docs/hardware/session-4-2026-08-13-transtemp-FALSIFIED.md`). [transTempRecord]
     * therefore scales nothing and its parse refuses; OBD-51 re-identifies the real byte. Used as
     * [KwpRecordSpec.dataByteIndex] only to satisfy the spec's shape — it is never read, because the
     * refusing parse fails first.
     */
    const val TRANS_TEMP_BYTE_UNIDENTIFIED = -1

    /** Record bytes that follow `61 30`: 26 service bytes less the 2-byte response header. */
    const val RECORD_DATA_BYTES = 24

    /**
     * The Mercedes coolant temperature offset: `°C = raw − 50`.
     *
     * Not a fresh guess — it is the constant OBD-15 solved for out of the ScanGauge `MTH` field
     * (`raw × 9/5 − 58` is the °F conversion of a `raw − 50` °C byte, exactly, on integers), and
     * that byte 11 independently corroborates against engine coolant in this very record. Applies
     * to the reassembly probe ([tcuCoolantCelsius]).
     */
    const val CELSIUS_OFFSET = 50.0

    /**
     * The `21 30` record channel, kept for OBD-51's re-identification but publishing **no value**.
     *
     * Owns [PidIds.TRANS_TEMP] and stays in [PidCatalog.polled] so [PidCatalog.availabilityOf]
     * reports it as [ChannelAvailability.DecodeFalsified] and [RealVehicleDataSource]'s gate keeps
     * it off the wire — the tile blanks to "—". The byte-1 `63 − raw` decode is retired (OBD-59,
     * falsified on-vehicle), so `parse` refuses rather than scaling an unidentified field: were the
     * gate ever removed, a poll would skip on a [ParseFailure.ScalingError] rather than render a
     * wrong number.
     *
     * `verified = false`: it was never verified, and now it has no decode to verify at all.
     *
     * `SLOW`: transmission fluid has minutes of thermal inertia, and this is a five-command
     * header-scoped sequence — the poll cadence OBD-51 will want when it re-arms this channel.
     */
    val transTempRecord: KwpRecordSpec =
        KwpRecordSpec(
            definition =
                PidDefinition(
                    id = PidIds.TRANS_TEMP,
                    label = "Trans",
                    unit = MeasurementUnit.CELSIUS,
                    request =
                        ObdRequest.Mode22(
                            header = TCU_CAN_ID,
                            rxFilter = TCU_RESPONSE_ID,
                            request = REQUEST_BYTES,
                        ),
                    // Byte-1 63−raw FALSIFIED on-vehicle 2026-08-13 (jumps at operating RPM). No
                    // field is identified, so this refuses rather than decode one — OBD-51 re-IDs.
                    parse = {
                        throw UnsupportedOperationException(
                            "trans-temp byte-1 decode falsified on-vehicle 2026-08-13; no field identified (OBD-51)",
                        )
                    },
                    pollPriority = PollPriority.SLOW,
                    verified = false,
                ),
            canId = TCU_CAN_ID,
            rxFilter = TCU_RESPONSE_ID,
            localIdentifier = TRANS_TEMP_LOCAL_ID,
            recordDataBytes = RECORD_DATA_BYTES,
            dataByteIndex = TRANS_TEMP_BYTE_UNIDENTIFIED,
        )

    /**
     * TCU-side engine coolant from [record], °C.
     *
     * **Not a displayed channel and deliberately `internal`** — engine coolant comes from SAE
     * `0105`. This is the reassembly-consistency probe described in the class KDoc: across the
     * captured records it must read the coolant values the session wrote down, and a reassembly that
     * shifts by a byte cannot keep all of them. OBD-51's byte re-identification relies on the framing
     * landing on the right offsets, so this guard stays.
     */
    internal fun tcuCoolantCelsius(record: KwpRecord): Double = record.byteAt(TCU_COOLANT_BYTE) - CELSIUS_OFFSET

    /** Physical address of the 722.9 transmission controller. */
    private const val TCU_CAN_ID = "7E1"

    /** Its response id, per ISO 15765-4 (`7E1 + 8`) — and what the capture actually shows. */
    private const val TCU_RESPONSE_ID = "7E9"

    private const val TRANS_TEMP_LOCAL_ID = 0x30
    private const val REQUEST_BYTES = "2130"
}
