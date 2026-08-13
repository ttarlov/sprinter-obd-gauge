package com.revel.obdgauge.protocol

import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.ObdRequest
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.PollPriority

/**
 * The 722.9 transmission controller's KWP `21 30` record, and the transmission-fluid-temperature
 * channel decoded out of it — **identified on-vehicle 2026-08-13** (OBD-55).
 *
 * ## What the drive test settled (`docs/hardware/session-3-2026-08-13-transtemp.md`)
 *
 * The 2026-08-12 session (OBD-49) confirmed the *record* — `ATSH7E1` + `21 30` → positive `61 30`,
 * a 26-byte block over four CAN frames — but not where the transmission temperature lived in it. A
 * cold-start-through-drive test on 2026-08-13 answered that: **record data byte 1** tracked thermal
 * state, inversely and monotonically, across four states, and stayed **decoupled** from engine
 * coolant (byte 11) — the discriminator that kills the coolant-echo confusion the earlier
 * byte-18 candidate could not rule out.
 *
 * | State | byte 1 raw | °C = 63 − raw | byte 11 (coolant) |
 * |---|---|---|---|
 * | Cold engine-off | `0x2D` (45) | **18** ≈ ambient (65 °F day) | — |
 * | Warm idle (13 min) | `0x23` (35) | **28** | `0x8C` → 90 °C |
 * | Post 10-min drive | `0x12` (18) | **45** | `0x91` → 95 °C |
 * | +90 s heat-soak | `0x12` (18) | **45** (steady) | `0x8F` → 93 °C (dropping) |
 *
 * The last two rows are the identification proof: byte 1 held at `0x12` while byte 11 fell 95 → 93,
 * so byte 1 is *not* a copy of coolant — it is the fluid temperature the app exists to show.
 *
 * ## Why the model is `°C = 63 − raw`, and why it still ships **unverified**
 *
 * - **Offset SOLID.** Anchored on the one independent ground-truth point: a cold soak reads ambient.
 *   `raw 0x2D (45)` at ambient `18 °C` fixes the offset at `45 + 18 = 63` exactly.
 * - **Slope PROVISIONAL.** Only that single anchor exists. The `1 °C/count` slope is *assumed* —
 *   the Mercedes convention, matching the magnitude of coolant's own `raw − 50` byte in this same
 *   record — not measured, because the gentle drive only reached ~45 °C. High-temp behaviour
 *   (wrap / saturation above ~63 °C) is **untested**. So [transTempRecord] carries
 *   `verified = false`: the number is right where it has been seen and honestly caveated where it
 *   has not. A single reading with ATF > 60 °C (a sustained grade / tow), ideally cross-checked
 *   against a STAR/Xentry ATF value for a second anchor, is what promotes it (OBD-51 residual).
 *
 * This retires the falsified X-Gauge hypothesis. [MercedesPidRegistry.transTemp] decoded record
 * byte 0 (`raw − 50`), which the capture shows is `0x00` → −50 °C; it is removed from the polled
 * catalog and `PidIds.TRANS_TEMP` now resolves to this record channel (OBD-55).
 *
 * ## Byte 11 — the coolant anchor that keeps the framing honest
 *
 * Byte 11 read `raw − 50` = engine coolant, rising with the drive and matching SAE `0105`. It is
 * **not a displayed channel** — the app already shows coolant from `0105`, and a second, subtly
 * different TCU-side copy on one dashboard is a defect, not a feature. It exists as
 * [tcuCoolantCelsius], `internal`, purely as a test-time consistency probe on the record's framing:
 * across the captured records it must read its known coolant values, and a reassembly that shifts
 * by a byte cannot keep them all — it would move byte 1 off the transmission temperature at the
 * same time, so this anchor is the tripwire.
 */
object TcuRecordRegistry {
    /** Record byte 11: TCU-side engine coolant. Test-only anchor — see the class KDoc. */
    internal const val TCU_COOLANT_BYTE = 11

