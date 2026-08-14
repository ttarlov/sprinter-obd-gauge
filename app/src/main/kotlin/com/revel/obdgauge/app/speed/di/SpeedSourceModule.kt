package com.revel.obdgauge.app.speed.di

import com.revel.obdgauge.app.speed.SpeedSource
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Declares [SpeedSource] as an **optional** binding (OBD-61), so flavor-common code
 * (`MainActivity`) can inject `Optional<SpeedSource>` without either flavor being forced to
 * supply one — the same shape as `LinkControllerModule`.
 *
 * `prod` supplies it (`src/prod/.../speed/di/ProdSpeedModule.kt` binds the real
 * `GpsSpeedProvider`); `demo` supplies nothing, so every injection point there resolves to
 * `Optional.empty()` and the GPS speed-correction feature stays dormant (factor 1.0, raw fake
 * speed). `@BindsOptionalOf` rather than a default binding for the same reason
 * `LinkControllerModule` uses it: Hilt has no "default a flavor may override", so a default here
 * would collide with `prod`'s binding instead of being replaced by it.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SpeedSourceModule {
    @BindsOptionalOf
    abstract fun optionalSpeedSource(): SpeedSource
}
