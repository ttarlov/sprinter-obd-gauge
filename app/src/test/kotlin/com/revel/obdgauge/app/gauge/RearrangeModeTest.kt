package com.revel.obdgauge.app.gauge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OBD-67 round-1 review (minor): [autoscrollDelta]'s own KDoc advertises itself as pure and
 * unit-testable without a `LaunchedEffect`/frame clock — this is that test, locking the
 * edge-detection rule's three cases (top band, bottom band, neither) and the "nothing left to
 * scroll that direction" guards the issue's test plan calls for. [VIEWPORT] is picked large
 * relative to the band/speed constants (private to `RearrangeMode.kt`) so "near an edge" and
 * "away from either edge" don't need to know their exact pixel values.
 */
class RearrangeModeTest {
    @Test
    fun `finger near the top edge with room to scroll up returns a negative delta`() {
        val delta =
            autoscrollDelta(fingerScreenY = 0f, viewportHeightPx = VIEWPORT, scrollValue = 500, scrollMax = 1000)
        assertTrue("expected a negative (scroll-up) delta, was $delta", delta < 0f)
    }

    @Test
    fun `finger near the top edge but already scrolled to the top returns zero`() {
        val delta = autoscrollDelta(fingerScreenY = 0f, viewportHeightPx = VIEWPORT, scrollValue = 0, scrollMax = 1000)
        assertEquals(0f, delta, 0f)
    }

    @Test
    fun `finger near the bottom edge with room to scroll down returns a positive delta`() {
        val delta =
            autoscrollDelta(
                fingerScreenY = VIEWPORT - 1f,
                viewportHeightPx = VIEWPORT,
                scrollValue = 500,
                scrollMax = 1000,
            )
        assertTrue("expected a positive (scroll-down) delta, was $delta", delta > 0f)
    }

    @Test
    fun `finger near the bottom edge but already scrolled to the end returns zero`() {
        val delta =
            autoscrollDelta(
                fingerScreenY = VIEWPORT - 1f,
                viewportHeightPx = VIEWPORT,
                scrollValue = 1000,
                scrollMax = 1000,
            )
        assertEquals(0f, delta, 0f)
    }

    @Test
    fun `finger away from either edge returns zero regardless of scroll bounds`() {
        val delta =
            autoscrollDelta(
                fingerScreenY = VIEWPORT / 2,
                viewportHeightPx = VIEWPORT,
                scrollValue = 500,
                scrollMax = 1000,
            )
        assertEquals(0f, delta, 0f)
    }

    @Test
    fun `an unscrollable viewport never autoscrolls at either edge`() {
        assertEquals(0f, autoscrollDelta(0f, VIEWPORT, scrollValue = 0, scrollMax = 0), 0f)
        assertEquals(0f, autoscrollDelta(VIEWPORT - 1f, VIEWPORT, scrollValue = 0, scrollMax = 0), 0f)
    }

    private companion object {
        const val VIEWPORT = 2000f
    }
}
