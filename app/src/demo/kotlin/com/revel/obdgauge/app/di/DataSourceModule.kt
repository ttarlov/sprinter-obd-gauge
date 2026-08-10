package com.revel.obdgauge.app.di

import com.revel.obdgauge.app.datasource.RestartAnchoredDataSource
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
 * `demo` flavor wiring (OBD-12, formalizing the Sprint-1 default set up for OBD-10):
 * [VehicleDataSource] binds to [FakeVehicleDataSource] so the app is runnable end to end
 * against `:core:testing`, with zero Bluetooth permissions required. This file lives in
 * `src/demo/` (not `src/main/`) specifically so `:core:testing` never reaches the `prod`
 * flavor's runtime classpath — see the `demoImplementation(project(":core:testing"))`
 * dependency in `app/build.gradle.kts` and the HARD CONSTRAINT in `app/MODULE.md`.
 *
 * [Scenario.GRADE_CLIMB] is the default per OBD-10's ui-agent brief — it exercises the boost
 * arc sweep, which [Scenario.IDLE] barely moves.
 *
 * ### Clock fix (OBD-11)
 * `FakeVehicleDataSource` timestamps every reading on a *virtual* timeline starting at
 * `Instant.EPOCH` (see its KDoc) — never `Instant.now()`. The pre-OBD-11 wiring paired that
 * with `Clock.systemDefaultZone()` for the dashboard's "last seen Xs ago" math, which was only
 * consistent by accident (`GRADE_CLIMB` never goes stale — see the `app/MODULE.md` known
 * limitation this fixes). The moment a demo scenario disconnects, `now - reading.timestamp`
 * would span the ~56-year gap between 1970 and today and print a nonsense stale age.
 *
 * Fix chosen: an EPOCH-anchored offset clock, built once here at provisioning time so it reads
 * `Instant.EPOCH` "now" and advances at real wall-clock speed thereafter — the same shape of
 * clock `FakeVehicleDataSource` itself implicitly assumes (its replay coroutine `delay()`s in
 * real time between ticks, so its virtual EPOCH-based timestamps and real elapsed time move
 * 1:1). This keeps the fix entirely in DI wiring, with no `DashboardViewModel`/mapper changes.
 *
 * Because `WhileSubscribed(5_000)` in `DashboardViewModel` restarts the source on every
 * background/foreground round-trip — and `FakeVehicleDataSource.start()` resets its virtual
 * clock to `EPOCH` each time — the clock must re-anchor per `start()`, not once at
 * provisioning. [RestartAnchoredDataSource] records each start's wall-clock instant and
 * derives the clock from it (review round-1 M1; a one-shot `Clock.offset` anchor drifted by
 * the app's whole backgrounded lifetime).
 */
@Module
@InstallIn(SingletonComponent::class)
object DataSourceModule {
    @Provides
    @Singleton
    fun provideVehicleDataSource(): RestartAnchoredDataSource =
        RestartAnchoredDataSource(FakeVehicleDataSource(scenario = Scenario.GRADE_CLIMB))

    @Provides
    @Singleton
    fun bindVehicleDataSource(source: RestartAnchoredDataSource): VehicleDataSource = source

    @Provides
    @Singleton
    fun provideClock(source: RestartAnchoredDataSource): Clock = source.epochSinceStartClock()
}
