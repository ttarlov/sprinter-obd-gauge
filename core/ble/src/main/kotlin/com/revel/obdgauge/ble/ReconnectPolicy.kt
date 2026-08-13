package com.revel.obdgauge.ble

import com.revel.obdgauge.ble.scan.ScanStateMachine
import com.revel.obdgauge.model.LinkError
import kotlin.random.Random
import kotlin.time.Duration

/** Whether trying the same thing again, unaided, could ever produce a different answer. */
enum class Recoverability {
    /**
     * The van can fix this by coming back to life: the dongle lost power with the ignition, the
     * link dropped out of range, the stack timed out, a scan swept an empty parking lot. Retry.
     */
    RECOVERABLE,

    /**
     * Retrying is not persistence here, it is a loop that never converges: a permission the app
     * must be granted before it may even look, Location services off, a device with no BLE radio
     * at all — or the user themselves saying stop.
     */
    TERMINAL,
}

/**
 * When to try again after the link goes down (OBD-23), and whether to try at all.
 *
 * Pure and stateless — the whole decision surface of the reconnect state machine is testable
 * without a radio, a clock or a coroutine. `BleObdLink` owns the state (armed, attempt count,
 * pending job); this object owns the arithmetic and the classification.
 *
 * ### The classification rule
 * **Retry unless a retry is provably futile.** Futile means the module would be asking the same
 * question of the same unchanged world: a permission it has not been granted, Location services
 * it cannot switch on, a radio the phone does not have. Everything else — the van coming back,
 * the adapter coming back, a stack that threw once — is worth another attempt, because the cost
 * of one is a `isEnabled()` read plus a scan the backoff has already spaced out, and the cost of
 * being wrong in the other direction is a gauge that stays dead for the rest of the drive.
 *
 * Round 1 had this as "anything only a human can fix is terminal", which sounded tidy and put
 * [LinkError.BluetoothOff] on the wrong side: the dominant cause of an off adapter is a stack
 * restart, not a human. [LinkError.PermissionDenied] stays terminal on the narrower ground that
 * the app cannot even look until it is granted, and `:app` owns that dialog and re-calls
 * `connect()` on the grant.
 *
 * The two aborts that ride on [LinkError.Unknown] with stable message prefixes are matched by
 * string, because `LinkError` is a frozen `:core:model` contract — see `MODULE.md`.
 *
 * Key-off is deliberately on the recoverable side and is the case that motivated the issue: on
 * some vans the OBD port dies with the ignition, so a fuel stop reads as
 * `Gatt(status)`/`Unknown("dongle disconnected")` on the way down and `DeviceNotFound` on every
 * attempt until the key comes back. That is a link waiting, not a link that failed.
 *
 * ### The backoff
 * Exponential from [BleConfig.reconnectInitialDelay], doubling, capped at
 * [BleConfig.reconnectMaxDelay], then jittered by ±[BleConfig.reconnectJitter]. The jitter is
 * not thundering-herd insurance — there is one dongle — it is desynchronisation from *its* own
 * cycle: a dongle that reboots on a fixed period and a phone that retries on a fixed period can
 * lock into a phase where every attempt lands in the dead window, and stay there.
 */
object ReconnectPolicy {
    /**
     * Ceiling on the doubling exponent, so a long-parked van cannot overflow the shift. The cap
     * in [delayFor] applies long before this bites; it exists so the arithmetic is total.
     */
    private const val MAX_SHIFT = 30

    /**
     * Message prefix for a retry that died on an exception rather than a typed failure (round-1
     * BLOCKER B1). Stable, for the same reason [ConnectPlanner.LOCATION_OFF] is: `LinkError` is a
     * frozen `:core:model` contract, so a new cause cannot have its own variant, and `:app` needs
     * *something* to match on. Classified recoverable by the [LinkError.Unknown] default below —
     * a stack that threw on one sweep routinely works on the next, and the attempt budget bounds
     * how long the module keeps believing that.
     */
    const val ABNORMAL_ATTEMPT = "connect attempt failed abnormally"

    fun classify(cause: LinkError): Recoverability =
        when (cause) {
            LinkError.PermissionDenied -> Recoverability.TERMINAL
            // Round-1 MINOR m4: this was terminal, and terminal was a landmine. The dominant
            // trigger for `BluetoothOff` is not a user reaching for the toggle — it is the
            // Bluetooth stack restarting, which reports the adapter off for a few seconds. The
            // first backoff is one second, so the module samples almost exactly that window: one
            // unlucky read used to disarm auto-reconnect permanently and silently, and the link
            // never came back even after the adapter did. Retrying costs one `isEnabled()` read
            // per backoff cycle and touches no radio, so the trade is not close.
            LinkError.BluetoothOff -> Recoverability.RECOVERABLE
            LinkError.DeviceNotFound, LinkError.Timeout -> Recoverability.RECOVERABLE
            is LinkError.Gatt -> Recoverability.RECOVERABLE
            is LinkError.Unknown -> classifyUnknown(cause.message)
        }

    /**
     * The wait before reconnect attempt [attempt] (1-based). [random] is a seam so the jitter is
     * assertable; production takes the default.
     */
    fun delayFor(
        attempt: Int,
        config: BleConfig,
        random: Random = Random.Default,
    ): Duration {
        require(attempt >= 1) { "reconnect attempts are 1-based, got $attempt" }
        val doublings = (attempt - 1).coerceAtMost(MAX_SHIFT)
        val capped = minOf(config.reconnectInitialDelay * (1 shl doublings), config.reconnectMaxDelay)
        val jitter = config.reconnectJitter.coerceIn(0.0, 1.0)
        if (jitter == 0.0) {
            return capped
        }
        // Symmetric around the schedule, then clamped: the ceiling is a ceiling, and a wait
        // must never round down to "immediately" and turn the backoff into a spin.
        val spread = (random.nextDouble() * 2.0 - 1.0) * jitter
        return (capped * (1.0 + spread)).coerceIn(capped * (1.0 - jitter), config.reconnectMaxDelay)
    }

    private fun classifyUnknown(message: String): Recoverability =
        when {
            message.startsWith(ConnectPlanner.LOCATION_OFF) -> Recoverability.TERMINAL
            message.startsWith(ConnectPlanner.BLE_UNSUPPORTED) -> Recoverability.TERMINAL
            // A spent scan budget is the one recoverable case that recovers by *waiting* rather
            // than by anything changing in the van: the rolling 30-second window drains on its
            // own, and the backoff is already longer than that by attempt 5.
            message.startsWith(ScanStateMachine.THROTTLED) -> Recoverability.RECOVERABLE
            // Everything else — `Unknown("dongle disconnected")` on a clean key-off included.
            else -> Recoverability.RECOVERABLE
        }
}
