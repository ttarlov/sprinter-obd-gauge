package com.revel.obdgauge.app.maintenance

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * OBD-79's Room database — deliberately separate from the OBD-21 DataStore file
 * ([com.revel.obdgauge.app.settings.di.SettingsModule]'s `STORE_NAME`); see that module's own
 * "never collide" discipline. `exportSchema = false`: this is a small, code-owned catalog with no
 * shipped-install migration story yet — a schema-export/migration-test setup is machinery this
 * issue's scope doesn't warrant (same "no serialization-library dependency" call
 * [com.revel.obdgauge.app.settings.SettingsCodec] makes for `AppSettings`).
 */
@Database(
    entities = [ServiceItem::class, MaintenanceRecord::class],
    version = 1,
    exportSchema = false,
)
abstract class MaintenanceDatabase : RoomDatabase() {
    abstract fun maintenanceDao(): MaintenanceDao
}
