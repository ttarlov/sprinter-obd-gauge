package com.revel.obdgauge.app.console.di

import com.revel.obdgauge.ble.BleObdLink
import com.revel.obdgauge.model.ObdLink
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * OBD-19: binds [ObdLink] to the real [BleObdLink] for debug builds only.
 *
 * Lives in `src/debug/` (a build-type source set, shared by the `demo` and `prod` flavors'
 * debug variants — see app/MODULE.md's "Build flavors"), so it compiles, and `:core:ble`
 * resolves, only when `debugImplementation(project(":core:ble"))` is on the classpath —
 * `demoRelease`/`prodRelease` see neither. `ConsoleActivity` is the only consumer today; the
 * real dashboard flow (`VehicleDataSource` built on top of this `ObdLink`) is OBD-25.
 *
 * `BleObdLink`'s own constructor dependencies (scanner, transport factory, remembered-device
 * store, dispatcher, ...) are satisfied by `:core:ble`'s own `di.BleModule` — also on the
 * classpath only via this same `debugImplementation`, so nothing further needs binding here.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DebugObdLinkModule {
    @Binds
    abstract fun bindObdLink(impl: BleObdLink): ObdLink
}