    /**
     * Record byte 1: transmission fluid temperature (OBD-55, identified on-vehicle 2026-08-13).
     *
     * Repointed from byte 18 by the drive test: byte 18 was a rock-steady status byte, byte 1
     * tracked thermal state and decoupled from coolant.
     */
    const val TRANS_TEMP_BYTE = 1

    /** Record bytes that follow `61 30`: 26 service bytes less the 2-byte response header. */
    const val RECORD_DATA_BYTES = 24

    /**
     * The Mercedes coolant temperature offset: `°C = raw − 50`.
     *
     * Not a fresh guess — it is the constant OBD-15 solved for out of the ScanGauge `MTH` field
     * (`raw × 9/5 − 58` is the °F conversion of a `raw − 50` °C byte, exactly, on integers), and
     * that byte 11 independently corroborates against engine coolant in this very record. Applies
     * to the coolant anchor ([tcuCoolantCelsius]); the transmission channel uses
     * [TRANS_TEMP_OFFSET] under an **inverse** law — see the class KDoc.
     */
    const val CELSIUS_OFFSET = 50.0

    /**
     * The transmission-fluid inverse offset: `°C = 63 − raw` (OBD-55).
     *
     * Solid, unlike the slope: anchored on the cold-soak = ambient reading (`raw 0x2D → 18 °C`, so
     * `45 + 18 = 63`). The `1 °C/count` slope of `63 − raw` is provisional — see the class KDoc.
     */
    const val TRANS_TEMP_OFFSET = 63.0

    /**
     * Transmission fluid temperature from the `21 30` record, °C, **unverified** (slope
     * provisional — see the class KDoc).
     *
     * Owns [PidIds.TRANS_TEMP] (OBD-55): this is the polled, displayed trans-temp channel now that
     * the X-Gauge byte-0 decode is retired.
     *
     * `SLOW`: transmission fluid has minutes of thermal inertia, and this is a five-command
     * header-scoped sequence — spending one every cycle would crowd out the channels that
     * actually move.
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
                    parse = { data -> TRANS_TEMP_OFFSET - VendoredSaeScaling.dataByte(data, TRANS_TEMP_BYTE) },
                    pollPriority = PollPriority.SLOW,
                    // Slope is assumed, not measured (offset anchored on cold=ambient; >60 °C
                    // untested). A hot cross-checked sample flips this (🖐 OBD-51), nothing else may.
                    verified = false,
                ),
            canId = TCU_CAN_ID,
            rxFilter = TCU_RESPONSE_ID,
            localIdentifier = TRANS_TEMP_LOCAL_ID,
            recordDataBytes = RECORD_DATA_BYTES,
            dataByteIndex = TRANS_TEMP_BYTE,
        )

    /**
     * Transmission temperature from [record], °C — the inverse law `63 − raw` at byte 1. The value
     * [transTempRecord] publishes.
     */
    fun transTempCelsius(record: KwpRecord): Double = TRANS_TEMP_OFFSET - record.byteAt(TRANS_TEMP_BYTE)

    /**
     * TCU-side engine coolant from [record], °C.
     *
     * **Not a displayed channel and deliberately `internal`** — engine coolant comes from SAE
     * `0105`. This is the framing consistency probe described in the class KDoc: across the captured
     * records it must read the coolant values the session wrote down, and a reassembly that shifts
     * by a byte cannot keep all of them.
     */
    internal fun tcuCoolantCelsius(record: KwpRecord): Double = record.byteAt(TCU_COOLANT_BYTE) - CELSIUS_OFFSET

    /** Physical address of the 722.9 transmission controller. */
    private const val TCU_CAN_ID = "7E1"

    /** Its response id, per ISO 15765-4 (`7E1 + 8`) — and what the capture actually shows. */
    private const val TCU_RESPONSE_ID = "7E9"

    private const val TRANS_TEMP_LOCAL_ID = 0x30
    private const val REQUEST_BYTES = "2130"
}
