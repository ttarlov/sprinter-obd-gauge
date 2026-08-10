package com.revel.obdgauge.ble.gatt

/**
 * Everything `GattSession` is allowed to do to a GATT connection, and nothing else.
 *
 * This interface is the seam that makes OBD-18 testable headlessly. `BluetoothGatt` is final,
 * its callbacks arrive on binder threads, and its behaviour is unmockable without a device — so
 * the real implementation ([AndroidGattTransport]) is kept to a literal adapter (call the
 * framework, translate the callback into a [GattEvent]) and every decision, every timeout and
 * every piece of reassembly lives above it in code a JVM test can drive.
 *
 * Implementations may be called from one thread only — the link dispatcher — and may deliver
 * events from any thread.
 */
interface GattTransport {
    /** Address of the peer, for logs and for remembering a successful device. */
    val address: String

    /**
     * Registers [listener] and starts connecting. Returns false if the framework refused to
     * even try (`connectGatt` returning null), in which case no event will ever arrive.
     */
    fun open(listener: GattEventListener): Boolean

    /** Kicks off service discovery; result arrives as [GattEvent.ServicesDiscovered]. */
    fun discoverServices(): Boolean

    /** Services found by the last successful discovery, in the order the framework returned them. */
    fun services(): List<GattServiceInfo>

    /**
     * Turns on notifications for [profile]'s notify characteristic: the local
     * `setCharacteristicNotification` flag *and* the CCCD descriptor write that actually tells
     * the peer to start sending. Result arrives as [GattEvent.NotificationsEnabled].
     */
    fun enableNotifications(profile: SerialProfile): Boolean

    /** Requests an ATT MTU; result arrives as [GattEvent.MtuChanged]. */
    fun requestMtu(mtu: Int): Boolean

    /** Writes one chunk to [profile]'s write characteristic. */
    fun write(
        profile: SerialProfile,
        payload: ByteArray,
    ): Boolean

    /** Disconnects and releases the GATT client. Must be idempotent. */
    fun close()
}

/** Creates a transport for a device address. Injected so tests can hand back a scripted double. */
fun interface GattTransportFactory {
    fun create(address: String): GattTransport
}

/** Receives [GattEvent]s from whatever thread the BLE stack chose. */
fun interface GattEventListener {
    fun onGattEvent(event: GattEvent)
}

/**
 * The GATT callback surface, flattened into data.
 *
 * Note what is *not* here: `onConnectionStateChange` with its `(status, newState)` pair.
 * The adapter collapses it, because "connected with a failure status" and "disconnected" are
 * the same thing to everything upstream, and conflating them at the edge is how a link ends up
 * believing it is connected when it is not.
 */
sealed interface GattEvent {
    /** The peer is connected. */
    data class Connected(
        val status: Int,
    ) : GattEvent

    /** The peer is gone — dropped, never arrived, or the adapter was turned off underneath us. */
    data class Disconnected(
        val status: Int,
    ) : GattEvent

    data class ServicesDiscovered(
        val status: Int,
    ) : GattEvent

    /** The CCCD write completed. */
    data class NotificationsEnabled(
        val status: Int,
    ) : GattEvent

    data class MtuChanged(
        val mtu: Int,
        val status: Int,
    ) : GattEvent

    /** One chunk of a command finished writing (the BLE flow-control signal to send the next). */
    data class WriteCompleted(
        val status: Int,
    ) : GattEvent

    /**
     * A notification arrived. Not a `data class` on purpose: `ByteArray` equality is identity,
     * which would make generated `equals`/`hashCode` quietly wrong.
     */
    class DataReceived(
        val bytes: ByteArray,
    ) : GattEvent
}
