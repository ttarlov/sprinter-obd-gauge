package com.revel.obdgauge.app.settings

import kotlinx.coroutines.flow.Flow

/**
 * Persistence boundary for [AppSettings] (OBD-21). An interface (not a concrete DataStore type)
 * so tests can substitute an in-memory double instead of touching disk — mirrors `:core:ble`'s
 * `RememberedDeviceStore`/`DataStoreRememberedDeviceStore` split.
 */
interface SettingsRepository {
    /** Current settings, updated live as they change (including edits from another collector). */
    val settings: Flow<AppSettings>

    /** Applies [transform] to the current settings and persists the result. */
    suspend fun update(transform: (AppSettings) -> AppSettings)
}
