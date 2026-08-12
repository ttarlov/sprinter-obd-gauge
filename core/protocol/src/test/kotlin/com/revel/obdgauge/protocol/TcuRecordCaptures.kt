package com.revel.obdgauge.protocol

/**
 * The `21 30` records captured from the van's 722.9 transmission controller on 2026-08-12, as
 * raw ELM327 text with CAN-ID headers on (`ATH1`).
 *
 * **These are captures, not predictions.** Everything else this module tests the manufacturer
 * path against is synthetic — a transcript written from what the decode says the ECU *should*
 * send. These four lines per record are what a real 722.9 actually put on the wire, transcribed
 * byte for byte from `issues/OBD-49.md` and `docs/hardware/session-2026-08-12.md`. Nothing here
 * may be "tidied": the padding byte, the odd-length lines, the header format and the spacing are
 * the properties under test.
 *
 * Line terminators are `\r`, which is what an ELM327 emits and what the tolerance rules in
 * [ResponseParser] are written around; [KwpRecordParserTest] covers the `\n` and prompt variants
 * separately rather than by editing these.
 *
 * ## Provenance, including the one gap
 *
 * The issue records [WARM_IDLE] in full. [POST_STALL] and [POST_DRIVE] are quoted there with
 * their unchanged leading frames elided as `…`; each is reconstructed here by taking its captured
 * `22`/`23` frames verbatim and restoring the elided frames from the surrounding record:
 *
 * | Record | first frame `10` | continuation `21` | `22` | `23` |
 * |---|---|---|---|---|
 * | [WARM_IDLE] | captured | captured | captured | captured |
 * | [POST_STALL] | reconstructed | reconstructed | captured | captured |
 * | [POST_DRIVE] | captured (`… 00 12 00 FF`) | reconstructed | captured | captured |
 *
 * The reconstructed `21` frame carries record bytes 4–10 only. **No assertion in this suite reads
 * those bytes** — they are uncatalogued state fields, explicitly out of scope in the issue — so
 * the reconstruction cannot prop up a passing test. Every asserted field (byte 11, byte 18, the
 * `data[1]` and byte-19 state fields the session tracked) comes from a captured frame.
 */
internal object TcuRecordCaptures {
    /**
     * Warm idle. Byte 11 `8E` = 92 °C, byte 18 `86` = 84 °C, `data[1]` = `13`, byte 19 = `18`.
     *
     * Fully captured, including the trailing `FF` — one byte of CAN padding past the declared
     * 26, which the parser must discard rather than count.
     */
    const val WARM_IDLE: String =
        "7E9 10 1A 61 30 00 13 00 00\r" +
            "7E9 21 00 00 00 08 04 00 DD\r" +
            "7E9 22 8E FF F3 FF F3 00 00\r" +
            "7E9 23 86 18 00 08 00 00 FF\r"

    /**
     * Immediately after a 90-second torque-converter stall. Byte 11 `8D` = 91 °C (coolant dipped
     * 1 °C), byte 18 still `86` = 84 °C, byte 19 `10`, and the 13/15 signed pairs moved
     * `FFF3 → FFF6` with converter load.
     */
    const val POST_STALL: String =
        "7E9 10 1A 61 30 00 13 00 00\r" +
            "7E9 21 00 00 00 08 04 00 DD\r" +
            "7E9 22 8D FF F6 FF F6 00 00\r" +
            "7E9 23 86 10 00 08 00 00 FF\r"

    /**
     * After a 15-minute drive. Byte 11 `93` = 97 °C (coolant up 6 °C over the session — the drive
     * is visible in the anchor), byte 18 *still* `86` = 84 °C, `data[1]` `13 → 12`, byte 19 `00`,
     * and the signed pairs at `FFFF`.
     *
     * This is the record that makes byte 18 unproven rather than proven: everything else in the
     * block moved, and it did not.
     */
    const val POST_DRIVE: String =
        "7E9 10 1A 61 30 00 12 00 FF\r" +
            "7E9 21 00 00 00 08 04 00 DD\r" +
            "7E9 22 93 FF FF FF FF 00 00\r" +
            "7E9 23 86 00 00 08 00 00 FF\r"

    /**
     * The falsified UDS hypothesis: `ATSH7E1` + `220543` → `7F 22 11`, serviceNotSupported.
     *
     * Captured with headers off, which is how it is quoted in the session notes.
     */
    const val UDS_22_NEGATIVE: String = "7F 22 11\r"

    /** Every captured record, for tests that must hold across all three. */
    val ALL_RECORDS: List<Pair<String, String>> =
        listOf(
            "warm idle" to WARM_IDLE,
            "post-stall" to POST_STALL,
            "post-drive" to POST_DRIVE,
        )
}
