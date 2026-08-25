package com.revel.obdgauge.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color.TRANSPARENT
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.revel.obdgauge.app.gauge.DashboardViewModel
import com.revel.obdgauge.app.gauge.GAUGE_CATALOG
import com.revel.obdgauge.app.gauge.GaugeDashboard
import com.revel.obdgauge.app.link.LinkController
import com.revel.obdgauge.app.service.ObdConnectionService
import com.revel.obdgauge.app.settings.SettingsRoute
import com.revel.obdgauge.app.sparkline.SparklinePoint
import com.revel.obdgauge.app.speed.SpeedSource
import com.revel.obdgauge.app.ui.theme.ObdGaugeTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Optional
import javax.inject.Inject

/**
 * Single-activity host. No `screenOrientation` lock in the manifest: a dash mount holds the
 * phone in landscape (the primary target, per [GaugeDashboard]'s layout), but the app must
 * not crash or clip if it ends up in portrait — see OBD-10 AC.
 *
 * [DashboardViewModel] is `@HiltViewModel`-annotated; `@AndroidEntryPoint` below patches this
 * Activity's default `ViewModelProvider.Factory` so the plain Compose `viewModel()` call
 * resolves it with its Hilt-injected constructor — no `hilt-navigation-compose` dependency
 * needed for a single-Activity app with no nav graph. OBD-21's settings screen is reached the
 * same way: a `mutableStateOf<Screen>` swap below, not a nav library.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /**
     * OBD-25: the connect entry point, present on `prod` and `Optional.empty()` on `demo` (which
     * has no link — see `LinkController`'s KDoc for why the binding is optional rather than
     * defaulted). Absent means the dashboard renders with no Connect button, exactly as before.
     */
    @Inject
    lateinit var linkController: Optional<LinkController>

    /**
     * OBD-61: the GPS speed provider, present on `prod` and `Optional.empty()` on `demo` (which
     * has no GPS — see `SpeedSource`'s KDoc). Started/stopped with this Activity's foreground
     * lifecycle ([onStart]/[onStop]) so GPS is never polled in the background, and only once
     * `ACCESS_FINE_LOCATION` is granted. Absent, or denied, means the correction feature stays
     * dormant (factor 1.0) — the Speed tile just shows the raw ECU speed.
     */
    @Inject
    lateinit var speedSource: Optional<SpeedSource>

    /**
     * OBD-25 / OBD-17: the BLE runtime permissions, requested **at the connect moment** rather
     * than at launch — same flow `ConsoleActivity` has run since OBD-19, and for the same reason:
     * a permission prompt makes sense when the user has just asked for a dongle, and is noise
     * when they have just opened a gauge. `:core:ble` reports what is missing
     * (`BleObdLink.missingPermissions`, API-level-aware via `BlePermissionPolicy`) and never
     * prompts itself; this is where the prompting lives.
     *
     * A denial is not an error state to render: `connect()` would have parked the link in
     * `LinkState.Error(PermissionDenied)` anyway, which the banner already says in words, so the
     * result handler simply does not connect. Registered as a field for the Activity Result
     * API's before-STARTED requirement, like the notification request below it.
     */
    private val requestBlePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.isNotEmpty() && grants.values.all { granted -> granted }) {
                connectNow()
            }
        }

    // B6 (round-1 review, reviews/OBD-24-round1.md): registered as a field, not inline in
    // onCreate — the Activity Result API requires this before the Activity reaches STARTED,
    // same constraint ConsoleActivity's own requestPermissions already follows. No-op result
    // handler: a denial doesn't change app behavior (ObdConnectionService.areNotificationsSafeToPost
    // already degrades to "service runs, notification silently doesn't post" either way) — this
    // launch exists only to give the OS prompt a chance to appear on first launch instead of
    // leaving OBD-24's persistent-notification AC permanently unreachable on a fresh 13+ install.
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * OBD-61: FINE_LOCATION for GPS speed. Requested once on first launch (like
     * [requestNotificationPermission]); on grant, the GPS provider is started immediately so the
     * feature is live this session rather than only after the next foreground. A denial is not an
     * error to render — the correction simply stays at factor 1.0.
     */
    private val requestLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startSpeedSource()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Explicit dark scrims for both bars: ObdGaugeTheme forces dark unconditionally (see
        // its KDoc), so the system bars must match rather than follow the device's own
        // light/dark setting — the no-arg enableEdgeToEdge() ties bar style to system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(TRANSPARENT),
        )
        requestNotificationPermissionIfNeeded()
        requestLocationPermissionIfNeeded()
        // OBD-24: starts the connection foreground service once per process, the moment the app
        // is opened — "the app's connection lifecycle," not "the Activity's." The service is
        // idempotent to a repeat start (ObdConnectionService.onStartCommand doesn't re-trigger
        // ConnectionServiceController.start(), which itself no-ops while already running), so
        // re-entering MainActivity (e.g. after a config change) never spins up a second poll loop.
        ContextCompat.startForegroundService(this, Intent(this, ObdConnectionService::class.java))
        // OBD-25 remembered-device fast path: if this phone has connected to a dongle before and
        // every permission is already granted, reconnect without the user tapping anything —
        // key-on, phone on the mount, gauges live. Silent no-op otherwise (including all of
        // `demo`), which is what leaves the banner's Connect action as the visible way in rather
        // than throwing a permission dialog at a launch. Guards live in
        // `BleLinkController.connectIfRemembered`; this is the only automatic connect in the app.
        lifecycleScope.launch { linkController.orElse(null)?.connectIfRemembered() }
        setContent {
            ObdGaugeTheme {
                val viewModel: DashboardViewModel = viewModel()
                // Lifecycle-aware: pauses collection (and, via DashboardViewModel's
                // WhileSubscribed producer, the underlying data source) when this Activity
                // isn't STARTED, rather than collecting for as long as the Activity exists.
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val gaugeOrder by viewModel.gaugeOrder.collectAsStateWithLifecycle()
                val gridLayoutsByColumns by viewModel.gridLayoutsByColumns.collectAsStateWithLifecycle()
                val thresholds by viewModel.thresholds.collectAsStateWithLifecycle()
                val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
                val recordingState by viewModel.recordingState.collectAsStateWithLifecycle()

                // OBD-21: FLAG_KEEP_SCREEN_ON follows the persisted setting live — no restart,
                // and it's cleared automatically the moment the setting flips back off.
                LaunchedEffect(keepScreenOn) {
                    if (keepScreenOn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                var showSettings by remember { mutableStateOf(false) }
                if (showSettings) {
                    SettingsRoute(onBack = { showSettings = false })
                } else {
                    // GAUGE_CATALOG (OBD-42), not just DASHBOARD_PIDS: a tile swapped to a
                    // non-core id (e.g. rpm) still needs a sparkline flow to pass down.
                    val sparklines: Map<String, StateFlow<List<SparklinePoint>>> =
                        remember(viewModel) { GAUGE_CATALOG.associate { it.id to viewModel.sparklineFlow(it.id) } }
                    GaugeDashboard(
                        uiState = uiState,
                        gaugeOrder = gaugeOrder,
                        gridLayoutsByColumns = gridLayoutsByColumns,
                        sparklines = sparklines,
                        thresholds = thresholds,
                        onSettingsClick = { showSettings = true },
                        onSwapGauge = viewModel::swapGauge,
                        onAddGauge = viewModel::addGauge,
                        onAddGaugeAt = viewModel::addGaugeAt,
                        onRemoveGauge = viewModel::removeGauge,
                        onResizeGauge = viewModel::resizeGauge,
                        onSetThreshold = viewModel::setThreshold,
                        onMoveGauge = viewModel::moveGauge,
                        // null on `demo` — no link, so no button (GaugeDashboard's KDoc).
                        onConnect = if (linkController.isPresent) ::requestConnect else null,
                        recordingState = recordingState,
                        onStartRecording = viewModel::startRecording,
                        onStopRecording = viewModel::stopRecording,
                    )
                }
            }
        }
    }

    /**
     * The banner's Connect/Retry tap: request exactly what's missing, then connect — or connect
     * straight away when nothing is missing. Mirrors `ConsoleActivity.requestConnect`.
     */
    private fun requestConnect() {
        val controller = linkController.orElse(null) ?: return
        val missing = controller.missingPermissions
        if (missing.isEmpty()) {
            connectNow()
        } else {
            requestBlePermissions.launch(missing.toTypedArray())
        }
    }

    /**
     * The one user-gesture-driven `connect()` in the app (the other caller is
     * `connectIfRemembered` at launch). Everything after this — retry cadence, backoff, giving up
     * — belongs to `:core:ble`'s reconnect machine; see `LinkController`'s ownership KDoc.
     */
    private fun connectNow() {
        lifecycleScope.launch { linkController.orElse(null)?.connect() }
    }

    /**
     * OBD-61: GPS speed updates follow the Activity's foreground lifecycle — started when it
     * becomes visible, stopped when it leaves. [startSpeedSource] is a no-op without the
     * permission or on `demo` (empty [speedSource]), and [SpeedSource.start]/`stop` are
     * idempotent, so a config-change round-trip never leaks a second listener.
     */
    override fun onStart() {
        super.onStart()
        startSpeedSource()
    }

    override fun onStop() {
        super.onStop()
        speedSource.orElse(null)?.stop()
    }

    /** Starts GPS speed updates iff the permission is granted and a provider exists. */
    private fun startSpeedSource() {
        if (hasLocationPermission()) {
            speedSource.orElse(null)?.start()
        }
    }

    /** OBD-61: requests FINE_LOCATION once, only when needed, not already granted, and available. */
    private fun requestLocationPermissionIfNeeded() {
        if (speedSource.isEmpty || hasLocationPermission()) return
        requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** B6: requests POST_NOTIFICATIONS once, only when it's both needed and not already granted. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val alreadyGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!alreadyGranted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
