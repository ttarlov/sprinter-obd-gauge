package com.revel.obdgauge.ble

import com.revel.obdgauge.ble.gatt.GattTransport
import com.revel.obdgauge.ble.gatt.GattTransportFactory
import com.revel.obdgauge.ble.permission.BlePermissionPolicy
import com.revel.obdgauge.ble.scan.DiscoveredDevice
import com.revel.obdgauge.ble.scan.ScanOutcome
import com.revel.obdgauge.ble.testing.DEVICE
import com.revel.obdgauge.ble.testing.FakeBleEnvironment
import com.revel.obdgauge.ble.testing.FakeBleScanner
import com.revel.obdgauge.ble.testing.FakeGattTransport
import com.revel.obdgauge.ble.testing.FakeGattTransportFactory
import com.revel.obdgauge.ble.testing.FakeRememberedDeviceStore
import com.revel.obdgauge.ble.testing.RecordingLogger
import com.revel.obdgauge.ble.testing.STALE_DEVICE
import com.revel.obdgauge.ble.testing.bleObdLink
import com.revel.obdgauge.ble.testing.recordStates
import com.revel.obdgauge.model.LinkError
import com.revel.obdgauge.model.LinkState
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * OBD-17: what has to be true before a radio is touched, how a dongle is found, and the
 * remembered-device fast path. The GATT bridge itself is exercised in [GattBridgeTest].
 */
class BleObdLinkTest {
    private val environment = FakeBleEnvironment()
    private val scanner = FakeBleScanner(ScanOutcome.Found(DiscoveredDevice(DEVICE, "VEEPEAK-OBD")))
    private val store = FakeRememberedDeviceStore()
    private val logger = RecordingLogger()
    private val transport = FakeGattTransport()

    // ---- OBD-17: preconditions -------------------------------------------------------------

    @Test
    fun `a connect without permissions parks in PermissionDenied instead of throwing`() =
        runTest {
            environment.missing = listOf(BlePermissionPolicy.BLUETOOTH_SCAN)
            val link = newLink()

            link.connect()

            assertEquals(LinkState.Error(LinkError.PermissionDenied), link.state.value)
            assertEquals("a denied scan must never reach the radio", 0, scanner.calls)
        }

    @Test
    fun `missing permissions are reported for the app to request`() =
        runTest {
            environment.missing = listOf(BlePermissionPolicy.BLUETOOTH_CONNECT)

            assertEquals(listOf(BlePermissionPolicy.BLUETOOTH_CONNECT), newLink().missingPermissions)
        }

    @Test
    fun `a connect with the adapter off parks in BluetoothOff`() =
        runTest {
            environment.bluetoothEnabled = false
            val link = newLink()

            link.connect()

            assertEquals(LinkState.Error(LinkError.BluetoothOff), link.state.value)
            assertEquals(0, scanner.calls)
        }

    @Test
    fun `a device without bluetooth le parks in a typed unknown error`() =
        runTest {
            environment.bleSupported = false
            val link = newLink()

            link.connect()

            assertTrue((link.state.value as LinkState.Error).cause is LinkError.Unknown)
        }

    // ---- OBD-17: scan and the remembered-device fast path -----------------------------------

    @Test
    fun `a scan that finds nothing parks in DeviceNotFound`() =
        runTest {
            scanner.outcome = ScanOutcome.Failed(LinkError.DeviceNotFound)
            val link = newLink()

            link.connect()

            assertEquals(LinkState.Error(LinkError.DeviceNotFound), link.state.value)
        }

    @Test
    fun `a scan then connect reaches Ready and passes through Scanning`() =
        runTest {
            val link = newLink()
            val states = recordStates(link)

            link.connect()

            assertEquals(LinkState.Ready, link.state.value)
            assertTrue("expected a Scanning transition, got $states", states.contains(LinkState.Scanning))
            assertEquals(1, scanner.calls)
        }

    @Test
    fun `a successful connect is remembered for next time`() =
        runTest {
            val link = newLink()

            link.connect()

            assertEquals(listOf(DEVICE), store.remembered)
            assertEquals(DEVICE, link.rememberedDevice())
        }

    @Test
    fun `a remembered device skips the scan entirely`() =
        runTest {
            val link = newLink(store = FakeRememberedDeviceStore(DEVICE))
            val states = recordStates(link)

            link.connect()

            assertEquals(LinkState.Ready, link.state.value)
            assertEquals("the fast path must not scan", 0, scanner.calls)
            assertFalse("no Scanning state on the fast path: $states", states.contains(LinkState.Scanning))
        }

