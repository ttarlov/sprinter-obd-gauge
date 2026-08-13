package com.revel.obdgauge.app.e2e

import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.ObdLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration

/**
 * The 2026-08-12 van session, replayed as an [ObdLink] — the fixture the OBD-25 end-to-end test
 * runs the whole real chain over.
 *
 * **These are captures, not predictions.** Every response below is transcribed from
 * `docs/hardware/session-2026-08-12.md` (Veepeak + 2018 Sprinter NCV3 OM642, engine running at
 * warm idle, ~5 800 ft) — the same discipline `:core:protocol`'s `TcuRecordCaptures` follows, and
 * for the same reason: the point of this test is that real bytes off a real ECU produce the right
 * numbers on the gauge, which a synthetic transcript cannot establish. Nothing here may be
 * "tidied". In particular:
 *
 * - **`0105` answers on three lines.** Every functional (`7DF`) request on this van gets one
 *   reply per ECU (`7E8` engine, `7E9` TCU, `7EC` third powertrain module) and the session found
 *   all three agreeing on `86`. That is the shape Sprint 2a's per-line-first framing was built
 *   for, and it is the shape the parser must survive here.
 * - **`010B` and `010F` answer `NO DATA`.** MAP and IAT are not implemented on this vehicle —
 *   the `0100` bitmap agrees — which is what takes computed boost off the board and is asserted
 *   as a typed unavailability rather than a zero.
 * - **`010C` is scripted as the session's two quoted endpoints, `0B54` then `0B64`.** The doc
 *   records a range (725–729 rpm at idle), not a single sample; the test asserts against that
 *   range rather than inventing a midpoint that no capture contains.
 * - **`0104` likewise carries both quoted samples, `8E` and `90`.**
 *
 * A `:core:testing` `FakeObdLink` would have been the natural vehicle for this, but that module
 * is `demoImplementation`-scoped by `:app`'s HARD CONSTRAINT and must not reach a `prod`
 * classpath — so this is a deliberately small local replay with the same command-keyed,
 * queue-consuming semantics (each call takes the next scripted response; the last one repeats
 * forever, which is what steady-state polling needs).
 *
 * Unlike `FakeObdLink` this does **not** enforce half-duplex: `RealVehicleDataSource`'s
 * single-flight sequencing is already pinned by that module's own suite, and duplicating the
 * guard here would only add a way for this test to fail for a reason it is not about.
 */
class ScriptedVanLink(
    script: Map<String, List<String>> = VAN_2026_08_12,
) : ObdLink {
    private val queues: Map<String, ArrayDeque<String>> =
        script.entries.associate { (command, responses) -> command.uppercase() to ArrayDeque(responses) }

    private val recorded = mutableListOf<String>()

    /** Every command that reached the wire, in order — so "never sent" is assertable. */
    val commands: List<String> get() = recorded.toList()

    private val mutableState = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val state: StateFlow<LinkState> = mutableState.asStateFlow()

    override suspend fun connect() {
        mutableState.value = LinkState.Ready
    }

    override suspend fun disconnect() {
        mutableState.value = LinkState.Disconnected
    }

    override suspend fun sendRaw(
        command: String,
        timeout: Duration,
    ): String {
        recorded += command
        val queue = queues[command.uppercase()] ?: return UNKNOWN_COMMAND
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }

    companion object {
        /** ELM327's own reply to a command it does not know. */
        const val UNKNOWN_COMMAND = "?"

        /**
         * The session transcript. Response lines are `\r`-joined, prompt already stripped —
         * the shape [ObdLink.sendRaw] returns.
         */
        val VAN_2026_08_12: Map<String, List<String>> =
            mapOf(
                // ---- init: ATZ answered "ELM327 v2.2", closing OBD-19's hardware checklist ----
                "ATZ" to listOf("ATZ\rELM327 v2.2"),
                "ATE0" to listOf("ATE0\rOK"),
                "ATL0" to listOf("OK"),
                "ATS0" to listOf("OK"),
                "ATSP0" to listOf("OK"),
                // The engine ECU's bitmap, the richest of the three the van returns. Only 7E8's
                // was recorded, so only 7E8's is here — the other two modules' bitmaps are not
                // invented.
                "0100" to listOf("SEARCHING...\r41 00 98 18 A0 13"),
                // ---- standard PIDs, warm idle ----
                "0105" to listOf("41 05 86\r41 05 86\r41 05 86"), // 94 °C, all three ECUs agree
                "010C" to listOf("41 0C 0B 54", "41 0C 0B 64"), // 725 → 729 rpm
                "0104" to listOf("41 04 8E", "41 04 90"), // ~56 % load (idle + AC, altitude)
                "0111" to listOf("41 11 D3"), // 83 % — diesel intake flap, NOT driver throttle
                "0133" to listOf("41 33 52", "41 33 52"), // 82 kPa ≈ 5 800 ft
                "010B" to listOf("NO DATA"), // MAP unsupported → boost has no inputs
                "010F" to listOf("NO DATA"), // IAT unsupported
                // ---- the KWP `21 30` TCU record, framed and restored (OBD-55) ----
                // The trans-temp field was IDENTIFIED on the 2026-08-13 drive test
                // (docs/hardware/session-3-2026-08-13-transtemp.md): record byte 1, °C = 63 − raw.
                // This replays that session's post-drive record so the whole prod chain shows a
                // real trans-temp value — the crown-jewel decode going live.
                "ATSH7E1" to listOf("OK"),
                "ATCRA7E9" to listOf("OK"),
                "ATCRA" to listOf("OK"),
                "ATSH7DF" to listOf("OK"),
                "2130" to listOf(TRANS_TEMP_RECORD_POST_DRIVE),
            )

        /**
         * The 2026-08-13 post-drive `21 30` record, headers-on, byte for byte: record byte 1
         * `0x12` → **45 °C** (63 − 18), byte 11 `0x91` → 95 °C TCU-side coolant. Four CAN frames,
         * one trailing `FF` of padding past the declared 26.
         */
        const val TRANS_TEMP_RECORD_POST_DRIVE: String =
            "7E9 10 1A 61 30 00 12 00 FF\r" +
                "7E9 21 00 00 00 08 04 00 DD\r" +
                "7E9 22 91 00 00 00 00 00 00\r" +
                "7E9 23 86 10 00 08 00 00 FF"
    }
}
