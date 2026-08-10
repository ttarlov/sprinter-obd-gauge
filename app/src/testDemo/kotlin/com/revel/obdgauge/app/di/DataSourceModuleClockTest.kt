package com.revel.obdgauge.app.di

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Regression coverage for the OBD-11 stale-clock fix (review round-1 M2): the DI-provided
 * clock must read ≈ [Instant.EPOCH] — the fake's virtual timeline — not wall-clock time.
 * Under the original `Clock.systemDefaultZone()` bug this reads ~56 years, so these tests
 * fail loudly if that wiring ever comes back.
 */
class DataSourceModuleClockTest {
    private val tolerance: Duration = Duration.ofSeconds(2)

    @Test
    fun `provided clock reads EPOCH at provisioning`() {
        val source = DataSourceModule.provideVehicleDataSource()
        val clock = DataSourceModule.provideClock(source)

        val age = Duration.between(Instant.EPOCH, clock.instant())

        assertTrue("expected ≈EPOCH, was $age past it", age.abs() < tolerance)
    }

    @Test
    fun `provided clock re-anchors to EPOCH on source restart`() {
        val source = DataSourceModule.provideVehicleDataSource()
        val clock = DataSourceModule.provideClock(source)

        Thread.sleep(RESTART_DELAY_MS)
        source.start(emptyList())
        val age = Duration.between(Instant.EPOCH, clock.instant())
        source.stop()

        assertTrue(
            "expected re-anchor ≈EPOCH after restart, was $age past it",
            age.toMillis() < RESTART_DELAY_MS,
        )
    }

    private companion object {
        const val RESTART_DELAY_MS = 250L
    }
}
