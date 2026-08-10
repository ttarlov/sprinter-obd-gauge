package com.revel.obdgauge.ble.gatt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.revel.obdgauge.ble.BleLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject

/**
 * The only class in this module that talks to `BluetoothGatt`, and it does nothing else.
 *
 * Every method is "call the framework, return whether it was accepted"; every callback is
 * "translate to a [GattEvent], hand it to the listener". No waiting, no state machine, no
 * decisions — those live in [GattSession], where they are testable. If a bug ever turns out to
 * be in this file, it is a translation bug, and the class is small enough to read in one go.
 *
 * Permissions are checked by `BleObdLink` (through `BleEnvironment`) before a transport is ever
 * created, which is why `MissingPermission` is suppressed here rather than re-checked per call.
 */
@SuppressLint("MissingPermission")
class AndroidGattTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val logger: BleLogger,
) : GattTransport {
    override val address: String get() = device.address

    @Volatile private var gatt: BluetoothGatt? = null

    @Volatile private var listener: GattEventListener? = null

    private val callback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                val connected = status == GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED
                emit(if (connected) GattEvent.Connected(status) else GattEvent.Disconnected(status))
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int,
            ) {
                emit(GattEvent.ServicesDiscovered(status))
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                if (descriptor.uuid == CCCD_UUID) {
                    emit(GattEvent.NotificationsEnabled(status))
                }
            }

            override fun onMtuChanged(
                gatt: BluetoothGatt,
                mtu: Int,
                status: Int,
            ) {
                emit(GattEvent.MtuChanged(mtu, status))
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                emit(GattEvent.WriteCompleted(status))
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                emit(GattEvent.DataReceived(value))
            }

            @Deprecated("Framework calls this below API 33; the API 33+ overload carries the value.")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                emit(GattEvent.DataReceived(characteristic.value ?: ByteArray(0)))
            }
        }

    override fun open(listener: GattEventListener): Boolean {
        this.listener = listener
        val connected = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        gatt = connected
        return connected != null
    }

    override fun discoverServices(): Boolean = gatt?.discoverServices() == true

    override fun services(): List<GattServiceInfo> =
        gatt?.services.orEmpty().map { service ->
            GattServiceInfo(
                uuid = service.uuid,
                characteristics =
                    service.characteristics.map { characteristic ->
                        GattCharacteristicInfo(characteristic.uuid, characteristic.properties)
                    },
            )
        }

    /**
     * Local notification flag plus the CCCD write. Both are required: the first tells the
     * Android stack to surface the notifications, the second tells the dongle to send them.
     * Skipping the CCCD write is the classic "connects fine, never receives anything" bug.
     */
    override fun enableNotifications(profile: SerialProfile): Boolean {
        val activeGatt = gatt
        val characteristic = characteristic(profile.serviceUuid, profile.notifyUuid)
        val descriptor = characteristic?.getDescriptor(CCCD_UUID)
        if (activeGatt == null || characteristic == null || descriptor == null) {
            logger.log("cannot enable notifications on $address: CCCD not present")
            return false
        }
        val value =
            if (profile.notifyKind == NotifyKind.INDICATE) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
        return activeGatt.setCharacteristicNotification(characteristic, true) &&
            writeDescriptorCompat(activeGatt, descriptor, value, logger)
    }

    override fun requestMtu(mtu: Int): Boolean = gatt?.requestMtu(mtu) == true

    override fun write(
        profile: SerialProfile,
        payload: ByteArray,
    ): Boolean {
        val activeGatt = gatt
        val characteristic = characteristic(profile.serviceUuid, profile.writeUuid)
        val writeType =
            if (profile.writeType == GattWriteType.NO_RESPONSE) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
        return if (activeGatt == null || characteristic == null) {
            false
        } else {
            writeCharacteristicCompat(activeGatt, characteristic, payload, writeType, logger)
        }
    }

    override fun close() {
        val activeGatt = gatt ?: return
        gatt = null
        listener = null
        runCatching {
            activeGatt.disconnect()
            activeGatt.close()
        }.onFailure { failure -> logger.log("error closing GATT for $address: $failure") }
    }

    private fun emit(event: GattEvent) {
        listener?.onGattEvent(event)
    }

    private fun characteristic(
        serviceUuid: UUID,
        characteristicUuid: UUID,
    ): BluetoothGattCharacteristic? = gatt?.getService(serviceUuid)?.getCharacteristic(characteristicUuid)

    private companion object {
        /** Client Characteristic Configuration Descriptor — the notification on/off switch. */
        val CCCD_UUID: UUID = shortUuid("2902")
    }
}

/**
 * API 33 replaced the "stash the value on the object, then write it" descriptor/characteristic
 * calls with ones that take the value directly — the old pair is racy when two writes overlap.
 * Both paths are needed at minSdk 26.
 *
 * The API-33 calls return a **`BluetoothStatusCodes`** value, not a GATT status. The two happen
 * to agree that 0 means success and nothing else: `ERROR_GATT_WRITE_REQUEST_BUSY`,
 * `ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION` and `ERROR_DEVICE_NOT_BONDED` are three very
 * different problems at a bring-up, so the code is logged rather than collapsed into `false`.
 */
@Suppress("DEPRECATION")
private fun writeDescriptorCompat(
    activeGatt: BluetoothGatt,
    descriptor: BluetoothGattDescriptor,
    value: ByteArray,
    logger: BleLogger,
): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        activeGatt.writeDescriptor(descriptor, value).acceptedWrite("writeDescriptor", logger)
    } else {
        descriptor.value = value
        activeGatt.writeDescriptor(descriptor)
    }

@Suppress("DEPRECATION")
private fun writeCharacteristicCompat(
    activeGatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    payload: ByteArray,
    writeType: Int,
    logger: BleLogger,
): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        activeGatt
            .writeCharacteristic(characteristic, payload, writeType)
            .acceptedWrite("writeCharacteristic", logger)
    } else {
        characteristic.writeType = writeType
        characteristic.value = payload
        activeGatt.writeCharacteristic(characteristic)
    }

/** Decodes an API-33 `BluetoothStatusCodes` return value, logging anything but success. */
private fun Int.acceptedWrite(
    operation: String,
    logger: BleLogger,
): Boolean =
    if (this == BluetoothStatusCodes.SUCCESS) {
        true
    } else {
        logger.log("$operation refused, BluetoothStatusCodes=$this")
        false
    }

/** Creates [AndroidGattTransport]s from device addresses via the system Bluetooth adapter. */
class AndroidGattTransportFactory
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val logger: BleLogger,
    ) : GattTransportFactory {
        override fun create(address: String): GattTransport {
            val manager =
                requireNotNull(context.getSystemService(BluetoothManager::class.java)) {
                    "BluetoothManager unavailable"
                }
            val adapter = requireNotNull(manager.adapter) { "Bluetooth adapter unavailable" }
            return AndroidGattTransport(context, adapter.getRemoteDevice(address), logger)
        }
    }
