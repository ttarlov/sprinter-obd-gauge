package com.revel.obdgauge.app.maintenance

import kotlinx.coroutines.flow.Flow

/**
 * Persistence boundary for the maintenance catalog/history — mirrors
 * [com.revel.obdgauge.app.settings.SettingsRepository]'s interface-not-concrete-type split so
 * tests substitute an in-memory Room database instead of a real on-disk one (see
 * `MaintenanceRepositoryTest`).
 */
interface MaintenanceRepository {
    /** The full catalog, one row per [ServiceItem], ordered by category then name. */
    val serviceItems: Flow<List<ServiceItem>>

    /**
     * Every item's most recent [MaintenanceRecord], keyed by [ServiceItem.id] — see
     * [MaintenanceDao.latestRecords].
     */
    val latestRecordByItemId: Flow<Map<Long, MaintenanceRecord>>

    /** One item's full record history, newest first. */
    fun recordsFor(serviceItemId: Long): Flow<List<MaintenanceRecord>>

    /** Persists an edit to [item] (intervals, part numbers, spec notes, enabled). */
    suspend fun updateServiceItem(item: ServiceItem)

    /** Logs a new service event; returns the inserted record's id. */
    suspend fun logService(record: MaintenanceRecord): Long

    /**
     * Inserts [Ncv3Om642Seed.items] once, iff the catalog table is currently empty — never
     * overwrites or re-inserts on a later call (see `MaintenanceRepositoryTest`'s "seed inserts
     * once" case). Safe to call on every app/ViewModel start, same idempotent-eager-seed idiom
     * [com.revel.obdgauge.app.gauge.DashboardViewModel]'s grid-layout seed already uses.
     */
    suspend fun seedIfNeeded()
}
