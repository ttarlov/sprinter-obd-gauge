package com.revel.obdgauge.app

import android.graphics.Color.TRANSPARENT
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.revel.obdgauge.app.gauge.DashboardViewModel
import com.revel.obdgauge.app.gauge.GaugeDashboard
import com.revel.obdgauge.app.ui.theme.ObdGaugeTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity host. No `screenOrientation` lock in the manifest: a dash mount holds the
 * phone in landscape (the primary target, per [GaugeDashboard]'s layout), but the app must
 * not crash or clip if it ends up in portrait — see OBD-10 AC.
 *
 * [DashboardViewModel] is `@HiltViewModel`-annotated; `@AndroidEntryPoint` below patches this
 * Activity's default `ViewModelProvider.Factory` so the plain Compose `viewModel()` call
 * resolves it with its Hilt-injected constructor — no `hilt-navigation-compose` dependency
 * needed for a single-Activity app with no nav graph.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Explicit dark scrims for both bars: ObdGaugeTheme forces dark unconditionally (see
        // its KDoc), so the system bars must match rather than follow the device's own
        // light/dark setting — the no-arg enableEdgeToEdge() ties bar style to system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(TRANSPARENT),
        )
        setContent {
            ObdGaugeTheme {
                val viewModel: DashboardViewModel = viewModel()
                // Lifecycle-aware: pauses collection (and, via DashboardViewModel's
                // WhileSubscribed producer, the underlying data source) when this Activity
                // isn't STARTED, rather than collecting for as long as the Activity exists.
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                GaugeDashboard(uiState = uiState)
            }
        }
    }
}
