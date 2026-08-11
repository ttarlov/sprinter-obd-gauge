package com.revel.obdgauge.protocol

/**
 * One ScanGauge **X-Gauge** code, verbatim as a human types it into a ScanGauge II.
 *
 * These four fields are the only documentation that exists for the Mercedes-specific channels
 * this app needs: the OM642/722.6 transmission temperature has no standard SAE PID, and the
 * codes below are what the Sprinter community has been running on ScanGauges for years. They
 * are kept here as *data* rather than being hand-translated into constants so that the decode
 * is executable, and therefore testable: [decode] turns the fields into wire facts, and
 * `XGaugeCodeTest` pins every step of the arithmetic. Retyping `0x0009 / 0x0005 / 0xFFC6` into
 * a hand-computed `× 1.8 − 58` by eye is exactly the kind of transcription that produces a
 * plausible wrong number on a gauge.
 *
 * **Field formats** (ScanGauge X-Gauge, ISO 15765-4 / CAN vehicles). None of this is officially
 * specified by ScanGauge; the reading below is reconstructed from the field values themselves
 * and cross-checked against the canonical published X-Gauge for a *standard* PID, engine
 * coolant, whose correct answer is known independently from SAE J1979:
 *
 * ```
 *   coolant:     TXD 07DF0105   RXF 034105000000   RXD 1808   MTH 00010001FFD8
 *   trans temp:  TXD 07E12130   RXF 032200000000   RXD 1808   MTH 00090005FFC6
 * ```
 *
 * - **TXD** — transmit data: a 4-hex-digit CAN id (`07DF` = the functional broadcast address,
 *   `07E1` = physical address of the second ECU, the transmission controller) followed by the
 *   request bytes (`01 05` = mode 01 PID 05; `21 30` = KWP `readDataByLocalIdentifier`, local id
 *   `0x30`). The coolant code decoding to a textbook `0105` broadcast is what establishes this
 *   split.
 * - **RXF** — receive filter: the ISO-TP PCI byte of the expected single-frame reply followed by
 *   the leading response bytes, zero-padded to six bytes. Coolant: `03` (3 bytes follow) then
 *   `41 05` — the mode-01 positive response header — which again matches independently.
 *   See [XGaugeDecode.rxFilterPci] for what the trans-temp code's `03 22 …` does and does not
 *   tell us.
 * - **RXD** — receive data: start bit and bit length of the value inside that reply, as two hex
 *   bytes. `1808` = bit 24, 8 bits ⇒ frame byte 3. Counting from the PCI byte (frame byte 0),
 *   coolant's reply `03 41 05 A` puts `A` at byte 3 ✓ — and `A − 40` °C is the known-correct
 *   answer, so both the bit convention and the PCI-inclusive origin are confirmed by a channel
 *   whose truth is not in doubt.
 * - **MTH** — math: three 16-bit hex fields, multiplier / divisor / **signed** adder, applied as
 *   `display = raw × mul ÷ div + add`. Coolant: `0001 / 0001 / FFD8` = `raw × 1 ÷ 1 + (−40)`,
 *   i.e. exactly SAE's `A − 40` °C ✓✓ — the third independent confirmation, and the one that
 *   proves the adder is two's-complement signed.
 *
 * That coolant cross-check is the whole reason to trust the trans-temp decode: every field
 * convention used to read `07E12130 / 1808 / 00090005FFC6` is one that reproduces a known-correct
 * answer when applied to `07DF0105 / 1808 / 00010001FFD8`.
 *
 * @param name the ScanGauge `NAM` field / gauge label.
 * @param txd the `TXD` field, e.g. `"07E12130"`.
 * @param rxf the `RXF` field, e.g. `"032200000000"`.
 * @param rxd the `RXD` field, e.g. `"1808"`.
 * @param mth the `MTH` field, e.g. `"00090005FFC6"`.
 */
