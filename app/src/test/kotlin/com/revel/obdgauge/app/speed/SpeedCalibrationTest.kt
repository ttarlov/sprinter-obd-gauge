package com.revel.obdgauge.app.speed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OBD-61's correctness core, tested exhaustively: every quality gate rejects what it should, the
 * median math is right (odd/even), warm-up returns the default, and the ring buffer stays
 * bounded. Pure JVM — no Android, no coroutines.
 */
class SpeedCalibrationTest {
    // A ratio that passes the clamp (≈ the van's real +10% tire error): gps 1.10 × ecu.
    // ecu 100 km/h, gps 110 km/h → ratio 1.10, both above MIN_SPEED, no accuracy problem.
    private val goodEcu = 100.0
    private val goodGps = 110.0
    private val goodRatio = 1.10

    private fun accept(
        state: SpeedCalibrationState,
        ecu: Double = goodEcu,
        gps: Double = goodGps,
        accuracy: Double? = 1.0,
    ) = SpeedCalibration.accept(state, ecu, gps, accuracy)

    /** Feed [n] identical good samples, threading state; steady speed so the steadiness gate passes. */
    private fun steadyRun(
        n: Int,
        ecu: Double = goodEcu,
        gps: Double = goodGps,
        accuracy: Double? = 1.0,
    ): SpeedCalibrationState {
        var state = SpeedCalibrationState()
        repeat(n) { state = accept(state, ecu, gps, accuracy) }
        return state
    }

    // ---- warm-up / default ----

    @Test
    fun `below MIN_SAMPLES the factor is the passed-in default`() {
        val state = steadyRun(SpeedCalibration.MIN_SAMPLES - 1)
        assertEquals(SpeedCalibration.MIN_SAMPLES - 1, state.ratios.size)
        assertEquals(1.234, state.factor(1.234), 0.0)
    }

    @Test
    fun `empty state returns the default`() {
        assertEquals(1.0, SpeedCalibrationState().factor(1.0), 0.0)
        assertEquals(0.97, SpeedCalibrationState().factor(0.97), 0.0)
    }

    @Test
    fun `at exactly MIN_SAMPLES the median takes over from the default`() {
        val state = steadyRun(SpeedCalibration.MIN_SAMPLES)
        assertEquals(SpeedCalibration.MIN_SAMPLES, state.ratios.size)
        // Default is ignored now — all samples are goodRatio.
        assertEquals(goodRatio, state.factor(999.0), 1e-9)
    }

    // ---- median math ----

    @Test
    fun `odd-sized buffer median is the middle value`() {
        // Ratios 1.00, 1.05, 1.10, 1.15, 1.20 (steady enough: ecu constant, gps varies).
        var state = SpeedCalibrationState()
        listOf(100.0, 105.0, 110.0, 115.0, 120.0).forEach { gps ->
            state = accept(state, ecu = goodEcu, gps = gps)
        }
        // 5 ratios: 1.00,1.05,1.10,1.15,1.20 → median 1.10.
        assertEquals(1.10, state.factor(1.0), 1e-9)
    }

    @Test
    fun `even-sized buffer median averages the two middle values`() {
        // 6 ratios: 1.00,1.05,1.10,1.15,1.20,1.25 → middle two 1.10 & 1.15 → 1.125.
        var state = SpeedCalibrationState()
        listOf(100.0, 105.0, 110.0, 115.0, 120.0, 125.0).forEach { gps ->
            state = accept(state, ecu = goodEcu, gps = gps)
        }
        assertEquals(6, state.ratios.size)
        assertEquals(1.125, state.factor(1.0), 1e-9)
    }

    @Test
    fun `median is order-independent and ignores one outlier at the clamp edge`() {
        // A hi outlier at the 1.25 clamp edge among otherwise-1.10 samples must not move the median.
        var state = SpeedCalibrationState()
        // 5 samples: 1.10, 1.10, 1.25, 1.10, 1.10 → sorted 1.10,1.10,1.10,1.10,1.25 → median 1.10.
        listOf(110.0, 110.0, 125.0, 110.0, 110.0).forEach { gps ->
            state = accept(state, ecu = goodEcu, gps = gps)
        }
        assertEquals(1.10, state.factor(1.0), 1e-9)
    }

    // ---- gate: minimum speed ----

    @Test
    fun `rejects when ECU speed is below MIN_SPEED`() {
        // ecu 39, gps 43 (both would-be plausible ratio) but ecu under the floor.
        val state = accept(SpeedCalibrationState(), ecu = 39.0, gps = 43.0)
        assertTrue(state.ratios.isEmpty())
    }

