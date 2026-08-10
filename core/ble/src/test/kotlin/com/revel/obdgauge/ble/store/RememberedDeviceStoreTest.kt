package com.revel.obdgauge.ble.store

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The remembered-device fast path's persistence, against a real Preferences DataStore writing to
 * a temp file — no Robolectric needed, because the store takes a `DataStore` rather than a
 * `Context`.
 */
class RememberedDeviceStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val scope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `an address survives a round trip`() =
        runTest {
            val store = newStore()

            store.remember("AA:BB:CC:DD:EE:FF")

            assertEquals("AA:BB:CC:DD:EE:FF", store.lastAddress())
        }

    @Test
    fun `nothing is remembered before the first successful connect`() =
        runTest {
            assertNull(newStore().lastAddress())
        }

    @Test
    fun `addresses are normalised to the uppercase form android uses`() =
        runTest {
            val store = newStore()

            store.remember("aa:bb:cc:dd:ee:ff")

            assertEquals("AA:BB:CC:DD:EE:FF", store.lastAddress())
        }

    @Test
    fun `forget clears the fast path`() =
        runTest {
            val store = newStore()
            store.remember("AA:BB:CC:DD:EE:FF")

            store.forget()

            assertNull(store.lastAddress())
        }

    @Test
    fun `forgetting when nothing is remembered is a no-op`() =
        runTest {
            val store = newStore()

            store.forget()

            assertNull(store.lastAddress())
        }

    @Test
    fun `a malformed address is never persisted`() =
        runTest {
            val store = newStore()

            store.remember("not-an-address")

            assertNull(store.lastAddress())
        }

    @Test
    fun `a later connect overwrites the remembered device`() =
        runTest {
            val store = newStore()
            store.remember("AA:BB:CC:DD:EE:FF")

            store.remember("11:22:33:44:55:66")

            assertEquals("11:22:33:44:55:66", store.lastAddress())
        }

    @Test
    fun `a corrupted store reads as empty instead of failing every connect forever`() =
        runTest {
            // What an ignition cut mid-write leaves behind. Without the catch in lastAddress()
            // this throws here, and on every read after it, for the life of the install.
            val corrupted = temporaryFolder.newFile("corrupted.preferences_pb")
            corrupted.writeBytes(byteArrayOf(0x42, 0x00, 0x7F, 0x13, 0x37, 0x00, 0x01))
            val store = DataStoreRememberedDeviceStore(PreferenceDataStoreFactory.create(scope = scope) { corrupted })

            assertNull(store.lastAddress())
        }

    @Test
    fun `a write failure does not fail the caller`() =
        runTest {
            // produceFile points at a directory: every write attempt fails at the filesystem.
            val directory = temporaryFolder.newFolder("not-a-file")
            val store = DataStoreRememberedDeviceStore(PreferenceDataStoreFactory.create(scope = scope) { directory })

            store.remember("AA:BB:CC:DD:EE:FF")
            store.forget()

            assertNull(store.lastAddress())
        }

    @Test
    fun `address validation accepts only canonical mac addresses`() {
        assertTrue(BluetoothAddress.isValid("AA:BB:CC:DD:EE:FF"))
        assertTrue(BluetoothAddress.isValid("aa:bb:cc:dd:ee:ff"))
        assertFalse(BluetoothAddress.isValid("AA-BB-CC-DD-EE-FF"))
        assertFalse(BluetoothAddress.isValid("AA:BB:CC:DD:EE"))
        assertFalse(BluetoothAddress.isValid("AA:BB:CC:DD:EE:GG"))
        assertFalse(BluetoothAddress.isValid(""))
        assertFalse(BluetoothAddress.isValid(null))
    }

    private fun newStore(): RememberedDeviceStore =
        DataStoreRememberedDeviceStore(
            PreferenceDataStoreFactory.create(scope = scope) {
                temporaryFolder.newFile("remembered-${counter++}.preferences_pb").also { it.delete() }
            },
        )

    private companion object {
        var counter = 0
    }
}
