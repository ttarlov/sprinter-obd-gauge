package com.revel.obdgauge.app.speed.di

import com.revel.obdgauge.app.speed.GpsSpeedProvider
import com.revel.obdgauge.app.speed.SpeedSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * OBD-61: satisfies `src/main/`'s optional [SpeedSource] binding on the `prod` flavor with the
 * real [GpsSpeedProvider], so `MainActivity` can start/stop GPS updates while `demo` keeps
 * resolving to `Optional.empty()`. `GpsSpeedProvider` is a `@Singleton @Inject` type, so the
 * same instance the calibrator listens to (constructed in `DataSourceModule`) is the one
 * `MainActivity`'s lifecycle drives.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProdSpeedModule {
    @Binds
    abstract fun bindSpeedSource(impl: GpsSpeedProvider): SpeedSource
}
