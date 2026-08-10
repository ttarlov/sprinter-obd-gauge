package com.revel.obdgauge.ble.permission

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Android-12 split-permission rule, pinned. The constant-equality test is not ceremony: the
 * policy hardcodes the permission strings to stay Android-free, and a typo there would fail as
 * "permission denied forever" on a device with everything granted.
 */
class BlePermissionPolicyTest {
    @Test
    fun `android 12 and later require the split bluetooth permissions`() {
        val required = BlePermissionPolicy.requiredPermissions(Build.VERSION_CODES.S)

        assertEquals(
            listOf(BlePermissionPolicy.BLUETOOTH_SCAN, BlePermissionPolicy.BLUETOOTH_CONNECT),
            required,
        )
    }

    @Test
    fun `later api levels keep the same requirement`() {
        assertEquals(
            BlePermissionPolicy.requiredPermissions(Build.VERSION_CODES.S),
            BlePermissionPolicy.requiredPermissions(Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
        )
    }

    @Test
    fun `below android 12 a scan needs fine location`() {
        val required = BlePermissionPolicy.requiredPermissions(Build.VERSION_CODES.R)

        assertEquals(listOf(BlePermissionPolicy.ACCESS_FINE_LOCATION), required)
    }

    @Test
    fun `missing permissions reports only the ungranted ones in declaration order`() {
        val missing =
            BlePermissionPolicy.missingPermissions(
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                granted = setOf(BlePermissionPolicy.BLUETOOTH_CONNECT),
            )

        assertEquals(listOf(BlePermissionPolicy.BLUETOOTH_SCAN), missing)
    }

    @Test
    fun `nothing is missing when everything is granted`() {
        val missing =
            BlePermissionPolicy.missingPermissions(
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                granted =
                    setOf(
                        BlePermissionPolicy.BLUETOOTH_SCAN,
                        BlePermissionPolicy.BLUETOOTH_CONNECT,
                    ),
            )

        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `permission name constants match the framework`() {
        assertEquals(Manifest.permission.BLUETOOTH_SCAN, BlePermissionPolicy.BLUETOOTH_SCAN)
        assertEquals(Manifest.permission.BLUETOOTH_CONNECT, BlePermissionPolicy.BLUETOOTH_CONNECT)
        assertEquals(Manifest.permission.ACCESS_FINE_LOCATION, BlePermissionPolicy.ACCESS_FINE_LOCATION)
    }
}
