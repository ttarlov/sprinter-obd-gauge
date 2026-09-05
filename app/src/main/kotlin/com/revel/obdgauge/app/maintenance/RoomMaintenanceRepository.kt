package com.revel.obdgauge.app.maintenance

import com.revel.obdgauge.app.maintenance.seed.Ncv3Om642Seed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [MaintenanceRepository] backed by [MaintenanceDao] — takes the DAO, not a `Context`, so it runs
 * headless in a test against an in-memory Room database (see `MaintenanceRepositoryTest`).
 */
class RoomMaintenanceRepository(
    private val dao: MaintenanceDao,
) : MaintenanceRepository {
    override val serviceItems: Flow<List<ServiceItem>> = dao.serviceItems()

    override val latestRecordByItemId: Flow<Map<Long, MaintenanceRecord>> =
        dao.latestRecords().map { records -> records.associateBy { it.serviceItemId } }

    override fun recordsFor(serviceItemId: Long): Flow<List<MaintenanceRecord>> = dao.recordsFor(serviceItemId)

    override suspend fun updateServiceItem(item: ServiceItem) = dao.updateServiceItem(item)

    override suspend fun logService(record: MaintenanceRecord): Long = dao.insertRecord(record)

    override suspend fun seedIfNeeded() {
        if (dao.serviceItemCount() == 0) {
            dao.insertServiceItems(Ncv3Om642Seed.items)
        }
    }
}