data class XGaugeCode(
    val name: String,
    val txd: String,
    val rxf: String,
    val rxd: String,
    val mth: String,
) {
    /**
     * Decodes the four fields into wire facts.
     *
     * @throws IllegalArgumentException if a field is not hex, is the wrong length, or describes
     *   something this decoder deliberately refuses to guess at (a value that is not a whole
     *   number of whole bytes, or one that would start inside the response header). Every code in
     *   [MercedesPidRegistry] is decoded at class-init, so a bad transcription fails loudly at
     *   startup rather than producing a quietly shifted byte.
     */
    fun decode(): XGaugeDecode {
        requireHex(txd, "TXD")
        requireHex(rxf, "RXF")
        requireHex(rxd, "RXD")
        requireHex(mth, "MTH")
        require(txd.length > CAN_ID_DIGITS) { "$name: TXD needs a CAN id plus at least one request byte, was '$txd'" }
        require(rxd.length == RXD_DIGITS) { "$name: RXD must be 4 hex digits (start bit, bit length), was '$rxd'" }
        require(mth.length == MTH_DIGITS) { "$name: MTH must be 12 hex digits (mul, div, add), was '$mth'" }
        require(rxf.length >= RXF_MIN_DIGITS) { "$name: RXF needs at least a PCI byte and one response byte" }

        val requestBytes = txd.substring(CAN_ID_DIGITS).uppercase()
        require(requestBytes.length % HEX_DIGITS_PER_BYTE == 0) {
            "$name: TXD request bytes must be whole bytes, was '$requestBytes'"
        }
        val startBit = rxd.substring(0, HEX_DIGITS_PER_BYTE).toInt(HEX_RADIX)
        val bitLength = rxd.substring(HEX_DIGITS_PER_BYTE).toInt(HEX_RADIX)
        require(startBit % BITS_PER_BYTE == 0 && bitLength % BITS_PER_BYTE == 0 && bitLength > 0) {
            "$name: RXD '$rxd' is a sub-byte bit field (start $startBit, length $bitLength); " +
                "this decoder only supports whole-byte values"
        }

        // Frame layout: [PCI][response header bytes …][value …]. RXD counts from the PCI byte.
        val responseHeaderBytes = requestBytes.length / HEX_DIGITS_PER_BYTE
        val frameByteIndex = startBit / BITS_PER_BYTE
        val dataByteIndex = frameByteIndex - PCI_BYTES - responseHeaderBytes
        require(dataByteIndex >= 0) {
            "$name: RXD start bit $startBit lands inside the response header, not in its data"
        }

        return XGaugeDecode(
            name = name,
            canId =
                txd
                    .substring(0, CAN_ID_DIGITS)
                    .uppercase()
                    .trimStart('0')
                    .ifEmpty { "0" },
            requestBytes = requestBytes,
            rxFilterPci = rxf.substring(0, HEX_DIGITS_PER_BYTE).toInt(HEX_RADIX),
            rxFilterBytes = rxf.uppercase(),
            dataByteIndex = dataByteIndex,
            dataByteCount = bitLength / BITS_PER_BYTE,
            multiplier = mth.substring(0, MTH_FIELD_DIGITS).toInt(HEX_RADIX),
            divisor = mth.substring(MTH_FIELD_DIGITS, 2 * MTH_FIELD_DIGITS).toInt(HEX_RADIX),
            adder = signed16(mth.substring(2 * MTH_FIELD_DIGITS)),
        )
    }

    private fun requireHex(
        field: String,
        label: String,
    ) {
        require(field.isNotEmpty() && field.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) {
            "$name: $label must be hex digits, was '$field'"
        }
    }

    private companion object {
        const val CAN_ID_DIGITS = 4
        const val RXD_DIGITS = 4
        const val MTH_DIGITS = 12
        const val MTH_FIELD_DIGITS = 4
        const val RXF_MIN_DIGITS = 4
        const val PCI_BYTES = 1
        const val SIGN_BIT = 0x8000
        const val MODULUS_16 = 0x10000

        /** Reads four hex digits as a two's-complement 16-bit integer: `FFC6` → −58. */
        fun signed16(hex: String): Int {
            val unsigned = hex.toInt(HEX_RADIX)
            return if (unsigned >= SIGN_BIT) unsigned - MODULUS_16 else unsigned
        }
    }
}

/**
 * The machine-readable result of decoding an [XGaugeCode]. See that class for how each field is
 * read off the ScanGauge fields and why.
 *
 * @param name the gauge label from the code.
 * @param canId the transmit header for `ATSH`, leading zeros stripped, e.g. `"7E1"`.
 * @param requestBytes the request payload to send, e.g. `"2130"`.
 * @param rxFilterPci the ISO-TP PCI byte from `RXF`: how many bytes the reply carries.
 * @param rxFilterBytes the whole `RXF` field, kept verbatim for documentation and diagnostics.
 * @param dataByteIndex index of the value's first byte **after** the positive-response header
 *   — i.e. the index into what [ResponseParser.dataBytesForHeader] hands back.
 * @param dataByteCount how many bytes the value occupies.
 * @param multiplier `MTH`'s first field.
 * @param divisor `MTH`'s second field.
 * @param adder `MTH`'s third field, two's-complement signed.
 */
