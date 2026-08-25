package com.revel.obdgauge.app.recording.di

import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.protocol.PidCatalog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * OBD-70's `prod`-flavor "log every mapped PID" set — [PidCatalog.definitions]: the standard
 * mode-01 set, the Mercedes mode-22 hypotheses, the trans KWP record channel, and computed boost.
 *
 * This is the **one sanctioned `:core:protocol` reference** the recorder feature makes, and it is
 * deliberately confined to `src/prod/`, which already depends on `:core:protocol` and already
 * reads `PidCatalog.definitions` (`app/src/prod/.../datasource/DisplayUnitDataSource.kt`) — `:app`
 * `main`'s recorder engine (`Recorder`, `CsvEngine.kt`) only ever sees the resulting
 * `List<PidDefinition>` via the `@LoggablePids` qualifier, never `PidCatalog` itself. See
 * `issues/OBD-70.md`'s "Module boundary — CRITICAL" section for why this split exists.
 */
@Module
@InstallIn(SingletonComponent::class)
object LoggablePidsModule {
    @Provides
    @LoggablePids
    fun provideLoggablePids(): List<PidDefinition> = PidCatalog.definitions
}
