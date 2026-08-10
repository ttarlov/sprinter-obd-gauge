package com.revel.obdgauge.ble

import com.revel.obdgauge.ble.scan.ScanPass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Timeouts and tunables for the BLE link. Every wait in this module has one, because the BLE
 * stack's failure mode of choice is silence: a `connectGatt` to a dongle that has lost power
 * simply never calls back.
 *
 * Defaults are chosen for the real use case — a van dongle that is either right there or not
 * there at all — and can be overridden wholesale in tests.
 */
data class BleConfig(
    /** Hardware-filtered sweep (candidate service UUIDs only). */
    val filteredScanTimeout: Duration = DEFAULT_FILTERED_SCAN_SECONDS.seconds,
    /** Unfiltered follow-up sweep, only run when the filtered one finds nothing. */
    val broadScanTimeout: Duration = DEFAULT_BROAD_SCAN_SECONDS.seconds,
    /** `connectGatt` → `STATE_CONNECTED`. */
    val connectTimeout: Duration = DEFAULT_CONNECT_SECONDS.seconds,
    /** `discoverServices` → `onServicesDiscovered`. */
    val discoverTimeout: Duration = DEFAULT_DISCOVER_SECONDS.seconds,
    /** CCCD descriptor write → `onDescriptorWrite`. */
    val descriptorTimeout: Duration = DEFAULT_DESCRIPTOR_SECONDS.seconds,
    /** `requestMtu` → `onMtuChanged`. Short: a silent stack here is tolerated, not fatal. */
    val mtuTimeout: Duration = DEFAULT_MTU_MILLIS.milliseconds,
    /** One characteristic write → `onCharacteristicWrite`. */
    val writeTimeout: Duration = DEFAULT_WRITE_MILLIS.milliseconds,
    /**
     * How long the next command waits for a still-owed response/ack before presuming it lost.
     * Bounds the debt bookkeeping: a debt that outlives this window is cleared, so one
     * truncated response or dongle reset costs one quiet window — never the link.
     */
    val debtQuietWindow: Duration = DEFAULT_DEBT_QUIET_MILLIS.milliseconds,
) {
    /**
     * Pass 1 is filtered and cheap; pass 2 sweeps unfiltered for dongles that advertise a name
     * and no service UUID. See [ScanPass].
     */
    fun scanPasses(): List<ScanPass> =
        listOf(
            ScanPass(filterByServiceUuid = true, timeout = filteredScanTimeout),
            ScanPass(filterByServiceUuid = false, timeout = broadScanTimeout),
        )

    companion object {
        /**
         * MTU to request once connected. 512 is the ATT maximum; every byte of it shortens a
         * multi-frame response. Rejection is expected and harmless — the link falls back to the
         * 23-byte default, which is what most ELM327 clones negotiate anyway.
         */
        const val REQUESTED_MTU = 512

        /** ATT protocol overhead: 3 bytes of opcode + handle per write. */
        const val ATT_HEADER_BYTES = 3

        /** The MTU every BLE connection starts at, before negotiation. */
        const val DEFAULT_MTU = 23

        private const val DEFAULT_FILTERED_SCAN_SECONDS = 8
        private const val DEFAULT_BROAD_SCAN_SECONDS = 8
        private const val DEFAULT_CONNECT_SECONDS = 15
        private const val DEFAULT_DISCOVER_SECONDS = 10
        private const val DEFAULT_DESCRIPTOR_SECONDS = 5
        private const val DEFAULT_MTU_MILLIS = 2500
        private const val DEFAULT_WRITE_MILLIS = 2000
        private const val DEFAULT_DEBT_QUIET_MILLIS = 300
    }
}
