package com.revel.obdgauge.app.maintenance.di

import android.content.Context
import androidx.room.Room
import com.revel.obdgauge.app.maintenance.MaintenanceDao
import com.revel.obdgauge.app.maintenance.MaintenanceDatabase
import com.revel.obdgauge.app.maintenance.MaintenanceRepository
import com.revel.obdgauge.app.maintenance.RoomMaintenanceRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * OBD-79: qualifies the real wall-clock [Clock] Maintenance's date math needs (today's calendar
 * date for interval countdowns) — distinct from the app's unqualified [Clock] binding, which on
 * `demo` (`app/src/demo/kotlin/.../di/DataSourceModule.kt`) is deliberately an epoch-anchored FAKE
 * clock aligned to the fake data source's replay timeline, not a real date. Maintenance has no
 * dependency on `VehicleDataSource` at all (this issue's own hard constraint) and needs the SAME
 * real date on both flavors, so it can't reuse that binding.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MaintenanceClock

/**
 * OBD-79 maintenance persistence wiring: `:app`'s own Room database file, deliberately separate
 * from OBD-21's DataStore file (`settings/di/SettingsModule.kt`) — see that module's own KDoc for
 * why persistence stores never share a file in this codebase.
 *
 * Flavor-common (`src/main/`): the maintenance tracker has no dependency on `:core:ble`/
 * `:core:protocol`/`:core:testing` at all (this issue is manual-odometer-only — see
 * `issues/OBD-79.md`), so it's identical wiring on `demo` and `prod`.
 */
@Module
@InstallIn(SingletonComponent::class)
object MaintenanceModule {
    @Provides
    @Singleton
    fun provideMaintenanceDatabase(
        @ApplicationContext context: Context,
    ): MaintenanceDatabase = Room.databaseBuilder(context, MaintenanceDatabase::class.java, DATABASE_NAME).build()

    @Provides
    fun provideMaintenanceDao(database: MaintenanceDatabase): MaintenanceDao = database.maintenanceDao()

    @Provides
    @Singleton
    fun provideMaintenanceRepository(dao: MaintenanceDao): MaintenanceRepository = RoomMaintenanceRepository(dao)

    @Provides
    @MaintenanceClock
    fun provideMaintenanceClock(): Clock = Clock.systemDefaultZone()

    private const val DATABASE_NAME = "obd_maintenance.db"
}
