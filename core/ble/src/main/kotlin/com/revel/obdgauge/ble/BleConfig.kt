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
    /** First wait after a recoverable drop, before the backoff starts doubling (OBD-23). */
    val reconnectInitialDelay: Duration = DEFAULT_RECONNECT_INITIAL_SECONDS.seconds,
    /**
     * Ceiling on the backoff. A minute between attempts is cheap enough to leave running while
     * the van is parked and short enough that a key-on is noticed within one cycle.
     */
    val reconnectMaxDelay: Duration = DEFAULT_RECONNECT_MAX_SECONDS.seconds,
    /**
     * Proportional jitter applied to each computed wait, ±this fraction. Not thundering-herd
     * insurance — there is one dongle — but desynchronisation from *its* cycle: a dongle that
     * reboots on a fixed period and a phone that retries on a fixed period can lock into a phase
     * where every attempt lands in the dead window and stays there.
     */
    val reconnectJitter: Double = DEFAULT_RECONNECT_JITTER,
    /**
     * Consecutive failed attempts before auto-reconnect gives up and parks in
     * `LinkState.Error`, waiting to be asked again.
     *
     * At the defaults that is roughly twelve hours of trying — long enough to survive a
     * trailhead, a ferry, or a night parked up, which is the whole point of key-off recovery.
     * It is bounded rather than infinite for two reasons: a link that is never coming back
     * (dongle left at home) should stop touching the radio eventually, and an unbounded
     * self-rescheduling delay makes a virtual clock non-terminating, which would quietly turn
     * `advanceUntilIdle()` into a hang in any future test that armed one.
     */
    val reconnectMaxAttempts: Int = DEFAULT_RECONNECT_MAX_ATTEMPTS,
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

        private const val DEFAULT_RECONNECT_INITIAL_SECONDS = 1
        private const val DEFAULT_RECONNECT_MAX_SECONDS = 60
        private const val DEFAULT_RECONNECT_JITTER = 0.25

        /** ~12 h at the 60 s ceiling: 1+2+4+…+32 s of ramp, then 714 attempts a minute apart. */
        private const val DEFAULT_RECONNECT_MAX_ATTEMPTS = 720
    }
}
