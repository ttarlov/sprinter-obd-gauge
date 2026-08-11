package com.revel.obdgauge.protocol

import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.ObdRequest
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.PollPriority

/**
 * One manufacturer-specific PID: its [PidDefinition] plus the wire facts needed to frame the
 * request and find the answer. The mode-22 counterpart of [StandardPidSpec].
 *
 * "Mode 22" is this project's shorthand (from `ObdRequest.Mode22` and the build plan) for
 * *manufacturer-specific reads that need `ATSH`/`ATCRA` header control*. The Sprinter trans-temp
 * code actually uses KWP2000 mode `21` (`readDataByLocalIdentifier`); UDS mode `22`
 * (`readDataByIdentifier`) is the same shape with a 2-byte identifier. Both are handled: nothing
 * here assumes a particular mode byte or identifier length.
 *
 * @param definition the contract-level definition handed to the UI and the scheduler. Always
 *   `verified = false` here — see [MercedesPidRegistry].
 * @param canId the `ATSH` transmit header, e.g. `"7E1"`.
 * @param rxFilter the `ATCRA` receive filter, e.g. `"7E9"`, or `null` to leave filtering alone.
 * @param requestBytes the request payload, e.g. `"2130"`.
 * @param responseHeader the header a positive answer opens with, e.g. `"6130"`.
 * @param dataByteIndex index of the value's first byte within the data that follows
 *   [responseHeader].
 * @param dataByteCount how many bytes the value occupies.
 * @param source the X-Gauge code this spec was decoded from, kept for the debug console, for
 *   hardware bring-up (OBD-22), and so the decode stays auditable at runtime.
 */
data class Mode22PidSpec(
    val definition: PidDefinition,
    val canId: String,
    val rxFilter: String?,
    val requestBytes: String,
    val responseHeader: String,
    val dataByteIndex: Int,
    val dataByteCount: Int,
    val source: XGaugeCode,
) {
    /** The request mode, e.g. `0x21` — needed to recognize its `7F` negative response. */
    val requestMode: Int get() = requestBytes.substring(0, HEX_DIGITS_PER_BYTE).toInt(HEX_RADIX)

    /** Total data bytes that must follow [responseHeader] for the value to be readable. */
    val requiredDataBytes: Int get() = dataByteIndex + dataByteCount
}

