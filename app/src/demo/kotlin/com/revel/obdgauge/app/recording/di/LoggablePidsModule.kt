package com.revel.obdgauge.app.recording.di

import com.revel.obdgauge.app.gauge.DASHBOARD_PIDS
import com.revel.obdgauge.app.gauge.RPM_PID_DEFINITION
import com.revel.obdgauge.model.PidDefinition
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * OBD-70's `demo`-flavor "log every mapped PID" set. Module-boundary note: `:app`'s `main`
 * source set may depend only on `:core:model`/`:core:testing` — this file lives in `src/demo/`
 * specifically so it can be the ONE place demo-side logging composes its column set, mirroring
 * `src/demo/.../di/DataSourceModule.kt`'s own flavor split.
 *
 * [DASHBOARD_PIDS] + [RPM_PID_DEFINITION] is exactly the five ids
 * `ScenarioChannel`/`FakeVehicleDataSource`'s scripts ever emit (coolant, oilTemp, transTemp,
 * boost, rpm) — so a demo recording session's CSV always has real data in every column, never a
 * column that's structurally always-blank because the fake never emits that id.
 */
@Module
@InstallIn(SingletonComponent::class)
object LoggablePidsModule {
    @Provides
    @LoggablePids
    fun provideLoggablePids(): List<PidDefinition> = DASHBOARD_PIDS + RPM_PID_DEFINITION
}
