package com.revel.obdgauge.ble

import com.revel.obdgauge.model.LinkError

/** Everything [ConnectPlanner] needs to know, gathered before any radio is touched. */
data class ConnectPreconditions(
    val bleSupported: Boolean,
    val bluetoothEnabled: Boolean,
    /**
     * Whether a scan can actually return results. Always true on API 31+; below that the system
     * silently returns zero scan results when Location **services** are off, even with
     * `ACCESS_FINE_LOCATION` granted.
     */
    val locationUsableForScan: Boolean,
    val missingPermissions: List<String>,
    val rememberedAddress: String?,
)

/** What a connect attempt should do first. */
sealed interface ConnectPlan {
    /** Remembered-device fast path: connect straight to [address], no scan. */
    data class Direct(
        val address: String,
    ) : ConnectPlan

    /** No usable remembered device — scan for one. */
    data object Scan : ConnectPlan

    /** Can't even start; [error] is what `LinkState.Error` will carry. */
    data class Abort(
        val error: LinkError,
    ) : ConnectPlan
}

/**
 * Turns device state into a connect plan. Pure, so every denial path is a one-line JVM test
 * instead of a device-in-hand experiment.
 *
 * Check order matters and is deliberate:
 * 1. **BLE support** — nothing else is meaningful without a radio.
 * 2. **Permissions** — before the adapter, because on Android 12+ reading adapter state and
 *    starting a scan both throw `SecurityException` without them. Reporting `PermissionDenied`
 *    is more actionable than `BluetoothOff` inferred from a call that was never allowed to run.
 * 3. **Adapter enabled** — a scan with the adapter off fails in ways that look like "no dongle".
 * 4. **Location usable for scanning** — below Android 12 the scan returns nothing at all when
 *    Location services are switched off, which would otherwise be misreported as
 *    `DeviceNotFound` and send the user hunting for a dongle fault that does not exist. A
 *    remembered device still gets the direct path: [ConnectPlan.Direct] never scans.
 * 5. Remembered address, if any → [ConnectPlan.Direct]; otherwise [ConnectPlan.Scan].
 */
object ConnectPlanner {
    /**
     * Message prefix for the location-services abort. `LinkError` is a frozen `:core:model`
     * contract, so this cannot be its own variant without an orchestrator decision; the constant
     * gives `:app` something stable to match on in the meantime.
     */
    const val LOCATION_OFF = "Location services must be on for Bluetooth scanning below Android 12"

    /**
     * Message prefix for the no-radio abort, for the same reason as [LOCATION_OFF]. OBD-23's
     * [ReconnectPolicy] matches on it: a phone with no BLE radio is the one abort that no amount
     * of retrying, and no action by the user, will ever change.
     */
    const val BLE_UNSUPPORTED = "Bluetooth LE is not available on this device"

    fun plan(preconditions: ConnectPreconditions): ConnectPlan =
        when {
            !preconditions.bleSupported -> ConnectPlan.Abort(LinkError.Unknown(BLE_UNSUPPORTED))

            preconditions.missingPermissions.isNotEmpty() -> ConnectPlan.Abort(LinkError.PermissionDenied)

            !preconditions.bluetoothEnabled -> ConnectPlan.Abort(LinkError.BluetoothOff)

            preconditions.rememberedAddress != null -> ConnectPlan.Direct(preconditions.rememberedAddress)

            !preconditions.locationUsableForScan -> ConnectPlan.Abort(LinkError.Unknown(LOCATION_OFF))

            else -> ConnectPlan.Scan
        }
}
