package com.revel.obdgauge.protocol

import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.ObdRequest
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.PollPriority

/**
 * The 722.9 transmission controller's KWP `21 30` record, as the 2026-08-12 session found it
 * (OBD-49).
 *
 * ## What the van actually answered
 *
 * Two hypotheses went to the vehicle; the session settled both
 * (`docs/hardware/session-2026-08-12.md`):
 *
 * - `ATSH7E1` + `22 05 43` (UDS `readDataByIdentifier`) → `7F 22 11`. **Falsified**: no UDS `22`
 *   in the default session, and this project does not change diagnostic sessions — it is
 *   read-only by charter.
 * - `ATSH7E1` + `21 30` (KWP `readDataByLocalIdentifier`) → **confirmed**: positive `61 30`, a
 *   26-byte record over four CAN frames.
 *
 * The 24 record bytes at warm idle:
 * ```
 *   00 13 00 00 | 00 00 00 08 04 00 DD | 8E FF F3 FF F3 00 00 | 86 18 00 08 00 00
 *    0  1  2  3    4  5  6  7  8  9 10   11 12 13 14 15 16 17   18 19 20 21 22 23
 * ```
 *
 * ## Byte 11 — the anchor that makes the encoding believable
 *
 * Byte 11 read `8E → 8D → 93` across the session: **92 → 91 → 97 °C** under `raw − 50`, tracking
 * engine coolant and rising ~6 °C over a 15-minute drive. That matters twice over. It is an
 * independent field confirmation of the `−50` offset that OBD-15 derived *algebraically* from the
 * ScanGauge `MTH` field, arrived at from a completely different direction — and `−50` is the only
 * offset that makes both temperature fields in this record land somewhere sane at once (`−40`
 * leaves no self-consistent assignment).
 *
 * It is **not a displayed channel**: the app already reads engine coolant from SAE `0105`, and
 * publishing a second, TCU-side copy of it would put two subtly different coolant numbers on one
 * dashboard. It exists as [tcuCoolantCelsius], `internal`, purely as a test-time consistency
 * probe on the record's framing — if reassembly ever shifts, this anchor moves off 92/91/97 and
 * says so before a *transmission* temperature does.
 *
 * ## Byte 18 — the channel, and why it ships unverified
 *
 * Byte 18 read `86` = **84 °C** under the same `raw − 50`. Plausible for a warm 722.9, and
 * consistent with its thermostatic cooler regulation (~85 °C setpoint). But it did not move:
 * `0x86` through idle, through a 90-second converter stall, and through a 15-minute drive.
 *
 * Rock-steady is exactly what a correctly-regulated warm transmission looks like — and also
 * exactly what a **hard-coded setpoint constant** looks like. The capture cannot tell those apart,
 * so [transTempRecord]'s definition carries `verified = false` and this module says so rather
 * than shipping a confident 84 °C.
 *
 * 🖐 **The proof is one cold-start capture** (`issues/OBD-49.md`): a single `2130` poll on a cold
 * morning, before driving. Byte 18 near ambient `+ 50` and then climbing toward `0x86` proves the
 * field, the offset and the encoding in one shot. Byte 18 still reading `0x86` stone cold proves
 * it is a constant, and the search moves on.
 *
 * ## Why this does not replace [MercedesPidRegistry.transTemp]
 *
 * Both decode the same `21 30` request, and they disagree about where the temperature is: the
 * X-Gauge `RXD 1808` says the byte immediately after `61 30` (record byte 0), this capture says
 * record byte 18. The capture is the better evidence — record byte 0 is `0x00`, i.e. −50 °C — but
 * the two definitions are kept apart under different ids, and this one is **not** in
 * [PidCatalog.polled], because promoting it is a change to a displayed channel that should follow
 * the cold-start proof rather than precede it. See `MODULE.md`'s known limitations, which records
 * the consequence of leaving the older decode wired up.
 */
object TcuRecordRegistry {
    /** Record byte 11: TCU-side engine coolant. Test-only anchor — see the class KDoc. */
    internal const val TCU_COOLANT_BYTE = 11

    /** Record byte 18: the transmission-temperature candidate. */
    const val TRANS_TEMP_BYTE = 18

    /** Record bytes that follow `61 30`: 26 service bytes less the 2-byte response header. */
    const val RECORD_DATA_BYTES = 24

    /**
     * The Mercedes temperature offset: `°C = raw − 50`.
     *
     * Not a fresh guess — it is the constant OBD-15 solved for out of the ScanGauge `MTH` field
     * (`raw × 9/5 − 58` is the °F conversion of a `raw − 50` °C byte, exactly, on integers), and
     * that byte 11 independently corroborates against engine coolant in this very record.
     */
    const val CELSIUS_OFFSET = 50.0

    /**
     * Transmission fluid temperature from the `21 30` record, °C, **unverified**.
     *
     * `SLOW`: transmission fluid has minutes of thermal inertia, and this is a five-command
     * header-scoped sequence — spending one every cycle would crowd out the channels that
     * actually move.
     */
    val transTempRecord: KwpRecordSpec =
        KwpRecordSpec(
            definition =
                PidDefinition(
                    id = ProtocolPidIds.TRANS_TEMP_RECORD,
                    label = "Trans",
                    unit = MeasurementUnit.CELSIUS,
                    request =
                        ObdRequest.Mode22(
                            header = TCU_CAN_ID,
                            rxFilter = TCU_RESPONSE_ID,
                            request = REQUEST_BYTES,
                        ),
                    parse = { data -> VendoredSaeScaling.dataByte(data, TRANS_TEMP_BYTE) - CELSIUS_OFFSET },
                    pollPriority = PollPriority.SLOW,
                    // The cold-start capture (🖐 OBD-49) flips this, and nothing else may.
                    verified = false,
                ),
            canId = TCU_CAN_ID,
            rxFilter = TCU_RESPONSE_ID,
            localIdentifier = TRANS_TEMP_LOCAL_ID,
            recordDataBytes = RECORD_DATA_BYTES,
            dataByteIndex = TRANS_TEMP_BYTE,
        )

    /** Transmission temperature from [record], °C. The value [transTempRecord] publishes. */
    fun transTempCelsius(record: KwpRecord): Double = record.byteAt(TRANS_TEMP_BYTE) - CELSIUS_OFFSET

    /**
     * TCU-side engine coolant from [record], °C.
     *
     * **Not a displayed channel and deliberately `internal`** — engine coolant comes from SAE
     * `0105`. This is the framing consistency probe described in the class KDoc: across the three
     * captured records it must read 92, 91 and 97 °C, and a reassembly that shifts by a byte
     * cannot keep all three.
     */
    internal fun tcuCoolantCelsius(record: KwpRecord): Double = record.byteAt(TCU_COOLANT_BYTE) - CELSIUS_OFFSET

    /** Physical address of the 722.9 transmission controller. */
    private const val TCU_CAN_ID = "7E1"

    /** Its response id, per ISO 15765-4 (`7E1 + 8`) — and what the capture actually shows. */
    private const val TCU_RESPONSE_ID = "7E9"

    private const val TRANS_TEMP_LOCAL_ID = 0x30
    private const val REQUEST_BYTES = "2130"
}
