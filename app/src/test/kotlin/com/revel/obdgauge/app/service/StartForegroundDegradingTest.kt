package com.revel.obdgauge.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B1 BLOCKER belt-and-braces (round-1 review, reviews/OBD-24-round1.md): Robolectric's
 * `ShadowService` doesn't enforce the real `connectedDevice` FGS-type GRANTED-permission check,
 * so the fresh-install crash this guards against is invisible to `ObdConnectionServiceTest` — this
 * pins the degrade-not-crash *logic itself* directly, without Robolectric, since [startForegroundDegrading]
 * is plain Kotlin with no Android types in its own signature.
 */
class StartForegroundDegradingTest {
    @Test
    fun `falls back to the untyped call when the typed call throws SecurityException`() {
        var untypedCalled = false

        startForegroundDegrading(
            typed = { throw SecurityException("simulated missing connectedDevice FGS-type permission") },
            untyped = { untypedCalled = true },
        )

        assertEquals(true, untypedCalled)
    }

    @Test
    fun `does not call the fallback when the typed call succeeds`() {
        var typedCalled = false
        var untypedCalled = false

        startForegroundDegrading(
            typed = { typedCalled = true },
            untyped = { untypedCalled = true },
        )

        assertEquals(true, typedCalled)
        assertEquals(false, untypedCalled)
    }

    @Test
    fun `a non-SecurityException from the typed call is not swallowed`() {
        var thrown = false
        try {
            startForegroundDegrading(
                typed = { throw IllegalStateException("unrelated failure") },
                untyped = { error("must not be reached") },
            )
        } catch (e: IllegalStateException) {
            thrown = true
        }

        assertEquals(true, thrown)
    }
}
