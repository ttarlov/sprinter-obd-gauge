package com.revel.obdgauge.ble.permission

import android.os.Build

/**
 * Which runtime permissions a BLE scan-and-connect needs, per API level.
 *
 * Pure: [requiredPermissions] and [missingPermissions] take an SDK int and a set of granted
 * permission names, so the whole Android-12 split-permission rule is testable on the JVM.
 *
 * This module never *requests* anything — it has no UI and no Activity. It reports what is
 * missing (via [missingPermissions] and `BleObdLink.missingPermissions()`), and a connect
 * attempt with anything missing parks in `LinkState.Error(LinkError.PermissionDenied)` rather
 * than crashing with a `SecurityException` or hanging on a scan that will never return a
 * result. Prompting the user is `:app`'s job.
 */
object BlePermissionPolicy {
    /**
     * Permission name constants, spelled out rather than referenced through `android.Manifest`
     * so this file stays free of Android imports beyond [Build.VERSION_CODES].
     * `BlePermissionPolicyTest` pins each one against the framework constant.
     */
    const val BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
    const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
    const val ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"

    /**
     * Runtime permissions required on [sdkInt].
     *
     * API 31+: the split Bluetooth permissions. `BLUETOOTH_SCAN` is declared `neverForLocation`
     * in this module's manifest, which is what removes the location requirement.
     * API 26..30: legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` are install-time and never appear here,
     * but a BLE scan genuinely does not return results without `ACCESS_FINE_LOCATION`.
     */
    fun requiredPermissions(sdkInt: Int): List<String> =
        if (sdkInt >= Build.VERSION_CODES.S) {
            listOf(BLUETOOTH_SCAN, BLUETOOTH_CONNECT)
        } else {
            listOf(ACCESS_FINE_LOCATION)
        }

    /** The subset of [requiredPermissions] not present in [granted], in declaration order. */
    fun missingPermissions(
        sdkInt: Int,
        granted: Set<String>,
    ): List<String> = requiredPermissions(sdkInt).filterNot(granted::contains)
}
