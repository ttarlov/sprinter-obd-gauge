package com.revel.obdgauge.app.di

import com.revel.obdgauge.app.datasource.StubVehicleDataSource
import com.revel.obdgauge.model.VehicleDataSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

/**
 * `prod` flavor wiring (OBD-12): [VehicleDataSource] binds to [StubVehicleDataSource] — a
 * placeholder that sits [com.revel.obdgauge.model.LinkState.Disconnected] with empty readings.
 * The real `ObdLink` → `:core:protocol` → [VehicleDataSource] chain lands in OBD-25; this
 * module exists so the `prod` flavor is buildable/installable today, deliberately without
 * depending on `:core:testing` (see the HARD CONSTRAINT in `app/MODULE.md`, verified via
 * `./gradlew :app:dependencies --configuration prodReleaseRuntimeClasspath`).
 *
 * [Clock.systemDefaultZone] is fine here (unlike the `demo` flavor's anchored clock — see
 * `src/demo/.../di/DataSourceModule.kt`): [StubVehicleDataSource] never emits a stale reading,
 * so the "last seen Xs ago" math this clock feeds never runs.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataSourceModule {
    @Provides
    @Singleton
    fun provideVehicleDataSource(): VehicleDataSource = StubVehicleDataSource()

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemDefaultZone()
}