data class XGaugeDecode(
    val name: String,
    val canId: String,
    val requestBytes: String,
    val rxFilterPci: Int,
    val rxFilterBytes: String,
    val dataByteIndex: Int,
    val dataByteCount: Int,
    val multiplier: Int,
    val divisor: Int,
    val adder: Int,
) {
    /** The request mode: the first byte of [requestBytes], e.g. `0x21`. */
    val requestMode: Int get() = requestBytes.substring(0, HEX_DIGITS_PER_BYTE).toInt(HEX_RADIX)

    /**
     * The header a positive response opens with: request mode `+ 0x40`, then the identifier bytes
     * echoed back unchanged. `21 30` → `61 30`; a UDS `22 0F 0C` would give `62 0F 0C`.
     */
    val responseHeader: String
        get() =
            hexByte(requestMode + POSITIVE_RESPONSE_OFFSET) + requestBytes.substring(HEX_DIGITS_PER_BYTE)

    /**
     * How many data bytes the parser must extract after the response header for
     * [dataByteIndex]/[dataByteCount] to be readable.
     */
    val requiredDataBytes: Int get() = dataByteIndex + dataByteCount

    /**
     * The **ScanGauge display value** for a raw reading: `raw × mul ÷ div + add`, in whatever unit
     * the code's author chose (for the Sprinter temperature codes, °F).
     *
     * This app does not display this number — it exists so tests can assert that the natural-unit
     * value the registry actually publishes converts back to exactly what a ScanGauge would show.
     * That equality is the evidence that the natural-unit rewrite did not quietly change the
     * channel. Multiplication happens before division, in `Double`, so no intermediate truncation
     * can creep in.
     */
    fun displayValue(raw: Int): Double {
        require(divisor != 0) { "$name: MTH divisor is zero" }
        return raw.toDouble() * multiplier / divisor + adder
    }

    /**
     * True when `MTH` is exactly the °C → °F affine map `× 9/5 + 32` applied to a Celsius raw
     * value carrying a constant offset — i.e. when `mul/div == 9/5`.
     *
     * When this holds, [celsiusOffset] recovers that offset and the channel can be republished in
     * °C, this module's natural temperature unit. When it does **not** hold, the code is not a
     * temperature conversion and must not be treated as one.
     */
    val isFahrenheitConversion: Boolean
        get() = multiplier == F_PER_C_NUMERATOR && divisor == F_PER_C_DENOMINATOR

    /**
     * The Celsius offset hidden in `MTH`'s adder, for a code where [isFahrenheitConversion] holds.
     *
     * The algebra, spelled out because this constant is the entire trans-temp hypothesis:
     * ```
     *   display   = raw × 9/5 + add                      (what MTH says)
     *   °F        = (°C) × 9/5 + 32                      (definition)
     *   ⇒ °C      = raw − k   where   9/5 × (raw − k) + 32 = raw × 9/5 + add
     *   ⇒ −9/5·k + 32 = add
     *   ⇒ k       = (32 − add) × div ÷ mul
     * ```
     * For the trans-temp code (`add = −58`): `k = (32 + 58) × 5 ÷ 9 = 90 × 5 ÷ 9 = 50`. The raw
     * byte is therefore °C with a **−50** offset — *not* the SAE −40 that standard temperature
     * PIDs use, which is exactly why `−58` appears where a naive reading expects `−40`. A code
     * over an SAE-style `A − 40` raw would have carried `add = −40` (`FFD8`), and the published
     * coolant X-Gauge does exactly that.
     */
    val celsiusOffset: Double
        get() {
            require(isFahrenheitConversion) { "$name: MTH $multiplier/$divisor is not a °C→°F conversion" }
            return (FAHRENHEIT_FREEZING - adder).toDouble() * divisor / multiplier
        }

    private companion object {
        const val F_PER_C_NUMERATOR = 9
        const val F_PER_C_DENOMINATOR = 5
        const val FAHRENHEIT_FREEZING = 32
    }
}

internal const val HEX_DIGITS_PER_BYTE = 2
internal const val HEX_RADIX = 16
internal const val BITS_PER_BYTE = 8
