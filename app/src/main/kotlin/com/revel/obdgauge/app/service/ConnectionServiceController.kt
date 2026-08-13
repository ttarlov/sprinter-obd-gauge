package com.revel.obdgauge.app.service

import com.revel.obdgauge.app.gauge.GAUGE_CATALOG
import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.VehicleDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Pure keep-alive coordinator for [ObdConnectionService] — no Android/Hilt types, so it's
 * testable with plain JUnit + coroutines-test against a hand-built [VehicleDataSource] double,
 * matching this codebase's established split between Android glue and pure logic (see
 * `:core:ble`'s `ConsoleSession`/`ConsoleViewModel` for the same shape, or
 * `gauge/DashboardUiState.kt`'s `toDashboardUiState` vs `DashboardViewModel` in `:app` itself).
 *
 * ### Why this exists: screen-off keep-alive vs. `DashboardViewModel`'s UI-gated lifecycle
 * [DashboardViewModel][com.revel.obdgauge.app.gauge.DashboardViewModel] starts/stops the shared
 * [VehicleDataSource] singleton itself, gated on whether its `uiState` has a live Compose
 * collector (`SharingStarted.WhileSubscribed(5_000)` — pinned by `DashboardViewModelTest`'s "producer
 * starts only once uiState has a collector" case, deliberately left untouched by this file).
 * That means ~5s after the screen turns off (or the app backgrounds), the ViewModel's own
 * subscription lapses and it calls `dataSource.stop()` — precisely the moment OBD-24 needs
 * polling to *keep* running. Reworking that reviewed, pinned lifecycle so only the service owns
 * start/stop would be a far bigger, riskier change than this issue's scope (it would ripple
 * through every test that asserts `DashboardViewModel`'s own start/stop call counts). Instead,
 * this controller runs its own independent, non-UI-gated collector over
 * [VehicleDataSource.connection] for as long as the service is alive: if it observes a
 * **transition into** [LinkState.Disconnected] from [LinkState.Ready] — it immediately calls
 * [VehicleDataSource.start] again.
 *
 * ### Round-1 review fix (B2 MAJOR): the trigger was `!= Disconnected`, now it's `== Ready`
 * The original "was connecting/connected" trigger (`previous != Disconnected`) counted
 * [LinkState.Scanning] as "was connected" too. Reviewer-measured consequence against a data
 * source stuck cycling Scanning→Disconnected (a failing scan, the shape a real dongle-not-found
 * loop takes): **30 unbounded `start()` calls in 60s** against a 2s scan — 15 scans/30s vs
 * Android's ~5 scans/30s BLE throttle, so the OS silently blanks scan results and the storm
 * itself *prevents* the reconnect it's trying to force. Narrowing the trigger to "we were
 * specifically [LinkState.Ready] and are now `Disconnected`" is the smallest fix that matches
 * this class's actual, stated intent — re-assert against `DashboardViewModel`'s *own* UI-gated
 * `stop()`, which by construction only ever fires while the link was fully `Ready` (its
 * `combine()` only starts collecting once `uiState` has a subscriber, and — as of OBD-25 not
 * existing yet — the demo/prod data sources this controller runs against never emit `Scanning`
 * or `Connecting` immediately before a UI-driven `Disconnected`). `Scanning`/`Connecting`/
 * [LinkState.Error] no longer count as "was connected" at all, so a failing-scan producer (or
 * anything else that cycles through those states) can no longer trigger a restart, unbounded or
 * otherwise — see `ConnectionServiceControllerTest`'s storm regression test.
 *
 * This is deliberately narrow: it re-asserts against the *known* UI-gated stop, nothing more.
 * A real transport-level drop (BLE out of range, dongle powered off) is [LinkState.Error] or a
 * stall, not a clean `Disconnected`, and reacting to those is OBD-23's reconnect state machine's
 * job, not this controller's — conflating the two here would duplicate policy this codebase is
 * building elsewhere on purpose. **OBD-25 hazard, unresolved by this narrowing alone:** once a
 * real reconnect policy exists behind this same [VehicleDataSource], if THAT policy ever parks at
 * a clean `Disconnected` between its own retry attempts (rather than staying in `Scanning`/
 * `Error`), this controller would still immediately restart it from a Ready-then-dropped state,
 * defeating that policy's backoff. OBD-25 must resolve ownership explicitly (delete this
 * self-heal, narrow further, or gate it behind the reconnect policy's own budget) — see
 * `reviews/OBD-24-round1.md`'s hazard statement, carried forward verbatim as part of that issue's
 * brief.
 */
class ConnectionServiceController(
    private val dataSource: VehicleDataSource,
    private val scope: CoroutineScope,
    private val onStateChanged: (ServiceNotificationState) -> Unit,
) {
    private var job: Job? = null
    private var intendsToRun = false

    /** Idempotent: a second call while already running is a no-op, never a duplicate poll loop. */
    fun start() {
        if (intendsToRun) return
        intendsToRun = true
        dataSource.start(GAUGE_CATALOG)
        job =
            scope.launch {
                var wasReady = false
                combine(dataSource.readings, dataSource.connection) { readings, connection -> readings to connection }
                    .collect { (readings, connection) ->
                        if (wasReady && connection == LinkState.Disconnected && intendsToRun) {
                            // Self-heal against DashboardViewModel's UI-gated stop() — see class KDoc.
                            dataSource.start(GAUGE_CATALOG)
                        }
                        wasReady = connection == LinkState.Ready
                        onStateChanged(serviceNotificationState(connection, readings[PidIds.COOLANT]))
                    }
            }
    }

    /** Idempotent: a second call while already stopped is a no-op, never a duplicate underlying `stop()`. */
    fun stop() {
        if (!intendsToRun) return
        intendsToRun = false
        job?.cancel()
        job = null
        dataSource.stop()
    }
}