/**
 * The Mercedes-specific PIDs for the OM642 / 722.6 Sprinter, translated from the van's proven
 * ScanGauge X-Gauge codes.
 *
 * **Everything here is a hypothesis.** Each definition carries `verified = false` and stays that
 * way until a real capture from the van's ECU confirms it (OBD-22 / OBD-26). "Proven" above means
 * proven *on a ScanGauge*, in °F, through a different piece of hardware — not proven through this
 * app's ELM327 path, and not proven byte-for-byte. Consumers must surface the flag (OBD-27):
 * see [PidCatalog.isVerified].
 *
 * ## Trans temp (722.6 transmission oil), the channel this app exists for
 *
 * X-Gauge code, verbatim: `TXD 07E12130`, `RXF 032200000000`, `RXD 1808`, `MTH 00090005FFC6`.
 * The decode is executable — see [XGaugeCode] for each field's format and for the coolant
 * cross-check that establishes those formats — and it comes out as:
 *
 * | Field | Value | Meaning |
 * |---|---|---|
 * | TXD id | `07E1` → `7E1` | physical address of the transmission controller ⇒ `ATSH 7E1` |
 * | TXD data | `21 30` | KWP `readDataByLocalIdentifier`, local id `0x30` |
 * | response | `61 30 XX` | request mode `+ 0x40`, identifier echoed, one data byte |
 * | RXF | `03` + `22 00 …` | PCI `03` ⇒ a 3-byte single-frame reply — consistent with `61 30 XX` |
 * | RXD | `18 08` | bit 24, 8 bits ⇒ frame byte 3 ⇒ the byte right after `61 30` |
 * | MTH | `0009 / 0005 / FFC6` | `raw × 9 ÷ 5 + (−58)`, the value a ScanGauge displays, in °F |
 *
 * ### Why `−58` is not an anomaly (the algebra, because this constant *is* the hypothesis)
 *
 * A naive reading expects a temperature code to end in the SAE `−40` offset, and `−58` is neither
 * `−40` nor the `+32` of a °C→°F conversion. Solve it instead of eyeballing it:
 * ```
 *   MTH says          display = raw × 9/5 − 58
 *   °F is defined as  °F      = °C × 9/5 + 32
 *   assume            °C      = raw − k
 *   then              °F      = (raw − k) × 9/5 + 32 = raw × 9/5 + (32 − 9k/5)
 *   match the adder:  32 − 9k/5 = −58  ⇒  9k/5 = 90  ⇒  k = 50
 * ```
 * `k = 50` comes out **exactly**, on integers. So the code is precisely the °C→°F conversion of a
 * raw byte that encodes °C with a **−50** offset — a known Mercedes convention, and a different
 * one from the SAE −40 that `0105` uses. Had the raw been SAE-style `A − 40`, the adder would
 * have been `−40` (`FFD8`); the published *coolant* X-Gauge carries exactly that, which is the
 * control case. The `−58` is therefore *evidence for* the decode, not against it.
 *
 * Two independent fields corroborate the byte position on top of that: RXF's PCI byte says the
 * reply is 3 bytes (`61 30 XX` — exactly one data byte), and RXD says read 8 bits at frame byte 3
 * (that same single byte). They agree.
 *
 * ### What this module publishes, and the one open ambiguity
 *
 * Published value: **°C = raw − 50**, natural unit, per the module's unit strategy. Degrees F is
 * the UI's conversion, as for every other temperature. The `−50` is not typed in by hand — it is
 * derived from `MTH` at class-init by [XGaugeDecode.celsiusOffset], and `MercedesPidRegistryTest`
 * asserts that converting the published °C back to °F reproduces the ScanGauge formula bit for
 * bit across the whole `0..255` raw range. Plausible range: raw `0..255` ⇒ −50 °C … 205 °C, with
 * a warm 722.6 landing around 80–110 °C (raw `130`–`160`).
 *
 * **The open ambiguity is `RXF`, and it is not in the value path.** Under the field format the
 * coolant control case establishes (`PCI` + leading response bytes), this code's `03 22 00 …`
 * should read `03 61 30 00 …` — the `22` is not the `61` a `21` request must answer with, and it
 * is not any mode byte a `21 30` request can produce. Rather than invent a meaning for it, this
 * module does not use RXF at all:
 *
 * - `ATCRA` is set from the **ISO 15765-4 convention** instead — a physical request to `7E1` is
 *   answered by `7E9` (request id `+ 8`) — which is independently checkable and, unlike RXF,
 *   is what `ATCRA` actually takes (a CAN id, not a frame pattern).
 * - Correctness does not rest on it either way: `ATCRA` is a noise filter, and the value is only
 *   accepted if [Mode22ResponseParser] finds a byte-aligned `61 30` header in the reply. A wrong
 *   or absent filter yields a skipped reading, never a wrong number. If bring-up finds `7E9`
 *   filters out the real answer, [Mode22Config.rxFilterEnabled] turns `ATCRA` off wholesale and
 *   the payload match still guards the value.
 * - Logged for OBD-22: capture what `7E1`/`21 30` actually answers with, and settle `RXF`.
 *
 * ### Codes deliberately not ported
 *
 * `docs/01-build-plan.md` §2B also lists a mode-22 *boost source* (`TXD 07DF018670`,
 * `MTH 00910BB8____`). It is not here, for two reasons: OBD-16 computes boost from the standard
 * `010B`/`0133` pair instead, which is altitude-correct and fully verified against SAE; and that
 * code's transcription is visibly incomplete (the literal `____` in its MTH field) and its RXD
 * does not decode consistently, so porting it would mean guessing. Oil temp for the OM642 is
 * unscheduled (OBD-35). Adding either later is a new [XGaugeCode] constant and one registry
 * entry — no new machinery.
 */
