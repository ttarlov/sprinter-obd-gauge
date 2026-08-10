package com.revel.obdgauge.ble.permission

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The three yes/no questions that have to be answered before a scan is worth starting:
 * does this device do BLE, is the adapter on, and are the runtime permissions granted.
 *
 * An interface so `BleObdLink`'s connect logic is testable without an Android radio;
 * [AndroidBleEnvironment] is the only implementation that touches the framework.
 */
interface BleEnvironment {
    /** False on a device with no BLE radio — nothing else in this module can work. */
    fun isBleSupported(): Boolean

    /** False when the Bluetooth adapter is off (airplane mode, user toggle). */
    fun isBluetoothEnabled(): Boolean

    /**
     * Whether a BLE scan can return results at all. Always true on API 31+, where
     * `neverForLocation` decouples scanning from location. Below that, the system returns an
     * empty scan — no error, no callback, nothing — when Location **services** are off, even
     * with `ACCESS_FINE_LOCATION` granted.
     */
    fun isLocationUsableForScan(): Boolean

    /** Runtime permissions still needed, empty when everything is granted. */
    fun missingPermissions(): List<String>
}

/**
 * Reads the real device state. Deliberately thin: every decision made from it lives elsewhere.
 *
 * The adapter reads are wrapped: on API 31+ they carry `@RequiresPermission(BLUETOOTH_CONNECT)`,
 * and a permission revoked between the check and the call would otherwise throw
 * `SecurityException` out of `connect()`. "Can't tell" is reported as "no".
 */
@Singleton
class AndroidBleEnvironment
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : BleEnvironment {
        private val bluetoothManager: BluetoothManager?
            get() = context.getSystemService(BluetoothManager::class.java)

        override fun isBleSupported(): Boolean =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) &&
                runCatching { bluetoothManager?.adapter != null }.getOrDefault(false)

        override fun isBluetoothEnabled(): Boolean =
            runCatching { bluetoothManager?.adapter?.isEnabled == true }.getOrDefault(false)

        override fun isLocationUsableForScan(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ||
                runCatching { locationServicesOn() }.getOrDefault(true)

        @Suppress("DEPRECATION")
        private fun locationServicesOn(): Boolean {
            val manager = context.getSystemService(LocationManager::class.java) ?: return true
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.isLocationEnabled
            } else {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        }

        override fun missingPermissions(): List<String> =
            BlePermissionPolicy
                .requiredPermissions(Build.VERSION.SDK_INT)
                .filterNot { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