    @Test
    fun `a remembered device that no longer answers falls back to scanning`() =
        runTest {
            val stale = FakeGattTransport(address = STALE_DEVICE, connectStatus = FakeGattTransport.GATT_ERROR)
            val remembered = FakeRememberedDeviceStore(STALE_DEVICE)
            val link =
                newLink(
                    store = remembered,
                    transports = byAddress(STALE_DEVICE to stale, DEVICE to transport),
                )

            link.connect()

            assertEquals(LinkState.Ready, link.state.value)
            assertEquals(1, scanner.calls)
            assertEquals(DEVICE, remembered.remembered.last())
            assertTrue(logger.containing("falling back to scan").isNotEmpty())
        }

    @Test
    fun `the scan is told which address was remembered so it can prefer it`() =
        runTest {
            val stale = FakeGattTransport(address = STALE_DEVICE, connectStatus = FakeGattTransport.GATT_ERROR)
            val link =
                newLink(
                    store = FakeRememberedDeviceStore(STALE_DEVICE),
                    transports = byAddress(STALE_DEVICE to stale, DEVICE to transport),
                )

            link.connect()

            assertEquals(STALE_DEVICE, scanner.lastPreferredAddress)
        }

    @Test
    fun `forgetting the remembered device forces the next connect to scan`() =
        runTest {
            val store = FakeRememberedDeviceStore(DEVICE)
            val link = newLink(store = store)

            link.forgetRememberedDevice()
            link.connect()

            assertEquals(1, store.forgetCount)
            assertEquals(1, scanner.calls)
        }

    // ---- round-2 review: B2 completion — stale connect attempts do no work -------------------

    @Test
    fun `an overlapping connect on the remembered fast path opens exactly two clients and tracks the winner`() =
        runTest {
            // Round-2 probe scenario: connect#2 kills #1's silent Direct handshake, and #1 must
            // NOT fall back to scanning on its stale ticket and open a third, untracked client.
            val first = FakeGattTransport(address = DEVICE, connectStatus = null)
            val second = FakeGattTransport(address = DEVICE)
            val queue = ArrayDeque(listOf<GattTransport>(first, second))
            val factory = FakeGattTransportFactory { queue.removeFirst() }
            val link = newLink(store = FakeRememberedDeviceStore(DEVICE), transports = factory)

            val superseded = launch { link.connect() }
            runCurrent()
            link.connect()
            advanceUntilIdle()
            superseded.join()

            assertEquals("no third client may be opened", 2, factory.requested.size)
            assertEquals("the loser must be closed", 1, first.closeCount)
            assertEquals("the winner must stay open", 0, second.closeCount)
            assertEquals(LinkState.Ready, link.state.value)
            assertEquals("a stale attempt must not scan", 0, scanner.calls)
        }

    @Test
    fun `disconnect during a direct handshake wins and starts nothing new`() =
        runTest {
            val silent = FakeGattTransport(address = DEVICE, connectStatus = null)
            val factory = FakeGattTransportFactory(silent)
            val link = newLink(store = FakeRememberedDeviceStore(DEVICE), transports = factory)

            val attempt = launch { link.connect() }
            runCurrent()
            link.disconnect()
            advanceUntilIdle()
            attempt.join()

            assertEquals("the user disconnected; nothing may scan", 0, scanner.calls)
            assertEquals("one client, closed", 1, factory.requested.size)
            assertEquals(1, silent.closeCount)
            assertEquals(LinkState.Disconnected, link.state.value)
        }

    // ---- round-1 review: M1, the permission gate short-circuits the adapter read ------------

    @Test
    fun `the adapter is never read while permissions are missing`() =
        runTest {
            environment.missing = listOf(BlePermissionPolicy.BLUETOOTH_CONNECT)
            environment.adapterReadThrows = true
            val link = newLink()

            link.connect()

            assertEquals(LinkState.Error(LinkError.PermissionDenied), link.state.value)
            assertEquals("reading the adapter without BLUETOOTH_CONNECT throws on API 31+", 0, environment.adapterReads)
        }

    @Test
    fun `the link asks for a filtered sweep followed by an unfiltered one`() =
        runTest {
            val link = newLink()

            link.connect()

            assertEquals(listOf(true, false), scanner.lastPasses.map { it.filterByServiceUuid })
        }

    // ---- helpers ---------------------------------------------------------------------------

    private fun TestScope.newLink(
        store: FakeRememberedDeviceStore = this@BleObdLinkTest.store,
        transports: GattTransportFactory = FakeGattTransportFactory(transport),
        config: BleConfig = BleConfig(),
    ): BleObdLink = bleObdLink(environment, scanner, transports, store, logger, config)

    private fun byAddress(vararg entries: Pair<String, GattTransport>): GattTransportFactory {
        val map = entries.toMap()
        return FakeGattTransportFactory { address -> requireNotNull(map[address]) { "no transport for $address" } }
    }

    private companion object {
        val COMMAND_TIMEOUT = 2.seconds
    }
}
