package com.revel.obdgauge.protocol

import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.ObdRequest
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.PollPriority

/**
 * The 722.9 transmission controller's KWP `21 30` record, and the transmission-fluid-temperature
 * channel decoded from it: **record data byte 11, `°C = raw − 50`**.
 *
 * ## How byte 11 was identified (OBD-60, `docs/hardware/session-5-2026-08-13-transtemp-IDENTIFIED.md`)
 *
 * A diagnostic build logged the entire `21 30` record every poll cycle — 2495 samples across a
 * 29-minute warm-restart-through-drive — next to coolant (`0105`), RPM and oil (`015C`) ground
 * truth. Byte 11 is the only one of 24 bytes that behaves like a warming fluid: frame-to-frame
 * jitter 0.1 counts (a temperature has thermal inertia), correlation +0.94 with oil and +0.83 with
 * coolant, a bounded 66→99 °C band, and — the decisive test — it sat **25 °C below coolant** early in
 * the drive and *converged* as the transmission heated under load (2310 / 2495 samples below coolant,
 * zero above). See [transTempCelsius].
 *
 * ### Why it is transmission fluid and not the "coolant echo" earlier sessions called it
 *
 * Session 2 saw byte 11 track coolant in lockstep and labeled it a TCU-side coolant echo — but that
 * was an **idle-only** observation, where the transmission makes no heat and ATF, block and coolant
 * soak-warm together. Under drive load the byte decouples 25 °C below coolant, which a software mirror
 * of the `0105` value cannot do: two thermal masses with different warm-up slopes (coolant swung
 * 11 °C over the drive, this byte swung 33 °C). Reported by the transmission controller, a slow fluid
 * that lags engine coolant and converges under load is the ATF sump temperature. So the *decode*
 * session 2 wrote (`raw − 50`) was right; only its *label* was wrong, and the drive corrects it.
 *
 * The byte-1 `63 − raw` candidate (OBD-55) that this replaces was FALSIFIED on-vehicle — it jumps
 * frame-to-frame at operating RPM (`docs/hardware/session-4-2026-08-13-transtemp-FALSIFIED.md`,
 * OBD-59). Byte 11 passes every test byte 1 failed, which is exactly why it is trusted where byte 1
 * was not: it is stable, thermally correlated, and physically decoupled from the quantity it merely
 * resembles at idle.
 *
 * ## The `raw − 50` offset is anchored, not guessed
 *
 * The cold-soak record from session 2 reads byte 11 = `0x4D` = 77 → `77 − 50 = 27 °C` = ambient at
 * full cold soak. Nothing reads above a cold-soaked ambient, which rules out `raw − 40` and pins
 * `raw − 50` — the same Mercedes coolant-family offset OBD-15 solved from the ScanGauge `MTH` field
 * ([CELSIUS_OFFSET]).
 */
object TcuRecordRegistry {
    /**
     * Record data byte 11: transmission fluid temperature, `°C = raw − 50`. Identified on-vehicle
     * across 2495 samples — see the class KDoc and session-5 doc.
     */
    const val TRANS_TEMP_BYTE = 11

    /** Record bytes that follow `61 30`: 26 service bytes less the 2-byte response header. */
    const val RECORD_DATA_BYTES = 24

    /**
     * The Mercedes coolant-family temperature offset: `°C = raw − 50`.
     *
     * Not a fresh guess — it is the constant OBD-15 solved for out of the ScanGauge `MTH` field
     * (`raw × 9/5 − 58` is the °F conversion of a `raw − 50` °C byte, exactly, on integers), and it is
     * anchored a second way by the cold-soak record reading ambient (class KDoc).
     */
    const val CELSIUS_OFFSET = 50.0

    /**
     * Transmission fluid temperature in °C from an unsigned record byte value: `raw − 50`.
     *
     * The single arithmetic source for the decode — both [transTempRecord]'s parse lambda and the
     * [transTempCelsius] record overload route through here, so there is exactly one place the
     * offset lives.
     */
    fun transTempCelsius(rawByte: Int): Double = rawByte - CELSIUS_OFFSET

    /** Transmission fluid temperature in °C from a parsed [record] — reads byte [TRANS_TEMP_BYTE]. */
    fun transTempCelsius(record: KwpRecord): Double = transTempCelsius(record.byteAt(TRANS_TEMP_BYTE))

    /**
     * The `21 30` record channel: transmission fluid temperature, byte 11, `°C = raw − 50`.
     *
     * Owns [PidIds.TRANS_TEMP]. The parse reads byte [TRANS_TEMP_BYTE] as unsigned (`and 0xFF`,
     * because [PidDefinition.parse] receives Kotlin's signed `ByteArray`) and applies
     * [transTempCelsius]. `verified = true`: identified against on-vehicle ground truth (OBD-60).
     *
     * `SLOW`: transmission fluid has minutes of thermal inertia, and this is a five-command
     * header-scoped sequence — no reason to spend the BLE round trips at the dashboard's fast rate.
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
                    // Byte 11, °C = raw − 50. dataByte masks the signed ByteArray to 0-255 and
                    // bounds-checks, the same boundary every mode-01 scaling lambda uses.
                    parse = { bytes -> transTempCelsius(VendoredSaeScaling.dataByte(bytes, TRANS_TEMP_BYTE)) },
                    pollPriority = PollPriority.SLOW,
                    verified = true,
                ),
            canId = TCU_CAN_ID,
            rxFilter = TCU_RESPONSE_ID,
            localIdentifier = TRANS_TEMP_LOCAL_ID,
            recordDataBytes = RECORD_DATA_BYTES,
            dataByteIndex = TRANS_TEMP_BYTE,
        )

    /** Physical address of the 722.9 transmission controller. */
    private const val TCU_CAN_ID = "7E1"

    /** Its response id, per ISO 15765-4 (`7E1 + 8`) — and what the capture actually shows. */
    private const val TCU_RESPONSE_ID = "7E9"

    private const val TRANS_TEMP_LOCAL_ID = 0x30
    private const val REQUEST_BYTES = "2130"
}
