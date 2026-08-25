package com.revel.obdgauge.app.recording.di

import javax.inject.Qualifier

/**
 * OBD-70: marks the injected "log every mapped PID" set — see `app/src/demo/.../di/
 * LoggablePidsModule.kt` and `app/src/prod/.../di/LoggablePidsModule.kt`. A qualifier (not a bare
 * `List<PidDefinition>` binding) so this injection point can never be accidentally satisfied by
 * some unrelated future `List<PidDefinition>` provider.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class LoggablePids
