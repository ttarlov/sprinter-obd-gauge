package com.revel.obdgauge.ble.store

/**
 * Persists the address of the last dongle that connected successfully, so the next connect can
 * skip scanning entirely (OBD-17's fast path — the difference between gauges live in two
 * seconds and gauges live in fifteen, every single time the van starts).
 *
 * Only ever written *after* a link reaches Ready: a device that scanned but failed to bridge is
 * not worth remembering.
 */
interface RememberedDeviceStore {
    /** The remembered address, or `null` if there is none (or the stored value was malformed). */
    suspend fun lastAddress(): String?

    /** Records [address] as the last known-good device. Invalid addresses are ignored. */
    suspend fun remember(address: String)

    /** Clears the remembered device, forcing the next connect to scan. */
    suspend fun forget()
}

/**
 * Validation for persisted BLE addresses. Pure, and worth having: a corrupted or
 * hand-edited preference must not reach `connectGatt`, which throws
 * `IllegalArgumentException` on a malformed address and would take the connect attempt out
 * with an untyped crash instead of a clean fall-back to scanning.
 */
object BluetoothAddress {
    private val PATTERN = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

    fun isValid(address: String?): Boolean = address != null && PATTERN.matches(address.uppercase())

    /** Normalizes to the uppercase form Android uses, or `null` if not a valid address. */
    fun normalizeOrNull(address: String?): String? = address?.uppercase()?.takeIf { PATTERN.matches(it) }
}
