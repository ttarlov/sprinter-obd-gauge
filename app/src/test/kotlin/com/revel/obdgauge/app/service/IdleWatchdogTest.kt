package com.revel.obdgauge.app.service

import com.revel.obdgauge.model.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Plain-JVM tests for OBD-69's idle decision and its one mutable input — no Android, no
 * Robolectric, a `Long` standing in for the clock (`SystemClock.elapsedRealtime` in production).
 * The service wiring (that a `true` here reaches `stopSelf`/releases the wake lock) is pinned
 * separately in `ObdConnectionServiceTest`; this file owns the timing.
 */
class IdleWatchdogTest {
    /** [shouldStopForIdle] against the fixed [TIMEOUT], measuring `now` from [START]. */
    private fun idleAt(nowMillis: Long): Boolean = shouldStopForIdle(START, nowMillis, TIMEOUT)

    @Test
    fun `stops when no data for longer than the timeout`() {
        assertTrue(idleAt(START + TIMEOUT + 1))
    }

    @Test
    fun `keeps running when data arrived recently`() {
        assertFalse(idleAt(START + 1))
    }

    @Test
    fun `boundary is inclusive - stops at exactly the timeout`() {
        assertTrue(idleAt(START + TIMEOUT))
    }

    @Test
    fun `one millisecond short of the timeout keeps running`() {
        assertFalse(idleAt(START + TIMEOUT - 1))
    }

    @Test
    fun `never-any-data counts from the service-start stamp toward the timeout`() {
        // The tracker is initialised to service-start time, so "no sample ever arrived" is just
        // lastData == start: it still trips once now reaches start + timeout.
        val tracker = IdleActivityTracker()
        tracker.record(START)

        assertFalse(shouldStopForIdle(tracker.lastDataAtMillis, START + TIMEOUT - 1, TIMEOUT))
        assertTrue(shouldStopForIdle(tracker.lastDataAtMillis, START + TIMEOUT, TIMEOUT))
    }

    @Test
    fun `a new data sample resets the timer`() {
        val tracker = IdleActivityTracker()
        tracker.record(START)
        // Almost timed out...
        assertTrue(shouldStopForIdle(tracker.lastDataAtMillis, START + TIMEOUT, TIMEOUT))

        // ...then data arrives, moving the stamp forward: the same `now` is no longer idle.
        tracker.record(START + TIMEOUT)
        assertFalse(shouldStopForIdle(tracker.lastDataAtMillis, START + TIMEOUT, TIMEOUT))
    }

    @Test
    fun `tracker starts at zero and record overwrites it`() {
        val tracker = IdleActivityTracker()
        assertEquals(0L, tracker.lastDataAtMillis)

        tracker.record(START)
        assertEquals(START, tracker.lastDataAtMillis)
    }

    // ---- OBD-69 round-1 major: the load-bearing non-empty guard, tested directly ----

    @Test
    fun `an empty readings emission does not stamp or refresh the wake lock`() {
        val tracker = IdleActivityTracker()
        tracker.record(START)
        var refreshed = false

        applyReadingsToIdleSignal(emptyMap(), START + TIMEOUT, tracker) { refreshed = true }

        // The stamp is untouched — a never-connected session's emptyMap publishes must NOT reset
        // the timer, or the watchdog would never idle-stop it.
        assertEquals(START, tracker.lastDataAtMillis)
        assertFalse(refreshed)
    }

    @Test
    fun `a non-empty readings emission stamps and refreshes the wake lock`() {
        val tracker = IdleActivityTracker()
        tracker.record(START)
        var refreshed = false
        val readings = mapOf("coolant" to Reading("coolant", 1.0, Instant.EPOCH, stale = false))

        applyReadingsToIdleSignal(readings, START + TIMEOUT, tracker) { refreshed = true }

        assertEquals(START + TIMEOUT, tracker.lastDataAtMillis)
        assertTrue(refreshed)
    }

    private companion object {
        const val START = 1_000_000L
        const val TIMEOUT = 20L * 60 * 1000
    }
}
