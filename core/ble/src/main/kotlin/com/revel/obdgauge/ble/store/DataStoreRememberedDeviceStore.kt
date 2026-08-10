package com.revel.obdgauge.ble.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * [RememberedDeviceStore] backed by Preferences DataStore.
 *
 * Takes the `DataStore` rather than a `Context` so the same class runs headless in a unit test
 * against a temp-file store — no Robolectric, no device. The Hilt module builds the real one
 * (see `BleModule`) rather than exposing a `DataStore<Preferences>` binding to the app graph,
 * which would collide with any other DataStore `:app` grows later.
 *
 * Malformed stored values are treated as absent (and are not repaired in place): the worst case
 * is one extra scan, which is strictly better than handing garbage to `connectGatt`.
 */
class DataStoreRememberedDeviceStore(
    private val dataStore: DataStore<Preferences>,
) : RememberedDeviceStore {
    /**
     * A read failure yields `null`, never an exception. This file lives in a van: an ignition
     * cut mid-write leaves a truncated preferences file, and DataStore reports that as an
     * `IOException` on *every* subsequent read. Letting it escape would turn one bad power cycle
     * into a link that can never connect again; the cost of swallowing it is one extra scan.
     * The production `DataStore` also gets a corruption handler that repairs the file (see
     * `BleModule`) — this is the belt to that's braces.
     */
    override suspend fun lastAddress(): String? =
        dataStore.data
            .map { preferences -> BluetoothAddress.normalizeOrNull(preferences[LAST_ADDRESS]) }
            .catch { emit(null) }
            .first()

    /**
     * Best-effort: failing to persist the fast path must never fail a connect. Cancellation is
     * not a persistence failure, though — it must propagate, not be swallowed.
     */
    override suspend fun remember(address: String) {
        val normalized = BluetoothAddress.normalizeOrNull(address) ?: return
        runCatching { dataStore.edit { preferences -> preferences[LAST_ADDRESS] = normalized } }
            .onFailure { if (it is CancellationException) throw it }
    }

    override suspend fun forget() {
        runCatching { dataStore.edit { preferences -> preferences.remove(LAST_ADDRESS) } }
            .onFailure { if (it is CancellationException) throw it }
    }

    companion object {
        /** Preferences file name, created under the app's `datastore/` directory. */
        const val STORE_NAME = "obd_ble_link"

        private val LAST_ADDRESS = stringPreferencesKey("last_device_address")
    }
}
