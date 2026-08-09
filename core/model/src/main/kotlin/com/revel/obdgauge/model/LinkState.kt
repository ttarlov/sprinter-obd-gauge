package com.revel.obdgauge.model

/**
 * Lifecycle state of the connection to the OBD dongle, as observed by [ObdLink] consumers.
 *
 * This is a **frozen Phase-0 contract** (see `docs/01-build-plan.md` §0.2 and
 * `DECISIONS.md`). Changing it requires an orchestrator decision logged in `DECISIONS.md`
 * before the change lands.
 */
sealed interface LinkState {
    /** No connection attempt in progress; either never started, or [ObdLink.disconnect] was called. */
    data object Disconnected : LinkState

    /** A BLE scan for the dongle is in progress. */
    data object Scanning : LinkState

    /** A device was found and GATT connection/service-discovery/init is in progress. */
    data object Connecting : LinkState

    /** Connected, initialized, and able to accept [ObdLink.sendRaw] calls. */
    data object Ready : LinkState

    /** Connection failed or dropped. [cause] identifies why, for UI messaging and retry logic. */
    data class Error(
        val cause: LinkError,
    ) : LinkState
}

/**
 * Typed reasons a [LinkState.Error] occurred. Deliberately not a plain `enum class`: [Gatt]
 * and [Unknown] carry payloads that vary per occurrence.
 */
sealed interface LinkError {
    /** Required runtime Bluetooth permission (`BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`) was denied. */
    data object PermissionDenied : LinkError

    /** The device's Bluetooth adapter is off. */
    data object BluetoothOff : LinkError

    /** A scan completed without finding a matching dongle. */
    data object DeviceNotFound : LinkError

    /** The Android BLE/GATT stack reported a failure. [code] is the raw `BluetoothGatt` status. */
    data class Gatt(
        val code: Int,
    ) : LinkError

    /** An operation (connect, or [ObdLink.sendRaw]) exceeded its allotted time. */
    data object Timeout : LinkError

    /** Any failure not covered above. [message] carries diagnostic detail for logs/debug UI. */
    data class Unknown(
        val message: String,
    ) : LinkError
}
