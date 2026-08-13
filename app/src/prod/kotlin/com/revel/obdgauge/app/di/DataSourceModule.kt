package com.revel.obdgauge.app.di

import android.util.Log
import com.revel.obdgauge.app.datasource.DisplayUnitDataSource
import com.revel.obdgauge.ble.BleObdLink
import com.revel.obdgauge.model.VehicleDataSource
import com.revel.obdgauge.protocol.PollConfig
import com.revel.obdgauge.protocol.PollEvent
import com.revel.obdgauge.protocol.RealVehicleDataSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Clock
import javax.inject.Singleton

/**
 * `prod` flavor wiring — **the real chain** (OBD-25): [BleObdLink] → [RealVehicleDataSource] →
 * [DisplayUnitDataSource] → `DashboardViewModel`/`ObdConnectionService`.
 *
 * This replaces OBD-12's `StubVehicleDataSource` placeholder (deleted with this change); the
 * `demo` flavor's `FakeVehicleDataSource` binding in `src/demo/` is untouched and stays fake.
 *
 * ### Why the concrete `BleObdLink`, not the frozen `ObdLink` contract
 * OBD-19's `DebugObdLinkModule` already `@Binds ObdLink -> BleObdLink`, but it lives in
 * `src/debug/` and therefore does not exist in `prodRelease`. Injecting the concrete type here
 * needs no binding at all, works identically in both build types, and is what
 * `BleLinkController` needs anyway for `missingPermissions`/`rememberedDevice` (module-level
 * extras that are deliberately not on the frozen interface). `BleObdLink` is itself a
 * `@Singleton @Inject`-constructed type whose eight collaborators come from `:core:ble`'s own
 * `di.BleModule`, so nothing further needs binding.
 *
 * ### Lifecycle ownership, in one place
 * - The **link** is owned by `BleObdLink`'s reconnect machine (OBD-23). This module never calls
 *   `connect()`; the only callers are `LinkController`'s user-gesture entry points.
 * - The **poll loop** is owned by `ConnectionServiceController` while the foreground service
 *   intends to run, and by `DashboardViewModel`'s UI-gated subscription otherwise. See
 *   `ConnectionServiceController`'s KDoc for the full split and why neither one reacts to link
 *   state by (re)connecting.
 *
 * The scope handed to [RealVehicleDataSource] is a plain application-lifetime scope rather than
 * a service- or ViewModel-scoped one: the data source is a `@Singleton` shared by both owners,
 * and the poll loop's actual lifetime is governed by `start`/`stop` (start replaces, stop
 * cancels), not by scope death. `Dispatchers.Default` because the loop is a suspend/IO-bound
 * command pipeline that must never touch the main thread, and `:core:ble` already hops every
 * GATT interaction onto its own link dispatcher.
 *
 * [Clock.systemDefaultZone] is the same clock the data source stamps readings with and the
 * dashboard measures staleness against — one clock, so "last seen Xs ago" is honest (unlike the
 * `demo` flavor, which needs an EPOCH-anchored clock for its virtual timeline; see
 * `src/demo/.../di/DataSourceModule.kt`).
 */
@Module
@InstallIn(SingletonComponent::class)
object DataSourceModule {
    @Provides
    @Singleton
    fun provideVehicleDataSource(
        link: BleObdLink,
        clock: Clock,
    ): VehicleDataSource {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val real =
            RealVehicleDataSource(link = link, scope = scope, config = PollConfig(), clock = clock, onEvent = ::log)
        return DisplayUnitDataSource(real, scope)
    }

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemDefaultZone()

    /**
     * Typed protocol diagnostics ([PollEvent]) to logcat, and nowhere else.
     *
     * These are the events that explain a silent gauge — `ChannelAvailabilityChanged` is how the
     * poll loop announces, once per session and before a single command goes out, that boost has
     * no MAP to compute from on this van and that `transTemp`'s decode was falsified. Surfacing
     * them in the UI is a separate piece of work (there is no contract carrying them today);
     * dropping them entirely would leave a van-side "why is boost blank" with no answer at all,
     * so they go to the log the OBD-48 traffic tap already writes to.
     */
    private fun log(event: PollEvent) {
        Log.i("ObdPoll", event.toString())
    }
}
