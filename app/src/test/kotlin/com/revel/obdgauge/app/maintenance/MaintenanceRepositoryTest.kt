package com.revel.obdgauge.app.maintenance

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.revel.obdgauge.app.maintenance.seed.Ncv3Om642Seed
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * OBD-79 AC: CRUD against a real (in-memory) Room database, and "seed inserts exactly once."
 * `Room.inMemoryDatabaseBuilder` + `allowMainThreadQueries()`, per the issue's own Testing
 * section — Robolectric only for [ApplicationProvider]'s `Context`, no UI involved.
 */
@RunWith(RobolectricTestRunner::class)
class MaintenanceRepositoryTest {
    private lateinit var database: MaintenanceDatabase
    private lateinit var repository: MaintenanceRepository

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MaintenanceDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository = RoomMaintenanceRepository(database.maintenanceDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a new database has no service items until seeded`() =
        runTest {
            assertEquals(emptyList<ServiceItem>(), repository.serviceItems.first())
        }

    @Test
    fun `seedIfNeeded inserts the full NCV3-OM642 catalog exactly once`() =
        runTest {
            repository.seedIfNeeded()
            val afterFirstSeed = repository.serviceItems.first()
            assertEquals(Ncv3Om642Seed.items.size, afterFirstSeed.size)
            assertTrue(afterFirstSeed.all { it.isSeed })

            // Re-open (a fresh repository over the SAME database, simulating an app restart) and
            // seed again — must be a no-op, not a duplicate insert.
            val reopened = RoomMaintenanceRepository(database.maintenanceDao())
            reopened.seedIfNeeded()

            assertEquals(Ncv3Om642Seed.items.size, reopened.serviceItems.first().size)
        }

    @Test
    fun `seedIfNeeded does not overwrite a catalog that already has user data`() =
        runTest {
            repository.seedIfNeeded()
            val oilChange = repository.serviceItems.first().first { it.name == "Engine oil + filter" }
            repository.updateServiceItem(oilChange.copy(intervalMiles = 7_500))

            repository.seedIfNeeded()

            val stillEdited = repository.serviceItems.first().first { it.name == "Engine oil + filter" }
            assertEquals(7_500, stillEdited.intervalMiles)
        }

    @Test
    fun `updateServiceItem persists an edit to intervals, part numbers, and spec notes`() =
        runTest {
            repository.seedIfNeeded()
            val item = repository.serviceItems.first().first()

            val edited =
                item.copy(
                    intervalMiles = 12_345,
                    partNumbers = listOf("A1234567890", "A0987654321"),
                    specNotes = "edited by Taras",
                )
            repository.updateServiceItem(edited)

            val reloaded = repository.serviceItems.first().first { it.id == item.id }
            assertEquals(edited, reloaded)
        }

    @Test
    fun `logService inserts a record retrievable by recordsFor, newest first`() =
        runTest {
            repository.seedIfNeeded()
            val item = repository.serviceItems.first().first()

            repository.logService(
                MaintenanceRecord(serviceItemId = item.id, performedDateEpochDay = 19_000, odometerMiles = 90_000),
            )
            repository.logService(
                MaintenanceRecord(serviceItemId = item.id, performedDateEpochDay = 19_100, odometerMiles = 95_000),
            )

            val records = repository.recordsFor(item.id).first()
            assertEquals(2, records.size)
            assertEquals(19_100L, records.first().performedDateEpochDay)
        }

    @Test
    fun `latestRecordByItemId returns only the most recent record per item`() =
        runTest {
            repository.seedIfNeeded()
            val item = repository.serviceItems.first().first()

            repository.logService(
                MaintenanceRecord(serviceItemId = item.id, performedDateEpochDay = 19_000, odometerMiles = 80_000),
            )
            repository.logService(
                MaintenanceRecord(serviceItemId = item.id, performedDateEpochDay = 19_200, odometerMiles = 90_000),
            )

            val latest = repository.latestRecordByItemId.first()
            assertEquals(90_000, latest.getValue(item.id).odometerMiles)
        }
}
