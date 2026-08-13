package com.revel.obdgauge.app.link.di

import com.revel.obdgauge.app.link.LinkController
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Declares [LinkController] as an **optional** binding, so flavor-common code can inject
 * `Optional<LinkController>` without either flavor being forced to supply one.
 *
 * `prod` supplies it (`src/prod/.../link/di/ProdLinkModule.kt`); `demo` supplies nothing, and
 * every injection point there resolves to `Optional.empty()`. That is the whole reason this is
 * `@BindsOptionalOf` rather than a plain default binding in this module: Hilt has no notion of
 * "a default that a flavor may override", so a default here would collide with `prod`'s binding
 * instead of being replaced by it — and the alternative (a no-op binding module added to
 * `src/demo/`) would mean touching the `demo` flavor's DI graph for a feature the `demo` flavor
 * does not have. See `LinkController.None` for the shape a demo binding *would* take if one is
 * ever wanted.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LinkControllerModule {
    @BindsOptionalOf
    abstract fun optionalLinkController(): LinkController
}
