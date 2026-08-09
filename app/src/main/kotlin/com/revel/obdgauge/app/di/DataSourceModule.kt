package com.revel.obdgauge.app.di

import com.revel.obdgauge.model.VehicleDataSource
import com.revel.obdgauge.testing.datasource.FakeVehicleDataSource
import com.revel.obdgauge.testing.datasource.Scenario
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

/**
 * Sprint-1 wiring: [VehicleDataSource] binds to [FakeVehicleDataSource] so the app is
 * runnable end to end against `:core:testing` before the real `:core:ble`/`:core:protocol`
 * chain exists. Phase-4 integration (per `docs/01-build-plan.md`) replaces this binding for
 * the `prod` flavor; the `demo` flavor (OBD-12) keeps it.
 *
 * [Scenario.GRADE_CLIMB] is the default per OBD-10's ui-agent brief — it exercises the boost
 * arc sweep, which [Scenario.IDLE] barely moves.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataSourceModule {
    @Provides
    @Singleton
    fun provideVehicleDataSource(): VehicleDataSource = FakeVehicleDataSource(scenario = Scenario.GRADE_CLIMB)

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemDefaultZone()
}
