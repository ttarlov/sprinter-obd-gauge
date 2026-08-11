package com.revel.obdgauge.app.settings.di

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.revel.obdgauge.app.settings.DataStoreSettingsRepository
import com.revel.obdgauge.app.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * OBD-21 settings persistence wiring: `:app`'s own Preferences DataStore file, deliberately
 * separate from `:core:ble`'s `RememberedDeviceStore` file (`BleProvidersModule`) so the two can
 * never collide — same discipline that module's own KDoc calls out for its file relative to a
 * future `:app` store, now realized here.
 *
 * Flavor-common (`src/main/`): settings apply to both `demo` and `prod` — persistence itself has
 * no dependency on `:core:testing`/the fake data source, only `AppSettings.pollRate`'s *effect*
 * is flavor-conditional, and that's read at the data-source layer, not here.
 */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {
    @Provides
    @Singleton
    fun provideSettingsRepository(
        @ApplicationContext context: Context,
    ): SettingsRepository =
        DataStoreSettingsRepository(
            PreferenceDataStoreFactory.create(
                // A vehicle cuts power mid-write eventually. Without this the truncated file
                // throws on every read forever; with it the file is replaced and the worst
                // outcome is one reset-to-defaults settings screen.
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                produceFile = { context.preferencesDataStoreFile(STORE_NAME) },
            ),
        )

    private const val STORE_NAME = "obd_app_settings"
}
