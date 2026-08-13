package com.revel.obdgauge.ble.testing

import com.revel.obdgauge.ble.BleLogger
import com.revel.obdgauge.ble.gatt.GATT_SUCCESS
import com.revel.obdgauge.ble.gatt.GattCharacteristicInfo
import com.revel.obdgauge.ble.gatt.GattEvent
import com.revel.obdgauge.ble.gatt.GattEventListener
import com.revel.obdgauge.ble.gatt.GattProperty
import com.revel.obdgauge.ble.gatt.GattServiceInfo
import com.revel.obdgauge.ble.gatt.GattTransport
import com.revel.obdgauge.ble.gatt.GattTransportFactory
import com.revel.obdgauge.ble.gatt.SerialProfile
import com.revel.obdgauge.ble.gatt.shortUuid
import com.revel.obdgauge.ble.permission.BleEnvironment
import com.revel.obdgauge.ble.scan.BleScanner
import com.revel.obdgauge.ble.scan.ScanOutcome
import com.revel.obdgauge.ble.scan.ScanPass
import com.revel.obdgauge.ble.store.RememberedDeviceStore
import com.revel.obdgauge.ble.traffic.TrafficEntry
import com.revel.obdgauge.ble.traffic.TrafficLog
import com.revel.obdgauge.model.LinkError

/** Captures the module's log so tests can assert on what a connect decided and reported. */
class RecordingLogger : BleLogger {
    val lines = mutableListOf<String>()

    override fun log(message: String) {
        lines += message
    }

    fun containing(fragment: String): List<String> = lines.filter { it.contains(fragment) }
}

/** Captures the OBD-48 traffic tap in arrival order, so a test can assert on the capture. */
class RecordingTrafficLog : TrafficLog {
    val entries = mutableListOf<TrafficEntry>()

    override fun record(entry: TrafficEntry) {
        entries += entry
    }
}

class FakeBleEnvironment(
    var bleSupported: Boolean = true,
    var bluetoothEnabled: Boolean = true,
    var locationUsable: Boolean = true,
    var missing: List<String> = emptyList(),
    /** Models API 31+ throwing when the adapter is read without BLUETOOTH_CONNECT. */
    var adapterReadThrows: Boolean = false,
) : BleEnvironment {
    var adapterReads = 0

    override fun isBleSupported(): Boolean = bleSupported

    override fun isBluetoothEnabled(): Boolean {
        adapterReads++
        if (adapterReadThrows) {
            throw SecurityException("BLUETOOTH_CONNECT not granted")
        }
        return bluetoothEnabled
    }

    override fun isLocationUsableForScan(): Boolean = locationUsable

    override fun missingPermissions(): List<String> = missing
}

class FakeRememberedDeviceStore(
    private var address: String? = null,
) : RememberedDeviceStore {
    val remembered = mutableListOf<String>()
    var forgetCount = 0

    override suspend fun lastAddress(): String? = address

    override suspend fun remember(address: String) {
        this.address = address
        remembered += address
    }

    override suspend fun forget() {
        address = null
        forgetCount++
    }
}

class FakeBleScanner(
    var outcome: ScanOutcome = ScanOutcome.Failed(LinkError.DeviceNotFound),
) : BleScanner {
    var calls = 0
    var lastPreferredAddress: String? = null

    /** The pass plan the link actually asked for — asserted, so collapsing it fails a test. */
    var lastPasses: List<ScanPass> = emptyList()

    override suspend fun scan(
        passes: List<ScanPass>,
        preferredAddress: String?,
    ): ScanOutcome {
        calls++
        lastPasses = passes
        lastPreferredAddress = preferredAddress
        return outcome
    }
}

/**
 * A scripted `BluetoothGatt`.
 *
 * Each framework call either reports a status immediately (the happy path), reports a failure
 * status, or stays silent — silence being the failure mode the real stack actually favours, and
 * the one every timeout in `GattSession` exists to survive. A `null` status field means silence.
 *
 * Events are emitted synchronously from the call that triggers them; the session marshals them
 * through its channel, so ordering is preserved and the test scheduler stays deterministic.
 */
