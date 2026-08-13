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
 * # Ownership (OBD-25 — this section IS the hazard resolution)
 *
 * `reviews/OBD-24-round1.md` closes with a hazard statement, reproduced into `issues/OBD-25.md`,
 * about this class re-issuing `start()` on an observed fall to [LinkState.Disconnected]. With a
 * real reconnect policy behind the source, that trigger defeats the policy's backoff on every
 * cycle — measured at 30 `start()`/60 s, three times Android's BLE scan throttle, at which point
 * the OS silently blanks scan results and the storm *prevents* the reconnect it is forcing. The
 * subtlety found at fix-round 2 makes it worse, not better: `RealVehicleDataSource.connection`
 * simply forwards `ObdLink.state`, which the ViewModel's `start`/`stop` does not touch at all —
 * so a `Disconnected` observed here says nothing whatsoever about whether the poll loop is
 * running. Inferring one from the other was never sound; it only looked sound because the demo
 * fake conflated them.
 *
 * The resolution splits the two things that were being confused, and gives each exactly one
 * owner:
 *
 * **The LINK is owned by `:core:ble`'s reconnect machine (OBD-23), exclusively.** It alone
 * decides when to retry, on what backoff, and when to give up. This class holds **no reference to
 * `ObdLink` or `LinkController`** — not as a matter of discipline but as a matter of what is on
 * its constructor — so it structurally cannot call `connect()`/`disconnect()` in reaction to
 * anything it observes. `LinkController`'s KDoc carries the same rule for the components that do
 * hold a link: connect/disconnect are user gestures, never state reactions.
 *
 * **The SOURCE (the poll loop) is owned by this class while the service intends to run.** It
 * asserts that intent through two triggers, and only these two:
 *
 * 1. [start] — the service came up. Publishes the intent via [PollKeepAlive].
 * 2. A transition **into** [LinkState.Ready] — the link just came up, or just came *back* after
 *    the reconnect machine did its job. This trigger is necessary because
 *    `RealVehicleDataSource`'s loop **parks** on a link drop (marks everything stale, emits
 *    `PollEvent.LinkDropped`, returns) and, per its own contract, "polling resumes when the owner
 *    calls `start()` again on a live link" — this class is that owner. It is also *safe* in a way
 *    the old trigger was not: `Ready` is a **success** edge. A failing scan, a backing-off retry,
 *    a terminal `PermissionDenied` — none of them ever produce `Ready`, so no failure can drive a
 *    restart, and the number of `start()` calls is bounded above by the number of successful
 *    connects, which is itself backoff-gated by the reconnect machine. That is the difference
 *    between re-asserting on somebody's success and fighting somebody's failure.
 *
 * Consequently **[LinkState.Disconnected] triggers nothing here at all**, and neither do
 * `Scanning`, `Connecting` or `Error`. The old Ready→Disconnected self-heal is deleted, which is
 * option (1) of the three the hazard statement offered.
 *
 * ### What replaced the deleted self-heal's actual job
 * That self-heal existed to undo `DashboardViewModel`'s `WhileSubscribed(5_000)` teardown, which
 * stops the shared source ~5 s after the screen turns off — the precise moment this service
 * exists to keep polling. Inferring that stop from link state was the mistake. It is now a
 * published fact instead: [PollKeepAlive] carries the service's intent, `DashboardViewModel`
 * consults it and declines to stop a source the service is keeping alive, and this class never
 * has to guess. See `PollKeepAlive`'s KDoc.
 *
 * ### Pinned by
 * `ConnectionServiceControllerTest`: a link cycling `Scanning → Connecting → Error →
 * Disconnected` forty times — the failing-scan/backing-off shape — leaves the `start()` count at
 * exactly 1; a drop *from* `Ready` (the case the deleted trigger fired on) also leaves it at 1;
 * and a full drop-and-recover ending in `Ready` adds exactly one restart, no matter how many
 * failed attempts the machine made on the way.
 */
class ConnectionServiceController(
    private val dataSource: VehicleDataSource,
    private val scope: CoroutineScope,
    private val keepAlive: PollKeepAlive = PollKeepAlive(),
    private val onStateChanged: (ServiceNotificationState) -> Unit,
) {
    private var job: Job? = null
    private var intendsToRun = false

    /** Idempotent: a second call while already running is a no-op, never a duplicate poll loop. */
    fun start() {
        if (intendsToRun) return
        intendsToRun = true
        keepAlive.acquire()
        dataSource.start(GAUGE_CATALOG)
        job =
            scope.launch {
                var wasReady = false
                combine(dataSource.readings, dataSource.connection) { readings, connection -> readings to connection }
                    .collect { (readings, connection) ->
                        val becameReady = connection == LinkState.Ready && !wasReady
                        wasReady = connection == LinkState.Ready
                        if (becameReady && intendsToRun) {
                            // The link just came up (or came back). RealVehicleDataSource's loop
                            // parked on the drop; restart it. Success edge only — see class KDoc.
                            dataSource.start(GAUGE_CATALOG)
                        }
                        onStateChanged(serviceNotificationState(connection, readings[PidIds.COOLANT]))
                    }
            }
    }

    /** Idempotent: a second call while already stopped is a no-op, never a duplicate underlying `stop()`. */
    fun stop() {
        if (!intendsToRun) return
        intendsToRun = false
        keepAlive.release()
        job?.cancel()
        job = null
        dataSource.stop()
    }
}
