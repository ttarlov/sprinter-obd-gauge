package com.revel.obdgauge.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OBD-78 round-3: the two fixes that live in the input computation, not the decision — the
 * session-relative staleness clock (M1) and the sustained-health floor (B3). All in ms of virtual
 * elapsed-realtime; a healthy dongle samples every few hundred ms, a wedge produces nothing.
 */
class WedgeInputsTest {
    private val stale = 30_000L
    private val sustained = 90_000L

    private fun inputs(
        ready: Boolean = true,
        readySince: Long = 0L,
        lastData: Long = 0L,
        healthySince: Long = 0L,
        now: Long,
    ) = wedgeInputs(
        linkReady = ready,
        readySinceMillis = readySince,
        lastDataAtMillis = lastData,
        dataHealthySinceMillis = healthySince,
        nowMillis = now,
        staleAfterMillis = stale,
        sustainedAfterMillis = sustained,
    )

    @Test
    fun `M1 - a fresh session is immune during its own quiet window even though the global stamp is old`() {
        // Became Ready at 100_000 after a long outage; last real data was ages ago (1_000).
        // 29 s into the session, no data yet → NOT stale (session-relative), though now-lastData is huge.
        val r = inputs(readySince = 100_000L, lastData = 1_000L, now = 100_000L + 29_000L)
        assertFalse("session-relative clock keeps a fresh link immune", r.stale)
    }

    @Test
    fun `a session that has been Ready and silent past the window is stale`() {
        val r = inputs(readySince = 100_000L, lastData = 1_000L, now = 100_000L + 31_000L)
        assertTrue(r.stale)
    }

    @Test
    fun `a link that is not Ready is never stale`() {
        val r = inputs(ready = false, readySince = 100_000L, lastData = 1_000L, now = 100_000L + 60_000L)
        assertFalse(r.stale)
    }

    @Test
    fun `B3 - a single sample starts the health clock but cannot sustain it past one stale window`() {
        // A sample lands at 100_000; health clock starts.
        val landed = inputs(readySince = 50_000L, lastData = 100_000L, healthySince = 0L, now = 100_000L)
        assertEquals(100_000L, landed.dataHealthySinceMillis)
        assertFalse("one sample is not yet sustained", landed.sustainedHealthy)
        // No more data: by the time the stale window elapses, dataFlowing is false → health resets to
        // 0, long before the 90 s sustained threshold. So one sample can NEVER make it sustained.
        val aged = inputs(readySince = 50_000L, lastData = 100_000L, healthySince = 100_000L, now = 100_000L + stale)
        assertEquals(0L, aged.dataHealthySinceMillis)
        assertFalse(aged.sustainedHealthy)
    }

    @Test
    fun `continuous data for the sustained window is sustainedHealthy`() {
        // Data still fresh (lastData == now), health clock started 90 s ago and carried forward.
        val now = 500_000L
        val r = inputs(readySince = 400_000L, lastData = now, healthySince = now - sustained, now = now)
        assertTrue(r.sustainedHealthy)
        assertEquals(now - sustained, r.dataHealthySinceMillis) // carried, not reset
    }

    @Test
    fun `the PRODUCTION sustained threshold is strictly longer than the stale window - the B3 invariant`() {
        // round-4 test #1: bind the load-bearing invariant to the REAL constants, not the test's own —
        // so shrinking SUSTAINED_HEALTH_MILLIS below WEDGE_RECONNECT_STALE_MILLIS fails here.
        assertTrue(
            "SUSTAINED_HEALTH_MILLIS must exceed WEDGE_RECONNECT_STALE_MILLIS or a single sample could re-arm the latch",
            SUSTAINED_HEALTH_MILLIS > WEDGE_RECONNECT_STALE_MILLIS,
        )
    }
}
