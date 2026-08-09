package com.revel.obdgauge.model

import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Raw byte/string pipe to the OBD dongle. This is a **frozen Phase-0 contract** (see
 * `docs/01-build-plan.md` §0.2 and `DECISIONS.md`).
 *
 * `:core:ble` provides the real implementation over BLE/GATT; `:core:protocol` is the only
 * consumer, building typed readings on top via [PidDefinition]/[ObdRequest]. `:core:testing`
 * provides `FakeObdLink` (transcript replay) so the protocol layer never needs hardware to
 * be tested.
 *
 * The ELM327 dongle is half-duplex: exactly one command may be in flight at a time.
 * Implementations MUST enforce single-flight internally (e.g. a `Mutex` around [sendRaw]) —
 * callers are not required to serialize their own calls.
 */
interface ObdLink {
    /** Current connection lifecycle state. See [LinkState]. */
    val state: StateFlow<LinkState>

    /**
     * Initiate connection: scan (if needed), connect, and run the ELM327 init sequence.
     * Suspends until [state] reaches [LinkState.Ready] or [LinkState.Error]; does not throw
     * for expected failure modes — those surface through [state] instead.
     */
    suspend fun connect()

    /** Tear down the connection and return [state] to [LinkState.Disconnected]. */
    suspend fun disconnect()

    /**
     * Send one raw command and suspend until the full `>`-terminated response is assembled,
     * or [timeout] elapses.
     *
     * Half-duplex: implementations must enforce single-flight, so concurrent callers are
     * serialized rather than interleaved. Fragmented BLE notifications are reassembled by
     * the implementation — callers always see a complete response.
     *
     * @param command the raw AT/OBD command, without trailing `\r` (implementations append it).
     * @param timeout how long to wait for the terminating `>` before giving up.
     * @return the raw response text, `>` terminator stripped.
     * @throws Exception (implementation-defined) on timeout or disconnect mid-command; callers
     *   in `:core:protocol` are expected to catch and translate into a typed parse/skip outcome.
     */
    suspend fun sendRaw(
        command: String,
        timeout: Duration = 2.seconds,
    ): String
}
