package com.revel.obdgauge.app.service

import com.revel.obdgauge.model.Reading

/**
 * OBD-69: apply one [VehicleDataSource.readings] emission to the idle signal. A non-empty map is
 * data — stamp [tracker] at [nowMillis] and [refreshWakeLock] (re-hold the wake lock, so a live
 * screen-off session stays awake). An empty map (session start/clear, or a never-connected
 * forced-stale publish) is NOT data: return without touching either, so the stamp keeps ageing and
 * the watchdog can still fire on a session that never connects.
 *
 * This is the load-bearing non-empty guard, pulled out of the collector so both halves are
 * unit-testable directly (`IdleWatchdogTest`) rather than only incidentally through the demo fake.
 */
internal fun applyReadingsToIdleSignal(
    readings: Map<String, Reading>,
    nowMillis: Long,
    tracker: IdleActivityTracker,
    refreshWakeLock: () -> Unit,
) {
    if (readings.isEmpty()) return
    tracker.record(nowMillis)
    refreshWakeLock()
}

/**
 * OBD-69 idle battery-saver — the timing decision behind [ObdConnectionService]'s idle watchdog.
 *
 * Kept a pure top-level function (fed `now` rather than reading a clock itself) so every case is
 * unit-tested with plain values and no Robolectric — the same "test the seam directly" split as
 * [startForegroundDegrading] and [shouldPostNotification] in `ObdConnectionService.kt`. The
 * watchdog coroutine that drives it lives in the service and does nothing but call this on a
 * coarse interval; all the timing lives here.
 *
 * @param lastDataAtMillis when the dongle last produced a data sample (see [IdleActivityTracker]),
 *   in the same monotonic domain as [nowMillis] (`SystemClock.elapsedRealtime`).
 * @param nowMillis the current instant, same domain.
 * @param idleTimeoutMillis how long with no data before the service should stop.
 * @return `true` once the gap has reached [idleTimeoutMillis], so the service can stop, release its
 *   wake lock, and let the device Doze. The boundary is inclusive (`>=`): at exactly the timeout it
 *   stops — one fewer coarse tick spent holding the CPU awake for nothing.
 */
internal fun shouldStopForIdle(
    lastDataAtMillis: Long,
    nowMillis: Long,
    idleTimeoutMillis: Long,
): Boolean = nowMillis - lastDataAtMillis >= idleTimeoutMillis

/**
 * The single mutable timestamp the idle watchdog reads: when the dongle last produced a data
 * sample. Held here rather than as a field on the Android [ObdConnectionService] so the
 * reset-on-data behaviour is unit-testable without Robolectric.
 *
 * The service writes it from its readings collector on every non-empty emission, and initialises
 * it to service-start time — so a session that never connects still counts from start toward the
 * timeout (the "app left open, vehicle off, dongle never answers" case). Written from a coroutine
 * and read from another, so the field is `@Volatile`.
 */
internal class IdleActivityTracker {
    @Volatile
    var lastDataAtMillis: Long = 0L
        private set

    /** Record that data (or the service's own start) happened at [nowMillis]. */
    fun record(nowMillis: Long) {
        lastDataAtMillis = nowMillis
    }
}
