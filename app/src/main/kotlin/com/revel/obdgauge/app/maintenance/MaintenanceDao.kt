package com.revel.obdgauge.app.maintenance

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * OBD-79: Room DAO for both tables. Returns [Flow]s (never one-shot suspend reads) for anything
 * the UI observes — same "live-updates without a restart" shape [SettingsRepository]'s DataStore
 * Flow already gives the rest of the app.
 */
@Dao
interface MaintenanceDao {
    @Query("SELECT * FROM service_items ORDER BY category, name")
    fun serviceItems(): Flow<List<ServiceItem>>

    @Query("SELECT * FROM service_items WHERE id = :id")
    fun serviceItem(id: Long): Flow<ServiceItem?>

    @Query("SELECT COUNT(*) FROM service_items")
    suspend fun serviceItemCount(): Int

    @Insert
    suspend fun insertServiceItems(items: List<ServiceItem>)

    @Update
    suspend fun updateServiceItem(item: ServiceItem)

    @Query("SELECT * FROM maintenance_records WHERE serviceItemId = :serviceItemId ORDER BY performedDateEpochDay DESC")
    fun recordsFor(serviceItemId: Long): Flow<List<MaintenanceRecord>>

    /**
     * The single most recent record per service item, in one query — what
     * [MaintenanceRepository.statuses] needs to compute every list-row's countdown without an
     * N+1 query per item. `MAX(performedDateEpochDay)` breaks ties arbitrarily (Room/SQLite
     * default row selection); an item serviced twice on the same day picking either row is
     * immaterial since [MaintenanceStatus] only reads date + odometer, and a same-day double
     * entry would carry the same date either way.
     */
    @Query(
        """
        SELECT r.* FROM maintenance_records r
        INNER JOIN (
            SELECT serviceItemId, MAX(performedDateEpochDay) AS latestDate
            FROM maintenance_records GROUP BY serviceItemId
        ) latest
        ON r.serviceItemId = latest.serviceItemId AND r.performedDateEpochDay = latest.latestDate
        """,
    )
    fun latestRecords(): Flow<List<MaintenanceRecord>>

    @Insert
    suspend fun insertRecord(record: MaintenanceRecord): Long

    @Delete
    suspend fun deleteRecord(record: MaintenanceRecord)
}
