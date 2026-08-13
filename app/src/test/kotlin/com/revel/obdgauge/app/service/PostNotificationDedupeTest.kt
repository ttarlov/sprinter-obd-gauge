package com.revel.obdgauge.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B7 MINOR (round-1 review, reviews/OBD-24-round1.md): measured 22 posts / 3 distinct texts in
 * one run (~14,400 binder round-trips/hour at 2Hz) before this dedupe existed. Pure, direct test
 * of the decision itself — Robolectric can't observe "was `notify()` actually called N times,"
 * only "what's the current notification," so the seam is what's testable, per this file's
 * sibling `StartForegroundDegradingTest`.
 */
class PostNotificationDedupeTest {
    @Test
    fun `identical repeat state should not post again`() {
        val state = ServiceNotificationState(title = "OBD Gauge", text = "Connected — Coolant 190°F")

        assertFalse(shouldPostNotification(new = state, previouslyPosted = state))
    }

    @Test
    fun `structurally equal but distinct instances still count as identical`() {
        val a = ServiceNotificationState(title = "OBD Gauge", text = "Not connected")
        val b = ServiceNotificationState(title = "OBD Gauge", text = "Not connected")

        assertFalse(shouldPostNotification(new = b, previouslyPosted = a))
    }

    @Test
    fun `a genuine text change should post`() {
        val previous = ServiceNotificationState(title = "OBD Gauge", text = "Connected — Coolant 190°F")
        val next = ServiceNotificationState(title = "OBD Gauge", text = "Connected — Coolant 191°F")

        assertTrue(shouldPostNotification(new = next, previouslyPosted = previous))
    }

    @Test
    fun `the very first state (no prior post) should post`() {
        val first = ServiceNotificationState(title = "OBD Gauge", text = "Starting…")

        assertTrue(shouldPostNotification(new = first, previouslyPosted = null))
    }
}
