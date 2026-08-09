package com.revel.obdgauge.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point and Hilt component root.
 *
 * Every `@Inject`-annotated binding in the app (Compose UI, protocol layer wiring in
 * `:core:protocol`, and `ObdLink` implementations in `:core:ble`) is reachable from the
 * graph rooted here. Per DECISIONS.md D2, Hilt is the only DI mechanism in this codebase.
 */
@HiltAndroidApp
class ObdGaugeApplication : Application()
