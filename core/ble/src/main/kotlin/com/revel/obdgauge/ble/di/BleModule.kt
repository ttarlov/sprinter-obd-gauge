package com.revel.obdgauge.ble.di

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.revel.obdgauge.ble.AndroidBleLogger
import com.revel.obdgauge.ble.BleConfig
import com.revel.obdgauge.ble.BleLogger
import com.revel.obdgauge.ble.gatt.AndroidGattTransportFactory
import com.revel.obdgauge.ble.gatt.GattTransportFactory
import com.revel.obdgauge.ble.permission.AndroidBleEnvironment
import com.revel.obdgauge.ble.permission.BleEnvironment
import com.revel.obdgauge.ble.scan.AndroidBleScanner
import com.revel.obdgauge.ble.scan.BleScanner
import com.revel.obdgauge.ble.scan.DongleFilter
import com.revel.obdgauge.ble.store.DataStoreRememberedDeviceStore
import com.revel.obdgauge.ble.store.RememberedDeviceStore
import com.revel.obdgauge.ble.traffic.TrafficLog
import com.revel.obdgauge.ble.traffic.defaultTrafficLog
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * The single thread every piece of link state is confined to. See `BleObdLink`'s threading
 * notes: GATT callbacks arrive on binder threads and are marshalled here, so nothing in this
 * module needs a lock.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LinkDispatcher

/**
 * Bindings internal to `:core:ble`.
 *
 * `BleObdLink` itself is deliberately **not** bound to `ObdLink` here — that wiring belongs at
 * the `:app` edge (DECISIONS.md D2, and OBD-25 does it per flavor). What this module does is
 * satisfy `BleObdLink`'s own constructor so `:app` only has to write one `@Binds`.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class BleBindingsModule {
    @Binds
    abstract fun bindEnvironment(implementation: AndroidBleEnvironment): BleEnvironment

    @Binds
    abstract fun bindScanner(implementation: AndroidBleScanner): BleScanner

    @Binds
    abstract fun bindTransportFactory(implementation: AndroidGattTransportFactory): GattTransportFactory

    @Binds
    abstract fun bindLogger(implementation: AndroidBleLogger): BleLogger
}

@Module
@InstallIn(SingletonComponent::class)
internal object BleProvidersModule {
    /**
     * One thread, for the life of the process. Every GATT callback, every state transition and
     * every command runs on it, which is what makes the session state safe without locks.
     */
    @Provides
    @Singleton
    @LinkDispatcher
    fun provideLinkDispatcher(): CoroutineDispatcher =
        Executors
            .newSingleThreadExecutor { runnable -> Thread(runnable, "obd-ble-link") }
            .asCoroutineDispatcher()

    @Provides
    @Singleton
    fun provideBleConfig(): BleConfig = BleConfig()

    @Provides
    @Singleton
    fun provideDongleFilter(): DongleFilter = DongleFilter()

    /**
     * OBD-48. [defaultTrafficLog] is declared once per build type: `src/debug/` returns the
     * `ObdTraffic` logcat sink, `src/release/` returns [TrafficLog.NONE]. The variant chooses,
     * not a runtime flag — so the sink, its tag and its formatter are absent from the release
     * variant's bytecode rather than merely unreachable in it.
     */
    @Provides
    @Singleton
    fun provideTrafficLog(): TrafficLog = defaultTrafficLog()

    /**
     * The DataStore is built here and kept behind [RememberedDeviceStore] rather than exposed as
     * a `DataStore<Preferences>` binding, so it can never collide with a preferences store
     * `:app` adds later.
     */
    @Provides
    @Singleton
    fun provideRememberedDeviceStore(
        @ApplicationContext context: Context,
    ): RememberedDeviceStore =
        DataStoreRememberedDeviceStore(
            PreferenceDataStoreFactory.create(
                // A vehicle cuts power mid-write eventually. Without this the truncated file
                // throws on every read forever; with it the file is replaced and the worst
                // outcome is one forgotten device.
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                produceFile = { context.preferencesDataStoreFile(DataStoreRememberedDeviceStore.STORE_NAME) },
            ),
        )
}