object MercedesPidRegistry {
    /**
     * The trans-temp X-Gauge code exactly as it is entered on a ScanGauge II. Decoded, not
     * transcribed — see the class KDoc.
     */
    val TRANS_TEMP_CODE: XGaugeCode =
        XGaugeCode(
            name = "TFT",
            txd = "07E12130",
            rxf = "032200000000",
            rxd = "1808",
            mth = "00090005FFC6",
        )

    /** Transmission oil temperature (722.6), °C, **unverified**. See the class KDoc. */
    val transTemp: Mode22PidSpec = spec(PidIds.TRANS_TEMP, "Trans", TRANS_TEMP_CODE)

    /** Every manufacturer PID in the registry, in a stable declaration order. */
    val all: List<Mode22PidSpec> = listOf(transTemp)

    /** The [PidDefinition]s of [all], for handing to a `VehicleDataSource`. */
    val definitions: List<PidDefinition> get() = all.map(Mode22PidSpec::definition)

    /** Looks a spec up by its [PidDefinition.id], or `null` if this registry does not define it. */
    fun byId(id: String): Mode22PidSpec? = all.firstOrNull { it.definition.id == id }

    /**
     * Builds a spec from a decoded temperature [code], publishing °C.
     *
     * The `parse` lambda is derived from `MTH`, never hand-written: the offset comes from
     * [XGaugeDecode.celsiusOffset], which fails loudly if the code is not a °C→°F conversion. A
     * mistyped `MTH` therefore breaks the build's tests rather than shifting a gauge by a few
     * degrees.
     */
    private fun spec(
        id: String,
        label: String,
        code: XGaugeCode,
    ): Mode22PidSpec {
        val decoded = code.decode()
        val offset = decoded.celsiusOffset
        require(decoded.dataByteCount == 1) {
            "${code.name}: only single-byte temperature codes are supported, RXD asked for ${decoded.dataByteCount}"
        }
        val index = decoded.dataByteIndex
        val filter = responseIdFor(decoded.canId)
        return Mode22PidSpec(
            definition =
                PidDefinition(
                    id = id,
                    label = label,
                    unit = MeasurementUnit.CELSIUS,
                    request =
                        ObdRequest.Mode22(
                            // The frozen contract types rxFilter as non-null, so "no filter"
                            // is the empty string here; the spec below keeps the nullable form
                            // that Mode22Requester actually branches on.
                            header = decoded.canId,
                            rxFilter = filter.orEmpty(),
                            request = decoded.requestBytes,
                        ),
                    parse = { data -> VendoredSaeScaling.dataByte(data, index) - offset },
                    pollPriority = PollPriority.SLOW,
                    verified = false,
                ),
            canId = decoded.canId,
            rxFilter = filter,
            requestBytes = decoded.requestBytes,
            responseHeader = decoded.responseHeader,
            dataByteIndex = decoded.dataByteIndex,
            dataByteCount = decoded.dataByteCount,
            source = code,
        )
    }

    /**
     * The `ATCRA` receive filter for a physical request to [txId], per ISO 15765-4: an ECU
     * addressed at `7E0..7E7` answers from `7E8..7EF`, i.e. request id `+ 8`. `7E1` (the
     * transmission controller) ⇒ `7E9`.
     *
     * Returns `null` for any id outside the standard physical-request range — notably the `7DF`
     * functional broadcast, which has no single responder — so the filter is simply not set
     * rather than set to something invented. See the RXF discussion in the class KDoc for why
     * this is derived here instead of read out of the X-Gauge code.
     */
    internal fun responseIdFor(txId: String): String? {
        val id = txId.toIntOrNull(HEX_RADIX) ?: return null
        val physical = id in FIRST_PHYSICAL_REQUEST_ID..LAST_PHYSICAL_REQUEST_ID
        return if (physical) hexByte(id + PHYSICAL_RESPONSE_OFFSET) else null
    }

    // `const`, not `val`: these are read by responseIdFor while this object's own property
    // initializers are still running (transTemp is built at class-init), and a non-const val
    // declared below that point would still be null at the moment it is used.
    private const val FIRST_PHYSICAL_REQUEST_ID = 0x7E0
    private const val LAST_PHYSICAL_REQUEST_ID = 0x7E7
    private const val PHYSICAL_RESPONSE_OFFSET = 8
}