@Suppress("LongParameterList") // A scripted double: every knob is one framework behaviour.
class FakeGattTransport(
    override val address: String = DEFAULT_ADDRESS,
    var serviceTree: List<GattServiceInfo> = veepeakServices(),
    var openAccepted: Boolean = true,
    var connectStatus: Int? = GATT_SUCCESS,
    var discoverStatus: Int? = GATT_SUCCESS,
    var notificationsStatus: Int? = GATT_SUCCESS,
    var mtuGranted: Int? = REQUESTED_MTU,
    var mtuStatus: Int = GATT_SUCCESS,
    var writeStatus: Int? = GATT_SUCCESS,
) : GattTransport {
    private var listener: GattEventListener? = null
    private val commandBuffer = StringBuilder()
    private var released = false

    // --- "the framework refused to even try" knobs (one per request the session makes) ------
    var discoverAccepted = true
    var notificationsAccepted = true
    var mtuAccepted = true
    var writeAccepted = true

    /** Per-write ack statuses, consumed in order; falls back to [writeStatus] when exhausted. */
    var writeStatusSequence: MutableList<Int?> = mutableListOf()

    /** Drops the link instead of reporting discovery — a dongle losing power mid-handshake. */
    var dropDuringDiscovery: Int? = null

    /** Delivers the response notification *before* the write ack, which real stacks do. */
    var notifyBeforeAck = false

    /** Every chunk handed to [write], in order. */
    val writes = mutableListOf<ByteArray>()

    /** Chunks as text, `\r` terminators included. */
    val writtenChunks: List<String> get() = writes.map { String(it, Charsets.ISO_8859_1) }

    /** Whole commands the dongle saw, terminator stripped. */
    val commands = mutableListOf<String>()

    var closeCount = 0
    var profileUsed: SerialProfile? = null

    /**
     * Response script, keyed on the command (terminator stripped). Returning an empty list
     * means "the dongle says nothing", which is how the timeout tests work. Set to `null` to
     * take manual control and drive responses with [emitResponse] — that is what the
     * single-flight and cancellation tests need.
     */
    var responder: ((String) -> List<String>)? = { listOf("OK\r\r>") }

    override fun open(listener: GattEventListener): Boolean {
        this.listener = listener
        released = false
        if (!openAccepted) {
            return false
        }
        connectStatus?.let { status ->
            emit(if (status == GATT_SUCCESS) GattEvent.Connected(status) else GattEvent.Disconnected(status))
        }
        return true
    }

    override fun discoverServices(): Boolean {
        if (!discoverAccepted) {
            return false
        }
        val drop = dropDuringDiscovery
        if (drop != null) {
            emit(GattEvent.Disconnected(drop))
        } else {
            discoverStatus?.let { emit(GattEvent.ServicesDiscovered(it)) }
        }
        return true
    }

    override fun services(): List<GattServiceInfo> = serviceTree

    override fun enableNotifications(profile: SerialProfile): Boolean {
        profileUsed = profile
        if (!notificationsAccepted) {
            return false
        }
        notificationsStatus?.let { emit(GattEvent.NotificationsEnabled(it)) }
        return true
    }

    override fun requestMtu(mtu: Int): Boolean {
        if (!mtuAccepted) {
            return false
        }
        mtuGranted?.let { granted -> emit(GattEvent.MtuChanged(granted, mtuStatus)) }
        return true
    }

    override fun write(
        profile: SerialProfile,
        payload: ByteArray,
    ): Boolean {
        profileUsed = profile
        if (!writeAccepted) {
            return false
        }
        writes += payload
        commandBuffer.append(String(payload, Charsets.ISO_8859_1))
        val ackStatus = if (writeStatusSequence.isEmpty()) writeStatus else writeStatusSequence.removeAt(0)
        val complete = commandBuffer.endsWith("\r")
        val chunks =
            if (complete) {
                val command = commandBuffer.dropLast(1).toString()
                commandBuffer.setLength(0)
                commands += command
                responder?.invoke(command).orEmpty()
            } else {
                emptyList()
            }
        if (notifyBeforeAck) {
            emitResponse(*chunks.toTypedArray())
            ackStatus?.let { emit(GattEvent.WriteCompleted(it)) }
        } else {
            ackStatus?.let { emit(GattEvent.WriteCompleted(it)) }
            emitResponse(*chunks.toTypedArray())
        }
        return true
    }

    /** Idempotent, like the real one: only the first close returns the GATT client. */
    override fun close() {
        if (released) {
            return
        }
        released = true
        closeCount++
        listener = null
    }

    /** Pushes notification chunks as if the dongle had answered. */
    fun emitResponse(vararg chunks: String) {
        chunks.forEach { chunk -> emit(GattEvent.DataReceived(chunk.toByteArray(Charsets.ISO_8859_1))) }
    }

    /** Delivers a write acknowledgement out of band — a late ack for an abandoned chunk. */
    fun emitWriteAck(status: Int = GATT_SUCCESS) {
        emit(GattEvent.WriteCompleted(status))
    }

    /** Simulates the peer vanishing: key-off, out of range, adapter switched off. */
    fun dropConnection(status: Int = GATT_ERROR) {
        emit(GattEvent.Disconnected(status))
    }

    private fun emit(event: GattEvent) {
        listener?.onGattEvent(event)
    }

    companion object {
        const val DEFAULT_ADDRESS = "AA:BB:CC:DD:EE:FF"
        const val REQUESTED_MTU = 512

        /** A status a real stack reports on an unexpected drop (`GATT_INSUF_AUTHORIZATION`-era 8). */
        const val GATT_ERROR = 8

        /** The FFF0/FFF1/FFF2 layout the Veepeak family is expected to expose. */
        fun veepeakServices(): List<GattServiceInfo> =
            listOf(
                GattServiceInfo(
                    uuid = shortUuid("1800"),
                    characteristics = listOf(GattCharacteristicInfo(shortUuid("2A00"), GattProperty.READ)),
                ),
                GattServiceInfo(
                    uuid = shortUuid("FFF0"),
                    characteristics =
                        listOf(
                            GattCharacteristicInfo(shortUuid("FFF1"), GattProperty.NOTIFY or GattProperty.READ),
                            GattCharacteristicInfo(
                                shortUuid("FFF2"),
                                GattProperty.WRITE or GattProperty.WRITE_NO_RESPONSE,
                            ),
                        ),
                ),
            )
    }
}

/** Hands out transports per address, recording what was asked for. */
class FakeGattTransportFactory(
    private val provider: (String) -> GattTransport,
) : GattTransportFactory {
    constructor(transport: FakeGattTransport) : this({ transport })

    val requested = mutableListOf<String>()

    override fun create(address: String): GattTransport {
        requested += address
        return provider(address)
    }
}