    @Test
    fun `rejects when GPS speed is below MIN_SPEED`() {
        val state = accept(SpeedCalibrationState(), ecu = 45.0, gps = 39.0)
        assertTrue(state.ratios.isEmpty())
    }

    @Test
    fun `accepts exactly at MIN_SPEED`() {
        val state = accept(SpeedCalibrationState(), ecu = 40.0, gps = 40.0)
        assertEquals(1, state.ratios.size)
    }

    // ---- gate: GPS accuracy ----

    @Test
    fun `rejects a GPS sample worse than MAX_ACCURACY`() {
        val state = accept(SpeedCalibrationState(), accuracy = 2.01)
        assertTrue(state.ratios.isEmpty())
    }

    @Test
    fun `accepts exactly at MAX_ACCURACY`() {
        val state = accept(SpeedCalibrationState(), accuracy = 2.0)
        assertEquals(1, state.ratios.size)
    }

    @Test
    fun `null accuracy is allowed - cannot judge it, do not reject it`() {
        val state = accept(SpeedCalibrationState(), accuracy = null)
        assertEquals(1, state.ratios.size)
    }

    // ---- gate: steadiness ----

    @Test
    fun `first sample is accepted despite no previous speed (steadiness skipped)`() {
        val state = accept(SpeedCalibrationState())
        assertEquals(1, state.ratios.size)
        assertEquals(goodEcu, state.prevEcuSpeedKmh!!, 0.0)
    }

    @Test
    fun `rejects a sample when ECU speed jumped more than the steadiness delta (acceleration)`() {
        // First sample establishes prev = 100. Second at 106 (jumped 6 > 5) — accelerating.
        var state = accept(SpeedCalibrationState(), ecu = 100.0, gps = 110.0)
        state = accept(state, ecu = 106.0, gps = 116.0)
        // Only the first sample was accepted.
        assertEquals(1, state.ratios.size)
        // prev still advanced to the observed 106, so calibration can resume when steady.
        assertEquals(106.0, state.prevEcuSpeedKmh!!, 0.0)
    }

    @Test
    fun `accepts a sample within the steadiness delta`() {
        var state = accept(SpeedCalibrationState(), ecu = 100.0, gps = 110.0)
        state = accept(state, ecu = 105.0, gps = 115.5) // moved 5 (== delta), ratio 1.10
        assertEquals(2, state.ratios.size)
    }

    @Test
    fun `steadiness gate recovers on the first steady sample after an acceleration run`() {
        // Cruise, then a hard acceleration burst (each step > delta), then steady cruise again.
        var state = accept(SpeedCalibrationState(), ecu = 60.0, gps = 66.0) // accepted (first)
        state = accept(state, ecu = 80.0, gps = 88.0) // +20 → rejected, prev := 80
        state = accept(state, ecu = 100.0, gps = 110.0) // +20 → rejected, prev := 100
        val afterBurst = state.ratios.size
        state = accept(state, ecu = 102.0, gps = 112.2) // +2 → steady again → accepted
        assertEquals(afterBurst + 1, state.ratios.size)
    }

    // ---- gate: ratio clamp ----

    @Test
    fun `rejects a ratio below the low clamp (GPS glitch reading slow)`() {
        // ecu 100, gps 79 → ratio 0.79 < 0.80.
        val state = accept(SpeedCalibrationState(), ecu = 100.0, gps = 79.0)
        assertTrue(state.ratios.isEmpty())
    }

    @Test
    fun `rejects a ratio above the high clamp (GPS glitch reading fast)`() {
        // ecu 100, gps 126 → ratio 1.26 > 1.25.
        val state = accept(SpeedCalibrationState(), ecu = 100.0, gps = 126.0)
        assertTrue(state.ratios.isEmpty())
    }

    @Test
    fun `accepts ratios exactly on both clamp edges`() {
        val low = accept(SpeedCalibrationState(), ecu = 100.0, gps = 80.0) // ratio 0.80
        assertEquals(1, low.ratios.size)
        val high = accept(SpeedCalibrationState(), ecu = 100.0, gps = 125.0) // ratio 1.25
        assertEquals(1, high.ratios.size)
    }

    // ---- ring buffer bound ----

    @Test
    fun `the ratio buffer is bounded at CAPACITY, dropping the oldest`() {
        val state = steadyRun(SpeedCalibration.CAPACITY + 10)
        assertEquals(SpeedCalibration.CAPACITY, state.ratios.size)
    }

    @Test
    fun `a fresh factor differs from a stale default once enough samples land`() {
        // Guards against factor() silently returning the default forever.
        val state = steadyRun(SpeedCalibration.MIN_SAMPLES)
        assertNotEquals(1.0, state.factor(1.0))
        assertEquals(goodRatio, state.factor(1.0), 1e-9)
    }
}
