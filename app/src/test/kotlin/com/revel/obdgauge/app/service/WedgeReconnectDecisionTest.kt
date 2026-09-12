package com.revel.obdgauge.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * OBD-78: the pure wedge-recovery decision. The failure it fixes — a dongle that stops answering
 * while the BLE link stays `Ready`, so `:core:ble`'s drop-only auto-reconnect never fires and the
 * poll loop parks with dead gauges — is only detectable one layer up, as "Ready + no fresh data"
 * (see `reviews/OBD-78-round2.md`). These pin the properties three review rounds demanded: recover a
 * real wedge once, never storm, never tear down a healthy or freshly-recovered link, and re-arm only
 * on *sustained* data so an answers-once-then-wedges dongle can't loop (round-3 B3).
 */
class WedgeReconnectDecisionTest {
    private fun decide(
        available: Boolean = true,
        ready: Boolean = true,
        stale: Boolean = true,
        sustainedHealthy: Boolean = false,
        pending: Boolean = false,
    ) = wedgeReconnectDecision(
        linkAvailable = available,
        linkReady = ready,
        stale = stale,
        sustainedHealthy = sustainedHealthy,
        pending = pending,
    )

    @Test
    fun `a Ready link gone stale forces a reconnect and latches`() {
        assertEquals(WedgeDecision(forceReconnect = true, pending = true), decide(stale = true, pending = false))
    }

    @Test
    fun `once forced, a still-stale link does not force again - no storm`() {
        assertEquals(WedgeDecision(forceReconnect = false, pending = true), decide(stale = true, pending = true))
    }

    @Test
    fun `a single sample (recovering but not yet sustained) does NOT re-arm the latch - round-3 B3`() {
        // Not stale (a sample just landed) but not sustainedHealthy yet, and already forced: hold.
        assertEquals(
            WedgeDecision(forceReconnect = false, pending = true),
            decide(stale = false, sustainedHealthy = false, pending = true),
        )
    }

    @Test
    fun `only SUSTAINED data clears the latch and re-arms`() {
        assertEquals(
            WedgeDecision(forceReconnect = false, pending = false),
            decide(sustainedHealthy = true, pending = true),
        )
    }

    @Test
    fun `a link not Ready never forces and keeps the latch across its own reconnect's Connecting phase`() {
        assertEquals(WedgeDecision(forceReconnect = false, pending = true), decide(ready = false, pending = true))
        assertEquals(WedgeDecision(forceReconnect = false, pending = false), decide(ready = false, pending = false))
    }

    @Test
    fun `no link available never forces`() {
        assertEquals(WedgeDecision(forceReconnect = false, pending = false), decide(available = false, pending = false))
        assertEquals(WedgeDecision(forceReconnect = false, pending = true), decide(available = false, pending = true))
    }

    @Test
    fun `a fresh session with its own quiet window - not yet stale - is left alone (round-3 M1)`() {
        // The session-relative clock keeps `stale` false during the immunity window; nothing fires.
        assertEquals(
            WedgeDecision(forceReconnect = false, pending = false),
            decide(stale = false, sustainedHealthy = false, pending = false),
        )
    }

    @Test
    fun `the B3 loop is closed - wedge fires once, a single sample cannot re-arm it`() {
        // Ready + stale, first time → force + latch.
        var d = decide(ready = true, stale = true, sustainedHealthy = false, pending = false)
        assertEquals(WedgeDecision(true, true), d)
        // Forced reconnect goes through Connecting (not Ready): latch survives.
        d = decide(ready = false, pending = d.pending)
        assertEquals(WedgeDecision(false, true), d)
        // Back to Ready, ONE sample lands (not stale, not yet sustained): latch HOLDS — no re-fire.
        d = decide(ready = true, stale = false, sustainedHealthy = false, pending = d.pending)
        assertEquals(WedgeDecision(false, true), d)
        // The dongle wedges again (stale) — still latched, so NO storm (the B3 fix).
        d = decide(ready = true, stale = true, sustainedHealthy = false, pending = d.pending)
        assertEquals(WedgeDecision(false, true), d)
    }

    @Test
    fun `a genuinely recovered link (sustained) re-arms, so a later real wedge is handled`() {
        // Latched from a prior force; data sustains → re-arm.
        var d = decide(ready = true, sustainedHealthy = true, pending = true)
        assertEquals(WedgeDecision(false, false), d)
        // A later, separate wedge fires again.
        d = decide(ready = true, stale = true, sustainedHealthy = false, pending = d.pending)
        assertEquals(WedgeDecision(true, true), d)
    }
}
