package com.revel.obdgauge.app.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * [SettingsRepository] backed by Preferences DataStore.
 *
 * Takes the `DataStore` rather than a `Context` so it runs headless in a unit test against a
 * temp-file store (see `SettingsRepositoryTest`) — the Hilt module (`di/SettingsModule.kt`)
 * builds the real one, in its own file, separate from `:core:ble`'s `RememberedDeviceStore`
 * file so the two can never collide.
 *
 * Read failures decode to [AppSettings]' defaults rather than propagating — a van cuts power
 * mid-write eventually, and a truncated preferences file throws `IOException` on *every*
 * subsequent read; letting that escape would turn one bad power cycle into settings that can
 * never be read again. The production `DataStore` also carries a `ReplaceFileCorruptionHandler`
 * that repairs the file on disk (see `SettingsModule`) — this `catch` is the belt to that's
 * braces, mirroring `DataStoreRememberedDeviceStore`'s identical reasoning in `:core:ble`.
 */
class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {
    override val settings: Flow<AppSettings> =
        dataStore.data
            .map { preferences -> decodeAppSettings(preferences) }
            .catch { emit(AppSettings()) }

    /**
     * Best-effort: cancellation must propagate (a caller tearing down mid-write is not a
     * persistence failure), everything else is swallowed — a lost settings edit is recoverable
     * (the user can just re-edit), a crash on save is not.
     */
    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        runCatching {
            dataStore.edit { preferences ->
                val current = decodeAppSettings(preferences)
                encodeAppSettings(transform(current), preferences)
            }
        }.onFailure { if (it is CancellationException) throw it }
    }
}
