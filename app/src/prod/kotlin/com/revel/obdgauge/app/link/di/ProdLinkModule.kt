package com.revel.obdgauge.app.link.di

import com.revel.obdgauge.app.link.BleLinkController
import com.revel.obdgauge.app.link.LinkController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * OBD-25: satisfies `src/main/`'s optional [LinkController] binding on the `prod` flavor, so
 * flavor-common UI (`MainActivity`, the OBD-11 connection banner) gets a real connect affordance
 * while `demo` keeps resolving to `Optional.empty()`.
 *
 * Lives in `src/prod/` — not `src/debug/` like OBD-19's `DebugObdLinkModule` — because `prod`
 * needs `:core:ble` in **release** builds too, which is what the `prodImplementation`
 * dependency in `app/build.gradle.kts` adds. `demoRelease` still sees no `:core:ble` at all.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProdLinkModule {
    @Binds
    abstract fun bindLinkController(impl: BleLinkController): LinkController
}
