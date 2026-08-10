package com.revel.obdgauge.ble.gatt

import java.util.UUID

/**
 * Property bits of a GATT characteristic. The values mirror `BluetoothGattCharacteristic`'s
 * constants exactly, so the Android adapter passes the framework bitmask straight through and
 * every decision made from it stays testable on the JVM without an Android class in sight.
 */
object GattProperty {
    const val READ = 0x02
    const val WRITE_NO_RESPONSE = 0x04
    const val WRITE = 0x08
    const val NOTIFY = 0x10
    const val INDICATE = 0x20
}

/** GATT status code for success (`BluetoothGatt.GATT_SUCCESS`). */
const val GATT_SUCCESS = 0

/**
 * How a command is written to the serial characteristic.
 *
 * ELM327 BLE clones are not consistent here: some reject `WRITE_TYPE_DEFAULT` outright (the
 * write returns a failure status and every command silently vanishes), others expose only
 * `WRITE`. The choice is therefore made per characteristic from its advertised properties,
 * never hardcoded — [NO_RESPONSE] when the characteristic supports it, [DEFAULT] otherwise.
 */
enum class GattWriteType {
    /** `BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE` (0x01). */
    NO_RESPONSE,

    /** `BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT` (0x02). */
    DEFAULT,
}

/** How the dongle pushes response bytes back at us. */
enum class NotifyKind {
    NOTIFY,
    INDICATE,
}

/** A discovered characteristic, reduced to what profile selection actually needs. */
data class GattCharacteristicInfo(
    val uuid: UUID,
    val properties: Int,
) {
    val isNotifiable: Boolean
        get() = hasAny(GattProperty.NOTIFY or GattProperty.INDICATE)

    val isWritable: Boolean
        get() = hasAny(GattProperty.WRITE or GattProperty.WRITE_NO_RESPONSE)

    /** [GattWriteType.NO_RESPONSE] when advertised — the safest default for ELM327 clones. */
    val preferredWriteType: GattWriteType
        get() =
            if (hasAny(GattProperty.WRITE_NO_RESPONSE)) {
                GattWriteType.NO_RESPONSE
            } else {
                GattWriteType.DEFAULT
            }

    val notifyKind: NotifyKind
        get() = if (hasAny(GattProperty.NOTIFY)) NotifyKind.NOTIFY else NotifyKind.INDICATE

    private fun hasAny(mask: Int): Boolean = properties and mask != 0
}

/** A discovered service and its characteristics, in discovery order. */
data class GattServiceInfo(
    val uuid: UUID,
    val characteristics: List<GattCharacteristicInfo>,
)

/** Where a [SerialProfile] came from — logged on every connect, and asserted in tests. */
sealed interface ProfileSource {
    /** Matched a known dongle family from [SerialProfileProbe.CANDIDATES]. */
    data class Candidate(
        val name: String,
    ) : ProfileSource

    /** No candidate matched; picked the first service with a writable + notifiable pair. */
    data object Fallback : ProfileSource
}

/**
 * The service/characteristic triple that makes a dongle behave like a serial port: write
 * commands to [writeUuid], receive response chunks as notifications from [notifyUuid].
 * The two characteristic UUIDs are frequently the same one (the HM-10 family).
 */
data class SerialProfile(
    val serviceUuid: UUID,
    val notifyUuid: UUID,
    val writeUuid: UUID,
    val writeType: GattWriteType,
    val notifyKind: NotifyKind,
    val source: ProfileSource,
) {
    /** One-line description for the connect log — the first thing to read after a bring-up. */
    fun describe(): String =
        "service=$serviceUuid notify=$notifyUuid($notifyKind) write=$writeUuid($writeType) via=$source"
}

/** Expands a 16-bit GATT short UUID into its full 128-bit form using the Bluetooth base UUID. */
fun shortUuid(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805F9B34FB")
